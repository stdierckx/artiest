# The Inker — the stroke that survives pen-up, and the guides it is drawn against

> Authored 2026-09-13, against the code as it stands at `b163345`, and against
> three feature menus written on 2026-09-10 — `docs/vector-plan.md`,
> `docs/guides-plan.md` and `docs/layer-effects-plan.md`. Those documents chose
> nothing; this one chooses. **Nothing in it has been measured**, and every
> number marked *predicted* is a claim awaiting refutation in the sense Phase 2
> established. **Ik0 exists to refute the most expensive of them before the
> expensive work starts.**
>
> It is the plan for **P4/Inker** in `docs/master-plan.md`, and it assumes
> **P3 (the sketcher) is finished** — draw-and-hold, mirror, alpha lock,
> clipping and merge are that plan's, not this one's, and nothing here waits on
> them except the feel of the app it lands in.

## The decision

The Inker is **two subsystems and one idea**, and the idea is what makes the
price bearable:

> **A vector sheet is not a new kind of layer. It is a raster sheet that kept
> the strokes that made it, and can therefore rebuild itself.**

Everything downstream of a layer — `StackCompositor`, `PngExporter`,
`Thumbnails`, blend modes, opacity, the layers panel, the `.ora` export, the
project file — goes on seeing exactly what it sees today, because the pixels are
still there in a `Layer`. What changes is that the sheet also holds the list of
`StrokeRecord`s that produced those pixels, and can re-render any rectangle of
itself from that list.

That single framing collapses most of `docs/vector-plan.md`'s Tier 0. Item 1
(a vector layer type) becomes a field on `LayerStack.Entry`. Item 3 (the render
cache) already exists and is called `Layer`. Item 2 (resolution-independent
redraw) stops being the foundation and becomes one late, gated item, because
sharpness at 400% is not what an inker asks for first — **erasing an overshoot
back to the junction is**, and that needs the stroke list, not a tile pyramid.

Three consequences follow, and each cuts a plausible plan out of the running.

**The pencil must keep working, so model A is out and SVG is late and lossy.**
`docs/vector-plan.md` names this the biggest fork in the document: true Bézier
outlines cannot carry grain, tilt-driven ellipses or per-dab flow, and Phase 2's
entire thesis is that the pencil reads as graphite. So this plan stores **input
and renders through the dab loop we already have** — the survey's model C for
storage, model B for editing, and model A only at export time, much later,
clearly labelled lossy. Anything else is a second drawing engine.

**Ordinary drawing on a vector sheet must cost what it costs today.** The commit
path already stamps the stroke into the sheet — `InkSurfaceView.commitSink.onStroke`,
through the scratch buffer for translucent nibs and directly for opaque ones.
Appending a record beside that is a few hundred bytes and no pixels. So the
cache is maintained **incrementally by the path that already exists**, and a
full re-render happens only on an *edit*, an *undo* or a *zoom settle*. That is
why Ik0 measures re-render and not drawing: drawing is not the question.

**Guides are a filter and an overlay, and both positions already exist.**
`Stabilizer` sits inside `StrokeBuilder` between the samples and the
Catmull-Rom fit; a guide is another filter in that same position.
`SelectionOverlay` already draws a document-space path in view space over a
canvas that never hears about it; a guide is another pass in that same overlay.
Neither is new machinery, and this plan refuses to invent a second version of
either.

## What already exists, and is not rebuilt

Written as a list because every line of it is a line this plan does not have to
spend.

| What | Where | What it gives the Inker |
|---|---|---|
| A deterministic, scale-aware dab loop | `StrokeBuilder`, `DabRasterizer` | Re-running stored input at a different zoom draws the *correct* stroke at that zoom, not a magnified copy. This is what "resolution independent" means, and it is already true. |
| The translucent-stroke composite | `ScratchLayer`, `GrainTexture` | A re-rendered stroke beads exactly as the original did — or rather, does not. |
| Grain anchored to the page, not to the stroke | `GrainTexture`'s shader local matrix | Move a stroke and the tooth regenerates where it lands. `docs/vector-plan.md` trap 2 is already satisfied by construction; the note there exists so nobody "fixes" it. |
| One ordered crossing into the render thread | `CommitQueue`, `LayerOp`, `SelectOp`, `FloatOp` | Every vector edit is a fifth sealed member in a queue whose ordering argument has been made four times already. |
| A generic undo with a byte budget | `UndoHistory<T : UndoStep>` | Command undo is a second `UndoStep`, not a second history. See **Ik5**. |
| A selection held as a path, with marching ants | `Selection`, `Marquee`, `SelectionOverlay` | Lassoing *strokes* is the same gesture over a different hit test. |
| A transform box with a 120 ms refresh floor | `FloatingPixels`, `TransformBox` | Moving strokes is that box over a different source. S6 and S7 already paid for the hard part. |
| A brush as a value, with a text codec | `BrushLibrary`, `BrushCodec` | A stroke can name the brush that drew it, and carry the parameters as they were. |
| A recorded input format with adversarial tests | `Trace`, `TraceRecorder`, `TracePlayer` | The shape of what to store, and a corpus to test the snap stage against. |
| A project on disk, saved incrementally | `Project`, `ProjectSaver`, `ProjectJson` | Stroke files go beside the sheet PNGs, with the revision discipline already written. |

## The three things this plan has to invent

Everything else is assembly.

### 1. A stroke that is a value

`Stroke`'s own KDoc says it is *"retained only until the commit finishes"*, and
gives the reason: keeping the dabs would rebuild the ever-growing scene list the
layer bitmap exists to replace. That reason is still right, and it is not an
objection to this plan — **what is kept is the input, not the dabs.** A dab list
is the expensive, derived, brush-specific thing; the samples are what the hand
did.

```kotlin
// :engine, be.thalos.artiest.engine.ink
class StrokeRecord(
    val id: Long,
    /** Index into the sheet's brush table. See `VectorSheet.brushes`. */
    val brush: Int,
    val colorArgb: Int,
    val erase: Boolean,
    /** The stroke's random seed. See Ik2 — this is what makes redraw honest. */
    val seed: Int,
    /** The dab index the first dab of this record carries. Non-zero after a split. */
    val dabBase: Int,
    /** Packed samples: see `StrokeCodec`. Nine bytes each, delta-coded. */
    val samples: ByteArray,
    val sampleCount: Int,
    val bounds: Bounds,
    /** Index into the sheet's clip table, or -1. See **The selection, on a vector sheet**. */
    val clip: Int = -1,
)
```

**Why a packed `ByteArray` and not a `List<PenSample>`.** A `PenSample` is ten
fields and about 56 bytes, at a measured 321.75 Hz. A three-second stroke is
~965 samples: 54 KB as objects, and `TraceRecorder`'s text form is no better —
it is one line per sample and was designed to be read by a person. A thousand
strokes at 54 KB is 54 MiB of a 4.4 GiB device, sitting beside eight 27.19 MiB
sheets. Delta-coded — x and y as 16-bit fixed point at 1/16 px, pressure, tilt
and orientation as bytes, time as a 16-bit millisecond delta — one sample is
**nine bytes**, the same stroke is **8.7 KB**, and a thousand strokes is
**8.7 MiB**. That is the difference between a feature with a memory question and
one without, and the loss is below what the digitizer resolves.

`StrokeCodec` is `:engine`, pure, and tested the way `BrushCodec` and the trace
format are: round trip, truncation, garbage, and a version byte that refuses a
file from a newer build rather than guessing.

### 2. A sheet that can rebuild itself

```kotlin
// :app, be.thalos.artiest.doc
class VectorSheet(val widthPx: Int, val heightPx: Int) {
    /** Bottom-most first: the order they were drawn and are re-rendered in. */
    val strokes: List<StrokeRecord>          // render thread
    val brushes: List<String>                // `BrushCodec` text, deduplicated
    val clips: List<android.graphics.Path>   // deduplicated; usually empty

    fun add(record: StrokeRecord)
    fun remove(ids: LongArray): List<StrokeRecord>
    fun replace(id: Long, with: List<StrokeRecord>)

    /** Topmost stroke whose ink covers this point, or null. */
    fun hit(xDoc: Float, yDoc: Float, slopDoc: Float): Long?

    /** Every stroke whose ink meets this document-space path. */
    fun hits(path: android.graphics.Path): LongArray

    /** Strokes whose bounds meet [damage], in draw order. */
    fun overlapping(damage: Bounds): List<StrokeRecord>
}
```

`LayerStack.Entry` gains one field — `var vector: VectorSheet?` — and the rule
that a sheet with one is rebuilt rather than painted on directly. `LayerInfo`
gains a `Boolean` so the panel can show which sheets are which. `LayerOp` gains
`AddVector`, and `LayerOp.Open` learns to carry a sheet's strokes, because
opening a project is one operation and always has been.

**Hit testing is against a derived polyline, not against the samples and not
against the dabs.** Each record lazily builds a centreline simplified to two
document pixels — the number `Marquee` already chose for the lasso, for the same
reason — plus a half-width per point taken from the brush's size response. A few
hundred bytes per stroke, dropped under memory pressure and rebuilt on demand.
A uniform grid index over the page, cell size 256 px, turns "which stroke did I
touch" from a walk over 500 strokes into a walk over the handful in one cell.

### 3. A second kind of undo step, in the same history

`UndoHistory` is already generic over `UndoStep`, and `PixelPatch` is already
one implementation of it. What is *not* ready is `Document`, which holds an
`UndoHistory<PixelPatch>` and a private `exchange: (PixelPatch) -> PixelPatch`
lambda.

The change is small and it is the right one: **the exchange moves off `Document`
and onto the step.**

```kotlin
interface DocStep : UndoStep {
    /** Apply this step, and hand back the step that undoes it. Render thread. */
    fun exchange(doc: Document): DocStep
}
```

`PixelPatch` implements it with the body that lambda has today. `VectorStep`
implements it by adding back what it removed and removing what it added, then
re-rendering the union of their bounds. The history becomes
`UndoHistory<DocStep>` and gains nothing else. There is no `when` in the undo
path, no second stack, and no question about which history a mixed sequence of
edits walks back through — which is exactly the failure mode
`docs/vector-plan.md` trap 3 warns about.

The budget arithmetic improves, and that is worth saying because the memory
conversation in this repo has always run the other way: a vector edit's undo
step is **kilobytes** where a `PixelPatch` is up to 28 MB. The 48 MiB cap and
the 32-step depth stay as they are; on a vector sheet the depth will be what
bites, which is the correct end.

## The two decisions that are not obvious

### The selection, on a vector sheet

A selection confines ink by masking the scratch buffer — `indirectNeeded()`
returns true whenever `document.selection.active`, whatever the nib is, so that
there is exactly one way ink is confined. A stroke drawn into a selection is
therefore *clipped pixels*, and a record that remembered only the samples would
re-render **outside the stencil** the first time it was touched. Silently, and
long after the fact.

So the record carries a clip: an index into a small per-sheet table of the
selection paths that were live when strokes were drawn. Consecutive strokes
drawn under one selection share one entry, which is the normal case — a user
selects a region and then draws in it. The usual value is `-1`.

The alternative considered and rejected: refuse to confine ink on a vector
sheet. It is cheaper and it is the kind of exception that makes a program feel
untrustworthy — the same tool doing a different thing depending on which sheet
is active, with nothing on screen to say so.

### Determinism, which is the load-bearing property

`UndoHistory`'s header already refuses stroke-list undo on this ground:
*"Replay has to be deterministic, and Phase 2's brush will not be."* That
sentence is about **today's** brush, and it is exactly right about it —
`StrokeBuilder.begin` does `strokeSeed++` and derives the random state from a
counter, so re-running the same input draws a *similar* stroke, not the same
one. Scatter lands elsewhere, size jitter re-rolls.

A vector sheet cannot be built on that. If re-rendering alters the drawing, then
undo, redo, moving a stroke, changing its colour and zooming in all quietly
change the picture, and nobody will be able to say which of them did it.

So **Ik2 makes the stroke a function of its record**, and it is two changes:

- The seed becomes an input — `begin(colorArgb, seed)` — written into the
  record at pen-down and replayed from it.
- The per-dab random stops being a stream and becomes a **hash of (seed, dab
  index, channel)**. `nextRandom()` today advances a shared xorshift state, so
  the value a dab gets depends on how many dabs preceded it. That is fine
  forwards and wrong for every operation this plan adds — most sharply for
  **splitting a stroke**, where the second half must keep drawing what it drew
  as part of the parent. With the hash and `StrokeRecord.dabBase`, it does.

`docs/guides-plan.md` trap 3 names the same fix from the other direction —
draw-and-hold re-stamps a stroke while the endpoint is dragged, and grain that
crawls while you drag is the symptom. One change, two plans.

The test is a golden: build a record, render it, render it again in a fresh
process, and assert the two bitmaps are identical. It runs in `:app` under
Robolectric with native graphics, beside `ScratchLayerTest`.

## The work plan — the vector half

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| **Ik0** | **DONE, and it refuted something.** `VectorStress` beside `StrokeStress`: draw N strokes through the real path, then re-render the whole sheet from the records and report the cost. On the tablet. See **What Ik0 measured**. | `:app`, device | Low | — | 1 |
| **Ik1** | `StrokeRecord`, `StrokeCodec`, the packed sample buffer, the derived polyline, the grid index. Pure, JVM, no pixels. | `:engine` | Low | — | 4–6 |
| **Ik2** | Determinism: seed as an input, per-dab random as a hash of (seed, dab index), `dabBase`. Golden test across processes. **Ik0 priced the problem**: a nib with no scatter and no jitter is already deterministic, and the pencil differs by 105 052 pixels per million. | `:engine`, `:app` | Med | Ik1 | 2–3 |
| **Ik3** | `VectorSheet`, `LayerStack.Entry.vector`, `LayerOp.AddVector`, and the commit path appending a record beside the pixels it already stamps. A vector sheet you can draw on, that looks like a raster sheet and behaves like one. | `:app` | Med | Ik1 | 5–7 |
| **Ik4** | Re-render: damage rectangles, the redraw loop, the throttle, and the clip table. Nothing visible yet — the sheet can be rebuilt and is proved identical to what drawing it produced. **Ik0 moved this item's centre of gravity**: the rectangle is the design, it has to be tight, and the rebuild opens *one* scratch buffer for the whole patch rather than one per stroke. | `:app` | **High** | Ik3, Ik2 | 5–8 |
| **Ik5** | `DocStep`, the exchange moved onto the step, `VectorStep`, and undo/redo of a vector edit. | `:app` | Med | Ik4 | 4–6 |
| **Ik6** | Persistence: `strokes/<n>.ink` beside `layers/<n>.png`, `ProjectJson` v2 with a `kind` per sheet, save on the same debounce, load into `LayerOp.Open`. **The PNG stays** and is still written — see below. | `:app` | Med | Ik1, Ik3 | 4–6 |
| **Ik7** | Picking strokes: tap, lasso (the marquee gesture, a different hit test), the selected set, and the highlight in the overlay. | `:app` | Med | Ik3 | 4–6 |
| **Ik8** | **The three eraser modes.** Whole stroke; to the nearest intersection; and an ordinary partial rub that splits a record. `StrokeGeometry.intersections` in `:engine`, JVM-tested. | `:engine`, `:app` | **High** | Ik4, Ik7 | 6–9 |
| **Ik9** | Move, rotate and scale the selected strokes, through `TransformBox` over records instead of pixels. Drop is a `VectorStep`; grain regenerates where it lands. | `:app` | Med | Ik7, Ik5 | 4–6 |
| **Ik10** | Restyle: recolour, re-brush, scale the width, re-stabilise. Four operations, one panel, all of them one field on a record and a re-render. | `:app` | Low | Ik4, Ik7 | 3–5 |
| **Ik11** | **Sharp at any zoom**, and export at any scale: re-render the visible region at view scale on a zoom settle, and let `PngExporter` ask a vector sheet for 2x or 4x. **Gated on Ik0.** | `:app` | **High** | Ik4 | 5–8 |

**Vector subtotal: 47–71 days**, against `docs/vector-plan.md`'s 30–45 for its
"smallest set genuinely worth building". The difference is not scope creep: that
estimate did not include persistence (Ik6), a measurement (Ik0), or the
determinism work (Ik2), and it costed a render cache (its item 3, 5–8 days) that
this plan gets for nothing by using `Layer`. Roughly a wash, arrived at from
opposite directions.

### What Ik6 writes, and why the PNG stays

A vector sheet saves **both** its strokes and its pixels. The PNG is not a
duplicate, it is three things at once: what `ProjectSaver` already writes on its
debounce, what the gallery thumbnail and the `.ora` export read, and what opens
the drawing if the stroke file is ever unreadable. Disk is the cheapest thing
this project spends — a full drawing's stroke file is single-digit megabytes,
beside sheet PNGs that are already hundreds of kilobytes each — and the
alternative is a drawing whose only copy is in a format one build of one
program understands.

`.ora` has no vector layers and this plan does not invent an extension for them.
An exported `.ora` carries the pixels, as it does today, and the plan says so
out loud for the reason `docs/layer-effects-plan.md` trap 4 gives: a file that
opens in Krita and silently drops half of what it was is worse than a file that
was honest about what it carried.

## The work plan — the guide half

`docs/guides-plan.md` prices the framework at 8–12 days *before item 16 draws a
single ray*, and says the thing worth repeating here: **perspective is not a
feature, it is a subsystem with a feature on top.** This plan takes that price.

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| **Ik12** | **The snap stage.** A `Guide` interface in `:engine` with one method — project a document-space point onto the guide — called in `StrokeBuilder.add` between `stabilizer.push` and `resampler.add`, **after** smoothing and never before. The predicted tail is snapped too, through the same guide, via `forkSmoothing`. Tested against the trace corpus with golden dabs. | `:engine` | Med | — | 3–4 |
| **Ik13** | **The guide framework.** `GuideSet` on the document, `GuideOverlay` as a second pass in the chrome beside `SelectionOverlay`, handles that are dragged **in arrange mode only**, on/off per guide, and persistence in `project.json`. | `:app` | **High** | Ik12 | 8–12 |
| **Ik14** | The rulers: straight and infinite, parallel, ellipse, curve, and **snap falloff** — strength that fades with distance instead of an on/off, which is what separates a ruler you lean on from one that fights you. | `:engine`, `:app` | Med | Ik13 | 8–12 |
| **Ik15** | **Perspective:** a horizon, one to three vanishing points, rays, infinitising a point, an isometric grid, and the ray-choice rule — whichever ray is closest to the stroke's own direction, with a manual override. | `:engine`, `:app` | **High** | Ik13 | 10–14 |
| **Ik16** | The feel pass on the tablet, by the person holding the pen, and the reconcile of this document against what was measured. | device, docs | Low | all | 1–2 |

**Guide subtotal: 30–44 days.**

### Why the handles are in arrange mode, decided now

`docs/guides-plan.md` trap 4 says dragging a guide competes with panning, and
that deciding it late means rewriting it. This repo has already settled that
class of question twice — `docs/panels-plan.md`'s third stop condition says it
plainly: *"Arrange mode is the settled answer to that class of problem and a
second answer would make the first unreliable."*

So: **guides are placed and dragged in arrange mode**, the same mode that shapes
toolbars, and outside it they are furniture the pen draws against and cannot
knock over. It costs a mode switch to move a vanishing point, which is a thing
done once a drawing, and it buys back every gesture on the canvas for drawing —
which is what happens two hundred times a minute.

## The arithmetic, restated

`docs/layer-effects-plan.md` trap 2 requires any structural item to restate the
memory cap, so:

| | Today | With a vector sheet |
|---|---|---|
| Sheet pixels | 27.19 MiB each, cap 8 | unchanged — the pixels *are* the cache |
| Stroke records | — | ~8.7 KB per three-second stroke; 1000 strokes ≈ 8.7 MiB |
| Derived polylines | — | a few hundred bytes per stroke, dropped under pressure |
| Undo | ≤ 48 MiB, 32 steps, up to 28 MB per step | same cap; a vector step is kilobytes |
| Grid index | — | 3300×2160 in 256 px cells: 117 cells of `LongArray` |

The cap stays at eight sheets. Nothing here moves the number that was chosen
against W0's measured 4.4 GiB free.

## What Ik0 measured

> 2026-09-13, on the DTH-A116. `VectorStress` for the scene,
> `InkSurfaceView.measureRerender` for the run. A 3300x2160 page, strokes of
> 200-700 document pixels at 450-750 px a second, sampled at the pen's own
> 321.75 Hz, with an onset and a taper — so the dabs are where a real stroke
> puts them. The rebuild goes through `stampStroke`, which is the path a real
> commit takes, into a sheet of its own so that measuring does not paint over
> the drawing.

### The numbers

| Nib | Path | ms per stroke | 300-stroke rebuild | 1000 px patch | Redraw drift |
|---|---|---|---|---|---|
| **Pen** | direct, `drawCircle` | 3.6 – 3.8 | **1.09 s** | 281 ms (70 of 300) | **0** |
| **Pencil** | indirect, grain | 20.8 – 21.4 | **6.41 s** | 1.69 s (72 of 300) | **105 052 ppm** |
| **Ink-2 Fineliner** | indirect | 37.9 – 41.0 | ~11 s (2.27 s at 60) | 664 ms (15 of 60) | 0 |

Per-stroke cost is flat with the count — the pen reads 3.80, 3.71 and 3.65 ms at
100, 300 and 1000 strokes — so the rebuild is linear and these figures scale.

### The stop condition fires, and it fires wider than it was written

*"If re-rendering 300 pencil strokes costs more than a second on the tablet ...
the honest fallback is vector sheets for opaque nibs only."* The pencil is
**6.41 s**, six times over.

But the fallback does not clear its own bar either. The pen — the most opaque,
cheapest nib the app has, on the direct path with no scratch buffer — is
**1.09 s** for the same 300 strokes. So "opaque nibs only" is not a fallback,
because the thing it was a fallback *from* is not affordable for any nib.

**The stop condition was asking about the wrong operation.** A full-sheet
rebuild is what an app resume or a zoom settle costs and it happens once; what
happens two hundred times an hour is an *edit*, and an edit only has to redraw
the strokes that overlap the rectangle it dirtied. So the bench measures that
too, against a deliberately generous half-page square:

- **281 ms** for the pen on a 300-stroke drawing.
- **1.69 s** for the pencil.

The conclusion is therefore **not** "opaque nibs only". It is:

> **Damage rectangles are not an optimisation in Ik4. They are the design, and
> nothing above Ik4 is affordable without them.** And the rectangle has to be
> *tight* — a stroke's own bounds, not a generous square — because half a page
> of pencil is still 1.7 seconds.

### Where the cost actually is, which was not where the plan looked

The pencil lays **fewer** dabs than the pen — 88 182 against 137 178 at 300
strokes — and costs **5.9 times as much**. So the dab loop is not the expensive
part. What the pencil has and the pen has not is one `ScratchLayer.begin`, one
mask pass and one `compositeInto` **per stroke**, and that fixed cost is
roughly 16 of its 21 ms.

That names Ik4's real work, and it is not what the plan predicted. A rebuild
should open **one** scratch buffer sized to the damage rectangle and reuse it
across every stroke it redraws, rather than opening one per stroke as the commit
path does. The commit path is right to open one per stroke — it draws one — and
a rebuild is the one caller that knows it is about to draw fifty.

### Determinism, measured rather than asserted

The plan says *"re-running the same input draws a similar stroke, not the same
one"*. The bench renders the same records twice into two sheets and counts the
pixels that differ:

- Pen: **0**.
- Ink-2 Fineliner: **0**.
- Pencil: **105 052 per million — one tenth of the page.**

The cause is one line: `BrushPreset.PENCIL` sets `scatter` to 1.5 document
pixels, and scatter draws from `StrokeBuilder`'s counter-seeded random. A nib
with no scatter and no size jitter is already deterministic today, which is a
smaller change than Ik2 assumed and a sharper one — **Ik2 is not "make the
engine deterministic", it is "make the four random draws a function of (seed,
dab index)"**, and the test that proves it is the pencil going to zero here.

### Memory: comfortably answered

The plan predicted ~8.7 KB a stroke packed, from a three-second stroke at
321.75 Hz. These are inker's strokes — a third of a second to a second and a bit
— so 300 of them are **1.76 MiB** in the bench's unpacked 24-byte form and
**661 KiB** packed at the plan's nine bytes. A thousand strokes is under
2.2 MiB. The memory question closes at a quarter of what the plan reserved for
it.

### What Ik0 did not settle

- **`chalk-details`, the tipped nib**, has no row. It was in the run that took a
  quarter of an hour and never reached it; the run was restarted with two counts
  instead of three and the tipped nib is still unmeasured. Wb5 shipped tipped
  brushes after this plan was written, and a tip's mask is dearer to build than
  an ellipse's, so it is an open number rather than an assumed one.
- **Why the first run was pathologically slow.** Four nibs at three counts spent
  fifteen minutes at about five per cent CPU — the render thread was running but
  stalled, which smells of large-bitmap allocation churn rather than compute.
  The second run, with the same code and two counts, did the same work per nib
  in seconds. Worth knowing before Ik4 opens buffers in a loop.

## Stop conditions

The phase's, in the order they can fire.

1. ~~**Ik0 says re-rendering is unaffordable.**~~ **Fired, and the fallback it
   named was wrong.** The pencil rebuilds 300 strokes in 6.41 s against a 1 s
   bar — but so does the pen, at 1.09 s, so "vector sheets for opaque nibs only"
   fails its own test. The answer is **damage rectangles as the design rather
   than as an optimisation**, and a tight one. See **What Ik0 measured**. Left
   in the list rather than rewritten, because a stop condition that fired and
   changed the plan is the most valuable line in the document.
2. **Ik2's golden cannot be made to pass.** If a record cannot be re-rendered
   pixel-identically, the whole plan is unsound: stop, and fall back to storing
   the *dabs* rather than the input, which costs roughly six times the bytes and
   loses re-brushing and re-stabilisation. Do not proceed on "close enough".
3. **Ik4 forces a second compositor.** If re-rendering cannot go through
   `ScratchLayer` and `DabRasterizer` as the commit path does — if it needs its
   own loop — stop. Phase 3 bought one compositor deliberately, and two that
   drift is the defect a user finds months later in a file they have already
   sent somewhere.
4. **Ik8's erase-to-intersection is not the feature people mean.** It is the
   single most-praised vector feature in CSP and the reason inkers use vector
   layers at all. If, on the tablet, the junction it picks is not the junction
   the hand meant, the rest of Tier 1 is not worth building alone — say so and
   stop rather than adding features around a tool that misses.
5. **Ik13 needs a gesture that races the canvas.** See above; the answer is
   arrange mode, and a second answer is a stop condition, not a design.
6. **The chrome measurement regresses.** `U10`'s rule — `recompose N/s` reads 0
   while a stroke is drawn — covers the guide overlay too. A guide overlay that
   recomposes while the pen is down fails it.

## Cut order, decided now while it is cheap

**Ik15** (perspective — the framework alone gives rulers, which is most of the
daily value), then **Ik11** (sharp at zoom — editing is the reason for the
feature, sharpness is the poster), then **Ik10** (restyle), then **Ik14**'s
curve and ellipse rulers, leaving the straight and parallel ones.

**Do not cut Ik0** — Phase 1's strongest lesson is that a plan built on unmeasured
numbers produces confident wrong answers. **Do not cut Ik2** — without it every
later item quietly alters the drawing. **Do not cut Ik6** — an editable stroke
that does not survive closing the app is a demo.

## Risks and tripwires

| Risk | Tripwire | Answer |
|---|---|---|
| Re-render of translucent strokes dominates | Ik0's number at 300 strokes | Opaque-nib vector sheets only; pencil stays raster |
| Memory grows with the drawing | Records over 20 MiB in a session | Drop derived polylines first; then a per-sheet record cap with a warning |
| Two undo models confuse the user | An undo press that does nothing visible | One history, one order — `DocStep`. Never two stacks |
| A brush is retuned and old strokes change | A golden re-render after editing a preset | The record names a **table entry**, not a library id; retuning a preset does not reach back |
| Splitting a stroke changes its grain | Ik8 golden: parent's second half vs. the split record | The (seed, dab index) hash, Ik2 |
| Snap fights the stabilizer | A snapped stroke that drifts off the ruler | Snap **after** smoothing — `docs/guides-plan.md` trap 1, one line in `StrokeBuilder` |
| The predicted tail is not snapped | A wet tail that wanders off the guide and jumps back | Snap the fork too, Ik12 |
| The guide overlay costs a frame path | `dryframe` or `recompose` moving with guides on | Share the overlay Phase 3 built; never a second one |
| Vector sheets make `.ora` a lie | — | The `.ora` carries pixels and says so |

## Deliberately not in this plan

- **Node editing** — control points, per-point width, pinch, redraw a section.
  `docs/vector-plan.md` Tier 2, 30–45 days on its own, and it doubles this plan
  to buy precision a pen-in-hand inker may never reach for. The records make it
  possible later; nothing here forecloses it.
- **SVG export and import.** Lossy by nature for our brushes, and worth doing
  when there is a reason beyond the word "vector".
- **Booleans, closed-shape fills, gradient strokes.** That is the logo program
  `docs/master-plan.md` says not to chase.
- **Fill inside line art with gap closing.** Wanted, large, and CSP's own answer
  is to fill on a *raster* sheet that refers to the vector one — which is a plan
  of its own and belongs after this one.
- **Infinite canvas.** A document-model change wearing a vector feature's
  clothes. Named here so it stays a decision rather than a drift.
- **Fisheye perspective.** Everybody ships it, few use it.
- **Draw-and-hold, mirror, alpha lock, clipping, merge.** P3's, and prerequisites
  rather than contents.

## The questions only you can answer

1. **Does the pencil have to work on a vector sheet?** This plan assumes **yes**,
   because graphite is the app's one distinguishing asset, and that assumption is
   what rules out true Bézier outlines and makes SVG export permanently lossy. If
   the answer is "vector sheets are for *inking* and the pencil stays raster",
   the plan gets smaller, faster and more portable — and Ik0's stop condition
   becomes the design rather than the fallback.
2. **Editing, or sharpness?** The order here is editing first (Ik7–Ik10),
   sharpness last and gated (Ik11). If what you actually picture when you say
   "vector" is a line that stays crisp at 800%, Ik11 moves to the front and the
   eraser modes move back.
3. **Is perspective the real goal, or are rulers enough?** Ik13 is unavoidable
   for either. Ik15 is 10–14 days on top, and it is the item most likely to be
   wanted for a project you have not started yet.
4. **One artist at a time?** `docs/master-plan.md`'s organising principle says
   finish the Inker before starting the Painter. This plan is sized to be
   finishable. It stops being finishable the moment Tier 2's node editing is
   added to it.

## What this changes in the master plan

`docs/master-plan.md` prices the Inker at **150–200 days including the
sketcher**. This plan is the Inker **without** the sketcher: **77–115 days**,
which with P3's 30–45 comes to **107–160** — inside the band, at the lower end,
and for one reason worth recording: the vector layer is much cheaper here than
the menu assumed, because Phase 3 bought the compositor, Phase 2 bought a
deterministic dab loop, and `Layer` turns out to be the render cache
`docs/vector-plan.md` was budgeting five to eight days to build.

The order within the phase is the table order, and the two halves are
independent after Ik0: **the guide half can run first, last, or by somebody
else.** Nothing in Ik12–Ik16 depends on a stroke record, and nothing in
Ik1–Ik11 depends on a guide.
