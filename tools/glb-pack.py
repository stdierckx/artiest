#!/usr/bin/env python3
"""Pack a .gltf and the files beside it into one .glb.

The reference library takes .glb and only .glb, because a .glb is one file and
a .gltf is a JSON file that names other files -- see `RefKind.MODEL`. Most of
the free model libraries worth using (Poly Haven, Sketchfab's CC0 filter) hand
out .gltf, so this is the step between them and the tablet.

    tools/glb-pack.py model.gltf model.glb

It inlines the .bin and every image the .gltf names, in place, with no
re-encoding: the JPEGs and PNGs are copied byte for byte, so nothing about how
the model looks changes. Only external resources are inlined; a .gltf that
already embeds everything as data: URIs is copied through unchanged.
"""
import base64
import json
import os
import struct
import sys

JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942

MIME = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".ktx2": "image/ktx2",
}


def read_uri(base, uri):
    """The bytes a glTF uri names: a data: URI or a file beside the .gltf."""
    if uri.startswith("data:"):
        return base64.b64decode(uri.split(",", 1)[1])
    # Percent-encoding is legal in a glTF uri and common when a name has a
    # space in it. Nothing else about the path is interpreted.
    from urllib.parse import unquote
    return open(os.path.join(base, unquote(uri)), "rb").read()


def pad4(blob, filler=b"\x00"):
    while len(blob) % 4:
        blob += filler
    return blob


def pack(gltf_path, out_path):
    base = os.path.dirname(os.path.abspath(gltf_path))
    doc = json.load(open(gltf_path))

    blob = bytearray()
    views = doc.setdefault("bufferViews", [])

    # The buffers first and in order, so every bufferView that already exists
    # keeps the offset it was written with.
    offsets = []
    for buffer in doc.get("buffers", []):
        offsets.append(len(blob))
        uri = buffer.get("uri")
        blob += read_uri(base, uri) if uri else b""
        while len(blob) % 4:
            blob += b"\x00"
    for view in views:
        view["byteOffset"] = view.get("byteOffset", 0) + offsets[view.get("buffer", 0)]
        view["buffer"] = 0

    inlined = 0
    for image in doc.get("images", []):
        uri = image.get("uri")
        if not uri:
            continue
        data = read_uri(base, uri)
        offset = len(blob)
        blob += data
        while len(blob) % 4:
            blob += b"\x00"
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(data)})
        image["bufferView"] = len(views) - 1
        image.setdefault(
            "mimeType", MIME.get(os.path.splitext(uri)[1].lower(), "image/png")
        )
        image.pop("uri")
        inlined += 1

    doc["buffers"] = [{"byteLength": len(blob)}]

    text = pad4(json.dumps(doc, separators=(",", ":")).encode("utf-8"), b" ")
    binary = pad4(bytes(blob))
    total = 12 + 8 + len(text) + 8 + len(binary)

    with open(out_path, "wb") as out:
        out.write(b"glTF")
        out.write(struct.pack("<II", 2, total))
        out.write(struct.pack("<II", len(text), JSON_CHUNK))
        out.write(text)
        out.write(struct.pack("<II", len(binary), BIN_CHUNK))
        out.write(binary)

    print(
        "%s -> %s  %.1f MB, %d image%s inlined"
        % (gltf_path, out_path, total / 1e6, inlined, "" if inlined == 1 else "s")
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    pack(sys.argv[1], sys.argv[2])
