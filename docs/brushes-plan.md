# Brushes and pencils, and where they come from

> Authored 2026-09-10, after Phase 2 shipped three hand-authored presets (pen,
> pencil, marker) and the question became "how do we get to thirty without
> authoring thirty". Nothing here has been measured. Every claim about how a
> Krita preset maps onto our engine is a **prediction awaiting a render
> comparison**, and the work plan is arranged so the first refutation arrives
> before the expensive part starts.

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
| **Wb5** | Bitmap tips in the engine: mask source, cache key, tip budget, falloff shapes. | If tips push the dab loop past W0's measured budget, tips do not ship. Measure before believing. |
| **Wb6** | Second judgement pass, over predefined-tip brushes. | Same bar as Wb4. |
| **Wb7** | In-app import from `.bundle`/`.kpp`, with the swatch grid picker. | — |
| **Wb8** | Ship a curated CC0 starter set (~10–20), `LICENSES`, `NOTICE` amendment. | Any brush whose licence cannot be traced to a sentence in a `meta.xml` or an author's own page is dropped. |

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

## Sources

- [Krita 4 Preset Bundle overview](https://docs.krita.org/en/reference_manual/krita_4_preset_bundle.html)
- [Loading and saving brushes](https://docs.krita.org/en/user_manual/loading_saving_brushes.html)
- [Resource management / bundles](https://docs.krita.org/en/reference_manual/resource_management.html)
- [KDE community wiki: PaintOp Presets](https://community.kde.org/Krita/PaintOp_Presets) — presets are PNGs with the settings as XML in the text area
- [David Revoy, Krita brushes 2025-01 bundle](https://www.davidrevoy.com/article1060/krita-brushes-2025-01-bundle) — CC0, explicitly permits inclusion in other software
- [krita-artists: patterns and brushes, commercial use](https://krita-artists.org/t/patterns-and-brushes-commercial-use/135925) — defaults "mostly CC-0", check each bundle
- [krita-artists: brush bundle license](https://krita-artists.org/t/brush-bundle-license/53534)
