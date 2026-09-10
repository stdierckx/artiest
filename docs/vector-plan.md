# Vector layers — the feature list, before the decision

> Authored 2026-09-10, from the manuals and feature pages of Clip Studio Paint,
> Krita, Concepts, Inkscape, Affinity Designer, Adobe Fresco and Illustrator,
> and from a Krita developer's own study of the problem. **This is a menu, not a
> plan.** Nothing is chosen here; the numbers in the tables are estimates at the
> same confidence as Phase 2's "predicted" ones, which is to say they are claims
> awaiting refutation. The last section lists the three questions only you can
> answer, and the plan proper cannot be written until they are.

## The fact that decides everything

**artiest already has the vector data. It throws it away on purpose.**

The pipeline is pen samples → `StrokeBuilder` → a `Stroke`, which is a list of
resolved dabs → stamped into a layer bitmap at pen-up → **discarded**.
`Stroke`'s own KDoc says why: *"Retained only until the commit finishes.
`Document` keeps the bounds of each committed stroke and nothing more; keeping
the dabs would rebuild the ever-growing scene list the layer bitmap exists to
replace."*

A vector layer **is** that scene list. So this feature is not "add vectors to a
raster app" — it is *stop discarding what we already build, on the layers where
you ask for it*, and answer the objection that comment raises rather than
pretend it was not made.

Two pieces of luck make the foundation much cheaper here than it would be in
most raster apps:

- **`Trace` already exists and already round-trips.** Pen samples, per event,
  with the canvas transform, in a versioned format with adversarial tests. That
  is nine tenths of a vector stroke's storage, written for a different purpose
  (replaying strokes for measurement) and reusable as-is.
- **The dab loop is deterministic and scale-aware.** Re-running `StrokeBuilder`
  at a different zoom produces the *correct* stroke at that zoom, not a scaled
  copy of an old one. That is exactly what "infinite scaling" means, and we get
  it from the existing engine rather than from a new one.

What is *not* cheap is everything after "store it": editing, caching,
re-compositing translucent strokes, a second undo model, and a file format. Those
are the real work and they are what the tables below price.

## Three ways to be a vector layer, and they are not equivalent

A Krita developer surveyed this exact problem in 2021 and found three families.
The choice between them is the single largest decision in this document.

| Model | What a stroke *is* | Who does it | For us |
|---|---|---|---|
| **A. True vector paths** | A Bézier outline with a fill. Variable width becomes an outline shape (Inkscape's PowerStroke, Illustrator's Blob Brush). | Inkscape, Illustrator, Affinity, Krita's vector layers | **Portable — real SVG comes out.** But our pencil's grain, tilt-driven ellipse and per-dab flow cannot be expressed. It would be a *different tool* on those layers. |
| **B. Dense polyline with per-node properties** | A list of points, each carrying width, opacity, rotation. | Clip Studio Paint, SAI, Blender Grease Pencil | **The inking sweet spot.** Every CSP vector feature people love comes from this. Not portable without flattening. |
| **C. Stored input, re-rasterized** | The pen samples plus the brush id, re-run through the dab loop on demand. | Concepts (hybrid), and essentially what our `Trace` already holds | **Nearly free to store, keeps every brush exactly as it draws**, including grain. Hardest to *edit*, because a node in the middle of a stroke is not a thing that exists. |

The survey's own verdicts are worth carrying: Inkscape's approach "over-simplifies
the input, resulting in lines that often only have one or two points"; Grease
Pencil's dense polylines capture the hand beautifully and are "tricky to edit";
CSP and SAI treat variable-width strokes as first-class and get the editing tools
to match. And the honest warning: **textured and semi-transparent work resists
vectorization**, because it is made of many small overlapping marks.

That warning is aimed straight at us. **Phase 2's entire thesis is that the
pencil must read as graphite, and graphite is grain.** Model A cannot keep it.
Model C keeps it perfectly. Model B keeps it if the "render" step is still our
dab loop. This is the tension the whole feature has to be designed around, and
anyone who says vector layers are simply better has not been asked to keep the
pencil.

**My recommendation, for what it is worth before you have picked features:**
**B over C, with the file storing C.** Store the samples (so nothing is lost and
`Trace` does the work), derive a per-node polyline for editing, render through
the existing dab loop. Model A appears only at export time, and only as a
flattened outline, clearly labelled as lossy.

## The feature list

Scored for **this** app — a stylus sketchbook on an 11" tablet — not for a
logo-design program. A feature that is essential in Illustrator can be a 1 here.

- **Use** = how much it changes what you can do, 1–5.
- **Diff** = implementation difficulty, 1–5.
- **Time** = focused working days, at the confidence level stated at the top.

### Tier 0 — the foundation. Nothing below works without all of it.

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 1 | **Vector layer type** | A layer that keeps its strokes instead of flattening them. Chosen per layer, beside the raster ones. | CSP, Krita, Fresco | 5 | 3 | 4–6 |
| 2 | **Resolution-independent redraw** | Zoom in and the line is re-drawn sharp, not magnified. The headline reason to want any of this. | all of them | **5** | 3 | 3–5 |
| 3 | **Render cache** | A bitmap (or tiles) of the layer as currently seen, so drawing is not re-rendering 500 strokes a frame. | everyone, silently | 5 | 4 | 5–8 |
| 4 | **Command-based undo for vector layers** | Undo is "remove that stroke", not "restore these pixels". Our `UndoHistory` is region snapshots; this is a second model living beside it. | all | 5 | 4 | 4–6 |
| 5 | **Save and load** | Vector layers in our own file format. `Trace` gets us most of the way. | all | 5 | 2 | 2–4 |
| 6 | **Export at any resolution** | Print the sketch at 4× with no jaggies. The payoff of 2, made reachable. | all | 4 | 2 | 1–2 |

**Tier 0 total: roughly 20–30 days, and it delivers exactly one visible feature —
strokes stay sharp when you zoom.** That is the honest price of entry and it is
the number worth staring at before anything below is discussed.

### Tier 1 — the editing that makes people love vector layers

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 7 | **Vector eraser: erase whole line** | Touch a line, the whole line goes. | CSP | 4 | 1 | 1 |
| 8 | **Vector eraser: erase to intersection** | Rub past a junction and only the overshoot dies. **The single most-praised vector feature in CSP**, and the reason inkers use vector layers at all. | CSP | **5** | 3 | 3–5 |
| 9 | **Select a stroke** | Tap or lasso to pick strokes. Everything below needs it. | all | 5 | 2 | 2–3 |
| 10 | **Move / rotate / scale a selection** | Transform without ever resampling pixels. | all | 5 | 3 | 3–4 |
| 11 | **Delete / duplicate / re-order strokes** | Housekeeping inside a layer. | all | 4 | 1 | 1–2 |
| 12 | **Recolour an existing stroke** | Change a drawn line's colour weeks later. | Concepts, CSP, Krita | 4 | 1 | 1 |
| 13 | **Change the brush of an existing stroke** | Draw it as a pencil, decide later it is a marker. Falls out free of model C. | Concepts, CSP | 4 | 2 | 2 |
| 14 | **Scale a line's thickness** | Whole-line width up or down, losslessly. | CSP | 4 | 2 | 2 |
| 15 | **Stabilisation after the fact** | Re-smooth a wobbly line without redrawing it. Free from stored input; nobody else offers it because nobody else keeps the input. | (rare) | 3 | 2 | 2 |

### Tier 2 — node-level editing. This is where the cost turns.

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 16 | **Control points: move, add, delete, corner/curve** | The classic vector edit. Requires committing to model B and to a node representation. | CSP, Affinity, Krita, Inkscape | 4 | **5** | 10–15 |
| 17 | **Per-point width** | Drag a node sideways to fatten the line there. | CSP, Inkscape PowerStroke | 3 | 4 | 5–8 |
| 18 | **Pinch a line** | Grab a stretch and pull it into shape, no nodes visible. **More natural with a pen than nodes are**, and CSP users reach for it more. | CSP | 4 | 4 | 5–7 |
| 19 | **Redraw part of a line** | Re-trace a section; the rest stays. | CSP | 4 | 4 | 5–7 |
| 20 | **Simplify** | Cut the control points a hand-drawn line generates. Required before 16 is usable at all — the survey's central complaint about dense polylines. | CSP, Inkscape | 3 | 3 | 3–4 |
| 21 | **Connect lines / close gaps** | Join two ends that nearly meet. | CSP | 3 | 3 | 3–4 |
| 22 | **Taper after the fact** | Set start/end taper on a drawn line. | CSP, Affinity | 3 | 2 | 2–3 |

### Tier 3 — shapes and precision

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 23 | **Shape tools with live geometry** | Ellipse, rectangle, polyline, curve that stay editable. | all | 3 | 3 | 4–6 |
| 24 | **Snapping** — endpoints, grid, live snap | Concepts sells its whole "design" story on this. | Concepts, Affinity | 3 | 3 | 3–5 |
| 25 | **Rulers and guides from a vector line** | Turn a drawn curve into a ruler to draw along. A genuinely clever CSP idea. | CSP | 2 | 3 | 3 |
| 26 | **Symmetry / perspective guides** | Not vector-specific, but this is where they usually land. | CSP, Krita | 3 | 3 | 4–6 |
| 27 | **Boolean ops** (union, subtract, intersect) | Logo work. Not sketching. | Affinity, Illustrator | 1 | 4 | 5–8 |
| 28 | **Vector text** | Text that stays editable and sharp. | Krita, CSP, all | 2 | 4 | 6–10 |
| 29 | **Real-world scale and measurement** | Concepts' architect feature. | Concepts | 1 | 3 | 4 |

### Tier 4 — fill and colour

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 30 | **Fill inside line art, with gap closing** | Tap a region bounded by strokes and flood it, tolerating small gaps. Enormous for anyone colouring line art. **CSP itself refuses the fill tool on vector layers** and fills on a raster layer that *refers* to them — worth copying that answer rather than fighting it. | CSP (referenced), Illustrator Live Paint | 4 | 4 | 6–10 |
| 31 | **Closed-shape fills** | A stroke that closes becomes a fillable shape. | Illustrator, Affinity | 2 | 4 | 5 |
| 32 | **Gradient fills / gradient strokes** | Krita explicitly *cannot* do gradient strokes — lines are flat colour only. Being able to would be a differentiator; wanting to is another question. | Illustrator, Affinity | 1 | 4 | 5–8 |

### Tier 5 — interop

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 33 | **SVG export (flattened outlines)** | Real vectors out, as filled outline shapes. **Lossy by nature** — CSP warns that its own SVG export carries shape only, no colour, brush or thickness. Ours would carry shape and colour and lose the grain. | CSP, Krita, Concepts | 3 | 4 | 6–10 |
| 34 | **SVG import** | Bring in shapes from elsewhere. | Krita, CSP, Concepts | 2 | 4 | 6–8 |
| 35 | **PDF export** | Sharp print output. Often easier than good SVG. | Concepts, Krita | 2 | 3 | 3–5 |
| 36 | **Image trace (raster → vector)** | Auto-vectorize a scanned sketch. A large, separate research problem with a whole industry behind it. | Illustrator, Linearity | 1 | 5 | 15+ |

### Tier 6 — canvas-level, adjacent and expensive

| # | Feature | What it is | Seen in | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 37 | **Infinite canvas** | No page edge; pan forever, zoom forever. Concepts' entire identity, and **it is a document-model change, not a vector feature** — it just becomes tempting once strokes are resolution-independent. | Concepts | 3 | 5 | 15–25 |
| 38 | **Artboards** | Framed regions to export separately. | Concepts, Affinity | 1 | 3 | 4 |

## The traps, priced honestly

Five things will cost more than they look, and all five are consequences of our
own Phase 2 decisions rather than of vectors as such.

1. **Translucent strokes must each composite separately.** The pencil paints
   through a scratch buffer and composites once at stroke end — that is what
   makes it graphite. Re-rendering a vector layer of 300 pencil strokes means
   300 scratch passes, not one loop over 300×N dabs. **This, not the dab count,
   is the thing that decides whether vector layers are fast enough**, and it is
   measurable today with the existing stress harness before any of this is
   built.
2. **Grain lives in paper space.** Move a stroke and its grain must be
   *regenerated* at the new position, not carried along — otherwise the paper's
   tooth slides around the page. `GrainField` is procedural and positional, so
   this works correctly by default; it is written here so nobody "fixes" it into
   carrying the grain with the stroke.
3. **Two undo models in one app.** Raster layers undo by pixel region; vector
   layers must undo by command. Both are correct; having both is the cost.
4. **Memory grows with the drawing.** Every stroke kept forever. `Trace` says
   what a stroke costs; multiply by a real session before promising this.
5. **The androidx.ink decision deserves re-opening, and only for this.** Phase 2
   rejected androidx.ink partly *because* its canvas is "a list of immutable
   Stroke objects re-rendered each frame". For a vector layer that model is not
   a drawback, it is the requirement. The other two reasons for rejecting it
   (no latency win, undocumented brush authoring) still stand and are probably
   still decisive — but a document that recommended vector layers without
   noticing the reversal would be hiding something.

## If you only pick a few

The smallest set that is genuinely worth building, and what it gets you:

> **Tier 0 (1–6) + eraser modes (7, 8) + select and move (9, 10, 11) +
> recolour and rebrush (12, 13).**
>
> That is "your line art stays sharp at any zoom, you can rub an overshoot back
> to the junction, and you can pick a line up and change its colour a week
> later". It is the CSP inking proposition, it needs **no node editing at all**,
> and it is roughly **30–45 days**.

Everything in Tier 2 doubles that, and buys precision that a pen-in-hand
sketcher may never reach for. Tier 3 onward is a different program.

## The three questions only you can answer

1. **Does the pencil have to work on a vector layer?** If yes, model A is out
   and SVG export is permanently lossy. If the vector layers are for *inking*
   and the pencil stays raster, model A becomes possible and the whole feature
   gets smaller and more portable. **This is the biggest fork in the document.**
2. **Editing, or just sharpness?** "Never pixellates and exports big" is Tier 0.
   "I can grab a line and reshape it" is Tier 0 plus Tier 2, and is three times
   the work.
3. **Is infinite canvas part of what you are picturing?** It usually is when
   people say "vector", and it is a separate, larger change to the document
   model. Worth naming now so it is a decision rather than a drift.

## Sources

- [CSP: vector layers](https://help.clip-studio.com/en-us/manual_en/180_layers/Vector_layers.htm) — control points, width and opacity per point, pinch, simplify, connect, redraw, the three eraser modes, and what is *not* supported (fill, gradient, blend)
- [CSP: eraser tools](https://help.clip-studio.com/en-us/manual_en/240_brushes/Eraser_tools.htm)
- [CSP: Correct line tool](http://www.clip-studio.com/site/gd_en/csp/userguide/csp_userguide/500_menu/500_menu_layer_new_vector_edit_senshusei.htm)
- [Krita: vector layers](https://docs.krita.org/en/reference_manual/layers_and_masks/vector_layers.html) and [vector graphics](https://docs.krita.org/en/user_manual/vector_graphics.html) — SVG 1.1 only, no gradient strokes
- [Wolthera, *Study of editable strokes for inking*](https://wolthera.info/2021/10/study-of-editable-strokes-for-inking/) — the three models, and why each program's editing feels the way it does
- [Inkscape PowerStroke](https://wiki.inkscape.org/wiki/index.php/PowerStroke) — variable width as location/width pairs
- [Concepts](https://concepts.app/en/) — infinite canvas, every stroke editable, SVG/PDF/DXF export, live snap
- [Adobe Fresco: vector brushes](https://helpx.adobe.com/fresco/using/vector-brushes.html)
- [Illustrator: Blob Brush](https://helpx.adobe.com/illustrator/using/tool-techniques/blob-brush-tool.html) and [Image Trace](https://helpx.adobe.com/illustrator/desktop/manage-objects/traces-mockups-symbols/trace-images-to-convert-raster-into-vector-artwork.html)
