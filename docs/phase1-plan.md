# Phase 1 — Minimum ink

> Reconciled 2026-09-07 against what was measured on DTHA116, against 13
> serious refutations from adversarial review of the first draft, then after
> **W0** fired its stop condition, and finally after **W1 and W2 ran and the
> arms were judged on hardware**. See **What changed** at the end for the delta.

## The decision

Phase 1's wet-ink path is **`CanvasFrontBufferedRenderer`** — chosen on the only
criterion the phase has, which is how it feels under the pen.

The route here was not straight. W0 measured that this device has **no front
buffer at all**: the MT8781V/NA gralloc refuses `USAGE_FRONT_BUFFER`, including
the bare bit, so `CanvasFrontBufferedRenderer` has always run in its fallback
path. That fired the stop condition, and W1 built the replacement —
`DirectSurfaceInkView`, a plain `SurfaceHolder` on a dedicated render thread
using `lockHardwareCanvas` — so the two could be compared directly, with the
plain `View` baseline as calibration.

**Judged on hardware at a verified 90 Hz, graphics-core still won.** Its ink sits
closest to the pen tip. Baseline and DirectSurface both read as *smoother* —
evenly spaced frames — but visibly further behind. That is a signature of
vsync-locked scheduling: both of those paths wait for the next vsync before
drawing, while `renderFrontBufferedLayer` is called per sample straight from
`onTouchEvent` and starts drawing at once.

**We are shipping the winner without knowing why it wins.** The coalescing
explanation above is a hypothesis that fits the evidence; it was not tested,
because for Phase 1 it does not need to be — the bar is felt quality and one arm
plainly clears it. It becomes a real question again in **Phase 2**, when a
custom GL engine has to reproduce the win without the library, and it is better
answered then with the real engine to measure against than now against
`drawLine` segments.

Everything else stands. Build Phase 1 as **two Gradle modules**: `:engine`, a
pure-Kotlin JVM module holding every stroke decision testable without a device,
and `:app`, the single Android module holding everything that touches a
`Canvas`, a `Surface` or a `MotionEvent`. The document is **one ARGB_8888
`Bitmap` at 2160×3300**, and the layer is written **exactly once per stroke, on
the render thread, inside `onDrawMultiBufferedLayer`** — never incrementally,
never from the UI thread. That single write makes `ACTION_CANCEL` free (the
layer never contained the stroke), gives one writer and one lock, and deletes an
entire tile-backup subsystem.

### What this choice costs, stated plainly

`CanvasFrontBufferedRenderer` accumulates. Even with no front buffer it preserves
its buffer between renders and draws only the params added since the last flush,
so pixels already drawn are never re-transformed. Choosing it therefore buys back
every constraint the `DirectSurface` design had removed:

- **The transform freezes for the lifetime of a wet stroke.** No live zoom or
  rotate while the pen is down.
- **Strict pen/gesture mutual exclusion** in `InputRouter` to enforce that.
- **Predicted ink is unretractable** — a speculative dab is baked until pen-up,
  which is the harsher of the two failure modes for prediction.
- **Clearing takes three calls in a specific order**, because `clear()` never
  invokes the draw callback.
- **A dependency on `androidx.graphics`** whose headline feature this device does
  not have.

That is a real trade: canvas interaction and design complexity, paid for feel
that can be seen with the naked eye. Given the phase's stated bar, it is the
right way round — but it is a decision made knowingly, not a drift.

**Keep the `InkSurface` seam regardless.** It made the W0 pivot cost a day rather
than a week, `DirectSurfaceInkView` stays in the frozen spike as a control, and
Phase 1 codes against the interface with graphics-core behind it. It costs
nothing and it has already paid for itself once.

## What Phase 0 settled, and what it did not

**Settled, from `~/artiest-phase0/*.json`, `dumpsys` and on-device comparison:**

- MT8781V/NA, Mali-G57 MC2, GL ES 3.2, `GL_MAX_TEXTURE_SIZE` 16383 — so 4096² is
  a *memory and bandwidth policy*, not a GPU limit.
- Panel 1440×2200, modes 60 and 90 Hz. 90 Hz is reachable **only** via
  `adb shell settings put system peak_refresh_rate 90.0` + `min_refresh_rate`;
  Wacom's `/vendor/etc/displayconfig/display_id_0.xml` pins peak to 61 with
  `mAlwaysRespectAppRequest=false`, so no app code can substitute for it. 60 Hz
  is the shipping default.
- Pen: 246.85 Hz at 60 Hz, **321.75 Hz at 90 Hz** — the digitizer rate tracks the
  panel, so the refresh override buys input rate as well as frame rate. 8192
  pressure levels; minimum observed pressure 0.00208, so the light end has real
  resolution and any dead zone is the curve's fault, not the sensor's. Tilt to
  0.98 rad, full ±π orientation.
- `requestUnbufferedDispatch` works: ~1.14–1.19 samples/event at ~218–271
  events/sec, far above the panel. But 4–6% of events still carry 2–4 historical
  samples, so **history iteration stays mandatory**.
- **`AXIS_DISTANCE` is dead on this digitizer.** 400 hover samples captured
  through a demonstrably correct `onHoverEvent`/`getAxisValue` path, every one
  reporting distance 0. `docs/analysis.html:313`'s hover-distance palm rejection
  cannot be built as written.
- **The pen has no eraser end.** Flipping it reports `TOOL_TYPE_FINGER`, never
  `TOOL_TYPE_ERASER` — which is the one bucket palm rejection is built to
  discard, so flip-to-erase is not merely absent but actively unusable. All
  three barrel buttons are individually addressable: `BUTTON_TERTIARY` (4),
  `BUTTON_STYLUS_PRIMARY` (32), `BUTTON_STYLUS_SECONDARY` (64). **Eraser is a
  button mapping, not a tool type.**
- **The graphics-core path beats the baseline, by eye, clearly.** Prediction and
  unbuffered dispatch both made it *worse*. See "The A/B result" below — and
  note that W0 changed what this result *means*, not whether it happened.
- **No front buffer exists on this device.** `HardwareBuffer.isSupported(1, 1,
  RGBA_8888, 1, 4294970112)` returns **false**, and so does the bare
  `USAGE_FRONT_BUFFER` bit (4294967296) on its own — so no superset can ever be
  granted. Confirmed four ways: the app's own probe; a standalone dex under
  `app_process`; `dumpsys SurfaceFlinger --allocated_buffers` showing the live
  buffer at `usage: 0xb30` (2864), which is exactly graphics-core's *fallback*
  constant and not `0x100000b00`; and bytecode. A discrimination control ruled
  out a gralloc that refuses everything — 2816, 2864, 512 and 256 all return
  true, a bogus bit returns false.
- **A full redraw holds 90 Hz with 2.8x headroom.** W2: draw p99 4.00 ms against
  an 11.1 ms budget, gap pinned to vsync, 0% dropped over 600 frames, with the
  layer rotating through a live matrix. The per-frame blit is ~2.6 Mpx because
  the surface is view-sized (2200x1181), not the full 7.1 Mpx document.
- **The 90 Hz override lapses on its own, and `settings get` does not show it.**
  It keeps reporting 90.0 while `dumpsys display` shows the render range capped
  at 60. Only a fresh 60 -> 90 bounce revives it; rewriting 90.0 over 90.0 is a
  no-op that changes nothing. Ruled out as a cause: the app's 1000 fps frame-rate
  vote, which holds 90 Hz fine once bounced. **Bounce and verify `activeMode`
  immediately before any judged run.** **W16 pinned the trigger and it changes
  the procedure: starting an activity drops the vote.** Bounced *before* a launch
  it is gone by the time the app is on screen; bounced *after*, it survived 200 s
  of continuous polling and a full stress run start to finish. So the order is
  launch, then bounce, then verify, then run — and **verify again afterwards**,
  because a run that began at 90 and ended at 60 is void and otherwise
  indistinguishable from a good one.
- **Memory: 7.70 GiB total**, 4.4 GiB free, low-memory threshold 0.21 GiB.
  `memoryClass` 256 / `largeMemoryClass` 512 do not bind, because on API 34
  bitmap pixels are native-heap allocations. **Do not set `android:largeHeap`.**

**Not settled:**

- **Why graphics-core wins.** It does — W1 settled that on hardware — but the
  mechanism is unverified. The leading hypothesis is vsync coalescing:
  `renderFrontBufferedLayer` is called per sample straight from `onTouchEvent`
  and starts drawing immediately, while both losing arms wait for the next vsync,
  which fits "smoother but further behind" exactly. Alternatives not ruled out:
  the unconditional 1000 fps frame-rate vote changing SurfaceFlinger's
  presentation deadline, or buffer-queue depth. **Not a Phase 1 blocker** — we
  ship the winner. It becomes load-bearing in Phase 2, when a GL engine has to
  reproduce the win without the library.
- ~~**No absolute latency number exists.**~~ **Answered at W16: 45 ms**, pen to
  photons, at 90 Hz with smoothing off and prediction off, measured from a
  240 fps film of a zigzag. Of that, **9.3 ms is the app and 36 ms is
  SurfaceFlinger and the panel** — 3.2 refreshes, which is what "no front
  buffer" costs. See the W16 note.
- ~~Whether `MotionPredictor.isPredictionAvailable` returns true for this pen, or
  `SystemMotionEventPredictor` silently falls back to the bundled Kalman
  predictor.~~ **Answered at W11, and the answer is odd.**
  `MotionEventPredictor.newInstance` resolves to `SystemMotionEventPredictor`,
  so the platform path is selected — and `MotionPredictor.isPredictionAvailable`
  returns **false** for this pen's device id and source. It predicts anyway:
  `predict()` puts the pen a mean of **41.5 doc px** ahead of the last real
  sample on the stress stroke and **91 doc px** on a faster injected one, about
  27 ms of lookahead either way. So the availability flag does not gate what
  comes out, and "prediction was judged and lost" is a statement about something
  that is running rather than about a no-op.

### The A/B result

"Front buffer on, everything else off" is the best configuration on this
hardware. W0 then established that **it was never front-buffering**: the toggle
selected `CanvasFrontBufferedRenderer` running in its fallback path, and what it
was being compared against was `BaselineInkView`, a plain `View` going through
the ordinary view hierarchy and HWUI.

The win is real — it was seen — but the mechanism is not the one the name
implies. W1 built the obvious replacement, reproducing what the library appeared
to be doing: a dedicated `SurfaceControl` on its own render thread plus the same
unconditional 1000 fps frame-rate vote
(`configureFrontBufferLayerFrameRate`, verified unconditional in bytecode).

**It was not enough.** Judged three ways at a verified 90 Hz, graphics-core was
still clearly closest to the tip; `DirectSurface` and the plain-`View` baseline
both read as smoother but further behind. Whatever graphics-core is doing, it is
not only those two things — and the design that reproduces two of its three
ingredients still loses. That is why Phase 1 ships the library rather than a
reimplementation of it.

It also gives the unbuffered-dispatch regression a plausible mechanism: with no
front buffer, each `renderFrontBufferedLayer` is a full SurfaceControl
transaction, and 271 of those per second against a 90 Hz scanout is a great deal
of work for frames nobody sees.

Two further qualifications the number-free method cannot resolve, both of which
shape work items rather than the decision:

**Prediction's grey is an instrument artifact.** `LowLatencyInkView` draws
predicted ink at `Color.argb(90, 0, 0, 0)` deliberately, so overshoot is
*visible* to a 240 fps camera. Shipping prediction would draw it in the real ink
colour, where the same behaviour reads as the line leading slightly rather than
as a grey ghost. Prediction lost partly on an artifact of the measuring
instrument, so W12 keeps it behind a toggle and re-judges it on tip-lead and
reversal spurs, not on colour.

**Unbuffered dispatch hurting the front buffer is real but unexplained.** The
sample rate is identical either way (~270/sec); only the arrival pattern
differs. The cumulative export shows the two dispatch modes cleanly separated —
11,044 events carrying zero historical samples, 3,071 carrying exactly four. The
working hypothesis is that 271 individual `renderFrontBufferedLayer` submissions
per second against a 90 Hz scanout build a backlog that batches of four do not,
which would mean each submission costs a full SurfaceControl transaction. W0's
composition check and W10's allocation trace should confirm or kill this. Until
then it is a hypothesis, and the plan ships with unbuffered dispatch **off** for
the front-buffered path while keeping `requestUnbufferedDispatch` in the code
behind the same toggle.

## Architecture

```
artiest/
├── settings.gradle.kts        includes :spike (frozen), :engine, :app
├── engine/                    kotlin("jvm") — ZERO Android imports, enforced by the plugin
│   └── be.thalos.artiest.engine
│       ├── input/  PenSample, ToolType, Stabilizer, PredictionGate, TwoFingerDoubleTap
│       ├── ink/    RoundPen, CatmullRomResampler, StrokeBuilder, Stroke, Bounds
│       ├── xform/  CanvasTransform, GestureSolver
│       └── trace/  TraceRecorder, TracePlayer
├── engine/src/test/           JUnit, runs natively — no Robolectric, no mockable android.jar
├── app/                       com.android.application, be.thalos.artiest
│   └── be.thalos.artiest
│       ├── input/  MotionEvents (collectSamples ext), InputRouter, Predictor
│       ├── canvas/ InkSurface, InkSurfaceView, DabBatch, DabBatchPool, GestureController, Matrices
│       ├── doc/    Document, Layer
│       ├── ink/    DabRasterizer
│       ├── io/     PngExporter, ExportResult
│       └── DeviceProbe, RefreshPolicy, MainActivity
└── spike/                     Phase 0 harness (be.thalos.artiest.spike), frozen after W3
```

**Why two modules and not one or seven.** The only boundary worth paying for in
Phase 1 is the one a *compiler* enforces, and `kotlin("jvm")` enforces it for
free — an `android.graphics` import in `:engine` will not compile. That gets
`CanvasTransform` and the whole stroke pipeline unit-tested natively, which
matters disproportionately here: this machine builds Android only through a
hand-bootstrapped SDK and a wrapper that had to be generated from an upstream
distribution because the distro's Gradle is broken. Tests that need no device
are worth a module boundary.

Everything else (`:canvas`, `:io`, `:ui`, `:brush`, `:document`) stays a package
named after its future module, so the Phase 2/3 split is a `git mv`. No
`build-logic` and no convention plugins: they deduplicate config *across Android
modules* and there is exactly one. They arrive in the same commit that creates
the second. `:app` takes `applicationId be.thalos.artiest`, distinct from
`be.thalos.artiest.spike`, so both APKs sit on the tablet.

**Render path — `CanvasFrontBufferedRenderer`.** `InkSurfaceView` is a bare
`SurfaceView` owning a `CanvasFrontBufferedRenderer<DabBatch>`, constructed in
`onAttachedToWindow` and `release(true)`d in `onDetachedFromWindow` — the
spike's lifecycle, javap-verified against the 1.0.4 aar.

**It must not call `setBackgroundColor`.** Not a style preference: a
`SurfaceView` shows its surface through a transparent hole punched by
`clearSurfaceViewPort()`, and `super.draw()` paints the view's background *over*
that hole. An opaque background hides the surface completely — ink, frame
counter and all — while the renderer keeps submitting frames perfectly. This
cost a full session of blank-canvas debugging and was fixed in `b02a531`. Paper
white comes from `onDrawMultiBufferedLayer`'s `drawColor`.

`InkSurfaceView` implements a narrow `InkSurface` interface — `beginStroke(Matrix,
colorArgb, antiAlias)`, `acquireBatch()`, `drawWet(DabBatch)`,
`commitStroke(Stroke)`, `cancelStroke()`, `redrawDry()`, `release()` — expressed
in strokes and dabs with **no library type in the signature**. That seam is why
the W0 pivot cost a day, and it stays even though the pivot was ultimately
reversed — Phase 2 will want it again.

W8 widened it twice against the first draft's four methods, and both are
threading, not convenience. `beginStroke` takes the colour and the antialias
flag alongside the matrix because all three are frozen at the same instant and
for the same reason — the wet pass and the dry commit must paint the same
stroke the same way. And `acquireBatch()` is on the interface rather than on a
pool the caller keeps, because whether a batch slot is safe to reuse depends on
the *implementation's* completion signal; a GL implementation's is a fence, not
a callback return.

- **`onDrawFrontBufferedLayer(canvas, w, h, batch)`** — the library invokes this
  **N times on one `RecordingCanvas` with no `save()`/`restore()` between
  invocations**, so a bare `canvas.concat(m)` compounds: the second batch in a
  flush draws at M², the third at M³. The body must be
  `val s = canvas.save(); canvas.concat(frozenDocToView); …drawDabs…;
  canvas.restoreToCount(s)`. Both allocate nothing, so the zero-allocation
  budget survives.
- **`onDrawMultiBufferedLayer(canvas, w, h, params)`** — ignores `params`
  entirely and reproduces the scene from app-held state. It (1) takes
  `pendingStroke` via `AtomicReference.getAndSet(null)` and rasterizes its dabs
  into the layer `Bitmap` under `layerLock`; (2) `drawColor(paperWhite)`;
  (3) `concat(docToView)`; (4) `drawBitmap(layer, 0f, 0f, filterPaint)` where
  `filterPaint` has `isFilterBitmap = true` and `isAntiAlias = false` — **a null
  Paint means point sampling**, which would nearest-neighbour-resample the whole
  document at every zoom and make fine ink crawl. It never posts to or blocks on
  the main thread: the synchronous `surfaceRedrawNeeded` path awaits it on an
  untimed `CountDownLatch`.

  **Register the app's own `SurfaceHolder.Callback`** and call `redrawDry()`
  from `surfaceChanged`. The library does *not* redraw there — it only tears
  down and rebuilds its SurfaceControls — so without this the canvas blanks on
  rotation or resize.

**Do not translate `renderFrontBufferedLayer` into a vsync-coalesced loop.** The
library calls it once per sample straight from `onTouchEvent`, and W1's evidence
is that this is where the feel comes from: the two arms that waited for the next
vsync before drawing both read as smoother *and further behind*. Coalescing looks
like an obvious efficiency win — W2 shows throughput was never the constraint —
and taking it would trade away the only thing this path was chosen for.

`DirectSurfaceInkView` stays in the frozen spike as the control that established
this, and `LowLatencyCanvasView` is **rejected** with the reason in a comment so
Phase 2 does not reopen it: its internal scene `Bitmap` is *view*-sized, so it
cannot represent a 2160×3300 document you pan around, and its `onDraw` calls
`canvas.setMatrix` (replace, not concat). GL is **deferred to Phase 2** and
recorded as debt: Phase 1's entire render is antialiased circles plus one
matrixed bitmap blit, both of which hardware Canvas already does on the GPU.

**Threading contract, stated once and enforced:** the layer `Bitmap` is written
on the render thread only, only in the commit step at pen-up, under `layerLock`.
`PngExporter` takes the same lock to read. The UI thread never touches it.

**The handoff is a queue, not a slot — corrected at W10.** The first draft said
`AtomicReference.getAndSet(null)`, on the grounds that it consumes a stroke
exactly once so a dropped frame cannot double-stamp. That is true and it is only
half the requirement: `getAndSet` gives *at most* once, never *at least* once.
Two pen-ups inside one render-thread stall — a quick pair of tick marks, a
signature, a hatch — and the second `set` overwrites the first, whose ink is then
missing from the layer while `Document.recordStroke` has already counted it.
Nothing fails; a stroke is simply not there. A second hole came with it: Clear
was a separate flag beside the slot, and two independent fields cannot express
"after everything I have drawn", so a stroke committed just before Clear survived
it depending on which branch the render thread read first. `CommitQueue` is one
FIFO carrying both kinds of commit, owned by the `Document` rather than by the
view, lock-free at both ends, and one allocation per pen-up. `CommitQueueTest`
carries the slot-and-flag version longhand and shows it losing a stroke and
inverting a clear.

**Input pipeline** (mandated order, prediction before stabilization):

```
MotionEvent (+history) → PenSample → route → toDoc → predict → stabilize → Catmull-Rom → resample → dab
```

`MotionEvent` enters only at `InkSurfaceView.onTouchEvent`/`onHoverEvent` and
dies at `InputRouter`. `PenSample` carries the doc's eight fields plus
`buttonState` and `Source{CURRENT, HISTORICAL, PREDICTED}` — the spike's data
class moved to `:engine`, with `ToolType` constants mirroring `MotionEvent`'s so
the module stays Android-free. Three edits to `collectSamples`: it takes a
**pointerId** resolved through `findPointerIndex` per event (indices shuffle when
a pointer lifts, so a cached index is a bug waiting for the second finger);
`sampleAt` takes an explicit `source` so `Source.PREDICTED` finally has a
producer; and the event is expanded **exactly once** into one reusable scratch
list (the spike expands twice, in `PenCapture` and `LowLatencyInkView`).

`onHoverEvent` routing is kept as a **presence** signal. The distance axis is
dead, but the routing itself is a genuinely non-obvious asset — the framework
sends `ACTION_HOVER_*` there and not to `onTouchEvent`, which is exactly the
kind of thing that silently makes working hardware look broken. `PenStats` does
not come forward.

`Stabilizer(strength: Float in 0..1)` is a real, zero-capable stage: at
`strength == 0` it is **bit-exact identity**, and that is a unit test, not a
comment. It is an exponential with a time constant **integrated over each
sample's dt**, not a per-sample lerp — the digitizer runs 246.85 Hz at 60 Hz and
321.75 Hz at 90 Hz, so a per-sample lerp would change the feel of the line with
the refresh rate and silently invalidate every tuning decision. Default 0.15, on
a toolbar slider.

`RoundPen` carries the doc's Phase 2 struct field *names* (`spacing`, `sizeMin`,
`sizeMax`, `hardness`, `opacity`, pressure→size curve, `stabilization`,
`antiAlias`) on a plain class, so Phase 2's serializable brush model is a lift
rather than a redesign. `sizeFor(pressure, elapsedMillis)` is a cubic plus an
**onset ramp measured in milliseconds** — 12 ms at full lift, released linearly
over 12 more.

Two corrections to the earlier wording, both made in W7 and both recorded in
`RoundPen`'s own comments. **The ramp is wall-clock, not "the first few
samples"**: four samples is 12.4 ms at 321.75 Hz and 16.2 ms at 246.85 Hz, so a
sample-counted ramp changes length by a third with the refresh rate — the same
defect this document correctly rejects one paragraph earlier for the stabilizer.
And **the tip is not invisible**: with `sizeMin` at 1.5 doc px the measured
0.00208 pressure still paints a 1.5 px mark. The real complaint is that for the
opening stretch of every stroke the nib sits at that floor however hard the user
pressed, then swells — which reads as the ink starting a beat behind the pen and
gets misdiagnosed as latency. The lift is also applied *after* the curve rather
than to the pressure: releasing a pressure floor linearly and then cubing it
sheds 55% of the width change in the first 3 ms, measured, which is a notch.

`StrokeBuilder` fits **centripetal** Catmull-Rom through the stabilized
document-space points and resamples at `spacing` (1/8 diameter), emitting
`x, y, radius` triples into a reusable `FloatArray` while accumulating `Bounds`.
A fast stroke leaves tens of pixels between samples, so this stage is not
optional even at 320 Hz.

No `Dab` and no `DabList`, against the module tree above: W6 already gave
`Stroke` a flat `FloatArray` for this, and a `Dab` object per dab is an
allocation per dab on the one path with a per-sample budget. Centripetal rather
than uniform is measured, not preferred — on a long approach into a short
segment before a hard turn, uniform wanders 7.4 doc px off a straight run and
centripetal wanders 0.6, and `CatmullRomResamplerTest` carries the uniform
control so the comparison is against arithmetic rather than against a claim.
The fit costs **one sample of latency** — 3.1 ms at 321.75 Hz — because a
segment needs the point after its endpoint, and a speculative tail is not
available here: front-buffer ink is unretractable. Budget: **under
0.31 ms per event, and one immutable `PenSample` per digitizer sample and
nothing else** from `onTouchEvent` to `renderFrontBufferedLayer`. **W9 measured
both and both hold.** On the release build, driving a deliberately punishing
stroke — one event per frame carrying 5.31 samples and **77.2 dabs** — the path
costs **p50 0.119 ms, p99 0.185, max 0.295 ms**, which is 0% of events over the
ceiling; and it allocates **27 to 55 B per digitizer sample** in steady state against the
~56 B predicted for one `PenSample`, so "and nothing else" is a measurement now.
That range is the instrument, not the variance: `totalMemory() - freeMemory()`
moves in 32 KiB steps, so a 1201-sample stroke resolves to one or two steps and
nothing finer. W9 reported 54.6 B/sample, which was one step read as a figure;
repeat runs land on 32,768 B and 65,536 B, both exact. The conclusion survives
the correction — one or two 32 KiB steps is what one ~56 B `PenSample` per
sample and nothing else looks like — but the third significant figure was the
instrument's and not the code's. The earlier wording said "zero allocation",
which the shipped input path cannot meet and should not: pooling `PenSample` is
**off the table**, because `TraceRecorder` retains the samples it is handed and
a recorder holding pooled instances records aliases that the next event
overwrites. Everything else on that path is allocation-free — one scratch
`ArrayList`, three preallocated gesture arrays, an `Int` decision mask, index
loops on every emit path. `DabBatch` comes from a preallocated ring
(`DabBatchPool`). **Corrected at W8: the completion signal the first draft said
did not exist is `onDrawFrontBufferedLayer` itself.** `renderFrontBufferedLayer`
is async and returns nothing, which is true, but by the time the draw callback
returns the batch has been read and its slot is free — and "read" is the only
question a pool has to answer, as opposed to "presented", which nothing answers.
So reuse is provably safe rather than sized by hope: the render thread reports
the sequence it drew, `acquire` compares it against what has been issued, and an
exhausted ring **allocates and counts a spill** instead of aliasing a batch the
library is still holding. That trades a correctness bug for an allocation, which
is the right trade here, and it makes undersizing a number W9 reads rather than
a glitch someone eventually notices. **W9 set the slot count from that number:
8, down from W8's 24.** Across every run of the punishing stroke `peakInFlight`
never exceeded **3** and `spills` stayed at **0**; a firm-pressure stroke peaked
at 1. 24 was eight times a figure that was itself conservative, and 8 keeps a
2.6x margin at 6 KB allocated once. The margin can be that thin only because
exhaustion is no longer a bug — it degrades to one allocation and a counter.

`TraceRecorder`/`TracePlayer` serialize the `PenSample` stream and replay it
deterministically. It is the only reproducible regression test that exists for
this pipeline, and it turns "does it feel good" from a subjective re-draw into
the same stroke replayed after every tuning change.

**Transform model.** `CanvasTransform` lives in `:engine` and holds **four
floats** — `scale`, `rotationRad`, `txDoc`, `tyDoc` — not a `Matrix`.
`android.graphics.Matrix` is a stub in JVM unit tests, so a Matrix-backed
transform is untestable here; components also make clamping and snapping
expressible instead of extracted from matrix entries. `Matrices.kt` in `:app`
converts to a `Matrix`.

Scale clamped **0.5..8.0**. The floor is 0.5, not 0.35: bilinear filtering is
honest to about 2× minification and 0.35 is past it, which would make a
zoomed-out document shimmer during pan. At 0.5 no mip chain is needed, and
generating one on a full layer would cost ~11 ms — a guaranteed dropped frame —
for nothing Phase 1 gains.

**W8 found that this means fit-to-view does not fit, and W12 decided to keep it
that way.** The honest landscape fit for a 2160×3300 page in a 2200×1330 window
is 0.403, so `fitTo` clamps to 0.5 and about 320 px of the page is off screen.
Three ways out were on the table: lower the floor and accept shimmer while
panning, shrink the default document, or make fit-to-view mean fit-*width* and
scroll. **None of them is taken.** Lowering the floor trades a permanent
image-quality cost against a case the user can resolve in two ways already —
rotate to portrait, where the same page fits at 0.633 with room to spare, or pan,
which W12 is the item that ships. Shrinking the document would size the paper
around one window's aspect ratio, which is backwards. And fit-width is a
different feature wearing fit-to-view's name. So landscape shows a page that is
taller than the window, which is what paper looks like on a screen that shape,
and `fitToView` is always one action away from a known state.

**Export path.** `PngExporter` is a suspend function on `Dispatchers.IO`. The
layer stays **alpha-carrying** — paper white is drawn by the renderer, never
baked in — so it remains a real layer for Phase 3's stack and Phase 4's `.ora`.
~~Export takes `layerLock`, allocates one transient document-sized `Bitmap`,
`drawColor(paperWhite)` then `drawBitmap(layer, 0f, 0f, filterPaint)`, releases
the lock, and compresses off-lock.~~ **Corrected at W14:** the allocation and
the `drawColor` come *out* of the critical section, which holds nothing but one
`drawBitmap` into a bitmap allocated before the lock was taken; the paper goes
under the ink with `DST_OVER` afterwards, which is the same image. `Layer`'s
contract already forbade the allocation ("no second `Bitmap` allocation" while
holding the lock) and `Layer.read` hands out the `Bitmap` rather than a `Canvas`
specifically so this is possible. Measured on the tablet, both shapes in the
same export: 22.0-25.8 ms on-lock as specified against 13.6-14.8 ms as shipped.
MediaStore write lifted from
`SessionExporter` and retargeted at `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`,
`image/png`, `RELATIVE_PATH "Pictures/Artiest/"` — no permission.

**The silent-failure bug is fixed in the port:** `SessionExporter` swallows a
null from `openOutputStream` and then unconditionally flips `IS_PENDING` to 0,
publishing a zero-byte file that the UI reports as success. The port opens the
stream inside a `try`, calls `resolver.delete(uri)` on any failure, and returns
a sealed result the UI actually surfaces.

**Memory, stated honestly.** The transient export bitmap (27.2 MiB at default
size) is roughly one fifth of the real graphics footprint. Unaccounted: the
GPU-side copy of the layer and the surface's own swap chain. W0 settled the
denominator: **7.70 GiB total, 4.4 GiB free**, so a 27.2 MiB document plus a
transient export copy plus a view-sized swap chain is comfortable, and Open
Question 2 can now be answered on a real number rather than an assumption.

## The canvas transform — the hard part, and it is back

`SingleBufferedCanvasRenderer$DrawParamRequest.onExecute` calls
`mRenderNode.beginRecording()`, iterates **only `mPendingParams`** — those added
since the last flush — invokes your callback for each, clears the list,
`endRecording()`, and draws that RenderNode into the **same preserved
`HardwareBuffer`**. Pixels already drawn are never re-recorded and never
re-transformed. The only APIs that touch existing content are `clear()` and
`commit()`, and both discard the whole thing. There is no invalidate, no partial
clear, no re-transform.

This is true **whether or not the device grants a front buffer**. W0 proved this
one does not, and the accumulate-only semantics are unchanged by that: the
buffer is still preserved between renders. So choosing graphics-core means
accepting the constraint in full.

**A transform change mid-stroke renders one stroke at two transforms
simultaneously.** Zoom 1.0×→2.0× with the pen down and the already-drawn half
stays at the old scale and old screen position while every subsequent dab lands
at the new one. Rotate and the old pixels keep their old orientation.
`SurfaceControlCompat.Transaction` cannot rescue it — it has `setScale` and
eight discrete `setBufferTransform` cases, but no `setMatrix`, so arbitrary
rotation is not expressible at the compositor.

**The rule is absolute: the transform is frozen for the lifetime of a wet
stroke.** `InkSurfaceView` snapshots `docToView` at `ACTION_DOWN` and both
callbacks concat that same snapshot; the render thread never reads the live
`CanvasTransform`. No live zoom while drawing. **W2's throughput headroom does
not buy a way out of this** — it is a semantic constraint of the renderer, not a
performance one.

**Predicted ink is unretractable.** A speculative dab stamped at frame N is baked
until pen-up. "Predicted dabs go to the front buffer only, so the commit path
drops them for free" is true of the *layer* and false of the *screen*. That is
the harsher failure mode — a spur that persists rather than one that vanishes
next frame — and it is why prediction ships off by default.

During a gesture there is no wet ink by construction, so only the dry layer
moves: one `renderMultiBufferedLayer(emptyList())` per **`Choreographer` frame
with coalescing**, at most one in flight — not one per touch event, which at
250–320 Hz would be a full commit transaction per event. At pen-up always
`commit()`, never `renderMultiBufferedLayer`: only `commit()` increments the
counter that defers concurrent front-buffer renders until the transaction lands.

**A correction worth keeping.** Front-buffered rendering does *not* "skip the
compositor", as the spike's comments and `docs/analysis.html` both claim.
`CanvasFrontBufferedRenderer` builds and commits a full `SurfaceControl`
transaction on **every** render — and on this device, with no front buffer, it
is an ordinary buffer being scanned out. Whatever the win is, it is not a
scanout shortcut, and W1 showed it is not the `SurfaceControl` plus render
thread plus frame-rate vote either, since reproducing those lost.

### Palm rejection, which does not change

Rejection is by tool type and by stroke exclusivity, not by hover height, because
`AXIS_DISTANCE` is dead. `InputRouter` keeps **strict mutual exclusion**: while a
pen stroke is live, finger pointers are dropped entirely; while a gesture is
live, a pen `ACTION_DOWN` is ignored until all fingers lift.

**A gesture takes two fingers, and single-finger pan therefore does not exist.**
W4 shipped this as a rule inside `StrokeExclusivity`, not as a `GestureController`
preference, and the difference matters. With one finger opening a gesture, a palm
that lands *before* the pen — the ordinary order as a hand is lowered toward the
tablet — put the machine in `GESTURE`, and a live gesture drops every pen contact
for its whole lifetime, including contacts that begin and end inside it. The pen
then drew nothing until the artist lifted the *hand*, a recovery nobody performs
because nobody knows they have to. Fixing it downstream in W12 would have stopped
the canvas moving and left the lockout exactly where it was, since it fires
whether or not anything acts on `GestureBegin`. So: one finger is `DISOWNED` and
emits nothing; the second finger opens the gesture and promotes the first into
it. If a one-finger pan is ever wanted, it cannot be added downstream — this line
has to change, and the pen goes dead under a resting hand again.

The transform argument for this is now weaker — the canvas *can* move under the
pen safely. The palm argument is not: a palm landing mid-stroke would otherwise
chop the line in two and pan the canvas. Keep the exclusivity, for the reason
that survives.

Note the sharp edge — the pen's back reports `TOOL_TYPE_FINGER`, so it lands in
the rejected bucket. That is the correct outcome (it is not an eraser) but it
must be a deliberate decision in the router rather than an accident. W13 made it
one twice over: `toolClassOf` says so in a comment, and
`RejectionCounters.fingerStrokeBegins` says so as a number on the device readout
whose only correct value is zero.

### The clear button — three calls, in this order

Blank the app's layer `Bitmap` under `layerLock`, then `renderer.clear()`, then
`renderer.renderMultiBufferedLayer(emptyList())`.

**The first call is the render thread's, not the UI thread's.** An earlier draft
had the UI thread blanking the layer under `layerLock`, which contradicts the
threading contract stated above: the UI thread never touches those pixels. The
button raises a `pendingClear` flag that `onDrawMultiBufferedLayer` consumes
exactly as it consumes `pendingStroke`, and `Layer.blank()` throws on the main
thread rather than letting that be a matter of discipline. The UI thread's half
of the clear is `Document.forgetStrokes()`. The ordering below is unchanged;
only the thread the first call runs on is.

Each is load-bearing. `renderer.clear()` blanks the multi-buffered layer
**without ever invoking the draw callback** — it records a `BlendMode.CLEAR`
into the library's own RenderNode — so paper white and anything else the
callback paints would simply vanish without the third call. And never
`commit()`: it hands the whole `ParamQueue` back to the callback and resurrects
the stroke into the list just emptied. The spike's `clear()` had exactly that
bug, fixed in `54999d8`.

**Prediction detail carried forward.**
`PredictionGate` (pure, in `:engine`) gates on **curvature, not distance**. The
first draft also clamped distance to half a frame of travel; that clamp is
smaller than the horizon the predictor aims at *by construction*, so it would
discard most of the prediction on every sample and amount to scaling the
prediction down uniformly — the cost of prediction without the benefit. If a
distance cap is wanted, it must be the predictor's actual horizon × velocity,
not a frame fraction.

**Two corrections at W11, both from the same class of mistake the stabilizer
avoids.** The threshold was "the heading change between the last two stabilized
samples exceeds ~25°", and a per-sample angle is a filter whose sensitivity is
the sample rate: a corner is a fixed amount of turning however often you sample
it, so sampling faster splits it across more samples and the gate gets *less*
sensitive exactly when the data gets better. The threshold is now a heading
change per **second** — 140 rad/s, which is the same 25° restated at the
measured 321.75 Hz and 32.5° at 246.85 Hz. And the verdict now **holds for
30 ms** rather than for the one sample that saw the corner, because samples
arrive batched and prediction is asked for once per `MotionEvent`: measured on
the tablet, a twelve-legged zigzag tripped a one-sample gate on 3 reversals out
of 11 and predicted straight through the other 8, because the heading had
straightened out again before the end of the batch. With the hold it suppresses
19 events of 225 on the zigzag and **0 of 225 on the spiral**, which is the
discrimination the gate is for.

The predicted tail is smoothed through a **copy** of the `Stabilizer` state
(three floats, discarded after) so speculative points never mutate the filter
that the next real sample runs through. Prediction ships behind a runtime
toggle, defaulting **off** on the measured evidence, and **shipping it off is an
acceptable outcome**: a stroke that occasionally grows a hair is worse under the
pen than a stroke that is 5 ms slower.

All of the above is inferred from bytecode. **W3 confirms the tear on hardware in
half a day** before any of it is built on.

## Work plan

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| 0 | ~~Front-buffer reality probe~~ **DONE — `54999d8`. Verdict: no front buffer. Stop condition fired.** | `:spike` | — | — | ✔ |
| 1 | ~~`DirectSurface` arm, A/B'd by eye~~ **DONE — `24701ab`. Graphics-core still closest to the tip; both vsync-locked arms smoother but behind.** | `:spike` | — | — | ✔ |
| 2 | ~~Full-redraw throughput probe~~ **DONE. draw p99 4.0 ms against an 11.1 ms budget, 0% dropped over 600 frames. Full redraw is viable.** | `:spike` | — | — | ✔ |
| 3 | ~~Timeboxed androidx.ink 1.1.0-alpha07 arm, hard stop at one day~~ **CUT, by the cut order below and on purpose. It bought Phase 2's kill criterion, not Phase 1's ink. The open question it left — whether textured brushes have landed in `BrushFamily` — was answered at the top of Phase 2 by the ten-minute check rather than the day-long arm: **they have**, and the recommendation held anyway. See `docs/phase2-plan.md`. `:spike` is frozen regardless.** | `:spike` | — | — | ✂ |
| 4 | ~~`PenSample`, `MotionEvents.collectSamples`, `InputRouter`, `TraceRecorder`/`TracePlayer`~~ **DONE — `f7ffa18`. A palm landing before the pen locked it out; a gesture now takes two fingers.** | both | Low | — | ✔ |
| 5 | ~~`CanvasTransform` + native JVM tests~~ **DONE — `06bd9cf`, `6aada12`. Order measured against Skia, not derived; `Matrices.kt` landed in `:app` with it.** | `:engine` | Low | — | ✔ |
| 6 | ~~`Document`, `Layer`, `Stroke`, `Bounds`~~ **DONE — `aa38787`. The row was never ticked at the time and the tick is backdated here: `Layer` could not live in `:engine` as the table says, because a `Bitmap` does not resolve in a `kotlin("jvm")` module. The module tree won that argument and the plan's column was wrong.** | both | — | — | ✔ |
| 7 | ~~`Stabilizer`, `RoundPen`, `CatmullRomResampler`, `StrokeBuilder` + dab-list goldens~~ **DONE — `77feb1e`. Stabilizer integrates over dt; onset ramp moved to wall-clock; centripetal measured against a uniform control.** | `:engine` | — | — | ✔ |
| 8 | ~~`InkSurface` + `InkSurfaceView` front-buffered wiring, own `SurfaceHolder.Callback`, `DabBatchPool`~~ **DONE — `87a8d3c`. Ink on the tablet. The app's own `SurfaceHolder.Callback` proved by negative control; the batch ring got a real completion signal; the front buffer is clipped to the paper.** | `:app` | — | — | ✔ |
| 9 | ~~Wet ink end to end, allocation trace, batch-pool slot validation~~ **DONE — `49aa8c6`. Budget met on release: p50 0.119 ms an event and 54.6 B a sample. The ring drops 24 slots to 8. Two measurement traps found, both bigger than the thing being measured.** | `:app` | — | — | ✔ |
| 10 | ~~Commit: stroke becomes dry ink at pen-up, under `layerLock`~~ **DONE — `01e9477`. The single-slot handoff became a queue: it dropped strokes under back-to-back commits and could not order a Clear.** | `:app` | — | — | ✔ |
| 11 | ~~`Predictor` — `Source.PREDICTED`, curvature gate, forked stabilizer state, runtime toggle~~ **DONE — `15ce55d`. Ships off, and now there are numbers for why: 3x the per-event cost, 11x the allocation, and as many speculative dabs as real ones.** | both | — | — | ✔ |
| 12 | ~~`GestureController` — pan/zoom/rotate, Choreographer-coalesced dry redraw, **frozen-transform handshake**~~ **DONE — `71208ed`. 362 pointer updates coalesced to 90 renders with none skipped; the freeze rule's real reason turned out to be narrower and sharper than stated.** | `:app` | — | — | ✔ |
| 13 | ~~Cancellation and palm rejection (`ACTION_CANCEL`, `FLAG_CANCELED`, fingers and pen-back never draw)~~ **DONE — `7b6698f`. Eight contact sequences run on the tablet, 8/8. The rules were already right; a cancel released no batches, and the counters now say which of six ways a stroke died.** | `:app` | — | — | ✔ |
| 14 | ~~`PngExporter`~~ **DONE — `f54f785`. 24-bit PNGs on the tablet in 450 ms. The plan's critical section held the lock for 22-26 ms; the shipped one holds it for 14, and the skew between a stroke's history and its pixels turned out to be visible to the user after all.** | `:app` | — | — | ✔ |
| 15 | ~~`MainActivity`, Compose chrome, refresh-rate toggle, `DeviceProbe` port~~ **DONE — `e436c22`. A 60 Hz control W16 can reach from a button, and the vendor cap turned out to be one-directional. Two ported lines were wrong; the double tap shipped doing nothing and the JVM tests could not have caught it.** | `:app` | — | — | ✔ |
| 16 | ~~Feel pass on device; **film at 240 fps and record the Phase 1 latency baseline**~~ **DONE — `77676e7`, `15fab21`. 45 ms pen to photons at 90 Hz: 9.3 ms app, 36 ms compositor and panel. Prediction judged under a real pen and stays off — the wet ink is visibly jagged until pen-up. Verdict on the feel: fast enough.** | device | — | — | ✔ |
| 17 | ~~Reconcile `docs/analysis.html` with what was measured~~ **DONE — `46c2d5a`. Three load-bearing claims refuted (no front buffer, no eraser end, `AXIS_DISTANCE` dead), one API that cannot do what it says (`preferredDisplayModeId`), prediction rejected, and the thesis' number finally filled in at 45 ms. Corrections annotated in place beside the predictions rather than replacing them.** | docs | — | — | ✔ |

~~**≈12.75 days remaining.**~~ **Phase 1 is complete.** Sixteen of the seventeen
items shipped; W3 was cut, which is the first entry in the cut order below and
the only one that had to be used. W12's fallback ladder was not needed and W16
was not cut.

The estimate said 10–15 days and the plan sat at the top of it. What actually
happened is not a schedule number worth quoting — the work was done in sessions
rather than days — but the shape is worth recording: **the items that ran long
were the ones where the plan was wrong about the hardware, not the ones that
were technically hard.** W0 fired a stop condition on day one and cost W1
entirely. W10, W13 and W14 each turned up a defect in the design rather than in
the code implementing it. W15's double tap shipped doing nothing. Nothing
overran because the code was difficult.

The original cut order, for the record: W3 (the Ink arm — it buys Phase 2's kill
criterion, not Phase 1's ink), then W12's fallback ladder, then W17 sliding into
Phase 2's first commit. Only the first was spent. Do **not** cut W16 — and it was
not; it produced the phase's headline number.

**W0 — DONE, `54999d8`.** The probe returned `false` for the library's exact
usage set (4294970112) *and* for the bare `USAGE_FRONT_BUFFER` bit, so the stop
condition fired and W1 built the replacement. W1 then judged that replacement to
be worse, and the decision was to ship graphics-core anyway — running in a
fallback path, on a device with no front buffer, because it is what feels best
under the pen. W0's value was not the pivot it triggered but the fact that the
choice is now made knowingly.

Two lessons from it worth keeping, because both nearly went the other way:

- **Probe the question the library actually asks.** The first draft specified
  `USAGE_FRONT_BUFFER or USAGE_GPU_COLOR_OUTPUT` (4294967808). graphics-core
  asks 4294970112 — `BaseFlags` (COMPOSER_OVERLAY | GPU_COLOR_OUTPUT |
  GPU_SAMPLED_IMAGE = 2816) or the front-buffer bit — verified by javap on
  `UsageFlagsVerificationHelper`. Both return false here, so the answer survived
  the error, but only by luck.
- **Run a discrimination control.** "isSupported returns false" and "isSupported
  returns false for *everything* on this gralloc" produce identical observations
  and opposite conclusions. 2816, 2864, 512 and 256 all return true and a bogus
  bit returns false, so the refusal is specific. Any future probe of this shape
  needs the same control.

Also corrected: graphics-core does **not** blindly allocate and silently
degrade, as the first draft claimed. `obtainUsageFlagsV33()` is literally
`isSupported(4294967296L) ? 4294970112L : 2864L` — it probes first and lowers
its own request, with no log line. Same effect, different diagnosis.

**W1 — DONE, `24701ab`. Graphics-core wins; the reimplementation lost.**
`DirectSurfaceInkView` was built as a third arm reproducing what the library
appeared to be doing — dedicated `SurfaceControl`, own render thread, the same
unconditional 1000 fps frame-rate vote — and judged against it and the plain
`View` baseline at a verified 90 Hz.

Verdict, in the user's words: *"graphics core is the closest to the tip.
baseline and directsurface are 'animated' a bit smoother it seems. but graphics
core is definitely the closest following the tip of the pen."*

The two losing arms are exactly the two that wait for vsync before drawing, which
makes coalescing the leading suspect. **That was not tested** — building the
immediate-scheduling variant was started and then abandoned once the decision was
made to ship the library. So Phase 1 ships a path whose advantage is real,
reproducible on demand, and unexplained. Phase 2 will have to explain it.

Getting a trustworthy judgement needed four fixes to the harness first, none of
which the arm itself would have revealed: a half-pixel translate that ran every
dry pixel through the bilinear filter and made one arm look softer; ink silently
discarded in a 20 px band at 1:1 where the document does not reach the view
edges; `Predict` defaulting on while the three arms implement prediction
incompatibly; and no way to see that the 90 Hz override had lapsed mid-run. **An
A/B harness needs its own review pass** — three of those four would have produced
a confident wrong answer rather than an obvious failure.

**W2 — DONE. The full-redraw model is viable with room to spare.** Measured on
the DirectSurface arm at a verified 90 Hz, rotating the layer through a live
matrix every frame so the blit is a genuine resample rather than an
axis-aligned fast path, 600 frames:

```
draw p50 2.97   p95 3.59   p99 4.00 ms
gap  p50 11.18  p95 12.00  p99 12.56 ms
dropped 0.0%    n=600      (vsync 89.4 Hz)
```

Draw cost sits at **2.8x headroom** against the 11.1 ms budget, the gap is
pinned to vsync, and not one frame in 600 was dropped. Nothing needs clipping to
a dirty region, and W8's frame body can be written as specified. Note the draw
figure is measured across `lockHardwareCanvas` to `unlockCanvasAndPost`, so it
includes the buffer swap and any back-pressure — it is the honest cost of a
frame, not just the recording.

**A measurement lesson that outlived the item.** The first run reported "100%
over 11.1 ms" and looked like a catastrophic failure. It was not: the refresh
override had lapsed mid-run and a 60 Hz panel was being judged against a 90 Hz
budget, with gap p50 at 16.75 ms — exactly 1/60. The miss rate is now computed
against the run's own median gap rather than a hardcoded budget, so a loop
pinned to vsync reads 0% at any refresh rate and only real hitches show. A
fixed threshold in a probe measures the environment as much as the code.

**W8 — DONE. Ink on the tablet, and four things the plan had wrong.**

The build was checked against the device throughout, driven by `adb shell input
stylus swipe` and read back off screenshots, which is a poor substitute for a
pen in a hand but is repeatable and can be measured to the pixel. What it found:

- **The `toDoc` step was missing, and it does not fail loudly.** The pipeline
  order above names it; the wiring skipped it and fed `PenSample`'s *view*
  coordinates straight to `StrokeBuilder`. The result is not a crash and not
  obviously wrong ink — it is a stroke scaled by the zoom and offset by the pan,
  which at 1:1 with no pan is invisible. Here it drew at 0.44x, 160 px from the
  pen. `StrokeBuilder` gained a by-parts `add(xDoc, yDoc, pressure, timeNanos)`
  so the conversion can happen in place: a second `PenSample` per digitizer
  sample to carry document coordinates is not in the budget, and the two
  overloads are pinned bit-for-bit against each other.
- **The app's own `SurfaceHolder.Callback` is load-bearing, and now measured
  rather than argued.** Removed it, backgrounded the app, resumed: **zero** ink
  pixels. Put it back: 23,633. The library's own `surfaceChanged` was confirmed
  by javap to call nothing but `update()`, and this is what that costs. Its
  registration order matters too — it must be added *after* the renderer is
  constructed, since `SurfaceHolder` runs callbacks in registration order and
  the redraw has to land on rebuilt buffers.
- **The front buffer needed clipping to the document.** Dry ink is clipped for
  free by the layer bitmap; the front buffer is the whole view, and fitted to
  this tablet's landscape window the page leaves a **measured 560 px margin on
  each side**. Wet ink in a margin therefore appeared under the pen and vanished
  at pen-up — which reads as a dropped stroke, not as drawing off the page. One
  `clipRect` inside the existing save/concat, verified by comparing a
  mid-stroke screenshot against the committed one: both now start at x = 560.
- **Fit-to-view belongs to the surface, not to `displayMetrics`.** The two
  differ by the system bars always and by the whole aspect ratio after a
  rotation, and fitting once at construction left a 2160x3300 page drawn at the
  landscape scale in the corner of a portrait window. It now refits in
  `surfaceChanged` while `fitOnResize` is true, which W12 turns off the first
  time the user moves the canvas themselves.

Confirmed working: a drag produces wet ink that survives commit; rotation both
ways preserves the drawing and returns it to exactly the same pixels; a
background/resume cycle preserves it; no crash on any of them.

**And one finding for W12: fit-to-view does not fit on this panel.** The
measured fit scale in landscape is exactly 0.5 — the `MIN_SCALE` floor — because
the honest fit is 0.40 and the floor exists for a real reason (bilinear
filtering is good to about 2x minification). At 0.5 the page is 1650 px tall in
a window about 1330 px tall, so roughly 320 px of it is off screen and
"fit-to-view" crops. Three ways out and none is free: lower `MIN_SCALE` and
accept shimmer while panning, make the default document smaller, or make
fit-to-view mean fit-*width* and scroll. **Decide it in W12, do not let it be
decided by whichever number someone edits first.**

What to check first if it breaks later: **a blank canvas** (look for an
accidental `setBackgroundColor` on the `SurfaceView` — it is the known trap, it
cost a session once already, and the renderer will look perfectly healthy in
logcat while painting invisible frames); a crash on rotation or backgrounding
(the render thread outlived the `Surface` — `lockHardwareCanvas` on a destroyed
one throws); or a stroke that flickers between frames (the wet dab list is being
consumed rather than read, so a frame arriving between samples draws nothing).

**W9 — DONE. Both budgets hold, and the harness was the hard part.**

Measured with `StrokeStress`, which synthesizes `MotionEvent`s — `obtain` for a
frame's first sample, `addBatch` for the rest — and dispatches them through
`onTouchEvent`, so the router, `collectSamples`, the stabilizer, the resampler
and the pool all run for real. The stroke is a spiral with pressure sweeping its
whole range, which is not a typical stroke and is not meant to be: `RoundPen`
spaces dabs at a fraction of the *diameter* and clamps at `MIN_SPACING_DOC`, so
a feather-light dab sits 0.5 doc px from its neighbour where a full-press one
sits 3.0, and the same movement emits six times the dabs. The sweep is therefore
the worst case by construction — **77.2 dabs per event** against a firm press's
19.5.

**The results, release build, on a big core:**

```
event    p50 0.119   p95 0.170   p99 0.185   max 0.295 ms   over budget 0.0%
submit   p50 0.034   p95 0.045   p99 0.054 ms  (inside renderFrontBufferedLayer)
sample   p50 22.4    p99 34.8 us   of a 3108 us interval
alloc    54.6 B/sample   drag 65536 B over 1201   commit 212992 B
batches  8 slots   peak 3   spills 0        77.2 dabs/event, 5.31 samples/event
```

The per-event budget holds with the worst-case stroke and 16% to spare at max.
The per-sample allocation lands on the predicted ~56 B, so the pipeline really
does allocate one `PenSample` and nothing else. The commit's 212,992 B for
17,439 dabs is 12.2 B a dab — the flat float triple plus the `Stroke` and
`Bounds` headers — which is the one allocation W6 designed in on purpose.

**Two measurement traps, both larger than the effect being measured. Anyone
re-running this has to know about them or they will measure the wrong thing.**

- **The debug build is ~3.5x slower than release.** The same firm stroke reads
  p50 0.408 ms debug and 0.117 ms release. `:app`'s build file already says the
  release variant is kept unminified and debug-signed *so latency stays
  measurable*, and W9 measured on debug first anyway and spent a while
  explaining a budget miss that does not exist on the build that ships.
- **Core placement is worth 4-6x, and synthetic input loses it.** The MT8781 is
  two Cortex-A76 at 2.2 GHz (cpu6, cpu7) and six A55 at 2.0 GHz. Sampling
  `/proc/<pid>/task/<tid>/stat` during a run: real injected stylus input holds
  the UI thread on **cpu7 for the entire stroke**, because the framework boosts
  on input; a `Choreographer`-driven synthetic run drifts onto **cpu0-2** once
  the boost from the launching tap decays, and the same code then reads p50 0.52
  to 0.71 ms instead of 0.119. Every CPU was at its maximum frequency
  throughout, so this is not throttling and a frequency check will not find it.
  The big-core figures are the ones a hand on glass sees.

**`renderFrontBufferedLayer` is not the cost**: 0.034 ms of a 0.119 ms event,
29%, submitting 1.2 batches. That also settles a question W8 left open —
submitting **one batch per event** rather than one per sample is right. It is
not vsync coalescing, nothing waits, and the alternative is five times the
submissions for the same ink.

**A per-event budget is rate-dependent, and that is worth saying out loud.** The
panel ran at 60 Hz for these runs, so an event carried 5.31 samples; at 90 Hz it
would carry 3.57 and cost proportionally less. So "0.31 ms per event" is a
harder target the *slower* the panel — the same shape of mistake the stabilizer
avoids by integrating over dt. The rate-independent figure is the per-sample one,
**22.4 us of a 3108 us interval**, and that is the number to carry forward.

**The GC guard fired on the device**, which is the only evidence that it is not
dead code: heap-used is a level, not a counter, so a collection inside the
measured window subtracts freed bytes from allocated ones and the path reads
*cheaper* than it is. One run reported `invalidated by 1 GC` and refused to print
a figure. Also worth knowing: the first stroke after a process start allocates
285-747 B a sample rather than 54.6, entirely `StrokeBuilder`'s dab array growing
from 256 dabs to whatever the stroke needed. That is one array per session
instead of one per stroke, which is what it was designed to be, but it means a
single-stroke allocation measurement measures the growth and not the path.

**W10 — DONE. The commit path, and the handoff it rested on was wrong.**

W8 wrote the multi-buffered callback body as specified, because a callback
missing half its body is not a seam. What W10 owed was the bookkeeping, and
looking at it properly turned up two defects in the design above rather than in
the code implementing it — both invisible except under timing.

- **A one-slot handoff loses strokes.** See the threading contract above. The
  fix is a FIFO; the test builds the old version and watches it lose one.
- **The history and the pixels could disagree permanently.** `recordStroke` ran
  on the UI thread at pen-up while the pixels went through the slot, and
  `commitStroke` *dropped* the stroke when there was no surface — so a stroke
  finished as the app went to the background was counted by the `Document` and
  never drawn. `Document.commitStroke` now does both halves in one call and
  there is no arrangement of threads in which one happens and the other does
  not. Measured on the tablet: a pen-up followed immediately by HOME, then
  resume, comes back with the ink.

The queue lives on the `Document` and not on the view, which is the same
reasoning that put the layer there: the document outlives every surface, so a
stroke in flight when the surface goes away is stamped by the first render
against the next one. Rotation never exercises this — the manifest handles
`orientation|screenSize`, so the view stays attached and only the surface is
rebuilt — and backgrounding does.

The history leads the pixels by at most one frame and nothing reads both:
`PngExporter` reads the layer, undo reads the bounds, the readout is a readout.

**W11 — DONE. Prediction works, and the numbers say keep it off.**

Same stroke, same process, same release build, back to back — 1201 samples,
4397 dabs, 19.5 dabs an event:

```
                   prediction off      prediction on
event p50            0.126 ms            0.369 ms
event p99            0.194               1.181
event max            0.283               1.820
over the budget      0.0%                98.7%
per sample           23.5 us             69.1 us
allocation           32,768 B/stroke     360,448 B/stroke
speculative dabs     0                   4,452
```

**Three times the per-event cost, eleven times the allocation, and as many
speculative dabs as real ones.** The allocation is the unrecycled `MotionEvent`
that `predict()` hands back, once per event; recycling it is off the table for
the reason `:spike` gives — ownership has varied across library versions and a
double recycle crashes where a missed one churns. The dab count is the part
worth staring at: the front buffer carries roughly double the ink, and half of
it is a guess that cannot be taken back.

The lead is real and it is large. On the stress stroke the predictor puts the
pen a mean of 41.5 and a maximum of 110 doc px ahead of the last real sample;
on a faster injected stroke, a mean of 91. At the fitted scale that is 20 to
55 view px of speculative ink hanging off the tip. When the gate is right that
reads as the line leading the pen, which is what prediction is *for*. When it is
wrong it is a spur, and it stays until pen-up.

So the toggle exists, it works, and it defaults **off** — which the plan already
called an acceptable outcome and which now has arithmetic behind it rather than
one A/B by eye. **W16 judged it under a real pen and the verdict is the harsher
one: with prediction on, the wet ink carries visibly jagged sections that stay
until the pen lifts.** That is this arm's predicted failure mode observed rather
than reasoned about — the spurs are speculation the pen never reached, they
cannot be taken back because front-buffer ink is unretractable, and they vanish
at pen-up because the committed stroke is built from real samples alone. The
layer was always clean; it is the screen that was not, and a user drawing does
not care which.

**The blocker is unretractable wet ink, not prediction.** Nothing measured about
`MotionPredictor` was wrong — the lead is real and large. What makes it unusable
here is that this render path can only add pixels. A Phase 2 engine that
re-renders the wet stroke each frame turns every spur into something erasable,
and at that point prediction is worth re-testing against the 5.75 ms of
digitizer-and-dispatch lag it exists to hide. Until then it stays off, and the
reason is recorded here so nobody re-derives it from the lead figures alone.

Three things worth keeping from building it:

- **The tail is a straight line, and that follows from the gate.** A spline
  through a predicted point needs a *second* predicted point to anchor it —
  speculation on speculation — and would mean forking the resampler's four knots
  and spacing debt every frame. It is unnecessary because `PredictionGate` has
  already established the pen is not turning: a straight tail is wrong exactly
  in proportion to the curvature that was suppressed.
- **The fork is the stabilizer's, and only the stabilizer's.** Predicted points
  go through a copy, so the filter the next real sample runs through is never
  moved by a guess. `StrokeBuilderTest` pins that the real ink lands bit-for-bit
  where it would have if the fork had never existed.
- **Predicted dabs never reach `StrokeBuilder`.** They go into a `DabBatch`
  through the same `DabEmitter` seam the resampler uses, so they share
  `RoundPen`'s sizing and spacing without sharing a destination, and the
  committed `Stroke` is built from real samples alone. The layer is clean; the
  screen is not, and that asymmetry is the whole risk.

**W12 — DONE. The canvas moves, and the contingency ladder was not needed.**

`GestureSolver` lives in `:engine/xform` and holds all the arithmetic, so the
part that can be wrong is driven by tests rather than by a hand;
`GestureController` in `:app` holds the `Choreographer` and the redraw policy,
which is the part tests cannot reach. Measured on the tablet with a synthesized
two-finger pinch — `adb shell input` is single-touch, so a gesture is the one
thing in Phase 1 that cannot be driven from a shell, and `GestureStress` builds
the real DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP sequence and dispatches it
through `onTouchEvent`:

```
362 pointer updates -> 90 renders, 0 skipped
scale 0.5 -> 1.0 (the span exactly doubled)
rotation 0 -> 0.576 rad (a 0.698 rad twist, less the 0.122 rad dead zone)
```

**The coalescing is the measurement.** Four pointer updates a frame — which is
what `requestUnbufferedDispatch` would deliver, and the case the plan named —
produce one render a frame and no extra transactions, and the transform lands on
exactly the same value it would have from one update a frame. That last part is
the anchored solver rather than the coalescing: because each update is computed
from the anchor rather than accumulated onto the last result, intermediate
updates are not information and dropping them costs nothing. No stutter, so
contingency (b) — blitting a downscaled layer during the drag — is not needed
and stays unbuilt.

**Two things the item changed about its own specification.**

The freeze rule's reason is narrower and sharper than the plan stated. A
transform change mid-stroke does *not* corrupt the stroke: the render matrix is
snapshotted at pen-down and so is the mapping incoming samples go through, so
every dab lands consistently whatever the live transform does. What breaks is
the relationship between the two **layers** — the front buffer drawing wet ink
through the frozen matrix while the multi-buffered layer blits the committed ink
through the live one, which puts the drawing and the stroke being drawn on it at
two different scales at once. `requestTransform` is where the rule is enforced,
and it holds the request until pen-up rather than refusing it.

And the path that reaches it is not rotation. Rotation destroys the surface
first, `surfaceDestroyed` abandons the open stroke, and the refit then arrives
with no stroke to defer around — measured, by rotating the tablet mid-stroke
and finding the stroke cancelled and the transform refitted cleanly. What the
guard is actually for is a `surfaceChanged` **without** a destroy: a window
resize with the pen still on the glass. `transformDeferrals` counts it, so the
branch is instrumented rather than merely present.

**A dead zone on rotation, and it is a trade rather than a free win.** A pinch
is never a pure pinch — hands are hinged, and a relaxed two-finger spread twists
three to five degrees — so without one, every zoom tilts the canvas a little and
getting back to level is fiddly. 7 degrees swallows that. The cost is exact:
inside the dead zone the canvas does not follow the fingers exactly, because two
points define a similarity and one of its degrees of freedom is being held at
zero. Past it the tracking is exact again, offset by the threshold, so rotation
starts continuously instead of jumping 7 degrees.

Note what contingency (b) is *not*, in case it is ever wanted: applying the delta
via `SurfaceView` view properties (`setScaleX`/`setRotation`/`setTranslation`).
A `SurfaceView`'s View-level transform moves the punched hole in the window, not
the surface content.

**Double-tap-to-reset is not shipped; `fitToView` is.** The plan asked for both,
so the reason matters: a single-finger tap never reaches the app, because
`StrokeExclusivity` takes two fingers to open a gesture and drops lone fingers
entirely — that rule is W4's, it is what keeps a palm landing before the pen from
locking the pen out, and it is not worth reopening for a shortcut. A two-finger
double tap would work within the rules and is W15's to add beside the rest of the
chrome. The safety property the plan actually wanted — a lost canvas is always
recoverable — is met by `fitToView`, which restores the fit and re-arms
`fitOnResize` in one call. **Shipped at W15** as `TwoFingerDoubleTap`, fed from
the gesture decisions rather than from `onTouchEvent`, so "two fingers, and not
the pen" needs no restating.

**W13 — DONE. The rules were already right; the bookkeeping around a cancel was
not, and three claims the plan could not check now have counters.**

Almost nothing in this item was a new rule. W4 put every one of them in
`StrokeExclusivity` and proved them on the JVM, and W13's job was the half that
module cannot reach: whether the `MotionEvent` side agrees, and whether a
rejected contact stays rejected all the way to the pixels. `RejectionStress`
answers that by replaying eight contact sequences through the real view and then
asking the *document* what happened:

```
8/8 pass    peak in flight 1   spills 0
contacts 12 (6 pen)   dropped 6 (1 pen)   finger-begins 0   flagged 1
strokes  5 begun   3 ended   2 cancelled -> flag 1  cancel_action 1
```

The first case draws, and that is structural rather than incidental: seven of
the eight assert that something did *not* happen, and a harness whose synthetic
pen cannot draw at all passes every one of them. Same geometry, same event
shapes, one committed stroke — then the seven.

Two of the eight are worth naming. **The palm mid-stroke case is checked
geometrically**, not by counting strokes: the palm arrives after the pen and
lifts before it, so the pen slides from pointer index 0 to 1 and back, and the
assertion is that the committed stroke's bounds come nowhere near where the palm
was resting. A consumer holding the index it was handed at DOWN passes a stroke
count and fails this. **The pen-during-gesture case asserts the fingers panned**,
because "the pen drew nothing" is also true of a run where the gesture was
dropped as well, and that would be a different bug wearing this one's result.

Confirmed on the real input dispatcher afterwards, not only through synthesized
events: an injected finger swipe across the canvas moves nothing and draws
nothing (`contacts` and `dropped` both +1, transform unchanged), and an injected
stylus swipe over the same path commits a stroke.

**The defect it found is in the pool, and it is smaller than it first looks.**
`CanvasFrontBufferedRenderer.cancel()` invokes **neither** draw callback —
bytecode: `ParamQueue.clear`, `cancelPending`, a runnable that hides the
front-buffer SurfaceControl, a buffer clear, and no dispatch — while
`commitWatermark` is only ever applied inside `onDrawMultiBufferedLayer`. So a
cancel set a watermark that nothing read. `markDrawn` is a watermark, though, so
the next batch that *is* drawn releases the abandoned ones with it: the leak is
one cancel deep and self-heals. What it costs first is that the stroke after a
cancel starts with a ring short by that many slots and can spill through no
fault of its own, and that `peakInFlight` reads high from the cancel onwards —
which is the number W9 sized `DEFAULT_SLOTS` with. The fix is one
`renderMultiBufferedLayer` after the cancel, which is the same handoff the
commit path uses, on the same thread, in order; with no surface there is no
render thread to hand to, so `DabBatchPool.releaseAll` does it directly.

**And it could not be reproduced on the device, which is the honest result.**
The unfixed build was reinstalled and driven through the harness, and through a
stroke interrupted by HOME — the sequence the fix's comment describes — and both
reported `in flight 0`. The render thread never ran more than one batch behind
at any rate this device produces (peak in flight 1 on both attempts; W9's
punishing case peaked at 3, against 8 slots). So the leak is real in the
bookkeeping and latent in practice, the unit test is what demonstrates its
consequence, and the fix is kept for making `inFlight` exact rather than for
fixing an observed fault. Claiming otherwise would be claiming a measurement
that was tried and did not come.

**Three questions the plan reasoned about and could not answer now have
counters, always on.** Whether this digitizer's driver ever sets `FLAG_CANCELED`
— Phase 0 counted the flag but exported no session with a palm on the glass.
Whether the framework ever sends this app an `ACTION_CANCEL`. Whether a pointer
is ever lost mid-stroke. `RejectionCounters` costs nine longs and a
`LongArray` incremented once per event, and `CancelCause` partitions every path
in `StrokeExclusivity` that can discard a stroke into six — flag, cancel action,
lost pointer, stale DOWN, mismatched lift, lifecycle abandon — because "a stroke
was cancelled" is not a diagnosis and those six have completely different fixes.
The synthesized flag arrives as `flagged 1` and `flag 1`; **a real one has still
never been seen**, and the honest reading of the zero is "not yet", not "never".
`POINTER_LOST` is deliberately excluded from the flag count: `:app` synthesizes
it with the flag set, and counting it would answer the open question with a
signal this app wrote itself.

The readout prints the router's cancel count and the view's side by side. They
are the same number unless a cancel stopped somewhere between the decision and
the ink, which is exactly the failure that would otherwise be silent.

**W14 — DONE. The export works; the plan's critical section was twice as long
as it needed to be, and there was a second silent failure under the first.**

The MediaStore half was never a risk — Phase 0 proved it on this tablet — so
what W14 owed was everything around it. Measured on the device, release build,
2160x3300: a blank document is 27,533 B, a two-stroke one 54,705 B, `copy 14-17
ms`, `encode 383-386 ms`, `total 441-467 ms`, and the pulled file is a 2160x3300
**colour type 2** PNG, which is to say 24-bit with no alpha channel at all.

**The critical section is a third of the plan's, and that is a correction and
not a tightening.** The plan holds `layerLock` across a 27.2 MiB allocation, a
full-canvas `drawColor` and the composite. `Layer`'s contract — written two
items later — already forbids the first of those outright, and `Layer.read`
hands out the `Bitmap` rather than a `Canvas` precisely so the section can be
one blit. Both versions were built and timed on the tablet, back to back inside
the same export so neither inherited the other's warm allocator:

```
                  on-lock        of which
plan's shape   22.0-25.8 ms   alloc 0.07  drawColor 12.9-13.8  blit 9.1-9.9
shipped        13.6-14.8 ms   one drawBitmap
```

Effectively all of the difference is a `drawColor` over 7.1 Mpx that does not
need the lock. The paper goes *under* the ink afterwards with `DST_OVER`, which
is the same image — `dst + src*(1-dstA)` with an opaque src is what painting ink
over paper computes — and rather than leave that as a comment, the test asserts
the exported PNG equals the paper-first composite pixel for pixel. 14 ms is
still most of a frame, so the honest cost of pressing Save is one dropped frame
at the next pen-up rather than two.

**The second silent failure, which the plan did not have a name for.**
`commitStroke` records the stroke's bounds on the UI thread and *queues* its
pixels; W10 settled that skew as harmless because "nothing reads both halves —
`PngExporter` reads the layer, undo reads the bounds". That is true of the code
and false of the user, who lifts the pen and presses Save. Copy the layer in
that window and the PNG is missing the last stroke, the document's own count
says it is there, and nothing reports anything — the same shape as the zero-byte
file, one layer down. So the export waits for the queue, bounded at 250 ms, and
reports what is still outstanding as `notYetStamped`.

**How much of that is reachable today, said plainly.** Every path that queues a
commit already asks for a render — a pen-up calls `commit()`, Clear calls
`redrawDry` — so with a live surface the queue drains within a frame. On the
tablet the wait was **0 ms on every export**, including one fired as close
behind a Clear as two `input tap`s can be, and the file was the blank-paper
27,533 B rather than the one with ink in it. The state that reliably leaves
commits queued is having no surface, which is also the state in which the button
cannot be pressed. So this is a guarantee and a report, not a save anyone has
watched happen; `PngExporterTest` is where the case is real, and W15 binds export
to more than a button.

**`setHasAlpha(false)` was measured because the obvious guess is wrong in both
directions.** On an ink-covered document the file goes from 547,826 B to
468,416 B — a seventh — and on a blank one it saves two bytes, because a
constant channel is exactly what PNG's row filters already reduce to nothing.
It is conditional on an opaque paper colour: a translucent one would make the
encoder write premultiplied colours as if they were straight, and the test
carries that as the other side of the branch.

**The `:spike` bug is fixed, and the fix is tested against the bug rather than
around it.** `SessionExporter` swallows a null from `openOutputStream` and then
flips `IS_PENDING` to 0 unconditionally, publishing an empty file and returning
a perfectly good `Uri` for it. `PngExporterTest` builds that version longhand,
watches it publish and clean up nothing, and then asserts the port's behaviour
against the same failure: `Failed(OPEN)`, the row deleted, and **no** update
statement at all.

Reaching that branch needed a seam, and it is worth saying why rather than
hiding it. Robolectric's `ContentResolver` catches `FileNotFoundException` and
`SecurityException` inside `openOutputStream` and returns a no-op stream
instead (bytecode, `ShadowContentResolver$1`), so under test it never returns
null and never throws — the branch the class exists for is unreachable. So
`export` takes the opener as a parameter whose default is
`ContentResolver::openOutputStream`. Nothing in the app passes it. A branch that
cannot be reached in a test is a branch nobody has run.

The other three failure stages are reached with a real `ContentProvider`
registered for the `media` authority, which is the instrument that fits there:
refusing the insert gives `Failed(INSERT)`, refusing the update gives
`Failed(PUBLISH)` **and one delete**, because an invisible pending row is worse
than no row — it holds the name, never appears in the gallery, and nothing ever
cleans it up. A closed document fails at the pixels *before* the row is created,
which the test pins by asserting MediaStore was never touched.

**W15 — DONE. The chrome, and the refresh toggle turned out to buy more than
the plan expected. Two ported lines were wrong and one shipped feature did
nothing at all.**

The item is mostly assembly, and the parts that were not are the ones worth
writing down.

**The refresh toggle works, and the vendor cap is one-directional.** Measured on
the tablet, with `mAppRequestedModeByDisplay` and `mActiveSfDisplayMode` read
back out of `dumpsys display` for every arm:

```
req highest  -> app asks id=2 90.0   active 90.0   readout 90.0 Hz
req sixty    -> app asks id=1 60.0   active 60.0   readout 60.0 Hz
req auto     -> no app request       active 90.0   readout 90.0 Hz
```

The 60 Hz arm works **with the adb override still on**: the app's
`PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE` vote of 60 beats the user
setting's `MIN_RENDER_FRAME_RATE` vote of 90. Going *up* is what the vendor cap
refuses — `mAlwaysRespectAppRequest: false` with `mDefaultPeakRefreshRate: 61`
governs whether an app may exceed the user's peak, not whether it may go below
their minimum. So W16 can A/B 60 against 90 by tapping a button, without
touching device settings between runs, which is a much better control than the
plan expected to have.

**Two things about that override W16 must not discover the hard way.** It lapses
on its own: found mid-item with `settings get system peak_refresh_rate`
returning `90.0` while the live vote was `render: (0.0 60.0)` and the panel was
at 60. The setting value and the vote are different facts, and only the vote is
the panel. And re-applying it needs a *value change* — writing 90 over 90 fires
no observer, so it must go `put 60.0` then `put 90.0`. Verify in
`dumpsys display | grep mDesiredDisplayModeSpecs` immediately before a run, never
in `settings get`. This is the same lapse W2 recorded after the fact; now it has
a procedure.

**`:spike`'s `requestHighestRefreshRate` picks the wrong mode in general.** It is
`supportedModes.maxByOrNull { it.refreshRate }`, and a `Display.Mode` carries a
physical size as well as a rate — so on a panel that offers a faster mode at a
smaller resolution, that line asks the compositor to **change the panel
resolution** to gain refresh rate. Nothing in the call says so; the app is
simply rescaled, and on a drawing app that is the pen and the ink landing in
different places. This device offers 60 and 90 at the same 1440x2200, so the bug
is invisible here. `RefreshPolicyTest` drives a panel where it bites, and
carries the spike's line longhand as the control.

**The `DeviceProbe` port owed two fixes and found a third.** The two
`EGLDisplay` leaks this document lists were already fixed in `:spike` during W0
— the port is unchanged there, and saying so is better than quietly ticking it
off. What the port did have to fix is the probe's own output: it flattened the
mode list to `"1440x2200 @ 90.0Hz"` with `"%.1f".format(…)`, which follows the
default locale. nl-BE prints a comma, so the Phase 0 device report changed shape
with the user's language, in the one file whose whole purpose is being read
later. Modes are now `ModeInfo` data, formatted where they are shown — which is
also what let the refresh toggle choose from them.

**The texture cap is a gate now, not a note.** `documentSizeFor` clamps the
document to the measured `GL_MAX_TEXTURE_SIZE`, keeping the aspect ratio. The
failure it prevents is not a slow canvas but a blank one: the layer is blitted
as a texture every frame, and an over-cap upload fails with no exception on any
thread the app owns. This device measures 16383 against a 3300 requirement, so
it never fires — which is exactly why it is a tested function rather than a
`check` in `onCreate`. `DeviceReportTest` also pins the case that would
otherwise be worse than the bug: a failed GL probe reports `0`, and trusting it
would clamp a working device to nothing.

**The double tap shipped doing nothing, and the unit tests could not have caught
it.** `TwoFingerDoubleTap` passed ten tests on the JVM and, on the tablet, never
fired once. The router reports a gesture's pointers again as one finger *lifts*,
and the centroid of one finger is half a hand-span from the centroid of two:
fingers 240 px apart move their centroid 120 px at the instant the first leaves
the glass — five times the travel bound, on every tap, with nothing having
moved. A centroid over a changing set of pointers is not a position, and
comparing two of them is not a distance. `move` now takes the pointer count and
ignores reports that do not match the one the tap was primed with, every test in
the file drives the lift, and the control asserts the same 120 px travelled by
the same *two* fingers is still a pan. Verified on the device: pinch to
`scale 1.0 rot 0.576 t 1392.6,-1132.0`, then a synthetic two-finger double tap,
and the transform returns to `scale 0.5 rot 0.0 t 1120.0,-210.0` with
`fitOnResize` back to true.

That synthetic tap is `GestureStress.doubleTap`, added for the same reason the
pinch exists: `adb shell input` is single-touch, so a two-finger tap cannot be
driven from a shell at all. One event per frame, so the 83 ms tap and the 167 ms
gap are real durations and not a loop satisfying both bounds trivially.

**One thing beyond the letter of the item: the paper is now visible.** Through
W14 the dry render was `drawColor(paperColor)` over the whole surface — a white
page on a white background, with no way to see where the sheet ends. A stroke
running off the paper just stopped, and pan, zoom and fit moved something
invisible; W15's own double-tap-to-fit could not be judged by eye at all. The
render now paints a dark desk and then the page rectangle in the document's own
coordinates. The layer is still never painted into — the alpha-carrying
invariant is untouched — and the exported PNG's first row decodes as uniformly
white, so the desk is not in the file.

**No regression, and the way that had to be measured is itself a W16
instruction.** Same Sweep, same device, each as the *first* action in a fresh
process:

```
                   event p50   submit p50   over budget
W14 build           0.211 ms     0.041 ms      2.7%
W15 build           0.229 ms     0.041 ms      3.6%
```

The first attempt at that comparison read `1.293 ms` for W15 and looked like a
six-fold regression. It was not: the readout is **not stationary within a
session**. Driven as A / pinch / B / fit / C, the same Sweep with identical dab
counts reads p50 0.229, then 1.006, then 0.857 — and C has exactly A's
transform, so it is not the transform. Submit rises with it (0.041 to 0.186),
which is the library's own call slowing down, so it is the device and not the
app: the CPU read 44.7 C by then. `InputStats` already records this shape from
W9 ("the first stroke after a process start ran at a third the cost of every
later one, at a pinned 2.0 GHz"). **W16 must take every number as the first
action in a fresh process, and record the thermal reading beside it**
(`dumpsys thermalservice | grep mValue`). A run that follows a stress harness is
measuring the harness's leftovers, not the app.

The rest still holds under the new chrome: `reject 8/8 pass` with W13's exact
counts, batches `8 slots peak 3 spills 0 in flight 0`, and an export of
54,975 B reported in the chrome rather than in a `Toast` — a floating rectangle
over the canvas for two seconds after every save would be in W16's 240 fps
frame.

The W9-through-W14 readout is not deleted; it is folded behind a Stats toggle
and defaults off, because a feel pass cannot be run against eleven lines of
monospace over the paper. Every number is still live, one tap away, with the
stress harnesses beside it.

**W16 — DONE. Pen to photons is 45 ms, four fifths of it is downstream of the
app, and the pen holder's verdict on it is "fast enough for now".**

Both halves ran: the film, and the feel pass under a real hand. The feel pass
returned two judgements and they are recorded as judgements rather than
measurements, because that is what they are.

- **Prediction is off, and now for an observed reason.** With it on, the wet ink
  carries visibly jagged sections that persist until the pen lifts. See the W11
  note: the spurs are unretractable front-buffer ink, they clear at pen-up
  because the committed stroke is built from real samples only, and the blocker
  is the render path rather than the predictor.
- **45 ms is acceptable for Phase 1.** Judged on the tablet, with the pen, by the
  person who will use it. That is the phase's stated bar — felt quality — and it
  is met. It is *not* a claim that 45 ms is good: three quarters of it is the
  compositor, Phase 2 is where that becomes addressable, and the number is
  recorded here precisely so the improvement can be shown rather than asserted.

**The number, and the whole chain it decomposes into.** At 90 Hz, smoothing off,
prediction off, size 24:

```
digitizer sampling + dispatch     5.75 ms   the app's own `latency` line, real pen
engine pipeline (spline)          3.21 ms   StrokeBuilderTest
app work, onTouchEvent -> submit  0.31 ms   the app's own readout
                                 --------
software subtotal                 9.3 ms
measured, pen to photons         45.2 ms   240 fps film, four zigzag reversals
                                 --------
SurfaceFlinger + panel           35.9 ms   = 3.2 refreshes at 90 Hz
```

**Four fifths of Phase 1's latency is in a part of the pipeline Phase 1 does not
own**, and that is W0's finding arriving with a price on it. No front buffer
means graphics-core runs its fallback path, so wet ink goes through the ordinary
buffer queue, SurfaceFlinger composition and scanout: two frames of pipeline
plus a frame of scanout plus the panel's response is 3.2 refreshes almost
exactly. W1's verdict was always a *relative* one, and this is why it had to be
— every arm was paying the same 36 ms, and the differences that were visible to
the eye lived in the remaining 9.

It also prices Phase 2's options honestly. Tightening the engine buys at most
3.2 ms. Turning the shipped smoothing off buys 4.5. **The compositor is where
the other 36 lives, and only a real front-buffered GL path can touch it** — on a
device whose gralloc refuses `USAGE_FRONT_BUFFER`, that is a question about the
hardware, not about the code.

**How it was measured, because the method is most of the result.** The first
take was a fast straight stroke and `gap / speed`, which the protocol below
still describes. It gave 40-48 ms and it was hard work: separating the ink's
leading edge from the nib is separating two adjacent dark objects, and mask
subtraction cannot do it — every automatic attempt latched onto the pen.

The second take was a zigzag, and it is a better instrument by construction.
**At a reversal the pen is momentarily stationary, so the ink's apex is reached
exactly one latency later.** There is no gap to estimate and no speed term; it
is two frame numbers. And because *any* rigid feature of the pen reverses at the
same instant the nib does, the unknown offset between the tracked feature and
the contact point cancels — so the pen is tracked by the leftmost sliver of its
dark blob, which is easy and robust, rather than by a nib nobody can find.

```
apex   pen frame   ink frame   d frames   latency
  1      144.0        155        10.98     45.4 ms
  2      179.5        191        11.53     47.6 ms
  3      216.1        227        10.90     45.0 ms
  4      249.4        260        10.56     43.6 ms
                              median      45.2 ms
```

**The frame rate is calibrated from inside the clip rather than taken on trust.**
The ink advances in discrete steps, one per panel refresh, so the ink-growth
series carries the panel's 90 Hz as a clock: the dominant period is **2.690
frames**, so the capture rate is 242.1 fps against a claimed 240. That is worth
doing — a phone that quietly recorded at 120 would put the answer out by a
factor of two with nothing else looking wrong — and it independently confirms
the wet path really is presenting at the panel rate.

`tools/latency-from-video.py` is the whole analysis, with its ROI and thresholds
noted as tuned to one take. Phase 2 should re-run it against the same zigzag to
prove any improvement is real.

**One honest observation from the same run.** The stroke was 339 samples and
19,826 dabs — **230 dabs an event**, against the 19.5 W9 measured and the 0.31 ms
per-event ceiling that was sized against it. The app exceeded that ceiling on
52% of events, at `event p50 0.311, p99 1.223 ms`. The ceiling as written is not
met by a size-24 pen moving quickly. It also matters far less than the plan
assumed: half a millisecond of app time against 36 ms of compositor is not where
this device's latency is, and W9's budget was set before anyone knew that.

**The chain at the other rate, for the feel pass to compare against:**

```
                                    90 Hz / 321.75 Hz pen   60 Hz / 246.85 Hz pen
digitizer sampling + dispatch                    5.75 ms       not yet measured
engine pipeline, smoothing 0                     3.21 ms                4.07 ms
  + the shipped smoothing 0.15                  +4.50 ms               +4.50 ms
app work, onTouchEvent -> submit                 0.31 ms                0.178 ms
SurfaceFlinger + panel                          35.9 ms          film it at 60
```

**The engine's own tip lag was not known and is larger than the app's work by a
factor of forty.** `StrokeBuilderTest` drives a constant-velocity stroke and
measures how far the last dab is behind the last sample, as time: **3.21 ms**
with smoothing off, **7.71 ms** at the shipped default. The first figure is one
sample interval — Catmull-Rom needs four knots to emit the segment between the
middle two — plus a tenth of a millisecond of arc-length quantum. The second
adds the stabilizer.

**And the stabilizer's share is not its time constant, which is the trap.**
`strength * TAU_MAX` is 6.0 ms at the default, and that is the continuous-time
limit; this filter is evaluated at sample instants, and the exact discrete lag
on a steady line is `dt * q / (1 - q)` with `q = exp(-dt / tau)` — **4.58 ms at
321.75 Hz, 4.20 ms at 246.85**. Out by a quarter, in the direction that would
have had the film subtracting more smoothing than the smoothing does.
`StabilizerTest` pins the formula, both device rates, and the naive answer as
the control.

The consequence is worth stating plainly before anyone judges the feel: **at the
shipped default, smoothing costs more latency than the entire rest of the
software path put together**, and it is one slider away from zero. That is a
tuning decision the feel pass now gets to make with a number in hand rather than
by taste alone.

**`latency` on the readout is the term nothing else could reach.** `age` is how
stale the newest sample already was when `onTouchEvent` was handed it — the
digitizer's own sampling plus the framework's dispatch. It is measured against
`System.nanoTime` at the top of the same window `event` measures, so the two
meet exactly with no gap and no overlap.

Two things about it. First, **nothing synthetic can produce this number**:
`StrokeStress` stamps its events with the current time, so a Sweep reads
`age p50 0.0`, and an `adb shell input stylus swipe` reads whatever the
injection path happened to cost — 0.0, 0.41, 6.1 and 8.73 ms on four runs of the
same command, which is a measurement of `adb` and not of a digitizer. Only a
real pen on the glass answers it, which is one stroke of the filmed run. Second, **the figure is refused rather than approximated when the
clocks disagree**: `getEventTimeNanos` is documented in the
`SystemClock.uptimeMillis` base and `System.nanoTime` is `CLOCK_MONOTONIC`, and
a mismatch would give a plausible wrong number rather than a failure. The skew
is remeasured every time the line is built and an out-of-range value replaces
the figure with the reason. That check cannot live in a unit test —
Robolectric's `uptimeMillis` is a simulated clock and the first version of the
test failed with a skew of 74,071 seconds — so it is an assertion left running
on the only machine that can answer it.

**60 against 90, both fresh-process and first-action, same 1201-sample stroke,
CPU at 44.7 C:**

```
                        90 Hz      60 Hz
events for the stroke     336        226
samples/event            3.57       5.31
dabs/event               51.9       77.2
event p50              0.175 ms   0.178 ms
event p99              0.284 ms   0.355 ms
over the 0.31 ms budget   0.9%       4.9%
submit p50             0.031 ms   0.027 ms
per sample             48.8 us    33.4 us
```

The app's cost per *event* is the same at both rates; what changes is the tail
and the budget. At 60 Hz each event carries half again as many samples, so the
same work arrives in bigger lumps and misses the per-event ceiling five times as
often. Per *sample* the picture inverts — 48.8 us against 33.4 — because fewer
samples per event amortise the fixed per-event cost over less work. Neither
number is felt latency; both are inputs to it, and the film is what closes it.

**The measurement procedure, which W16 owed as much as it owed numbers.**
Starting an activity drops the 90 Hz vote: bounced before a launch it is gone by
the time the app is on screen, and the first A/B attempt of this item ran its
90 Hz arm at 60 without saying so. Bounced *after* the launch it held for 200 s
of continuous polling and through a full stress run. Launch, bounce, verify,
run, **verify again**. Together with W15's finding — take every number as the
first action in a fresh process, and record the CPU temperature beside it — that
is the whole of how a Phase 1 number is taken.

### The filmed run — the procedure, and W16's outstanding half

The 90 Hz baseline above was taken this way, and run D — prediction — was
judged by eye rather than filmed, because it did not survive the first stroke.
Runs B and C are **not** done: what the shipped smoothing costs and what 60 Hz
costs are still arithmetic rather than measurement. They are cheap now that the
procedure exists, and Phase 2 will want them as a before-picture.

**Equipment.** A phone that films at 240 fps, something to hold it still, bright
flicker-free light. Avoid mains-frequency lighting: a 50 Hz flicker beating
against a 90 Hz panel puts a rolling band through the footage exactly where the
ink is.

**Setup.**

1. Launch the app. *Then* bounce the refresh override:
   `adb shell settings put system peak_refresh_rate 60.0` and the same for
   `min_refresh_rate`, then both to `90.0`.
2. Verify: `adb shell dumpsys display | grep mDesiredDisplayModeSpecs` shows
   `primary=physical: (90.0 Infinity)`. The app's `refresh` line should read
   `now 90.0 Hz`.
3. In the app: Stats **off**, prediction **off**, smoothing **0**, size 24, Fit.
4. Frame the camera on the middle third of the page, as close as it will focus,
   so the pen tip and the ink's leading edge are both in shot and large.

**The runs.** Each is one long, fast, straight stroke left to right, as close to
a constant speed as a hand manages. Four of them:

| run | smoothing | prediction | panel | what it isolates |
|---|---|---|---|---|
| A | 0 | off | 90 Hz | the baseline — **done: 45.2 ms** |
| B | 0.15 | off | 90 Hz | what the shipped default costs, felt |
| C | 0 | off | 60 Hz | the panel's share |
| D | 0 | on | 90 Hz | **judged, not filmed: visibly jagged wet ink, rejected** |

**Draw a zigzag, not a straight line, and run
`tools/latency-from-video.py`.** The reversal method above needs no ruler, no
calibration and no speed: at each apex the pen is stationary, so the answer is
two frame numbers. Six or seven peaks in one stroke gives four or five
independent readings.

The straight-line alternative is `gap / speed` — in one frame, the distance from
the nib to the ink's leading edge, over the distance the nib moved to the next
frame times the capture rate; the pixel units cancel, so the screen size, camera
distance and lens are all irrelevant. It works and it agreed (40-48 ms), but it
is markedly harder to read: the ink's leading edge sits right under the nib, and
telling two adjacent dark objects apart is the part no amount of thresholding
fixes.

**Record beside each run**, from the app with Stats on: the `latency` line
(`age` p50/p95/p99 — this is the run that finally produces it, because only a
real pen carries real event times), `event`, `submit`, `xform scale`, and
`adb shell dumpsys thermalservice | grep mValue` for the CPU temperature.
Re-verify the refresh vote afterwards.

**The feel pass, same session, camera off.** Prediction on against off, judged on
tip-lead *and* on reversal spurs — a fast zigzag, not a straight line, because a
straight line is where prediction is always right. Smoothing at 0, 0.15 and 0.4
on a slow deliberate curve, which is where tremor shows and where the 4.5 ms is
being spent. 60 against 90 back to back on the same stroke shape, by the toggle,
which is the control W15 built and which no earlier item had.

### Runs B and C — what they returned

**Run B (smoothing 0.15, 90 Hz): no measurable difference, and the reason is a
correction to this document's own arithmetic.**

```
                      apex method              cross-correlation
run A  (smoothing 0)  45.2 ms, 4 readings      42.8 ms   r = +0.62
run B  (smoothing 0.15) 43.4 ms, 3 readings    41-44 ms  r = +0.20
```

B read *lower*, so the filter was driven with a triangle wave using its own exact
arithmetic. **A first-order lag delays a corner by about 0.6 tau, not tau** — the
continuous-time limit is `ln 2` = 0.693, and the discrete filter gives a little
less:

```
strength 0.15 at 321.75 Hz   ramp lag 4.58 ms   corner delay 2.79 ms   ratio 0.61
strength 0.15 at 246.85 Hz   ramp lag 4.20 ms   corner delay 2.17 ms   ratio 0.52
```

So **the smoothing costs 4.58 ms on a straight stroke and 2.79 ms at a
reversal**, and a reversal is exactly the feature this film measures. 2.79 ms is
inside the method's resolution — run A's four apexes spanned 43.6 to 47.6, and
its two methods disagreed by 2.4 — and B's zigzag was about 35% slower besides
(118 events against 86). **The film cannot see this term and should not be asked
to.** `StabilizerTest` measures the filter exactly and deterministically; that is
the right instrument for it. The useful output of run B is the distinction
between the two figures, which was not previously drawn.

**Run C (60 Hz): attempted twice, and it does not have an answer.** Reported as
inconclusive rather than resolved, because the two methods straddle the two
hypotheses it exists to separate:

```
apex readings, where the pen stayed in frame     46-48 ms
cross-correlation, five windows across two takes 30-34 ms   r = 0.15-0.40
```

Both takes failed for instrument reasons rather than for want of another window.
The first had a pen lift and the pen left the frame for half the stroke. The
second is a clean stroke — but the camera was reframed closer, and **the tool's
ROI and thresholds are tuned to one framing and do not transfer**: with the page
filling the frame there is no dark surround, the pen tracker latches onto a fixed
ink feature after the sixth peak, and the ink-top staircase sticks. On top of
that the cross-correlation is *structurally* weaker at 60 Hz — the ink advances
every 4.05 frames instead of 2.69, so its growth series is a sparser spike train
and the correlation peak flattens (r 0.62 at 90 Hz against 0.15-0.40 at 60).

What C would decide is worth stating so it does not get lost: **if 60 Hz reads
near 60 ms the compositor's 36 ms is refresh-quantised and a faster panel buys
latency directly; if it reads near 45 ms the term is largely fixed — most likely
the panel's own response — and Phase 2's front-buffer work would be chasing
something it cannot move.** That is a Phase 2 question with a Phase 2 budget, and
the honest thing is to leave it open rather than settle it from a reading the
instrument does not support. Getting it would need either a reshoot at run A's
exact framing, or a tool that finds the page and sets its own thresholds.

**Two things the runs did settle, both solid.**

- **The frame-rate self-calibration survives a panel change**, which is what
  makes it trustworthy rather than a coincidence: 2.690 and 2.700 frames a step
  at 90 Hz, 4.040 and 4.050 at 60, giving 242.1, 243.0, 242.4 and 243.0 fps for
  the same camera. It also confirms independently that the panel really was at 60
  for the C runs.
- **The digitizer-and-dispatch age does not move with the refresh rate.**
  5.75, 5.78 ms at 90 Hz; 5.71, 5.69 at 60. The digitizer itself slows from
  321.75 to 246.85 Hz between those, so **those 5.7 ms are dispatch, not
  sampling** — which means the one term of the software budget that looked like
  it might shrink with a faster panel does not.

**W17 — DONE. The analysis reconciled, with its wrong predictions left standing
beside the corrections.**

`docs/analysis.html` was written before a line of code existed, and the
temptation with a document like that is to quietly edit the mistakes out. The
opposite was done: every refuted claim stays where it was, struck through where
it is simply false, with a dated note beside it saying what the device said when
asked. Which predictions were wrong, and why, is the part worth keeping — a
design document with its errors removed is no longer evidence of how the
decisions were made.

**Three load-bearing claims did not survive contact with the hardware**, and all
three were in the two sections the whole design hangs on:

- **"Front-buffered rendering — biggest win ... skipping the normal
  double-buffer and compositor path."** There is no front buffer on this device.
  W16 prices the difference exactly: 36 of the 45 ms is the compositor path the
  claim says is skipped.
- **"Flipping the pen reports `TOOL_TYPE_ERASER`"** and **"suppress finger input
  while `AXIS_DISTANCE` shows the pen hovering"** — the document's two
  palm-rejection mechanisms, and neither exists. The pen's back reports
  `TOOL_TYPE_FINGER`, which is the bucket palm rejection *discards*, and the
  distance axis reads 0.0 across 400 hover samples.
- **"Pin the highest mode via `Surface.setFrameRate()` / preferred display
  mode."** An app cannot pin this panel to 90 Hz; the vendor's config outranks
  it, the `adb` override that works lapses on its own, and an app launch drops
  it.

Motion prediction is annotated as built, measured and rejected — the entry's own
warning about overshoot was right, and its mitigation ("render as *temporary*
ink", "clamp the distance") assumes ink that can be taken back, which this render
path does not have.

**Two of its questions the build answered, and two it left open**, which is now
said in the document rather than implied: the latency number the whole thesis
hangs on (45 ms, decomposed), and whether the front-buffer path exists here (no).
Still open: whether textured brushes have landed in `androidx.ink` — the
"ten-minute check that could save a month" was never done, because W3 was cut —
and whether a re-renderable wet layer makes prediction usable, which is the one
thing that would reopen it.

The header now carries `Measured latency 45 ms` beside the design facts, which
is what a document whose first sentence is "the whole design hangs on one
number" should have at the top of it.

## Carried over from the spike

**Reused:**

- `PenSample.kt` essentially verbatim → `:engine`, keeping the API-34
  `eventTimeNanos` path. Three edits only: pointerId support, explicit `source`
  parameter, single expansion per event.
- `PenCapture`'s two good ideas, not the class: `requestUnbufferedDispatch` on
  `ACTION_DOWN` (behind a toggle, defaulting off until W1 re-measures it against
  `DirectSurface` — the regression was measured against a path that no longer
  exists), and the `onHoverEvent` routing.
- `LowLatencyInkView`'s `SurfaceView` lifecycle shape, though not its renderer.
  **Including its comment explaining why there is no `setBackgroundColor`** —
  that comment is a fix's tombstone, not a style note, it applies to any
  `SurfaceView`, and deleting it re-opens a day of blank-canvas debugging.
- `DeviceProbe.probeGl()` / `supportsFullCanvas` as a startup gate on the 4096²
  option, with the two `EGLDisplay` leaks on the pre-`try` early returns fixed,
  plus `getMemoryInfo()` and the `isSupported` probe added in W0.
- `SessionExporter.writeToDownloads()`'s MediaStore `IS_PENDING` pattern,
  retargeted and with the zero-byte-file bug fixed.
- `keepNavGesturesOutOfTheWay()` verbatim; `requestHighestRefreshRate()`
  **replaced at W15** by the three-way `RefreshPolicy` toggle
  (request-highest / request-nothing / request-60) with the mode read back,
  because with the unconditional 90 Hz request there was no way to construct a
  genuine 60 Hz control. Deleting the adb override does not produce one: both
  Phase 0 runs were already "app requests 90, vendor cap decides". Measured at
  W15, and the answer is better than the plan assumed — see the W15 note.
- The build config: compileSdk 35, minSdk 29, targetSdk 34, Java 17, release
  unminified and debug-signed so latency stays measurable on a non-debuggable
  build. Gradle wrapper pinned at 8.11.1 — the distro's Gradle 9.x is both
  incompatible with AGP 8.7.3 and, as packaged on Arch, broken outright.

**Deleted:** `PenStats.kt` entirely (a `TreeSet` insert per sample has no place
on a drawing app's hot path). `BaselineInkView` (the A/B control, stays in the
frozen spike). `InkPaints.cursor()`/`counter()`/`drawFrameCounter()` (video
instruments). `InkPaints.widthFor` (an admitted arbitrary linear ramp, replaced
by `RoundPen.sizeFor`). `MainActivity`'s tabs and `formatStats`/`formatDevice`.
`SessionExporter.buildReport()`. `LowLatencyInkView`'s scene model — the
ever-growing `ArrayList<Segment>` replayed on every commit, mutated from two
threads, is precisely what the layer bitmap replaces.

`:spike` stays in `settings.gradle.kts`, frozen after W3, still independently
installable. It is the only instrument that can settle the remaining hardware
questions, and deleting it is defensible only after W17 records a baseline.

## Deliberately not in Phase 1

- **GLES 3.0, `GLFrontBufferedRenderer`, FBOs, SDF shaders.** Phase 1's render is
  circles plus a matrixed blit; Canvas already does both on the GPU. Recorded
  debt, paid in Phase 2, one class.
- **The scratch FBO.** Not a compromise — a full-opacity pen cannot produce the
  dark blobs it exists to prevent. **Tripwire, written into `RoundPen` as a
  comment:** do not ship an opacity slider, a translucent brush, or a second
  blend mode before the scratch buffer exists.
- **androidx.ink, both 1.0.0 and 1.1.0-alpha07.** Stable ships `StockBrushes` and
  an opaque `BrushFamily` with no constructor. The alpha has real textured
  brushes but replaces "one bitmap layer" with an immutable stroke list — a
  different memory model, undo model and export path. Re-decide in Phase 2 with
  W1's number in hand. **Re-decided, 2026-09-08.** The brush API went public in
  1.1.0-alpha03 and the texture support is real, so the check flipped — and the
  decision did not, because W16's number says Ink offers no latency win (same
  graphics-core stack, same 36 ms) while still costing the document model.
- **Pixel golden images.** W7 ships **dab-list goldens** instead — serialized
  `(x, y, radius)` output for a fixed corpus, diffed on the JVM. Runs without a
  device and does not invalidate on every AA tweak. Eight strokes (straight,
  arc, onset, flick, taper, dwell, corner, tap), regenerated with
  `-Dartiest.golden.write=true`, and `DabGoldenTest` carries a mutation check
  that every stage the goldens claim to cover actually moves at least one file.
- **Undo, the layer stack, cached compositing, tiling, mipmaps.** Phase 3+.
  Phase 1's only obligations are keeping the layer alpha-carrying and storing
  per-stroke bounds, which is what Phase 3's undo snapshots.
- **`AXIS_DISTANCE` finger suppression.** Not deferred — **deleted**. 400 hover
  samples, all 0.0. `docs/analysis.html:313` gets amended in W17.
- **Flip-to-erase.** Not deferred — **impossible**. The pen back reports
  `TOOL_TYPE_FINGER`. Eraser becomes a barrel-button mapping in Phase 2; the
  three buttons are individually addressable (4 / 32 / 64).
- **Hilt, Proto DataStore, `.ora`, JPEG, SAF, autosave, image import, colour
  wheel.** Phase 4, and the doc agrees. Manual DI — about eight objects.
- **`build-logic` / convention plugins.** One Android module. They arrive with
  the second.

## Open questions that need a human answer

1. **Is the 90 Hz adb override acceptable as a personal-device precondition, or
   must Phase 1 feel right at 61 Hz?** It changes the prediction target, the
   stabilizer time constant, the frame budget *and* the digitizer rate
   (246.85 Hz vs 321.75 Hz), and no app code can set it for a user. A product
   call, not a technical one.
2. **Default document size: 2160×3300 (1.5× the panel), or A4 at 300 dpi
   (2480×3508, 33.2 MiB)?** Answer after W0 reports real total RAM. One line
   today, a migration later.
3. **Does the repo stay private?** It is currently public at
   `github.com/stdierckx/artiest`. That already forecloses using GPL reference
   material (Krita, GIMP, MyPaint) as anything but read-and-reimplement.
   Confirm that is intended.
4. **Answered.** W0 found no front buffer, W1 found the reimplementation lost
   anyway, and the call was to ship graphics-core on felt quality. What remains
   open is *why* it wins — deferred to Phase 2, where a GL engine has to
   reproduce it. Recorded because it is the kind of thing that nags.

## What changed in reconciliation

Against the first draft, authored before the last round of device work:

**Package names.** `eu.torqa.artiest` → `be.thalos.artiest` throughout.

**Facts that moved from "not settled" to "settled":**

- The front buffer renders. The first draft's W0 spent half a day proving it;
  the bug that made it look broken was an opaque `setBackgroundColor` on a
  `SurfaceView`, fixed in `b02a531`.
- The A/B is decided by on-device comparison: front buffer wins, prediction and
  unbuffered dispatch both hurt. The first draft treated this as the phase's
  central open risk and gated all other work behind it.
- The pen has no eraser; the pen back reports `TOOL_TYPE_FINGER`; three barrel
  buttons are individually addressable.

**A correction the review caught in its own ground truth.** One lane read
`LowLatencyInkView` *after* the fix, saw the comment explaining why there is no
`setBackgroundColor`, and concluded that occlusion had never been the cause —
reading the fix's tombstone as evidence the bug never existed. It then told W0
to look elsewhere on a blank canvas. Occlusion *was* the cause; the comment is
now marked as a tombstone so it cannot be misread the same way twice.

**Thirteen serious refutations folded in**, of which the load-bearing ones:

- `onDrawFrontBufferedLayer` is called N times on one `RecordingCanvas` with no
  `save`/`restore`, so a bare `concat` compounds to M², M³.
- `renderer.clear()` never invokes `onDrawMultiBufferedLayer`; clearing needs
  three calls, not one.
- The library does not redraw from `surfaceChanged`; the app needs its own
  `SurfaceHolder.Callback` or the canvas blanks on rotation.
- Layer presence in `dumpsys` cannot detect a front-buffer fallback — the name is
  identical either way. Only `HardwareBuffer.isSupported` can.
- Front-buffered rendering does **not** skip the compositor; it commits a full
  SurfaceControl transaction per render.
- `drawBitmap(…, null)` is point sampling; the scale floor of 0.35 was past
  bilinear's honest range. Now a filtering `Paint` and a 0.5 floor.
- The prediction distance clamp was smaller than the predictor's horizon by
  construction, so it would have discarded most of the prediction on every
  sample. Curvature gate retained, distance clamp dropped.
- W13's fallback of transforming the `SurfaceView` via view properties cannot
  work — the pixels are on library-owned SurfaceControls, not the view's
  RenderNode.
- The ~3.4 ms layer re-upload assumed 8.0 GB/s that nothing measured. Promoted
  to its own early work item (W11) so W13's design does not rest on it.

**Zero fatal refutations.** Both adversarial reviewers returned
`planSurvives: true`.

*(Work-item numbers in the list above refer to the first draft's numbering, which
this revision changed. They are left as written so the history reads straight.)*

### Round two — after W0 ran

W0 was specced as the phase's cheapest question and its stop condition. It fired.

**The finding.** `HardwareBuffer.isSupported(1, 1, RGBA_8888, 1, 4294970112)` is
`false` on the MT8781V/NA, as is the bare `USAGE_FRONT_BUFFER` bit. There is no
front buffer on this hardware and there never was.

**What that invalidated.** Not the A/B — the graphics-core arm really did beat
the baseline, and that was seen, not inferred. What it invalidated is the
*explanation*. The winning arm was `CanvasFrontBufferedRenderer` in its fallback
path, so whatever it won on, it was not front-buffering. Every design decision
that followed from "pixels accumulate in a preserved buffer" therefore had to be
re-derived, and most of them dissolved: the freeze-the-transform invariant, the
gesture/stroke handshake it forced, the three-call clear sequence, and the
unretractable-prediction argument.

**What it cost.** Roughly a day of plan, no code — the `InkSurface` seam existed
precisely so this could happen cheaply, and it was specified before W0 ran rather
than after. That is the seam earning its keep, which is worth noting because
speculative interfaces usually do not.

**What it bought beyond the verdict.** Total RAM (7.70 GiB) settling a budget
that had been an assumption; a corrected mechanism for graphics-core's fallback
(it probes and lowers, it does not blindly degrade); and a fixed `clear()` in the
spike, which still matters because the spike is still the A/B control.

**Two methodological lessons, both nearly missed.** The draft specified the wrong
usage flags (4294967808 rather than the library's 4294970112) — the answer
survived only because both are refused here. And "isSupported says no" is
indistinguishable from "isSupported says no to everything" without a
discrimination control; that control was run, and it is the reason the verdict
is trustworthy rather than merely alarming.

### Round three — after W1 and W2, and the decision to ship graphics-core

W0 fired the stop condition and this plan was rewritten around
`DirectSurfaceInkSurface`. W1 then built that arm and **it lost**, so the plan is
rewritten back.

**What was actually established.** Judged on hardware at a verified 90 Hz,
graphics-core is closest to the pen tip; the plain-`View` baseline and
`DirectSurface` both read as smoother but further behind. Reproducing what the
library appeared to be doing — dedicated `SurfaceControl`, own render thread,
the same 1000 fps frame-rate vote — was **not sufficient**. Whatever it does, it
is not only those three things.

**What was not established, and is now deliberately unanswered.** Why. The
leading hypothesis is that both losing arms wait for the next vsync before
drawing while `renderFrontBufferedLayer` starts immediately, which fits
"smoother but behind" precisely. The immediate-scheduling variant that would
have tested it was started and abandoned once the decision was made. Phase 1
does not need the answer; **Phase 2 does**, because a custom GL engine has to
reproduce the win without the library.

**What the decision costs.** Every constraint the `DirectSurface` design had
removed comes back: frozen transform for the lifetime of a wet stroke, strict
pen/gesture mutual exclusion, unretractable predicted ink, the three-call clear
sequence, and a dependency on a library whose headline feature this device does
not have. Deliberate, not drift.

**W2 stands regardless.** Draw p99 4.00 ms against an 11.1 ms budget, 0% dropped
over 600 frames. It was measured on the `DirectSurface` loop, so it no longer
gates Phase 1's render path — but it does establish that this GPU has ample
headroom for a full-document blit per frame, which is what Phase 3's layer
compositing will need.

**The lesson worth carrying.** Three of the four harness bugs found before W1
could be judged would have produced a *confident wrong answer* rather than an
obvious failure: a half-pixel translate softening one arm, ink discarded at the
document edge, and prediction defaulting on across arms that implement it
incompatibly. An A/B harness needs its own adversarial review before its output
is trusted — the instrument is as likely to be wrong as the thing measured.

## What comes next

**`docs/phase2-plan.md`.** It inherits three things from here: the 45.2 ms
decomposition, which retires latency as Phase 2's organising goal and demotes the
GL rewrite from W1 to a gated W14; the `RoundPen` tripwire, which W6 and W7 there
finally pay; and this plan's unanswered open questions 1 and 3, which are still
unanswered and now have consequences.
