#!/usr/bin/env python3
"""
Pen-to-photon latency from a 240 fps clip of a zigzag stroke.

W16's instrument. It answers the one question nothing inside the process can:
how long after the pen moves does the light change. Everything upstream of the
panel is measured by the app itself — see the `latency` line on the readout and
`StrokeBuilderTest` — and the difference between that sum and this number is
SurfaceFlinger plus the panel.

**The measurement is a time offset, not a distance.** At each apex of a zigzag
the pen is momentarily stationary, so the ink's apex is reached exactly one
latency later. That removes both of the error-prone terms a straight-stroke
`gap / speed` measurement has: no gap to estimate between two touching dark
objects, and no speed. Four apexes in one stroke give four independent readings.

**The frame rate is calibrated from inside the clip and not taken on trust.**
The ink advances in discrete steps, one per panel refresh, so the ink-growth
series carries the panel's own refresh rate as a clock. The dominant period of
that series, times the refresh rate, is the true capture rate. On the take this
was written against it read 2.690 frames x 90 Hz = 242.1 fps against a claimed
240 — worth checking, because a phone that silently captured at 120 would put
the answer out by a factor of two with nothing else looking wrong.

Two things it does not do, both deliberate. It does not find the nib: it tracks
the leftmost sliver of the pen's dark blob, which is a *rigid* feature of the
pen, and a rigid feature reverses at the same instant the nib does — the unknown
offset between them cancels in a reversal measurement. And it does not try to
separate ink from pen where they touch, which is the failure that sank the
straight-stroke attempt: mask subtraction cannot split two adjacent dark objects,
and the ink's leading edge under the nib is exactly that case.

The ROI and thresholds below were tuned against one take. They are the first
thing to check on a new one.

Usage:
    ffmpeg -i clip.mp4 -q:v 3 frames/f%04d.jpg
    python3 tools/latency-from-video.py frames --refresh 90
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image

# The page, inside the bezel and below the toolbar. Retune per take.
ROI = (60, 700, 60, 1020)          # y0, y1, x0, x1
DARK = 0.5                         # fraction of the page's median brightness
PEN_ERODE = 4                      # kills the thin ink line, keeps the pen
PEN_DILATE = 5
INK_CLEARANCE = 10                 # ink pixels this close to the pen are fringe


def frames(path):
    fs = sorted(glob.glob(os.path.join(path, "*.jpg")))
    if not fs:
        sys.exit(f"no frames in {path}")
    return fs


def load(f):
    y0, y1, x0, x1 = ROI
    return np.asarray(Image.open(f).convert("L"), dtype=np.float32)[y0:y1, x0:x1]


def morph(m, r, op):
    """Erode or dilate without wraparound. `np.roll` wraps, and a wrapped mask
    puts the frame's opposite edge into the middle of the page."""
    pad = np.pad(m, r, constant_values=(op == "e"))
    out = np.ones_like(m) if op == "e" else np.zeros_like(m)
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            w = pad[r + dy:r + dy + m.shape[0], r + dx:r + dx + m.shape[1]]
            out = (out & w) if op == "e" else (out | w)
    return out


def signals(files, lo, hi):
    """Per frame: the pen's leftmost-sliver y, the ink's topmost y, the ink area."""
    h, w = ROI[1] - ROI[0], ROI[3] - ROI[2]
    yy, xx = np.mgrid[0:h, 0:w]
    out = []
    for i in range(lo, hi):
        img = load(files[i - 1])
        dark = img < np.median(img) * DARK
        pen = morph(morph(dark, PEN_ERODE, "e"), PEN_DILATE, "d")
        ink = dark & ~morph(pen, INK_CLEARANCE, "d")
        nib_y = np.nan
        if pen.sum() >= 400:
            px, py = xx[pen], yy[pen]
            nib_y = float(py[px <= px.min() + 6].mean())
        ink_top = float(yy[ink].min()) if ink.sum() > 25 else np.nan
        out.append((i, nib_y, ink_top, int(ink.sum())))
    return np.array(out)


def capture_fps(area, refresh_hz):
    """The ink steps once per panel refresh; find that period and scale it."""
    d = np.diff(area)
    d = d - d.mean()
    best = (0.0, -1.0)
    for p in np.arange(2.0, 6.001, 0.01):
        mag = abs((d * np.exp(-2j * np.pi * np.arange(len(d)) / p)).sum())
        if mag > best[1]:
            best = (p, mag)
    return best[0], best[0] * refresh_hz


def minima(v, guard=6):
    """Frames where a signal is at a local minimum — the pen at a zigzag apex."""
    out = []
    for i in range(guard, len(v) - guard):
        if np.isnan(v[i]):
            continue
        w = v[i - guard:i + guard + 1]
        if np.nanmin(w) == v[i] and v[i] < np.nanmax(w) - 20:
            if not out or i - out[-1] > guard:
                out.append(i)
    return out


def smooth5(v):
    """Median of five. The ink-top series carries dropouts from JPEG noise, and
    they come in bursts of two, which a median of three does not remove."""
    out = v.copy()
    for i in range(2, len(v) - 2):
        out[i] = np.median(v[i - 2:i + 3])
    return out


def settles(top, start, window=5):
    """When the ink finishes climbing to the apex the pen reached at `start`.

    Two phases, and the first is why a plain flatness test does not work: after
    the pen turns, the ink's top is still sitting at the *previous* apex and is
    perfectly flat there. So wait for the descent to begin, then take the frame
    that first reaches the plateau it descends to — not the frame where the
    series happens to look flat, which arrives a frame or two early and is worth
    4 ms here.

    `base` is a median of the frames before the apex rather than the value at
    it, because the apex frame itself can be one of the dropouts.
    """
    base = np.nanmedian(top[max(0, start - 5):start + 1])
    if np.isnan(base):
        return None
    i = start
    while i < len(top) - 2 and not (top[i] < base - 3):
        i += 1                                   # still at the previous apex
    if i >= len(top) - 2:
        return None
    # Walk the descent and stop once it has been flat for `window` frames. A
    # fixed lookahead cannot work: anything long enough to cover a slow climb
    # also reaches the *next* apex, whose lower plateau then wins.
    best, best_f = top[i], i
    j = i
    while j < len(top):
        # 1.5 px, not 0: the ink line is three to four pixels wide and its
        # measured top wanders a pixel between frames. A tighter test keeps
        # chasing that wander and lands a frame or two late.
        if np.isfinite(top[j]) and top[j] < best - 1.5:
            best, best_f = top[j], j
        elif j - best_f >= window:
            break
        j += 1
    return best_f


def subframe_min(v, i):
    """The turn is between frames. A parabola through the three samples around
    the minimum recovers where, which halves the quantisation error on a
    measurement whose whole content is a frame count."""
    if i <= 0 or i >= len(v) - 1:
        return float(i)
    a, b, c = v[i - 1], v[i], v[i + 1]
    d = a - 2 * b + c
    if not np.isfinite(d) or abs(d) < 1e-6:
        return float(i)
    return i + 0.5 * (a - c) / d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames_dir")
    ap.add_argument("--refresh", type=float, default=90.0, help="panel Hz during the take")
    ap.add_argument("--from", dest="lo", type=int, default=130)
    ap.add_argument("--to", dest="hi", type=int, default=262)
    a = ap.parse_args()

    fs = frames(a.frames_dir)
    sig = signals(fs, a.lo, min(a.hi, len(fs)))
    idx, nib, area = sig[:, 0], sig[:, 1], sig[:, 3]
    top = smooth5(sig[:, 2])

    period, fps = capture_fps(area, a.refresh)
    dt = 1000.0 / fps
    print(f"ink steps every {period:.3f} frames at {a.refresh:g} Hz "
          f"-> capture {fps:.1f} fps ({dt:.3f} ms a frame)")

    print(f"\n{'apex':>4} {'pen f':>7} {'ink f':>7} {'d frames':>9} {'latency ms':>11}")
    lats = []
    for m in minima(nib):
        s = settles(top, m)
        if s is None:
            continue
        pen_f = subframe_min(nib, m)
        d = s - pen_f
        lats.append(d * dt)
        print(f"{len(lats):4d} {idx[0]+pen_f:7.1f} {idx[s]:7.0f} {d:9.2f} {d*dt:11.1f}")
    if lats:
        v = np.array(lats)
        print(f"\nn={len(v)}  median {np.median(v):.1f} ms  "
              f"mean {v.mean():.1f}  range {v.min():.1f}-{v.max():.1f}")


if __name__ == "__main__":
    main()
