# The learner — the third artist, and the cheapest one to serve

> Authored 2026-09-14, from the user's list given after the Inker was finished,
> and from a read of how ten programs and six websites serve beginners today.
> Sources at the end. Everything here is **read, not copied** — the same rule
> every other reference in this repository is under. No competitor's source was
> opened; what is taken is what any user could describe from memory after using
> the program.

The ask, verbatim:

> I have another category of artist that needs its special catering. The
> beginner, learning artist. [...] Reference feature: add references in a easy
> way in a side-by-side pane · Reference library · Tutorials feature ·
> Knowledge base: the artist can create its own knowledgebase with his own
> examples on how to draw stuff.

## The claim this document makes

**Four of those are one feature, and artiest can build it in a way no other
drawing program can.** Not because of a clever idea, but because of something
the Inker already paid for: this program stores a drawing as *the strokes that
made it*, not only as the pixels they left behind. Every other program on this
list can show a learner a finished picture or a sped-up video. This one can show
them the drawing being made, one stroke at a time, and let them start drawing
from any point in it.

That is the killer feature, and the argument for it is below. It is also, by the
arithmetic at the end, the **cheapest** of the three remaining artist identities:
roughly 35–54 days against the Painter's 120–160 and the Comic's 250+, because
almost all of it is exposure of things that exist.

## Part 1 — what is in the wild

### Reference, which turns out to be four different features wearing one name

| Program | What it actually is | The part worth stealing |
|---|---|---|
| **Procreate** *Reference Companion* | A small floating window over the canvas with three tabs: **Canvas**, **Image**, **Face**. | The **Canvas** tab. It shows *your own drawing*, whole, while you are zoomed into an eyelash. Nobody copies this and beginners use it more than the image tab. |
| **Clip Studio Paint** *Sub View* | A docked palette holding a **set** of images you flick through with arrows; zoom, rotate, flip inside the pane. | **"Switch to eye dropper automatically."** Put the pen on the reference and you are picking its colour; take it off and you are drawing. This is the feature people name when you ask them why they use it. |
| **Krita** *Reference Images Tool* | Images pinned **onto the canvas** itself, outside the layer stack: move, rotate, tilt, resize. | Two sliders — **opacity** and **saturation** — and the storage choice: *embed in the file* or *link to the file on disk*. Both are decisions we will have to make and Krita has already made them well. |
| **PureRef** | A separate always-on-top program: an infinite board you drop images onto and arrange. | That the board is a *thing you keep*, per project, and that collecting is frictionless. Also the warning: it is a second program, which is the problem. |
| **artiest today** | `IMPORT` → `PictureImporter` drops the picture in **as a layer of its own**, at an opacity you can pull down. | It is already half a reference feature, and its KDoc already says why: *"an imported picture is almost always a reference."* |

The pattern: **a reference is either a pane you look at, or a layer you draw
over, and a learner needs both.** Krita and Procreate give you one; CSP gives you
the other and the eyedropper on top.

### Practice, which lives outside every drawing program

Line of Action, Quickposes, SketchDaily, DrawGestures, Pose Library, SketchKit —
all of them websites, all of them the same shape: a pose appears, a clock runs
30 s / 1 m / 2 m / 5 m, the next pose appears. One of them says the useful thing
out loud:

> *"The single biggest gain from a tool like this isn't the reference library —
> it's the timer, as the pressure forces you to commit to lines, prioritize the
> largest shapes first, and stop fussing with details."*

Not one drawing program has this built in. Every artist doing gesture practice
on a tablet is running a browser in one window and their drawing app in another,
and on this tablet, in this program, there is no other window.

### Teaching, which no drawing program does well

- **Procreate, Clip Studio, Krita, Infinite Painter, Sketchbook**: no course in
  the app. The learning is on YouTube, Proko, Drawabox, Skillshare, Domestika.
  Infinite Painter's own "beginner's guide" is a 119-minute Skillshare class by
  a third party.
- **The apps that do teach** — SketchAR, Drawing Desk, ArtLoop, DoodleVerse —
  are tracing toys. AR projection onto real paper, "1000+ lessons", photo-to-line-art.
  They teach a beginner to *copy an outline* and they do not grow into a program
  the learner can keep using. Nobody graduates from them.
- **ibisPaint is the closest anyone has come to an answer**, and it is worth
  studying: every drawing is recorded automatically, and uploading a drawing
  uploads the **time-lapse video and the drawing file** with it. Its community
  page is thousands of people learning by watching each other's process. But it
  is a video, it needs an account, and it needs their server.
- **Procreate's time-lapse replay** is the same idea kept private: the app
  records the whole session and you can watch it back without leaving the app.

So the state of the art in "show me how it was done" is **a sped-up video**. A
video is the wrong shape for learning: you cannot stop it *at a stroke*, it
cannot tell you which brush made the mark, it is a hundred megabytes, and you
cannot draw from inside it.

### Mistake-finding, which is the same two tricks everywhere

Every beginner guide found says the same two things, and both are one button:

1. **Flip the canvas horizontally.** *"Skewed facial features, lopsided eyes and
   unbalanced poses will jump out at you in less than a second."*
2. **Squint, or turn it greyscale**, to check values without the colour.

Neither is a learner feature strictly speaking — professionals flip constantly —
but for a beginner it is the difference between "something is wrong and I don't
know what" and seeing it.

### The rest, and what it costs

- **3D pose figures** (Clip Studio): pose a mannequin from any angle, adjust
  proportions, match a photo with a scan. Genuinely loved, genuinely enormous —
  a rigged model format, a viewport, a posing interface. **Out**, and named here
  so nobody has to re-decide it.
- **The grid method** — a grid over the reference and the same grid over the
  page, so proportions can be copied square by square. Old, still taught, and
  nearly free here: `docs/guides-plan.md` item 9 is a 2D grid and it is unbuilt.
- **The 50 % rule** (Drawabox): half your time on exercises, half drawing for
  fun, because "the final result often gets in the way of true learning". Worth
  knowing because it tells you what *not* to build: nothing in this document
  should measure a beginner's output or score it.

## Part 2 — the gap, stated plainly

Nobody joins these up. Reference lives in one program, practice in a browser
tab, teaching on YouTube, and the drawing in a fourth place. A beginner is asked
to run four things to do one thing, on a device that shows one thing at a time.

And underneath that, a second gap that nobody has even tried to fill:

> **There is no program in which your own drawings become the thing you learn
> from.** You draw a good hand on Tuesday. On Friday you cannot draw it again
> and there is no record of how you did it — only the picture of the result,
> which is the one part that does not teach you anything.

## Part 3 — the killer feature: a drawing that can teach itself

### Why this program, and not the others

`StrokeRecord` already holds, for every ink stroke ever drawn:

- `id` — unique and **ascending in draw order**
- `brush` — an index into the sheet's brush table
- `colorArgb`, `erase`, `seed`, `dabBase`
- `guide` — which ruler it was drawn against, or none
- `bounds` — what it painted
- and the samples: x, y, pressure, tilt, orientation and **milliseconds from
  pen-down**, delta-coded, about 8.7 KB for a three-second stroke

`VectorSheet` holds them in order, `StrokeCodec` writes them to
`strokes/<n>.ink` beside the PNG on every autosave, and `SheetRebuilder` already
re-renders a sheet from them — that machinery is the entire foundation of the
Inker, it is built, measured and on the tablet.

**A replay is therefore not a feature to build. It is a feature to expose.**
Playing a drawing forward is "paint the next record", which is the same cost as
drawing it in the first place — Ik0 measured 3.6–3.8 ms per pen stroke. Stepping
*backwards* is the expensive direction, because it is a rebuild (1.09 s for 300
pen strokes, 6.41 s for pencil), and that is a solvable problem with periodic
snapshots rather than an unsolvable one.

### What that makes possible, that a video cannot

- **Scrub to stroke 214** and the canvas is exactly what it was at stroke 214.
  Not a frame that looks like it: the drawing, re-rendered.
- **Ask a stroke what it was.** Which brush, which colour, which ruler, how long
  it took. The record knows all four, and this is the question a beginner
  actually has — *"what did they draw that with?"*
- **Draw from there.** Rewind to the sketch under the line art and keep going,
  your way. A lesson you can take over halfway through is a different kind of
  object from a video.
- **Kilobytes, not megabytes.** A thousand strokes is ~8.7 MiB of records, and
  they are already being written. Procreate's 4K time-lapse is not.
- **It is sharp at any zoom**, because it is re-rendered rather than scaled —
  which is also, not coincidentally, what Ik11 is about.

### And then the knowledge base is a small step

A **card** is: a title, a note in the user's own words, and a drawing that can
replay itself. That is the whole format.

- The user draws a hand, taps **Keep this**, types *"start with the box, then
  the sausage fingers"*, and it is in their deck. That is the user's item 4,
  the knowledge base, and they authored it by drawing — which is the only way a
  beginner will ever actually keep notes.
- A **tutorial** is a card somebody else made. Same format, same player, same
  "draw from here" button. That is the user's item 3, and it costs us a *format*
  rather than a curriculum.

This is the part worth being clear-eyed about: **we are not going to write a
drawing course.** Authoring a curriculum is not a software project and pretending
otherwise is how this plan would fail. We ship the format, five or six honest
cards made in the app, and the ability for the learner — or their teacher, or
anyone on the internet — to make more and pass them around as files.

### The honest limitation, stated before it is discovered

**Replay works on ink sheets only.** A sheet has stroke records because
`VectorSheet` gave it them; a painted sheet, an imported picture and an `.ora`
layer from Krita have pixels and nothing else. A card made from a drawing that
was half-painted replays the ink and shows the paint arriving in one step, and
the player has to *say so* rather than quietly lie about it.

That is not a defect to hide. It is a reason the Learner workspace should open
on ink brushes, which it wants to do anyway.

## Part 4 — the work plan

Days are focused working days in the sense the other plans use.

| # | Work item | Module | Risk | Depends on | Days |
|---|---|---|---|---|---|
| **Lr1** | **The eyedropper.** Pick a colour with the pen — from the canvas, and later from the reference pane. There is no colour picker in this program at all today, which is a hole in the *drawing* program before it is a hole in the learning one. | `:app` | Low | — | 2–3 |
| **Lr2** | **The reference pane.** A docked surface in the existing cell grid holding a set of pictures: flick between them, pinch to zoom, rotate, flip, and **pen-on-the-pane picks the colour**. Docks, fixates and resizes exactly like the layers panel, because it is the same kind of object. | `:app` | Med | Lr1 | 4–6 |
| **Lr3** | **The reference library.** Pictures on disk under the app's own files, added from the system picker, the clipboard or the camera; thumbnails; a set attached to a drawing and a set that is always there. Krita's choice made explicitly: **copy into the library, do not link**, because a phone photo that moves breaks a link silently. | `:app` | Med | Lr2 | 4–6 |
| **Lr4** | **The reference layer, finished.** `PictureImporter` already lands a picture as its own layer. It needs **lock** (the pen cannot paint it — this does not exist and the Painter wants it too), a **desaturate** toggle, *fit to page*, and one switch that hides every reference layer so an export never bakes one in. | `:app` | Low | — | 2–3 |
| **Lr5** | **The mirror and the grey.** Two view-only buttons: flip the canvas horizontally, and show it in greyscale. Neither edits anything, neither is undoable, both are one pass in `StackCompositor` and both are the most-repeated advice in every beginner guide there is. | `:app` | Low | — | 2–3 |
| **Lr6** | **Replay.** Scrub a drawing stroke by stroke; play it at the pace it was drawn; at each stroke show the brush, the colour and the ruler. Snapshots every N strokes so scrubbing backwards is not a full rebuild. **The one high-risk item, and the one everything else leans on.** | `:app` | **High** | Ik3, Ik5 | 5–8 |
| **Lr7** | **The card, and the deck.** Title, note, a replayable drawing, tags. Decks on disk beside projects, one file per card, a card openable as a new drawing to practise on top of. *Keep this* from the canvas; the deck as a panel. | `:app` | Med | Lr6 | 6–9 |
| **Lr8** | **A starter deck and the Learner workspace.** Five or six cards drawn in the app, and a fifth shipped workspace that is **smaller** than the others. | `:app`, docs | Low | Lr7 | 3–5 |
| **Lr9** | **Practice sessions.** Choose a set from the library, choose 30 s / 1 m / 2 m / 5 m, the picture changes and the page turns. Auto-advance, pause, skip, and no score of any kind. | `:app` | Med | Lr3 | 4–6 |
| **Lr10** | **The grid method.** `docs/guides-plan.md` item 9 — a 2D grid guide — plus the same grid drawn over the reference pane. Two grids of the same divisions is the whole trick. | `:engine`, `:app` | Low | Lr2 | 2–3 |
| **Lr11** | **Measure it, and the feel pass.** Replay cost on a real 500-stroke drawing; library size on disk; and an hour of somebody actually learning with it. | device, docs | Low | all | 1–2 |

**Total: 35–54 days.** For comparison, `docs/master-plan.md` prices the Painter
at 120–160 and the Comic at 250+.

### If only three things get built

**Lr1, Lr2 and Lr6 — the eyedropper, the reference pane and replay. 11–17
days.** That is enough to know whether the idea is right: a beginner can keep a
reference on screen and steal its colours, and can watch any drawing rebuild
itself stroke by stroke. Everything else in the list is a consequence of those
three being good.

## Part 5 — the Learner workspace has *fewer* buttons

`docs/master-plan.md`: *"A workspace named Inker is a promise."* A workspace
named **Learner** makes a harder promise than the others, because the person
opening it cannot tell a good interface from a bad one yet and will assume
anything confusing is their own fault.

So the Learner workspace is the one place where the rule is **subtraction**:

- Two brushes and an eraser. Not the shelf of sixteen.
- No blend modes, no selection panel, no guides panel — `CatalogueFilter`
  already does exactly this, and `docs/ui-space-plan.md` has already been
  through the argument that a panel of twenty-one buttons is overwhelming.
- The reference pane and the deck **on screen by default**, because a feature a
  beginner has to discover is a feature they do not have.

This is also the honest test of the workspace system: if the system cannot
express "the same program, with three quarters of it hidden", then P0 did not
deliver what it promised.

## Part 6 — the traps

1. **Copyright, and the export that bakes a reference in.** A reference library
   invites people to import pictures they do not own. Two rules, both
   enforceable in code: **nothing leaves the tablet** — no upload, no sync, no
   account, ever — and **a reference layer is never exported**, which is why
   Lr4's hide-all switch is part of the work item and not a nicety.
2. **The knowledge base turning into a note-taking app.** A card is a drawing
   with a sentence on it. The moment it wants rich text, folders, links and
   search across notes, it is a different product and the drawing program has
   stopped being the point. Cap it: **title, one note, tags, one drawing.**
3. **Replay lying about painted sheets.** See the limitation above. The player
   must say "this sheet has no strokes to replay" rather than skipping to the
   end and looking broken.
4. **Scrubbing backwards.** Ik0's numbers are the warning: a full rebuild is
   1.09 s for 300 pen strokes and 6.41 s for pencil. Snapshots every N strokes
   are the answer and they cost memory — which lands on the same cap
   `docs/layer-effects-plan.md` trap 2 makes every structural item restate.
   A snapshot is a `Layer` and a `Layer` is 27.19 MiB.
5. **The library eating the disk.** Phone photographs are 3–8 MB each and a
   beginner will import forty. Copy them in downscaled to the page's own size —
   which `PictureImporter` already does for an import — and show the total.
6. **Tutorials as content debt.** Five cards shipped once is a feature. A
   promised course is an obligation that outlives the enthusiasm for it. Ship
   the format; ship a handful; never promise the twentieth.
7. **Gamification.** Streaks, badges and a score are the standard answer to
   "how do we keep beginners coming back" and they are the wrong one here. The
   50 % rule exists because *the finished result already gets in the way of
   learning*; adding a number to chase makes that worse. A practice log that
   only says what happened is the most this should ever do.

## Part 7 — deliberately not in this plan

- **Any account, feed, community or upload.** ibisPaint's community is genuinely
  the best learning feature in any drawing app and it is a server, a moderation
  problem and a privacy problem. Cards are files; files can be shared by the
  means people already share files.
- **3D pose figures.** See above.
- **AI anything** — photo-to-lineart, pose-from-photo, "critique my drawing".
- **A written curriculum.** The format, not the course.
- **AR tracing onto real paper.** It is a different product and it does not grow
  into this one.

## Where this sits against the master plan

`docs/master-plan.md` has the Inker finished and P4 open, with Painter or Comic
as the choices. **The Learner is a third choice and it changes the shape of the
question**, because it is not a fourth identity competing for the same 150 days:

- It is **35–54 days**, a third of the Painter.
- Almost every item is *exposure* of something already built — the stroke
  records, the workspace system, `PictureImporter`, `StackCompositor`,
  `CatalogueFilter`, the guides.
- Three of its items are not learner features at all and are owed to every other
  identity: **the eyedropper does not exist** (Lr1), **layers cannot be locked**
  (Lr4), and **the canvas cannot be flipped** (Lr5).
- And it is the only one of the three that makes the program worth opening on
  the first day rather than the hundredth.

## Sources

Read for this document on 2026-09-14. No source code of any program below was
consulted.

- [Procreate — Reference Companion](https://help.procreate.com/articles/ZWopfZ-reference)
- [Procreate — Video and time-lapse replay](https://help.procreate.com/procreate/handbook/actions/actions-video)
- [Clip Studio Paint — Sub View palette](https://www.clip-studio.com/site/gd_en/csp/userguide/csp_userguide/545_subview/545_subview_0.htm)
- [Clip Studio Paint — acquiring colours from a reference image](http://www.clip-studio.com/site/gd_en/csp/userguide/csp_userguide/545_subview/545_subview_procedure.htm)
- [Clip Studio Paint — 3D drawing figures](https://help.clip-studio.com/en-us/manual_en/660_3d/Posing_3D_drawing_figures.htm)
- [Krita — Reference Images Tool](https://docs.krita.org/en/reference_manual/tools/reference_images_tool.html)
- [Krita — Reference Images Docker](https://docs.krita.org/en/reference_manual/dockers/reference_images_docker.html)
- [PureRef, and why artists use it](https://ab-arts.be/en/pureref-the-ultimate-tool-for-artists-and-designers-to-organize-reference-images)
- [ibisPaint — about, the recording and the community site](https://ibispaint.com/about.jsp?lang=en)
- [DrawGestures — the drawing timer](https://drawgestures.com/guides/drawing-timer)
- [Pose Library — timed gesture practice](https://poselibrary.com/gesture-drawing-practice)
- [Drawabox — the 50 % rule](https://drawabox.com/lesson/0/2/50percent)
- [Sketchar — the AR drawing assistant and its lessons](https://sketchar.io/ar-assistant)
- [Why drawings look "off" — flip horizontal and the squint check](https://doncorgi.com/blog/why-drawings-look-off/)
- [Free pose reference sites, and their licences](https://doncorgi.com/blog/free-pose-reference-websites/)
