# Layer effects — the feature list, before the decision

> Authored 2026-09-10, from the manuals of Clip Studio Paint, Krita, Procreate
> and Photoshop, and from what `CompositeBench` already measured. Like
> `docs/vector-plan.md` this is **a menu, not a plan**: the scores are estimates
> at the confidence level Phase 2 calls *predicted*. The three questions at the
> end are the ones that decide which half of this list is worth writing.

## Where this meets work already planned

**Blend modes are not in this document's scope. They are Phase 3's S8**, they
are specced in `docs/phase3-plan.md` under *One compositor, and blend modes in
it*, with a proposed list (Normal, Multiply, Screen, Overlay, Darken, Lighten,
Difference) and a note that they are one `Paint.blendMode` field. They are also
**first in Phase 3's cut order**, which is worth knowing: everything below
assumes the compositor Phase 3 builds, and several items are worth much less
without blend modes underneath them.

What already exists on a layer: opacity, visibility, name, order, thumbnail, and
a commit-queue discipline (`LayerOp`) that every new property can reuse
unchanged. What does not exist: groups, masks, merge, and any effect at all.

## The three families, and why the grouping is the whole story

"Layer effects" sounds like one feature. It is three, with costs an order of
magnitude apart, and knowing which family a wish falls into predicts its price
better than anything else in this document.

| Family | What it costs | Android gives us |
|---|---|---|
| **1. A setting on the blit** | Nothing measurable. The sheet is already being drawn; the `Paint` carries one more field. | `Paint.blendMode`, `ColorMatrixColorFilter`, `Paint.alpha` |
| **2. An offscreen pass** | One full-page buffer (27 MiB) and one extra blit, per group, per frame. | `Canvas.saveLayer`, `PorterDuff.DST_IN` — both already used and measured |
| **3. A real image operation** | A convolution or a distance transform over the page. Must be cached; cannot be per-frame on the CPU. | `RenderEffect` (blur, offset, colour filter, chained and blended) — **API 31, and `minSdk` is 29** |

Family 1 is nearly free. Family 2 is affordable if the compositor caches. Family
3 is affordable **only** through `RenderEffect` on the GPU, or by being
destructive (apply it once, bake it into the pixels, and let undo hold the
before-picture).

### The number that governs all of it

`CompositeBench`, on the host: eight plain sheets cost **20.8 ms** of CPU
compositing, one MULTIPLY sheet adds **8.3 ms**, and **three blits of a cached
backdrop cost 7.98 ms whatever the stack depth**. The screen path is GPU and is
therefore unmeasured — Phase 3's **S0** is the item that measures it on the
tablet, and Phase 3 explicitly gates the compositor cache on that answer.

So the rule this document is built on:

> **Every non-destructive effect is a tax on every frame. The cached backdrop is
> what makes the tax payable, and it is gated on S0. No effect from family 2 or
> 3 should be started before S0 has run.**

And one small decision that unlocks a third of the list: **`RenderEffect` is API
31.** The tablet is Android 14. Raising `minSdk` from 29 to 31 costs this project
nothing real and turns blur, glow and drop shadow from CPU convolutions into GPU
one-liners. It is a one-line change with a large consequence and it should be
made deliberately rather than discovered.

## The feature list

Scored for a stylus sketchbook, as in `docs/vector-plan.md`.
**Use** 1–5, **Diff** 1–5, **Time** in focused working days.

### Tier 0 — high value, low cost. These are the bargains.

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 1 | **Alpha lock** (preserve transparency) | Paint only where the layer already has ink. Shading and recolouring without leaving the shape. Procreate makes it a two-finger swipe, which tells you how often it is used. | Procreate, CSP, Krita, PS | **5** | 2 | 2 |
| 2 | **Layer colour** (tint the whole sheet one colour) | One tap turns a sketch layer blue so the ink on top reads clearly. CSP's, and the single best value-for-effort item in this document — it is a colour filter on an existing blit. | CSP | 4 | 1 | 1 |
| 3 | **Clipping mask / alpha inheritance** | Confine a layer to the shape of the one below. The standard way to shade without spilling. | all four | **5** | 3 | 4–6 |
| 4 | **Merge down / flatten** | Not an effect, and missing. Everything below makes the stack more complicated, and merge is the pressure valve. | all | 4 | 2 | 1–2 |
| 5 | **Lock layer** | Stop drawing on the wrong sheet. Costs nothing, prevents the most common accident. | all | 3 | 1 | 0.5 |
| 6 | **Non-destructive brightness / contrast / saturation** | A `ColorMatrix` on the blit. Free at frame time, reversible forever. | all | 3 | 2 | 2–3 |
| 7 | **Invert / desaturate** | Same machinery as 6, two presets of it. | all | 2 | 1 | 0.5 |

### Tier 1 — structure. Half the list below is worth much less without these.

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 8 | **Layer groups / folders** | The stack becomes a tree. Touches `LayerOp`, the snapshot, undo, thumbnails and the compositor. **Prerequisite for group opacity, group blend and group masks**, which is where most people actually use effects. | all | 4 | 4 | 8–12 |
| 9 | **Layer mask** (a painted mask per layer) | Hide parts without erasing them. A second `ALPHA_8` page per layer — **6.8 MiB each, against an 8-layer cap chosen from a memory budget**, so it changes an arithmetic that was deliberately chosen. | all | 4 | 4 | 6–10 |
| 10 | **Group pass-through vs isolation** | Whether a group's blend modes see through to the sheets below. The subtle one nobody notices until it is wrong. | PS, Krita, CSP | 2 | 4 | 3–4 |
| 11 | **Adjustment layers** (affect everything below) | A brightness or hue change as a sheet in the stack. Needs the cached backdrop from Phase 3 to be affordable at all. | PS, Krita | 2 | 4 | 6–8 |

### Tier 2 — the "layer styles" family (Photoshop's list, Krita's copy of it)

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 12 | **Stroke / border effect (Edge)** | An outline around everything drawn on the sheet. CSP's version is a staple of manga and sticker work; it is also how you make a sketch layer read at a glance. | CSP, Krita, PS | 4 | 3 | 4–6 |
| 13 | **Drop shadow** | Offset, blurred, tinted copy of the layer's alpha. With `RenderEffect`, three chained calls; without it, a full-page blur on the CPU. | Krita, PS | 3 | 3 | 3–5 |
| 14 | **Outer glow** | Drop shadow with no offset and a light colour. Same machinery — build 13 and this is a day. | Krita, PS | 2 | 2 | 1–2 |
| 15 | **Inner glow / inner shadow** | The same, clipped to the inside of the shape. One extra `DST_IN`. | Krita, PS | 2 | 3 | 3 |
| 16 | **Colour overlay** | Flood the layer's opaque pixels with one colour. Nearly the same as 2, and gets absorbed by it. | Krita, PS | 2 | 1 | 0.5 |
| 17 | **Gradient overlay** | The same with a gradient. | Krita, PS | 2 | 2 | 2–3 |
| 18 | **Pattern overlay** | Needs pattern *assets*, which means the licensing question from `docs/brushes-plan.md` again. `GrainField` could generate them instead. | Krita, PS | 1 | 3 | 3–4 |
| 19 | **Bevel and emboss** | The 1998 look. Cheap to want, expensive to do well, and rarely what a drawing needs. | Krita, PS | 1 | 4 | 6–8 |
| 20 | **Watercolour edge** | CSP's pale bleed at the edge of a stroke. Lovely, and a genuine image operation. | CSP | 2 | 4 | 5 |

### Tier 3 — image operations offered as effects

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 21 | **Gaussian blur (destructive)** | Apply, bake, undo holds the before. Procreate's most-reached-for adjustment. **`RenderEffect` makes this small**; a CPU convolution over a 27 MiB page does not. | all | 4 | 2 | 2–4 |
| 22 | **Blur as a live layer effect** | Same result, never baked, paid every frame. | PS, Krita | 2 | 4 | 5–6 |
| 23 | **Noise / grain as a layer effect** | We already have a tileable procedural grain field with cutoffs and a seed. This is mostly wiring. | Krita | 2 | 2 | 2 |
| 24 | **Halftone / tone** | CSP's screentone, the backbone of black-and-white manga. A whole feature, not an effect. | CSP | 2 | 4 | 6–8 |
| 25 | **Extract line / line extraction** | Pull line art out of a scan. A research problem wearing an effect's clothes. | CSP | 1 | 5 | 10+ |
| 26 | **Curves / levels** | A LUT per channel. Android has no LUT colour filter for `Canvas`; it needs `RuntimeShader` (API 33) or a CPU pass. | all | 3 | 4 | 5–6 |

## The traps

1. **The export must keep matching the screen.** Phase 3 builds one
   `StackCompositor` used by both the screen and `PngExporter`, with a test that
   composites twice and asserts pixel identity. **Every effect added here must
   go through that one compositor**, or the export diverges — and Phase 3
   already names that as a risk that becomes a certainty if the guard is
   skipped.
2. **Memory arithmetic was chosen, not assumed.** Eight layers at 27.19 MiB is a
   deliberate number sitting beside a 48 MiB undo budget. Masks (+6.8 MiB each),
   groups (an offscreen per group) and cached effect results all spend from the
   same pot, and running out shows up as an `OutOfMemoryError` **with a null
   message** — `Layer`'s KDoc records that. Any item from Tier 1 upward has to
   restate the cap.
3. **Undo has two kinds of change now.** An effect is a *property*, not pixels,
   so it wants a property-undo the way `LayerOp` already works — cheap, and a
   different shape from `PixelPatch`. A destructive effect (21) is the opposite
   and wants a full-page patch. Both are fine; conflating them is not.
4. **Nothing is persisted yet.** Phase 4's `.ora` will carry opacity, visibility
   and composite-op because the format has them. **Layer styles, masks and
   adjustment layers are not in the ORA spec**, so each one becomes an artiest
   extension that other programs silently drop. Worth knowing before promising a
   file that opens in Krita.
5. **A wet stroke on an effect layer.** Phase 3 already handles blend modes
   during a wet stroke by turning `saveLayerAlpha` into `saveLayer`. Every
   family-2 and family-3 effect has the same question — *what does the ink under
   my pen look like while I am drawing it* — and the honest answer for some of
   them is "the effect updates at pen-up". Say so per effect rather than
   discovering it.

## If you only pick a few

> **Alpha lock (1), layer colour (2), clipping mask (3), merge (4), and
> destructive Gaussian blur (21)** — on top of Phase 3's blend modes.
>
> That is roughly **10–15 days**, it needs no groups, no masks and no layer-style
> engine, and it covers what people actually do all day: shade inside a shape,
> turn the sketch blue, clip the colour to the line art, tidy the stack, and
> soften something.

Add **border effect (12)** if the work is ink-and-line. Add **groups (8)** only
when the stack is genuinely too tall to manage — it is the item most likely to
be wanted for tidiness and least likely to change a drawing.

## The three questions only you can answer

1. **Ink or paint?** The manga-and-ink direction wants border effect, tone and
   extract-line. The painting direction wants blur, glow, adjustments and
   clipping. They share almost nothing, and picking one halves this document.
2. **Non-destructive, or apply-and-bake?** Baking is roughly a fifth of the work
   and most of the value — a blur you can undo is nearly as good as a blur you
   can re-tune, and it costs nothing per frame. Non-destructive effects are what
   make the compositor complicated.
3. **Do you want groups?** They are the single biggest structural item, they make
   masks and group blending worth having, and they change the layer stack from a
   list into a tree — which touches undo, thumbnails, the panel and the file
   format all at once.

## Sources

- [CSP: layer properties](https://help.clip-studio.com/en-us/manual_en/180_layers/Layer_properties.htm) — border effect (Edge and Watercolour edge), tone, layer colour, and that these apply to folders as well as layers
- [Krita: layers and masks](https://docs.krita.org/en/user_manual/layers_and_masks.html), [transparency masks](https://docs.krita.org/en/reference_manual/layers_and_masks/transparency_masks.html), [filter masks](https://docs.krita.org/en/reference_manual/layers_and_masks/filter_masks.html), [clipping masks and alpha inheritance](https://docs.krita.org/en/tutorials/clipping_masks_and_alpha_inheritance.html)
- [Krita: layer styles](https://userbase.kde.org/Krita/Manual/Layerstyles) — drop shadow, inner shadow, outer/inner glow, bevel and emboss, stroke, colour/gradient/pattern overlay
- [Procreate: blend modes](https://help.procreate.com/procreate/handbook/layers/layers-blend), [masks](https://help.procreate.com/procreate/handbook/layers/layers-mask), [blur](https://help.procreate.com/procreate/handbook/adjustments/adjustments-blur)
- `docs/phase3-plan.md` — the compositor, the measured numbers, and S8's blend modes
