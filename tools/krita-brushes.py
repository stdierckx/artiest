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

`paintopid="paintbrush"`, with either kind of nib:

- **`auto_brush`** — a procedural round or elliptical nib, which is exactly what
  `MaskGenerator` produces. 35 of the 118 presets in Krita's default bundle.
- **`gbr_brush` and `png_brush`** — a picture, which is what `Tip` is. 46 more,
  and they arrive with their tip extracted beside them. This is Wb5.

The rest use paintops this engine does not have at all — `colorsmudge` reads the
colour under the nib, and nothing here can.

## Where a tip's size comes from

Not from the preset. Krita stores `scale`, a multiplier on the tip file's own
pixel dimensions, so a 150-pixel rake at `scale="0.05"` is a seven-pixel nib.
The tip file has to be read to know that, which is why the bundle is opened as a
whole rather than a directory of `.kpp` being enough.

## The two tip containers

- **`.png`** is copied through untouched. Android decodes it, and
  `Tips.coverageOf` settles what its pixels mean — alpha where there is any,
  and `255 - grey` where there is not.
- **`.gbr` and `.gih`** are GIMP's own formats and nothing on Android reads
  them, so they are decoded here and written out as grey-plus-alpha PNGs. Black
  ink at the coverage's alpha, which is correct under *both* halves of that
  rule rather than relying on either.

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
import zipfile
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
    return read_preset_bytes(open(path, 'rb').read(), os.path.basename(path))


def read_preset_bytes(data, name):
    xml = preset_xml(data) or ''
    out = {}
    for attrs, body in PARAM.findall(xml):
        name = re.search(r'\bname="([^"]+)"', attrs)
        if not name:
            continue
        found = CDATA.search(body)
        out[name.group(1)] = (found.group(1) if found else body).strip()
    found = re.search(r'<Preset[^>]*\bname="([^"]+)"', xml)
    out['_name'] = found.group(1) if found else name
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


# ---------------------------------------------------------------- tips


def png_size(data):
    """(width, height) out of a PNG's IHDR, without decoding it."""
    if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        return None
    w, h = struct.unpack('>II', data[16:24])
    return (w, h) if w > 0 and h > 0 else None


def gbr_coverage(data):
    """(width, height, coverage bytes) out of a GIMP `.gbr`, or None.

    The one-byte form is the mask itself — measured, not assumed: the bundle's
    `bristles_grouped.gbr` is two thirds zeros read straight and would be two
    thirds solid read inverted, and a brush tip is mostly nothing.

    The four-byte form is RGBA, and there the *alpha* is the mask. A preset may
    set `ColorAsMask`, which asks for the colour's darkness instead; that is the
    caller's decision because the flag lives on the preset and not on the file.
    """
    if len(data) < 20:
        return None
    header, version, width, height, depth = struct.unpack('>5I', data[:20])
    if not (0 < width <= 4096 and 0 < height <= 4096):
        return None
    if depth not in (1, 4) or header < 20 or header > len(data):
        return None
    body = data[header:header + width * height * depth]
    if len(body) < width * height * depth:
        return None
    if depth == 1:
        return width, height, bytearray(body)
    return width, height, bytearray(body[3::4])


def gbr_luma(data):
    """The same file read as darkness rather than alpha. For `ColorAsMask`."""
    read = gbr_coverage(data)
    if read is None:
        return None
    width, height, _ = read
    header, _, _, _, depth = struct.unpack('>5I', data[:20])
    if depth != 4:
        return read
    body = data[header:header + width * height * 4]
    out = bytearray(width * height)
    for i in range(width * height):
        r, g, b = body[i * 4], body[i * 4 + 1], body[i * 4 + 2]
        out[i] = 255 - ((r * 77 + g * 150 + b * 29) >> 8)
    return width, height, out


def gih_first_cell(data):
    """A GIMP image pipe's first frame, as `.gbr` bytes.

    A `.gih` is a name line, a parameter line, and then several `.gbr` images
    end to end. Krita picks between them per dab — at random, or by pressure, or
    by travel direction — and this engine has one tip per brush, so the first
    cell is taken and the rest are dropped. That is a real loss and it is named
    in the plan: a seven-cell chalk becomes one chalk.
    """
    first = data.find(b'\n')
    if first < 0:
        return None
    second = data.find(b'\n', first + 1)
    if second < 0:
        return None
    return data[second + 1:]


def grey_alpha_png(width, height, coverage):
    """Coverage as a grey-plus-alpha PNG: black ink, alpha is the mask.

    Written by hand because the alternative is a dependency, and this is eight
    lines of zlib. Black at the coverage's alpha reads correctly whether the
    other side looks at the alpha channel or at `255 - grey`, which takes the
    convention question off the table for every tip that goes through here.
    """
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter: none
        row = coverage[y * width:(y + 1) * width]
        for value in row:
            raw.append(0)
            raw.append(value)

    def chunk(kind, payload):
        return (struct.pack('>I', len(payload)) + kind + payload
                + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 4, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
            + chunk(b'IEND', b''))


class Tips:
    """The bundle's `brushes/` folder, converted on demand.

    On demand because 80 tips is 30 MB of PNG and a picked set uses a dozen of
    them; nothing is decoded or written until a preset that wants it converts.
    """

    def __init__(self, sources, out_dir):
        #: filename inside the bundle -> bytes
        self.sources = sources
        self.out_dir = out_dir
        self.done = {}

    def take(self, filename, as_mask=False):
        """`(tip id, longest side)` for a tip file, or None if it cannot be read."""
        key = (filename, as_mask)
        if key in self.done:
            return self.done[key]
        result = self._convert(filename, as_mask)
        self.done[key] = result
        return result

    def _convert(self, filename, as_mask):
        data = self.sources.get(filename)
        if data is None:
            return None
        ident = slug(os.path.splitext(os.path.basename(filename))[0])
        if as_mask:
            ident += '-mask'
        lower = filename.lower()
        if lower.endswith('.png'):
            size = png_size(data)
            if size is None:
                return None
            self._write(ident + '.png', data)
            return ident, max(size)
        if lower.endswith('.gih'):
            data = gih_first_cell(data)
            if data is None:
                return None
            lower = '.gbr'
        if not lower.endswith('.gbr'):
            return None      # .svg and anything else: not a picture we can stamp
        read = gbr_luma(data) if as_mask else gbr_coverage(data)
        if read is None:
            return None
        width, height, coverage = read
        self._write(ident + '.png', grey_alpha_png(width, height, coverage))
        return ident, max(width, height)

    def _write(self, name, data):
        if not self.out_dir:
            return
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, name), 'wb') as f:
            f.write(data)


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


def convert(preset, ident, label, tips=None):
    """One preset as artiest `.brush` text, or None if it is not convertible."""
    if preset.get('paintop') != 'paintbrush':
        return None
    attrs, mask = brush_definition(preset)
    kind = attrs.get('type')
    tip = None
    if kind != 'auto_brush':
        if tips is None or kind not in ('gbr_brush', 'png_brush'):
            return None
        # `ColorAsMask` says to read a coloured tip's darkness instead of its
        # alpha. It lives on the preset rather than the file, so the same `.gbr`
        # can be two different nibs and the two get two ids.
        tip = tips.take(attrs.get('filename', ''),
                        as_mask=truthy(attrs.get('ColorAsMask'), False))
        if tip is None:
            return None

    composite = preset.get('CompositeOp', 'normal')
    if composite not in ('normal', 'erase'):
        # A dodge, a multiply or an overlay brush is not ink, and this engine
        # has no per-stroke composite op to carry it. Importing one draws a
        # slab of black where Krita draws a glow — which is worse than not
        # importing it, because it looks like the converter working.
        return None

    if tip is not None:
        # Krita's `scale` multiplies the tip file's own pixel size, so the nib
        # is only knowable with the picture in hand. See the module docstring.
        diameter = tip[1] * num(attrs.get('scale'), 1.0)
    else:
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
    if tip is not None:
        # A picture brings its own edge and its own proportions. Hardness is
        # ignored over a tip by `MaskGenerator`, and a ratio here would squash
        # a nib that is already the shape it is meant to be.
        hardness = 1.0
        ratio = 1.0
    else:
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

    erasing = composite == 'erase' or truthy(preset.get('EraserMode'), False)

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
        # **`erase 1` is now written, and it used to be refused.** The refusal
        # was right at the time: erasing was a mode a toolbar toggle owned and
        # re-read at every stroke, so a brush file claiming it made a claim
        # nothing honoured. There is no toggle any more — an eraser is a brush
        # with `erase` set, there are two shipped ones, and `adoptBrush` carries
        # the field. See `docs/ui-space-plan.md`, Us2.
        f'erase {1 if erasing else 0}',
        'eraseSize 96.0',
        'onset 0.0 0.0',
        'burnish 0.0',
    ]

    if tip is not None:
        lines.insert(lines.index(f'hardness {hardness:.6g}') + 1, f'tip {tip[0]}')

    tags = ['krita']
    if tip is not None:
        tags.append('tip')
    if erasing:
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


def load_bundle(path):
    """`([(name, kpp bytes)], {tip filename: bytes})` out of a `.bundle` zip.

    A bundle is a plain ZIP — established by opening one, which is what
    `docs/brushes-plan.md` permits — with the presets under `paintoppresets/`
    and the tips they name under `brushes/`.
    """
    presets = []
    tips = {}
    with zipfile.ZipFile(path) as z:
        for name in sorted(z.namelist()):
            if name.startswith('paintoppresets/') and name.lower().endswith('.kpp'):
                presets.append((os.path.basename(name), z.read(name)))
            elif name.startswith('brushes/'):
                tips[os.path.basename(name)] = z.read(name)
    return presets, tips


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('sources', nargs='+',
                    help='.bundle files, .kpp files, or directories of .kpp')
    ap.add_argument('-o', '--out', required=True, help='where to write .brush files')
    ap.add_argument('--tips', help='where to write the tip pictures. Without it, '
                                   'only the procedural brushes convert')
    ap.add_argument('--preview', help='also write each preset\'s own Krita preview here')
    args = ap.parse_args()

    presets = []
    tip_sources = {}
    for source in args.sources:
        if source.lower().endswith('.bundle'):
            found, tips = load_bundle(source)
            presets.extend(found)
            tip_sources.update(tips)
        elif os.path.isdir(source):
            for path in sorted(glob.glob(os.path.join(source, '*.kpp'))):
                presets.append((os.path.basename(path), open(path, 'rb').read()))
        else:
            presets.append((os.path.basename(source), open(source, 'rb').read()))

    os.makedirs(args.out, exist_ok=True)
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
    tips = Tips(tip_sources, args.tips) if args.tips else None

    done = skipped = tipped = 0
    for name, data in presets:
        preset = read_preset_bytes(data, name)
        label = tidy(preset['_name'])
        ident = slug(label)
        text = convert(preset, ident, label, tips)
        if text is None:
            skipped += 1
            attrs, _ = brush_definition(preset)
            print(f'  skip {label}: {preset.get("paintop", "?")}/{attrs.get("type", "-")}',
                  file=sys.stderr)
            continue
        open(os.path.join(args.out, ident + '.brush'), 'w').write(text)
        if 'tip ' in text:
            tipped += 1
        if args.preview:
            # The `.kpp` is a PNG, and the picture in it is Krita's own preview
            # of that brush. It is the control the swatch is judged against.
            open(os.path.join(args.preview, ident + '.png'), 'wb').write(data)
        done += 1
    print(f'{done} converted ({tipped} with a tip), {skipped} skipped')


if __name__ == '__main__':
    main()
