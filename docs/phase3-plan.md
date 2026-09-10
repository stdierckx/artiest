# Phase 3 — Selection, and the compositor that has to come first

> Authored 2026-09-10, against the code as it stands at `e5e4364` and against
> four measurements taken before the plan was written. Those measurements are in
> `app/src/test/.../doc/CompositeBench.kt` and they are **host numbers on
> software Skia** — see **The measurements** for which of them transfer to the
> device and which do not. Every number below marked *predicted* is a claim
> awaiting refutation, and **S0 exists to refute them**.
>
> The request this plan answers, in the user's words: *"marquee tool: marching
> ants selection: rectangular, circular and free draw selection. Boolean
> operations on a selection: addition, subtraction. Selection: only being able
> to draw in the selection (= an important tool / strategy for the artist).
> Selection: transform, rotate. Layer blending options, layer transparency,
> layer transform and rotate."*
>
> **Revised the same day**, once, on the user's reading of the plan: *"only
> turning and moving a selection, it should update once every 120ms, so the user
> knows where it is being dropped."* That answers the one thing this plan had no
> device number for and had been prepared to solve badly. See **The float** —
> the outline is free and tracks the pen, the pixels refresh on a 120 ms floor,
> and the wireframe-only fallback the risk table was holding in reserve is gone.

## The decision

Phase 3 builds **one selection, held as a path, that confines the ink by
masking the scratch buffer** — and **one floating-pixels object** that carries
both "move the selected pixels" and "rotate this layer", because they are the
same operation with a different source.

Four things follow from the code as it stands, and each of them cuts a plausible
plan out of the running.

**Layer transparency is already shipped, and so is most of what a selection
needs.** `LayerStack` has opacity per sheet with a slider in the panel; that
line of the request is done and this plan does not rebuild it. What is more
useful: the machinery a selection needs already exists for other reasons.
`CommitQueue` is the one UI→render crossing and already carries a sealed
`LayerOp` beside strokes, which is exactly the shape "select this region" needs
and for exactly the same reason. `ScratchLayer` already puts every translucent
stroke on its own surface before it reaches the page, which is the one place a
mask can be applied so that the wet stroke and the committed one cannot
disagree. `PixelPatch` already snapshots a rectangle of one identified sheet,
which is what a lift-and-drop has to record. Phase 3 is mostly a matter of
pointing things that exist at a new job.

**The clip cannot be a clip.** The obvious implementation of "only draw inside
the selection" is `canvas.clipPath` at the two places ink is drawn. It does not
work here, and the reason is measured: the layer's `Canvas` is a software canvas
over a `Bitmap`, where Skia antialiases a path clip — the bench sees alphas of
0, 128 and 255 along a 45-degree clip edge — while the frame's `Canvas` comes
from `CanvasFrontBufferedRenderer`, which holds an `android.graphics.RenderNode`
(read out of the 1.0.4 aar with `javap`, not assumed), so it is a hardware
recording canvas, where clip edges are hard. Clipping both would give a wet
stroke with a stair-stepped selection edge that snapped smooth at pen-up. So the
mask is applied to the **pixels** — one `DST_IN` into the scratch buffer, which
both paths read — and not to either canvas.

**A floating selection must not touch the layer until it is dropped.** The
textbook model lifts pixels out of the sheet, floats them, and writes them back;
it needs two undo steps for one move, and a cancel has to be a third operation
that puts things back. Keeping the source pixels in place and merely *hiding*
them while the float is live makes cancel free — nothing was written — and makes
the whole move a single `PixelPatch` over the union of where the pixels were and
where they went. One press of undo, one rectangle, and no state to unwind. See
**The float**.

**Blend modes are a change to a compositor that currently exists twice.** The
sheet-by-sheet loop is written once in `InkSurfaceView.compositeStack` and again
in `PngExporter`, and they already differ in a way that a blend mode turns into
a bug: on screen the paper is painted *under* the stack before any sheet is
drawn, while the export paints the stack first and slides the paper underneath
with `DST_OVER` at the end. Those are the same image for `SRC_OVER` sheets and
different images the moment one of them multiplies, because a multiply against
transparency is not a multiply against paper. So the compositor is extracted
**before** blend modes are added to it, and the extraction is a work item with
no user-visible change and a test that the two agree.

## The measurements

Taken on the host before this plan was written, with `CompositeBench`. Reps are
means of 10–200 runs after three warm-ups.

| | ms |
|---|---|
| mask rebuild: clear + antialiased path fill, full page `ALPHA_8` | **0.31** |
| `Path.op` UNION of two 240-segment lassos | **0.32** |
| allocate + clear a full-page `ALPHA_8` mask | **0.16** |
| `DST_IN` a 400×400 scratch buffer by the mask | **0.36** |
| dry-frame shape: 1 plain sheet at fit zoom | 3.49 |
| dry-frame shape: 2 / 4 / 8 plain sheets | 6.46 / 10.35 / 20.80 |
| dry-frame shape: 8 sheets, one of them MULTIPLY | 29.05 |
| dry-frame shape: 8 sheets, three of them MULTIPLY | 49.25 |
| dry-frame shape: cached backdrop, three blits | 7.98 |
| live: rotate a full page into the view | 13.27 |
| live: rotate a quarter page into the view | 2.32 |
| **drop:** rotate a full page into the document | **41.64** |
| **drop:** rotate a quarter page into the document | **10.76** |

**The rows in bold transfer to the device; the rest do not, and it matters
which.** The mask, the boolean op, the scratch `DST_IN` and the drop all write
into `Bitmap`s the app owns and the CPU touches — `Layer`'s pixels,
`ScratchLayer`'s buffer, the selection mask — so software Skia on the host is
measuring the same code the tablet will run, on a faster CPU. The dry-frame and
live rows are not: those blits go to the GPU through the renderer's `RenderNode`,
and the host bench has no GPU in it at all. They are kept because they say what
the work grows *with*, and because `PngExporter` genuinely does composite on the
CPU and pays them as written.

**What they decided.**

1. **A full-page mask can be rebuilt from nothing on every selection edit.** At
   0.31 ms there is no case for incremental mask updates, no case for tiling the
   mask, and no case for keeping the mask and the path in sync by any means
   other than throwing the mask away. That deletes the most bug-prone thing in
   the design before it is written.
2. **Boolean operations are free.** `Path.op` at 0.32 ms means add, subtract,
   intersect and invert are a one-line call on the path, and the mask is simply
   rebuilt after. No custom geometry, no scanline merge, no mask arithmetic.
3. **A float is resampled once, at drop, and never per frame.** 41.64 ms for a
   full page and 10.76 for a quarter is fine as a one-off at the end of a drag
   and impossible as a per-frame cost. This is why the float holds its original
   pixels and a `Matrix` rather than resampled pixels: nudge it thirty times and
   it resamples once.
4. **The compositor is worth looking at before blend modes go into it.** Eight
   plain sheets cost 20.8 ms of CPU compositing and one MULTIPLY sheet adds
   8.3 ms on top — a blended sheet is about three and a half times a plain one.
   Three blits of a cached backdrop cost 7.98 ms regardless of depth. Whether
   any of that matters on the device is unknown, because the screen path is on
   the GPU. **It is measured in S0 and the cache is gated on the answer**, in
   the same way Phase 2 gated its GL rewrite and did not fire it.

## What Phase 2 and the layers work left standing

- **`CommitQueue` is the only crossing, and it is already a sealed hierarchy.**
  Adding `Commit.Select` beside `Commit.Layers` costs one branch in `drain` and
  one method on `Sink`. The ordering argument is verbatim the one already
  written for `Commit.Layers`: *"select this region" means after everything I
  have drawn*, and a stroke finished a millisecond ago that the render thread
  has not stamped yet is part of what the user drew.
- **`ScratchLayer` is the choke point for ink.** Every translucent stroke
  already goes through it; `indirectNeeded()` decides. Making a live selection
  force that to true gives exactly one place to mask.
- **`PixelPatch` carries a layer id.** A patch whose sheet has been deleted
  restores nothing and is consumed. The float's drop patch inherits that for
  free.
- **`Layer`'s lock is a leaf.** Nothing in this plan may hold two. The float
  copies out under one lock and writes back under the other, which is what
  `LayerStack`'s duplicate already does and for the same stated reason.
- **The Compose overlay is already in front of the surface.** `BrushCursor`
  reads position through lambdas inside a `Canvas` draw lambda, so a hover at
  200 Hz invalidates the draw phase and recomposes nothing. The marching ants
  are the same trick with a different shape.
- **Thumbnails taught the resampling lesson the hard way.** A single filtered
  `drawBitmap` at 26:1 came back blank because a bilinear filter samples a 2×2
  neighbourhood however far apart the taps are. `LayerStack.buildThumbnail`
  halves repeatedly instead. The float's drop has the same problem at any scale
  below 0.5 and gets the same answer.

## Architecture

### The selection: a path, a mask, and a rectangle

One selection per **document**, not per layer. Photoshop, Krita and GIMP all do
it this way and the reason is the workflow the user named: a selection is a
stencil you hold over the drawing while you work through several sheets, and one
that vanished when you changed sheet would have to be redrawn every time it
became useful.

```
class Selection {
    val path: Path          // document coordinates; empty means "everywhere"
    val mask: Bitmap?       // ALPHA_8, full page, rebuilt whenever path changes
    val bounds: Rect        // integer, the path's extent, clipped to the page
    val isEmpty: Boolean    // no selection at all — not "an empty region"
}
```

**The path is the truth and the mask is a cache.** Every operation is expressed
on the path: a rectangle is `addRect`, an ellipse is `addOval`, a lasso is the
pen's own trail closed at the end, and add/subtract/intersect are
`Path.op(other, UNION | DIFFERENCE | INTERSECT)`. The mask is then rebuilt from
the path in one antialiased fill, at 0.31 ms, and is the only thing the ink path
ever reads.

**Empty is not a region.** `isEmpty` means there is no selection and the whole
page is drawable; a selection that has been reduced to nothing by a subtraction
means nothing is drawable. Conflating them makes the eraser stop working after
an unlucky boolean op with no way to tell why.

**Mutation is on the render thread, through the queue**, exactly like
`LayerOp`:

```
sealed interface SelectOp {
    class Set(val path: Path, val op: Path.Op) : SelectOp   // rect, ellipse, lasso
    object All : SelectOp
    object None : SelectOp
    object Invert : SelectOp
}
```

**The published `Path` must be a copy, and this is the one new threading rule
Phase 3 adds.** `android.graphics.Path` is mutable native state. `LayerStack`
publishes a `List<LayerInfo>` of strings and floats, which is safe because
nothing in it can change; a `Path` handed to the UI while the render thread
still holds the same instance is a shape changing underneath a draw call. So
`publish()` builds `Path(current)` — a copy — and the render thread never
touches the published one again. 240 segments copy in microseconds and the
publish happens at most once per selection edit.

### Confining the ink

**A live selection forces the indirect path.** `indirectNeeded()` gains
`|| document.selection.isActive`, so every stroke — pen, pencil, marker, eraser
— accumulates on the scratch buffer before it reaches a sheet. That costs the
scratch path, which is already shipped and already measured, and it buys exactly
one place to apply the mask.

**The mask is applied to the scratch, in place, after the dabs are laid**: one
`DST_IN` of the mask's sub-rectangle over the buffer's used area, measured at
0.36 ms for a 400×400 region. Three things fall out of it that are worth stating
because none of them needs any further code:

- The wet stroke and the committed stroke are the same pixels, so there is no
  edge that changes at pen-up.
- Erasing is confined too, because a masked scratch subtracted with `DST_OUT`
  can only subtract where the mask let it through.
- Burnish, grain and the self-composed shader all read the same buffer, so they
  inherit the confinement without knowing about it.

`DST_IN` with a fixed mask is idempotent, so applying it after every batch
during the wet pass — rather than tracking which dabs are new — is correct as
well as simple. The one real piece of care is that `ScratchLayer` grows and
moves its origin as a stroke wanders; the mask sub-rectangle has to be recomputed
from `originX/originY/usedWidth/usedHeight` each time, not cached.

**Clear, inside a selection, clears the selection.** That is what every editor
does and it is the behaviour the request implies. `onClear` masks rather than
blanks, and `snapshotBeforeClear` narrows its patch to the selection bounds,
which also makes it cheaper.

**Undo patches narrow to the selection.** A stroke's bounds intersected with
`Selection.bounds` is the region that can possibly have changed. Free, and it
keeps the 48 MB history budget from being spent on rectangles that could not
have moved.

### Marching ants

**In the Compose overlay, not in the render path**, beside `BrushCursor` and for
the same reason it is there: the ants animate continuously, and every frame of
that animation in the render path would be a full `SurfaceControl` transaction —
`GestureController` exists entirely to stop that happening for gestures.

- Read the published path and the live `CanvasTransform` through lambdas inside
  the `Canvas` draw lambda, so the animation invalidates the draw phase and
  recomposes nothing.
- Transform the document-space path into view space per frame with the same
  matrix the renderer uses, and stroke it **in view space**, so the ants are one
  screen pixel wide and their dashes are a fixed screen length at every zoom. A
  document-space stroke would give ants that vanished when zoomed out and turned
  into slabs when zoomed in.
- Two passes: white dashes, then black dashes with the phase offset by half a
  period, so the outline reads over ink and over paper. That is what makes them
  ants rather than a dotted line.
- Advance the phase from `withInfiniteAnimationFrameNanos`, which is
  frame-clocked and stops when the composition leaves.

**Lasso paths are simplified as they are built**, to roughly 2 document pixels
between points. The digitizer reports up to 320 samples a second, so a
three-second lasso is close to a thousand points; transforming and stroking that
per frame is affordable but pointless, and `Path.op` grows with it. The
stabilizer already establishes that the raw sample stream is not the thing to
keep.

### The float

One object carries every pixel move in this plan:

```
class FloatingPixels {
    val sourceLayerId: Int
    val sourceBounds: Rect        // where the pixels came from
    val pixels: Bitmap            // a copy, already masked by the selection
    val mask: Bitmap?             // null when the source was a whole layer
    var matrix: Matrix            // what the user has done to it so far
}
```

**Lift does not modify the layer.** It copies the selection's bounding rectangle
out of the active sheet, applies the mask to the copy, and hands it over. The
sheet is untouched. What changes is what the *compositor* draws: while a float
is live, the active sheet is drawn with its selected region punched out — one
`saveLayer` and one `DST_OUT` of the mask, only over the float's source bounds,
and only while transforming — and the float is drawn immediately above it
through its matrix.

Three things follow, and they are the reason for the design:

- **Cancel is free.** Drop the float, redraw. Nothing was written, so nothing
  has to be put back.
- **A move is one undo step.** At drop, capture a single `PixelPatch` over the
  union of the source bounds and the transformed bounds — one rectangle, one
  press of undo — then erase the source region and draw the float through its
  matrix.
- **Quality does not decay.** The float always holds the pixels as they were
  lifted, so twenty nudges and three rotations resample once, at drop.

**Drop resamples by halving when the matrix scales below 0.5**, which is the
thumbnail lesson applied forward: a bilinear filter samples a 2×2 neighbourhood
whatever the reduction, so a pencil line falls between the taps and disappears.
Halve the float until the remaining scale is above 0.5, then do the final blit.

**The outline follows the hand; the pixels catch up every 120 ms.** Decided by
the user, and it settles what would otherwise have been the phase's most
expensive open question. The two halves of the preview cost wildly different
amounts and there is no reason to run them at the same rate:

- **The box and the ants are free.** They are stroked in the Compose overlay,
  which costs a draw pass and no `SurfaceControl` transaction at all, so they
  track the pen at whatever rate the pen reports. The user always knows where
  the selection *is*.
- **The pixels are not.** Drawing the float means a dry render — the compositor
  repaints the stack and resamples the float through its matrix — measured at
  13.27 ms for a full page and 2.32 for a quarter, on top of the stack itself.
  At 90 Hz that is a redraw asking for more than it can have.

So the float's pixels refresh on a **120 ms floor**: about eight times a second,
enough that the user can see what is landing where and correct before letting
go, and about an eighth of the duty cycle even in the full-page worst case. It
also throttles the stack repaint that comes with it, which is the larger half of
the cost and the half this plan cannot yet put a device number on.

`FLOAT_PREVIEW_MS = 120` is one constant, and the throttle is
`GestureController`'s discipline with a floor added: at most one render in
flight, coalesced onto the frame clock, and now also never sooner than 120 ms
after the last one. Reusing that class's shape rather than inventing a second
one matters, because the half of it that is hard-won is the
`dryRenderInFlight` handshake — without it a slow render is followed
immediately by another and the queue never drains.

**Nothing about this changes the drop.** The 120 ms preview is a preview: it
resamples from the float's original pixels every time, so eight refreshes a
second cost nothing in quality, and the committed blit at drop is still the only
one that writes.

**The transform box lives in the Compose overlay and owns the pen while it is
live.** Drag inside to move, corner handles to scale, a handle above the box to
rotate. It is in the overlay rather than in `InputRouter` because there is
nothing to route: while a float is live there is no drawing to arbitrate against
and no palm to reject that a `pointerInput` cannot handle itself. It is
deliberately **not** the two-finger gesture, which belongs to the canvas and must
keep belonging to it — `StrokeExclusivity` is the settled answer to that class of
problem and a second answer would make the first one unreliable.

**Layer transform is the same float with a different source.** No selection, the
whole sheet lifted, `mask` null, and the same handles, the same live preview and
the same drop. That is the main structural reason the selection transform is
built first: building the layer transform on its own would produce a second
mechanism that has to be retired the moment the selection one arrives.

### One compositor, and blend modes in it

`StackCompositor` moves the sheet-by-sheet loop into `:app/doc`, taking a
`Canvas` in document space plus the stack, the float and the wet scratch, and is
used by `InkSurfaceView` on both its paths and by `PngExporter`. It has a test
that composites the same document twice and asserts the two are pixel-identical,
because "the export does not match the screen" is otherwise the kind of defect
that is found by a user, months later, in a file.

**Paper becomes the bottom of the stack in both.** The export's `DST_OVER`
trick has to go: it exists to save a full-canvas `drawColor` and it produces a
different image the moment a sheet blends. The cost is the measured 12.9–13.8 ms
that trick was avoiding, off-lock, out of an export that takes 450 ms. The 24-bit
PNG saving is unaffected — an opaque paper still leaves every pixel at alpha 255.

**Blend modes are `Paint.blendMode`,** which is API 29 and `minSdk` is 29. The
list is short on purpose, because a painting app with thirty blend modes is a
compositing app: **Normal, Multiply, Screen, Overlay, Darken, Lighten,
Difference**. Each is a dropdown on the layer row, beside the opacity slider
that is already there. Nothing is persisted, because nothing about a document is
persisted yet — that is Phase 4's `.ora`, and this plan adds one enum to what it
will have to write.

The one place blend modes are genuinely awkward is the active sheet during a wet
stroke, where the compositor already takes an offscreen layer when the sheet is
translucent or being erased. `saveLayerAlpha` becomes `saveLayer` with a paint
carrying both the alpha and the blend mode, so the wet stroke blends with the
sheet it is going onto before the pair of them blend with what is underneath —
which is what a painter means by drawing on a multiply layer.

## Work plan

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| **S0** | **The before-picture, on the tablet.** Port `CompositeBench` to a device path: is the dry canvas hardware-accelerated, what does a dry frame cost at 1, 4 and 8 sheets, what does one MULTIPLY sheet add, what does a quarter-page rotated blit into a `Layer` cost. Record it here. | `:app`, device | Low | — | 0.5 |
| **S1** | **`StackCompositor`.** One loop, used by the screen and the export. Paper to the bottom of the stack in both. No visible change; a test that the two agree pixel for pixel. | `:app` | Low | — | 0.5 |
| **S2** | **`Selection`** — path, mask, bounds — and `SelectOp` through `CommitQueue`. Model and tests only, no UI, no ink changes. The published `Path` is a copy. | `:app` | Low | — | 1 |
| **S3** | **Confining the ink.** A selection forces the indirect path; the scratch is masked in place; clear and the eraser are confined; undo patches narrow. Pixel tests that ink outside the selection changes nothing and that the wet and dry edges are identical. | `:app` | **High** | S2 | 1.5 |
| **S4** | **The marquee tools.** `SelectDriver` beside `StrokeDriver`, chosen once per stroke; rectangle, ellipse and lasso; add/subtract/intersect; select all, none, invert. Lasso simplification. | `:app` | Med | S2 | 1.5 |
| **S5** | **Marching ants** in the overlay: view-space stroking, two passes, frame-clocked phase. | `:app` | Med | S2 | 1 |
| **S6** | **The float.** `FloatingPixels`, the punch-out in the compositor, the transform box in the overlay, drop as one patch, halving below 0.5 scale, cancel. The box tracks the pen; the pixels refresh on a 120 ms floor. | `:app` | **High** | S1, S3 | 2 |
| **S7** | **Layer transform**, as the same float over a whole sheet. Should be small if S6 is right; if it is not small, S6 is wrong. | `:app` | Low | S6 | 0.5 |
| **S8** | **Blend modes** in `StackCompositor` and a control on the layer row. | `:app` | Med | S1, S0 | 1 |
| **S1b** | **GATED. The cached compositor**: everything below the active sheet in one bitmap, everything above in another. Entered **only** on an S0 measurement that names what it fixes. | `:app` | **High** | S0 | 2 |
| **S9** | **Feel pass** on the tablet, by the person holding the pen. Reconcile this document against what was measured. | device, docs | Low | S7, S8 | 0.5 |

**≈10 days if S1b does not fire, plus 2 if it does.** Same caveat every estimate
in this repo has earned: the work happens in sittings, not days, and the items
that run long will be the ones where this plan is wrong about the hardware.

**Cut order**, decided now while it is cheap: **S8** (blend modes — a layer stack
with opacity is already useful and multiply is a nicety), then **S7** (layer
transform — the selection transform can do it by selecting all), then **S5**'s
animation reduced to a static outline. **Do not cut S0** — Phase 1's strongest
lesson is that a measurement harness with no review pass produces confident wrong
answers, and half the numbers this plan rests on are from a machine with no GPU
in it. **Do not cut S1** — it is half a day, and it is the difference between one
compositor and two that drift.

**S0 and S1 are independent of everything else and can be done first, in one
sitting.**

## Deliberately not in Phase 3

- **Feathering, and a selection anti-alias slider.** The mask is `ALPHA_8` and a
  feather is a blur of it, so the door is open; nothing in the request asks for
  it and a feather radius is one more control to explain.
- **Magic wand, select-by-colour, select-by-layer-transparency.** Not asked for.
  Each is a flood fill or a threshold over a full page, and each wants its own
  tolerance control.
- **Perspective, skew and warp.** The float carries a `Matrix`, so an affine
  transform is what it does. Free-corner perspective needs a different sampler
  and a different set of handles, and it is a phase of its own.
- **Transforming several layers at once, and linked layers.** One float, one
  source.
- **Layer masks and clipping masks.** They are the other half of what a
  selection becomes in a mature editor, and they change the document model —
  every sheet gains a second full-page channel. Phase 4 at the earliest, and
  only if the drawing wants it.
- **Selection in the undo history.** Deliberate, and it has a cost worth stating:
  a mis-drawn lasso is redrawn rather than undone. The alternative is worse —
  `UndoHistory` is `UndoHistory<PixelPatch>` and its `exchange` returns a patch,
  so admitting a non-pixel step means widening it to `UndoStep` with
  `restoreInto` and `recapture` on the interface, and the result is that "undo
  my last stroke" takes four presses because three selections happened in
  between.
- **Saving or naming a selection.** Nothing about a document is persisted yet.

## Risks

| Risk | Signal it is happening | Response |
|---|---|---|
| The wet stroke's selection edge does not match the committed one | S3: a pixel test of the edge disagrees between the two paths, or the edge visibly snaps at pen-up on the tablet | This is the stop condition, not a tuning problem. The scratch mask is the design; if it does not hold, the design is wrong |
| The ants cost a frame | S5: `dryRenderInFlight` rises, or the readout's frame times move while a selection is on screen | The ants must invalidate the draw phase only. If they cannot, draw a static outline and animate nothing |
| A lasso produces a path `Path.op` chokes on | S4: a slow selection after a long free-hand loop | Simplify to 2 document pixels on the way in. The measurement is at 240 segments; a thousand is not measured |
| The float's preview cannot hold even 120 ms | S6, on the tablet: the pixels lag the outline by more than a beat, or the refresh eats the drop | Clip the float's blit to its transformed bounds, which the bench says is where the cost is — a quarter page is 2.32 ms against 13.27 for a full one. The outline is unaffected either way: it is in the overlay and free |
| The outline and the pixels disagree by enough to mislead | S6: at 120 ms a fast drag puts the box a long way from the pixels under it | This is the trade the 120 ms buys and it is the right way round — the box is where the pixels *will* land. If it reads badly, raise the rate rather than dropping the box |
| The drop hitches visibly | S6: 41 ms for a full page, measured, on the render thread inside a commit | Accept it for a whole-page transform and say so; it happens once, at the end of a deliberate gesture. If a quarter-page drop hitches, something else is wrong |
| A scaled-down float loses thin lines | S6: the thumbnail bug again, in a new place | Halve until the residual scale is above 0.5. The code to copy is `LayerStack.buildThumbnail` |
| The screen and the export disagree once blend modes exist | S8, after S1 was skipped or done badly | S1's pixel-identity test is the guard. If it is not written, this risk is a certainty rather than a risk |
| The published `Path` is mutated under the UI thread | A shape that flickers or tears while the ants animate | Copy on publish. Stated in S2 and it is the one new threading rule in the phase |
| Blend modes make a dry frame unaffordable | S0/S8: a pinch with a multiply layer drops frames on the tablet | S1b, gated. The cache is the answer the bench already priced at three blits |
| Memory | 8 sheets 217.5 MiB + mask 6.80 MiB + float up to 27.19 + a drop patch up to 27.19 | ≈279 MiB worst case against 4.4 GiB measured free in W0. Stated so it is arithmetic rather than a hope |

## Stop conditions

1. **S3 cannot make the wet stroke and the committed stroke agree at the
   selection edge.** Stop. "Only being able to draw in the selection" is the
   line of the request that matters most, and a selection whose edge moves at
   pen-up is not a selection, it is a hint.
2. **S2's `Selection` cannot be tested without a device.** Then the model has
   picked up a rendering dependency it should not have. The mask needs
   Robolectric with native graphics — that is expected and `ScratchLayerTest`
   already does it — but the path, the boolean ops and the bounds must be
   decidable on the JVM, or the design has put policy in the renderer again.
3. **S6 needs the two-finger gesture.** Stop. The canvas owns two fingers, and
   Phase 1 paid for `StrokeExclusivity` precisely so that ownership is not
   re-argued per feature.
4. **S0 says the dry canvas is *software*.** Then this plan's stack numbers are
   the app's real numbers, eight sheets already cost two frames at 90 Hz, and
   **S1b stops being gated and becomes the first thing built.**

## Open questions that need a human answer

1. **How should add and subtract be chosen with no keyboard?** The proposal is a
   three-way mode in the selection panel — New / Add / Subtract — *plus* the
   barrel button as a momentary subtract while the marquee is in hand, which is
   the same shape the eraser already has and needs no extra chrome. It is a
   guess about the hand and it should be judged on the tablet in S4.
2. **Is a selection a stencil or a lasso?** This plan makes it document-wide, so
   it survives changing sheet. The alternative — a selection per layer — matches
   nothing else and is offered only because it was not stated either way.
3. **Should the eraser be confined by the selection?** This plan says yes,
   because it is the same brush machinery and because "protect everything
   outside" is the point. The other reading is that an eraser is for mistakes and
   should not be fenced in. One line to change either way.
4. **Does the transform need scale, or only move and rotate?** Scale is nearly
   free — the same matrix — but it is the one that makes resampling quality
   visible, and it is the reason halving is in S6.
5. **Which seven blend modes?** The proposal is Normal, Multiply, Screen,
   Overlay, Darken, Lighten, Difference. Multiply and Screen carry most of the
   value for drawing; the rest are cheap to add and easy to remove.
