# The big nib — why one airbrush dab costs three and a half milliseconds

> Authored 2026-09-13 against `af5c4ec`, after a report that an airbrush stroke
> takes about a second to appear. **This one is measured**, not predicted:
> `app/src/test/kotlin/be/thalos/artiest/ink/BigNibBench.kt` is the evidence and
> every number below comes out of it. The numbers are **host** numbers on
> software Skia — the same arrangement `CompositeBench` uses — so the absolute
> scale belongs to this machine and not to the tablet. **The ratios are the part
> to trust**, because every row is the same kind of work on the same CPU, and
> Bn0 exists to put the device multiplier under them.

## The finding, in one line

> **A 600 px dab is not expensive because it is large. It is expensive because
> it is large *and* lands on a fractional pixel.** The same dab, blitted at a
> whole-pixel offset, is twenty-four times cheaper, and nothing about it looks
> different.

## What the airbrush actually is

`app/src/main/assets/brushes/airbrush-soft.brush`, imported from Krita's CC-0
bundle by `tools/krita-brushes.py`:

```
size 600 600        <- diameter in document pixels, min and max, so it is constant
spacing 0.1         <- a dab every 60 document pixels
hardness 0.4        <- a soft rim, so `indirectNeeded()` is true and it goes
                       through ScratchLayer
```

Three consequences follow from those three lines, and all three are load-bearing:

1. **One dab writes 602 x 602 = 362,404 pixels.** The ink pen's dab writes 49.
   The page itself is 7.1 Mpx, so a single airbrush dab is 5% of the drawing.
2. **Every covered pixel is written about ten times.** Diameter over spacing is
   `1 / 0.1`, so ten dabs overlap at any point along the stroke. That is what a
   soft airbrush build-up *is*, and it is not a defect — but it means the work
   is ten times the area the stroke visibly covers.
3. **It takes the indirect path.** A soft rim is a translucent rim, so
   `InkSurfaceView.indirectNeeded()` sends it through `ScratchLayer`, which also
   means `drawPredictedTail` refuses to run — the readout's
   `predictSuppressedIndirect` counter. There is no speculation hiding the
   latency for this brush.

## What a stroke costs, nib by nib

Twelve `VectorStress` strokes through the real commit path — `StrokeBuilder`,
`ScratchLayer.begin`, `DabRasterizer.drawDry`, `compositeInto` — with the mask
cache warm:

| nib | diameter | hardness | dabs | mask | blit ms | scratch ms | composite ms | **per stroke** | **µs/dab** |
|---|---|---|---|---|---|---|---|---|---|
| `ink-2-fineliner` | 5 | 0.02 | 10318 | 7 px | 48.4 | 3.0 | 1.1 | 4.4 ms | **5** |
| `pencil-1-hard` | 10 | 0.85 | 3155 | 12 px | 18.9 | 1.9 | 0.8 | 1.8 ms | **6** |
| `chalk-details` | 60 | 1.00 | 3032 | 60 px | 21.2 | 2.5 | 1.2 | 2.1 ms | **7** |
| `bristles-3-large-smooth` | 110 | 1.00 | 1699 | 111 px | 17.6 | 2.5 | 1.2 | 1.8 ms | **10** |
| `airbrush-soft` | 600 | 0.40 | 91 | 602 px | **313.0** | 6.4 | 5.5 | **27.1 ms** | **3440** |

**The first four rows move by a factor of two between runs and the last one does
not**, which is worth saying before anything is read off them. At two to ten
microseconds a dab the small nibs are measuring the JIT and this laptop's
schedulers as much as Skia; the airbrush row lands within 2% every time, because
three and a half milliseconds is long enough that nothing else in the machine is
visible inside it. Treat the small-nib columns as an order of magnitude and the
airbrush column as a number.

The airbrush lays **the fewest dabs of any nib in the set** — ninety-one against
the fineliner's ten thousand — and spends six times as long doing it. Against the
chalk, which is the nearest comparison because it is also a stamped mask: the
area is a hundred times larger and the cost per dab is **five hundred times**
larger. The gap between 100 and 500 is the whole of this document.

### Why it is worse than its own area

| how one 600 px soft dab is drawn | ms per dab | against today |
|---|---|---|
| `ALPHA_8` mask, `isFilterBitmap = true`, fractional offset — **today** | **3.887** | 1x |
| `ALPHA_8` mask, point-sampled, fractional offset | 0.797 | 4.9x |
| `ALPHA_8` mask, **whole-pixel offset** (filter flag irrelevant) | **0.164** | **23.8x** |
| `ARGB_8888` pre-coloured mask, whole-pixel offset | 0.136 | 28.6x |
| `RadialGradient` + `drawCircle`, no bitmap at all | 1.130 | 3.4x |
| a quarter-resolution scratch buffer, whole-pixel offset | 0.011 | 355x (+3.3 ms once) |

Skia has a fast path for a bitmap blit that is a pure integer translate at 1:1,
and it takes it **whether or not `isFilterBitmap` is set** — the flag stops
mattering when there is nothing to interpolate. A fractional offset leaves that
path and every destination pixel becomes a bilinear fetch. At 7 px of mask that
is invisible in the totals; at 602 px it is the totals.

`DabRasterizer` sets `isFilterBitmap = true` for a stated and correct reason:

> Dabs are spaced an eighth of a diameter apart, so snapping each to the pixel
> grid puts a visible ripple along a slow diagonal.

That reasoning is right, and it is right **because the dab is small**. At the
default eighth-of-a-diameter spacing on a 10 px pencil the dabs are 1.25 px
apart and half a pixel of placement error is 40% of the gap — a staircase. On
the airbrush the dabs are 60 px apart and half a pixel is 0.8% of the gap, over
a rim that falls off across 180 px. The argument inverts with size and nobody
noticed, because until Wb8 shipped sixteen imported presets there was no nib
anywhere near this size.

### The arithmetic that reproduces the symptom

A sweep 1500 document pixels long lays 25 dabs. At 3.89 ms each that is **98 ms
on this desktop**, before the scratch buffer, the composite, or the per-batch
stack recomposite the wet pass does on the front buffer.

The frame budget is 11.1 ms at 90 Hz, so today's path affords **two and a half
airbrush dabs per frame on a desktop CPU**. The pen reports at 321.75 Hz — 3.6
samples a frame — and at any hand speed above about 5400 document pixels per
second the stroke produces more than one dab per frame. The render thread then
never catches up *within the stroke*, and the batches queue: what the user sees
is not a slow pen-up, it is ink arriving further and further behind the pen, and
the gap only closes when the hand stops. A tablet several times slower at fill
rate than this machine reaches a second of accumulated lag over a stroke of
ordinary length, which is the report.

## Three more things the bench found

### 1. The scratch buffer reallocates on nearly every batch

Driving the wet pass properly — one batch per 90 Hz frame, `begin` then
`ensureCovers` — over eight strokes:

| nib | strokes | dabs | **allocations** | growths | scratch ms | dab ms |
|---|---|---|---|---|---|---|
| `pencil-1-hard` | 8 | 2470 | **607** | 606 | **173.4** | 17.3 |
| `airbrush-soft` | 8 | 72 | **56** | 55 | 45.6 | 262.0 |

Six hundred and seven `Bitmap.createBitmap` calls for eight pencil strokes, and
**the buffer costs ten times what the drawing costs**. The cause is in
`ScratchLayer.ensureCovers`: when the required extent reaches left or up of the
current origin, the branch sets `bitmap = null` before calling `ensureCapacity`,
so a new bitmap is allocated *even when the one in hand is several times larger
than the new extent*. A hand-drawn arc moves up or left on about half its
batches. The fresh branch then also clears the **whole allocation** rather than
the used region — the same cost `begin`'s `clearUsed` was specifically written
to avoid.

This is not the airbrush's problem; it is every translucent nib's problem, and
the pencil is the one people use all day. It is on this page because the same
bench found it and the same file fixes it.

### 2. The mask cache cannot hold a size ramp on a big nib

`MaskCache` budgets 4 MiB. A 602 px `ALPHA_8` mask is 353 KiB, so **eleven
buckets fill the entire cache**, and `MaskTolerance` quantises size
geometrically at 3% — about 150 buckets across a pressure ramp. Walking a ramp
up and down four times:

| nib | dabs | mask work | cache at the end |
|---|---|---|---|
| 600 px soft, size on pressure | 240 | **579.3 ms** | 43 masks, 3920 KiB, **hit 40.4%**, 100 evicted |
| 24 px pencil, size on pressure | 240 | 0.8 ms | 54 masks, 12 KiB, hit 77.5%, 0 evicted |

Five hundred and seventy-nine milliseconds of *mask generation* for two hundred and forty
dabs, on top of the blits. `airbrush-soft` escapes this because its size is
constant — `size 600 600`, no `sizeOpt.drive` — so it lives in exactly one
bucket. **Any big soft nib with size on pressure is several times worse than the
brush that prompted this document**, and the shelf lets anyone make one with a
slider. This is the tripwire, not the symptom.

### 3. Building the mask small and scaling it up makes things worse

The tempting fix — cap the mask at 128 px and let the blit scale it — was
measured and is a trap:

| wanted | built at | generation | blit per 200 dabs | KiB |
|---|---|---|---|---|
| 600 | 600 | 15.8 ms | **33.2 ms** | 353 |
| 600 | 256 | 2.9 ms | 771.8 ms | 65 |
| 600 | 128 | 0.8 ms | 776.5 ms | 16 |
| 600 | 64 | 0.2 ms | 779.9 ms | 4 |

It buys the generation cost and the memory, and it **pays twenty-three times the
blit**, because a scale that is not 1:1 leaves the same integer fast path a
fractional offset leaves. Whatever fixes finding 2 must not be this.

## The work plan

> **2026-09-13, all six done.** What was measured on the device, what the fixes
> bought, and the one rule this document predicted wrongly, are at the bottom
> under **What was built**.

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| **Bn0** | **DONE. The device multiplier.** Draw ten airbrush strokes on the tablet and read the debug row: `wetpass` mean over batches, `scratch … alloc … grown`, `stamp … masks … hit`. Every number in this document has a counter already on that screen, so this is a screenshot and an arithmetic check, not a build. Without it the ratios here are host ratios being quoted as device ratios, which is the mistake Phase 1's W2 made. | device | Low | — | 0.5 |
| **Bn1** | **DONE. Whole-pixel placement for large dabs.** In `DabRasterizer.dab`, round the blit offset to integers when the dab is large enough that half a pixel cannot be seen. Both paths that matter are already pure integer translates — `ScratchLayer.canvasInDocSpace` translates by `Int` origins, `Layer`'s canvas has no matrix at all — so the rounding is exact rather than approximate. **The expected win is 23x on the reported brush.** | `:app` | Low | Bn0 | 1–2 |
| **Bn2** | **DONE, and it found a different rule.** The threshold, found rather than guessed. Bn1 needs a rule, and the rule has two candidate shapes: on diameter alone, or on the softness of the rim in pixels — `diameter * (1 - hardness) / 2`, which is 180 px for the airbrush and 0 for the chalk. Draw slow diagonals at 8, 16, 32, 64 and 128 px with both rules and find where the ripple appears. Pin the answer in `DabFootprintTest`. | `:app`, device | Med | Bn1 | 1–2 |
| **Bn3** | **DONE, and it needed a second half the plan did not see.** Stop the scratch buffer reallocating. Two buffers, ping-ponged, so an origin move is a copy between two long-lived allocations rather than an allocation; and clear the used region rather than the whole bitmap on the fresh branch. `ScratchLayer.allocations` is already on the readout, so the fix has a number to prove itself by. | `:app` | Med | — | 2–3 |
| **Bn4** | **DONE, and half of it was unnecessary.** The mask cache against a big nib. Widen `MaskTolerance`'s size ratio above a diameter threshold — 3% is right at 10 px and absurd at 600, where one bucket step is 18 px on a rim that falls off over 180 — and scale `MaskCache.budgetBytes` with the largest mask the brush in the hand can ask for, instead of a flat 4 MiB. **Not** by capping the mask and scaling the blit; the table above says why. | `:engine` | Med | Bn0 | 2–3 |
| **Bn5** | **DONE: refused.** The preset itself. `spacing 0.1` means ten times overdraw. Try 0.2 and 0.25 with flow compensated so the built-up density matches, and keep it only if the mark is indistinguishable side by side. A one-line change to a shipped `.brush` with no code behind it, worth 2–2.5x on top of everything above — and worth nothing if the airbrush stops looking like an airbrush. | assets | Low | Bn1 | 0.5–1 |

**Subtotal: 7–11.5 days**, and Bn0 + Bn1 alone — a day and a half — is expected
to be the whole of the reported problem.

### Held back, and why

| # | Work item | Why it is not in the list above | Days |
|---|---|---|---|
| **Bn6** | **Subpixel phases in the mask key.** Generate each small mask at 4x4 subpixel offsets and blit every dab at whole pixels, so small dabs get the fast path *and* better placement than bilinear gives them — the generator already supersamples, it would just supersample off-centre. | Worth 2.5x on the pencil and the fineliner, which are already at 2–5 µs a dab, and it multiplies cache misses by sixteen during a size ramp. Revisit if Bn0 says small dabs are a real share of the device's frame. | 3–4 |
| **Bn7** | **A reduced-resolution scratch for very large soft nibs.** 355x on the dab loop, one 3.3 ms upscale a stroke. A 600 px rim falling off over 180 px carries no detail a quarter-resolution buffer loses. | It is the only option here that changes pixels, and it touches the selection stencil, the grain shader and the composite, all of which are document-space today. **Only if Bn1–Bn5 land and the device is still over budget.** | 6–9 |
| **Bn8** | **Band the dab loop across threads.** Same-colour dabs over `SRC_OVER` commute — the result alpha is `1-(1-a)(1-b)` either way — so horizontal bands of the scratch can be filled independently with no ordering question at all. | A second concurrency story on the render thread, for a brush that should not need it once it is twenty-three times cheaper. | 5–8 |

## What was built

> 2026-09-13. Host figures from `BigNibBench`, device figures from the debug
> readout on the DTH-A116 with ten airbrush strokes injected through the real
> input path.

### Bn0 — the device multiplier, and the stop condition that did not fire

| | baseline |
|---|---|
| `wetpass` mean | **22.244 ms** over 216 batches |
| `batches` | 8 slots, peak 25, **18 spills** |
| `scratch` | **114 alloc**, 113 grown |
| `stamp` | 1 mask, 357 KiB, hit 99.6% |

The first stop condition was *"if the tablet says the wet pass is inside budget,
the second of lag is coming from somewhere this bench cannot see."* It is not
inside budget: 22.2 ms against 11.1, with the batch ring exhausted eighteen
times. The document was aimed at the right thing.

The multiplier: about 270 dabs cost 4.8 s of wet pass, so **17.8 ms a dab on the
device against 3.89 on the host — 4.6x**.

### Bn1 and Bn2 — the rounding, and the rule this plan got wrong

**The plan expected a size threshold and the measurement produced a softness
one.** `DabFootprintTest` draws a slow shallow diagonal — the case where
consecutive dabs round in *different* directions — and counts rim pixels that
move by more than an eighth of the channel, per 1000 document pixels of stroke:

| diameter | hardness 1.0 | hardness 0.4 |
|---|---|---|
| 64 | 1367 | **0** |
| 128 | 1459 | **0** |
| 300 | 1570 | **0** |
| 600 | 147 | **0** |

A hard rim ripples at every size; a soft rim never does, at any size. Shipping
on diameter alone — which is what Bn1 was written to do — would have put a
visible staircase on every large hard nib. The third stop condition allowed for
exactly this and named the answer, so that is what shipped: **a dab is snapped
when it is at least 64 px wide *and* its rim falls off over at least 2 px**, and
never when it stamps a picture, because `hardness` says nothing about a tip's
edge.

What it bought, on one 600 px soft dab:

| | ms per dab |
|---|---|
| filtered, fractional — before | 3.887 |
| **whole-pixel — after** | **0.164** |

and through the whole commit path on the device, measured as an A/B in one run
by `InkSurfaceView.measureRerender`:

| airbrush strokes | snapped | not snapped |
|---|---|---|
| 30 | 287.8 ms | 2006.6 ms |
| 90 | **791.0 ms** | **6282.3 ms** |

**7.9x on the device.** The second stop condition asked for at least 10x on
`wetpass` and `wetpass` moved only 3.0x — but `wetpass` is not the dab loop. It
also carries a stack recomposite per batch, which this plan explicitly puts out
of scope and which is now the larger half of what is left. The A/B above is the
measurement that condition was really asking for, and the device's blitter does
have the integer fast path.

### Bn3 — and the half the plan did not see

Two long-lived buffers, ping-ponged, and the fresh branch clearing the used
region instead of the whole allocation. That killed the **allocations**:

| | before | after |
|---|---|---|
| pencil, 8 strokes, host | 607 alloc | **10** |
| airbrush, 8 strokes, host | 56 alloc | **8** |
| airbrush, 10 strokes, device | 114 alloc | **5** |

It did not kill the **copies**, and on the device those were most of what was
left. `ensureCovers` repositions the origin at the exact new minimum, so a
stroke drawn upward reaches one pixel higher on every batch and copies the
entire used region — 2055x1186 — every time: **242 growths for ten strokes**.
The fix is to overshoot by [ScratchLayer.SLACK] whenever the origin has to move
at all, so the next few batches land inside what is already held:

| | before | after |
|---|---|---|
| pencil growths, host | 606 | **18** |
| airbrush growths, device | 242 | **16** |
| `scratch ms`, pencil, host | 170.9 | **3.9** |

### Bn4 — and half of it was not needed

Widening `MaskTolerance`'s size step above 64 px from 3% to 8%, on a big soft
nib with size driven by pressure:

| | before | after |
|---|---|---|
| mask work, 240 dabs | 579.3 ms | **108.8 ms** |
| hit rate | 40.4% | **86.3%** |
| evictions | 100 | **0** |

The second half of the item — scaling `MaskCache.budgetBytes` with the largest
mask a brush can ask for — **turned out to be unnecessary and was not built**.
Coarser buckets cut the distinct masks from about 150 to 33, and 33 of them fit
in the 4 MiB the cache already had, with nothing evicted. A budget that is
already enough does not need to be made adaptive.

### Bn5 — refused, and for a reason worth keeping

Doubling `spacing` to 0.2 moves the mark: **mean alpha difference 6.6 of 255
over 170,000 inked pixels, worst 50**. The fifth stop condition is that if the
mark changes at all it does not ship, so it did not.

The reason is not what the plan assumed. `StrokeBuilder.dabAlphaFor` does invert
the overlap — `flow` means the coverage of one pass and the per-dab alpha is
solved from it and the spacing — but it has one exit that skips the whole thing:

```kotlin
if (flow >= 1f) return 1f
```

correct, since a fully opaque dab cannot be made more opaque. The airbrush's
flow curve **reaches 1 at 0.859 pressure**, so the firm half of every stroke
draws with no compensation at all and halving the dabs halves the build-up in
the rim. `AirbrushSpacingTest` pins the refusal and is the tripwire for trying
it again if the compensation ever reaches the opaque end.

### Where it ends up

| | before | after |
|---|---|---|
| `wetpass` mean, device | 22.244 ms | **7.408 ms** |
| — against an 11.1 ms frame | **2x over** | **inside** |
| Total wet work, 10 strokes | 4805 ms | **1630 ms** |
| `scratch alloc` / `grown`, device | 114 / 113 | **5 / 16** |
| airbrush, host, per stroke | 26.5 ms | **1.7 ms** |
| airbrush, host, per dab | 3374 µs | **137 µs** |

The reported symptom is gone: the wet pass is inside the frame budget, so the
ink no longer falls further behind the pen the longer the stroke runs.

**What is left, and it is named rather than fixed.** `batches … spills` is still
17, and `wetpass` moved 3.0x where the dab loop moved 7.9x. The difference is
the per-batch stack recomposite, which happens on the front buffer's own canvas
and which this bench cannot see — exactly what *Deliberately not in this plan*
says is the next place to look, and it needs a GPU trace rather than a JVM
bench. Bn7's reduced-resolution scratch is still held back and is still the
right lever if the device ever needs more.

## Stop conditions

1. **If Bn0's device numbers do not show the wet pass over 11.1 ms for the
   airbrush, stop and look elsewhere.** This document explains a render-thread
   fill-rate problem. If the tablet says the wet pass is inside budget, the
   second of lag is coming from somewhere this bench cannot see and the whole
   plan is aimed at the wrong thing.
2. ~~**If Bn1 does not show at least a 10x drop in `wetpass` on the device, stop
   and re-measure before continuing.**~~ **Fired, and re-measuring answered it.**
   `wetpass` moved 3.0x, under the bar — but `wetpass` is not the dab loop; it
   also carries a per-batch stack recomposite. A device A/B of the commit path
   with the rounding on and off measured **7.9x**, so the blitter does have the
   fast path and the rest of the plan stands. The lesson is in the condition's
   wording rather than its number: it asked about a counter that measures two
   things and attributed the whole of it to one.
3. ~~**If Bn2 cannot find a threshold where the ripple is invisible, Bn1 ships
   gated on hardness alone**~~ — **fired, and this is what shipped.** There is
   no diameter at which a hard rim stops rippling; there is no diameter at which
   a soft one starts. Size survives as a second condition only because dabs 1 px
   apart bead when they round to the same pixel, which is a different defect
   that softness does not fix.
4. ~~**If Bn3 does not drop `scratch … alloc` to single digits per stroke, revert
   it.**~~ **Cleared: 114 allocations became 5, for ten strokes.** The double buffer costs a second full-size allocation permanently, and
   that is only worth paying for if the churn genuinely goes away.
5. ~~**If Bn5 changes the mark at all, it does not ship.**~~ **Fired. It changed
   the mark by 6.6 of 255 and did not ship.** Performance is not a
   reason to make a brush draw differently, and a preset is the one thing here a
   user can already fix themselves with a slider.

## Risks and tripwires

| Risk | Tripwire | What it costs to be wrong |
|---|---|---|
| Whole-pixel snapping is visible after all, on some nib nobody tested | Bn2's diagonals, and `DabFootprintTest` | A revert, and the threshold moves up |
| The device has no integer fast path in its blitter | Bn0, then stop condition 2 | Bn1 buys nothing and Bn7 becomes the plan |
| Bn4's wider buckets make a pressure ramp stair-step on a big nib | The ramp is drawn slowly and watched, at 600 px | Narrow the ratio and raise the budget instead |
| Someone tunes a 600 px nib with size on pressure before Bn4 lands | Finding 2 above; the readout's `hit %` collapses | A brush four times slower than the one in this report |
| Bn3's double buffer pushes a big document into an out-of-memory | `ScratchLayer`'s `maxWidth`/`maxHeight` cap already exists for exactly this | The cap holds; the second buffer is capped with it |

## Deliberately not in this plan

- **GPU rasterisation of dabs.** Phase 2 gates it, and it stays gated. This is a
  twenty-three-times win available from arithmetic, and spending the GL card on
  a problem that a rounding fixes would be the most expensive possible answer.
- **Dropping the airbrush from the shipped set.** It is a legitimate brush at a
  legitimate size, and the program should be able to draw it.
- **Making `indirectNeeded()` cheaper.** The scratch buffer is a correctness fix
  and stays; Bn3 makes it cheaper without making it optional.
- **Anything about the front buffer's per-batch stack recomposite.** It happens
  on a hardware `RenderNode` canvas and this bench cannot see it. If Bn0's
  numbers do not add up, that is the next place to look, and it needs a GPU
  trace rather than a JVM bench.

## What this changes elsewhere

`docs/master-plan.md` gains a row. Nothing else in it moves: this is a defect
against shipped work, not a feature, and it does not sit in front of P4.

The one document it touches is `docs/brushes-plan.md`, whose Wb8 shipped the
sixteen presets. A note belongs there: **an import can produce a nib an order of
magnitude larger than anything the renderer was measured against**, and the
conversion tool is the natural place to warn about it — `tools/krita-brushes.py`
already clamps spacing into `0.02..1.0` and could as easily report the largest
diameter it wrote.
