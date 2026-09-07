# Phase 1 — Minimum ink

> Reconciled 2026-09-07 against what was actually measured on DTHA116, and
> against 13 serious refutations raised by adversarial review of the first
> draft. See **What changed in reconciliation** at the end for the delta.

## The decision

Build Phase 1 as **two Gradle modules**: `:engine`, a pure-Kotlin JVM module
holding every stroke decision that can be tested without a device, and `:app`,
the single Android module holding everything that touches a `Canvas`, a
`Surface` or a `MotionEvent`. The document is **one ARGB_8888 `Bitmap` at
2160×3300**, wet ink goes through `CanvasFrontBufferedRenderer` over a plain
`SurfaceView`, and the layer is written **exactly once per stroke, on the render
thread, inside `onDrawMultiBufferedLayer`** — never incrementally, never from
the UI thread.

That single call determines everything else: it makes `ACTION_CANCEL` free (the
layer never contained the stroke, so there is nothing to roll back), it gives
one writer and one lock, and it deletes an entire tile-backup subsystem.

The front-buffered path is **chosen, not assumed**. It was compared against the
baseline on hardware and won clearly. What remains unverified is not whether it
helps but *why* — specifically whether this device grants a true front buffer at
all, which W0 settles in half a day before anything is built on it.

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
- **Front buffer beats baseline, by eye, clearly.** Prediction and unbuffered
  dispatch both made it *worse*. See "The A/B result" below — this is the
  architectural verdict Phase 1 rests on.
- Memory: `memoryClass` 256 / `largeMemoryClass` 512, but on API 34 bitmap pixels
  are native-heap allocations, so neither number binds. **Do not set
  `android:largeHeap`.**

**Not settled:**

- **Whether this device actually grants `USAGE_FRONT_BUFFER`.** graphics-core
  creates a SurfaceControl named `FrontBufferedLayer` *unconditionally* and
  silently degrades to an ordinary buffer if gralloc refuses the flag — the
  layer keeps its name either way. Observing `FrontBufferedLayer` in logcat or
  `dumpsys` therefore proves nothing. Nothing has ever called
  `HardwareBuffer.isSupported`. **This is the single fact the phase turns on**
  and it is one line of code.
- **No absolute latency number exists.** The A/B verdict is comparative and
  visual. Deliberately deferred: the number is worth more measured against
  Phase 1's real ink than against the spike's `drawLine` segments, since it is
  Phase 2's stamp engine that will threaten it.
- Whether a mid-stroke transform change actually tears the front buffer —
  inferred from `javap` on `SingleBufferedCanvasRenderer`, never observed.
- Whether a full commit transaction per gesture frame sustains 60–90 Hz on this
  SoC, and whether the ~27 MiB layer re-upload really costs ~3.4 ms. That figure
  implies 8.0 GB/s of sustained bandwidth, which is **asserted, not measured** —
  nothing in Phase 0 measured this SoC's memory system.
- Whether the front-buffered layer is composited by the display controller
  (`DEVICE`/`SOLID`) or falls back to GPU composition (`CLIENT`). If `CLIENT`,
  the latency win is smaller than assumed and the reason is worth knowing.
- Whether `MotionPredictor.isPredictionAvailable` returns true for this pen, or
  `SystemMotionEventPredictor` silently falls back to the bundled Kalman
  predictor.
- Total device RAM. `DeviceProbe` never calls `getMemoryInfo()`, so the doc's
  8 GB / 1.5 GB texture budget is an assumption.

### The A/B result

Front buffer on, everything else off, is the best configuration on this
hardware. Two qualifications the number-free method cannot resolve, both of
which shape work items rather than the decision:

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

**Render path.** `InkSurfaceView` is a bare `SurfaceView` owning a
`CanvasFrontBufferedRenderer<DabBatch>`, constructed in `onAttachedToWindow`,
`release(true)` in `onDetachedFromWindow` — the spike's lifecycle,
javap-verified against the 1.0.4 aar.

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

- **`onDrawFrontBufferedLayer(canvas, w, h, batch)`** — the library invokes this
  **N times on one `RecordingCanvas` with no `save()`/`restore()` between
  invocations**, so a bare `canvas.concat(m)` compounds: the second batch in a
  flush draws at M², the third at M³. The body must be
  `val s = canvas.save(); canvas.concat(frozenDocToView); …drawDabs…;
  canvas.restoreToCount(s)`. `save`/`restoreToCount` allocate nothing, so the
  zero-allocation budget survives.
- **`onDrawMultiBufferedLayer(canvas, w, h, params)`** — ignores `params`
  entirely and reproduces the scene from app-held state. It (1) takes
  `pendingStroke` via `AtomicReference.getAndSet(null)` and rasterizes its dabs
  into the layer `Bitmap` under `layerLock`; (2) `drawColor(paperWhite)`;
  (3) `concat(docToView)`; (4) `drawBitmap(layer, 0f, 0f, filterPaint)` where
  `filterPaint` has `isFilterBitmap = true` and `isAntiAlias = false` — **a null
  Paint means point sampling**, which would nearest-neighbour-resample the whole
  document at every zoom and rotation and make fine ink crawl and shimmer.
  It never posts to or blocks on the main thread: the synchronous
  `surfaceRedrawNeeded` path awaits it on an untimed `CountDownLatch`.

  **The app must register its own `SurfaceHolder.Callback`** and call
  `redrawDry()` from `surfaceChanged`, after the library's own callback has
  rebuilt its SurfaceControls. The library does *not* redraw from
  `surfaceChanged` — it only tears down and rebuilds — so without this the
  canvas goes blank on rotation or resize.

`LowLatencyCanvasView` is **rejected**, and the reason goes in a comment so
Phase 2 does not reopen it: its internal scene `Bitmap` is *view*-sized, so it
cannot represent a 2160×3300 document you pan around, and its `onDraw` calls
`canvas.setMatrix` (replace, not concat). GL is **deferred to Phase 2** and
recorded as debt: Phase 1's entire render is antialiased circles plus one
matrixed bitmap blit, both of which hardware Canvas already does on the GPU.

**Threading contract, stated once and enforced:** the layer `Bitmap` is written
on the library's `CanvasRenderThread` only, only inside
`onDrawMultiBufferedLayer`, under `layerLock`. `PngExporter` takes the same lock
to read. The UI thread never touches it. `commit()` does **not** establish a
happens-before edge — the library invokes that callback from paths no app
`commit()` precedes.

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
GPU-side copy of the layer, the front buffer, the multi-buffered pool, and the
app window's own buffers — on a device whose total RAM has never been measured.
`DeviceProbe` gains `ActivityManager.getMemoryInfo()` in W0 and the budget is
restated against a real number before W7 fixes the default document size.

## The front buffer and the canvas transform

This is the crux, and the mechanism is specific.

`SingleBufferedCanvasRenderer$DrawParamRequest.onExecute` calls
`mRenderNode.beginRecording()`, iterates **only `mPendingParams`** — the params
added since the last flush — invokes your callback for each, clears the list,
`endRecording()`, and draws that RenderNode into the **same preserved
`HardwareBuffer`**. Pixels already in the front buffer are never re-recorded and
never re-transformed. The only APIs that touch existing content are `clear()`
and `commit()`, and both discard the whole thing. There is no invalidate, no
partial clear, no re-transform.

**A correction to the premise inherited from the spike's comments and from
`docs/analysis.html`:** front-buffered rendering does *not* "skip the
compositor". `CanvasFrontBufferedRenderer` builds and commits a full
`SurfaceControl` transaction on **every** front-buffered render — SurfaceFlinger
is in the loop for every wet-ink update. Whatever latency win exists comes from
the layer being scanned out directly rather than from bypassing composition.
This matters because it changes what "it didn't help" would mean, and because it
is the mechanism behind the unbuffered-dispatch regression above.

Two consequences, and they are the two hardest facts in Phase 1:

**1. A transform change mid-stroke renders one stroke at two transforms
simultaneously.** Zoom 1.0×→2.0× with the pen down and the already-baked half
stays at the old scale and old screen position while every subsequent dab lands
at the new one. Rotate and the old pixels keep their old orientation.
`SurfaceControlCompat.Transaction` cannot rescue it — it has `setScale`/
`setPosition` and eight discrete `setBufferTransform` cases, but no `setMatrix`,
so arbitrary rotation is not expressible at the compositor.

The rule that follows is absolute: **the transform is frozen for the lifetime of
a wet stroke.** `InkSurfaceView` snapshots `docToView` into a field at
`ACTION_DOWN` and both callbacks concat that same snapshot; the render thread
cannot read the live `CanvasTransform` at all.

Enforcement is **strict mutual exclusion in `InputRouter`**, not a handoff: while
a pen stroke is live, finger pointers are dropped entirely — they do not queue,
do not start a gesture, and cannot pan the canvas out from under the pen; while
a gesture is live, a pen `ACTION_DOWN` is ignored until all fingers lift. This
beats "commit the stroke, then start the gesture" because that races an
in-flight asynchronous commit transaction against the matrix unlock, and because
a palm landing mid-stroke would otherwise chop the line in two and pan the
canvas.

**This is also the palm-rejection story**, and it has to be, because
`AXIS_DISTANCE` is dead: rejection is by tool type and by stroke exclusivity,
not by hover height. Note the sharp edge — the pen's back reports
`TOOL_TYPE_FINGER`, so it lands in the rejected bucket. That is the correct
outcome (it is not an eraser) but it must be a deliberate decision in the router
rather than an accident.

During a gesture there is no wet ink by construction, so the front buffer is
empty and only the dry layer moves: one `renderMultiBufferedLayer(emptyList())`
per **Choreographer frame with coalescing**, at most one in flight — not one per
touch event, which at 250–320 Hz would be a full commit transaction per event.
At pen-up, always `commit()`, never `renderMultiBufferedLayer` — only `commit()`
increments the counter that defers concurrent front-buffer renders until the
transaction lands.

**The clear button needs three calls, not one.** `renderer.clear()` blanks the
multi-buffered layer **without ever invoking `onDrawMultiBufferedLayer`** — it
records a hard `BlendMode.CLEAR` into the library's own RenderNode and presents
it. So clearing is: blank the app's layer `Bitmap` under `layerLock`, then
`renderer.clear()`, then `renderer.renderMultiBufferedLayer(emptyList())` to
repaint paper white and the now-empty layer. Never `commit()` — the spike's
`clear()` calls `commit()`, which hands `ParamQueue.release()` back to the
callback and resurrects the in-flight segments.

**2. Predicted ink is unretractable.** The same mechanism means a speculative dab
stamped at frame N is baked until pen-up. "Predicted dabs go to the front buffer
only, so the commit path drops them for free" is true of the *layer* and false
of the *screen*.

`PredictionGate` (pure, in `:engine`) therefore gates on **curvature, not
distance**: it suppresses prediction outright when the heading change between the
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
| 0 | **Front-buffer reality probe**: `HardwareBuffer.isSupported`, HWC composition type, `getMemoryInfo()`; fix `clear()` in the spike | `:spike` | **High** | — | 0.5 |
| 1 | Timeboxed androidx.ink 1.1.0-alpha07 arm, hard stop at one day | `:spike` | Low | 0 | 1.0 |
| 2 | Hardware-confirm the mid-stroke transform tear (live scale ramp, filmed). Freeze `:spike` after this | `:spike` | **High** | 0 | 0.5 |
| 3 | `:engine` + `:app` modules, test source sets, catalog `kotlin-jvm` alias | build | Low | 0 | 0.75 |
| 4 | `PenSample`, `MotionEvents.collectSamples`, `InputRouter`, `TraceRecorder`/`TracePlayer` | both | Low | 3 | 1.5 |
| 5 | `CanvasTransform` + native JVM tests | `:engine` | Low | 3 | 0.75 |
| 6 | `Document`, `Layer`, `Stroke` | `:app` | Low | 3 | 0.5 |
| 7 | `Stabilizer`, `RoundPen`, `CatmullRomResampler`, `StrokeBuilder` + dab-list goldens | `:engine` | Medium | 4, 5 | 1.5 |
| 8 | `InkSurface` + `InkSurfaceView` front-buffered wiring, `DabBatchPool`, own `SurfaceHolder.Callback` | `:app` | **High** | 6, 7 | 1.0 |
| 9 | Wet ink end to end, allocation trace, batch-pool slot validation | `:app` | Medium | 8 | 0.75 |
| 10 | Commit: stroke becomes dry ink; **measure the pen-up layer re-upload** | `:app` | Medium | 9 | 0.75 |
| 11 | **Blit-throughput probe**: 2160×3300 through a rotating matrix at Choreographer cadence, report achieved frame time | `:app` | **High** | 8 | 0.5 |
| 12 | `Predictor` — `Source.PREDICTED`, curvature gate, forked stabilizer state, runtime toggle | both | Medium | 10 | 0.75 |
| 13 | `GestureController` — pan/zoom/rotate, Choreographer-coalesced dry redraw | `:app` | **High** | 10, 11 | 1.5 |
| 14 | Cancellation and palm rejection (`ACTION_CANCEL`, `FLAG_CANCELED`, fingers and pen-back never draw) | `:app` | Low | 13 | 0.5 |
| 15 | `PngExporter` | `:app` | Low | 10 | 0.75 |
| 16 | `MainActivity`, Compose chrome, refresh-rate toggle, `DeviceProbe` port | `:app` | Low | 12, 14, 15 | 1.0 |
| 17 | Feel pass on device; **film at 240 fps and record the Phase 1 latency baseline** | device | Medium | 16 | 1.5 |
| 18 | Reconcile `docs/analysis.html` with what was measured | docs | Low | 17 | 0.5 |

**≈15.25 days**, down from the first draft's 17 because the front-buffer A/B is
already decided. Still over a 10–15 day budget, and I would rather say so than
discover it in week three. If it runs long, cut in this order: W1 (the Ink arm —
it buys Phase 2's kill criterion, not Phase 1's ink), then W13's fallback ladder,
then W18 slides into Phase 2's first commit. Do **not** cut W0, W2, W11 or W17.

**W0 — how I'd know it went wrong.** Three measurements, all one-liners, all in
`DeviceProbe` and the exported JSON:

1. `HardwareBuffer.isSupported(1, 1, HardwareBuffer.RGBA_8888, 1, USAGE_FRONT_BUFFER or USAGE_GPU_COLOR_OUTPUT)`.
   **If this is false, the front buffer never existed** and every latency
   impression so far has another cause. Do not start W8; go to the stop
   condition below.
2. `adb shell dumpsys SurfaceFlinger` — confirm the `FrontBufferedLayer`'s HWC
   composition is `DEVICE` or `SOLID`, not `CLIENT`. `CLIENT` means the GPU is
   compositing it and part of the assumed win is not there.
3. `ActivityManager.getMemoryInfo()` — settles Open Question 2's memory
   arithmetic.

Layer *presence* in `dumpsys` proves nothing: both SurfaceControls are created
unconditionally regardless of whether gralloc granted the flag, and the names do
not change on fallback.

**STOP CONDITION:** if (1) is false, write `DirectSurfaceInkSurface` (plain
`SurfaceHolder`, one dedicated render thread, `lockHardwareCanvas`, driven by
input arrival) behind the same `InkSurface` interface — one day, no other work
item changes. That is why the interface exists; the second implementation is not
built speculatively.

**W2 — how I'd know it went wrong.** Add a temporary toggle to the spike that
ramps a live scale 1.0×→1.6× over a second while the pen is down, and film it.
Expected: the already-drawn half stays at the old scale, one stroke at two
transforms. If it does **not** tear, the freeze-transform invariant can be
relaxed and live zoom while drawing becomes possible — a genuine upside worth
half a day to discover. If it tears worse than predicted (a partially flushed
stroke surviving a frame past `commit()`), W10's wet-to-dry handoff needs a
slow-motion capture of its own.

**W8 — how I'd know it went wrong.** Build incrementally against the device: one
hardcoded dab, confirm it appears; then a straight drag, confirm wet ink appears
and survives commit. Gone wrong: **a blank canvas** (check for an accidental
`setBackgroundColor` first — it is the known trap, and the renderer will look
healthy in logcat while producing invisible frames); ink that vanishes on the
first rotation (means the app's own `SurfaceHolder.Callback` is missing, so
nothing redraws after `surfaceChanged`); a deadlock on rotation (means the
callback posted to or blocked on the main thread and hit the untimed
`CountDownLatch`); or ink drawn at compounding scale within a single flush
(means the missing `save()`/`restoreToCount()`).

**W11 — how I'd know it went wrong.** This exists because W13's viability rests
on an unmeasured bandwidth figure, and discovering it in W13 would be discovering
it too late. Blit a 2160×3300 bitmap through a rotating matrix into the
multi-buffered layer at Choreographer cadence and report achieved frame time over
a few hundred frames. If it cannot hold 11.1 ms, W13's design changes before
W13 is written, not after.

**W13 — how I'd know it went wrong.** A two-finger pinch that stutters or lags
the fingers. Contingency ladder: (a) Choreographer coalescing is already the
default; if it still stutters, (b) during the drag only, re-record at
Choreographer cadence with a **downscaled** layer blit, accepting a soft image
while the fingers are down and snapping to a sharp re-render on gesture end.

Note what contingency (b) is *not*: applying the delta via `SurfaceView` view
properties (`setScaleX`/`setRotation`/`setTranslation`) does not work. The pixels
the user sees are not on the `SurfaceView`'s RenderNode — both layers live on
SurfaceControls the library creates and reparents itself, so a View-level
transform moves the punched hole in the window, not the ink. Ship
double-tap-to-reset and fit-to-view regardless, so a lost canvas is always
recoverable.

## Carried over from the spike

**Reused:**

- `PenSample.kt` essentially verbatim → `:engine`, keeping the API-34
  `eventTimeNanos` path. Three edits only: pointerId support, explicit `source`
  parameter, single expansion per event.
- `PenCapture`'s two good ideas, not the class: `requestUnbufferedDispatch` on
  `ACTION_DOWN` (behind a toggle, defaulting off for the front-buffered path),
  and the `onHoverEvent` routing.
- `LowLatencyInkView`'s `CanvasFrontBufferedRenderer` lifecycle as a template,
  javap-verified against 1.0.4. **Including its comment explaining why there is
  no `setBackgroundColor`** — that comment is a fix's tombstone, not a style
  note, and deleting it re-opens a day of blank-canvas debugging.
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

`:spike` stays in `settings.gradle.kts`, frozen after W2, still independently
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
  samples, all 0.0. `docs/analysis.html:313` gets amended in W18.
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
4. **If W0 says there is no real front buffer, ship `DirectSurfaceInkSurface` and
   move on, or spend a week understanding why?** I would ship and move on — the
   bar is felt quality, not a measured architecture — but that trades away the
   Phase 2 GL-vs-Ink evidence base.

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
