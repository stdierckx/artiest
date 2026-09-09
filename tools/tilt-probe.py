#!/usr/bin/env python3
"""
What the pen actually reports for tilt, from the raw digitizer.

Android declares AXIS_TILT and AXIS_ORIENTATION on this device, but declared is
not populated -- AXIS_DISTANCE declares a range here and returns 0.0 for every
sample ever captured. So this reads evdev directly and reports what arrives.

    adb shell 'timeout 150 getevent -lt /dev/input/event6' > tilt.log
    python3 tools/tilt-probe.py tilt.log

Draw ten or so strokes at deliberately different tilts, including one that rolls
the pen from upright to flat within a single stroke -- the within-stroke sweep is
what proves tilt is not latched at pen-down.

Findings on DTH-A116, 2026-09-09, recorded in docs/phase2-plan.md: tilt is real
and continuous, tops out at 63 deg rather than the declared 90, updates at about
39 Hz in 1-degree steps against 240 Hz position, and is anti-correlated with
pressure at -0.82 in ordinary use.
"""
import re
import sys

import numpy as np

EVENT = re.compile(r"\[\s*([\d.]+)\]\s+(\w+)\s+(\w+)\s+(\w+)")
CENTIDEG = 100.0


def s32(hex_value):
    """getevent prints raw axis values as unsigned 32-bit hex."""
    v = int(hex_value, 16)
    return v - (1 << 32) if v & 0x80000000 else v


def packets(path):
    """One tuple per SYN_REPORT, with unchanged axes carried forward.

    evdev only emits an axis when it changes, so a naive per-line read sees tilt
    a sixth as often as position and concludes it is barely reported.
    """
    axes = {"ABS_X": None, "ABS_Y": None, "ABS_PRESSURE": 0,
            "ABS_TILT_X": 0, "ABS_TILT_Y": 0}
    down = False
    for line in open(path):
        m = EVENT.match(line)
        if not m:
            continue
        t, kind, code, value = m.groups()
        if kind == "EV_KEY" and code == "BTN_TOUCH":
            down = value.upper() == "DOWN"
        elif kind == "EV_ABS" and code in axes:
            axes[code] = s32(value)
        elif kind == "EV_SYN" and code == "SYN_REPORT" and axes["ABS_X"] is not None:
            yield (float(t), down, axes["ABS_PRESSURE"],
                   axes["ABS_TILT_X"], axes["ABS_TILT_Y"])


def declination(tilt_x_deg, tilt_y_deg):
    """Two independent tilt axes -> one angle away from perpendicular."""
    tx, ty = np.radians(tilt_x_deg), np.radians(tilt_y_deg)
    return np.degrees(np.arctan(np.hypot(np.tan(tx), np.tan(ty))))


def main(path):
    rows = list(packets(path))
    if not rows:
        sys.exit(f"{path}: no digitizer packets -- was the pen in range?")

    t = np.array([r[0] for r in rows])
    contact = np.array([r[1] for r in rows])
    pressure = np.array([r[2] for r in rows], dtype=float)
    tx = np.array([r[3] for r in rows]) / CENTIDEG
    ty = np.array([r[4] for r in rows]) / CENTIDEG

    print(f"packets {len(rows)}   pen down {contact.sum()}")
    print(f"ABS_TILT_X  {tx.min():7.2f} .. {tx.max():7.2f} deg   "
          f"{len(np.unique(tx)):3d} distinct")
    print(f"ABS_TILT_Y  {ty.min():7.2f} .. {ty.max():7.2f} deg   "
          f"{len(np.unique(ty)):3d} distinct")

    if not contact.any():
        sys.exit("no pen-down samples -- hover only")

    dec = declination(tx, ty)[contact]
    press = pressure[contact]
    print("\ndeclination from vertical, pen down only")
    for q in (0, 5, 25, 50, 75, 95, 100):
        print(f"   p{q:<4} {np.percentile(dec, q):6.2f} deg")

    # A ceiling shows up as a pile-up at one value, not as a tail -- and it has
    # to be looked for on the raw axis, not on the combined declination, which
    # smears the clamp across a degree or so of the other axis's contribution.
    for name, axis in (("ABS_TILT_X", tx[contact]), ("ABS_TILT_Y", ty[contact])):
        top = np.abs(axis).max()
        if top == 0:
            continue
        pinned = int((np.abs(axis) >= top - 0.005).sum())
        share = 100 * pinned / len(axis)
        note = " <- a ceiling; normalise the curve to it" if share > 10 else ""
        print(f"   {name} pinned at {top:.2f} deg: "
              f"{pinned}/{len(axis)} = {share:.0f}%{note}")

    print(f"\npressure  {press.min():.0f} .. {press.max():.0f} of 8191")
    print(f"corr(declination, pressure) = {np.corrcoef(dec, press)[0, 1]:+.3f}"
          "   negative means laid-over is light, which is how a pencil is held")

    strokes, current = [], []
    for r in rows:
        if r[1]:
            current.append(r)
        elif current:
            strokes.append(current)
            current = []
    if current:
        strokes.append(current)

    print(f"\nstrokes {len(strokes)}")
    print(f"{'n':>5} {'ms':>7}  {'tilt-Y range':>16}  {'pressure':>13}")
    for st in strokes:
        tys = [x[4] / CENTIDEG for x in st]
        ps = [x[2] for x in st]
        print(f"{len(st):5d} {(st[-1][0] - st[0][0]) * 1000:7.0f}  "
              f"{min(tys):6.1f} .. {max(tys):6.1f} deg  {min(ps):5d} .. {max(ps):5d}")

    # Rate matters as much as range: a shape driven off a slow, coarse signal
    # steps visibly, and that is a filter requirement rather than a defect.
    longest = max(strokes, key=len)
    span = longest[-1][0] - longest[0][0]
    changes = int((np.diff([x[4] for x in longest]) != 0).sum())
    print(f"\nlongest stroke {len(longest)} packets over {span * 1000:.0f} ms")
    print(f"   packet rate    {len(longest) / span:5.0f} Hz")
    print(f"   tilt updates   {changes / span:5.0f} Hz effective")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "tilt.log")
