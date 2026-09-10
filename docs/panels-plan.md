# Panels — how a popup becomes a fixed part of the UI

> Authored 2026-09-10, in answer to the user's words:
>
> *"Some buttons, like the layers, the color wheel etc, they open up a popup
> dialog. It would great to be have the user choose (via a "fixate" button) to
> keep those popup dialogs open. Like a widget. It should also be draggable and
> movable, just like a toolbar. But also closable."*
>
> and then, correcting the first attempt at it:
>
> *"Maybe the popup dialogs themself, fit in to the existing system of toolbars,
> no need for a new entity in the UI... the fixed dialog could be just another Ui
> element in another toolbar, just a little bigger"*
>
> **Nothing here has been built.** The first version of this was being built as
> a separate widget layer when the second message arrived; that code was deleted
> rather than committed, and the reason it deserved deleting is the whole first
> half of this document.

## The question, stated exactly

A colour wheel is 268 × 406 dp. A slot is 44dp and a bar is one slot thick. The
wheel therefore cannot be a toolbar item, and today it is not one: `COLOUR` is a
one-slot swatch that opens a `Popup`, and the popup closes when you look away.

The ask is that the popup can be made to stay. The correction is that staying
must not invent a second kind of thing.

So the question is not *"how do we add widgets"*. It is:

> **The placement model has exactly one dimension. What is the cheapest honest
> way to give it a second one?**

Everything below is an answer to that, and the reason each is or is not taken.

## What the model says today

Three types, and it is worth being precise about which one owns what, because
the answer turns on it.

| Type | Owns | Knows about |
|---|---|---|
| `ToolItem` | what a control *is*, and `slots` — its length | nothing |
| `ToolbarLayout` | a line of `slotCount` slots and what sits where | `ToolItem.slots` |
| `DockLayout` | five of those, one per `Dock`, and item uniqueness | `Dock.axis` |

`item.slots` is read in exactly six places, all in `ToolbarLayout`: `endSlot`,
`usedSlots`, two lines of `fits`, one of `place`'s message, and `firstFit`. That
is the whole surface of the one dimension, and it is small. This is a good sign:
whatever we do, it is six lines of algebra and their tests.

The other fact that matters: **`Dock` already carries an axis**, and
`ToolbarLayout` already belongs to exactly one dock. The second dimension is
therefore not missing information — it is information the layout is not being
told.

## Four ways to do it

### 1. A separate widget layer — rejected

A pinned panel becomes its own entity: its own position, its own persistence,
its own drag, its own codec.

This is what was being built when the correction arrived, and the correction is
right. The cost is not the code, which is small; it is that **every question
then has two answers**. Where do my controls live — two places. How do I move
one — two gestures, written twice. What is saved when I quit — two strings, two
migrations, two ways for the next release to lose somebody's layout. The drag
bug fixed in `48c423d` is a fair warning: that was one gesture, in one place,
and it still took a measured reproduction to find. A second placement system is
a second copy of every such bug, and they will not be found on the same day.

There is also a plainer objection. A pinned colour wheel and a docked size
slider are the same thing to the person using them — a control they chose to
have on screen, in a place they chose. A design that makes them different kinds
of object is a design that has stopped describing the user's world.

### 2. Item gains a depth, in the dock's frame — rejected

`slots` stays the length along the bar and the item gains `depth`, the number of
cells it sticks out. Cheap: one field, no change to `ToolbarLayout` at all.

It is rejected because **the item's screen shape then changes with the dock**. A
6-by-9 colour panel is 264 × 396 on the left edge and 396 × 264 on the top one.
Every panel would have to be responsive in both aspect ratios, forever, or look
wrong in half the docks. That is a cost paid once in the model and then again in
every panel that is ever written.

### 3. Item gains a footprint, in screen terms — **recommended**

An item declares `cellsWide × cellsTall`, meaning what it says on the screen.
The dock's axis decides which of the two consumes slots:

| | horizontal dock (top, bottom, floating) | vertical dock (left, right) |
|---|---|---|
| slots consumed | `cellsWide` | `cellsTall` |
| depth (sticks out) | `cellsTall` | `cellsWide` |

The item is `cellsWide × cellsTall` on screen **in both cases**. Only which
dimension is spent on slots changes. A button is 1 × 1 and behaves exactly as it
does now; a colour panel is 6 × 10 and is 264 × 440 dp wherever it is docked.

This is the whole idea, and it is one field plus one rule.

### 4. A panel is a dock — rejected

Make `Dock` open-ended and let a pinned panel be a dock of its own.

More machinery than 3 for no more capability. `Dock` is an enum today: a map
key, a persisted name, a hit-test rectangle, an alignment. Making it dynamic
touches all four and buys nothing that a footprint does not.

## The recommendation, as a mechanism

### The footprint, and the one exception that is not an exception

Every item declares a footprint in cells. Most items are 1 × 1. A slider is
4 × 1. A colour panel is 6 × 10.

A slider is the one control whose long axis genuinely *should* follow the bar —
it is a one-dimensional thing, and a slider laid down the left edge wants to be
a vertical slider, which is what it is today. A panel is a two-dimensional thing
with a shape of its own and must not rotate.

Rather than special-casing `ToolKind`, say it out loud as data:

```
ToolItem(cellsWide = 4, cellsTall = 1, turnsWithDock = true)   // a slider
ToolItem(cellsWide = 6, cellsTall = 10, turnsWithDock = false) // a panel
ToolItem(cellsWide = 1, cellsTall = 1)                          // every button
```

`turnsWithDock` swaps the footprint on a vertical dock. For a 1 × 1 item it
cannot matter, which is why the default is free. Two lines of code, and the
distinction is testable rather than remembered.

### `ToolbarLayout` should stop knowing what a `ToolItem` is

The tempting change is to give `ToolbarLayout` an axis so it can ask the item
how long it is. The better one is the opposite: **take `ToolItem` out of
`ToolbarLayout` entirely and let it allocate spans.**

`Placement` becomes `(item, slot, span)`, where `span` is computed by whoever
knows the axis — `DockLayout` — and `ToolbarLayout` becomes a pure interval
allocator over a fixed number of slots. `fits(span, slot)`, `place(item, slot,
span)`, `endSlot = slot + span`.

That is smaller than what is there now, not bigger, and it is the version in
which a button, a slider and a panel are provably the same thing: three spans.

**`span` is derived, never persisted.** A stored span could disagree with the
item's declared size after an upgrade, and then the bar would be laid out to a
number no code believes any more. It is recomputed on decode from the item, the
dock's axis and the expanded flag below.

### "Fixate" is placing, and closing is removing

This is the part that makes the whole thing one concept instead of two:

- **Fixate** = the item takes its full footprint, in the dock it is already in.
- **Close** = it goes back to being a button, or is removed from the bar.
- **Move** = drag it, which already works, unchanged.
- **Persist** = the dock codec, which already works, one character longer.

So `Placement` carries `expanded: Boolean`, and `span` is `1` when collapsed and
the footprint when expanded. The popup's fixate button sets it; the panel's own
collapse control clears it.

That keeps **one catalogue entry per idea**. `COLOUR` is a swatch when collapsed
and the wheel when expanded — not two entries that have to be kept in step, and
not an entry that is invisible until you have found the other one.

It also gives an honest failure: fixating needs six free slots and the bar may
not have them. `fits` already answers that, the chooser already knows how to
grey something out, and the popup can say *"no room on this edge"* rather than
appearing to do nothing.

### The bar stays one slot thick; the panel overhangs

The bar's translucent ground does not grow. An expanded item draws **its own
card**, anchored to its slots, extending away from the edge over the canvas.

Two reasons. It is what a docked palette looks like in every program that has
one, so it needs no explaining. And it means nothing about bar layout changes —
the run of slots, the scroll, the drop arithmetic fixed in `48c423d`, all of it
is untouched.

It also lands exactly on the rule already in force: **the bar is translucent and
the things on it are opaque.** A panel is a thing on the bar. It is opaque.

## What it costs

Stated separately from what it buys, because this is the part that goes wrong
quietly.

**`Placement` gains two fields and that touches the codec.** `DockCodec` goes to
`v3`, keeping the `v2` and `v1` readers, exactly as `v2` kept `v1`. The entry
format grows an optional expanded marker. The rule that decoding never throws is
unchanged and now covers one more thing: an unreadable marker decodes as
collapsed, because a collapsed control is visible and a dropped one is not.

**Twenty-four `ToolbarLayout` tests are touched, mechanically.** They construct
`of(12, listOf(Placement(SIZE, 0)))`. They would construct spans. This is
tedious rather than risky — every one of them is a JVM test that fails loudly.

**Arrange mode has to show the footprint.** A chip standing in for a 6 × 10
panel must be 6 × 10, or you are placing something whose size you cannot see.
Drag and drop are unchanged: it is still one `slot` along one axis.

**Overhangs can collide.** A panel on the top dock and one on the left dock can
overlap in the corner. The recommendation is to allow it and say so, because the
alternative is a constraint solver for a situation the user created on purpose
and can fix with one drag.

**A point over an overhang is not over the bar.** `dockAt` hit-tests bar
rectangles, and the overhanging part of a panel is outside its bar's rectangle,
so dropping a control onto a panel would fall through to the floating dock. The
fix is to include overhangs in the dock's hit rectangle, and it has to be
remembered at the time or it becomes a bug report about drops landing in the
wrong dock.

**The cell is not square.** `Chrome.SLOT` is 44dp along a bar and
`Chrome.BAR_THICKNESS` is 52dp across it. A footprint in cells has to pick one,
and it should pick `SLOT`: the bar's extra 8dp is padding around a control, not
a unit of layout. A panel's card is then `cellsWide × 44dp` by `cellsTall ×
44dp`, and it is the bar that is slightly thicker than its own cells, which is
already true today.

## Open questions that need a human answer

1. **Does fixating put the panel where the button was, or where there is room?**
   Where the button was is predictable and often refused for want of slots.
   Where there is room always works and moves the control away from the finger
   that just asked for it. The cheap answer is: try in place, fall back to the
   first fit in the same dock, and only then refuse.

2. **Should a panel be allowed on any edge?** A 10-cell-tall panel needs ten
   slots of a twelve-slot side dock — nearly the whole edge. It fits, and it
   leaves that edge good for nothing else. Worth allowing anyway, or worth
   restricting panels to the floating dock and the long edges?

3. **Does the floating dock become the natural home for panels?** It is the one
   dock whose position is already the user's own, and a floating panel is what
   was originally asked for. Making it the default target for fixate would give
   the original request literally, inside the model this document argues for.

## Work plan

Sized against the docking work, which was one sitting and one build.

| Item | What | Unlocks |
|---|---|---|
| **P1** | `Placement` gains `span` and `expanded`; `ToolbarLayout` becomes an interval allocator that has never heard of `ToolItem`; tests move over. No rendering, no new items. | everything below |
| **P2** | `ToolItem` gains `cellsWide`, `cellsTall`, `turnsWithDock`; `DockLayout` computes the span from the dock's axis. `DockCodec` → `v3`. | footprints |
| **P3** | Render an expanded item as a card overhanging its bar. Arrange mode shows the footprint. Fix `dockAt` to include overhangs. | panels on screen |
| **P4** | `COLOUR` becomes a panel: 1 × 1 collapsed, 6 × 10 expanded. The popup grows a fixate button; the card grows a collapse. Delete nothing else — the popup stays, because it is still the cheap path. | the feature as asked |
| **P5** | The tool-settings panel the UI plan's U6 wants, as a second panel, to prove the concept repeats. | U6 |

P1 and P2 are JVM-only and testable without a device, which is the same
property that made the dock work go well and is worth protecting.

## Stop conditions

1. **P1 cannot be done without `ToolbarLayout` importing `ToolItem` again.**
   Then the generalisation has not simplified anything and the design is wrong.
   Stop and re-derive it.
2. **A panel's footprint has to differ per dock to look right.** Then design 3
   has collapsed into design 2 and every panel owes responsiveness forever.
   Stop; it is cheaper to restrict where panels may go.
3. **Fixate needs a gesture that races the canvas.** Arrange mode is the settled
   answer to that class of problem and a second answer would make the first
   unreliable.
