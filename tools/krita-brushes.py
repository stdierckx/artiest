#!/usr/bin/env python3
"""
Read Krita brush presets and write artiest `.brush` files.

## What this is, and what it is not

A **spike**, answering one question from `docs/brushes-plan.md`: can a Krita
pixel brush be carried across into this engine and still read as itself? It is
Python and it lives in `tools/` because the answer had to be cheap; if the
answer is yes, Wb2 and Wb3 rewrite it in Kotlin and put it behind the shelf's
import button.

## Licensing

Nothing here is derived from Krita's source. The format was established by
reading the *files* — a `.kpp` is a PNG whose `tEXt` chunk holds the preset XML,
a `.bundle` is a zip — which is black-box observation of a program's output and
is what `docs/brushes-plan.md` permits. The brush data itself carries its own
licence in the bundle's `meta.xml`; Krita's default bundle says `CC-0`, and
nothing without a traceable licence may be shipped.

## What converts

`paintopid="paintbrush"` with `brush_definition type="auto_brush"` — a
procedural round or elliptical nib, which is exactly what `MaskGenerator`
produces. That is 35 of the 118 presets in Krita's default bundle. The other 46
pixel brushes use a bitmap tip (`gbr_brush`, `png_brush`) and need Wb5; the rest
use paintops this engine does not have at all.

## The one thing that is not a straight copy

**Krita's `declination` runs the other way from our `TILT`.** Declination is 1
with the pen upright and 0 with it laid over; `Sensor.TILT` is 0 perpendicular
and 1 flat. Established from `e) Marker Chisel Smooth`, whose ratio curve goes
from 0.035 at declination 0 to 1 at declination 1 — a chisel is a thin wedge
laid over and a round dot held upright, so declination 1 must be upright. Every
curve on that sensor is therefore mirrored on the way in.
"""

import argparse
import glob
import os
import re
import struct
import sys
import zlib

# ---------------------------------------------------------------- reading

def png_chunks(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return
    i = 8
    while i + 8 <= len(data):
        (ln,) = struct.unpack('>I', data[i:i + 4])
        yield data[i + 4:i + 8], data[i + 8:i + 8 + ln]
        i += 8 + ln + 4


def preset_xml(data):
    """The preset XML a `.kpp` carries, or None."""
    for typ, body in png_chunks(data):
        if typ == b'tEXt':
            key, _, value = body.partition(b'\x00')
            if key == b'preset':
                return value.decode('utf-8', 'replace')
        elif typ == b'zTXt':
            key, rest = body.split(b'\x00', 1)
            if key == b'preset':
                return zlib.decompress(rest[1:]).decode('utf-8', 'replace')
        elif typ == b'iTXt':
            key, rest = body.split(b'\x00', 1)
            if key == b'preset':
                compressed = rest[0]
                rest = rest[2:]
                _, rest = rest.split(b'\x00', 1)
                _, rest = rest.split(b'\x00', 1)
                return (zlib.decompress(rest) if compressed else rest).decode('utf-8', 'replace')
    return None


# The attributes come in either order — `name` first in most of the bundle,
# `type` first in about four fifths of it — so the tag is matched whole and the
# name is picked out of it. Assuming one order read 13 presets of 118 and
# reported the rest as having no paintop at all.
PARAM = re.compile(r'<param\s+([^>]*?)>(.*?)</param>', re.S)
CDATA = re.compile(r'<!\[CDATA\[(.*?)\]\]>', re.S)


def read_preset(path):
    xml = preset_xml(open(path, 'rb').read()) or ''
    out = {}
    for attrs, body in PARAM.findall(xml):
        name = re.search(r'\bname="([^"]+)"', attrs)
        if not name:
            continue
        found = CDATA.search(body)
        out[name.group(1)] = (found.group(1) if found else body).strip()
    name = re.search(r'<Preset[^>]*\bname="([^"]+)"', xml)
    out['_name'] = name.group(1) if name else os.path.basename(path)
    return out


def sensor_of(text):
    """(krita sensor id, [(x, y), ...]) from a sensor blob."""
    if not text:
        return None, []
    # A `sensorslist` wraps several; the first one is taken, because a
    # combination this engine cannot express is better approximated by one of
    # its terms than dropped entirely.
    ids = re.findall(r'\bid="([^"]+)"', text)
    sid = next((i for i in ids if i != 'sensorslist'), None)
    points = []
    curve = re.search(r'<curve>(.*?)</curve>', text, re.S)
    if curve:
        for pair in curve.group(1).strip().strip(';').split(';'):
            if ',' in pair:
                x, y = pair.split(',')[:2]
                try:
                    points.append((float(x), float(y)))
                except ValueError:
                    pass
    return sid, points


def brush_definition(preset):
    body = preset.get('brush_definition', '')
    head = body.split('<MaskGenerator')[0]
    attrs = dict(re.findall(r'(\w+)="([^"]*)"', head))
    mask = re.search(r'<MaskGenerator([^>]*)>', body)
    return attrs, dict(re.findall(r'(\w+)="([^"]*)"', mask.group(1))) if mask else {}


# ---------------------------------------------------------------- mapping

#: Krita's sensor ids, as this engine's. Anything absent drops its drive
#: rather than guessing — a brush with one sensor missing still draws.
SENSORS = {
    'pressure': 'PRESSURE',
    'pressurein': 'PRESSURE',
    'speed': 'SPEED',
    'declination': 'TILT',
    'ascension': 'ORIENTATION',
    'drawingangle': 'DIRECTION',
    'distance': 'DISTANCE',
    'time': 'TIME',
    'fuzzy': 'RANDOM_DAB',
    'fuzzydab': 'RANDOM_DAB',
    'fuzzystroke': 'RANDOM_STROKE',
}

#: Sensors whose zero is our one. See the module docstring.
FLIPPED = {'declination'}


def curve_text(points, flip):
    if not points:
        return None
    pts = [(1.0 - x, y) for x, y in reversed(points)] if flip else list(points)
    # Ascending and de-duplicated: `ResponseCurve.ofPoints` requires strictly
    # increasing x, and a mirrored curve can land two points on one x.
    out = []
    for x, y in sorted(pts):
        if out and x <= out[-1][0] + 1e-6:
            continue
        out.append((x, y))
    if not out:
        return None
    return 'points ' + ' '.join(f'{x:.6g} {y:.6g}' for x, y in out)


def truthy(value, default=True):
    if value is None:
        return default
    return str(value).strip().lower() in ('true', '1')


def num(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def drive_line(option, preset, key, enabled_default=True):
    """`<option>.drive SENSOR points …`, or None."""
    if not truthy(preset.get('Pressure' + key), enabled_default):
        return None
    sid, points = sensor_of(preset.get(key + 'Sensor', ''))
    ours = SENSORS.get(sid or '')
    if not ours:
        return None
    text = curve_text(points, sid in FLIPPED)
    return f'{option}.drive {ours} ' + (text or 'pow 1.0')


def convert(preset, ident, label):
    """One preset as artiest `.brush` text, or None if it is not convertible."""
    if preset.get('paintop') != 'paintbrush':
        return None
    attrs, mask = brush_definition(preset)
    if attrs.get('type') != 'auto_brush':
        return None

    composite = preset.get('CompositeOp', 'normal')
    if composite not in ('normal', 'erase'):
        # A dodge, a multiply or an overlay brush is not ink, and this engine
        # has no per-stroke composite op to carry it. Importing one draws a
        # slab of black where Krita draws a glow — which is worse than not
        # importing it, because it looks like the converter working.
        return None

    diameter = num(mask.get('diameter'), 20.0)
    if not diameter > 0:
        return None
    # Krita's fade is how far the edge is blurred; hardness is the opposite.
    #
    # **The generator matters more than the fade, and that is the finding.**
    # Krita's `default` mask fades over the band the fade describes, so
    # `1 - fade` is the whole of it. `gauss` and `soft` are soft *by
    # construction* — both ship at fade 0 and both are edgeless — so a straight
    # `1 - fade` made them hardness 1 and `a) Eraser Soft` and
    # `b) Airbrush Soft` came out as solid black slabs. This engine has one
    # falloff shape, so the generator becomes a ceiling on how hard the mask is
    # allowed to be. The numbers are what makes the three tell apart at the
    # sizes the bundle uses; a real second falloff is Wb5's job.
    fade = max(num(mask.get('hfade')), num(mask.get('vfade')))
    ceiling = {'gauss': 0.85, 'soft': 0.40}.get(mask.get('id', 'default'), 1.0)
    hardness = max(0.02, min(ceiling, 1.0 - fade))
    ratio = max(0.05, min(1.0, num(mask.get('ratio'), 1.0)))

    spacing = num(attrs.get('spacing'), 0.1)
    if truthy(attrs.get('useAutoSpacing'), False):
        # Krita computes this from the nib; the coefficient is what the preset
        # exposes, and 0.1 of a diameter is this engine's own default.
        spacing = 0.1 * num(attrs.get('autoSpacingCoeff'), 1.0)
    spacing = max(0.02, min(1.0, spacing))


    # **Krita's dab alpha is not our flow, and the difference is the overlap.**
    # Krita lays `1 / spacing` dabs across a diameter and each carries
    # `OpacityValue * FlowValue`, so one pass arrives at `1 - (1 - a)^n`. This
    # engine's `flow` already *means* the coverage of one pass — `StrokeBuilder`
    # inverts the overlap itself — so the accumulation has to be done here or
    # it happens twice in Krita's favour and once in ours.
    #
    # Read off `c) Pencil-3 Large 4B`, which is the case that showed it:
    # opacity 0.15, flow 0.33, spacing 0.05. Carried straight across it painted
    # at 0.05 coverage and the swatch was blank paper; through the overlap it is
    # 0.64, which is a soft 4B. `opacity` then goes to 1, because the build-up
    # is in the flow and Krita's normal mode has no ceiling of its own.
    dab_alpha = (max(0.0, min(1.0, num(preset.get('OpacityValue'), 1.0)))
                 * max(0.0, min(1.0, num(preset.get('FlowValue'), 1.0))))
    opacity = 1.0
    overlap = max(1.0, 1.0 / spacing)
    flow = max(0.02, min(1.0, 1.0 - (1.0 - dab_alpha) ** overlap))

    size_drive = drive_line('sizeOpt', preset, 'Size')
    lines = [
        'artiest-brush 2',
        f'id {ident}',
        f'label {label}',
        'origin IMPORTED',
        'tags krita',
        # With a sensor the floor is zero and the curve owns the whole range,
        # which is Krita's arrangement exactly: its size curve multiplies the
        # diameter. Without one the nib is one width and both ends are it.
        f'size {0.0 if size_drive else diameter:.6g} {diameter:.6g}',
        'sizeCurve pow 1.0',
        f'spacing {spacing:.6g}',
        'isotropic 0',
        f'hardness {hardness:.6g}',
        f'opacity {opacity:.6g}',
        'flowMin 0.0',
        f'flow {flow:.6g}',
        # Krita has no stabilizer in the preset; this engine's default is the
        # honest answer rather than zero, which would read as a shaky import.
        'stabilization 0.1',
        'antialias 1',
        # Never `erase 1`. Erasing is a mode the toolbar's toggle owns and the
        # pen re-reads at every stroke, so a brush file claiming it would be a
        # claim nothing honours. A Krita preset that erases keeps its *shape*,
        # which is the useful half, and says so in its tags.
        'erase 0',
        'eraseSize 96.0',
        'onset 0.0 0.0',
        'burnish 0.0',
    ]

    tags = ['krita']
    if composite == 'erase' or truthy(preset.get('EraserMode'), False):
        tags.append('eraser')
    if truthy(preset.get('Texture/Pattern/Enabled'), False):
        # Krita's texture option is a pattern image; this engine's grain is a
        # procedural field. Enabling a graphite-like tooth is the closest thing
        # it can say, and it is marked in the tags rather than claimed as the
        # same.
        lines.append('grain 160.0 0.35 0.22 0.86 11')
        tags.append('textured')
    else:
        lines.append('grain 1.0 0.0 0.0 1.0 0')
    lines[lines.index('tags krita')] = 'tags ' + ' '.join(tags)

    lines.append('sizeOpt.combine MULTIPLY')
    if size_drive:
        lines.append(size_drive)
    lines.append('flowOpt.combine MULTIPLY')
    # Krita's per-dab alpha is its opacity option; this engine's per-pass
    # coverage is `flow`. Not the same quantity — see the plan — but the same
    # job, and the curve is the part that carries the feel.
    opacity_drive = drive_line('flowOpt', preset, 'Opacity')
    if opacity_drive:
        lines.append(opacity_drive.replace('flowOpt.drive', 'flowOpt.drive', 1))

    ratio_drive = drive_line('aspect', preset, 'Ratio', enabled_default=False)
    if ratio_drive:
        lines.append(f'aspect 0.05 {ratio:.6g} MULTIPLY')
        lines.append(ratio_drive)
    else:
        lines.append(f'aspect {ratio:.6g} {ratio:.6g} MULTIPLY')

    rotation_drive = drive_line('rotation', preset, 'Rotation', enabled_default=False)
    angle = num(attrs.get('angle'))
    if rotation_drive:
        lines.append('rotation 0.0 6.2831855 MULTIPLY')
        lines.append(rotation_drive)
    else:
        lines.append(f'rotation {angle:.6g} {angle:.6g} MULTIPLY')

    scatter_drive = drive_line('scatter', preset, 'Scatter', enabled_default=False)
    if scatter_drive:
        # Krita's scatter is a fraction of the nib; ours is document pixels.
        throw = num(preset.get('ScatterValue'), 0.0) * diameter
        lines.append(f'scatter 0.0 {throw:.6g} MULTIPLY')
        lines.append(scatter_drive)
    else:
        lines.append('scatter 0.0 0.0 MULTIPLY')

    lines.append('sizeJitter 0.0 0.0 MULTIPLY')
    return '\n'.join(lines) + '\n'


def slug(name):
    out = []
    for ch in name.lower():
        if ch.isalnum():
            out.append(ch)
        elif out and out[-1] != '-':
            out.append('-')
    return ''.join(out).strip('-')[:48] or 'brush'


def tidy(name):
    """`c)_Pencil-3_Large_4B` as `Pencil-3 Large 4B`."""
    name = re.sub(r'^[a-z]\)[_ ]*', '', name)
    return name.replace('_', ' ').strip()[:48]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('sources', nargs='+', help='.kpp files, or directories of them')
    ap.add_argument('-o', '--out', required=True, help='where to write .brush files')
    ap.add_argument('--preview', help='also write each preset\'s own Krita preview here')
    args = ap.parse_args()

    files = []
    for source in args.sources:
        files.extend(sorted(glob.glob(os.path.join(source, '*.kpp'))) if os.path.isdir(source)
                     else [source])

    os.makedirs(args.out, exist_ok=True)
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)

    done = skipped = 0
    for path in files:
        preset = read_preset(path)
        label = tidy(preset['_name'])
        ident = slug(label)
        text = convert(preset, ident, label)
        if text is None:
            skipped += 1
            attrs, _ = brush_definition(preset)
            print(f'  skip {label}: {preset.get("paintop", "?")}/{attrs.get("type", "-")}',
                  file=sys.stderr)
            continue
        open(os.path.join(args.out, ident + '.brush'), 'w').write(text)
        if args.preview:
            # The `.kpp` is a PNG, and the picture in it is Krita's own preview
            # of that brush. It is the control the swatch is judged against.
            open(os.path.join(args.preview, ident + '.png'), 'wb').write(open(path, 'rb').read())
        done += 1
    print(f'{done} converted, {skipped} skipped')


if __name__ == '__main__':
    main()
