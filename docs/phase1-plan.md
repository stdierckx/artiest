# Phase 1 — Minimum ink

> Reconciled 2026-09-07 against what was measured on DTHA116, against 13
> serious refutations from adversarial review of the first draft, and then
> again after **W0 ran and fired its stop condition**. See **What changed** at
> the end for the delta.

## The decision

**There is no front buffer on this hardware.** W0 measured it: the MT8781V/NA
gralloc refuses `USAGE_FRONT_BUFFER`, including the bare bit on its own, so no
superset can ever be granted. `CanvasFrontBufferedRenderer` was always running
in its fallback path here. The plan's stop condition therefore fires, and
Phase 1's wet-ink path is **`DirectSurfaceInkSurface`** — a plain
`SurfaceHolder` on a dedicated render thread using `lockHardwareCanvas`.

Everything else stands. Build Phase 1 as **two Gradle modules**: `:engine`, a
pure-Kotlin JVM module holding every stroke decision testable without a device,
and `:app`, the single Android module holding everything that touches a
`Canvas`, a `Surface` or a `MotionEvent`. The document is **one ARGB_8888
`Bitmap` at 2160×3300**, and the layer is written **exactly once per stroke, on
the render thread** — never incrementally, never from the UI thread. That single
write makes `ACTION_CANCEL` free (the layer never contained the stroke), gives
one writer and one lock, and deletes an entire tile-backup subsystem.

The render-path swap costs one file because the `InkSurface` seam was specified
before W0 ran. It also **removes** the phase's hardest constraint: a
`lockHardwareCanvas` surface is redrawn in full every frame, so there are no
already-baked pixels stuck at an old transform, and the freeze-the-transform
rule disappears with them. What it buys instead is a new obligation — every
frame now pays for a full document blit — which is why the throughput probe
moves early.

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
  the surface is view-sized (1181x2200), not the full 7.1 Mpx document.
- **The 90 Hz override lapses on its own, and `settings get` does not show it.**
  It keeps reporting 90.0 while `dumpsys display` shows the render range capped
  at 60. Only a fresh 60 -> 90 bounce revives it; rewriting 90.0 over 90.0 is a
  no-op that changes nothing. Ruled out as a cause: the app's 1000 fps frame-rate
  vote, which holds 90 Hz fine once bounced. **Bounce and verify `activeMode`
  immediately before any judged run**, and read the Hz on the Latency tab, which
  is red below 90.
- **Memory: 7.70 GiB total**, 4.4 GiB free, low-memory threshold 0.21 GiB.
  `memoryClass` 256 / `largeMemoryClass` 512 do not bind, because on API 34
  bitmap pixels are native-heap allocations. **Do not set `android:largeHeap`.**

**Not settled:**

- **Whether `DirectSurfaceInkSurface` matches the graphics-core fallback.** The
  measured win came from *something*, and the leading candidates are a dedicated
  `SurfaceControl` on its own render thread plus graphics-core's unconditional
  1000 fps frame-rate vote — both of which a plain `SurfaceHolder` path can
  reproduce. This is now the phase's central open risk and W1 measures it.
- **Whether `lockHardwareCanvas` preserves buffer contents between frames.**
  Assume **not** — the documented contract is that the content is undefined and
  the whole surface must be redrawn. If it turns out to preserve on this device,
  do not exploit it.
- **No absolute latency number exists.** The A/B verdict is comparative and
  visual. Deliberately deferred: the number is worth more measured against
  Phase 1's real ink than against the spike's `drawLine` segments.
- Whether `MotionPredictor.isPredictionAvailable` returns true for this pen, or
  `SystemMotionEventPredictor` silently falls back to the bundled Kalman
  predictor.

### The A/B result

"Front buffer on, everything else off" is the best configuration on this
hardware. W0 then established that **it was never front-buffering**: the toggle
selected `CanvasFrontBufferedRenderer` running in its fallback path, and what it
was being compared against was `BaselineInkView`, a plain `View` going through
the ordinary view hierarchy and HWUI.

The win is real — it was seen — but the mechanism is not the one the name
implies. What the winning arm actually had over the baseline was a dedicated
`SurfaceControl` rendered on its own thread, plus graphics-core's unconditional
1000 fps frame-rate vote (`configureFrontBufferLayerFrameRate`, verified
unconditional in bytecode). Both are reproducible without the library, which is
why `DirectSurfaceInkSurface` is a credible replacement rather than a
consolation prize — and why W1 must measure it rather than assume it.

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
│       ├── input/  PenSample, ToolType, Stabilizer, PredictionGate
│       ├── ink/    RoundPen, CatmullRomResampler, StrokeBuilder, Dab, DabList, Stroke, Bounds
│       ├── xform/  CanvasTransform
│       └── trace/  TraceRecorder, TracePlayer
├── engine/src/test/           JUnit, runs natively — no Robolectric, no mockable android.jar
├── app/                       com.android.application, be.thalos.artiest
│   └── be.thalos.artiest
│       ├── input/  MotionEvents (collectSamples ext), InputRouter, Predictor
│       ├── canvas/ InkSurface, InkSurfaceView, DabBatchPool, GestureController, Matrices
│       ├── doc/    Document, Layer
│       ├── ink/    DabRasterizer
│       ├── io/     PngExporter
│       └── DeviceProbe, MainActivity
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

**Render path — `DirectSurfaceInkSurface`.** `InkSurfaceView` is a bare
`SurfaceView` owning **one dedicated render thread** with a `HandlerThread` and
a `SurfaceHolder.Callback`. The thread runs a single loop: take the current
`CanvasTransform` and the pending dab batch, `holder.lockHardwareCanvas()`,
`drawColor(paperWhite)`, `concat(docToView)`, `drawBitmap(layer, 0f, 0f,
filterPaint)`, draw the wet stroke's dabs on top, `unlockCanvasAndPost()`.

**The whole surface is redrawn every frame.** `lockHardwareCanvas` gives no
content guarantee between frames, so there is nothing to preserve and nothing
accumulates. That is the model, and three things follow from it:

- **The transform is free to change at any time,** including mid-stroke. There
  are no already-baked pixels to strand at an old scale. The freeze-transform
  invariant that dominated the front-buffered design is gone.
- **Cancellation is still free**, for the original reason: the layer `Bitmap`
  only receives the stroke at pen-up, so `ACTION_CANCEL` drops the wet dab list
  and the next frame simply does not draw it.
- **Every frame costs a full blit.** The surface is view-sized (1181×2200 per
  `dumpsys`), so this is one matrixed blit of ~2.6 Mpx plus the wet dabs — not
  the 7.1 Mpx document. Unmeasured, and W2 measures it before anything is built
  on it.

Draw is driven by **input arrival, coalesced onto `Choreographer`**: a sample
arriving marks the frame dirty, and at most one frame is in flight. At 250–320 Hz
against a 90 Hz panel this is the difference between 3 frames of work and 300.

The renderer must be torn down in `surfaceDestroyed` **synchronously** — the
render thread cannot outlive the `Surface` it is locking, and `lockHardwareCanvas`
on a destroyed surface throws. Quit the thread and join it inside the callback.

**It must not call `setBackgroundColor`.** This is not a style preference: a
`SurfaceView` shows its surface through a transparent hole punched by
`clearSurfaceViewPort()`, and `super.draw()` paints the view's background *over*
that hole. An opaque background hides the front buffer completely — ink, frame
counter and all — while the renderer continues submitting frames perfectly. This
cost the spike a full session of blank-canvas debugging and was fixed in
`b02a531`. Paper white comes from `onDrawMultiBufferedLayer`'s `drawColor`.

`InkSurfaceView` implements a narrow `InkSurface` interface (`beginStroke(Matrix)`,
`drawWet(DabBatch)`, `commitStroke(Stroke)`, `cancelStroke()`, `redrawDry()`,
`release()`) expressed in strokes and dabs with no library type in the
signature, so a renderer swap is one file.

Two details of the frame body are load-bearing:

- **`drawBitmap(layer, 0f, 0f, filterPaint)`, never a null `Paint`.** A null
  Paint means point sampling, which would nearest-neighbour-resample the whole
  document at every zoom and rotation and make fine ink crawl and shimmer.
  `filterPaint` has `isFilterBitmap = true` and `isAntiAlias = false`.
- **`save()`/`restoreToCount()` around the `concat`,** even though a fresh
  `lockHardwareCanvas` canvas arrives with an identity matrix. It costs nothing,
  and it is the habit that stops the compounding-matrix bug the front-buffered
  design would have shipped, where a callback invoked N times on one recording
  canvas turned `concat(M)` into M², M³.

`CanvasFrontBufferedRenderer` is **retired from the design**, and the reason goes
in a comment so Phase 2 does not reopen it: on this device it cannot obtain a
front buffer, so it contributes a SurfaceControl, a render thread and a frame
rate vote — all of which `DirectSurfaceInkSurface` has directly — in exchange
for an accumulate-only model whose pixels cannot be re-transformed. On hardware
that *does* grant `USAGE_FRONT_BUFFER` the trade would be worth revisiting;
`DeviceProbe.frontBufferSupported` is what tells you, and it is exported.

`LowLatencyCanvasView` is **also rejected**: its internal scene `Bitmap` is
*view*-sized, so it cannot represent a 2160×3300 document you pan around, and
its `onDraw` calls `canvas.setMatrix` (replace, not concat). GL is **deferred to
Phase 2** and recorded as debt: Phase 1's entire render is antialiased circles
plus one matrixed bitmap blit, both of which hardware Canvas already does on the
GPU.

**Threading contract, stated once and enforced:** the layer `Bitmap` is written
on the render thread only, only in the commit step at pen-up, under `layerLock`.
`PngExporter` takes the same lock to read. The UI thread never touches it. The
wet dab list is handed across by an `AtomicReference.getAndSet(null)`, so the
render thread consumes it exactly once and a dropped frame cannot double-stamp.

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
rather than a redesign. `sizeFor(pressure)` is a cubic, plus an **onset ramp over
the first few samples** — `ACTION_DOWN` arrives near zero pressure (0.00208
measured), and a cubic maps that to an invisible tip, which will otherwise be
misdiagnosed as latency.

`StrokeBuilder` fits Catmull-Rom through the stabilized document-space points
and resamples at `spacing` (1/8 diameter), emitting `Dab(x, y, radius)` into a
reusable list while accumulating `Bounds`. A fast stroke leaves tens of pixels
between samples, so this stage is not optional even at 320 Hz. Budget: **under
0.31 ms per event, zero allocation** from `onTouchEvent` to
`renderFrontBufferedLayer`. `DabBatch` comes from a preallocated ring
(`DabBatchPool`); `renderFrontBufferedLayer` is async with no completion signal,
so the slot count is **validated against observed render-thread lag** in W10's
allocation trace, not assumed.

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

**Export path.** `PngExporter` is a suspend function on `Dispatchers.IO`. The
layer stays **alpha-carrying** — paper white is drawn by the renderer, never
baked in — so it remains a real layer for Phase 3's stack and Phase 4's `.ora`.
Export takes `layerLock`, allocates one transient document-sized `Bitmap`,
`drawColor(paperWhite)` then `drawBitmap(layer, 0f, 0f, filterPaint)`, releases
the lock, and compresses off-lock. MediaStore write lifted from
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

## The canvas transform, and what W0 changed about it

The first draft's hardest constraint came from the front buffer's accumulate-only
model: `SingleBufferedCanvasRenderer` iterates only the params added since the
last flush and draws them into the **same preserved `HardwareBuffer`**, so pixels
already in the front buffer are never re-recorded and never re-transformed. A
zoom mid-stroke would therefore leave the already-baked half at the old scale
while new dabs land at the new one — one stroke rendered at two transforms — and
`SurfaceControlCompat.Transaction` cannot rescue it, having `setScale` and eight
discrete `setBufferTransform` cases but no `setMatrix`.

**That constraint is gone**, because the front buffer is gone. A
`lockHardwareCanvas` surface is redrawn in full every frame from the layer
`Bitmap` plus the live wet-dab list, so the transform can change at any time,
including under the pen. Live zoom while drawing is *possible* here in a way it
was not under the front-buffered design.

Two things replace it as the hard parts.

**1. Every frame pays for the document blit.** Under the front-buffered design
only gesture frames redrew the dry layer; now every frame does. The surface is
view-sized so it is ~2.6 Mpx of matrixed blit rather than the full 7.1 Mpx
document, and drawing is coalesced onto `Choreographer` so it happens at most 90
times a second rather than 320. But nothing has measured it on a Mali-G57 MC2,
and the whole render path rests on it. **W2 measures it before W8 is written.**

**2. Predicted ink is retractable now, which is a genuine gain.** Under the front
buffer a speculative dab stamped at frame N was baked until pen-up. With a full
redraw each frame, predicted dabs simply are not drawn in the next frame if the
real sample contradicts them. That does not make prediction good — the measured
result was that it hurt — but it changes the failure mode from a permanent spur
to a transient one, and it is worth re-judging in W12 with that in mind.

**A correction worth keeping** even though the path is retired: front-buffered
rendering does *not* "skip the compositor", as the spike's comments and
`docs/analysis.html` both claim. `CanvasFrontBufferedRenderer` builds and commits
a full `SurfaceControl` transaction on **every** render. That is the likely
mechanism behind the unbuffered-dispatch regression, and it is why
`DirectSurfaceInkSurface` is not obviously slower: both go through
SurfaceFlinger, and neither was ever scanning out directly on this device.

### Palm rejection, which does not change

Rejection is by tool type and by stroke exclusivity, not by hover height, because
`AXIS_DISTANCE` is dead. `InputRouter` keeps **strict mutual exclusion**: while a
pen stroke is live, finger pointers are dropped entirely; while a gesture is
live, a pen `ACTION_DOWN` is ignored until all fingers lift.

The transform argument for this is now weaker — the canvas *can* move under the
pen safely. The palm argument is not: a palm landing mid-stroke would otherwise
chop the line in two and pan the canvas. Keep the exclusivity, for the reason
that survives.

Note the sharp edge — the pen's back reports `TOOL_TYPE_FINGER`, so it lands in
the rejected bucket. That is the correct outcome (it is not an eraser) but it
must be a deliberate decision in the router rather than an accident.

### The clear button

Blank the layer `Bitmap` under `layerLock`, drop the wet dab list, mark the frame
dirty. One path, no library semantics to get wrong.

This is simpler than it was, and the reason is worth recording: under
graphics-core, clearing needed three calls in a specific order, because
`renderer.clear()` blanks the multi-buffered layer **without ever invoking the
draw callback** (it records a `BlendMode.CLEAR` into the library's own
RenderNode), while `commit()` hands the whole `ParamQueue` back to the callback
and resurrects the stroke into the list just emptied. The spike's `clear()` had
exactly that bug and it is fixed in `54999d8` — the spike is still the A/B
control, so it needed to be right.

**Prediction detail carried forward.**
`PredictionGate` (pure, in `:engine`) gates on **curvature, not distance**: it suppresses prediction outright when the heading change between the
last two stabilized samples exceeds ~25°. The first draft also clamped distance
to half a frame of travel; that clamp is smaller than the horizon the predictor
aims at *by construction*, so it would discard most of the prediction on every
sample and amount to scaling the prediction down uniformly — the cost of
prediction without the benefit. If a distance cap is wanted, it must be the
predictor's actual horizon × velocity, not a frame fraction.

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
| 1 | **`DirectSurfaceInkSurface` arm in the spike**, A/B'd by eye against both the baseline and the graphics-core fallback | `:spike` | **High** | — | 1.0 |
| 2 | ~~Full-redraw throughput probe~~ **DONE. draw p99 4.0 ms against an 11.1 ms budget, 0% dropped over 600 frames. Full redraw is viable.** | `:spike` | — | — | ✔ |
| 3 | Timeboxed androidx.ink 1.1.0-alpha07 arm, hard stop at one day. Freeze `:spike` after this | `:spike` | Low | 1 | 1.0 |
| 4 | `PenSample`, `MotionEvents.collectSamples`, `InputRouter`, `TraceRecorder`/`TracePlayer` | both | Low | 3 | 1.5 |
| 5 | `CanvasTransform` + native JVM tests | `:engine` | Low | 3 | 0.75 |
| 6 | `Document`, `Layer`, `Stroke` | `:app` | Low | 3 | 0.5 |
| 7 | `Stabilizer`, `RoundPen`, `CatmullRomResampler`, `StrokeBuilder` + dab-list goldens | `:engine` | Medium | 4, 5 | 1.5 |
| 8 | `InkSurface` + `InkSurfaceView`: render thread, `SurfaceHolder.Callback`, `lockHardwareCanvas` frame loop, `DabBatchPool` | `:app` | **High** | 2, 6, 7 | 1.25 |
| 9 | Wet ink end to end, allocation trace, batch-pool slot validation | `:app` | Medium | 8 | 0.75 |
| 10 | Commit: stroke becomes dry ink at pen-up, under `layerLock` | `:app` | Medium | 9 | 0.5 |
| 11 | `Predictor` — `Source.PREDICTED`, curvature gate, forked stabilizer state, runtime toggle | both | Medium | 10 | 0.75 |
| 12 | `GestureController` — pan/zoom/rotate. **Transform may change mid-stroke**, so no freeze handshake | `:app` | Medium | 10 | 1.0 |
| 13 | Cancellation and palm rejection (`ACTION_CANCEL`, `FLAG_CANCELED`, fingers and pen-back never draw) | `:app` | Low | 12 | 0.5 |
| 14 | `PngExporter` | `:app` | Low | 10 | 0.75 |
| 15 | `MainActivity`, Compose chrome, refresh-rate toggle, `DeviceProbe` port | `:app` | Low | 11, 13, 14 | 1.0 |
| 16 | Feel pass on device; **film at 240 fps and record the Phase 1 latency baseline** | device | Medium | 15 | 1.5 |
| 17 | Reconcile `docs/analysis.html` with what was measured | docs | Low | 16 | 0.5 |

**≈14.25 days**, down from 15.25 because W0 is spent and the front-buffered
design's freeze-transform handshake is gone with it. Still over a 10–15 day
budget, and I would rather say so than discover it in week three. If it runs
long, cut in this order: W3 (the Ink arm — it buys Phase 2's kill criterion, not
Phase 1's ink), then W12's fallback ladder, then W17 slides into Phase 2's first
commit. Do **not** cut W1, W2 or W16.

**W0 — DONE, `54999d8`.** The probe returned `false` for the library's exact
usage set (4294970112) *and* for the bare `USAGE_FRONT_BUFFER` bit, so the stop
condition fired and the render path is `DirectSurfaceInkSurface`.

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

**W1 — how I'd know it went wrong.** Build `DirectSurfaceInkSurface` as a fourth
arm in the spike's Latency tab, alongside baseline and the graphics-core
fallback, and compare all three by eye. Gone wrong is **`DirectSurface` feeling
clearly worse than the graphics-core arm**, which would mean the win came from
something the library does that a plain `SurfaceHolder` does not — most likely
its unconditional 1000 fps frame-rate vote, which is reproducible via
`Surface.setFrameRate(1000f, …)`. Try that before concluding anything. If
`DirectSurface` matches or beats it, the library leaves the design entirely.

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

**W8 — how I'd know it went wrong.** Build incrementally against the device: one
hardcoded dab, confirm it appears; then a straight drag, confirm wet ink appears
and survives commit. Gone wrong: **a blank canvas** (check for an accidental
`setBackgroundColor` on the `SurfaceView` first — it is the known trap, it cost a
session once already, and the renderer will look perfectly healthy in logcat
while painting invisible frames); a crash on rotation or backgrounding (means the
render thread outlived the `Surface` — `lockHardwareCanvas` on a destroyed
surface throws, and `surfaceDestroyed` must quit and join synchronously); or a
stroke that flickers between frames (means the wet dab list is being consumed
rather than read, so a frame that arrives between samples draws nothing).

**W12 — how I'd know it went wrong.** A two-finger pinch that stutters or lags
the fingers. Contingency ladder: (a) Choreographer coalescing is already the
default; if it still stutters, (b) during the drag only, blit a **downscaled**
copy of the layer, accepting a soft image while the fingers are down and
snapping to a sharp re-render on gesture end.

Note what contingency (b) is *not*: applying the delta via `SurfaceView` view
properties (`setScaleX`/`setRotation`/`setTranslation`). A `SurfaceView`'s
View-level transform moves the punched hole in the window, not the surface
content. Ship double-tap-to-reset and fit-to-view regardless, so a lost canvas
is always recoverable.

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
- `requestHighestRefreshRate()` and `keepNavGesturesOutOfTheWay()`, verbatim —
  but see W16: the refresh request becomes an explicit three-way toggle
  (request-highest / request-nothing / request-60) with `mActiveSfDisplayMode`
  read back, because with the current unconditional 90 Hz request there is no
  way to construct a genuine 60 Hz control. Deleting the adb override does not
  produce one: both Phase 0 runs were already "app requests 90, vendor cap
  decides".
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
  W1's number in hand.
- **Pixel golden images.** W7 ships **dab-list goldens** instead — serialized
  `(x, y, radius)` output for a fixed trace corpus, diffed on the JVM. Runs
  without a device and does not invalidate on every AA tweak.
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
4. **W0 said there is no front buffer. Ship `DirectSurfaceInkSurface` and move
   on, or spend a week understanding why the MT8781 refuses it?** I would ship
   and move on — the bar is felt quality, and the answer would not change what
   gets built. Recorded here because it is the kind of thing that nags.

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
