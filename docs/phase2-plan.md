# Phase 2 — The brush

> Authored 2026-09-08, against Phase 1's measured numbers and against a survey
> of how Krita, MyPaint and GIMP solve the same problem. **Nothing in this plan
> has been measured yet.** Phase 1's plan earned its authority by being wrong
> four times on hardware and saying so; this one has not been through that, and
> every number below marked *predicted* should be read as a claim awaiting
> refutation. See **Stop conditions**, which is the part of this document most
> likely to matter.
>
> **Revised 2026-09-09.** Open questions 1 and 2 are answered — the repo is
> private and the graphite reference is named — and measuring that reference
> moved W6 and W7 ahead of W8 as the phase's primary mechanism. A new fact
> arrived with them: the app is intended to be sold, which tightened the licence
> rule rather than relaxing it.

## The decision

Phase 2 builds **our own brush engine**: a mask-stamp pipeline with a
sensor/curve dynamics model, rendered through `Canvas` first and through GL only
when a measurement names the thing GL fixes.

Three things follow from Phase 1's numbers, and each of them cuts a plausible
plan out of the running.

**Latency is not Phase 2's job.** W16 measured 45.2 ms pen-to-photon, of which
**35.9 ms is SurfaceFlinger and the panel** — 3.2 refreshes at 90 Hz, on a device
whose gralloc refuses `USAGE_FRONT_BUFFER`. Tightening the engine buys at most
3.2 ms; turning off the shipped smoothing buys 4.5. A phase organised around
latency would be spending its whole budget fighting for 7 ms of a 45 ms number,
against hardware that owns the other 36. So the GL rewrite — carried since
Phase 1 as recorded debt — **is not justified by latency**, and this plan does
not justify it that way. It is justified, if at all, by brush feel and by making
the wet stroke re-renderable. That is a real justification and a much smaller
one, and it is why GL is W14 here instead of W1.

**The bar is felt quality again, and it has a specific name: the pencil must
read as graphite, not as a grey pen.** That is the whole phase in one sentence.
Everything below is either a mechanism that serves it or scaffolding that lets
us tell whether it worked. Since the first draft the bar has acquired a named
reference and a measurement — see **The bar, measured** — and measuring it moved
the phase's centre of gravity from texture to translucency.

**The brush model is where the prior art is worth the most, and the render path
is where it is worth the least.** Krita's dab loop, dab cache, sensor/curve
system and indirect-painting split are twenty years of learning that transfers
directly. Krita's canvas is a `QOpenGLWidget` with no front-buffer trick — we
already measured better than that stack can offer. So: take the model, ignore
the plumbing.

### The androidx.ink re-decision — W3's debt, paid without W3

Phase 1 cut W3 (the timeboxed Ink arm) by its own cut order, leaving
`analysis.html` §04's question open: *has textured-brush support properly landed
in `BrushFamily`?* That question was billed as "a ten-minute check that could
save a month", so it was run before this plan was written rather than as a work
item.

**The check has flipped.** As of this writing:

- **androidx.ink 1.0.0 is stable** (Dec 2025); current alpha is **1.1.0-alpha07**
  (Aug 2026).
- **1.1.0-alpha03** made the programmatic brush-customisation API public and split
  `BrushPaint.TextureLayer` into specific subclasses.
- **1.1.0-alpha06** improved the protobuf representation of custom texture layers
  and added `calculateMinimumRequiredVersion` on `BrushFamily` subtypes.
- **1.1.0-alpha04** added `BrushBehavior.developerComment` (for a Brush Designer
  UI) and new `BrushPaint.ColorFunction` types.
- `TextureBitmapStore` exists; `CanvasStrokeRenderer` and `InProgressStrokesView`
  give a real low-latency path.

So the condition `analysis.html` named — *"if texture support in `BrushFamily`
has properly landed, option A stretches much further than I'm crediting it and
could carry v1 alone"* — is **met on its own terms**. The honest thing is to say
so plainly before saying why we are still not taking it.

**We are still not taking it, for three reasons that Phase 1 supplied and the
check does not touch:**

1. **It replaces the document model, not the brush.** Ink's canvas is a list of
   immutable `Stroke` objects re-rendered each frame. Phase 1 shipped one
   `ARGB_8888` bitmap layer written once per stroke, and the export, the
   `ACTION_CANCEL` guarantee, the single-writer lock and Phase 3's undo all hang
   off that. Adopting Ink is not swapping a brush; it is discarding W6, W8, W10,
   W13 and W14 and rebuilding on a different memory model.
2. **There is no latency win to be had.** `InProgressStrokesView` sits on the same
   `androidx.graphics` stack we already ship, on the same device with no front
   buffer. It inherits the same measured 36 ms. The one axis where a library
   could beat us is the one axis where it cannot.
3. **Custom brush authoring is a gzipped protobuf behind
   `@OptIn(ExperimentalInkCustomBrushApi::class)`,** loaded via
   `BrushFamily.decode(inputStream)`. The public guides still do not document
   `BrushCoat`, `BrushTip` parameters, or `BrushBehavior` mappings. So the price
   of Ink's textured brushes is authoring them through an undocumented
   experimental API and shipping them as opaque binaries — which is strictly
   worse for iteration than a brush model we can read, diff and edit.

**The kill criterion survives, and is now attached to a work item.** If W8 and
W10 land and the pencil still reads as a grey pen, that is real evidence that
this engine cannot get there, and the correct response is to fall back to Ink for
v1 rather than sink more weeks into it. See **Stop conditions**.

### What this choice costs, stated plainly

- **We write and maintain a brush engine.** Krita has a dozen; we will have two
  and it will still be the largest single piece of code in the project.
- **We inherit the texture-authoring problem** — though it shrank once the
  reference was measured. Ink ships stock textures and we do not, so a grain has
  to come from somewhere we may legally sell. The reference's grain turns out to
  be stochastic rather than a repeating scanned tooth, so procedural noise is
  likely sufficient and no asset need be sourced at all. Likely, not proven.
- **We keep the 36 ms.** Nothing in this plan moves it. If the compositor term
  turns out to be the thing that ruins the feel at some future point, this plan
  has not addressed it and says so.
- **Dab goldens will move.** W3's per-dab spacing change is a deliberate,
  measured break of W7's golden corpus. Any golden diff that is *not* explained
  by that item is a defect.

## The bar, measured

Phase 2's open question 2 asked for a graphite reference to be named before W8,
so that "reads as graphite" would be falsifiable. **It is answered:**
`docs/reference/graphite-target.png` — a page of figure studies drawn by the user
in Wacom Canvas on this tablet, 1336x2096, exported with the background removed.
Committed here because the repo is private; it would not have been committed
otherwise, since it is the user's own artwork.

Naming it turned out to be worth more than a target. **Measuring it changes the
plan's priorities**, and this section is the evidence.

### What the reference actually contains

Two distinct marks, not one:

1. **A narrow contour and hatching pencil.** Strong pressure response — the same
   brush produces near-black contours and ghost-grey construction lines. Its
   edges are *ragged and granular* rather than cleanly antialiased, and there is
   visible longitudinal streaking inside a single stroke, like graphite catching
   on paper tooth. Strokes taper in and out.
2. **A broad, very low-opacity shader with flat, chisel-like ends and hard
   lateral edges.** It lays down bands whose overlaps visibly stack into tone.
   This is not a round nib — it is an elliptical or flat dab.

### The numbers, and the one that reframes the phase

Alpha distribution over every inked pixel:

```
p10   0.055        p75   0.510
p25   0.118        p90   0.737
p50   0.271        p99   0.953
                   max   1.000
fully opaque pixels:  12  out of 2,800,256   (0.0004%)
broad shader patch:   mean alpha 0.051
```

**The median inked pixel is 27% opaque, and twelve pixels in the whole drawing
are solid.** The entire mass of this distribution is in the middle.

**Phase 1's brush can only produce 0.0 or 1.0.** Our current engine sits at
exactly the two ends of a distribution that has essentially nothing at either
end. That is the sharpest available statement of what Phase 2 is for, and it
means **the primary mechanism of "graphite" is translucency and build-up, not
grain.**

The plan as first written bills W8 (texture) as "the graphite item — the whole
point", and W6/W7 (scratch buffer, opacity and flow) as the plumbing that unlocks
a slider. **That ordering is wrong and is corrected here.** W6 and W7 are the
larger half of the bar; W8 is the second half and still necessary, because the
ragged edges and internal streaks in the reference are unmistakably texture and
no amount of correct opacity produces them.

### Grain is stochastic, and that is a licensing gift

A lateral autocorrelation of a flat mid-tone shaded band finds **no periodic
peak** out to 60 px. The reference's grain is fine and stochastic, not a
repeating scanned tooth.

Two consequences. **Procedural noise is likely sufficient for W8**, which removes
almost all of the asset-licensing risk described above — no image needs to be
sourced at all. And the grain we are matching is *subtler* than a real graphite
scan, which makes W8 easier than it was scoped, not harder.

### The acceptance test W10 now has

Draw a comparable page with the shipped presets, export it, and run the same
measurement. **Targets:** inked-pixel alpha median 0.20-0.35, p90 0.65-0.80,
fully-opaque pixels under 0.01%, and a broad shading pass averaging 0.04-0.07.

This does not replace the felt judgement — W10 is still decided on the tablet, by
eye, in the user's words, the way W1 and W16 were. It replaces the situation
where a disagreement about whether the pencil "reads as graphite" has no evidence
either side can point at. If the numbers match and it still looks wrong, that is
a real and interesting finding; if the numbers are nowhere near, there is nothing
to discuss yet.

## What Phase 1 settled, and what it did not

Carried forward as fact, because it was measured on DTHA116:

| Fact | Value | Where |
|---|---|---|
| Pen to photons, 90 Hz, smoothing 0, prediction off | **45.2 ms** | W16 film, 4 zigzag reversals |
| ...of which SurfaceFlinger + panel | **35.9 ms** (3.2 refreshes) | W16 decomposition |
| ...of which sampling + dispatch | 5.75 ms | app `latency` readout, real pen |
| ...of which engine spline pipeline | 3.21 ms | `StrokeBuilderTest` |
| ...of which app work, `onTouchEvent` → submit | 0.31 ms | app readout |
| Full-document rotated blit, p99 | 4.0 ms against 11.1 ms | W2 |
| Wet-path per-event cost, release | p50 0.119 ms, 54.6 B/sample | W9 |
| **Dabs per input event, real fast stroke** | **230** (339 samples, 19,826 dabs) | W16 |
| Front buffer available | **No.** gralloc refuses `USAGE_FRONT_BUFFER` | W0 |
| Pen back reports | `TOOL_TYPE_FINGER` — flip-to-erase impossible | W13 |
| Barrel buttons, individually addressable | 4 / 32 / 64 | W13 |

**That 230 is the number this phase is built against, and it deserves its own
paragraph.** W9 sized the wet path against 19.5 dabs an event and the app met a
0.31 ms per-event ceiling; a real fast stroke produces an order of magnitude
more. Phase 1 got away with it because a dab is `drawCircle` and Skia is very
good at circles. **A stamped, textured, scattered dab is not a circle**, and any
per-dab cost we add is multiplied by 230, not by 20. This is the single
constraint that shapes the architecture below — it is why the mask cache is W4
and not an optimisation, and why W0 exists at all.

Still unknown, and honestly unknown:

- **Why graphics-core wins.** W1 judged it closest to the tip and the
  vsync-coalescing explanation was never tested. Phase 1 said "Phase 2 will have
  to explain it" on the assumption Phase 2 would rebuild the render path. This
  plan does not rebuild the render path by default, so **the question stays
  open** — and that is a deliberate downgrade, recorded here rather than quietly
  dropped.
- **Whether the 36 ms is refresh-quantised or fixed.** Run C was inconclusive:
  apex readings 46–48 ms against cross-correlation 30–34 ms, straddling both
  hypotheses. Unresolved, and W14's entry condition depends on it.
- **Whether `tools/latency-from-video.py` transfers between framings.** It did
  not for run C. W0 has to fix that before any Phase 2 improvement can be shown
  rather than asserted.

## Prior art, and the rule that governs it

**The rule first, and it got stricter rather than looser.** Phase 1's open
question 3 is answered: **the repo is private** (verified 2026-09-09 — it already
was, so the plan's premise was stale). And a second fact arrived with the answer:
**the app is intended to be sold, for about €1.**

The intuition is that a private repo frees us to read GPL code. It does not, and
the selling plan is why. GPL obligations trigger on **distribution**, not on
publication of a repository. Shipping a paid app *is* distribution. If the app
contained or derived from Krita's or GIMP's GPL-3.0 code, the whole app would
have to be released under GPL-3.0 with source offered to every buyer — who could
then redistribute it freely, for free. Private hosting changes nothing about
that; it only means nobody can see the problem before shipping.

So the standing rule stands and tightens:

- **Do not read Krita or GIMP source at all.** Reading and then reimplementing
  creates a derivative-work question that is expensive to disprove and impossible
  to disprove cheaply. Work from documentation, published architecture
  descriptions, and the prior-art summary in this document — which was written
  from architectural knowledge, not from a checkout.
- **libmypaint is the one possible exception and needs a real check.** It is
  believed to be permissively licensed (ISC), unlike the MyPaint *application*
  and unlike MyPaint's brush packs, which are separate works with separate terms.
  If that verifies, it is legitimately usable in a paid app with attribution —
  and it is the only legal shortcut to a mature brush model. Verify properly
  before relying on it; do not rely on this sentence.
- **Every dependency is already clean.** Kotlin, AndroidX and
  `androidx.graphics:graphics-core` are Apache-2.0: commercial use, no source
  obligation, attribution in the app's licences screen.

**Assets are now the sharper risk than code.** A paper-grain image "found online"
is very often not licensed for redistribution inside a paid app, and "free to
download" is not "free to sell". In preference order: **(a) generate the grain
procedurally** — zero licence surface, and the measurement below says this is
probably sufficient; **(b) photograph your own paper** with the tablet — free,
clean, authentic, and unambiguously yours; **(c) CC0 only**, with the source URL
and a saved copy of the licence page committed alongside the asset. Never a
"free for personal use" texture.

Two smaller consequences of selling, recorded so they are not discovered late: a
paid Play listing needs a privacy policy even for an app that collects nothing,
and the app needs an open-source-licences screen for its Apache-2.0
dependencies. Neither is Phase 2 work; both are cheap and easy to forget.

What transfers, and the judgement on each:

**1. Spacing is recomputed per dab. — TAKE (W3).** Krita's `KisPaintOp` returns a
`KisSpacingInformation` *after each dab*, and `KisDistanceInformation` carries the
sub-spacing remainder across event boundaries. So a stroke swelling from 2 px to
40 px automatically goes from dense dabs to sparse ones and no seam appears where
two `MotionEvent` batches meet. We have the loop shape already
(`CatmullRomResampler` → `DabEmitter.emit` returns the next step) but take
`pen.spacing` as a constant fraction of a *nominal* size. Making it a function of
the dab just laid is a small change with two payoffs: correct density under a
pressure ramp, and **fewer dabs on thick strokes**, which is the 230 problem
easing itself.

**2. The dab mask is generated once and cached. — TAKE (W4).** `KisDabCache` keys
a generated mask on (size, rotation, softness, mirror) with a tolerance, so a
0.3 px size change reuses the cached mask; everything downstream is a blit of an
8-bit alpha mask. This reframes what a brush *is*: not a draw call, but **a mask
generator plus a cache**. At 230 dabs an event, regenerating a soft mask per dab
is the whole budget; reusing one is a bitmap blit.

**3. Procedural and bitmap brushes feed one pipeline. — TAKE (W4).** Krita's
`KisMaskGenerator` family (circle, rectangle, gauss, curve-falloff) and its
bitmap brushes (`.gbr`, `.png`, `.abr`, and `.gih`'s indexed *sequence* of masks
selected per dab by random/pressure/angle/velocity) produce the same thing: an
alpha mask. One interface, two implementations, and nothing downstream knows.

**4. Sensors → response curves → every parameter. — TAKE, and it is the big one
(W1).** Krita does not have "a pressure curve". It has `KisDynamicSensor`
(pressure, tilt X/Y, tilt direction and elevation, speed, drawing angle, distance,
time, fade, fuzzy-per-dab, fuzzy-per-stroke, rotation) and `KisCurveOption`, and
*every* brush parameter — size, opacity, flow, rotation, softness, scatter,
spacing — is a curve option driven by any combination of sensors through its own
editable curve, combined by multiply or maximum. MyPaint is the same idea with a
different vocabulary: ~40 settings, each a base value plus (input → piecewise-
linear curve) mappings.

Our `RoundPen` has `pressureCurve: Float = 3f` — one hardcoded exponent, on one
parameter, and its own KDoc already calls it "the single knob that decides
whether the pen feels dead or twitchy". The prior art says: write **one**
`ResponseCurve`, **one** `Sensor` enum, **one** `CurveOption`, and then size,
opacity, hardness, scatter and rotation are all instances. It is perhaps 150
lines, it collapses five future features into one, and the brush file format
falls out of it for free.

**5. The scratch buffer is per-brush, not global. — TAKE, and it upgrades our
tripwire (W6).** Krita's `KisIndirectPaintingSupport` paints the in-progress
stroke into a `temporaryTarget()` and composites it onto the layer once at stroke
end — **but only when the paintop needs it** (opacity < 100%, wash modes, certain
blends). An opaque hard brush paints straight to the layer.

That is independent confirmation of the tripwire written into `RoundPen`'s
header, and it sharpens it. The rule was *"do not ship an opacity slider, a
translucent brush, or a second blend mode before the scratch buffer exists."* The
better rule is: **translucent brushes take the indirect path; opaque ones keep
Phase 1's fast path.** Two paths, chosen by the brush, and the fast path we
already measured stays intact for the pen.

**6. Texture is sampled in canvas space. — TAKE, and it is the graphite answer
(W8).** Krita's `KisTextureOption` multiplies dab alpha by a pattern sampled at
the dab's *canvas* position, with a strength and a cutoff range. Because it is
canvas-anchored, the grain stays fixed to the paper as the brush passes over it;
move back and forth and the same grain shows through. **Dab-space texture — grain
that slides along with the brush — looks immediately wrong**, and is the usual
first mistake. Together with per-dab scatter and size jitter, this is what makes
a pencil read as graphite. Not dab shape, not opacity.

**7. Composite at more than 8 bits. — ADAPT, and measure it (W6).** MyPaint
composites into 15-bit premultiplied tiles; Krita's internal formats are 16-bit
and up. The reason is arithmetic: a 3%-alpha pencil dab in `ARGB_8888` quantises
to nearly nothing, and two hundred of them stack into banding rather than a
smooth build. **A low-flow pencil essentially does not work in 8 bits.** Android
offers `Bitmap.Config.RGBA_F16`. It also doubles the bandwidth of a buffer we
blit every frame, on a device that is already compositor-bound — so this is
exactly the kind of claim Phase 1 taught us to measure rather than adopt. W6
allocates the scratch buffer both ways and decides with a number.

**8. Brush state with per-dab low-pass filters. — ADAPT, later.** MyPaint's
`slow_tracking` lags the brush position *inside* the dab loop as evolving state,
not upstream as an input filter. Our `Stabilizer` is upstream, which is why
smoothing shows up as the 4.5 ms lag W16 priced. Worth knowing when we tune; not
worth restructuring for now.

**9. Smudge as a one-lerp accumulator. — SKIP for Phase 2.** MyPaint samples the
canvas under each dab into an accumulator (`accum = lerp(accum, sample,
1/smudge_length)`) and blends that into the dab colour. One canvas read per dab
buys convincing wet-media pickup, and it is genuinely cheap for how good it
looks. It is also a second brush engine and a second canvas read at 230 dabs an
event. Phase 3.

**10. COW tiles are how raster undo stays affordable. — NOTE, for Phase 3.**
Krita's `KisTiledDataManager` stores layers as 64×64 copy-on-write tiles and
`KisMementoManager` snapshots only the tiles a stroke dirtied; GIMP's paint core
does the same under another name. So undo costs the stroke's bounding tiles, not
the layer. We already accumulate a per-stroke dirty `Bounds` in
`StrokeBuilder.accumulator`, which is most of the input. Recorded here so Phase 3
does not rediscover it.

**What we explicitly do not take:** Krita's dozen brush engines (deform, spray,
hairy, grid, particle — a museum; two engines cover the goal), its render stack
(we measured better), and libmypaint's flat 40-setting vocabulary
(`opaque_multiply`, `offset_by_random` — a data model that leaked its
implementation; take the structure, not the field list).

## Architecture

### The dab pipeline

Phase 1: `emit(x, y, pressure, elapsedMillis)` → `(x, y, radius)` into a
`FloatArray`, later drawn as `drawCircle`. Phase 2 keeps the array and the
single-write-per-stroke contract, and changes what a dab *is*:

```
sample → Stabilizer → CatmullRomResampler
       → for each dab position:
             evaluate CurveOptions against Sensors   (W1)  → size, opacity, hardness, rotation, scatter
             MaskCache.get(size, hardness, rotation)  (W4)  → AlphaMask, usually a hit
             stamp mask at (x + jitter, y + jitter)   (W5)
             next spacing = f(size just laid)         (W3)
```

`:engine` owns everything above the stamp: `AlphaMask` is a `ByteArray` plus
width and height, which is pure Kotlin and testable on the JVM. `:app` owns the
stamp, because that is where a `Bitmap` and a `Canvas` live. The module boundary
that Phase 1 enforced by compiler — zero Android imports in `:engine` — holds
without an exception.

New package: `engine/src/main/kotlin/be/thalos/artiest/engine/brush/`.

### The brush model

`RoundPen` becomes `Brush`, and its scalar fields become curve options:

```
Brush
  mask:     MaskSpec        (shape, aspect ratio, angle, softness falloff)
  size:     CurveOption     (min, max, sensors, curve)
  opacity:  CurveOption
  flow:     CurveOption
  hardness: CurveOption
  rotation: CurveOption     (fixed angle, or locked to stroke direction)
  scatter:  CurveOption
  spacing:  SpacingSpec     (fraction of diameter, isotropic flag, min doc px)
  texture:  TextureSpec?    (grain, strength, cutoff)  — null for the pen
  blend:    BlendMode       (SRC_OVER, or ERASE for W11)
  paintPath: DIRECT | INDIRECT     — chosen by the brush, see below
```

`RoundPen`'s existing defaults become `Brush.pen()`, and W2's acceptance test is
that the W7 dab goldens **do not move** across that refactor. They move in W3,
once, on purpose, by an amount we compute in advance and then check.

`RoundPen`'s KDoc says it carries "the *field names* the Phase 2 document format
specifies, so Phase 2 is a lift rather than a redesign". That was a good
instinct and it is now half wrong: the names transfer, the *shape* does not, and
a flat struct of scalars is exactly the model the prior art abandoned. Recorded
as a correction to Phase 1 rather than glossed.

### Two paint paths

- **DIRECT** — opaque, hard, `SRC_OVER`: dabs stamp straight into the layer, as
  Phase 1 does. Untouched, and still the pen's path.
- **INDIRECT** — anything translucent, soft-edged, textured, or erasing: dabs
  accumulate into a stroke-scoped scratch buffer with `max`-style alpha
  accumulation, and the buffer composites onto the layer **once** at stroke end,
  under the same `layerLock` and the same single-write contract W6/W10
  established. `ACTION_CANCEL` stays free: the layer never contained the stroke.

The scratch buffer is allocated to the stroke's growing bounds, not to the
document, and it is the reason opacity can finally be a slider.

### Where GL enters, and what has to be true first

GL is **W14**, it is **gated**, and its entry condition is written down so it
cannot be entered on enthusiasm: *a measurement that names the specific thing GL
fixes, taken after W10.* Candidates, in the order they are likely to fire:

- The stamp path cannot hold 230 dabs an event inside the per-event budget even
  with the mask cache warm (W5/W0 will say).
- `RGBA_F16` scratch compositing is too slow through `Canvas` but fine as an FBO
  (W6 will say).
- Canvas-space texture sampling costs a second bitmap read per dab that a shader
  would fold into one pass (W8 will say).

If none of them fires, **GL does not ship in Phase 2**, and the debt is carried
into Phase 3 with the same gate. This is a reversal of Phase 1's expectation and
it is the direct consequence of the 36 ms finding.

## Work plan

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| 0 | Before-picture: dab-loop bench (JVM + device), fix `latency-from-video.py` to find the page and set its own thresholds, re-film the W16 zigzag | tools, `:app` | Med | — | 1.5 |
| 1 | `Sensor`, `ResponseCurve`, `CurveOption` — pure JVM, with goldens | `:engine` | Low | — | 1.5 |
| 2 | `Brush` replaces `RoundPen`; `Brush.pen()` preset. **W7 goldens must not move** | `:engine` | Low | 1 | 1 |
| 3 | Per-dab spacing recompute, spacing from the dab just laid, isotropic option. **Goldens move here, once, by a predicted amount** | `:engine` | Med | 2 | 1 |
| 4 | `AlphaMask`, `MaskSpec`, procedural generators, `MaskCache` with quantised keys | `:engine` | Med | 2 | 2 |
| 5 | Stamp renderer: `drawBitmap` of an `ALPHA_8` mask with a colour filter, replacing `drawCircle`. A/B'd against Phase 1 on device | `:app` | **High** | 4 | 1.5 |
| 6 | Indirect paint path + scratch buffer; `ARGB_8888` vs `RGBA_F16` decided by measurement. **The larger half of the graphite bar** | `:app` | **High** | 5 | 2 |
| 7 | Opacity and flow as real sliders — **the tripwire is paid here**, and the reference's median 0.27 alpha becomes reachable | both | Low | 6 | 0.5 |
| 8 | Canvas-space texture: grain, strength, cutoff. **The second half of the bar** — ragged edges and streak, which opacity alone cannot make | both | Med | 6 | 2 |
| 9 | Scatter, per-dab size jitter, rotation — all through W1's machinery | `:engine` | Low | 4 | 1 |
| 10 | Three presets — pen, pencil, marker — judged on device, by eye, by the person who will use it | both | Med | 8, 9 | 1.5 |
| 11 | Eraser: barrel-button mapping (4 / 32 / 64) + a real erase blend through the indirect path | both | Med | 6 | 1 |
| 12 | Brush serialization and the on-disk format | `:engine` | Low | 2 | 1 |
| 13 | Prediction, re-tested — only now that the wet stroke is re-renderable | both | Med | 6 | 0.5 |
| 14 | **GATED.** GL engine, entered only on a measurement from 5, 6 or 8 that names what it fixes | `:app` | **High** | 5, 6, 8 | 3+ |
| 15 | Feel pass, re-film, reconcile `analysis.html` and this plan against what was measured | device, docs | Low | 10 | 1 |

**≈18.5 days if W14 does not fire, plus 3 or more if it does.** Same caveat
Phase 1's estimate earned: the work happens in sessions, not days, and the items
that run long will be the ones where this plan is wrong about the hardware.

**Cut order**, decided now while it is cheap: **W13** (prediction re-test — it is
curiosity with a number attached), then **W12** (serialization — presets can be
code until they are not), then **W14** (which is gated anyway), then **W11**'s
button mapping reduced to a toolbar toggle. **Do not cut W0** — Phase 1's
strongest lesson is that a measurement harness with no review pass produces
confident wrong answers, and run C proved the film tool is not yet trustworthy.
**Do not cut W6, W7 or W8** — between them they are the phase, and the
reference measurement says W6 and W7 are the larger half of it.

### W0 — the before-picture, and why it is first

Phase 1 ended with two instruments in doubt. The app's own readouts are sound
and were validated against the film. The film tool is not: run C failed because
its ROI and thresholds were tuned to one framing, and a closer retake left
`nibY` stuck at 136.5 and `inkTop` stuck entirely. It reported nothing, which was
the right outcome, and it means **we currently cannot prove a Phase 2 latency
improvement even if we make one.**

So W0 makes the tool find the page and set its own thresholds rather than
carrying constants from take 2, re-films the same zigzag at the same 90 Hz /
smoothing 0 / prediction off configuration, and must land within the film's
resolution of 45.2 ms. If it does not reproduce W16's number, **the tool is
wrong and gets fixed before anything else is built**, because every subsequent
claim in this phase is a difference against it.

The dab-loop bench is the other half: a JVM microbench over the W7 golden corpus
and an on-device run of the W16 zigzag trace, reporting dabs, per-dab cost, mask
cache hit rate and per-event cost. W5's high risk rating is a statement about
this bench — without it, "stamping is fast enough" is an opinion.

### W1–W2 — the dynamics model, and the refactor that must be invisible

W1 is pure `:engine` and needs no device: a `Sensor` enum (pressure, speed, tilt
where the hardware supplies it, direction, distance, time-since-down, random per
dab, random per stroke), a `ResponseCurve` over piecewise-linear control points,
and a `CurveOption` combining several sensors by multiply or maximum into a
`(min, max)` range. Everything is a `Float` in, a `Float` out, and every part of
it is golden-testable.

W2 then rewrites `RoundPen` in those terms with one hard acceptance test: **the
W7 dab goldens do not move.** A refactor of the brush model that changes the
strokes is not a refactor. `RoundPen`'s cubic `pressureCurve` becomes a
three-point curve that must reproduce `p³` to within the goldens' tolerance — and
`sizeFor`'s special case for exactly 3, which exists because it runs a few
hundred times a stroke, becomes a curve-evaluation fast path rather than a
special case in the pen.

The onset ramp (`onsetMillis`, `onsetPressure`) is a floor applied *after* the
curve and stays exactly where it is. It is wall-clock for a reason W7 spelled out
and Phase 2 does not get to relitigate.

### W3 — spacing, and the one deliberate golden break

Spacing becomes `f(diameter of the dab just laid)` with `MIN_SPACING_DOC` still
the floor. **Predicted effect, written before the change so it can be wrong:** a
constant-pressure stroke is unchanged; a stroke that ramps from light to heavy
loses dabs at the heavy end. On the W16 zigzag — 339 samples, 19,826 dabs at
size 24 — the predicted new count is *lower*, and if it comes out higher the
model is wrong and the item stops. Goldens are regenerated once, with the diff
inspected stroke by stroke and the reasoning recorded in the commit.

### W4–W5 — masks, the cache, and the phase's first real risk

The cache is the item that decides whether this architecture is viable at 230
dabs an event. It is keyed on **quantised** (size, hardness, rotation) — Krita's
tolerance idea — and the quantisation step is the tuning knob: too coarse and a
pressure ramp visibly stair-steps, too fine and the cache misses every dab and we
have made the engine slower than `drawCircle` for no gain.

**The failure mode to watch, named in advance:** a fast pressure ramp is exactly
the stroke that both misses the cache *and* produces the most dabs. If W0's bench
shows the hit rate collapsing on the ramp strokes in the golden corpus, the fix
is a small ring of nearby sizes rather than a finer step, and if that does not
work the item stops and W14's first entry condition has fired.

W5 replaces `drawCircle` with an `ALPHA_8` mask blit under a colour filter, and
A/Bs it on device against the Phase 1 path at identical settings. Phase 1's W9
budget — p50 0.119 ms an event — is the number to beat or to consciously spend.

### W6–W7 — the scratch buffer, and paying the tripwire

`RoundPen`'s header carries the tripwire in the code where it can be seen:
*"do not ship an opacity slider, a translucent brush, or a second blend mode
before the scratch buffer exists."* W6 builds the scratch buffer; W7 is where
that comment gets rewritten rather than deleted, to say what replaced it.

The F16 question is settled here, by allocating both and measuring: scratch
allocation cost, per-dab stamp cost, and end-of-stroke composite cost, at the
document sizes `DeviceProbe.documentSizeFor` actually produces. **Predicted:**
F16 is correct for the pencil and affordable because the scratch buffer is
stroke-bounds-sized, not document-sized. If it is not affordable, 8-bit with a
dithered flow accumulator is the fallback, and the pencil bar gets harder.

### W8 — texture, and the bar

Grain sampled at canvas position, multiplied into dab alpha, with strength and a
cutoff range. This is the item the phase exists for, and it is the item most
likely to need several attempts, because "reads as graphite" is a judgement and
not a threshold.

Two things it needs that are not code. **A grain image we are allowed to ship** —
photographed or generated, not borrowed, given the licence rule above. And **a
reference the user names in advance**: a photo or a screenshot they would accept
as the target, agreed before W8 starts rather than argued about after. Phase 1's
felt-quality judgements worked because the bar was stated first.

### W10 — three presets, judged the way W16 was judged

Pen (opaque, hard, direct path — Phase 1's brush, unchanged and still fast),
pencil (textured, scattered, low flow, indirect, F16, strong pressure response),
and — **changed from "marker" after measuring the reference** — a **broad chisel
shader**: elliptical dab, hard lateral edges, flat ends, very low flow, whose
whole purpose is that overlaps stack into tone. The reference contains exactly
these two drawing marks and no marker, and it is a page of figure studies, which
is the actual use case: sketching and hatching, not painting. Judged on the tablet with
the pen by the person who will use it, in the user's own words, recorded in the
plan the way W1's and W16's verdicts were.

### W15 — reconcile, including against this document

Same shape as W17: re-film, re-run W0's tool, and annotate `analysis.html` *and*
this plan in place — predictions left standing beside what actually happened,
not replaced. Every *predicted* label above is a place this document expects to
be corrected.

## Deliberately not in Phase 2

- **Smudge and colour pickup.** A second engine and a second canvas read per dab.
  Phase 3, with W0's bench to price it.
- **Undo, the layer stack, tiling, mipmaps.** Still Phase 3. W6's scratch buffer
  is stroke-scoped and is not a step toward tiling; do not let it become one.
- **Bitmap brushes, `.gbr`/`.abr` import, brush sequences.** W4's `AlphaMask`
  interface is designed so these are additions rather than rewrites. That is the
  whole investment they get.
- **A brush editor UI.** W12 ships a format; presets are edited by editing them.
- **androidx.ink.** Re-decided above, with the check run. Reopens only if W8 and
  W10 fire the stop condition.
- **Chasing the 36 ms.** Not deferred out of laziness — deferred because run C is
  inconclusive about whether it is even addressable, and because on a device with
  no front buffer it is a hardware question. W14 may touch it as a side effect;
  it is not W14's justification.
- **Explaining why graphics-core wins.** Phase 1 assigned this to Phase 2 on the
  assumption of a GL rewrite. With GL gated, the question is unassigned again,
  and pretending otherwise would be worse than saying so.

## Risks

| Risk | Signal it is happening | Response |
|---|---|---|
| Mask cache thrashes on pressure ramps | W0 bench: hit rate collapses on ramp strokes | Ring of nearby sizes; then W14 |
| Stamping cannot hold 230 dabs/event | W5 A/B: per-event cost above W9's p50 by more than 2× | W3's spacing win first, then W14 |
| F16 scratch too slow through `Canvas` | W6: composite cost visible in the frame gap | 8-bit + dithered flow, or W14 |
| Texture read doubles per-dab cost | W8: cost per dab jumps against W5's baseline | Bake grain into the cached mask per canvas cell |
| "Graphite" is not reachable on this path | W10: the user's verdict | **Stop condition — fall back to Ink for v1** |
| The film tool still does not transfer | W0: cannot reproduce 45.2 ms | Fix the tool before building anything |
| Public repo vs GPL reference material | Open question 1, still unanswered | Read-and-reimplement stands until answered |

## Stop conditions

Written as conditions, not intentions, because Phase 1's W0 fired one on day one
and it was the most valuable thing that happened.

1. **W0 cannot reproduce 45.2 ms on a fresh film of the same zigzag.** Stop. The
   instrument is wrong and nothing measured after it means anything.
2. **W3's dab count goes *up* on the W16 zigzag.** Stop. The spacing model is
   backwards and the goldens must not be regenerated to match a wrong model.
3. **W5 costs more than 2× W9's p50 per event with the cache warm.** Stop
   building features; W14's gate has opened and the next item is the engine, not
   the eighth brush parameter.
4. **W7 lands and the alpha distribution is still bimodal.** Draw a page with
   the pencil preset and measure it against `docs/reference/graphite-target.png`.
   If the inked-pixel median is not in 0.20-0.35 and fully-opaque pixels are not
   under 0.01%, the indirect path is not accumulating the way the reference
   builds, and no amount of texture in W8 will rescue it. Fix W6 before W8.
5. **W8 and W10 land and the pencil still reads as a grey pen.** This is the
   androidx.ink kill criterion from `analysis.html` §04, and it fires here. The
   response is to fall back to Ink for v1 — accepting its document model,
   rewriting export and undo around immutable strokes — rather than spending more
   weeks proving the point. Judged by the user, in the user's words, on the
   tablet, with the reference measurement beside the verdict rather than instead
   of it.

## Open questions that need a human answer

1. ~~**Does the repo stay public?**~~ **Answered 2026-09-09: private, and it
   already was.** It does *not* unlock GPL source, because the app is also
   intended to be sold — see the licence rule, which tightened rather than
   relaxed. What it does unlock is committing the reference artwork.
2. ~~**What is the graphite reference?**~~ **Answered 2026-09-09:**
   `docs/reference/graphite-target.png`. Measuring it reordered W6-W8; see
   **The bar, measured**.
3. **New, and it arrived with the answer to 1: the app is to be sold for about
   €1.** That is a change of project constraints, not just of licensing, and it
   deserves its own decision at some point — a paid app implies a Play listing, a
   privacy policy, an open-source-licences screen, a support address, and a
   quality bar set by strangers rather than by its author. None of it is Phase 2
   work. All of it is cheaper to plan for now than to discover at submission.
4. **Is 61 Hz still out of scope?** Phase 1's question 1 was never answered
   either. It matters more now: dab spacing, scatter randomness and flow build-up
   all interact with the sample rate, and tuning them at 90 Hz and 321.75 Hz
   makes them wrong at 61 Hz and 246.85 Hz.
5. **Own brush format, or aim at an existing one?** W12 can ship a format we
   define in an afternoon, or target something interchangeable. Only worth the
   second if brushes are ever meant to be shared.
