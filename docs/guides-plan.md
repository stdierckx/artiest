# Guides, rulers and shape tools — the feature list, before the decision

> Authored 2026-09-10, from the manuals of Clip Studio Paint, Krita, Procreate,
> SketchBook and Infinite Painter. A menu, not a plan; the scores are *predicted*
> in Phase 2's sense. The section that matters most is **The seven mechanisms**,
> because the same wish — "help me draw a straight line" — is answered five
> different ways by five programs, and the choice of mechanism decides the cost
> far more than the choice of feature does.

## Where this plugs in, and why it is cheaper than it looks

Two facts about the code as it stands:

1. **There is already a filter stage on the sample stream.** `Stabilizer` sits
   inside `StrokeBuilder`, in `:engine`, between the incoming samples and the
   Catmull-Rom fit. **A guide is another filter in that same position**: take the
   sample, project it onto a line, a curve, an ellipse or a vanishing ray, hand
   the projected point on. It is arithmetic per sample, in a JVM-testable module,
   with a trace corpus and golden dabs already built to catch a regression in
   exactly that stage.
2. **There is already an overlay problem being solved.** Phase 3's marching ants
   draw a thing over the canvas that is not part of any layer. Guides are the
   same shape of work — drawn into the frame in view space, never into a
   `Layer` — so the two should share whatever Phase 3 builds rather than each
   inventing an overlay.

Mirror is the exception and is worth separating now: **mirror is not a filter, it
is dab multiplication.** Two-axis mirror doubles or quadruples the dabs in every
batch; radial symmetry at 8 segments multiplies them by eight. The dab loop was
measured at 23.4 ns a dab and the batch ring has a spill counter, so the cost is
knowable — but it lands on the batch pool, not on the arithmetic.

## The seven mechanisms

This is the answer to "are there other ways to give the user the same thing".
Every feature further down is delivered by one of these, and the mechanism is
what you are really choosing.

| | Mechanism | How it feels | Who does it | Cost |
|---|---|---|---|---|
| **M1** | **Persistent guide + snap while drawing** | Place a ruler once; every stroke follows it until you turn it off. | Krita assistants, CSP rulers, Procreate Drawing Assist | Guide objects, an overlay, handles to drag, persistence, and a snap stage. **The expensive one, and the one that scales to perspective.** |
| **M2** | **Draw and hold** | Draw a rough line or circle, keep the tip down, it snaps to the ideal shape and stays adjustable until you lift. | Procreate QuickShape, Infinite Painter ("pausing snaps a sketch into a clean shape") | **One dwell timer and a shape fitter.** No guide objects, no overlay, no persistence, no new UI at all. |
| **M3** | **Modal shape tool** | Pick the line tool, drag from A to B, release. | every classic paint program | Cheapest to write, worst with a pen in hand: it costs a tool switch for every shape. |
| **M4** | **Fix it after the fact** | Draw freely, then straighten or re-fit the stroke you just made. | CSP's correct-line tools | Needs the stroke to still exist — free *before* commit, needs `docs/vector-plan.md` to work *after* it. |
| **M5** | **Second-finger constraint** | Hold a finger down and the stroke locks to straight, or to 15° steps. | common on tablets; the pen-and-touch equivalent of Shift | Small. The touch path and pen path are already separate, and `TwoFingerDoubleTap` shows the plumbing exists. |
| **M6** | **A ruler you lean on** | A physical object on the page: strokes near it snap, strokes away from it do not. Bendable rulers and French curves. | SketchBook | M1's machinery with a distance falloff instead of an on/off. Nicer to draw with, one more parameter to tune. |
| **M7** | **Predict and fit continuously** | The line quietly becomes a perfect arc while you draw it, with no dwell and no hold. | SketchBook predictive stroke | Feels magical when it is right and awful when it is wrong; the failure mode is your stroke changing under your hand. |

**The observation worth acting on:** your own example — *"drawing a line but keeping
the tip on the page switches to a straight line that can be modified by
dragging"* — is **M2**, and M2 alone delivers straight lines, circles, ellipses,
rectangles, polygons and smooth curves with **no guides, no overlay, no
persistence and no toolbar buttons**. It is the single highest value-for-effort
item in this document, and it is independent of everything else here.

## The feature list

**Use** 1–5, **Diff** 1–5, **Time** in focused working days. "Via" names the
mechanism.

### Tier 0 — the bargains

| # | Feature | What it is | Via | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 1 | **Straight line by draw-and-hold** | Hold at the end of a stroke and it becomes a perfect line, draggable at both ends until you lift. | M2 | **5** | 2 | 3–4 |
| 2 | **Circle / ellipse by draw-and-hold** | The same gesture, the same fitter, a different primitive. Once 1 exists this is mostly the fitting maths. | M2 | 4 | 3 | 3–4 |
| 3 | **Rectangle / polygon by draw-and-hold** | Same again. Cheap once the mechanism is there, and low value for a sketchbook. | M2 | 2 | 2 | 2 |
| 4 | **Smooth-curve fit by draw-and-hold** | A wobbly arc becomes a clean Bézier. | M2 | 3 | 3 | 3 |
| 5 | **Straight-line constraint on a held finger** | Lock the current stroke to straight, or to fixed angles. | M5 | 3 | 2 | 2 |
| 6 | **Vertical / horizontal mirror** | Draw one side, get both. The most-used symmetry by a wide margin. | mirror | **5** | 2 | 3–4 |

### Tier 1 — guides as objects

| # | Feature | What it is | Via | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 7 | **The guide framework itself** | Guide objects, an overlay pass, handles you can drag, on/off, persistence. **Every item below depends on it**, and none of Tier 0 does. | M1 | — | 4 | 8–12 |
| 8 | **Straight ruler / infinite ruler** | A line on the page; strokes snap to it. | M1 | 4 | 1 | 1–2 |
| 9 | **2D grid** | Squared paper you can rotate and space. | M1 | 3 | 2 | 2–3 |
| 10 | **Parallel-line ruler** | Every stroke comes out parallel to a set angle. Manga speed-lines and hatching. | M1 | 3 | 1 | 1 |
| 11 | **Radial / concentric ruler** | Strokes converge on a point, or circle around it. | M1 | 2 | 2 | 2 |
| 12 | **Curve guide (spline / French curve)** | Place a curve, draw along it. Krita's spline assistant, SketchBook's French curve. | M1/M6 | 3 | 3 | 4–5 |
| 13 | **Ellipse guide** | An ellipse you draw around — how wheels and cups in perspective are actually drawn. | M1 | 3 | 3 | 3–4 |
| 14 | **Snap falloff** ("lean on it") | Snap strength fades with distance from the guide, instead of on/off. **This is what separates a ruler that feels good from one that fights you.** | M6 | 4 | 2 | 2 |
| 15 | **Guide from a drawn stroke** | Draw a shape, turn it into a ruler. CSP's ruler pen. | M1 | 2 | 2 | 2 |

### Tier 2 — perspective

| # | Feature | What it is | Via | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 16 | **Vanishing point + horizon** | One point, one eye level, strokes snap to rays from it. The foundation of everything else here. | M1 | 4 | 3 | 4–5 |
| 17 | **1 / 2 / 3-point perspective grid** | The standard set, with draggable points and a movable horizon. | M1 | 4 | 3 | 4–6 |
| 18 | **Choose which point to snap to** | With three points live, the app must decide which ray you meant — usually "whichever is closest to the stroke's direction", with a manual override. **The part that decides whether perspective feels usable**, and the part every program solves differently. | M1 | 4 | 3 | 3–4 |
| 19 | **Isometric grid** | Three fixed angles, no vanishing points. Much simpler than 17 and independently useful. | M1 | 3 | 2 | 2 |
| 20 | **Infinitise a vanishing point** | Push a point to infinity and its rays become parallel. CSP's, and a genuinely good idea. | M1 | 2 | 1 | 1 |
| 21 | **Fisheye / curvilinear** | Curved perspective. Krita and SketchBook both have it; few people use it. | M1 | 1 | 4 | 5–6 |
| 22 | **Perspective-aware shapes** | Drop a rectangle or circle and it lands in perspective. Infinite Painter's party trick. | M1+M2 | 2 | 4 | 5–7 |

### Tier 3 — symmetry beyond a single axis

| # | Feature | What it is | Via | Use | Diff | Time |
|---|---|---|---|---|---|---|
| 23 | **Movable, rotatable mirror axis** | The mirror line is not stuck to the page centre. | mirror | 4 | 2 | 2 |
| 24 | **Quadrant mirror** (both axes) | Four copies. | mirror | 3 | 1 | 1 |
| 25 | **Radial symmetry** (N segments) | Mandalas. Multiplies every dab by N — the batch pool is the thing to watch. | mirror | 3 | 3 | 3 |
| 26 | **Kaleidoscope / mirrored radial** | Radial plus a flip per segment. | mirror | 2 | 2 | 1 |

### Tier 4 — "same distance", four readings

You did not say which of these you meant, and they are four different features
with nothing in common but the name. My guess is the third.

| # | Reading | What it would do | Use | Diff | Time |
|---|---|---|---|---|---|
| 27 | **Equal-spacing snap** | Marks snap to a fixed interval — hatching at even spacing, fence posts on a flat plane. | 3 | 2 | 2–3 |
| 28 | **A caliper / measure** | Show the distance between two points on the page, in pixels or millimetres. Concepts sells real-world measurement as a headline feature. | 2 | 2 | 2–3 |
| 29 | **Even division in perspective** | Divide a receding plane into equal parts — windows, columns, railway sleepers — where "equal" means equal *in the scene*, not on the page. Real perspective arithmetic, and **almost nobody offers it**; artists do it by hand with the diagonal method. | 4 | 4 | 5–7 |
| 30 | **Distribute existing marks evenly** | Select several things, space them out. Needs Phase 3's selection and probably vector layers. | 2 | 3 | 3–4 |

## If you only pick a few

> **Draw-and-hold for lines and circles (1, 2), a straight-line finger
> constraint (5), and vertical mirror (6).**
>
> Roughly **10–14 days**, no guide objects, no overlay, no persistence, no new
> panel — and it covers most of what a sketcher reaches for. It is also the set
> that does not commit you to anything: guides can be built later on top of it
> without rework.

Then, if perspective is what you actually want, the honest price is **item 7
first** (the framework, 8–12 days) before item 16 draws a single ray. That
ordering is the thing worth knowing in advance: **perspective is not a feature,
it is a subsystem with a feature on top**.

## The traps

1. **Snap must come after stabilisation, not before.** Smoothing a snapped
   stroke pulls it back off the guide; snapping a smoothed stroke is stable.
   The order is one line in `StrokeBuilder` and it is invisible until it is
   wrong.
2. **The predicted tail has to be snapped too.** `PredictedTail` extrapolates
   ahead of the pen. Unsnapped, the wet tail wanders off the ruler and snaps
   back when the real sample lands — visible jitter at exactly the moment the
   guide is supposed to feel solid.
3. **Re-fitting must be deterministic.** Draw-and-hold re-stamps the whole
   stroke every time you adjust it. Any brush parameter driven by
   `Sensor.RANDOM_DAB` re-rolls on each re-stamp, so the grain would crawl while
   you drag the endpoint. The per-stroke random is already held at pen-down;
   the per-dab one needs a seed derived from the dab index.
4. **Dragging a guide competes with panning.** Two-finger pan, pinch-zoom and
   "drag this vanishing point" all want the same gestures. `GestureController`
   and `InputRouter` already arbitrate pen against touch; guides add a third
   claimant, and deciding it late means rewriting it.
5. **The overlay costs a frame path, and latency is the project's constraint.**
   Guides drawn over the canvas must not force a full redraw on the wet-stroke
   path — 45.2 ms pen-to-photon is the measured number this project organises
   itself around. Share whatever Phase 3 builds for marching ants; do not invent
   a second overlay.
6. **Mirror multiplies the batch, not the arithmetic.** Eight-fold radial
   symmetry is eight times the dabs per event, against a batch ring with a
   measured spill counter. Measure it with `StrokeStress` before promising it.
7. **One stroke is one undo, however many mirrored copies it made.** Cheap to
   get right at the start, awkward to retrofit.

## The questions only you can answer

1. **Which "same distance" did you mean?** Items 27–30 are four different
   features. My guess is 29 — dividing a receding plane evenly — because that is
   the one artists actually struggle with and almost no program provides.
2. **Do you want persistent guides, or per-stroke help?** Per-stroke help (M2,
   M5) is a tenth of the cost of a guide framework and covers most sketching.
   Guides win the moment you need everything on the page to obey the same
   perspective.
3. **Is perspective the real goal?** If yes, item 7 is unavoidable and the
   Tier 0 bargains are a detour — worth taking, but they will not get you there.

## Sources

- [Procreate: drawing guides and assistance](https://help.procreate.com/procreate/handbook/5.3/guides) — 2D grid, isometric, perspective (up to three points), symmetry, and Drawing Assist
- [Procreate: how to use the guides](https://procreate.com/insight/2018/drawing-guides), and QuickShape — draw, hold, and adjust
- [CSP: basics of creating rulers](https://help.clip-studio.com/en-us/manual_en/510_ruler/Basics_of_creating_rulers.htm) — linear, curve, figure, ruler pen, parallel, radial, concentric, guide
- [CSP: perspective rulers](https://help.clip-studio.com/en-us/manual_en/510_ruler/Perspective_Rulers.htm) — eye level, movable/fixed/infinitised vanishing points, per-point snap toggles, fisheye
- [Krita: painting with assistants](https://docs.krita.org/en/user_manual/painting_with_assistants.html) — ellipse, concentric ellipse, spline, ruler, infinite ruler, parallel ruler, perspective, vanishing point, fish-eye point, and snap-to-assistant
- [SketchBook: rulers and guides](https://help.sketchbook.com/docs/rulers-and-guides) — bendable ruler, French curve, ellipse guide with minor-axis readout, predictive stroke, 1/2/3-point and fisheye perspective, radial symmetry
- [Infinite Painter: perspective](https://docs.infinitestudio.art/painter/perspective/isometric/) — five perspective guides, shapes that snap into perspective, radial and kaleidoscope symmetry
