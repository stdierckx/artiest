#!/usr/bin/env python3
"""The seven solids every drawing course starts with, as `.glb` files.

    tools/primitives-to-glb.py out/

Lr12. A reference library full of skulls and busts is missing the first half of
the course. Before anyone draws a head they draw a sphere, a cube and a
cylinder, for a reason that does not stop being true later: those are the forms
a head is *made of*, and the light on them is the light on it. A sphere teaches
the terminator, a cube teaches that three planes of one colour are three
values, a cylinder teaches how a curved surface turns away from a light, a cone
teaches the ellipse, and a torus teaches all of it at once on a form with a
hole in it.

These are generated rather than downloaded, which is worth saying: there is no
licence to honour on a sphere, nobody to credit, and nothing to go stale.
Thirty lines of trigonometry is a better source for a cylinder than anybody's
model library.

## Smooth where it curves, faceted where it does not

Each shape says which of its own parts are round. A sphere is entirely smooth
and its normals are exact -- the normal at a point of a unit sphere *is* that
point -- so it has no faceting at any zoom whatever the triangle count. A cube
is entirely flat. A cylinder is both, and the seam between them is what makes
it read as turned rather than as melted: the side is smooth, the caps are flat,
and the rim between them is a hard edge because the two do not share vertices.

## Size and place

Every shape comes out centred on the origin with its longest half-side at one,
which is the convention the rest of the library follows. See `glbwrite.settle`
for why that matters to the pane.
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glbwrite import alive, facet, outward, settle, write_glb  # noqa: E402

FLAT = "flat"


def ring_of(segments, radius=1.0, y=0.0):
    theta = np.linspace(0.0, 2.0 * np.pi, segments, endpoint=False)
    return np.stack([
        np.cos(theta) * radius,
        np.full(segments, y),
        np.sin(theta) * radius,
    ], axis=1)


def grid_faces(rows, cols):
    """Two triangles per quad over a rows x cols lattice, wrapping in cols."""
    r = np.arange(rows - 1)[:, None]
    c = np.arange(cols)[None, :]
    c1 = (c + 1) % cols
    a = r * cols + c
    b = r * cols + c1
    d = (r + 1) * cols + c
    e = (r + 1) * cols + c1
    lower = np.stack([a, d, e], axis=-1).reshape(-1, 3)
    upper = np.stack([a, e, b], axis=-1).reshape(-1, 3)
    return np.concatenate([lower, upper]).astype(np.int64)


def fan(hub, rim_at, segments, upward):
    """A cap: one triangle per segment, all meeting at the middle."""
    out = []
    for c in range(segments):
        c1 = (c + 1) % segments
        if upward:
            out.append((hub, rim_at + c, rim_at + c1))
        else:
            out.append((hub, rim_at + c1, rim_at + c))
    return out


def sphere(rings=48, segments=96):
    phi = np.linspace(0.0, np.pi, rings)
    theta = np.linspace(0.0, 2.0 * np.pi, segments, endpoint=False)
    P, T = np.meshgrid(phi, theta, indexing="ij")
    points = np.stack([
        (np.sin(P) * np.cos(T)).ravel(),
        np.cos(P).ravel(),
        (np.sin(P) * np.sin(T)).ravel(),
    ], axis=1)
    return points, grid_faces(rings, segments), points.copy()


def egg(rings=48, segments=96):
    """A sphere fatter below than above. Every head begins as one of these."""
    points, faces, _ = sphere(rings, segments)
    height = points[:, 1]
    waist = 0.80 - 0.15 * height
    points = points * np.stack([waist, np.ones_like(height), waist], axis=1)
    return points, faces, None


def cube():
    corners = np.array([
        [-1, -1, -1], [1, -1, -1], [1, 1, -1], [-1, 1, -1],
        [-1, -1, 1], [1, -1, 1], [1, 1, 1], [-1, 1, 1],
    ], dtype=np.float64)
    quads = [
        (0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
        (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5),
    ]
    faces = []
    for a, b, c, d in quads:
        faces.append((a, b, c))
        faces.append((a, c, d))
    return corners, np.array(faces, dtype=np.int64), FLAT


def cylinder(segments=96, height=1.0, radius=0.62):
    top = ring_of(segments, radius, height)
    bottom = ring_of(segments, radius, -height)
    radial = ring_of(segments, 1.0, 0.0)

    points = [top, bottom, top, np.array([[0.0, height, 0.0]]),
              bottom, np.array([[0.0, -height, 0.0]])]
    norms = [radial, radial,
             np.tile([0.0, 1.0, 0.0], (segments + 1, 1)),
             np.tile([0.0, -1.0, 0.0], (segments + 1, 1))]
    faces = []
    for c in range(segments):
        c1 = (c + 1) % segments
        faces.append((c, segments + c, segments + c1))
        faces.append((c, segments + c1, c1))
    lid = 2 * segments
    faces += fan(lid + segments, lid, segments, True)
    floor = lid + segments + 1
    faces += fan(floor + segments, floor, segments, False)
    return np.concatenate(points), np.array(faces, dtype=np.int64), np.concatenate(norms)


def cone(segments=96, height=1.0, radius=0.72):
    """Smooth down the side, flat underneath.

    The apex is given the average of the normals around it, which is a lie --
    a cone has no normal at its point -- but it is the lie that shades
    correctly everywhere else, and the alternative is a pinhole of black.
    """
    rim = ring_of(segments, radius, -height)
    slant = np.arctan2(radius, 2.0 * height)
    side = ring_of(segments, np.cos(slant), np.sin(slant))
    points = [rim, np.array([[0.0, height, 0.0]]),
              rim, np.array([[0.0, -height, 0.0]])]
    norms = [side, np.array([[0.0, 1.0, 0.0]]),
             np.tile([0.0, -1.0, 0.0], (segments + 1, 1))]
    faces = [(segments, c, (c + 1) % segments) for c in range(segments)]
    floor = segments + 1
    faces += fan(floor + segments, floor, segments, False)
    return np.concatenate(points), np.array(faces, dtype=np.int64), np.concatenate(norms)


def pyramid(height=1.0, half=0.80):
    points = np.array([
        [0.0, height, 0.0],
        [-half, -height, -half], [half, -height, -half],
        [half, -height, half], [-half, -height, half],
    ], dtype=np.float64)
    faces = np.array([
        (0, 1, 2), (0, 2, 3), (0, 3, 4), (0, 4, 1), (1, 4, 3), (1, 3, 2),
    ], dtype=np.int64)
    return points, faces, FLAT


def torus(rings=48, segments=96, thickness=0.34):
    u = np.linspace(0.0, 2.0 * np.pi, segments, endpoint=False)
    v = np.linspace(0.0, 2.0 * np.pi, rings, endpoint=False)
    V, U = np.meshgrid(v, u, indexing="ij")
    radial = np.stack([np.cos(U), np.zeros_like(U), np.sin(U)], axis=-1)
    points = radial * (1.0 + thickness * np.cos(V))[..., None]
    points[..., 1] = thickness * np.sin(V)
    norms = radial * np.cos(V)[..., None]
    norms[..., 1] = np.sin(V)
    # The lattice wraps in both directions, so the row after the last is the
    # first: build one row too many and fold the indices back down.
    faces = grid_faces(rings + 1, segments) % (rings * segments)
    return points.reshape(-1, 3), faces, norms.reshape(-1, 3)


SHAPES = [
    ("sphere", "Sphere", sphere),
    ("cube", "Cube", cube),
    ("cylinder", "Cylinder", cylinder),
    ("cone", "Cone", cone),
    ("pyramid", "Pyramid", pyramid),
    ("torus", "Torus", torus),
    ("egg", "Egg", egg),
]


def main():
    ap = argparse.ArgumentParser(description="Write the drawing primitives as .glb files.")
    ap.add_argument("out", help="directory to write the .glb files into")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for slug, label, build in SHAPES:
        points, faces, norms = build()
        # Measured, not trusted: see `glbwrite.outward`. Five of these seven
        # were wound inside out when they were first written, and a solid
        # wound inside out is one whose front is culled away.
        faces = alive(points, outward(points, faces))
        if isinstance(norms, str):
            points, faces, norms = facet(points, faces)
        points = settle(points)
        if norms is not None:
            length = np.linalg.norm(norms, axis=1, keepdims=True)
            length[length == 0] = 1.0
            norms = norms / length
        path = os.path.join(args.out, slug + ".glb")
        size = write_glb(path, points, faces, label, norms)
        print("  %-10s %6d faces  %7.1f kB" % (slug, len(faces), size / 1e3))


if __name__ == "__main__":
    main()
