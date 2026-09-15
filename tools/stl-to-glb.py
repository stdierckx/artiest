#!/usr/bin/env python3
"""Turn an STL scan into a .glb small enough for the reference library.

Museum scans are published as STL and they are enormous: a hyena skull off
Wikimedia Commons is ninety megabytes of triangles, which is more detail than a
pane eight hundred pixels across can show and more than `RefFiles.MAX_MODEL_BYTES`
will take. This reads one, thins it, and writes a .glb.

    tools/stl-to-glb.py skull.stl skull.glb --faces 120000 --up z

## How the thinning works

Vertex clustering. The bounding box is cut into a grid, every vertex in a cell
is replaced by the average of them, and triangles whose corners end up in the
same cell disappear. It is not the best decimation there is -- quadric error
metrics keep silhouettes better -- but it is about forty lines of numpy, it
never folds the mesh over itself, and at the sizes here the difference is
invisible: what survives is the form, which is the whole of what the model is
being looked at for.

The grid is chosen by measuring rather than by guessing. Cell size is halved or
doubled until the triangle count lands near the target, because how many cells a
scan actually occupies depends on how hollow it is and no formula knows that.

## Up

STL carries no units and no idea of which way is up; scanners and CAD both tend
to write Z-up and glTF is Y-up, so the default turns the model a quarter turn
about X. Pass `--up y` for a file that is already the right way round.
"""
import argparse
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glbwrite import facet, settle, write_glb  # noqa: E402


def read_stl(path):
    """The triangles, as an (n, 3, 3) array. Binary or ASCII, decided by content."""
    with open(path, "rb") as f:
        head = f.read(84)
        if len(head) < 84:
            raise SystemExit("%s: too short to be an STL" % path)
        count = struct.unpack("<I", head[80:84])[0]
        body = f.read()
    # A binary STL is exactly 84 + 50n bytes. An ASCII one usually starts with
    # "solid", but so do some binary ones written by bad exporters, so the
    # length is the test that decides.
    if len(body) == count * 50:
        raw = np.frombuffer(body, dtype=np.uint8).reshape(count, 50)
        floats = raw[:, :48].copy().view("<f4").reshape(count, 4, 3)
        return floats[:, 1:, :].astype(np.float64)
    return read_stl_ascii(path)


def read_stl_ascii(path):
    points = []
    with open(path, "r", errors="replace") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 4 and parts[0] == "vertex":
                points.append([float(parts[1]), float(parts[2]), float(parts[3])])
    if not points or len(points) % 3:
        raise SystemExit("%s: not an STL this can read" % path)
    return np.asarray(points, dtype=np.float64).reshape(-1, 3, 3)


def weld(triangles):
    """One vertex per distinct position, and triangles as indices into them."""
    flat = triangles.reshape(-1, 3)
    # Rounded before the unique, or float noise from the STL's repeated corners
    # leaves three vertices where the scanner meant one.
    span = max(float(flat.max() - flat.min()), 1e-9)
    keys = np.round(flat / (span * 1e-6)).astype(np.int64)
    _, first, inverse = np.unique(keys, axis=0, return_index=True, return_inverse=True)
    return flat[first], inverse.reshape(-1, 3)


def cluster(points, faces, cell):
    """Collapse every vertex in a cell of size `cell` onto their average."""
    grid = np.floor((points - points.min(axis=0)) / cell).astype(np.int64)
    _, inverse = np.unique(grid, axis=0, return_inverse=True)
    n = inverse.max() + 1
    sums = np.zeros((n, 3))
    np.add.at(sums, inverse, points)
    counts = np.bincount(inverse, minlength=n).reshape(-1, 1)
    moved = sums / counts

    remapped = inverse[faces]
    keep = (
        (remapped[:, 0] != remapped[:, 1])
        & (remapped[:, 1] != remapped[:, 2])
        & (remapped[:, 0] != remapped[:, 2])
    )
    return moved, remapped[keep]


def thin(points, faces, target):
    """Halve or double the cell until the triangle count lands near `target`."""
    if len(faces) <= target:
        return points, faces
    size = points.max(axis=0) - points.min(axis=0)
    cell = float(size.max()) / 256.0
    best = None
    for _ in range(14):
        moved, kept = cluster(points, faces, cell)
        if best is None or abs(len(kept) - target) < abs(len(best[1]) - target):
            best = (moved, kept)
        if len(kept) > target * 1.15:
            cell *= 1.35
        elif len(kept) < target * 0.7:
            cell /= 1.2
        else:
            return moved, kept
    return best


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("stl")
    ap.add_argument("glb")
    ap.add_argument("--faces", type=int, default=120_000, help="triangles to aim for")
    ap.add_argument("--up", choices=["z", "y"], default="z", help="which axis is up in the STL")
    ap.add_argument("--name", default=None)
    ap.add_argument(
        "--flat",
        action="store_true",
        help="one normal per triangle: the planes show as planes",
    )
    args = ap.parse_args()

    triangles = read_stl(args.stl)
    points, faces = weld(triangles)
    before = len(faces)
    points, faces = thin(points, faces, args.faces)

    if args.up == "z":
        # A quarter turn about X: z-up becomes y-up, and what was +y goes back.
        points = np.stack([points[:, 0], points[:, 2], -points[:, 1]], axis=1)

    # Centred and scaled, so every model in the library arrives at the same
    # size whatever units it was scanned in. See `glbwrite.settle`.
    points = settle(points)

    norms = None
    if args.flat:
        points, faces, norms = facet(points, faces)

    name = args.name or args.stl.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    size = write_glb(args.glb, points, faces, name, norms)
    print(
        "%s -> %s  %d -> %d triangles, %.1f MB"
        % (args.stl, args.glb, before, len(faces), size / 1e6)
    )


if __name__ == "__main__":
    main()
