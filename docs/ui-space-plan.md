# Room on the glass

The user's list, given in one message after a session of drawing on the
tablet. Five items, and four of them are the same complaint from four
directions: **the chrome is eating the drawing.**

> - Colorwheel, takes up a lot of space. There is a lot of lost screen real
>   estate for that window. You cant input a color code. The palette with some
>   standard colors can go instead for a hue, saturation, brightness slider.
>   Check how other software solve this. I made a screenshot on the tablet of
>   the interface of krita.
> - The eraser: Now it is some sort of toggle button on a brush, that is not how
>   i like it. I want the eraser to be a separate tool. We should have 2 erasers:
>   a hard one, and a soft one. No toggles, just a separate "brush" with the
>   name: (Hard/Soft) eraser.
> - Dragging the brush out of the Brushes window does not work, you tell me. Are
>   you sure, because the current workflow does not suit me. I press the "Put on
>   a toolbar" button, but you dont know where, and if there is no empty target
>   button available, it does nothing. It is not intuitive. Better would be to in
>   arrange mode, when you click a + button, the menu with all the controls that
>   pop up? It has 4 categories. Edit, draw, Canvas and File. Make a new
>   categorie: brushes.
> - Layers: The blend modes take up a lot of space, maybe put them in dropdown
>   menu, so we save some space
> - Overall: Find ways to save space, the screen real estate is important.

## The reference, read rather than copied

The screenshot is Krita on the same tablet, 2200x1440, and it is the same
licensing rule every other reference in this repository is under: **it is read,
not copied.** What is taken from it is what any user could describe from
memory — that the wheel is small, that blend mode is a dropdown, that a value
strip is 25 pixels tall. No Krita source is opened for any of this.

What the screenshot actually shows, measured off the pixels:

| Krita's control | Height on screen | Ours, before this work |
|---|---|---|
| Hue ring with a saturation/value triangle inside | 160 px, square | 236 dp disc + 28 dp bar + label |
| Value / shade strip | 22 px | — |
| Recent colours strip | 22 px | a 45 dp row of 26 dp circles |
| Layer blend mode | one 26 px dropdown | a 62 dp label plus two rows of 28 dp chips, 106 dp |
| Layer opacity | one 26 px bar, the number written into it | a label, a slider and a number across 40 dp |

The dominant pattern in Krita's docker column is **one row per idea, about 26
pixels tall, with the value written into the control** rather than beside it.
That is the pattern to take.

## Work items

### Us1 — a Brushes category in the `+` chooser

The third item, and first because it is the one that unblocks the second.

`ToolChooser` already has tabs and they are already `ToolGroup`. The brushes are
not a `ToolGroup` — no `ToolItem` has that group and none should, because a
brush button is `ToolItem.BRUSH` *plus an argument* and the catalogue has no
opinion about which argument. So the tab strip stops being "the groups" and
becomes "the tabs", of which one is Brushes and the rest are groups.

Picking a brush there places `BRUSH` with `arg = entry.id`, in the cell the `+`
was pressed in. **That is the whole of the user's complaint answered**: you press
`+` where you want it, so there is no "you dont know where", and the cell is
empty by construction, so there is no "it does nothing".

The shelf's own *Put on a toolbar* row action stays. It is the shortcut for when
the shelf is already open and the chooser is the answer for when it is not.

### Us2 — the eraser stops being a mode and becomes two brushes

Two new `BrushPreset` entries, `hard_eraser` and `soft_eraser`, whose only
unusual property is `erase = true`. Everything else about them is a brush: they
are in the shelf, they have swatches, they can be put on a toolbar, they can be
saved from and tuned.

This reverses a decision made three commits ago, and the reversal is the point.
`adoptBrush` stopped copying `erase` because erase was a *mode* owned by a
toggle, so copying it was writing a field the next pen-down overwrote. Erase is
not a mode any more. It is a property of the brush, `adoptBrush` copies it, and
the toggle is gone.

What goes:

- `ToolItem.ERASER`, the toggle. Replaced by `ToolItem.HARD_ERASER` and
  `ToolItem.SOFT_ERASER`, which behave exactly as `PEN`, `PENCIL` and `MARKER`
  do — named favourites for built-in brushes.
- `ToolItem.ERASER_SIZE`, the second size slider. An eraser is a brush and its
  width is the `SIZE` slider, like every other brush's. This is a cell of bar
  back, which is Us5.
- `InkSurfaceView.eraserTool`. The stroke's erase decision reads `ink.erase`.

What stays:

- `Brush.eraseSizeMax` and `modeScale`. They are still what makes the *barrel
  button* work — a pencil held backwards rubs out at rubber width with the
  pencil's shape — and that is a real thing the user has never complained about.
  An eraser preset sets it equal to its own `sizeMax` so the scale is 1.
- `InkSurfaceView.rubber` and the shelf's *Use as eraser*. Same reason: they are
  the barrel's brush, not the toolbar's.

The hard one is the pen's nib: round, opaque, hard-edged, no grain. The soft one
is a wide soft-edged dab at low flow, so that repeated passes fade a passage
rather than cutting it out — which is what a soft eraser is *for*, and which the
translucent-erase path already supports because `Brush.erase`'s own KDoc says an
eraser beads exactly as a translucent brush does.

### Us2 — what was actually built

Shipped as described, with three things the plan did not foresee.

**The soft eraser needed the engine to stop overriding it.**
`InkSurfaceView.compositeAlpha` forced *every* erasing stroke to alpha 1 and
`armRasterizer` set `solid` on every one, both for a good reason: a pencil
pressed into service as a rubber inherits a 0.02 flow floor and a grain mask,
and a full-pressure wipe was removing about a quarter of what was under it.
That reason applies to a *borrowed* rubber and not to a real one — so the
override is now conditional on `PenChoice.borrowed`, and the soft eraser keeps
its own 0.45 flow under its own 0.85 ceiling. Without this the two erasers
would have differed only at the rim.

**The eraser's swatch was a fat black band.** Every other row is honest because
it is the mark, drawn by drawing it; an eraser's mark is a hole, and a hole on
an empty page is nothing. `BrushSwatch.rubbedOut` washes the page and punches
the stroke through it with `DST_OUT`, so the row shows a stroke-shaped gap in a
tone. The width, the rim and how much comes out at a light press are all still
the engine's answer — only the wash is scenery.

**The swatch page had to grow**, 480×160 → 696×232. A 128-pixel eraser with a
34-pixel swing covers a 160-tall page entirely, so there was no ground left for
the hole to be in and the row came back blank — seen on the tablet before it was
seen anywhere else. The cost is that the pen is a tenth of its row's height
where it was a seventh.

### Us3 — blend modes in a dropdown

`LayersPanel` spends 106 dp on a heading and seven chips. Krita spends 26 on a
combo box. The chips' KDoc argued that a dropdown is one more thing to open
before you can see what you have; the counter-argument is the screen, and the
screen wins — the seven modes are seven *words*, and a dropdown showing the
current word is showing you what you have.

### Us4 — the colour panel, rebuilt around the screen

Four changes, in order of how much room they give back:

1. **The palette row goes.** It is eight fixed colours in a row 45 dp tall, and
   the user named it: *"The palette with some standard colors can go instead for
   a hue, saturation, brightness slider."* Black and white stay reachable — they
   are the ends of the value bar and the corners of any picker.
2. **Three sliders, H, S and V**, each one row, each with its own gradient track
   so the track is the range. This is what replaces the palette, and it is also
   the only way to set a colour *exactly* without arithmetic.
3. **A hex field you can type into.** `#RRGGBB`, six characters, and it is the
   other half of "you cant input a color code". It is a field and not a label,
   which is the whole change.
4. **The disc shrinks and the rows get thinner.** The disc's argument for being
   236 dp was aim — a full hue sweep at 740 dp of travel. That argument is
   weaker once there is a hue *slider*, because the slider is the precise
   instrument and the disc is the fast one.

### Us5 — the sweep

Whatever is left. Counted, not guessed: the panel heights before and after, in
dp, in this document.

## Stop condition

The user draws with it and does not say the chrome is in the way. Short of
that: the colour panel and the layers panel each give back at least a third of
their height, the eraser is two rows in the shelf and two buttons on a bar with
no toggle anywhere, and a brush reaches a toolbar by pressing `+` in the cell it
should go in.
