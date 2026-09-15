#!/usr/bin/env python3
"""Writing one mesh into a `.glb`, shared by the converters beside this file.

Lr12. Both `stl-to-glb.py` and `primitives-to-glb.py` end at the same place: a
set of points, a set of triangles into them, and a single-mesh glTF container
with the geometry inlined. This is that ending, in one copy.

Nothing here knows what the mesh is of. It writes positions, normals, indices
and one flat material, because that is what the reference pane reads -- and it
writes the material deliberately rather than leaving it out: a primitive with no
material at all is plain white by the glTF spec, and `wearsItsOwnSurface` would
have to guess. With this material present and no image beside it, the pane knows
exactly what it has: a mesh that brought no surface, which it opens in stone.
"""
import json
import struct

import numpy as np

JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942


def smooth_normals(points, faces):
    """Area-weighted vertex normals: the shading a curved form wants."""
    a = points[faces[:, 0]]
    b = points[faces[:, 1]]
    c = points[faces[:, 2]]
    face = np.cross(b - a, c - a)
    out = np.zeros_like(points)
    for i in range(3):
        np.add.at(out, faces[:, i], face)
    length = np.linalg.norm(out, axis=1, keepdims=True)
    length[length == 0] = 1.0
    return out / length


def facet(points, faces):
    """Split every shared vertex, so each triangle gets its own flat normal.

    What makes a cube read as a cube and a planar head read as planes. Smooth
    normals average across an edge, which is right for a sphere and is exactly
    wrong for a form whose whole subject is that it has edges: the plane
    changes and the shading has to change with it, hard, on the line between.

    Costs three vertices a triangle instead of one, which for a few hundred
    faces is nothing and is why this is not the default.
    """
    corners = points[faces.reshape(-1)]
    faces = np.arange(len(corners), dtype=np.int64).reshape(-1, 3)
    a, b, c = corners[0::3], corners[1::3], corners[2::3]
    face = np.cross(b - a, c - a)
    length = np.linalg.norm(face, axis=1, keepdims=True)
    length[length == 0] = 1.0
    face = face / length
    norms = np.repeat(face, 3, axis=0)
    return corners, faces, norms


def outward(points, faces):
    """Turn the triangles the right way out, by measuring rather than by care.

    A closed mesh wound anticlockwise seen from outside has a positive signed
    volume, and one wound the other way has the same volume negative -- the
    divergence theorem, and it does not care where the origin is. So instead of
    getting the corner order right by hand in seven builders and being wrong in
    five of them, which is what happened, each shape is measured once and
    reversed if it came out inside out.

    It matters because Filament drops back faces: a solid wound inwards is not
    a solid with odd shading, it is a solid you can see the inside of.
    """
    a = points[faces[:, 0]]
    b = points[faces[:, 1]]
    c = points[faces[:, 2]]
    volume = np.einsum("ij,ij->i", a, np.cross(b, c)).sum() / 6.0
    return faces[:, ::-1] if volume < 0 else faces


def alive(points, faces, least=1e-12):
    """Drop the triangles with no area in them.

    A UV sphere has a row of them at each pole, where every vertex of the ring
    is the same point. They draw nothing, so they are not a bug -- but they are
    two hundred triangles of index buffer and two hundred degenerate normals
    for a shape whose whole selling point is that it is exact.
    """
    a = points[faces[:, 0]]
    b = points[faces[:, 1]]
    c = points[faces[:, 2]]
    area = np.linalg.norm(np.cross(b - a, c - a), axis=1)
    return faces[area > least]


def settle(points, reach=1.0):
    """Centre the mesh on the origin and scale its longest half-side to [reach].

    Every model in the library arrives in different units -- museum scans in
    millimetres, exports in metres, generated shapes in whatever they were
    written in -- and the pane frames by the bounding box, so the size a file
    claims never shows. What it does change is everything measured in world
    units: the contour spacing, the stone's grain, the occlusion radius. Those
    are all shares of the model's own half-size for that reason, and this makes
    the half-size the same number for everything that goes through it.
    """
    low = points.min(axis=0)
    high = points.max(axis=0)
    points = points - (low + high) / 2.0
    longest = float(np.abs(points).max())
    if longest > 0:
        points = points * (reach / longest)
    return points


def write_glb(path, points, faces, name, norms=None):
    """One mesh, one material, one buffer. Returns the file size in bytes."""
    points = np.asarray(points, dtype="<f4")
    if norms is None:
        norms = smooth_normals(points.astype(np.float64), faces)
    norms = np.asarray(norms, dtype="<f4")
    indices = np.asarray(faces, dtype="<u4")

    blob = bytearray()

    def add(array):
        start = len(blob)
        blob.extend(array.tobytes())
        while len(blob) % 4:
            blob.append(0)
        return start, array.nbytes

    pos_at, pos_len = add(points)
    nor_at, nor_len = add(norms)
    idx_at, idx_len = add(indices)

    low = points.min(axis=0).tolist()
    high = points.max(axis=0).tolist()

    doc = {
        "asset": {"version": "2.0", "generator": "artiest glbwrite"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": name}],
        "meshes": [
            {
                "name": name,
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "NORMAL": 1},
                        "indices": 2,
                        "material": 0,
                    }
                ],
            }
        ],
        "materials": [
            {
                "name": "plaster",
                "pbrMetallicRoughness": {
                    "baseColorFactor": [0.82, 0.80, 0.77, 1.0],
                    "metallicFactor": 0.0,
                    "roughnessFactor": 0.85,
                },
            }
        ],
        "accessors": [
            {
                "bufferView": 0, "componentType": 5126, "count": len(points),
                "type": "VEC3", "min": low, "max": high,
            },
            {"bufferView": 1, "componentType": 5126, "count": len(norms), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5125, "count": indices.size, "type": "SCALAR"},
        ],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_at, "byteLength": pos_len, "target": 34962},
            {"buffer": 0, "byteOffset": nor_at, "byteLength": nor_len, "target": 34962},
            {"buffer": 0, "byteOffset": idx_at, "byteLength": idx_len, "target": 34963},
        ],
        "buffers": [{"byteLength": len(blob)}],
    }

    text = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    while len(text) % 4:
        text += b" "
    binary = bytes(blob)
    total = 12 + 8 + len(text) + 8 + len(binary)
    with open(path, "wb") as out:
        out.write(b"glTF")
        out.write(struct.pack("<II", 2, total))
        out.write(struct.pack("<II", len(text), JSON_CHUNK))
        out.write(text)
        out.write(struct.pack("<II", len(binary), BIN_CHUNK))
        out.write(binary)
    return total
