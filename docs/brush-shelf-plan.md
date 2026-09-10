# Wb1 — The brush shelf

> Authored 2026-09-10, as the first item of `docs/brushes-plan.md`. Nothing here
> is built yet. It is the item that has to land before importing brushes means
> anything, and it is worth having on its own: a user who tunes a pencil they
> like currently has nowhere to put it.

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
