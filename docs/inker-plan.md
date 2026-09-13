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
| **Ik1** | **DONE.** `StrokeRecord`, `SampleLog`, `StrokeCodec`, `StrokePolyline`, `StrokeGrid`, `IdList`, and `Bounds.intersects`. Pure, JVM, no pixels. See **What Ik1 built**. | `:engine` | Low | — | 4–6 |
| **Ik2** | **DONE, and the pencil is at zero.** Seed as an input, per-dab random as a hash of (seed, dab index, channel), `dabBase`. See **What Ik2 changed**. | `:engine`, `:app` | Med | Ik1 | 2–3 |
| **Ik3** | **DONE.** `VectorSheet`, `PendingStroke`, `LayerStack.Entry.vector`, `LayerOp.AddVector`, the commit path appending a record beside the pixels, and **`VectorSheet.intact`** — which is the part the plan did not foresee. See **What Ik3 built**. | `:app` | Med | Ik1 | 5–7 |
| **Ik4** | **DONE, and "identical" turned out to be the wrong word.** `InkSurfaceView.rebuild`, `Confinement`, `Layer.blank(rect)`, a coalescing throttle, and the clip table in use. See **What Ik4 built**. | `:app` | **High** | Ik3, Ik2 | 5–8 |
| **Ik5** | **DONE.** `DocStep`, the exchange moved onto the step, `VectorStep`, `SheetRebuilder`, and undo/redo of a vector edit. Two of Ik3's three spoilers are gone. See **What Ik5 built**. | `:app` | Med | Ik4 | 4–6 |
| **Ik6** | **DONE.** `strokes/<n>.ink` beside `layers/<n>.png`, `ProjectJson` v2, `PathText` for the clip table, save on the same debounce, load into `LayerOp.Open`. The PNG stays. See **What Ik6 built**. | `:app` | Med | Ik1, Ik3 | 4–6 |
| **Ik7** | **DONE.** `StrokeOp`, `StrokePick`, `CommitQueue.Commit.Pick`, the strokes/pixels toggle in the selection panel, and the highlight in the overlay. See **What Ik7 built**. | `:app` | Med | Ik3 | 4–6 |
| **Ik8** | **DONE.** `StrokeGeometry`, `StrokeSplitter`, `StrokeEraser`, `EraseMode`, the record's time origin, and the mode selector. See **What Ik8 built**. | `:engine`, `:app` | **High** | Ik4, Ik7 | 6–9 |
| **Ik9** | **DONE.** `StrokeTransform` in `:engine`, `StrokeMove` in `:app`, `TransformBox` over the picked strokes with a live highlight preview. See **What Ik9 built**. | `:app` | Med | Ik7, Ik5 | 4–6 |
| **Ik10** | **DONE, and the four operations turned out to be one.** `StrokeOp.Restyle`, `StrokeRestyle`, and three buttons that appear only when strokes are picked. See **What Ik10 built**. | `:app` | Low | Ik4, Ik7 | 3–5 |
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
| **Ik12** | **DONE.** `Guide`, `Snap`, `LineGuide`, `StrokeBuilder.snap`, and the predicted tail through the same guide. See **What Ik12 built**. | `:engine` | Med | — | 3–4 |
| **Ik13** | **DONE.** `GuideSet`, `Guideline`, `NearestGuide`, `GuideOverlay`, `GuideHandles`, the guides panel, `StrokeRecord.guide` and format 3 on both files. See **What Ik13 built**. | `:app` | **High** | Ik12 | 8–12 |
| **Ik14** | **DONE.** `ParallelGuide`, `EllipseGuide`, `CurveGuide`, the `Guide.begin` the first needed, and *Trace that stroke*. The straight ruler and the falloff came with Ik13 and Ik12. See **What Ik14 built**. | `:engine`, `:app` | Med | Ik13 | 5–8 |
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
| **Chalk Details** (tipped) | indirect, tip | 25.9 – 26.1 | **7.82 s** | 2.04 s (72 of 300) | 0 |
| **Airbrush Soft** (600 px) | indirect | 8.4 | 2.54 s | 1.30 s (146 of 300) | 0 |

> The last two rows were measured on 2026-09-13 with Ik2's build, which is why
> their drift reads 0 where the pencil's original run read 105 052. The pencil
> and the pen were re-measured in the same run and their costs did not move.


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

- ~~**`chalk-details`, the tipped nib**, has no row.~~ **Measured, 2026-09-13,
  with Ik2's build.** It is the dearest nib in the app: **26.0 ms a stroke**,
  **7.82 s** to rebuild 300 strokes and **2.04 s** for the 1000 px patch, above
  the pencil's 21.7 / 6.50 / 1.69. It lays 80 321 dabs against the pencil's
  88 182, so once again the dab loop is not where the money goes — a tip's mask
  is dearer to build than an ellipse's, and it is on the indirect path with a
  scratch buffer and a composite per stroke like the pencil. It changes no
  conclusion: damage rectangles were already the design, and this is the nib
  that needs them most. The airbrush, measured in the same run, is **8.4 ms a
  stroke** — cheap per stroke because a 600 px nib lays 2 418 dabs for 300
  strokes, and dear per *pixel*, which is `docs/big-nib-plan.md`'s subject
  rather than this one's.
- **Why the first run was pathologically slow.** Four nibs at three counts spent
  fifteen minutes at about five per cent CPU — the render thread was running but
  stalled, which smells of large-bitmap allocation churn rather than compute.
  The second run, with the same code and two counts, did the same work per nib
  in seconds. Worth knowing before Ik4 opens buffers in a loop.

## What Ik1 built

> 2026-09-13. `:engine`, `be.thalos.artiest.engine.ink`, 48 tests, no device.

Five types and one method, and the numbers the plan predicted came out where it
said they would.

| Type | What it is |
|---|---|
| `StrokeRecord` | The value: id, brush table index, colour, erase, seed, `dabBase`, clip index, painted bounds, and the packed samples. Immutable; every edit is a new record. |
| `SampleLog` | The growing buffer samples are appended to while the pen is down, reset rather than reallocated, packed at pen-up. |
| `StrokeCodec` | Both layers of the format: the nine-bytes-a-sample packing, and the file `strokes/<n>.ink` will be. |
| `StrokePolyline` | The derived centreline — thinned to 2 document pixels, a half-width at each point — that a tap is answered against. |
| `StrokeGrid`, `IdList` | The uniform index, 256 px cells, plus the unboxed id list a query fills without allocating. |
| `Bounds.intersects` | Closed rather than half-open, so a zero-area rectangle still meets things. The one new method on an existing type. |

### The packing, and the drift that is not in it

Nine bytes a sample after an eight-byte origin, exactly as predicted: x and y as
int16 deltas at 1/16 document pixel, pressure, tilt and orientation as bytes,
and time as a uint16 delta at 1/8 ms. A three-second stroke at the pen's own
321.75 Hz is 965 samples and **8 693 bytes**, against the 8.7 KB the plan
reserved. Ik0's 300-stroke page is **663 KiB** of samples and **678 KiB** as a
file with every header included.

The one thing the plan did not say, and the thing most likely to have gone wrong
unnoticed: **a delta code that rounds each difference on its own drifts.** The
error random-walks, so a thousand-sample stroke ends somewhere the hand did not
put it — and it is invisible on any stroke short enough to write a test for by
hand. The encoder therefore quantises the *absolute* coordinate and stores the
difference of the quantised values, so the decoder recovers the quantised
absolute exactly and the error is half a quantum at every sample whatever the
length. `StrokeRecordTest` pins it at a thousand samples along a path whose
steps are deliberately 0.6 of a quantum, which is the case that drifts fastest.

The other three channels are quantised against what *consumes* them rather than
against what produces them, which is what makes a byte defensible:

- **Orientation to 1.41 degrees**, which is finer than `MaskTolerance`'s own
  rotation bucket of 2.81 degrees. So a chisel nib replayed from a record asks
  the mask cache for the same mask it asked for live.
- **Pressure to 1/255**, which moves a 24 px nib by 0.09 px — a quarter of the
  mask cache's 3% size bucket.
- **Tilt to 0.35 degrees.**

**What is still open, and it belongs to Ik4.** Position is stored to 1/16 px, so
a replayed dab can sit up to 1/32 px from where the live one sat. Whether that
is *visible* is a different question from whether it is small, and nobody has
counted it. `StrokeCodec.VERSION` is the escape hatch: a finer fixed point is one
constant and a version bump, and old files then refuse to load rather than
decoding as a different drawing.

### The index, measured against Ik0's own page

A tap on a 300-stroke page laid out the way `VectorStress` lays one out measures
**at most 12 strokes**, not 300 — asserted over a sweep of 380 tap positions
rather than at one lucky point. 3300x2160 in 256 px cells is 13 by 9, the 117
the plan's memory table counted.

The grid holds every stroke's bounds itself rather than being handed them back
at removal time. That is four floats a stroke against the failure it prevents: a
caller that passes a slightly different rectangle to `remove` leaves the id filed
in a cell forever, and the symptom is a deleted stroke that a tap still selects,
in a drawing that has since been saved a hundred times.

### The hit band is wider than the stroke, on purpose

`StrokePolyline` stores `Brush.sizeFor(pressure, elapsed) / 2` at each point,
which is the nominal size response. A brush that scatters paints up to
`scatter.max` outside that band and a size driven by tilt or speed can evaluate
above it, so `maxOvershoot` is added to every hit test. Wrong in the safe
direction costs a tap that selects a stroke whose ink is a pixel away; wrong in
the other direction costs a tap on visible ink that selects nothing, which is
the one users report.

It is added **at the test and not baked into the stored band**, because Ik8's
split has to be exact about where the ink is and merging the two numbers would
quietly widen it.

## What Ik2 changed

> 2026-09-13. `StrokeBuilder` in `:engine`, `StrokeRedrawGoldenTest` in `:app`
> under Robolectric with native graphics.

`UndoHistory`'s header refuses stroke-list undo on the ground that *"replay has
to be deterministic, and Phase 2's brush will not be"*. That sentence is now
false, and two changes made it false.

**The seed is an input.** `begin(colorArgb, seed, dabBase = 0)`. The unseeded
`begin` is still there and still gives every stroke its own look — it hands out
the next counter value as a seed — so nothing about drawing by hand changes.

**The per-dab random is a hash, not a stream.** `randomFor(index, channel)` is
`fmix32` over the seed, the dab's index and a channel number. A stream's value
depends on how many draws came before it, which means a dab's scatter depended
on whether the brush also jittered, on whether the previous dab happened to take
the `throwPx > 0f` branch, and on where in the stroke the render started. Each
of those is a way for a re-render to draw something else.

The four channels are numbered rather than drawn in order, so that **adding a
fifth never moves the other four** — which would change every drawing already on
the device.

### The golden, and the number it replaces

Ik0 measured the same records rendered twice and counted the pixels that
differed: pen 0, fineliner 0, **pencil 105 052 per million**, one tenth of the
page. `StrokeRedrawGoldenTest` renders twelve strokes of `VectorStress`'s scene
from **packed records** — so the packing, the delta code, the hash and the dab
loop are all on the path — and counts the same thing. All three nibs are at
**zero**, and the allowed number is zero, because the stop condition says *"do
not proceed on 'close enough'."*

The two renders are separated by five unrelated strokes drawn with other seeds
into a throwaway sheet. That is the part a back-to-back comparison would miss:
the defect is state carried *between* strokes, and two renders in a row do not
disturb it.

Three more tests exist so that the golden cannot pass for the wrong reason. A
different seed must draw a different page; a `dabBase` must shift the grain; and
the pen and the fineliner — which were already at zero — must stay there, which
is what catches a "fix" that made every stroke identical to every other.

### What the seed reaches, which is more than decoration

A changed seed changes the **dab count**, not just where the dabs sit: size
jitter changes the radius, the radius feeds `spacingFor`, and the spacing
decides where the next dab lands. So a stroke's seed is part of its geometry,
which is another way of saying a record that lost its seed would not be a
record of anything.

### The hash is checked as a random, not only as a function

A hash that is a poor one passes every test above and ruins the pencil: grain
that repeats every few dabs reads as a pattern, and grain with a bias reads as a
stroke quietly thinner than the slider says. So 4 000 draws are checked for mean
(within 0.02 of 0.5), for spread (ten buckets, each within a fifth of its share)
and for the absence of any cycle up to a period of 64. And the scatter and
jitter channels are checked for correlation — under 0.1 over 900 dabs — because
a brush whose ink flies furthest exactly where the dab is thinnest looks like a
deliberate effect and is a bug.

## What Ik3 built

> 2026-09-13. `:app`, `be.thalos.artiest.doc`, plus the commit path in
> `InkSurfaceView`.

An ink layer is made from the layers panel, drawn on exactly as any sheet is,
and keeps what was drawn on it. The readout's `ink` line says how many strokes
each one holds and what they cost.

| What | Where |
|---|---|
| `VectorSheet` | The strokes in draw order, the brush table, the clip table, the grid. No pixels: the pixels are the `Layer` beside it, as they always were. |
| `PendingStroke` | One stroke's input crossing from the UI thread, in `CommitQueue.Commit.Draw` beside the pixels. |
| `LayerStack.Entry.vector` | One nullable field, which is the whole of `docs/vector-plan.md`'s "vector layer type". |
| `LayerOp.AddVector`, `LayerInfo.vector` | A sheet that keeps strokes, and the panel knowing which sheets those are. |
| `Document.vectorNote()` | The readout line. |

### Why the record crosses the thread in two halves

Three of a record's fields are the sheet's to assign — the id, the brush table
index and the clip table index — and the sheet lives on the render thread. So
the UI thread hands over the samples, the seed, the colour, the erase flag, the
bounds and the brush **as text**, and the sheet interns the rest.

That is also what makes `docs/inker-plan.md`'s risk-table line true rather than
intended: *"the record names a table entry, not a library id; retuning a preset
does not reach back."* A test moves `sizeMax` from 18 to 120 after the stroke is
recorded and asserts the record still reads 18.

The record is packed on **every** stroke, including on ordinary raster sheets
where it is thrown away. The UI thread does not know which sheet the stroke will
land on, so the choice was between six float writes a sample that are sometimes
wasted and a boolean mirrored across a thread boundary that is sometimes stale.
The first is cheap and cannot be wrong.

### `VectorSheet.intact`, which the plan did not foresee

Ik3 ships before Ik5 and Ik8, and there are three things that move a sheet's
pixels without moving its records:

- **Undo and redo**, which restore a rectangle through `PixelPatch`. Ik5's.
- **A clear inside a selection**, which on a record list is a *partial erase* —
  Ik8's split, not a deletion.
- **Dropping floating pixels**, which paints something that was never a stroke.

The pixels are the truth about what a sheet looks like; the records are what it
can be *rebuilt* from. After any of the three they disagree, and a rebuild would
repaint the drawing into something the user did not draw — the defect somebody
finds months later in a file they have already sent somewhere.

So a sheet that has had one of them done to it is **spoiled**, says so in the
readout, and Ik4 will refuse to rebuild it. Spoiling is deliberately coarse —
anything that spoils one sheet spoils all of them — because an over-cautious
refusal costs a feature that has not shipped, and a missed one costs a drawing.
A *whole* clear is the one pixel operation outside drawing that a record list
can express exactly, so it empties the list and un-spoils.

**Ik5 and Ik8 remove the three callers. When the last one goes, so does the
flag.** It is not a permanent part of the design and it should not become one.

### The one ordering rule

The record is appended **after** the pixels land, not before. If stamping throws
— an out-of-memory opening the scratch buffer is the real case — the record must
not be left describing ink that is not on the page. The same invariant
`Document.clearHistory` already keeps for the stroke bounds.

## What Ik4 built

> 2026-09-13. `:app`. Measured on the host with `StrokeRedrawGoldenTest` and on
> the DTH-A116 with the debug row's **Rebuild** button.

`rebuild(sheet, into, damage)` blanks a rectangle and repaints it from the
strokes that overlap it. On the tablet: five pencil strokes on an ink layer,
**261.3 ms**, and the drawing does not move.

### One compositor, which was a stop condition

Every stroke goes through `stampStroke` — the same method the commit path uses,
the same `ScratchLayer`, the same `DabRasterizer`. The record's brush is
**adopted into the pen** for the duration and the pen is restored from its own
encoded text afterwards, which is the trick `rerenderReport` already used and is
safe for the same reason: a rebuild is one render-thread task, no commit is
drained during it. Stop condition 3 is cleared without a second loop.

### The confinement, which is two things and one clip

A stroke that overlaps the damage rectangle usually pokes outside it, and ink
outside it would land on pixels nobody cleared — a second coat. So a rebuild
clips to the rectangle. It also clips to the record's own entry in the **clip
table**, which is the whole reason that table exists: a stroke drawn into a
selection is clipped pixels, and re-rendering it unclipped would put ink outside
the stencil, silently, long after the fact.

Both are `Canvas` clips rather than the mask bitmap the commit path uses, and
the existing code already said why that is safe: *"`Layer`'s canvas is a
software one, where Skia antialiases a path clip, which is exactly why it is
safe here and wrong on the frame's `RenderNode` canvas."* The commit path masks
because it must also confine the **wet** pass, which draws on a hardware canvas.
A rebuild has no wet pass. It is also what makes the damage rectangle
affordable: masking would mean an `ALPHA_8` page per clip-table entry.

### The throttle is a coalescer, not a rate limit

A rebuild that is *skipped* leaves the sheet showing something its records do
not say, which is the one state this design exists to prevent. So nothing is
ever skipped: repeated requests union into one rectangle and are paid for once,
at the top of the next dry frame — before the dry clock starts, so a rebuild
does not appear as one long frame in the percentiles it exists to protect. That
is also the shape Ik9's drag (a rectangle eight times a second) and Ik10's
sliders want.

### "Identical to what drawing it produced" is not quite true, and here is how much

The plan's row asked for a rebuild *proved identical* to the drawing. It is not,
and the difference was worth measuring rather than asserting away. The same
scene drawn live from its floats, and drawn again from its packed records:

| Nib | Inked pixels that differ | Worst single channel | Total ink |
|---|---|---|---|
| Pen | 9.6% | 39 of 255 | 0.013% off |
| Pencil | 46.9% | 14 of 255 | 0.029% off |
| Ink-2 Fineliner | 54.9% | 16 of 255 | 0.400% off |

**Counting how many pixels differ is the wrong question.** The ink is the same
amount in the same place — total coverage is within 0.4% — and its antialiased
edges land a fraction differently. On the tablet, over the whole drawing at
0.667 zoom, 70% of the differing pixels are 2 of 255 or less and 30 pixels out
of 162 549 exceed 8.

So the test's bars are on the two numbers that separate that from a defect:
**total coverage within 1%**, which a dropped dab or a moved stroke would blow
open, and **no single channel past 64**, which one dab in the wrong place would
pass immediately.

### The sweep, so nobody has to run it again

Finer packing was tried at 1/16, 1/64 and 1/256 document pixels, with pressure
at 8 and at 16 bits:

- The **pen** — no dynamics, so only position, pressure and time reach it —
  improves steadily: 6.4%, 2.5%, 1.1% of its ink. Position precision is the
  lever for an opaque nib.
- The **pencil** plateaus at 41–46% at every setting. Something other than the
  packing dominates it.
- Sixteen bits of pressure took the pen from 9.6% to 6.4% and cost a byte a
  sample, which is 11% of the record.

**1/16 px stayed.** It is what keeps a sixteen-fold margin under the int16 step
limit — a step that does not fit is a refusal, not a clamp — and spending that
margin on an invisible improvement is the wrong trade. `StrokeCodec.VERSION` is
still there if a later measurement disagrees.

### What Ik0 asked for and did not get

Ik0's note said a rebuild should open *"one scratch buffer sized to the damage
rectangle and reuse it across every stroke it redraws, rather than opening one
per stroke"*. Half of that is right and half of it is not. **Reuse** is right
and is what happens — `ScratchLayer` keeps its buffer across `begin` calls and
Bn3's 256 px overshoot means a rebuild rarely reallocates. **One buffer for the
whole patch** is wrong: two overlapping translucent strokes accumulated into one
buffer and composited once are a different drawing from two strokes composited
one at a time, and not being that is the entire reason the buffer exists.

## What Ik5 built

> 2026-09-13. `:app`. Verified on the DTH-A116.

`Document` used to hold a `(PixelPatch) -> PixelPatch` lambda, which had exactly
one kind of step in it by construction. The exchange moved **onto the step**:

```kotlin
interface DocStep : UndoStep {
    fun exchange(doc: Document): DocStep
}
```

`PixelPatch` implements it with the body that lambda had. `VectorStep` implements
it by removing what it added and adding back what it removed, then repainting the
union of their bounds through `SheetRebuilder` — one interface with one method,
so `Document` can ask for a rebuild without knowing about the rasterizer or the
scratch buffer, and so there is still exactly one implementation of it.

`Document.exchange` is now `{ step -> step.exchange(this) }`. There is no `when`
in the undo path, no second stack, and no question about which history a mixed
sequence walks back through — which is `docs/vector-plan.md` trap 3, and is
tested against the real `Document` rather than against `UndoHistory`, because
what could go wrong is not the deque but somebody adding a second history later.

### The number

On the tablet, three pencil strokes on an ordinary sheet and then three on an
ink sheet:

| | Steps | History |
|---|---|---|
| Three raster strokes (plus a clear) | 4 | **30.3 MiB** |
| Three ink strokes on top of those | 7 | **30.3 MiB** |

Three vector strokes added about a kilobyte where three raster ones cost roughly
7.6 MiB each. The 48 MiB cap and the 32-step depth stay as they are; on an ink
sheet the *depth* is what will bite, which `UndoHistory` says is the correct end.

Undo and redo, measured on the device: **45.5 ms** and **49.8 ms** to take a
stroke off, **80.0 ms** to put one back. The redo repaints two strokes rather
than one, because the rectangle it dirtied also contains a neighbour — which is
the damage rectangle working, not a mistake.

### A stroke gets its step at a different moment depending on the sheet

On an ordinary sheet the patch is captured **before** the ink lands, because
that is the last moment the region exists in its pre-stroke state. On an ink
sheet the step is recorded **after**, because the record does not exist until
then. One branch in `commitSink.onStroke`, where both the sheet and the stroke
are known.

### Two of Ik3's three spoilers are gone

Ik3 spoiled **every** vector sheet on **any** undo, because a `PixelPatch`
restores pixels the record list cannot follow. Ik5 makes that precise: a
`VectorStep` moves the list and repaints, so nothing disagrees and nothing is
spoiled; a `PixelPatch` spoils the sheet **it lands on**, from inside its own
`exchange`, where the layer id is known.

What is left spoiling a sheet is a clear inside a selection and a float drop —
Ik8's and a later item's. The flag is doing what it was built for: shrinking.

### One thing worth knowing about the repaint

A rebuild repaints *every* stroke overlapping the rectangle, not only the ones
the step touched, so a neighbour is re-rendered from its record too. That is
real and it is bounded: Ik4 measured a record's re-render against the live
drawing at under 0.4% of the total ink, and after the first rebuild the pixels
come from the records — so a second rebuild of the same region is
pixel-identical. It converges after one press rather than drifting with every
press.

## What Ik6 built

> 2026-09-13. `:app`. Verified on the DTH-A116: four strokes on an ink layer, a
> force-stop, a relaunch — `ink  Ink 3 strokes 1 KiB`, and a rebuild of the
> reloaded sheet repaints all three in 428.8 ms.

An ink sheet writes `strokes/<n>.ink` beside `layers/<n>.png`, named from its
position for the same reason the PNG is. `ProjectJson` is version **2**, and the
version-1 shape is untouched: the three new fields — `strokes`, `brushes`,
`clips` — are written only when there is something to say, so a drawing of
ordinary sheets encodes to exactly the bytes version 1 wrote.

### What goes where, and why it is split

| | Where | Why |
|---|---|---|
| The samples | `strokes/<n>.ink`, `StrokeCodec` | Nine bytes a sample is the whole argument for keeping them; that does not belong in JSON. |
| The brush table | `project.json` | It is the *sheet's*, not the strokes', it is text beside other text a person reads, and it has two or three entries. |
| The clip table | `project.json`, as `PathText` | Same. |

### The clip table had to be solved, not deferred

A stroke drawn into a selection is *clipped pixels*. If the clip table did not
survive a save, the first edit after reopening would re-render that stroke
**outside** the stencil — silently, and nowhere near what caused it. That is the
exact failure the table was invented to prevent, so a table that does not
persist is a table that does not work.

`Path` has no serialisation this build can use (`getPathIterator` is API 34,
`minSdk` is 29), so **`PathText`** walks each contour with a `PathMeasure` and
emits points a document pixel apart. That is exact enough by construction:
a clip is a mask, rasterised to whole pixels, whose edge Skia antialiases over
one of them. It would be the wrong technique for a stroke — which is why strokes
are stored as their input, and why this plan refuses Bézier outlines for ink.

Direction is kept, so a selection with a hole in it comes back with the hole.
Reversing one contour would fill it in, which is a defect that looks like a
rendering bug three features away, so it is its own test.

### An unreadable stroke file costs editability and nothing else

The PNG is what the drawing looks like; the strokes are what it can be edited
from, and losing the second is recoverable where losing the first is not. So a
sheet whose manifest says it keeps strokes and whose file is missing, corrupt,
or has a clip this build cannot read **still opens** — with its pixels, as a
vector sheet with no records, **spoiled**, and with a note saying so. Nothing
tries to repaint a page from a list that does not describe it.

### Two things the saver had to learn

**A second revision to watch.** `dirty()` is a poll — comparing longs every few
seconds, so that nobody has to remember to call something from a stroke, a
clear, an undo or whatever the next feature adds. `VectorSheet.revision` is
`Layer.revision`'s counterpart, and without it a change to a record list that
happened to repaint identically would be missed.

**A second thing to prune.** `strokes/3.ink` left behind by a deleted sheet
would be picked up by whatever becomes sheet 3 next — a drawing that opens with
somebody else's strokes on it. Pruned by the set of indices the manifest still
names, not by a count, because a sheet can stop keeping strokes without going
away.

### A copy of an ink sheet is an ink sheet

`LayerOp.Duplicate` copies the record list too. The records are **shared**
rather than copied — they are immutable values, and copying the samples would
double the one cost this design is careful about — while the clip paths are
copied, because a `Path` is not immutable.

## What Ik7 built

> 2026-09-13. `:app`. Verified on the DTH-A116: a tap picks one stroke, a lasso
> over the page picks all four, and the highlight draws — 2 004 blue pixels
> along the stroke that was tapped, and none anywhere else.

The marquee gesture, pointed at strokes instead of pixels. `StrokeOp` is the
fifth member of `CommitQueue`, for the fifth time for the same reason: "select
this" means *after everything I have drawn*, and a pick applied straight from
the UI thread would land in front of a stroke the render thread has not stamped
yet — and then fail to find it, which on a tap looks like the app ignoring the
pen.

### It is a choice, not an inference

Picking strokes whenever the sheet keeps them would make the same tool do a
different thing depending on which sheet is active **with nothing on screen to
say so** — which is the objection this plan raises against exactly that
shortcut, in the note about confining ink. So the selection panel has a
strokes/pixels pair, shown only on a sheet that keeps strokes, defaulting to
strokes because that is what the tool is for there.

### A tap is a gesture whose shape is empty

`Marquee` records nothing below its own two-pixel step, so a tap's path has no
points and its bounds is a rectangle at the origin. A tap is therefore answered
at the point the gesture went **down**, which is the only thing that knows where
the pen landed — and the same branch catches a real hand's tap, which travels a
pixel or two rather than none. Four document pixels decides it: twice the
marquee's step, and about how far a hand aiming at a two-pixel fineliner line
misses it.

### Two rules that are tests rather than comments

**A picked set belongs to one sheet.** A stroke id is unique *within* a sheet,
so a set carried to another sheet names other strokes — and the first thing done
to it, an erase or a drag, happens to them. Moving the pen to another sheet
drops the set.

**A picked set cannot outlive the strokes in it.** Undoing a stroke that was
picked un-picks it; without that the next operation is a no-op nobody can
explain.

### The highlight is the spine, and it is not ants

A pixel selection is a *region* and its boundary is the thing to show. A stroke
selection is a *set of objects*, and the thing to show is the objects — so the
picked strokes' centrelines are drawn as a pale halo under a blue core, in the
same overlay pass, sharing its scratch path. Marching ants along fifty spines
would read as fifty very thin regions, and the two can be on screen at once.

Centrelines and not outlines: a stroke's outline is the expensive derived thing
this whole design avoids computing, and a line along the spine is what a vector
editor's own highlight is.

## What Ik8 built

> 2026-09-13. `StrokeGeometry` and `StrokeSplitter` in `:engine`,
> `StrokeEraser` in `:app`. 38 tests, plus the tablet.

Three eraser modes on a sheet that keeps its strokes: take the whole stroke,
take the stretch the eraser passed over, or take from the crossing before the
touch to the crossing after it.

### The junction cut, measured on the device

A 1 200-pixel line crossed by another at two thirds of its length, one tap on
the overshoot with **Back to the junction**:

```
erase TO_JUNCTION: -1 +1  removed [1:62]  added [3:40@0]
```

Sixty-two samples in, forty out — the cut landed at sample 40 of 62, which is
the crossing. The stop condition asked whether *"the junction it picks is the
junction the hand meant"*, and it is.

### `dabBase` is why a cut stroke keeps its grain

A piece cut from the middle of a stroke has to go on drawing what it drew as
part of the stroke. Its scatter, jitter and grain are a hash of (seed, **dab
index**, channel) since Ik2, so all a piece needs is the dab index its first dab
carries — and **that number cannot be estimated from the sample index**, because
dabs are laid by arc length and a slow stretch of a stroke lays far more of them
than a fast one. So `StrokeSplitter` re-runs the parent's own dab loop and reads
`dabCount` at the cut, which is exact by construction.

The test is the risk table's: a brush that jitters its size and nothing else, at
flat pressure, where every dab would be the same radius but for the jitter. A
piece cut from the middle lays the parent's own radii — 90% of them exactly, the
rest being the four dabs either side of the cut where the spline sees different
neighbours. The counterfactual is beside it: the same piece told it starts at
dab 0 matches fewer than ten.

### The record grew a clock, and the format went to version 2

A tail whose time restarted at zero gets a **fresh onset ramp**: the pen appears
to lift and land again at the cut, which is the most visible thing a split could
get wrong. So the packed buffer's origin widened from eight bytes to twelve and
now carries the time of its first sample, and `StrokeBuilder.begin` takes a
`startMillis` that is added to every elapsed time.

### Two defects found on the tablet that were not Ik8's

Both were old, both were invisible until an erase made them matter, and both are
the kind that only a device finds.

**A kept layers panel was acting on a stale active layer.** `layerRows` and
`activeLayer` only refreshed while the *popup* was open, so the panel fixated
onto a bar sent `LayerOp.Delete` naming whichever sheet was active when the popup
was last closed — and the stack refused it silently. Found by pressing Delete
four times and watching nothing happen. The poll now runs with the popup closed
too, at a third of the rate.

**The wet scratch was composited past its own stroke.** `StackCompositor.Wet`
asked `scratch.isOpen`, which stays true after a stroke ends because keeping the
buffer is the point. For an ordinary stroke that is invisible — the buffer holds
the stroke that was just committed, so it paints the same pixels twice in the
same place — but a stroke that is *abandoned* rather than committed leaves the
sheet repainting correctly underneath a stale buffer. The epoch check every
other reader of the scratch already used was the one that had been left out.

### And a note for the next person with a screenshot

**`adb shell screencap` does not tell the truth about this surface.** With every
layer hidden, a screenshot still showed the drawing. The canvas is a front
buffered layer — a hardware overlay — and the capture can return what was in it
some time ago. An afternoon went into chasing a repaint bug that was not there.
Read the drawing from the readout, from the log, or from a screenshot taken
after a restart; do not read it from a screencap taken a second after a gesture.

## What Ik10 built

> 2026-09-13. `:app`. Nine tests, and the tablet: three ink strokes given the
> pencil repaint in **466 ms** — a pencil is the dear nib, which is Ik0's number
> showing up again — as one undo step, and the undo puts the pen back in
> **34.5 ms**.

Pick some strokes and give them the colour in your hand, or the brush in your
hand, or rub them out. Three buttons in the selection panel, shown only when
something is picked.

### The four operations are one

The plan lists recolour, re-brush, scale the width and re-stabilise. **Three of
them are a brush**: width is `sizeMin` and `sizeMax`, stabilisation is
`stabilization`, and re-brush is the whole of it. So the caller tunes a copy of
the brush and hands over its `BrushCodec` text, and there is one op, one undo
step and one repaint.

### What a restyle keeps, and why that is the point

The samples, the seed and the `dabBase`. So the mark lands in **exactly** the
same place and the grain falls in **exactly** the same pattern; only the colour
and the nib change. That is not a property the pixel version of this operation
could have at any price — recolouring pixels is a mask and a fill, and
re-brushing them is not possible at all.

### The bounds is the union of both

A stroke moved onto a wider nib paints outside the rectangle it painted before,
so a rebuild of only the old rectangle would clip it — and the *old* rectangle
still has to be cleared, or the wide version's edges stay behind. Each
replacement therefore carries the union of what it painted and what it will
paint, which is what makes `VectorStep.damage` right.

### Undo drops the selection, and that is left as it is

Undoing a restyle brings the original records back with their *original* ids, so
the picked set — which is on the replacements — prunes to nothing. The strokes
are right and the selection is empty. Restoring it would mean an undo step that
remembers what was picked when it was made, which is a fourth thing for
`VectorStep` to carry for a convenience; it is written down here rather than
built.

### The picked set has to follow the replacements

A restyled stroke is a **new record with a new id**, because an undo step whose
two halves name the same thing is a step that removes its own replacement. So
`StrokePick.setTo` moves the selection onto the new ids; without it the user
would watch their selection vanish for having changed its colour.

## What Ik9 built

> 2026-09-13. `StrokeTransform` in `:engine`, `StrokeMove` in `:app`. Twenty
> tests, and the tablet: three strokes lassoed and dragged repaint in
> **56.9 ms**, as one undo step, with the selection still on them afterwards.

Pick some strokes and the transform box appears over them. Drag to move, a
corner to scale, the knob to turn. The highlight follows the hand; the strokes
move when the hand comes off the glass.

### A moved stroke is the same hand movement, somewhere else

Not pixels that slid across the page — the samples are mapped and the stroke is
redrawn where they now are. So the grain regenerates in its new place, which is
`docs/vector-plan.md` trap 2 satisfied **by construction**: `GrainTexture`'s
shader is anchored to the page and nothing in this path touches a pixel.

### What moves and what does not

| Channel | Under a transform |
|---|---|
| x, y | Mapped. That is the operation. |
| orientation | **Rotated with it.** It is the pen's azimuth *on the page*, so a stroke turned a quarter turn was drawn by a hand holding the pen a quarter turn round — and a chisel nib that did not turn would be a different mark in its new place. |
| tilt | Unchanged. Turning the paper does not change how far the pen was leaning. |
| pressure, time | Unchanged. Neither is geometry. |

Orientation is stored in -PI..PI, so a turn that carries it past the end wraps
rather than clamping; clamped, a chisel nib would stop turning half way through
a rotation.

### Scale is two things, and the second is Ik10's

Mapping the samples moves the dabs further apart; it does not make them bigger,
because a dab's size comes from the brush. A stroke scaled by two that stayed
thin is a *stretched* stroke, not a bigger one. So the nib is scaled with the
mark — a copy of the brush with its size range multiplied, interned in the
sheet's table, which is exactly Ik10's re-brush reused. Strokes scaled together
share one new entry, and a scale under half a percent adds none at all: that is
below `MaskTolerance`'s own 3% size bucket, so a table entry per pixel of drag
would be a table nobody could read for a difference nobody could see.

A non-uniform scale becomes a uniform nib of the same area. The alternative is
an elliptical nib that changes shape along the stroke, which is a feature nobody
asked for and `MaskSpec` would have to grow a field for.

### The drag previews and the drop edits

`TransformBox` gained one optional callback: `onSettled`, the hand coming off
the glass. `onMatrix` could not serve — it fires eight times a second, and a
caller that turned each into an edit would make eight undo steps for one
gesture. `FloatingPixels` does not need it, because pixels in the air are
dropped by a button; strokes have no such moment, so the end of the drag is the
moment.

While the drag is happening the **highlight** moves and the strokes do not.
Re-rendering fifty strokes eight times a second is not a drag anybody would want
to be on the other end of, and a moving highlight is what a hand aims with.

### Two tokens that stop a transform being applied twice

The box's matrix is cumulative, so it is reset both when the selection changes —
a transform held across a new pick would be applied to strokes it was never
dragged over — and after each drop, because the strokes have absorbed it.

## What Ik12 built

> 2026-09-13. `:engine`, `be.thalos.artiest.engine.guide`. Thirteen tests, no
> device.

`docs/guides-plan.md` prices the guide framework at 8–12 days *before item 16
draws a single ray*. This is the part of it the **ink** has to know about, and
it is one field and three lines:

```kotlin
var snap: Snap? = null                     // on StrokeBuilder
```

`Guide` has one method — *where would this point be if it were on the guide?* —
and that is the whole interface. A ruler, a parallel set, an ellipse and a
three-point perspective ray are all that question with different arithmetic in
it, which is what keeps the snap stage one branch on the hottest path in the
engine rather than a `when` over guide types.

A guide that does not apply at a point answers **false** and the point is left
alone. Ik15's rays are the case that needs it: a stroke started nowhere near any
ray has no ray to snap to, and forcing one would drag it across the page.

### Where it happens, which is the whole of the risk

Between `stabilizer.push` and `resampler.add`. Both halves of that are traps
`docs/guides-plan.md` names, and both are tests:

**After the smoothing.** Snapping first puts the stabilizer's lag *across* the
guide, so the line drifts off the ruler and creeps back — it reads as a loose
ruler. The test draws with stabilisation at 0.9 and asserts every dab is within
a fifth of a pixel of the line.

**Before the spline.** The resampler interpolates between the points it is
given, so snapping its *output* would move the knots and leave the curve between
them bulging off the guide. The test feeds twenty samples far apart, gets over
two hundred dabs, and asserts that **every dab** is on the line — not every
sample.

### The falloff is smoothstep, and the shape is the point

`Snap` carries a strength and a reach. The reach is 0 by default, which means
everywhere — that is a ruler's own behaviour and the one a ruler wants: while it
is on, it is on. A finite reach is for guides that are furniture rather than
instruments.

The curve is smoothstep and not linear or squared, because it is flat at **both**
ends: a hand near the guide does not feel the pull changing as it moves, and the
pull dies away at the edge without a corner. A linear falloff has a corner at the
boundary that reads as a click; a plain square is already down to 0.81 a tenth of
the way in, so a ruler with any reach at all would feel loose everywhere but
exactly on it.

### The predicted tail goes through the same guide

`docs/inker-plan.md`'s tripwire is *"a wet tail that wanders off the guide and
jumps back"*, and the tail is the part of the stroke the eye is on. The fork
that copies the stabilizer now has a twin that applies the snap, and
`drawPredictedTail` uses it.

## What Ik13 built

> 2026-09-13. `:app`, plus `NearestGuide` in `:engine`. Seventy-two tests and a
> tablet. Two commits: the model and the format, then the chrome.

`docs/guides-plan.md` prices the guide framework at 8–12 days *before item 16
draws a single ray*, and this plan took that price. What it did not price, and
what turned out to be the half that mattered, is that **a guide is a filter on
the input and a stroke has to remember which filter it was drawn through**.

### The three types, and why the geometry is a float array

| | What it is | Where |
|---|---|---|
| `Guideline` | A kind, a list of document points, and whether it is on | `:app doc` |
| `GuideSet` | The guides on the document, and one `Snap` for the page | `:app doc` |
| `NearestGuide` | Several guides as one: whichever projects nearest wins | `:engine guide` |

The obvious shape for `Guideline` is one subclass per kind with named fields — a
ruler with two endpoints, an ellipse with a centre and two radii. It is not what
is there, and the reason is the three things that have to be written **once**
rather than once per kind: the codec, the overlay's handles, and dragging. All
three want *the points of this guide* and none of them wants to know what the
points mean; a `when` over kinds in each of them is three places to forget
Ik15's vanishing point. What is genuinely kind-specific is two methods —
`buildGuide` and `outline` — and those are the two that differ.

One kind ships. `RULER` is `LineGuide` with two draggable ends, which is items
7 and 8 of `docs/guides-plan.md` together, and two points rather than a point
and an angle because that is what a hand edits.

`NearestGuide` exists because `StrokeBuilder` takes one `Snap` and a page can
carry a horizon, two vanishing points and a ruler at once. Its own KDoc says
where nearest-projection stops being enough, and it is worth repeating here
because it is Ik15's first problem: **with three vanishing points live, every
ray of every point passes through the pen sooner or later**, so "nearest ray" is
nearly always the ray you are standing on rather than the one you are drawing
along. Item 18's rule — *closest to the stroke's own direction* — needs
something the `Guide` interface deliberately does not have, which is where the
stroke came from. That belongs to the perspective guide itself, and
`NearestGuide` is what it will be **one of** rather than what it will replace.

### A stroke keeps the ruler it was drawn against, and it had to

This is the part the plan did not see coming, and it is not optional.

A record stores the **raw** samples. The snap is applied on the way to the dabs,
between the smoothing and the curve fit — which is Ik12's whole argument and is
not negotiable. So a record that did not name its guide would re-render *off*
the ruler the first time anything repainted the rectangle it is in. What repaints
a rectangle is an undo three strokes later: silently, and nowhere near the thing
that caused it.

That is word for word the failure the clip table was invented to prevent, so it
gets the clip table's answer: `StrokeRecord.guide`, an index into a per-sheet
table of `GuideText` lines, `NO_GUIDE` for the freehand stroke that is nearly
all of them. Interned **whole** rather than per ruler, because every stroke of a
sitting is drawn against the same set — a page of inking against one ruler is
one table entry. `StrokeCodec` goes to version 3 and `ProjectJson` to format 3;
a version 2 file decodes with `NO_GUIDE`, and that is not a default standing in
for a lost value, because there was no guide to draw against.

**The stabilizer needs no such field**, and the reason is why the guide is the
only filter with the problem: smoothing is a weighted average of past points, an
affine map distributes over one, so the two commute. A projection does not.

That same fact is the whole of why a dragged stroke needs its ruler dragged with
it. A record stores the mapped raw samples, so a rebuild computes
`snap(smooth(M·x))`; that equals `M·snap(smooth(x))` only when the guide is
mapped too. Leave it alone and a moved line springs back onto the ruler it was
drawn along, the first time anything repaints it. `GuideText.mapSnap` is the
twenty lines that fix it, and the reach scales with the stroke because a reach
is a distance in document pixels.

`StrokePolyline` takes the snap for the same reason pointing the other way: it
is meant to be *where the ink is*, and the ink of a ruled stroke is on the ruler.
Without it a tap on a ruled line misses by however far the hand was from the
ruler, and Ik7's highlight is drawn beside the line rather than along it.

### The one interface decision, which was made in advance

`docs/guides-plan.md` trap 4 says dragging a guide competes with panning and
that deciding it late means rewriting it. This plan decided it before writing a
line — **handles in arrange mode, and only there** — and the section above that
says so is unchanged by what was built.

What is worth adding is how it coexists with the dock, because arrange mode is
*also* where toolbars are dragged and shapes are painted. `GuideHandles` uses
`awaitEachGesture` rather than `detectDragGestures` and tests the **down** event
against the guides before consuming it. A layer that swallowed every pointer
over the canvas in arrange mode would have broken both other things.

### What the tablet found, which was the same defect twice

Both are about *saying so*, and both are now tests.

**A drawing opened with its guides live and nothing on screen to say why.** The
pen really was snapping. `GuideSet` is a plain mutable object — deliberately: it
is read on the render thread at pen-down and a Compose snapshot would not
survive that crossing — so the overlay and the panel both hang off a counter,
and nothing bumped it when a project arrived.

**Laying a ruler down was not a change worth saving.** `ProjectSaver.dirty` is a
poll rather than a hook, and its KDoc says why: every alternative means
remembering to call something from a stroke, a clear, an undo, a layer
operation, an import *and whatever the next feature adds*. The guides are the
next feature, they are the page's rather than a sheet's, and they touch no layer
and no stroke list — so the poll answered "nothing to write" and a grid somebody
spent five minutes placing was gone on the next launch. The fix is one counter
compared beside the eight longs, which is what the poll was shaped for.

### What is left for Ik14

The framework carries `strength` and `reachDoc` already and the panel has both
on sliders, so item 14 — snap falloff — is **done** rather than pending; what
Ik14 owns is the other guide *kinds*, and each of them is `Guideline` plus one
`buildGuide` branch and one `outline` branch.

There is no per-guide strength, and that is a deliberate absence rather than an
oversight: a perspective grid to lean on and a ruler to obey, on one page, is a
real thing to want, and it is not there because nothing today would read it.

## What Ik14 built

> 2026-09-13. `:engine` and `:app`. Twenty-two tests and a tablet.

Two more kinds, and the framework held: each is one `Guide` in `:engine`, one
`buildGuide` branch and one `outline` branch. The codec, the persistence, the
overlay, the hit test and the snap stage are all untouched, which is what Ik13's
float-array geometry was for.

### The ellipse, which was written twice

`docs/guides-plan.md` item 13, and the entry that justifies Tier 1 to an inker.
It is also the only hard arithmetic in the framework.

The obvious iteration — start from the angle the *circle* would give and walk
the parametric angle toward the foot of the perpendicular — converges
beautifully from outside the ellipse and **does not converge at all** near the
long axis inside the evolute. The cross product it steers by is exactly zero
there, so it sits still and answers the end of the axis: on a 300 by 40 ellipse
the point (26, 0) came back 274 pixels from the curve instead of 40.

The shape of the test that caught it is worth more than the fix. It is brute
force against four thousand points on the ellipse, because **a wrong answer that
is still on the ellipse is exactly what converging to the wrong root looks
like**, and every cheaper check passes.

The replacement is Eberly's method: bisection on a root that is monotone by
construction, which cannot fail and needs no starting guess. In **double**,
because the root lands at −1 + ε for a point near the long axis and in float the
sum `s + 1` there has about one significant bit left — which came out as a
nearest point two pixels *outside* an ellipse forty pixels tall. It runs once
per sample rather than once per dab, so a double divide costs nothing anybody
can measure.

### The parallel ruler, which cost a word

Item 10, and the cheapest useful thing in the tier: hatching and speed lines are
*many lines at one angle in different places*, which is otherwise a ruler
dragged between every stroke.

It is the one guide that is not a fixed thing on the page. A ruler is a line you
draw along; this is an **angle**, and the line is wherever the pen landed. So
`Guide` grew `begin(xDoc, yDoc)` and its contract went from *must be pure* to
*must be pure **within one stroke***.

That is a real weakening of an interface this plan wrote deliberately, so the
alternative is worth recording: a guide that latched its origin on the first
`project` call instead would be one whose answer depended on whether the
predicted tail had run yet — the same defect one layer down, and harder to see.

The origin is the **raw** first sample, which is the one value that is identical
live and on a replay: the stabilizer has nothing to smooth on the first sample,
and a rebuild feeds the same first sample back through the same call. So a
stroke drawn against a parallel ruler re-renders exactly and **nothing extra is
stored to make it so** — which is the one place Ik13's guide table did not have
to grow.

### The curve, which is items 12 and 15 at once

`docs/guides-plan.md` lists *curve guide* and *guide from a drawn stroke* as two
entries, and they are one: the interesting half of a French curve is not
projecting onto a polyline, it is **getting the polyline**, and the honest
answer to "where would a default French curve go" is *you draw one*. Ik7 picks a
stroke and Ik1 keeps its centreline, so the panel grows one button and the
feature is the join between two that already existed.

Two things had to give, and both were the float-array geometry paying off a
second time. `GuideKind.points` gained `ANY`, because a curve's point count is
its shape rather than a property of its kind — and only the model and the codec
branch on it. `GuideKind.handles` is new and false for the curve: a traced curve
has hundreds of points, a knob on each would bury it, and a French curve is a
thing you *slide* rather than reshape.

It clamps at its ends. The line follows the curve and stops where the curve
stops, which reads as a stop and not a blob because the dab emitter spaces by
arc length. Answering false past the end instead would jump the ink from the end
of the curve to wherever the hand had got to — a kink at the moment the hand is
going fastest.

### What is left

Nothing in Ik14. The four kinds `docs/guides-plan.md` asks for at Tier 1 — 8,
10, 12 and 13 — are built, item 14's falloff came with Ik12's `Snap`, and item
15 came free with the curve. Item 9's 2D grid and item 11's radial ruler are not
built and are each one `buildGuide` branch and one `outline` branch, which is
the price the framework was designed to make them cost.

## The Inker workspace, which is what all of this was for

> 2026-09-13, on the user's ask: *"create a nice worklayout for the inker."*

`ShippedWorkspaces` has said since workspaces landed that *Inker*, *Painter*,
*Webtoon* and *Animator* ship when the tools behind them do and not before,
because **a workspace with a name and nothing behind it is the one way that
feature can make the app feel worse instead of better**. Ik1–Ik13 are what was
missing. Before them, *Select* meant pixels and there was nothing to lean a line
against; *Inker* would have been *Sketcher* with the buttons in another order.

Four bars, and the three arguable decisions are argued in the KDoc rather than
left as taste:

- **No pencil and no soft eraser on the bar.** Ink is a hard edge, and a soft
  eraser fades a passage — which is what the end of an ink line must not do.
  Both are still offered and both are one tap away on the shelf; the two cells
  buy the marquee and the selection panel, on the side the free hand rests on.
- **The marquee is in the tool column**, which is the one place this disagrees
  with `DockLayout.STARTER`. On an ink sheet the marquee picks *strokes*, so
  rubbing a line back to its junction is done with it. That is a tool.
- **Guides are in the tool column too**, and the same disagreement for the same
  reason: an inker lays a ruler down and inks along it.
- **Stabilisation is the first slider**, where Sketcher has size first, and it
  arrives at 0.55 against the app's own 0.15. A sketcher wants the wobble; an
  inker is trying to get rid of it.

It is also the first workspace with anything in its `defaults`, which made
`WorkspaceStore`'s long-standing promise — *"apply whichever defaults have a
subsystem to receive them"* — true for the first time. **On arrival and not on
every launch**: a `LaunchedEffect` keyed on the workspace would re-apply them
each time the app opened and quietly undo the tuning done in the session before.

## Stop conditions

The phase's, in the order they can fire.

1. ~~**Ik0 says re-rendering is unaffordable.**~~ **Fired, and the fallback it
   named was wrong.** The pencil rebuilds 300 strokes in 6.41 s against a 1 s
   bar — but so does the pen, at 1.09 s, so "vector sheets for opaque nibs only"
   fails its own test. The answer is **damage rectangles as the design rather
   than as an optimisation**, and a tight one. See **What Ik0 measured**. Left
   in the list rather than rewritten, because a stop condition that fired and
   changed the plan is the most valuable line in the document.
2. ~~**Ik2's golden cannot be made to pass.**~~ **Cleared.** A record
   re-renders pixel-identically, for the pencil as well as for the opaque nibs —
   0 differing pixels where Ik0 measured 105 052 per million. See **What Ik2
   changed**. The fallback it named, storing the *dabs* rather than the input at
   roughly six times the bytes and losing re-brushing and re-stabilisation, is
   not needed. Left in the list because a stop condition that was cleared on
   evidence is worth as much as one that fired.
3. ~~**Ik4 forces a second compositor.**~~ **Cleared.** `rebuild` calls
   `stampStroke`, which is the commit path's own method, with the commit path's
   `ScratchLayer` and `DabRasterizer`. What it needed instead was a way to
   confine a stroke to a rectangle and to the record's own clip, which is a
   canvas clip and eleven lines. See **What Ik4 built**.
4. ~~**Ik8's erase-to-intersection is not the feature people mean.**~~
   **Cleared on the arithmetic, and it wants a hand on it.** On the tablet a tap
   on an overshoot cut the stroke at sample 40 of 62, which is exactly where the
   crossing is. What a log cannot answer is whether it *feels* like the right
   junction when the lines are not two ruled strokes — that is Ik16's, with the
   pen in a hand. See **What Ik8 built**.
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
