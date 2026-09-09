# Phase 2 — The brush

> Authored 2026-09-08, against Phase 1's measured numbers and against a survey
> of how Krita, MyPaint and GIMP solve the same problem. **Nothing in this plan
> has been measured yet.** Phase 1's plan earned its authority by being wrong
> four times on hardware and saying so; this one has not been through that, and
> every number below marked *predicted* should be read as a claim awaiting
> refutation. See **Stop conditions**, which is the part of this document most
> likely to matter.
>
> **Revised 2026-09-09**, twice in one day, and the second revision corrected
> the first. Open questions 1 and 2 are answered: the repo is **public and free**
> (briefly private-and-for-sale, then reconsidered), and the graphite reference
> is named. Measuring that reference moved W6 and W7 ahead of W8 as the primary
> mechanism. Then the user corrected the reading of it — **the page is one pencil
> tilted, not two brushes** — which made tilt load-bearing, so tilt was probed on
> the hardware before planning around it. It works. See **The bar, measured**.

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
Committed with the user's explicit agreement, who called it "just a sketch"
when told that making the repo public would publish it.

Naming it turned out to be worth more than a target. **Measuring it changes the
plan's priorities**, and this section is the evidence.

### What the reference actually contains

**One brush, in two postures.** The first reading of this page found two marks
and inferred two presets — a hatching pencil and a broad chisel shader. **That
was wrong, and the user corrected it:** the whole page is one pencil, and the
wide bands are the *same* pencil tilted over, so the side of the lead meets more
paper. The two marks are two postures of one tool.

That correction is the single most consequential thing in this document, because
it changes what the brush model must do rather than how many presets it ships:

1. **Upright posture** — narrow, near-round dab, strong pressure response, from
   near-black contours down to ghost-grey construction lines. Edges *ragged and
   granular* rather than cleanly antialiased, with visible longitudinal streaking
   inside a single stroke, like graphite catching on paper tooth. Strokes taper.
2. **Laid-over posture** — a wide, very low-opacity band with flat ends and hard
   lateral edges, whose overlaps stack into tone. Not a round nib: an ellipse,
   widened and rotated by where the pen is pointing.

**So the dab is an ellipse whose aspect ratio and angle come from tilt**, and the
reference's coherence — the reason the page looks like one hand with one tool —
is a property we would have destroyed by shipping it as two brushes.

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

### Tilt, measured on the hardware

The one-brush correction makes tilt load-bearing, so it was probed before
planning around it — the same "ten-minute check" discipline that W3's debt
taught. **The pen reports tilt, and it reports it well enough to drive a dab.**

Kernel level, `/dev/input/event6`: `ABS_TILT_X` and `ABS_TILT_Y`, each
`min=-9000 max=9000 resolution=5730` — centidegrees, +/-90 degrees, two
independent axes, which Android folds into the `TILT` and `ORIENTATION` axes it
declares. Declared is not populated, which `AXIS_DISTANCE` proved in Phase 0, so
a live capture followed: 150 s of raw digitizer events over **10 deliberate
strokes at different tilts**, one of them sweeping tilt continuously mid-stroke.
`tools/tilt-probe.py` is the analysis and re-runs on any fresh capture.

```
ABS_TILT_X            -23.00 .. +16.00 deg      40 distinct values
ABS_TILT_Y              0.00 .. +63.00 deg      58 distinct values
declination, pen down   p5 9.98   p50 54.01   p95 63.09 deg   (n=1327)
pressure                8 .. 4630 of 8191
packet rate             240 Hz
tilt update rate        ~39 Hz effective, quantised to 1 degree
corr(declination, pressure)   -0.82
```

Four findings, three of which change the design:

- **Tilt is real, continuous, and updates within a stroke.** One captured stroke
  sweeps 13 to 63 degrees across its 311 packets. It is not latched at pen-down,
  which is what would have made the whole mechanism unusable.
- **The usable range tops out at 63 degrees, not 90.** 33% of pen-down samples
  sit at exactly 63.00 on `ABS_TILT_Y`, with a pile-up beneath it (439 samples at
  63, 154 at 61) — a ceiling, not a distribution. `ABS_TILT_X` shows no such
  pile-up, so it is a limit on the axis being exercised rather than a global
  clamp. Whether that is a driver clamp or simply the flattest a pen can
  be held and still register was not determined and does not need to be:
  **normalise the tilt curve to 0-63 degrees.** Normalising to the declared 90
  would waste a third of the input range and make the flattest posture
  unreachable.
- **Tilt is six times coarser than position, in both rate and resolution.**
  Position arrives at 240 Hz; tilt changes at about 39 Hz in 1-degree steps. A
  dab shape driven straight off it will visibly step as the hand rolls.
  **Tilt needs its own low-pass filter** — the same first-order form as
  `Stabilizer`, applied to shape rather than position, and its lag is far less
  costly than positional lag because a slightly stale dab *shape* is invisible
  where a stale dab *position* is the thing W16 spent a phase measuring.
- **Tilt and pressure are strongly anti-correlated in real use** (-0.82): laid
  over is light, upright is firm. That is exactly the reference's two postures
  showing up in the input stream, and it implies a rule.

**The rule: tilt drives shape, pressure drives amount.** Tilt sets the dab's
aspect ratio and angle; pressure sets its size and opacity. Letting both drive
size would have them fight — upright wants "big" from pressure and "narrow" from
tilt, laid-over the reverse — and the result reads as a brush with no character
in either posture. Stated here because it is the kind of thing that is obvious
once written and very easy to get wrong while tuning.

**No Phase 1 code has to change to supply this.** `PenSample` already carries
`tilt` and `orientation` as two of its ten fields, and
`MotionEvents.collectSamples` already reads `AXIS_TILT` and
`getHistoricalOrientation` for historical samples too. Phase 1 built the
plumbing and never used it. W1's sensor set gains two members and nothing else
moves.

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

**The rule, after two reversals in two days.** Phase 1 left this open. It was
briefly answered "private, and to be sold for about EUR 1", which tightened the
rule. The user then reconsidered — *"i didnt know it would be so much hassle.
Let's make it public so everyone can enjoy. I dont need the money. I need a good
drawing program."* — so the project is now **public and free**, and the rule
relaxes to roughly where Phase 1 assumed it was.

What that changes:

- **The Play-listing overhead is gone.** Privacy policy, licences screen,
  support address, a quality bar set by paying strangers — all of it was a
  consequence of selling, and none of it applies now.
- **The asset problem mostly dissolves.** A grain texture no longer has to
  survive resale, which was the strict case. It still has to be redistributable,
  so the preference order stands and for a better reason than licensing:
  **generate the grain procedurally**, because the measurement below says the
  reference's grain is stochastic and a generated one is likely closer than a
  scanned tooth would be. Then the user's own photographs. Then CC0 with the
  licence page saved beside the asset.
- **GPL is now genuinely available, and is still not worth taking.** A free,
  public app *can* be licensed GPL-3.0, which would make Krita's and GIMP's
  source legitimately readable. It is not worth it: it binds the project
  permanently, it binds anyone who ever contributes, and **we do not need their
  code** — the prior-art section was written from architectural knowledge and the
  plan it produced is complete. Trading a permanent constraint for something we
  have already worked around is a bad trade.
- **So: keep read-and-reimplement, and pick a permissive licence.** ~~MIT or
  Apache-2.0 for artiest itself.~~ **Decided 2026-09-09: Apache-2.0.** See
  `LICENSE` and `NOTICE`. The deciding argument over MIT was not freedom — they
  are equivalent there — but that this is a *stylus and rasterisation* project,
  and those are areas with live patents. Apache-2.0 carries an express patent
  grant from every contributor and terminates it for anyone who sues over the
  work; MIT is silent on patents, which means a contributor's patent claim is
  simply an open question. It also matches every dependency the project has, so
  compatibility never has to be thought about again.

Every dependency was already clean and stays clean: Kotlin, AndroidX and
`androidx.graphics:graphics-core` are Apache-2.0.

**One thing the reversal does not undo.** The reference artwork was committed
while the repo was private. Making the repo public publishes it, and git history
means a later removal would not fully retract it. The user was told this
explicitly and chose to keep it — recorded here because consent to publish
someone's own work should be traceable to a sentence they actually said, not
inferred from a general instruction about the code.

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
  mask:     MaskSpec        (shape, softness falloff)
  aspect:   CurveOption     (dab ellipse ratio — driven by TILT)
  angle:    CurveOption     (dab ellipse angle — driven by ORIENTATION)
  size:     CurveOption     (min, max, sensors, curve)
  opacity:  CurveOption
  flow:     CurveOption
  hardness: CurveOption
  rotation: CurveOption     (extra spin: fixed, random, or stroke-direction)
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
| 1 | `Sensor`, `ResponseCurve`, `CurveOption` — pure JVM, with goldens. **Tilt and orientation are required sensors**, normalised 0-63 deg, with their own low-pass filter | `:engine` | Low | — | 2 |
| 2 | `Brush` replaces `RoundPen`; `Brush.pen()` preset. **W7 goldens must not move** | `:engine` | Low | 1 | 1 |
| 3 | Per-dab spacing recompute, spacing from the dab just laid, isotropic option. **Goldens move here, once, by a predicted amount** | `:engine` | Med | 2 | 1 |
| 4 | `AlphaMask`, `MaskSpec`, procedural generators, `MaskCache` with quantised keys | `:engine` | Med | 2 | 2 |
| 5 | Stamp renderer: `drawBitmap` of an `ALPHA_8` mask with a colour filter, replacing `drawCircle`. A/B'd against Phase 1 on device | `:app` | **High** | 4 | 1.5 |
| 6 | Indirect paint path + scratch buffer; `ARGB_8888` vs `RGBA_F16` decided by measurement. **The larger half of the graphite bar** | `:app` | **High** | 5 | 2 |
| 7 | Opacity and flow as real sliders — **the tripwire is paid here**, and the reference's median 0.27 alpha becomes reachable | both | Low | 6 | 0.5 |
| 8 | Canvas-space texture: grain, strength, cutoff. **The second half of the bar** — ragged edges and streak, which opacity alone cannot make | both | Med | 6 | 2 |
| 9 | **Tilt-driven elliptical dab** — aspect from tilt, angle from orientation — plus scatter, size jitter and spin, all through W1's machinery | `:engine` | Med | 4 | 1.5 |
| 10 | Two presets — pen and the tilt-aware pencil — judged on device, by eye, by the person who will use it | both | Med | 8, 9 | 1.5 |
| 11 | Eraser: barrel-button mapping (4 / 32 / 64) + a real erase blend through the indirect path | both | Med | 6 | 1 |
| 12 | Brush serialization and the on-disk format | `:engine` | Low | 2 | 1 |
| 13 | Prediction, re-tested — only now that the wet stroke is re-renderable | both | Med | 6 | 0.5 |
| 14 | **GATED.** GL engine, entered only on a measurement from 5, 6 or 8 that names what it fixes | `:app` | **High** | 5, 6, 8 | 3+ |
| 15 | Feel pass, re-film, reconcile `analysis.html` and this plan against what was measured | device, docs | Low | 10 | 1 |
| C | **Unplanned, done 2026-09-09.** Customisable toolbar: slot model, chooser, persistence. See below | `:app` | Low | — | 0.5 |

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

### Wc — the customisable toolbar, which was not in this plan

Added to the record because it happened, not because it was foreseen. The
honest account: the camera was two hours away, W0's remaining work needs a film,
and asked what else there was the user answered with a design — *"The vision is
complete customizability: we have a empty toolbar with slots. If the user clicks
an empty slot, he is presented a menu, from which he can choose a button or
component."*

**Why it was worth doing before the brush engine rather than after.** Not
because the toolbar is urgent — there are seven controls to arrange and the
feature only compounds as there are more. Because of what it does to *those*
controls. Phase 1's toolbar was a hand-built `Row`, so W7's opacity and flow
sliders and W10's preset picker each meant editing a layout and re-deciding
where everything sits. They now mean one entry in a catalogue and one branch in
an exhaustive `when` that the compiler checks. The order matters: doing this
after W7 and W10 would have meant building the same bar twice.

**What was built.** `ToolItem` is the catalogue; `ToolbarLayout` is a fixed row
of 16 slots with items one to four slots wide, immutable, every operation
returning a new layout; `ToolbarCodec` puts it in one line of
`SharedPreferences`. Three decisions in there are worth keeping:

- **Fixed slots, not a row that grows.** A bar that reflows when you add
  something puts Export somewhere new every week, and the one thing a toolbar is
  for is that your hand knows where the button is. The bar scrolls when it is
  longer than the screen; positions stay absolute.
- **The chooser greys out what will not fit rather than rearranging to make it
  fit.** Shoving the neighbours along, or silently placing the item elsewhere,
  both move something the user did not touch.
- **Decoding never throws.** This string is written by one build and read by the
  next, so an unknown id, a stale slot count and an overlapping pair are all
  normal, and none of them may be a crash into a blank screen. Unreadable
  becomes the default; unusable *entries* are dropped and the rest survives.

**Twenty-three JVM tests, and that is the point of the split.** Everything about
where an item may go is decidable without a device and is tested; only how it
looks is not. It was written and proved correct while the tablet was
unreachable, and needed one build to confirm on hardware.

**One concession to reality, stated rather than hidden.** The design says
long-press a filled slot to change it. A `Slider` consumes presses, so a
long-press over the size slider never reaches the toolbar, and a bar where the
gesture works on buttons and silently fails on sliders is worse than one where
it never works. So filled slots are edited through an **Arrange** toggle at the
end of the bar, and empty slots keep the direct tap the design asks for, because
they have nothing inside competing for it.

**The default bar is empty**, as asked. That has a real cost — a fresh install
cannot draw in colour until a slot is filled, so the whole feature rests on an
empty slot reading as *tap me*. `ToolbarLayout.STARTER` is the populated bar
kept beside it, and switching the default to it is one word.

**What is deliberately absent.** The user's target catalogue is undo, redo,
eraser, pen, pencil, marker, layers, colour wheel, document history. None of
them is in the chooser, because a chooser full of buttons that do nothing is
worse than a short one: the user cannot tell a control they have not understood
from one that was never wired. Each is recorded in `ToolItem`'s KDoc against the
work item that unlocks it — W10 for presets, W11 for the eraser, Phase 3 for
undo and layers, Phase 4 for the colour wheel — so it is visible that they were
scoped, not forgotten. Zoom in and zoom out shipped, because
`CanvasTransform.zoomedAbout` already existed and they are two lines.

**Marker is a question, not a backlog item.** The plan deleted it when the
reference turned out to be one pencil at two tilts. It comes back only if it is
wanted for its own sake, and that is the user's call rather than a gap to fill.

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

### W0 — IN PROGRESS. What the before-picture found

**The dab-loop bench is done and the corpus is now shared.** `StrokeCorpus` was
lifted verbatim out of `DabGoldenTest` so the goldens and the bench read the same
eight strokes — a bench with its own fixture would drift from the goldens
silently and be evidence for nothing. The goldens did not move, which is what
makes "verbatim" a checked claim rather than an intention.
`./gradlew :engine:benchDabLoop`:

```
stroke      samples    dabs  dabs/smp     add us   ns/dab    B/smp
straight         48     516     10.75      12.47     24.2      0.7
arc              72     704      9.78      16.17     23.0      0.4
onset            40     226      5.65       4.86     21.5      0.8
flick            36     913     25.36      22.49     24.6      0.9
taper            60     456      7.60      11.07     24.3      0.5
dwell            52     530     10.19      10.96     20.7      0.6
corner           44     794     18.05      18.85     23.7      0.7
TOTAL           353    4140     11.73      96.92     23.4      0.7
```

**23.4 ns a dab and essentially no allocation.** Two things follow. The
0.7 B/sample also explains W9's 54.6 B/event on the tablet: that is the
`PenSample` allocation itself at ~56 bytes, so the dab loop adds nothing to it —
a Phase 1 design claim that had never actually been separated out. And 230 dabs
an event costs about 5 microseconds of engine arithmetic here; even allowing a
large factor for the tablet's CPU, **the dab loop is not what fills the 0.31 ms
per-event budget.** The risk in W5 is the rasterisation, not the maths, which is
what W5's risk rating already said and now has a number behind it.

**The bench is a `main`, not a test.** Counts are asserted by the goldens; times
are only reported. A timing assertion in `:engine:test` fails on a loaded laptop,
gets its tolerance widened until it cannot fail, and then reports nothing — W2's
lesson in a new place.

**The film tool turned out to have a worse problem than the framing bug it was
opened for, and this is the important part of W0.** Run C's failure was blamed on
an ROI tuned to one take. Fixing that turned up the real defect: **the answer
depends on the ink threshold, and the tool never said so.** Swept across `DARK`
on take 2's own clip, at the original hand-tuned window:

```
DARK   0.30   0.35   0.40   0.45   0.50   0.55   0.60   0.65   0.70
med    44.2   49.2   44.3   44.6   45.2    --     --     --     --
                                          negative and 115 ms readings,
                                          mixed in with plausible ones
```

Two findings, and the second is worse than the first. **45.2 ms was one sample of
a distribution**, quoted to a precision the method does not have; the stable band
says 44-49 ms. And **outside that band the tool produced nonsense without
failing** — an apex reading of -1.9 ms is ink arriving before the pen, which is
impossible, and the median absorbed it into a number that still looked like an
answer. That is the single most dangerous property an instrument can have, and it
is what run C actually hit.

So the tool no longer picks a threshold. It sweeps, rejects any pass failing a
*physical* check — no negative latency, nothing beyond a dozen refreshes, at
least three apexes, apexes agreeing to within about a refresh and a half — and
reports the consensus of the survivors with their range.

**On take 2, from the automatically found page, it reports 44.3 ms** (apexes
45.5, 43.6, 45.1, 43.4), against the 45.2 published from a hand-tuned ROI. The
page detection transfers — that part worked — and the two agree well inside the
method's spread.

**But only one of nine thresholds survived the gates**, so that 44.3 is a single
pass rather than the consensus the design intends, and its printed range of
44.3-44.3 overstates what it knows. A prototype using the original's unscaled
tolerances accepted three thresholds and agreed at 44.1-44.5, so the cause is
most likely the scaled `APEX_DROP_W` and settle tolerances being slightly wrong
for this stroke width rather than anything deeper — but that is a hypothesis and
is written here as one. **Tuning those against take 2 and run B, so that a
healthy clip passes several thresholds, is the first thing to finish in W0.**

Three further changes came out of it, one of them a regression this work
introduced and caught:

- **Per-frame thresholding is load-bearing.** Replacing it with one calibrated
  level broke the tool badly: the phone auto-exposes, the page drifts several
  grey levels as the hand crosses it, and a fixed level lets the pen leak into
  the ink mask exactly at the apex. That put a three-frame spike into the
  ink-top series — which `smooth5` cannot remove, being a median of five — and
  the settle walk locked onto the spike and reported ink before pen. Caught only
  because take 2 has a known answer to disagree with.
- **The settle walk could stop a whole step above the plateau.** The last frames
  of a climb read 238, 237, 236 and the tolerance is the same size as those
  steps, so which frame it stopped on was decided by one pixel and moved the
  answer by two frames. It now takes the flat run's own level and re-enters from
  the first frame that reaches it.
- **Morphology is separable** — bit-identical, six times faster — and each frame
  is decoded once for the whole sweep rather than once per threshold.

**What is not done, and is the next thing.** The analysis window is still passed
by hand. The obvious automatic rule — the span over which the ink area grows —
does not work, because during the run-up the pen and hand enter the page and are
counted as ink; on take 2 it reports the ink at 97% of final area by frame 31,
when the stroke is drawn between about 130 and 262. Feeding that window to the
frame-rate calibration pinned its search at the lower bound and silently rescaled
every reading by a third. **That failure is now a hard error rather than a wrong
number**, which is the part worth having landed. The fix is probably to take the
window from the *nib track* — the drawing period is where the nib oscillates —
rather than from the ink area, and it is left rather than guessed at.

**The re-film has not happened**, so W0 is not closed and the plan's stop
condition 1 has not been tested against fresh footage. What has changed is that
there is now an instrument worth pointing at it.

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

**Two presets and an eraser, down from three presets.** Pen (opaque, hard,
direct path — Phase 1's brush, unchanged and still fast) and **the pencil**,
which is the phase. The third slot is deleted rather than filled: the first draft
specced a marker, the second a broad chisel shader, and both were the same
mistake — inventing a second tool to do what one tilted pencil does. The
reference is a page of figure studies made with one pencil, so the use case is
sketching and hatching, and a preset list longer than the toolset is a plan
describing itself rather than the drawing.

The pencil is: elliptical dab whose ratio and angle follow tilt, size and opacity
following pressure, textured, scattered, low flow, indirect path, F16 scratch. It
has to cover both of the reference's postures without a mode switch, and W10's
judgement is specifically whether it does. Judged on the tablet with
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
| Public repo vs GPL reference material | ~~Open question 1~~ **Closed:** Apache-2.0, so GPL source stays unreadable | Read-and-reimplement is now permanent, not provisional |

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
4. **W9's tilt response steps visibly under a rolling hand.** The input is
   1-degree quantised at ~39 Hz against 240 Hz position. If the shape filter does
   not hide that, the elliptical dab is worse than no elliptical dab, and the
   pencil should ship round until it is fixed rather than shipping jittery.
5. **W7 lands and the alpha distribution is still bimodal.** Draw a page with
   the pencil preset and measure it against `docs/reference/graphite-target.png`.
   If the inked-pixel median is not in 0.20-0.35 and fully-opaque pixels are not
   under 0.01%, the indirect path is not accumulating the way the reference
   builds, and no amount of texture in W8 will rescue it. Fix W6 before W8.
6. **W8 and W10 land and the pencil still reads as a grey pen.** This is the
   androidx.ink kill criterion from `analysis.html` §04, and it fires here. The
   response is to fall back to Ink for v1 — accepting its document model,
   rewriting export and undo around immutable strokes — rather than spending more
   weeks proving the point. Judged by the user, in the user's words, on the
   tablet, with the reference measurement beside the verdict rather than instead
   of it.

## Open questions that need a human answer

1. ~~**Does the repo stay public?**~~ **Answered 2026-09-09, twice.** Briefly
   "private and sold for EUR 1"; then reconsidered to **public and free**, which
   is where it now stands. See the licence rule for what each reversal changed.
   The narrower question it exposed — **which licence?** — is **answered
   2026-09-09: Apache-2.0**, in `LICENSE`, with `NOTICE` covering the reference
   artwork and the read-and-reimplement rule. GPL-3.0 was available and was not
   worth its permanence.
2. ~~**What is the graphite reference?**~~ **Answered 2026-09-09:**
   `docs/reference/graphite-target.png`. Measuring it reordered W6-W8; see
   **The bar, measured**.
3. ~~**The app is to be sold for about €1.**~~ **Withdrawn the same day**, and
   with it the Play-listing overhead. Kept in the record because the reasoning it
   forced — that a private repo does not unlock GPL code, because distribution
   rather than publication is what triggers it — stays true and would otherwise
   have to be rediscovered.
4. **Is 61 Hz still out of scope?** Phase 1's question 1 was never answered
   either. It matters more now: dab spacing, scatter randomness and flow build-up
   all interact with the sample rate, and tuning them at 90 Hz and 321.75 Hz
   makes them wrong at 61 Hz and 246.85 Hz.
5. **Own brush format, or aim at an existing one?** W12 can ship a format we
   define in an afternoon, or target something interchangeable. Only worth the
   second if brushes are ever meant to be shared.
