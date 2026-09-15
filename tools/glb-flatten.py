#!/usr/bin/env python3
"""Flatten a scene-graph `.glb` into the one-mesh kind the reference library holds.

    tools/glb-flatten.py downloaded.glb model.glb --faces 120000 --flat

Lr12. A model exported from a modelling site is a scene: two dozen meshes under
two dozen nodes, each with its own transform, and often a hundred megabytes of
it. `RefFiles` will take a `.glb` of any shape, but a reference pane wants one
object at a size a tablet can hold, and a plane study in particular wants its
own normals rather than the ones an exporter guessed at.

So this walks the scene, multiplies every mesh's points by the transform of the
node it hangs under, drops everything that is not a triangle, and hands the
result to the same thinning and the same writer `stl-to-glb.py` uses. Textures
and materials are left behind on purpose: the pane dresses a bare model in
marble, bronze or terracotta, and a scan's own surface is the one thing a plane
study must not have.
"""
import argparse
import json
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glbwrite import alive, facet, outward, settle, write_glb  # noqa: E402

_stl = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stl-to-glb.py")
_ns = {"__file__": _stl, "__name__": "_stl_to_glb"}
exec(compile(open(_stl).read(), _stl, "exec"), _ns)
thin = _ns["thin"]

COMPONENT = {5120: "<i1", 5121: "<u1", 5122: "<i2", 5123: "<u2", 5125: "<u4", 5126: "<f4"}
COUNT = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_glb(path):
    data = open(path, "rb").read()
    if data[:4] != b"glTF":
        raise SystemExit("%s is not a .glb" % path)
    off, doc, blob = 12, None, b""
    while off < len(data):
        length, kind = struct.unpack_from("<II", data, off)
        off += 8
        chunk = data[off:off + length]
        off += length
        if kind == 0x4E4F534A:
            doc = json.loads(chunk.decode("utf-8"))
        else:
            blob = chunk
    return doc, blob


def read_accessor(doc, blob, index):
    acc = doc["accessors"][index]
    view = doc["bufferViews"][acc["bufferView"]]
    dtype = np.dtype(COMPONENT[acc["componentType"]])
    wide = COUNT[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride") or dtype.itemsize * wide
    raw = np.frombuffer(blob, dtype=np.uint8, count=acc["count"] * stride, offset=start)
    raw = raw.reshape(acc["count"], stride)[:, : dtype.itemsize * wide]
    return np.ascontiguousarray(raw).view(dtype).reshape(acc["count"], wide)


def node_matrix(node):
    if "matrix" in node:
        # glTF matrices are column-major; numpy wants them the other way round.
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    out = np.eye(4)
    if "scale" in node:
        out = np.diag(list(node["scale"]) + [1.0]) @ out
    if "rotation" in node:
        x, y, z, w = node["rotation"]
        turn = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0],
            [0, 0, 0, 1],
        ])
        out = turn @ out
    if "translation" in node:
        move = np.eye(4)
        move[:3, 3] = node["translation"]
        out = move @ out
    return out


def gather(doc, blob):
    """Every triangle in the scene, in one set of points and faces."""
    points, faces, at = [], [], 0
    scene = doc["scenes"][doc.get("scene", 0)]

    def walk(index, parent):
        nonlocal at
        node = doc["nodes"][index]
        here = parent @ node_matrix(node)
        if "mesh" in node:
            for prim in doc["meshes"][node["mesh"]]["primitives"]:
                if prim.get("mode", 4) != 4 or "POSITION" not in prim["attributes"]:
                    continue
                p = read_accessor(doc, blob, prim["attributes"]["POSITION"]).astype(np.float64)
                p = (here[:3, :3] @ p.T).T + here[:3, 3]
                if "indices" in prim:
                    idx = read_accessor(doc, blob, prim["indices"]).reshape(-1).astype(np.int64)
                else:
                    idx = np.arange(len(p), dtype=np.int64)
                points.append(p)
                faces.append(idx.reshape(-1, 3) + at)
                at += len(p)
        for child in node.get("children", []):
            walk(child, here)

    for index in scene.get("nodes", []):
        walk(index, np.eye(4))
    if not points:
        raise SystemExit("no triangles in that file")
    return np.concatenate(points), np.concatenate(faces)


def main():
    ap = argparse.ArgumentParser(description="Flatten a .glb scene into one mesh.")
    ap.add_argument("source")
    ap.add_argument("glb")
    ap.add_argument("--faces", type=int, default=120_000, help="triangles to aim for")
    ap.add_argument("--flat", action="store_true", help="one normal per triangle")
    ap.add_argument("--up", choices=["y", "z"], default="y")
    ap.add_argument("--name", default=None)
    args = ap.parse_args()

    doc, blob = read_glb(args.source)
    points, faces = gather(doc, blob)
    before = len(faces)
    points, faces = thin(points, faces, args.faces)
    if args.up == "z":
        points = np.stack([points[:, 0], points[:, 2], -points[:, 1]], axis=1)
    faces = alive(points, outward(points, faces))
    points = settle(points)

    norms = None
    if args.flat:
        points, faces, norms = facet(points, faces)
    name = args.name or os.path.basename(args.source).rsplit(".", 1)[0]
    size = write_glb(args.glb, points, faces, name, norms)
    print("%s -> %s  %d -> %d triangles, %.1f MB"
          % (args.source, args.glb, before, len(faces), size / 1e6))


if __name__ == "__main__":
    main()
