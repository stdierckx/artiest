# Wb1 — The brush shelf

> Authored 2026-09-10, as the first item of `docs/brushes-plan.md`. **Built on
> 2026-09-11** — Wb1a to Wb1f, all six. It is the item that has to land before
> importing brushes means anything, and it is worth having on its own: a user
> who tunes a pencil they like had nowhere to put it.
>
> What the tablet showed, and what changed on the way, is at the end.

## The problem, stated exactly

The app knows three brushes and it knows them **as code**. `BrushPreset` is an
enum whose `applyTo` is Kotlin, `BrushStore` persists the enum's `name`,
`MainActivity` has three hardcoded buttons, and `ToolItem` has three hardcoded
catalogue entries. Every one of those is right for three authored tools and none
of them can hold a fourth that came from a file.

So the shelf is not really a UI item. It is **the moment a brush stops being a
function and becomes a value**, and the UI is what that makes possible.

## What the shelf is

A dockable panel, built the way `LAYERS_PANEL` and `COLOUR_PANEL` are built: a
scrolling list of brushes, each row showing **the mark the brush actually
makes**, its name, and where it came from. Tap a row and it is the brush in your
hand.

**The three toggle buttons stay.** `PEN`, `PENCIL` and `MARKER` remain in the
catalogue as one-slot toggles, because a tool you reach for fifty times an hour
should be one tap on the bar and not two taps through a panel. The shelf is the
full list; the buttons are the favourites. This follows the rule `ToolItem`
already states — an item lives in exactly one place — by making the shelf a
separate entry rather than a bigger version of a button.

## The four changes underneath it

### 1. Presets become data (`:engine`)

A new `BrushLibrary.kt` beside `BrushPreset.kt`:

- **`BrushEntry`** — an id, a label, an origin, a tuning number, and a way to
  make a `Brush`. Built-ins make theirs by calling the enum; file-backed ones by
  decoding their text. Nothing downstream knows which.
- **`BrushLibrary`** — the list, keyed by id, with the built-ins first. It is
  built at startup from the three enum entries plus whatever files are on disk,
  and it is the only thing the UI ever asks for a brush.
- **`Origin`** — `BUILT_IN`, `SAVED`, `IMPORTED`. It decides two behaviours that
  must not be guessed: whether `TUNING` may overwrite it (only built-ins), and
  whether it can be deleted (never a built-in).

`BrushPreset` keeps its three members, its KDoc and its authority. It gains one
thing: a **stable `id` string** (`"pen"`, `"pencil"`, `"marker"`) that is not
`name`, for the reason `ToolItem` gives — renaming a Kotlin constant is a
refactor, renaming a persisted identifier empties somebody's shelf.

### 2. Ids everywhere, and one migration

`BrushStore` stores `brush.presetId` as a string instead of the enum name. The
old key holds `"PENCIL"`; the new one holds `"pencil"`. **One migration, read
once:** an unrecognised stored value that matches an enum `name`
case-insensitively maps to that entry's id; anything else falls back to the pen.
Written down because a silent fallback here means "the app forgot which brush I
was holding", which is exactly the complaint `BrushStore`'s KDoc exists to
prevent.

### 3. The codec learns a header

`BrushCodec` currently encodes a `Brush` and nothing else — no id, no name. A
file-backed brush needs both. The change is small and backwards compatible,
because the decoder already skips lines it does not understand:

- Two new optional lines, `id` and `label`.
- `encodeFile(entry)` / `decodeFile(text): BrushEntry?` beside the existing
  `encode`/`decode`, which keep working unchanged for `BrushStore`'s
  "the brush currently in the hand".
- `VERSION` goes to 2; version 1 files still load, and the test that asserts
  that is the point of the bump.

### 4. The shelf itself (`:app`)

Two catalogue entries, mirroring the colour and layers pattern exactly:

| Entry | Shape | What it is |
|---|---|---|
| `BRUSHES` | 1 cell, button | Opens the shelf as a popup, with fixate |
| `BRUSH_SHELF` | 6 × 11 cells (264 × 484 dp) | The same list, kept |

Six wide rather than the layers panel's seven: a row here is a swatch, a name
and a small origin mark, which is narrower than a layer row's thumbnail plus
three buttons. Eleven tall for the same reason layers is — the list is the part
worth having more of. A row is two cells tall (88 dp), so five rows are visible
and the rest scrolls.

## The swatch, which is the real work

A shelf of thirty brushes is only usable if you can tell them apart by looking,
and the only honest way to show what a brush does is to **draw with it**.

Three options, and the third is the one to take:

1. **A glyph per brush.** Free, and useless past the three we authored — every
   imported brush would get the same generic mark.
2. **A cheap approximation** — a gradient bar from the brush's size and opacity.
   Fast, and it lies. A pencil and a marker at the same size and opacity would
   look identical, which is precisely the distinction the shelf exists to make.
3. **A real stroke, rendered by the real pipeline.** A short S-curve with a
   pressure ramp and a tilt sweep, built with `StrokeBuilder` and drawn with
   `DabRasterizer.drawDry` into an offscreen bitmap, through the same scratch
   and grain path a committed stroke takes. Cached per brush id.

Take 3, for a reason beyond honesty: **it is exactly the renderer the import
picker needs later.** A tool that shows a grid of "what would this Krita brush
look like in our engine" is this function called forty times. Building it here
means Wb7 is a screen, not a subsystem.

What it costs, stated so it is not a surprise:

- The grain and the opacity composite live in `:app`'s ink path, not in
  `:engine`. The swatch needs a small **stroke-to-bitmap** service that shares
  that path without being the live surface. That is the one genuinely new piece
  of code in this item.
- Thirty swatches on first open is thirty small renders. **Lazily, off the pen
  thread, cached to memory, and never on the thread that is drawing.** A shelf
  that stutters the pen is a worse shelf than no shelf.
- The stroke must be **the same stroke for every brush**. A brush that looks
  good because its preview stroke is drawn differently is a shelf that lies in a
  subtler way than option 2 does.

## The state that is easy to get wrong

The current rule is already right and must survive: **the brush text is the
truth about how it draws, the id is the truth about which row is lit, and a user
who has moved a slider is still holding the pencil.** With three brushes that is
invisible. With thirty it becomes a visible piece of UI:

- A selected row that has been edited shows a **modified mark**.
- The shelf offers **Save as new brush** (writes a `SAVED` entry with a new id)
  and **Revert to <name>** (re-applies the entry).
- Selecting a different row while modified **discards the edit**, the way it
  does today. It does not silently save. The modified mark is what makes that
  fair rather than surprising.

`TUNING` keeps its current meaning for built-ins only. A `SAVED` or `IMPORTED`
brush is its own truth and is **never** overwritten by a tuning bump — the whole
point of saving one is that it stays put.

## Work items

| # | Item | Done when |
|---|---|---|
| **Wb1a** | `BrushEntry`, `BrushLibrary`, `BrushPreset.id`. No UI. | The three built-ins are reachable only through the library, and every existing brush test still passes. |
| **Wb1b** | Codec header, `encodeFile`/`decodeFile`, version 2. | A version-1 file round-trips; a file with an unknown line still loads. |
| **Wb1c** | `BrushStore` on ids, with the migration. | A device that had a pencil saved under the old key comes back holding a pencil. |
| **Wb1d** | Stroke-to-bitmap swatch renderer + cache. | Pen, pencil and marker are told apart at a glance in a 264 dp row, and opening the shelf does not touch the pen thread. |
| **Wb1e** | `BRUSHES` + `BRUSH_SHELF` catalogue entries and the panel. | Docks, fixates, resizes and survives a restart like the layers panel does. |
| **Wb1f** | Save / revert / delete, and the modified mark. | A tuned pencil can be saved, found after a restart, and deleted. |

Tags and search are **not** in this list. They are the right answer at thirty
brushes and premature at six, so `BrushEntry` carries a `tags` field from Wb1a
that nothing reads yet — the data is cheap, the UI is not, and the import item
is what will make it necessary.

## Stop conditions

- **If a shelf of thirty rows cannot be scrolled comfortably on this tablet with
  the pen in hand**, importing brushes has nowhere to land and the import work
  does not start. This is the item's whole justification and it is testable
  before any of it is written, by filling the shelf with thirty copies of the
  pencil.
- **If swatch rendering costs anything the pen can feel**, the swatch falls back
  to a static render generated at build time for built-ins, and imported brushes
  get theirs on import rather than on open.

## Risks

- **The panel is 264 dp of a 1200 dp screen and holds five rows.** Five is few.
  Worth checking early whether a two-column grid of smaller swatches reads
  better than one column of larger ones — that is a question for the device, not
  for this document.
- **Deleting a brush that is currently in the hand.** Falls back to the pen and
  says so. Cheap to get wrong, cheap to test.
- **Saved brushes are files now**, so the app acquires a small directory it must
  survive being corrupted in. `BrushCodec`'s "decoding never throws" rule already
  covers the file contents; the directory listing needs the same discipline.


## What was built, and what the tablet showed

All six items, on 2026-09-11, in three commits.

| # | Where it lives |
|---|---|
| **Wb1a** | `engine/…/brush/BrushLibrary.kt` — `BrushEntry`, `BrushOrigin`, `BrushLibrary`, `adoptBrush`, `copyWiringOnto`; `BrushPreset.id` |
| **Wb1b** | `BrushCodec.encodeFile` / `decodeFile`, `VERSION = 2` |
| **Wb1c** | `ui/BrushStore.kt` on ids, `ui/BrushFiles.kt` as the directory |
| **Wb1d** | `ink/BrushSwatch.kt` and `ink/BrushSwatches.kt` |
| **Wb1e** | `ui/BrushShelf.kt`, `ToolItem.BRUSHES` and `BRUSH_SHELF` |
| **Wb1f** | Save / rename / delete / revert, and the modified dot |

Tests: `BrushLibraryTest` (10), `BrushFileTest` (7), `BrushFilesTest` (9),
`BrushSwatchTest` (7, Robolectric NATIVE).

### Verified on the DTH-A116

Every line of Wb1f's "done when", with the app installed and the shelf opened
from the left column:

- **The three read apart at a glance.** The pen is a thin crisp S, the pencil a
  soft grainy one that tapers, the marker a broad flat band. The swatch is the
  real stroke, so it also told the truth about something a glyph could not: the
  pencil on that tablet has its size slider at 15, and the saved copy's swatch
  is visibly thinner than the built-in pencil's beside it.
- **A tuned pencil saved, survived a force-stop, and was deleted.**
  `files/brushes/sketch-2b.brush` is 746 bytes of version 2 text with `id`,
  `label` and `origin` lines. Renaming it to "2B soft" changed the label and
  **kept the id**, which is what `BrushEntry.id` is for.
- **Deleting the brush in the hand fell back to the pen**, the toolbar's Pen
  button lit, the sliders moved to the pen's numbers and `brush.preset` in the
  preferences read `pen`. Nothing arranged that at the call site; it is
  `BrushLibrary.entryFor` answering for an id that no longer exists.
- **The modified dot** appeared on the selected row as soon as the brush
  differed from the entry, and went out when it was saved. Revert is greyed
  until there is something to revert.

### What changed from the plan

- **The row is a name over a full-width swatch**, not a swatch beside a name.
  The plan's "swatch, name and a small origin mark" in a 264 dp row left the
  picture about 130 dp wide, and the picture is the part that does the work.
  Origin is shown by *having a menu* rather than by a mark: a built-in row has
  no `⋮` because there is nothing you may do to it.
- **The panel is six by eleven as planned**, and the popup holds three rows
  before it scrolls rather than five. A row here is 88 dp of name plus picture.
- **Rename joined save, revert and delete.** It is one call into the same file
  writer and `NameDialog` already existed; a shelf where a typo is permanent
  would have been a strange place to stop.
- **The starter layout was rearranged to make room.** `DockStore` marks an item
  as offered whether or not it found a cell, and every bar of the shipped
  starter was full — so the shelf would have been invisible on a fresh install.
  The left column now runs pen, pencil, marker, **brushes**, eraser, gap,
  colour, and the marquee moved to the right bar beside the selection panel,
  where it always belonged. Growing the column to eight cells instead was tried
  and reverted: at 12x8 cells it reaches the bottom bar, and
  `the starter layout does not overlap itself on any screen worth having`
  caught it.
### The stop condition, answered

*"If a shelf of thirty rows cannot be scrolled comfortably on this tablet with
the pen in hand, importing brushes has nowhere to land."*

Thirty brush files were written into `files/brushes` — pencils at sizes 11 to
98, so that every row is a genuinely different mark and not thirty copies of one
picture — making a shelf of 33 with the shipped three. Scrolled six times, end
to end, reading `dumpsys gfxinfo`:

| | |
|---|---|
| Frames | 121 |
| Janky | **1 (0.83%)** |
| p50 / p90 / p99 | 10 / 12 / **13 ms** |

Under one frame at 60 Hz at the 99th percentile, and one janky frame in a
hundred and twenty-one. **The shelf scrolls, so the import work has somewhere to
land.**

What makes that true is the swatch cache and where the renders run. A row
appears with its picture missing for a moment on the very first open — visible
in the first screenshot, where the pencil's strip is still blank — and never
again, because `BrushSwatches` is keyed by what a brush draws rather than by a
row being on screen. The renders themselves are on `Dispatchers.Default`, which
is the plan's own stop condition about not touching the pen's thread.

The thirty test files were removed from the tablet afterwards.


## Wb1g — a brush as a button, and the eraser's own brush

> 2026-09-12, both asked for by name after the shelf landed. Neither was in the
> plan above; both follow from it, which is what a shelf being *data* buys.

### The eraser gets a brush

Krita ships a soft eraser and a hard one. This app could only erase with the
brush already in your hand — a good default, and still the default, because the
pencil rubbing out with the pencil's tilt is what `ToolItem.ERASER`'s "a toggle,
not a third tool" has always meant. What it could not do is keep a soft rubber
*and* a hard one without changing the brush you draw with.

`Brush.eraseSizeMax` already gave the rubber its own width. This gives it the
rest of its shape, by the same argument, and `InkSurfaceView` now holds three
brushes instead of one:

| | |
|---|---|
| `ink` | what the toolbar configures |
| `rubber` | what the eraser uses, or **null** for `ink`'s own shape |
| `pen` | what the stroke in flight is drawn with, chosen at pen-down |

`pen` used to be both the first and the third, and it stopped being able to be
the moment the rubber got a shape: something has to hold the pencil's numbers
while the rubber is on the glass. The copy at pen-down is also the thread
safety — a slider dragged mid-stroke cannot change the stroke being drawn.

Picked from the shelf's row menu, on every row including the built-ins, because
the pen's hard edge and the pencil's soft one are the two most obvious rubbers
this app ships. It is a toggle: *erase with the brush in my hand* is a state you
have to be able to get back to.

### A brush as a toolbar button

> *"I would like to be able to drag a brush out of the brush selector window and
> have it as a tool button on the toolbar… so the artist can have 2 pencil
> buttons, with different qualities, right next to each other."*

`ToolItem.BRUSH`, and it is **the only entry in the catalogue that carries an
argument**. `CellPlacement.arg` holds the brush id, and the rule that an item
lives in exactly one place is unchanged — of an *item*. What stands on a bar is
an item plus what it is for, so two brush buttons are two controls.

That one field is the whole feature, and it reaches further than brushes: the
layout carries a string it has no opinion about and hands it back to whoever
knows, which is the same discipline that keeps `Dock.kt` free of Compose. The
next control that wants an argument costs nothing.

Its face is the mark it makes, for `ColourButton`'s reason — and it is the only
face that could tell two pencils apart, which is the point of being allowed two.

**Not a drag, and that is a real limitation.** The shelf is a `Popup`, which is
its own window: a gesture begun inside it cannot be handed to the chrome's drag,
whose coordinates are the main window's. What shipped is a row-menu action that
places the button on the first bar with room — a new one beside the shelf only
when every bar is full — and turns arrange mode on, so the next thing the hand
does is drag it where it belongs. The *result* is the request; the gesture is
one tap rather than a drag, and making the drag itself work means either
converting through screen coordinates or rendering panels inside the chrome.

### The version that did not move

`DockCodec` stays at `v5`. `~arg` is a suffix a v5 string never carries, so
everything ever saved parses unchanged; bumping would have meant writing a v5
reader to avoid emptying every toolbar on upgrade, to buy nothing. An older
build reading a newer string drops that one entry — a button missing rather
than a bar.

`DockLayout.of` was the bug this feature nearly shipped with: it deduplicated
placements by `ToolItem`, so the second brush button vanished on every read. The
layout held both and the codec round trip came back with one. It keys on
`CellPlacement.identity` now.

## Wb2: a brush is a tool you own, not a preset you re-open

From the tablet, after an hour of drawing:

> *"The pencil and the pen and the eraser: If you set the settings different,
> they should be remembered. Now if i take the eraser, it is set to 96. I change
> it so 30, because 96 is way too big for what i am doing. I switch back to
> pencil, and then back to eraser: 96 again :( All settings made on a tool
> should be remembered. That is what the user expects anyway."*

That behaviour was not an oversight. It is what `BrushEntry.applyTo` is
documented to do — *be that brush* — and picking a row called it, so the
authored numbers won every time. The argument for it was that a shelf row is a
definition and a definition that drifts is not one.

The argument is wrong, and the last sentence of the report is why. **Nobody
re-sharpens a pencil to the factory point every time they put it down.** The
authored numbers are for the first time you ever pick a brush up. After that the
truth about how wide your eraser is, is whatever you last set it to.

### What was built

`BrushTweaks`, a per-brush store beside `BrushStore`. Putting a brush down
remembers how it was set; picking one up puts that back. A brush that has not
been moved stores nothing, so the file holds only what somebody actually
changed.

Three rules it inherited rather than invented:

- **Only the numbers travel.** The entry is applied whole first and then
  `copyScalarsOnto` moves the scalars over it, so the tip and the sensor wiring
  are always the entry's. A stored text is the one place a *partial* brush comes
  from, which is what `copyWiringOnto` was written for; a tweak must not be able
  to make a pencil that has lost its tilt.
- **A retune forgets.** The tuning number is stored alongside, exactly as
  `BrushStore.storedTuning` does it. A remembered size from before
  `BrushPreset.TUNING` moved would win over every re-solved number forever, on a
  device where nobody could tell why. A saved brush has tuning 0 at both ends of
  that comparison, so a retune never touches one.
- **Revert forgets too.** Putting a row back to what it was authored as also
  drops the tweak, or the button would appear to work and then undo itself the
  moment the brush was put down again.

### What it reversed

This file used to say that picking a different row while modified **discards the
edit**, and that the modified dot is what made that fair. The dot stays and the
discarding is gone: the edit is kept, per brush, and the dot now means *this
brush is not where the shelf says it is* for as long as that is true. **Save as
new brush** is unchanged and is still the way to make a tuning into a thing with
a name of its own.

### The three copies of `adopt` that had drifted

Putting a brush in the hand existed three times in `MainActivity`: `adopt`, the
toolbar's `onBrush`, and the readout's `onWetBrush`, each with its own copy of
*apply the entry, then pull the five sliders back from it*. They agreed by luck.
Adding the tweak store to one of the three and not the others would have been a
pen that remembers and a pencil that does not, so they are one function now —
`pick` — and the toolbar keeps only the one line that is genuinely its own,
turning the marquee off.
