# Brushes and pencils, and where they come from

> Authored 2026-09-10, after Phase 2 shipped three hand-authored presets (pen,
> pencil, marker) and the question became "how do we get to thirty without
> authoring thirty". Nothing here had been measured; every claim about how a
> Krita preset maps onto our engine was a **prediction awaiting a render
> comparison**, and the work plan was arranged so the first refutation arrived
> before the expensive part started.
>
> **2026-09-12: it has been measured.** Wb1 shipped, and a spike ran Wb2 and
> Wb3 end to end against Krita's own default bundle on this tablet. The answer
> is at the bottom, under *"What the first import actually did"*. The short
> version: 26 of 30 converted brushes read as themselves, which clears Wb4's
> bar, and the four that do not each name a specific thing to fix.
>
> **2026-09-13: the engine grew a second kind of nib.** Wb5 and Wb6, under
> *"The nib that is a picture"*. 76 of the bundle's 118 presets convert now and
> 46 of them stamp a tip; 40 of those 46 read as themselves, and the six that do
> not fail for one reason that is named there.

## The three questions, answered before anything else

**1. Can we borrow brushes from Krita?** The *code*, no. The *presets*, yes,
with conditions. Those are two different objects with two different licences,
and conflating them is the mistake this document exists to avoid.

**2. Is the licence all right?** Three objects, three answers:

| Object | Licence | What we may do |
|---|---|---|
| Krita's C++ source (`KisPaintOp`, `KisDabCache`, the paintop engines) | GPL-3.0 | Read for understanding, reimplement. **Never copy.** Unchanged from `NOTICE` and the Phase 2 rule. |
| The file *formats* — `.kpp`, `.bundle`, `.gbr`, `.gih` | Not a work | Write our own reader. A format is a description, not a copyrightable expression, and ours will be written from the published description, not from their parser. |
| The preset *data* — the brushes themselves | Per bundle, declared in the bundle | Depends. See below. |

The preset data is the interesting one. A Krita `.bundle` is a ZIP holding
`meta.xml`, `META-INF/manifest.xml`, `mimetype`, `preview.png`, a
`paintoppresets/` directory of `.kpp` files and a `brushes/` directory of tip
images. **`meta.xml` carries a `<license>` field**, so every bundle states its
own terms in a place a script can read. Krita's shipped defaults are, in the
KDE community's own words, *mostly* CC0 — with some CC-BY-SA mixed in, and the
official advice is to check each bundle rather than assume. David Revoy's
`deevad` bundle (the origin of a good part of what Krita ships) is CC0 and says
so explicitly: *"free to do commercial work with them, to reshare them, to fork
them, to reuse them, **to include them in your software**"*. That last clause is
the one that matters to us.

So the rule, and it is short:

> **Import anything the user already has. Ship only CC0.**

Importing distributes nothing — a CC-BY-SA brush a user imports on their own
tablet is their business and never touches our repository or our APK. Bundling
one in `app/src/main/assets` is *us* redistributing it, and then the licence is
ours to honour. Keeping those two paths separate costs one `if` in the build and
removes the entire question from the interesting part of the work.

Two hard consequences, written down so they are not softened later:

- **A bundle with no `<license>` in `meta.xml` does not ship.** Not "probably
  fine because Krita ships it". Not shipped.
- **Every shipped brush carries its origin.** `app/src/main/assets/brushes/LICENSES`
  names the source bundle, the author, the licence and the date fetched, for
  every single file. `NOTICE` gains a paragraph pointing at it. This is the one
  place the project vendors third-party data, and the sentence in `NOTICE` that
  currently reads "no third-party source, assets or brush data have been copied
  into this repository" has to be amended honestly rather than quietly.

**3. How do you pick by hand?** That is the whole of **The picking** below, and
it is deliberately a text file you edit, not an algorithm.

## What "compatible" can honestly mean

Krita has more than a dozen paint engines. artiest has one: a mask stamp with a
sensor/curve dynamics model. So "compatible" has to be graded, because the word
can mean three very different things and only one of them is available:

- **Pixel-identical.** No. It would mean reimplementing Krita's paintops to the
  pixel, and our engine has parameters theirs does not (grain, burnish) and
  lacks parameters theirs has. Not a goal, not attempted, not claimed anywhere
  a user can read it.
- **Recognisably the same brush.** Yes, for a subset. A round soft brush that
  thins with pressure is the same brush in both programs; the mapping is
  arithmetic between two sensor/curve models that were designed for the same
  job. **This is the target.**
- **Reads the file without crashing and produces something.** Trivially yes,
  and worthless on its own. A brush that imports but looks nothing like its
  preview is worse than one that refuses to import, because it wastes the
  user's judgement instead of the importer's.

The judgement is by eye, against evidence we already have: **every `.kpp` is a
PNG whose visible image is Krita's own preview of that brush.** The file is its
own reference photograph. That is a gift — it means we can build a side-by-side
sheet without installing Krita at all.

### What maps, and what does not

| Krita paint engine | Verdict | Why |
|---|---|---|
| **Pixel brush, auto (procedural) tip** | **Take.** | Circle/square, softness, ratio, rotation, spacing, opacity, flow — this is exactly `MaskSpec` + `MaskGenerator`. The bulk of pencils, inkers and basic rounds live here. |
| **Pixel brush, predefined (bitmap) tip** | **Take, later.** | Needs a second mask source in the engine. Where most of the interesting texture brushes are, so it is worth the work item — but it is a real engine change and it goes after the cheap half has proved the mapping. |
| **Pixel brush, `.gih` tip sequences** | Frame zero only, or skip. | An indexed sequence of masks chosen per dab. Our cache is keyed on geometry, not on a tip index. Cheap to add later, not a v1 concern. |
| Color smudge | Skip | Reads the layer under the dab. We have no read-back path and adding one touches the single-writer lock Phase 1 built the document model around. |
| Bristle, hairy, chalk, sketch, spray, particle, grid, deform, filter, clone, tangent normal | Skip | None is a mask stamp. Each is its own renderer. Importing them as "some round brush" is the failure mode listed above — a file that loads and lies. |
| MyPaint engine presets inside Krita 5 | Skip for now | See the note on `.myb` at the end. |

What we can carry across, in the part that transfers best — the dynamics:

| Krita sensor | Ours | Note |
|---|---|---|
| Pressure | `Sensor.PRESSURE` | Straight through. |
| Tilt elevation | `Sensor.TILT` | Ours saturates at 63°; theirs at 90°. **The curve has to be rescaled, not copied.** Getting this wrong makes every tilt brush too weak by a third and it will not be obvious. |
| Tilt direction / ascension | `Sensor.ORIENTATION` | Both circular. `TiltFilter` already exists for the averaging trap. |
| Drawing angle | `Sensor.DIRECTION` | |
| Speed | `Sensor.SPEED` | Different reference scale (ours is 3 doc px/ms). Rescale. |
| Distance, Time, Fade | `Sensor.TIME` / `Sensor.DISTANCE` | `DISTANCE` is dead on this hardware and its KDoc says so. A preset driven by it imports to a constant, and the importer must **warn** rather than pretend. |
| Fuzzy per dab / per stroke | `Sensor.RANDOM_DAB` / `RANDOM_STROKE` | Direct. |
| Perspective, Rotation (canvas) | none | Drop, warn. |

Krita stores each curve as a list of control points in a string. `ResponseCurve.of(...)`
takes exactly that. This is the piece that will work best and it is also the
piece worth the most, because a curve is the part of a brush nobody wants to
re-author by hand.

Parameters with no home on either side, listed so neither is a surprise:

- **Theirs, not ours:** blending mode, the texture/pattern option (which needs a
  *pattern asset* — licensed data, and our grain is procedural on purpose),
  airbrush rate, mirror, per-sensor size-ratio locks, masked-brush overlays.
  Dropped, and every drop is reported by the importer.
- **Ours, not theirs:** `grain`, `burnish`, `stabilization`, `onset`. Imported
  presets get **grain 0 and burnish 0**. They will therefore look flatter than
  our own pencil, and that is correct: they are supposed to look like the Krita
  brush they came from. Sprinkling our grain onto imported brushes to make them
  "look better" is falsifying the comparison the whole plan is judged by.

One more mapping that will cause trouble if it is left implicit: **size means
different things**. Krita carries an absolute tip diameter in pixels. Our
`sizeMax` is *the widest mark the tool can make* — the pencil's KDoc is explicit
that its slider is the flat, not the point. The converter needs one stated rule
(start with: Krita diameter → `sizeMax`, `sizeMin` = 1.5 or the tip's own floor)
and a line in the generated file recording what it did.

## The picking

This is the part you asked for, and the design principle is: **the machine
proposes, you dispose, and the disposal is a file in git.**

1. **Fetch a bundle.** Into `brushes/incoming/`, which is `.gitignore`d. The
   bundle itself is never committed — not because it must not be, but because a
   30 MB ZIP in git for data we regenerate from is waste.

2. **`tools/kpp-sheet.py <bundle>`** — unpacks, reads `meta.xml` for the licence,
   parses each `.kpp`'s embedded XML, and emits two things:

   - **`sheet.png`** — a contact sheet. One cell per preset: Krita's own preview
     thumbnail, the preset name, the engine, and a badge — green (auto-tip pixel
     brush, we can do this), amber (bitmap tip, needs Wb5), red (engine we do
     not have). Plus a line under each naming any parameter that will be
     dropped.
   - **`picks.txt`** — every green and amber preset, one per line, **all
     commented out**, with engine, licence and warnings in the comment.

3. **You uncomment the ones you want.** That is the hand selection. A text file,
   diffable, reviewable, and reversible. Nothing in the pipeline ever adds a
   brush you did not uncomment, and re-running the tool never uncomments
   anything or reorders what you have edited.

4. **`tools/kpp-import.py --picks picks.txt`** converts *only* the picked ones
   into `.artiest-brush` files in `BrushCodec`'s existing line-based format —
   which is already diffable, already forward-compatible, and already skips
   lines it does not understand. Bitmap tips, where taken, land beside them as
   PNGs.

5. **The judgement pass.** A test renders a standard stroke with each imported
   brush and writes `compare.png`: our stroke next to Krita's preview, same
   cell. You look at it. Brushes that do not read right get **commented back out
   of `picks.txt`** — they do not get silently "fixed" by fudging the mapping,
   because a fudge that rescues one brush usually breaks four you already
   accepted. If a whole *class* fails the same way, that is a mapping bug and it
   gets fixed in the converter, once.

6. **What ends up in git:** `picks.txt`, the generated `.artiest-brush` files,
   any CC0 tip PNGs, the `LICENSES` file, and the comparison sheet as evidence.
   Not the bundle.

**And then the same idea on the tablet, later.** *Import Krita brushes* opens a
`.bundle` or `.kpp` from storage, renders a grid of swatches **with our engine**,
and you tick what you want onto the shelf. Same selection principle, and legally
free — nothing is redistributed. It is second, not first, because the desktop
tool is where eyeballing forty brushes at once is comfortable and where the
result is a reviewable diff.

## What has to change in the app before any of this lands

None of this is Krita's fault; it is what "three brushes" costs when it becomes
"thirty".

1. **Presets must become data.** `BrushPreset` is an enum of three whose
   `applyTo` is Kotlin. That is right for the three we authored and it cannot
   hold an imported one. Needs a `BrushLibrary`: built-ins (the three, staying
   as code — they are documented decisions, not data) plus file-backed presets
   from assets and from user storage, all behind one id.
2. **`BrushStore` stores an id string, not an enum name.** And `TUNING` needs a
   per-preset equivalent, or imported presets get reset by a bump that has
   nothing to do with them.
3. **`MainActivity`'s three hardcoded buttons become a shelf.** Scrollable,
   tagged, with rendered swatches. This is a Dock/panel job and it is probably
   the largest UI item in the list. **Thirty brushes with no tags or search is
   worse than three**, and that is a real risk, not a caveat.
4. **`BrushCodec` v2** — id, label, tip reference, falloff type. The decoder's
   "skip what you don't understand" rule means old files keep loading.
5. **A second mask source in the engine** (Wb5). `MaskGenerator.generate` is
   procedural-only. Bitmap tips mean a tip id in the `MaskCache` key, scaling a
   stored tip to the quantised size, and a memory budget for tips. This is the
   biggest engine change in the plan and it is deliberately late.
6. **Falloff shapes.** Krita's auto-tip has gauss and curve falloffs; we have
   one smoothstep band. One or two more falloff functions in `MaskGenerator`,
   or a lot of brushes import looking harder than they should.

## Work plan

Each item is separately useful, and the order puts the cheap refutations first.

| # | Item | Stop condition |
|---|---|---|
| **Wb1** | Preset library: data-backed presets, string ids, `BrushCodec` v2, shelf UI. No Krita anywhere. | If the shelf cannot show thirty brushes comfortably on this tablet, the import work has nowhere to land — fix that before importing anything. |
| **Wb2** | The reader: `.bundle`/`.kpp` parse, licence extraction, contact sheet, `picks.txt`. Read-only, no engine change. | If `meta.xml` licences turn out to be absent or unreliable across real bundles, shipping a curated set is off and only user-side import survives. |
| **Wb3** | Converter for auto-tip pixel brushes: parameter and sensor mapping, curve translation, tilt/speed rescaling. | — |
| **Wb4** | **First judgement pass.** Render `compare.png` over a picked set of ~30. | **If fewer than half read as their original, the mapping approach is wrong.** Fall back to using Krita presets as *inspiration* and hand-author a dozen the way the pencil was authored. That is a real outcome and not a failure of the phase. |
| **Wb5** | **DONE.** Bitmap tips in the engine: mask source, cache key, tip budget. Falloff shapes did not turn out to be part of it — see below. | **Cleared.** 1.10 ms for the largest mask a tip can produce, against a 16.6 ms frame. |
| **Wb6** | **DONE.** Second judgement pass, over predefined-tip brushes. | **Cleared.** 40 of 46 read as themselves; the six that do not share one cause. |
| **Wb7** | In-app import from `.bundle`/`.kpp`, with the swatch grid picker. | — |
| **Wb8** | **DONE.** Ship a curated CC0 starter set (~10–20), `NOTICE` amendment. | **Cleared.** Sixteen presets and six tips, every one traced to the `CC-0` line in the bundle's own `meta.xml`. |

## Risks

- **Krita's parameter semantics are not fully documented.** Some will have to be
  established by rendering in Krita and comparing output. That is black-box
  observation of a program's *output*, which the repo's rule permits and which
  is a different act from reading its source. Worth writing in the commit that
  introduces it, because a future reader will ask.
- **Thirty brushes is a UI problem before it is an engine one.** See Wb1.
- **Imported brushes will look flatter than our pencil**, having no grain. That
  is by design; it will still read as a regression to anyone who does not know.
  The shelf should show where a brush came from.
- **`.kpp` XML has changed across Krita versions** (4 vs 5 resource system). The
  reader has to tolerate both or state which it takes.
- **The temptation to claim compatibility.** The listing wording is "imports
  Krita brush presets (pixel brushes)". Not "Krita-compatible".

## An alternative worth checking before Wb3

MyPaint's `.myb` is **plain JSON** — a base value plus (input → piecewise-linear
curve) mappings per setting, which is our `CurveOption` almost exactly, and with
no PNG-metadata unwrapping in the way. It may map more directly than `.kpp`
does. Its brush collection's licensing has **not** been checked here and must be
before it is counted on; MyPaint itself is GPL-2.0, which says nothing about the
brush data either way. Half a day in Wb2 to find out, and it either provides a
second source or is ruled out cheaply.

## Deliberately not in this plan

- Vendoring Krita or GIMP source. The rule does not move.
- Shipping any data whose licence is not CC0 and traceable.
- Colour-reading brushes (smudge, blend), which need a document-model change
  Phase 2 explicitly deferred.
- A brush *editor*. Importing presets and editing them are different features,
  and this one is worth nothing if the shelf is unusable, so the shelf comes
  first.

## What the first import actually did

> 2026-09-12. `tools/krita-brushes.py` and `KritaSheetTool`, against
> `Krita_4_Default_Resources.bundle` taken from the copy of Krita installed on
> the DTH-A116. Read-only; nothing from Krita's source.

### Wb2's stop condition is cleared

*"If `meta.xml` licences turn out to be absent or unreliable across real
bundles, shipping a curated set is off."* The bundle's `meta.xml` carries

```xml
<meta:meta-userdefined meta:name="license" meta:value="CC-0"/>
```

alongside the author line — "Deevad with derivations of the brushes of Ramon
Miranda, Razvanc, Radian, Wolthera, Storm, Scottyp and other." So the licence is
present, machine-readable and CC-0, and Wb8's curated set is on. **One bundle is
not "across real bundles"**, and the rule stands: any brush whose licence cannot
be traced to a sentence in a `meta.xml` is dropped.

### Where the bundle actually is

Not where it looks. Krita's own copy in `/sdcard/Android/data/org.krita/files/`
is mode 600 and `adb pull` cannot read it — but the bundle ships *inside the
APK*, at `assets/krita/bundles/`, and the APK is world-readable. That is how the
files were obtained, and it is worth writing down because the obvious path
fails.

### What converts, and what does not

Of the bundle's **118 presets**:

| | Count | |
|---|---|---|
| `paintbrush` + `auto_brush` | **35** | converts today, no engine change |
| `paintbrush` + `gbr_brush` / `png_brush` / `svg_brush` | 47 | needs Wb5's bitmap tips |
| `colorsmudge` | 15 | needs a colour-reading paintop |
| everything else (spray, deform, sketch, filter, hatching, …) | 21 | paintops this engine does not have |

Of the 35, five are blend-mode brushes — *Adjust Dodge*, *Adjust Multiply* and
friends — and the converter now **refuses** them. Their `CompositeOp` is not
`normal` and nothing here carries one, so importing one drew a slab of black
where Krita draws a glow. That is worse than not importing it, because it looks
like the converter working.

**30 brushes convert.**

### The judgement pass

Wb4's bar: *"if fewer than half read as their original, the mapping approach is
wrong."* Rendered side by side with Krita's own preset preview:

**26 of 30 read as themselves.** The pencils are pencils, the inks are thin and
crisp, the charcoals are grainy, *Marker Chisel Smooth* is a chisel that turns
with the barrel, *Shapes Square* scatters, *Texture Reptile* is a dense grainy
band. The approach is sound.

The four that do not, and what each one names:

1. **Airbrush Soft** — a 600 px nib. Correct, and invisible in a 480 px preview.
   A preview-scale problem, not a conversion one.
2. **Eraser Soft** — converts correctly to `erase 1`, and the swatch renderer
   draws erasers as ink, so the row is a black slab. `BrushSwatch` should paint
   an eraser onto something.
3. **Pencil-3 Large 4B** — too faint. Krita's 4B leans on its *texture pattern*,
   which is a bitmap; our grain is procedural and cannot stand in for it.
   Waiting on Wb5.
4. **Pixel Art** — a 1 px nib. Right, and unreadable at any preview size.

### The three things that had to be established by rendering

None of these is in Krita's documentation; each came from a preset that came out
wrong and was chased down.

- **`declination` runs the other way from `Sensor.TILT`.** Krita's declination
  is 1 with the pen upright; ours is 0. Established from *Marker Chisel Smooth*,
  whose ratio curve is 0.035 at declination 0 and 1 at declination 1 — a chisel
  is a thin wedge laid over and a round dot held upright. Every curve on that
  sensor is mirrored on the way in.
- **The mask generator matters more than the fade.** `default` fades over the
  band `hfade` describes, so `1 - fade` is the whole of it — but `gauss` and
  `soft` are soft *by construction* and both ship at fade 0. Reading `1 - fade`
  gave them hardness 1 and turned *Eraser Soft* and *Airbrush Soft* into solid
  black slabs. The generator is now a ceiling on hardness (0.85 for gauss, 0.40
  for soft) until Wb5 adds a real second falloff.
- **Krita's dab alpha is not our flow, and the difference is the overlap.**
  Krita lays `1 / spacing` dabs across a diameter and each carries
  `opacity × flow`; `StrokeBuilder` already inverts that overlap, so `flow` here
  *means* the coverage of one pass. Carried straight across, *Pencil-3 Large 4B*
  painted at 0.05 coverage and its swatch was blank paper. Through the
  accumulation — `1 - (1 - a)^(1/spacing)` — it is 0.64, and `opacity` goes to 1
  because the build-up is in the flow and Krita's normal mode has no ceiling of
  its own.

### What this does not answer

- The spike is Python in `tools/`. Wb2 and Wb3 proper are Kotlin behind the
  shelf's import button, and the `picks.txt` contact sheet is still to write.
  `.bundle` reading landed with Wb5, because a tip's size cannot be known
  without the tip.
- **Sizes are carried across 1:1 and that is a guess.** A Krita preset's
  diameter is in canvas pixels and its canvas is whatever the artist made; ours
  is a 3300 px page. A 5 px fineliner reads as a fineliner on both, so nothing
  obviously breaks — but nobody has drawn with one for an hour.

## Wb5 and Wb6 — the nib that is a picture

> 2026-09-13. `engine/…/brush/Tip.kt`, `MaskGenerator.stamp`, `ink/Tips.kt`, and
> the converter's tip extraction. Measured against the same bundle.

### What a tip is

`Tip` is one byte of coverage per pixel and a chain of halved copies of itself,
and `MaskSpec` gained one nullable field to point at one. That field is the
whole of the feature: everything upstream — sensors, curves, spacing, the
scratch buffer, the selection — asks a bristle stub exactly the questions it
asks a round dab, and only the eleven lines that turn a size into coverage
differ.

Three things about the *cache* had to be re-derived, because every one of them
was an ellipse's property rather than a dab's:

- **Rotation folds over a whole turn, not half.** An ellipse at θ and at θ+π is
  the same shape and `MaskTolerance` spends that symmetry; a bristle fan upside
  down is a different mark. A tipped dab gets 128 rotation buckets over 2π where
  an ellipse gets 64 over π — the same angular step, twice the buckets.
- **A round tipped dab keeps its rotation.** The collapse that saves 64 buckets
  for a circular ellipse describes the *squash*, not the mark. Applying it to a
  picture would draw every fan pointing the same way.
- **Hardness leaves the key.** A picture brings its own edge and
  `MaskGenerator` ignores hardness over one, so letting it into the key would
  build the same bitmap sixteen times for a brush whose hardness moves with
  pressure.

**Falloff shapes — item 6 of "what has to change" — did not turn out to be part
of this.** The argument for them was that Krita's `gauss` and `soft` generators
have edges our one smoothstep band cannot make. That is still true, and it is
still worth doing, but it is about the *procedural* nib and has nothing to do
with tips. Wb3's hardness ceiling stands in for it; the item moves rather than
closing.

### The mip chain, and what it costs

A 600-pixel tip drawn at 9 pixels is a 66:1 minification, and bilinear sampling
of that reads 81 texels out of 360,000 — so the dab flickers as the stroke
moves, because *which* 81 changes. Halved copies, and sample the one nearest the
size being drawn. A third more memory, built once, at registration.

With the chain underneath it, two subsamples an axis is the *matched* number
rather than a cheapened one: the level is chosen so what is left to resolve is
inside one octave, and two samples an axis is exactly one octave. The ellipse
keeps four, because it is solved analytically with no filtering underneath.

Wb5's stop condition was *"if tips push the dab loop past W0's measured budget,
tips do not ship."* Measured on the bench, with the biggest tip the bundle
contains (454 px) at the biggest size anything asks for:

| | |
|---|---|
| A 128 px tipped taper, 226 dabs, 105 masks, from cold | 9.9–13.8 ms |
| The single largest mask — what one frame actually pays | **1.10 ms** |
| Masks held | 112 KiB |

A stroke crosses about one size bucket a frame, so a frame pays for one mask and
not the stroke's whole bill. 1.10 ms against 16.6. **Tips ship.**

### The reader, and the trap in it

A tip arrives as a PNG and nothing in the file says which channel the mark is
in. The rule is: a usable alpha channel is the tip, and otherwise `255 - grey`,
which is GIMP's black-ink-on-white-paper convention.

**"Usable" is a fraction and not "any", and the bundle taught that immediately.**
The first version asked whether *any* pixel was less than opaque.
`oil_knife.png` is a grey-plus-alpha PNG whose picture is entirely in the grey
and whose alpha is opaque for all but 0.004% of its 90,000 pixels — four stray
pixels, enough to send it down the alpha branch and turn a palette knife into a
300-pixel solid slab. The floor is one pixel in 256; an antialiased rim on the
smallest plausible tip clears it three times over.

`.gbr` and `.gih` are GIMP's own containers and nothing on Android reads them,
so the converter decodes them and writes grey-plus-alpha PNGs — black ink at the
coverage's alpha, which is correct under *both* halves of the rule rather than
relying on either.

### Wb6's judgement pass

Of the bundle's 118 presets, **76 now convert and 46 of those carry a tip**,
against 30 before. Rendered beside Krita's own preview:

**40 of 46 read as themselves.** The bristles are bristles, the chalks are
grainy, the stamps are grass and mountains and sparkles, *Waterpaint Soft Edges*
is a soft blob rather than the slab the alpha bug made of it.

The six that do not **all fail the same way, and it is one cause**: *Chalk
Grainy*, *Dry Bristles Eroded*, *Pencil-6 Quick Shade*, *Texture Wood Fiber*,
*Waterpaint Hard Edges* and *Stamp Stylised Tree* show the tip repeating in a
visible regular pattern where Krita's shows a dense irregular one.

**A `.gih` is several tips, and this engine takes the first.** The bundle's
`graphite_grain.gih` says `ncells:7 … sel0:random` — Krita picks a different
cell for every dab, at random, and that is what breaks up the pattern. One tip
per brush turns a seven-cell chalk into one chalk stamped 226 times. It is a
real loss, it is named rather than fudged, and the fix is a tip that carries
frames plus a per-dab pick — an engine change of the same size as this one, and
not part of it.

Two more are preview-scale rather than conversion problems, the same as *Airbrush
Soft* in Wb4: *Texture Big* at 435 px and *Stamp Bokeh* at 384 px do not fit in a
232-pixel swatch page. They are counted among the 40 because the stroke is right
and the picture of it is too small.

### One thing Wb8 shipped that the renderer had never been measured against

`airbrush-soft` is **600 document pixels wide**. Every preset this engine was
tuned on tops out at 96, so one imported brush is six times larger than anything
the dab loop had ever been asked to draw — one of its dabs writes 5% of the
page, and at `spacing 0.1` ten of them overlap at every point. It took about a
second for an airbrush stroke to appear on the tablet, and the cause and the fix
are `docs/big-nib-plan.md`.

Nothing about the brush was wrong. What was wrong is that **an import can
produce a nib an order of magnitude outside the range the renderer was measured
on, and nothing said so.** `tools/krita-brushes.py` now prints a `BIG` line for
any preset it writes above 128 px, so the next one is noticed on the day it
arrives rather than in a report six weeks later.

### Two corrections the eraser work made possible

- **The converter writes `erase 1` now, and it used to refuse to.** The refusal
  was right at the time: erasing was a mode a toolbar toggle owned and re-read at
  every stroke, so a brush file claiming it made a claim nothing honoured. There
  is no toggle any more. See `docs/ui-space-plan.md`, Us2.
- **Wb4's second failure is fixed.** *"`Eraser Soft` converts correctly to
  `erase 1`, and the swatch renderer draws erasers as ink, so the row is a black
  slab."* `BrushSwatch.rubbedOut` punches the stroke through a wash now, so an
  eraser's row shows the hole it makes.

## Wb8 — the sixteen a fresh install has

> 2026-09-13. `app/src/main/assets/brushes`, `app/src/main/assets/tips`,
> `ui/StarterBrushes.kt`, `tools/brush-picks.txt`.

### The picking, as the plan described it

`tools/brush-picks.txt` is the file this plan asked for under *"the machine
proposes, you dispose, and the disposal is a file in git."* The converter's
`--write-picks` proposes every preset it can convert, **all commented out**;
uncommenting a line is the decision; `--picks` converts only what is
uncommented. Re-running the proposal keeps the file line for line and appends
only ids it has never mentioned, so regenerating after a bundle changes cannot
silently undo a choice.

Sixteen are uncommented, chosen to fill the gaps around the five the app
authors itself rather than to be a tour of the bundle:

| | |
|---|---|
| Pencils | Pencil-1 Hard, Pencil-3 Large 4B, Pencil-4 Soft, Pencil-5 Tilted |
| Charcoal | Charcoal Pencil Large, Charcoal Pencil Thin |
| Ink | Ink-1 Precision, Ink-2 Fineliner, Ink-3 Gpen, Ink-7 Brush Rough |
| Marker | Marker Chisel Smooth |
| Paint | Bristles-1 Details, Bristles-3 Large Smooth |
| Other | Chalk Details, Airbrush Soft, Stamp Grass |

Four of them stamp a picture. 160 KiB of APK all told, which is the whole cost.

None of the bundle's three erasers is among them. The app has two of its own
now and they are brushes rather than a mode, so a third and fourth called
*Eraser Circle* and *Eraser Small* would be four rows doing one job.

### Copied out, not read in place

`StarterBrushes` copies the assets into `files/brushes` and `files/tips` the
first time a version of the set runs. **Copied, so they are ordinary brushes**:
a starter set that lived in the assets would need a third origin beside built-in
and saved, and every question the shelf asks — can I tune it, save over it,
delete it, does a tuning bump move it — would need a third answer. Copied out, a
charcoal pencil from the bundle is exactly as much yours as one you made this
morning.

The cost is that deleting one is permanent, and that is the right cost. A
starter set that grew back would be a shelf that will not let you tidy it —
which is why the seed is keyed on a **version number** and not on "is the
directory empty". An empty directory is what you have after deleting all
sixteen.

### What the tablet showed

Twenty-seven rows in the shelf, all of them rendering their own mark, the
imported ones among them. *Chalk Details* — a tipped brush — draws a grainy
chalk line on the glass, which is the first time a picture-nibbed brush has been
used to draw anything outside a test.

### One thing this found in the build, not in the brushes

`testOptions.unitTests.isIncludeAndroidResources` was off, so Robolectric could
not see `assets/` at all. The first version of `StarterBrushesTest` passed while
reading nothing: an asset listing came back empty, the loop ran zero times, and
every assertion about the shipped set was true of the empty set. Worth writing
down because the failure mode is a green test rather than a red one.


## The size slider dragged half a brush

From the tablet:

> *"The ghost indicator of the eraser does not match the size of the brush. The
> indicator is a 100px diameter circle, even when the actual size of the eraser
> 'brush' is just 20."*

A brush has a size **range**, not a size: `sizeFor` is `sizeMin + (sizeMax -
sizeMin) * f`, and the hard eraser is authored `size 60 .. 96` so that a light
touch rubs out less than a firm one. The slider only ever wrote the 96.

Drag it to 38 and the brush is `size 60 .. 38` — **a range running backwards**.
The eraser got *wider* the more lightly it was held, and never went under 38
however hard it was pressed. Measured on the DTH-A116 at that setting, off the
instruments panel:

```
tilt  0.0 deg max last stroke   dab 59.1..59.1 doc px
back  Hard eraser   38.0 doc px
```

The ring said 38 and the eraser was taking 59.

### The ring was not the thing that was wrong

That is the part worth writing down, because the report points at the ring and
the ring is innocent. `cursorDiameterDocPx` reads `sizeMax`, `sizeMax` said 38,
and the ring is documented as a **reach** indicator — *if I press, what does
this cover*. The brush was the one making a mark nobody had asked for.

`Brush.resizeTo(max)` scales the whole range, so the ratio the brush was
authored with survives being resized: an eraser whose light end was five eighths
of its heavy end still has a light end five eighths of its heavy end at any
size. `back 38.0` and `dab 38.4..38.4` now, at a full press.

### Why it is a function and not the setter

Every other writer of the size range in the tree sets the two together —
`adoptBrush`, `copyScalarsOnto`, `BrushCodec.decode`, each `BrushPreset`,
`StrokeMove` — and a `sizeMax` setter that moved `sizeMin` as a side effect
would either double-scale those or make them depend on the order two
assignments happen to be written in. One caller wants this, so one caller asks
for it.

### And a tuning bump

`BrushPreset.TUNING` goes to 5. Every brush saved or tweaked before this carries
a bottom that was authored against a different top, and there is nothing in
those numbers that says what the ratio was meant to be — so they cannot be
repaired by arithmetic and are dropped instead, which is what that number is
for. The tablet's stored eraser read `size 60 .. 38` and is back to `60 .. 96`.

## Sources

- [Krita 4 Preset Bundle overview](https://docs.krita.org/en/reference_manual/krita_4_preset_bundle.html)
- [Loading and saving brushes](https://docs.krita.org/en/user_manual/loading_saving_brushes.html)
- [Resource management / bundles](https://docs.krita.org/en/reference_manual/resource_management.html)
- [KDE community wiki: PaintOp Presets](https://community.kde.org/Krita/PaintOp_Presets) — presets are PNGs with the settings as XML in the text area
- [David Revoy, Krita brushes 2025-01 bundle](https://www.davidrevoy.com/article1060/krita-brushes-2025-01-bundle) — CC0, explicitly permits inclusion in other software
- [krita-artists: patterns and brushes, commercial use](https://krita-artists.org/t/patterns-and-brushes-commercial-use/135925) — defaults "mostly CC-0", check each bundle
- [krita-artists: brush bundle license](https://krita-artists.org/t/brush-bundle-license/53534)
