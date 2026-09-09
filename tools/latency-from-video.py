#!/usr/bin/env python3
"""
Pen-to-photon latency from a 240 fps clip of a zigzag stroke.

W16's instrument, rebuilt in Phase 2's W0. It answers the one question nothing
inside the process can: how long after the pen moves does the light change.
Everything upstream of the panel is measured by the app itself — see the
`latency` line on the readout and `StrokeBuilderTest` — and the difference
between that sum and this number is SurfaceFlinger plus the panel.

**The measurement is a time offset, not a distance.** At each apex of a zigzag
the pen is momentarily stationary, so the ink's apex is reached exactly one
latency later. That removes both of the error-prone terms a straight-stroke
`gap / speed` measurement has: no gap to estimate between two touching dark
objects, and no speed. Several apexes in one stroke give several independent
readings.

**The frame rate is calibrated from inside the clip and not taken on trust.**
The ink advances in discrete steps, one per panel refresh, so the ink-growth
series carries the panel's own refresh rate as a clock. The dominant period of
that series, times the refresh rate, is the true capture rate — worth doing,
because a phone that silently captured at 120 would put the answer out by a
factor of two with nothing else looking wrong.

Two things it does not do, both deliberate. It does not find the nib: it tracks
the leftmost sliver of the pen's dark blob, which is a *rigid* feature of the
pen, and a rigid feature reverses at the same instant the nib does — the unknown
offset between them cancels in a reversal measurement. And it does not try to
separate ink from pen where they touch, which is the failure that sank the
straight-stroke attempt: mask subtraction cannot split two adjacent dark
objects, and the ink's leading edge under the nib is exactly that case.

W0 REBUILD — WHAT CHANGED AND WHY
---------------------------------
The first version carried an ROI and five pixel constants tuned to one take, and
run C proved they do not transfer: reframed closer, the page ended 370 px higher
and the tool reported a stuck nib. Fixing that turned up something worse than a
framing bug.

**The answer depended on the ink threshold, and the tool did not say so.** Swept
across `DARK` on take 2, the same clip reads 44.2, 49.2, 44.3, 44.6 and 45.2 ms
between 0.30 and 0.50 — and then, from 0.55 up, produces *negative* latencies and
115 ms readings mixed in with plausible ones, which the median quietly absorbed
into a number that still looked like an answer. A published 45.2 ms was one
sample of that distribution, quoted to a precision the method does not have.

So the tool no longer picks a threshold. It **sweeps** the threshold, throws away
every pass that fails a physical check — no negative latency, nothing beyond a
dozen refreshes, at least three apexes, and the apexes agreeing to within about
a refresh and a half — and reports the consensus of the passes that survive,
with their range. On take 2 that is 44.6 ms from the original hand-tuned window
and 44.3 ms from the automatically found page: the framing stopped mattering,
which was the point, and the number now arrives with an honest spread.

Everything about the physics is unchanged — the reversal method, the
nib-as-leftmost-sliver trick, the two-phase settle walk, the sub-frame parabola
and the self-calibrated frame rate were all validated against a known answer and
are untouched. Two mechanical fixes came out of the rebuild: morphology is now
separable (bit-identical, six times faster) and each frame is decoded once for
the whole sweep instead of once per threshold.

**A regression this rebuild introduced and caught, worth keeping written down.**
Making the threshold a calibrated constant instead of a per-frame value broke it
badly: the phone auto-exposes, the page drifts several grey levels as the hand
crosses it, and one fixed level lets the pen leak into the ink mask exactly at
the apex. That put a three-frame spike into the ink-top series, which `smooth5`
cannot remove — it is a median of five — and `settles` locked onto the spike and
reported ink arriving *before* the pen. The clip calibrates the fraction; each
frame supplies its own level.

W0 FINISH — THE WINDOW, THE CEILING, AND ONE READING AT A TIME
--------------------------------------------------------------
The rebuild left two things unfinished and both are now done.

**The analysis window comes from the nib's direction reversals.** Approach,
lift-off and drift are monotonic; a zigzag is the only part of a clip where the
direction of travel flips repeatedly. So the signal is the local density of sign
changes in the nib's vertical velocity. See [drawing_window].

**A dark blob covering a fifth of the page is a hand, not a pen**, and where the
blob is that large the ink/pen separation is not measuring anything. See
`PEN_MAX_PAGE`, whose value was swept on both clips with a known answer rather
than chosen.

**The physical checks reject readings, not whole thresholds.** See [admissible].
Eight of nine thresholds were being discarded entire because one apex came back
negative while the other four agreed at 45 ms.

Unattended, from an automatically found page and window, take 2 now reads
45.1 ms over 5 of 9 thresholds and run B 45.8 ms over 5 of 9, against the 45.2
published from a hand-tuned ROI.

**And a diagnostic that would have saved a day.** The capture rate is derived
from the refresh rate the caller asserts, so a wrong `--refresh` shows up as a
camera that shot at a rate no camera offers. Run C reads 362.7 fps at
`--refresh 90` and 241.8 at `--refresh 60`, from the same phone that shot 242.1
on take 2: that take was filmed with the panel at 60 Hz and nobody knew. The
tool now says so, and prints every interpretation of the measured period instead
of guessing between them.

Usage:
    ffmpeg -i clip.mp4 -q:v 3 frames/f%04d.jpg
    python3 tools/latency-from-video.py frames --refresh 90

    --from/--to override the found window; --roi pins the page. Neither should
    be needed, and needing one is worth investigating rather than working around.
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image

# --- calibration: fractions and multiples, never pixels -----------------------
# Every constant here survived the reframe that broke the pixel constants it
# replaced. Anything expressed in pixels is a framing assumption in disguise.
DARK_SWEEP = np.arange(0.30, 0.71, 0.05)   # ink thresholds, as a fraction of
                                           # each frame's own page brightness
PAGE_BRIGHT = 0.55      # of the median frame's peak: what counts as page
PAGE_COVER = 0.5        # a row is page if this fraction of it is
PAGE_INSET = 0.02       # of the page's short side, to clear the bezel edge
ERODE_W = 1.5           # stroke widths: erodes the ink line away, keeps the pen
CLEARANCE_W = 3.0       # stroke widths of pen fringe to disown
APEX_DROP_W = 4.0       # stroke widths a minimum must clear to be an apex
SETTLE_START_W = 0.6    # stroke widths of descent before the walk starts
SETTLE_TOL_W = 0.3      # stroke widths of wander tolerated on the plateau

# --- validity: what makes a threshold's reading admissible -------------------
# Rates a phone will actually shoot at. Used only to sanity-check the derived
# capture rate against the refresh rate the caller asserted; see main.
CAMERA_RATES = (30, 60, 120, 240, 480)
CAMERA_RATE_TOL = 0.06

# A dark blob bigger than this fraction of the page is not a pen but a hand and
# a forearm, and where the blob is that large the erode/dilate separation of ink
# from pen cannot be trusted. Used by drawing_window to find the frames worth
# measuring from. See Calib.pen_max_px.
#
# Swept on both clips with a known answer, because a constant nobody swept is
# how the first version of this tool came to be wrong:
#
#   take 2   0.10 0.125 0.15 0.175 0.20 -> 45.1 ms, 5 of 9 thresholds
#            0.25                        -> 42.7 ms, 3 of 9   (hand let in)
#   run B    0.10 0.125                  -> inconclusive      (window too tight)
#            0.15 0.175 0.20 0.25        -> 45.8-46.0 ms, 5 of 9
#
# Both are flat over 0.15-0.20 and both fail outside it, in opposite directions,
# so the value is the middle of an overlap rather than a point that happened to
# work. If a future clip needs it moved, sweep it again and put the table here.
PEN_MAX_PAGE = 0.175

MIN_APEXES = 3          # fewer than this is not a measurement
MAX_REFRESHES = 12.0    # a latency beyond this many panel refreshes is a bug
MAX_SPREAD_REFRESH = 1.5  # apexes must agree to about this, or the pass is noise


def frames(path):
    fs = sorted(glob.glob(os.path.join(path, "*.jpg")))
    if not fs:
        sys.exit(f"no frames in {path}")
    return fs


def load(f, roi):
    y0, y1, x0, x1 = roi
    return np.asarray(Image.open(f).convert("L"), dtype=np.float32)[y0:y1, x0:x1]


def morph(m, r, op):
    """Erode or dilate by a square, one axis at a time.

    Separable: a square structuring element is the composition of a horizontal
    and a vertical line, so this is O(r) where the obvious double loop is
    O(r*r) — bit-identical output, six times faster at the radii used here.

    Padded rather than rolled. `np.roll` wraps, and a wrapped mask puts the
    frame's opposite edge into the middle of the page.
    """
    fill = (op == "e")
    out = m
    for axis in (0, 1):
        pad = [(0, 0), (0, 0)]
        pad[axis] = (r, r)
        p = np.pad(out, pad, constant_values=fill)
        acc = np.ones_like(out) if fill else np.zeros_like(out)
        for d in range(2 * r + 1):
            sl = [slice(None), slice(None)]
            sl[axis] = slice(d, d + out.shape[axis])
            w = p[tuple(sl)]
            acc = (acc & w) if fill else (acc | w)
        out = acc
    return out


def longest_run(flags):
    """Start and end of the longest contiguous True run.

    Used instead of connected components on purpose: this tool depends on numpy
    and PIL only, which is what lets it run anywhere with no install step, and a
    page is a rectangle so a profile is enough.
    """
    best = (0, 0)
    i = 0
    while i < len(flags):
        if flags[i]:
            j = i
            while j < len(flags) and flags[j]:
                j += 1
            if j - i > best[1] - best[0]:
                best = (i, j)
            i = j
        else:
            i += 1
    return best


def find_page(files, n=24):
    """The page rectangle, from a temporal median of the clip.

    The median is what makes this robust: the pen and the ink are somewhere
    different in every frame and average out of it, so what remains is the lit
    page and whatever surrounds the tablet. The page is then the longest run of
    rows that are mostly bright, and within those the longest run of such
    columns — which is what excluded run C's desk and bottle without needing to
    know they were there.
    """
    pick = files[::max(1, len(files) // n)][:n]
    med = np.median(
        np.stack([np.asarray(Image.open(f).convert("L"), dtype=np.float32) for f in pick]),
        axis=0,
    )
    bright = med > med.max() * PAGE_BRIGHT
    y0, y1 = longest_run(bright.mean(axis=1) > PAGE_COVER)
    if y1 - y0 < 40:
        sys.exit("no page found: is the tablet filling most of the frame?")
    x0, x1 = longest_run(bright[y0:y1].mean(axis=0) > PAGE_COVER)
    inset = int(round(PAGE_INSET * min(y1 - y0, x1 - x0)))
    return (y0 + inset, y1 - inset, x0 + inset, x1 - inset)


def stroke_width(files, roi, frac=0.5):
    """Median horizontal run of dark pixels: the ink line's width, in pixels.

    This is the ruler every other spatial constant is expressed in. Measured on
    frames from the middle of the clip, where there is ink and the pen has not
    yet lifted. Runs longer than 40 px are dropped so the pen's own body does
    not count as one very wide stroke.
    """
    runs = []
    for f in files[int(len(files) * 0.35):int(len(files) * 0.75):8]:
        img = load(f, roi)
        for row in (img < np.median(img) * frac):
            edges = np.flatnonzero(np.diff(np.concatenate(([0], row.view(np.int8), [0]))))
            for a, b in zip(edges[::2], edges[1::2]):
                if 1 <= b - a <= 40:
                    runs.append(b - a)
    if not runs:
        sys.exit("no ink found: is this a clip of a stroke being drawn?")
    return float(np.median(runs))


class Calib:
    """What the clip says about itself. See [find_page] and [stroke_width]."""

    def __init__(self, roi, stroke):
        self.roi = roi
        self.stroke = stroke
        self.erode = max(2, int(round(ERODE_W * stroke)))
        self.dilate = self.erode + 1
        self.clearance = max(3, int(round(CLEARANCE_W * stroke)))
        # A pen blob is two-dimensional where a stroke is a line, so "many times
        # the area a stroke could put in one place" separates them by size alone.
        self.pen_min_px = int(round(16 * stroke * stroke))
        # And a ceiling, which the first version did not have. A dark blob
        # covering a quarter of the page is a hand and a forearm, not a pen:
        # on take 2 the blob runs 300k-630k pixels of a 1.38 Mpx page while the
        # hand is still withdrawing, and 22k-128k once only the pen is over the
        # paper. Where the blob is that large the erode/dilate separation of ink
        # from pen cannot be trusted, so those frames are not a place to measure
        # from. Used by [drawing_window] to find where the pen is a pen.
        self.pen_max_px = int(round(PEN_MAX_PAGE * (roi[1] - roi[0]) * (roi[3] - roi[2])))
        self.ink_min_px = int(round(2 * stroke * stroke))
        self.sliver = max(3, int(round(stroke)))

    def __str__(self):
        y0, y1, x0, x1 = self.roi
        return (f"page y {y0}..{y1} x {x0}..{x1}  stroke {self.stroke:.1f}px  "
                f"erode {self.erode} clearance {self.clearance} "
                f"pen {self.pen_min_px}..{self.pen_max_px}px")


def scan(files, lo, hi, cal, fracs):
    """Per frame and per threshold: nib y, ink top y, ink area.

    Frames outer, thresholds inner: each JPEG is decoded once for the whole
    sweep rather than once per threshold, which is the difference between a
    minute and a quarter of an hour.
    """
    y0, y1, x0, x1 = cal.roi
    yy, xx = np.mgrid[0:y1 - y0, 0:x1 - x0]
    out = {f: [] for f in fracs}
    for i in range(lo, hi):
        img = load(files[i - 1], cal.roi)
        level = np.median(img)          # this frame's own page brightness
        for f in fracs:
            dark = img < level * f
            pen = morph(morph(dark, cal.erode, "e"), cal.dilate, "d")
            ink = dark & ~morph(pen, cal.clearance, "d")
            nib_y = np.nan
            if pen.sum() >= cal.pen_min_px:
                px, py = xx[pen], yy[pen]
                nib_y = float(py[px <= px.min() + cal.sliver].mean())
            ink_top = float(yy[ink].min()) if ink.sum() > cal.ink_min_px else np.nan
            out[f].append((i, nib_y, ink_top, int(ink.sum())))
    return {f: np.array(v) for f, v in out.items()}


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


def minima(v, drop, guard=6):
    """Frames where a signal is at a local minimum — the pen at a zigzag apex.

    `drop` is how far the window must fall away from the candidate before it
    counts as a real turn rather than pixel wander. It arrives in stroke widths
    from the caller; it was 20 hardcoded pixels, which is a different amount of
    travel at every framing.
    """
    out = []
    for i in range(guard, len(v) - guard):
        if np.isnan(v[i]):
            continue
        w = v[i - guard:i + guard + 1]
        if np.nanmin(w) == v[i] and v[i] < np.nanmax(w) - drop:
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


def settles(top, start, begin_drop, tol, window=5):
    """When the ink finishes climbing to the apex the pen reached at `start`.

    Two phases, and the first is why a plain flatness test does not work: after
    the pen turns, the ink's top is still sitting at the *previous* apex and is
    perfectly flat there. So wait for the descent to begin, then take the frame
    that first reaches the plateau it descends to — not the frame where the
    series happens to look flat, which arrives a frame or two early.

    `base` is a median of the frames before the apex rather than the value at
    it, because the apex frame itself can be one of the dropouts.

    The third phase is W0's, and it is worth its own paragraph. The walk stops
    on the first frame within `tol` of the plateau, which can be a whole step
    above the plateau itself — the last frames of a climb read 238, 237, 236,
    and `tol` is the same size as those steps, so which frame the walk stopped
    on was decided by one pixel and moved the answer by two frames, eight
    milliseconds. So once the flat run is found, take the run's own level and
    re-enter it from the first frame that actually reaches it.
    """
    base = np.nanmedian(top[max(0, start - 5):start + 1])
    if np.isnan(base):
        return None
    i = start
    while i < len(top) - 2 and not (top[i] < base - begin_drop):
        i += 1                                   # still at the previous apex
    if i >= len(top) - 2:
        return None
    # Walk the descent and stop once it has been flat for `window` frames. A
    # fixed lookahead cannot work: anything long enough to cover a slow climb
    # also reaches the *next* apex, whose lower plateau then wins.
    best, best_f = top[i], i
    j = i
    while j < len(top):
        if np.isfinite(top[j]) and top[j] < best - tol:
            best, best_f = top[j], j
        elif j - best_f >= window:
            break
        j += 1
    run = top[best_f:min(len(top), best_f + window + 1)]
    run = run[np.isfinite(run)]
    if len(run) == 0:
        return best_f
    plateau = np.median(run)
    k = best_f
    while k < len(top) and not (np.isfinite(top[k]) and top[k] <= plateau + 0.5):
        k += 1
    return min(k, len(top) - 1)


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


def latencies(sig, cal, dt):
    """Per-apex latencies in ms for one threshold's signals."""
    nib = sig[:, 1]
    top = smooth5(sig[:, 2])
    out = []
    for m in minima(nib, APEX_DROP_W * cal.stroke):
        s = settles(top, m, SETTLE_START_W * cal.stroke, SETTLE_TOL_W * cal.stroke)
        if s is None:
            continue
        out.append((s - subframe_min(nib, m)) * dt)
    return out


def admissible(lats, refresh_hz):
    """Split a threshold's per-apex readings into the physical and the not.

    **Per reading, not per threshold, and that is a correction to how this
    worked.** The first version rejected an entire threshold if any one of its
    apexes came back impossible, on the reasoning that a bad reading means the
    tracking is unreliable there. Swept over take 2 that throws away almost
    everything: eight of nine thresholds return a mix of four sound readings
    around 45 ms and one that is -0.4, and the pass rate was one in nine while
    the surviving numbers agreed with each other all along.

    A negative latency is not evidence that the other four apexes are wrong. It
    is evidence about *that apex*: the ink's settle was found at or before the
    pen's reversal, which happens when the pen's own blob leaks through the
    clearance into the ink mask at one reversal and the settle walk locks onto
    it. The other reversals in the same clip at the same threshold are
    unaffected. So each reading faces the physics on its own — ink cannot
    precede the pen, and a dozen refreshes is a tracking failure rather than a
    latency — and what is left faces the agreement check as a group.

    This is a stricter rule about individual numbers and a looser one about
    passes, which is the right way round: the discarded readings are discarded
    for a stated physical reason, and the ones that remain are the ones the
    consensus is built from.
    """
    refresh_ms = 1000.0 / refresh_hz
    good, dropped = [], 0
    for v in lats:
        if v <= 0 or v > MAX_REFRESHES * refresh_ms:
            dropped += 1
        else:
            good.append(v)
    return good, dropped


def verdict(good, refresh_hz):
    """Why a threshold's surviving readings are or are not admissible.

    Returns None when they pass. What is left after [admissible] has to be
    enough of the clip to be a measurement rather than a coincidence, and the
    apexes have to agree: several reversals of one stroke are several
    independent measurements of the same quantity, so if they disagree by much
    more than a refresh they are not measuring the same thing.
    """
    if len(good) < MIN_APEXES:
        return f"only {len(good)} usable apexes"
    v = np.array(good)
    spread = v.max() - v.min()
    if spread > MAX_SPREAD_REFRESH * (1000.0 / refresh_hz):
        return f"apexes disagree by {spread:.0f} ms"
    return None


def drawing_window(files, cal, step=4, span=12, pad=2):
    """The frames over which the zigzag is being drawn, from the nib's track.

    **The rule is direction reversals, not ink area, and the difference is the
    whole function.** The first version used the span over which the ink area
    grows, and it does not work: during the run-up the pen and the hand enter
    the page and are counted as ink, so on take 2 it reported the ink at 97% of
    its final area by frame 31 for a stroke actually drawn between 130 and 262.
    Handing that window to `capture_fps` swamps the ink's per-refresh
    periodicity and pins the period search at its lower bound, which would
    silently rescale every reading by a third — the boundary check in [main] is
    what caught it, and is why that check exists.

    Separating pen from hand from ink during the approach is the same
    two-adjacent-dark-objects problem that sank the straight-stroke method, so
    this does not try. It asks a different question: **when is the nib
    oscillating?** Approaching the page, lifting off, and drifting between
    strokes are all monotonic — the nib travels one way. A zigzag is the only
    part of the clip where the direction of travel flips over and over. So the
    signal is the local density of sign changes in the nib's vertical velocity,
    and the window is the longest run where that density says "at least two
    reversals nearby".

    That also makes it self-scaling. Nothing here is in pixels or milliseconds:
    `span` is in samples, and a faster or slower zigzag simply puts more or
    fewer reversals in the same window without moving the threshold.

    Validated against both takes that have a known answer — take 2, where the
    stroke runs about 130..262 of 483, and run B — rather than against the one
    it was written on.
    """
    y0, y1, x0, x1 = cal.roi
    yy, xx = np.mgrid[0:y1 - y0, 0:x1 - x0]

    idx, nib = [], []
    for i in range(1, len(files) + 1, step):
        img = load(files[i - 1], cal.roi)
        dark = img < np.median(img) * 0.5
        pen = morph(morph(dark, cal.erode, "e"), cal.dilate, "d")
        # The same leftmost-sliver rule [scan] uses, so the window is found on
        # the very feature the measurement is later made from. A rule that
        # tracked something else could bracket frames where the nib is not
        # locatable at all.
        v = np.nan
        if cal.pen_min_px <= pen.sum() <= cal.pen_max_px:
            px, py = xx[pen], yy[pen]
            v = float(py[px <= px.min() + cal.sliver].mean())
        idx.append(i)
        nib.append(v)

    nib = np.array(nib, dtype=np.float64)
    if not np.isfinite(nib).any():
        sys.exit("no pen found anywhere in the clip: is the ROI the page?")

    # Sign of travel, carried across frames where the nib is missing or still,
    # so a single dropped frame in the middle of a sweep is not a reversal.
    flips = np.zeros(len(nib), dtype=np.float64)
    prev = 0.0
    for i in range(1, len(nib)):
        d = nib[i] - nib[i - 1]
        if not np.isfinite(d) or d == 0.0:
            continue
        sign = 1.0 if d > 0 else -1.0
        if prev != 0.0 and sign != prev:
            flips[i] = 1.0
        prev = sign

    density = np.convolve(flips, np.ones(span), mode="same")
    live = np.isfinite(nib) & (density >= 2.0)
    a, b = longest_run(live)
    if b - a < 3:
        # Nothing oscillated. Say so by handing back the whole clip: the
        # boundary check in [main] will refuse it rather than measure it.
        return 1, len(files)
    return (max(1, idx[a] - pad * step),
            min(len(files), idx[min(b, len(idx) - 1)] + pad * step))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames_dir")
    ap.add_argument("--refresh", type=float, default=90.0, help="panel Hz during the take")
    ap.add_argument("--from", dest="lo", type=int, default=None)
    ap.add_argument("--to", dest="hi", type=int, default=None)
    ap.add_argument("--roi", help="y0,y1,x0,x1 — pin the page instead of finding it")
    a = ap.parse_args()

    fs = frames(a.frames_dir)
    roi = tuple(int(v) for v in a.roi.split(",")) if a.roi else find_page(fs)
    cal = Calib(roi, stroke_width(fs, roi))
    print(f"calibrated: {cal}")

    lo, hi = drawing_window(fs, cal)
    if a.lo is not None:
        lo = a.lo
    if a.hi is not None:
        hi = a.hi
    hi = min(hi, len(fs))
    print(f"drawing window: frames {lo}..{hi} of {len(fs)}")

    sigs = scan(fs, lo, hi, cal, list(DARK_SWEEP))

    # The frame rate is a property of the clip, not of the threshold; take it
    # from the middle of the sweep, where the ink series is cleanest.
    mid = list(DARK_SWEEP)[len(DARK_SWEEP) // 2]
    period, fps = capture_fps(sigs[mid][:, 3], a.refresh)
    dt = 1000.0 / fps
    print(f"ink steps every {period:.3f} frames at {a.refresh:g} Hz "
          f"-> capture {fps:.1f} fps ({dt:.3f} ms a frame)")

    # The capture rate is derived from the refresh rate the caller asserted, so
    # a wrong --refresh shows up here as a camera that shot at a rate no camera
    # offers. Run C read 362.7 fps at --refresh 90 and 241.8 at --refresh 60,
    # from the same phone that shot 242.1 on take 2 — which is not a subtle
    # clue that the panel had dropped to 60 Hz for that take, and is worth
    # saying rather than leaving in the numbers for someone to notice.
    near = min(CAMERA_RATES, key=lambda r: abs(r - fps))
    if abs(near - fps) > CAMERA_RATE_TOL * near:
        print(f"  WARNING: {fps:.1f} fps is not a rate cameras shoot at, so "
              f"--refresh {a.refresh:g} is probably wrong for this clip.")
        # Every interpretation, rather than a guess between them. At period
        # 4.03 the two candidates are 240 fps / 59.6 Hz and 480 fps / 119.1 Hz,
        # and they are almost equidistant from the computed rate — but this
        # panel only offers 60 and 90, so a person reading the list resolves it
        # instantly and an argmin would have picked the wrong one.
        options = "   ".join(
            f"{r:g} fps -> {r / period:.0f} Hz" for r in CAMERA_RATES if 20 <= r / period <= 200
        )
        print(f"  The measured period of {period:.2f} frames per refresh means: {options}")
        print(f"  Check which of those the panel was actually holding.")
    # A period sitting on the search boundary means no periodicity was found,
    # not that the ink steps every two frames. Reporting it would rescale every
    # latency below by whatever the true rate was — silently, and by a third on
    # the one clip where it has been seen. Refuse instead.
    if period <= 2.01 or period >= 5.99:
        sys.exit(
            f"frame-rate calibration failed: period {period:.3f} is at the search "
            f"boundary, so the ink's per-refresh step was not found.\n"
            f"Almost always the analysis window is wrong — it is {lo}..{hi} here. "
            f"Pass --from/--to bracketing just the drawn stroke."
        )
    print()

    print(f"{'DARK':>6} {'n':>3} {'cut':>4} {'median':>8} {'spread':>7}  readings / why rejected")
    accepted = []
    for f in DARK_SWEEP:
        good, dropped = admissible(latencies(sigs[f], cal, dt), a.refresh)
        why = verdict(good, a.refresh)
        if why:
            print(f"{f:6.2f} {len(good):3d} {dropped:4d} {'':>8} {'':>7}  rejected: {why}")
            continue
        v = np.array(good)
        accepted.append(float(np.median(v)))
        print(f"{f:6.2f} {len(v):3d} {dropped:4d} {np.median(v):8.1f} {v.max()-v.min():7.1f}  "
              + " ".join(f"{x:.1f}" for x in v))

    if not accepted:
        print("\nINCONCLUSIVE — no threshold survived the physical checks.")
        print("That is a result, not a failure: the clip does not support a number.")
        sys.exit(1)
    acc = np.array(accepted)
    print(f"\nconsensus {np.median(acc):.1f} ms   "
          f"over {len(acc)} of {len(DARK_SWEEP)} thresholds, "
          f"range {acc.min():.1f}-{acc.max():.1f} ms")


if __name__ == "__main__":
    main()
