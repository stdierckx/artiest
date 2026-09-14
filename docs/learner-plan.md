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
| **Lr6** | **HELD, on the user's call 2026-09-14 — "no, for now".** Replay. Scrub a drawing stroke by stroke; play it at the pace it was drawn; at each stroke show the brush, the colour and the ruler. Snapshots every N strokes so scrubbing backwards is not a full rebuild. **The one high-risk item, and the one everything else leans on.** | `:app` | **High** | Ik3, Ik5 | 5–8 |
| **Lr7** | **The card, and the deck.** Title, note, a replayable drawing, tags. Decks on disk beside projects, one file per card, a card openable as a new drawing to practise on top of. *Keep this* from the canvas; the deck as a panel. | `:app` | Med | Lr3 | 5–7 |
| **Lr8** | **A starter deck and the Learner workspace.** Five or six cards drawn in the app, and a fifth shipped workspace that is **smaller** than the others. | `:app`, docs | Low | Lr7 | 3–5 |
| **Lr9** | **Practice sessions.** Choose a set from the library, choose 30 s / 1 m / 2 m / 5 m, the picture changes and the page turns. Auto-advance, pause, skip, and no score of any kind. | `:app` | Med | Lr3 | 4–6 |
| **Lr10** | **DROPPED, on the user's call 2026-09-14 — "no".** The grid method. `docs/guides-plan.md` item 9 — a 2D grid guide — plus the same grid drawn over the reference pane. Two grids of the same divisions is the whole trick. | `:engine`, `:app` | Low | Lr2 | 2–3 |
| **Lr11** | **Measure it, and the feel pass.** Replay cost on a real 500-stroke drawing; library size on disk; and an hour of somebody actually learning with it. | device, docs | Low | all | 1–2 |

### What the user chose, 2026-09-14

Read the ten walkthroughs and answered: **the grid method no, replay not for
now, build the rest.**

So the build is **Lr1–Lr5, Lr7, Lr8, Lr9 and Lr11** — and Lr7 is the one item
the decision changes rather than removes. A card keeps its title, its note, its
drawing and its tags, and **Practice this**, which puts the card's drawing under
a blank page as a ghost. What it loses until Lr6 exists is the player inside the
card and the **Draw from here** button. The card file is written with room for
them, so the drawings kept now do not have to be remade later: the strokes are
saved beside the picture either way, because `ProjectSaver` has done that since
Ik6.

The grid method is dropped rather than held. It is the one item on the list that
teaches a beginner to copy rather than to see, and the divisions control would
have been a permanent piece of interface serving it.

**Total: 35–54 days.** For comparison, `docs/master-plan.md` prices the Painter
at 120–160 and the Comic at 250+.

### If only three things get built

**Lr1, Lr2 and Lr6 — the eyedropper, the reference pane and replay. 11–17
days.** That is enough to know whether the idea is right: a beginner can keep a
reference on screen and steal its colours, and can watch any drawing rebuild
itself stroke by stroke. Everything else in the list is a consequence of those
three being good.

> Overtaken by the decision above: replay is held, so the first three built are
> **Lr1, Lr5 and Lr4** — the picker, the two view buttons and the locked
> reference layer — which is the cheapest way to have something in the hand.

## Part 5 — what each of these looks like in the hand

Written before any of it is built, so that the arguing happens here rather than
in a half-written panel. Everything below is described as **what is on the
glass and what the hand does**, because that is the only description a plan can
be wrong about early enough to matter.

Two rules run through all of it and are not repeated each time:

- **Every one of these is a catalogue entry.** `ToolItem` already decides what
  can be put on a toolbar, and `docs/ui-space-plan.md`'s Selection work settled
  the pattern: a panel is a convenience, and every button inside it is
  separately placeable. The tool popup gains one category, **Learn**, the way
  it gained **Selection**.
- **Nothing here is modal unless it says so.** A beginner cannot recover from a
  mode they did not know they were in.

### Lr1 · The eyedropper

**What it is.** A toggle button beside the pen and the erasers. There is no
colour picker in this program at all today, so this is a hole in the drawing
program first and a learner feature second.

**What the hand does.** Tap the button, touch the drawing, lift. The colour is
now in the hand and **the button switches itself back off** — you are holding
the brush you were holding before. One pick, one tap, no mode to escape from.
Press and hold the button instead and it stays on until pressed again, for the
rare case of picking twenty colours in a row.

**What is on the glass.** While the nib is down, a small ring under the pen,
offset up and left so the hand is not covering the answer: the ring is split,
the colour under the nib on one half and the colour you were holding on the
other, so the choice is a comparison rather than a guess. **The pick lands on
lift**, not on touch, so the nib can be slid to the right pixel while the ring
updates.

**What it picks.** What you can *see* — the stack as composited, which is what
the eye meant when it chose. A switch in the colour panel says *this layer
only* for the case where you are picking out of a reference layer that has
something painted over it.

**The barrel button is not offered for this**, and the reason is in
`PenChoice`: the barrel already means *erase*, that is the behaviour of the pen
the user has in their hand, and a second meaning for the same button would make
the first unreliable.

### Lr2 · The reference pane

**What it is.** A panel, in the sense the layers panel and the brush shelf are
panels: it opens from a toolbar button, it can be dragged to an edge and
fixated there, and it can be resized. Side by side is therefore not a special
layout — it is the panel fixated to the right edge, which the workspace system
does today.

**What is in it.** One picture, as large as the panel allows. Under it, a strip
of small thumbnails of the others in the set; tap one to bring it up, or swipe
across the picture to go to the next. A row of small buttons along the bottom:
**add**, **fit**, **flip**, **grey**, **grid**, **remove**.

**What the hand does inside the pane.** The same vocabulary as the canvas,
because a hand should not have to learn a second one: one finger drags the
picture, two fingers pinch to zoom and twist to rotate. **Fit** puts it back.

**And the pen over the pane is always the eyedropper** — no button, no mode.
Nib down on the picture picks its colour, and the same split ring appears. This
is the single thing users name when asked why they keep Clip Studio's Sub View
open, and it costs nothing here once Lr1 exists.

**The pane can also show your own drawing.** A *This drawing* entry at the front
of the strip, which is the whole page at fit-to-pane while the canvas itself is
zoomed into an eyelash. Procreate has this and nobody copies it; beginners use
it more than they use the picture tab, because the commonest beginner mistake is
losing the shape of the whole while working on a part.

### Lr3 · The reference library

**What it is.** A grid of thumbnails, opened from the pane's **add** button or
its own toolbar button. Tap to put a picture in the pane; long press to select
several.

**How pictures get in.** Four ways, and the fourth is the one that matters:

1. The system picture picker.
2. The clipboard.
3. The camera — a photograph of the thing on the desk, or of a page in a book.
4. **Share to artiest.** Any picture, in any app, through Android's own share
   sheet. This is the PureRef lesson: the program that wins at reference is the
   one where collecting costs nothing. It is a manifest entry and a small
   receiver; the app has no intent filter but the launcher one today.

**How they are organised.** **Sets**, and nothing more clever. A set is a named
group — *hands*, *this drawing*, *bikes* — a picture may be in several, and one
set is *everything*. A drawing remembers the set that was open with it, so
re-opening the drawing re-opens its references.

**What is stored.** A copy, downscaled to the page's own size, not a link to
the file on the tablet. Krita offers both and is right to for a desktop; here a
photograph that was moved, renamed or cleaned up by the gallery app would break
the link silently, months later. `PictureImporter` already downscales for an
import, so this is the same code and a phone photograph costs a few hundred
kilobytes rather than eight megabytes. The library screen shows the total at the
bottom and deleting reclaims it.

### Lr4 · The reference layer

**What it is.** What `IMPORT` already does — the picture lands as a layer of its
own — plus the four things that make it behave like a reference rather than like
a drawing.

- **A lock, on the layer row.** A locked layer takes no ink, no rubber and no
  selection. It does not exist today, and the Painter wants it as much as the
  learner does.
- **Desaturate**, per layer. Krita puts a saturation slider on its reference
  images for the reason its own manual gives: so you look at light and shadow
  instead of being distracted by the colours.
- **Fit to page**, for the photograph that came in at the wrong size.
- **Marked as a reference**, which is what keeps it out of an export. And the
  export **says so** — *"2 reference layers were left out"* — because a program
  that silently drops half of what it was asked to save is
  `docs/layer-effects-plan.md` trap 4 all over again.

**The opacity is the ladder.** Trace at 80 %, ghost at 25 %, switch it off and
draw it from the pane beside you. That is the same one control doing the whole
of the beginner's journey, and it is why this is four small things and not a
"tracing feature".

### Lr5 · The mirror and the grey

**Two buttons, in the Canvas category.** *Flip view* and *Grey view*.

**They change what you see and nothing else.** No layer is touched, nothing
enters the undo history, and **you can keep drawing while they are on** — the
pen lands where the eye expects, because the input goes through the same flip
the picture does. That is the part worth being careful about and the part that
makes it useful rather than a novelty: the classic use is to flip, see that the
jaw is crooked, and fix it *while flipped*.

**A tell-tale, because a view mode you forget about is a bug.** The button stays
lit and a thin band sits along the top edge of the canvas while either is on.

### Lr6 · Replay

**Where it opens.** From the drawing you are in, and from any drawing's card in
the gallery.

**What is on the glass.** The page, rebuilding itself. Along the bottom, a bar:
play/pause, a scrubber for the whole drawing, step-back and step-forward by one
stroke, and a speed — *as drawn*, *4x*, *16x*, *stroke by stroke*. The scrubber
carries small marks where the layers were added, so *"take me to where the line
art started"* is one tap rather than a hunt.

**What the card beside it says**, and this is the part no video can do: for the
stroke on screen, **the brush and its swatch, the colour, the ruler it was drawn
against if any, and how long it took.** "What did they draw that with" is the
beginner's actual question, and the record already knows the answer — brush
index, colour, guide index, and milliseconds from pen-down, per sample.

**Draw from here.** The button that makes this a learning tool instead of a
curiosity. Press it at any stroke and the drawing *as it was at that stroke*
opens as a **new** drawing, ready to continue by hand. Never over the original:
the thing being learned from must survive being learned from.

**What it says when it cannot.** A painted sheet, an imported picture and a
layer that arrived from Krita have pixels and no strokes. They appear at the
step where they arrived, whole, and the bar says *"this layer has no strokes to
replay"* rather than skipping to the end and looking broken.

**What it costs.** Forward is 3.6–3.8 ms a pen stroke, which is the cost of
having drawn it. Backwards is a rebuild — Ik0 measured 1.09 s for 300 pen
strokes and 6.41 s for pencil — so the player keeps a snapshot every N strokes
and rebuilds from the nearest one. A snapshot is a `Layer` at 27.19 MiB, so N is
a memory decision, not a speed one, and it lands on the cap that
`docs/layer-effects-plan.md` trap 2 makes every structural item restate.

### Lr7 · Cards and decks

**A card is four things**: a title, one note in your own words, the drawing, and
tags. No more, ever — trap 2.

**Making one.** A **Keep this** button on the canvas. It takes the drawing you
are in, asks for the title and the note, offers the tags you have used before,
and the card is in your deck. That is the whole authoring flow, and it is the
point: a beginner will never keep written notes, but they are already drawing.

**The deck.** A panel of cards, each a thumbnail with its title; a row of tag
chips across the top filters it. *Hands*, *faces*, *perspective*, *folds* —
whatever words that person uses.

**Opening a card.** The note at the top, the replay under it, and two buttons:

- **Practice this** — a new blank page, with the card's drawing on a locked
  reference layer at 30 %, so you draw it again over your own ghost.
- **Draw from here** — Lr6's button, from wherever the replay is stopped.

**A card is one file.** Share it with the tablet's own share sheet; opening one
adds it to your deck. That is how a tutorial arrives, how a teacher hands out an
exercise and how two people swap a lesson — with no account, no server and no
moderation problem, which is Part 7's line held in the design rather than in a
promise.

### Lr8 · The Learner workspace and the starter deck

**A fifth shipped workspace, and the only one defined by subtraction.** Two
brushes and an eraser rather than the shelf of sixteen; undo, redo, colour,
size; the reference pane fixated right; the deck, the replay and the two view
buttons. No selection panel, no guides panel, no blend modes, no layer effects.
`CatalogueFilter` already expresses all of that.

**Five or six cards, drawn in the app**, on the things every beginner asks
first — a box in perspective, a head from the side, a hand as a box and five
sausages, a fold, a tree. Not a course. An example of the format, and a
demonstration that the person who made them was not a teacher either.

### Lr9 · Practice sessions

**Starting one.** Pick a set, pick a length — 30 s, 1 m, 2 m, 5 m, or your own —
and press start.

**What happens.** The picture is in the pane, a thin ring drains beside it, and
when it empties **the page turns**: a fresh blank page, the next picture. Pause,
skip and back are the only controls, and a session can be stopped at any point.

**What is at the end.** A contact sheet: everything you drew in the session, in
order, each one beside the picture it was drawn from. That is the payoff and it
is nearly free — the thumbnails already exist. It is also the only review this
feature will ever offer.

**No score, no streak, no badge.** Drawabox's 50 % rule exists because the
finished result already gets in the way of the learning; a number to chase makes
that worse. The practice log, if it ever exists, says what happened and stops.

### Lr10 · The grid method

**One button, two grids.** A grid over the page and the same grid over the
picture in the pane, with a divisions control — 3, 4, 6, 8. Proportions are then
copied square by square, which is the oldest teaching trick there is and still
the one that gets a beginner their first accurate drawing.

The page half is `docs/guides-plan.md` item 9, a 2D grid guide, unbuilt and
cheap now that `GuideSet` and the overlay exist. The pane half is the same
divisions drawn over the picture. Both go off together.

## Part 6 — the Learner workspace has *fewer* buttons

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

## Part 7 — the traps

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

## Part 8 — deliberately not in this plan

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

## What Lr1 built

`doc/ColourProbe.kt`, `ui/PickRing.kt`, the pick fork inside
`InkSurfaceView.StrokeDriver`, two catalogue entries (`pick_colour`,
`pick_layer_only`, catalogue version 8), a dropper in the colour panel and in
the docked colour card, and `tools/pen.sh`.

### The picker composes; it does not read a sheet

The obvious implementation reads the active layer's pixel. It is wrong in the
way that is only found with a pen in hand: what the eye picked is what it could
*see*, which is the whole stack over the paper at the opacities and blend modes
the sheets are set to. On a blank sheet over a finished drawing that answer is
transparent black, and the user has no way to tell that from a bug.

So `ColourProbe` composes through `StackCompositor` — *the one place that knows
what a drawing looks like* — into a one-pixel bitmap and reads that. Every rule
the screen obeys is obeyed here for free, including the ones not written yet.
Seven tests, one per rule that differs from the naive read: the paper on an
empty page, a blank sheet over a drawing, a half-opacity sheet, a hidden sheet,
off the page, *this layer only*, and that a pick is always opaque.

### It runs on the render thread, and the tablet is what said so

The first version probed on the UI thread and the app died on the first pick:

```
java.lang.IllegalStateException: Layer.read on the main thread;
the layer is the render thread's
```

That check has been in `Layer` since Phase 1 and it earned its keep here. The
pick is now a request: the UI thread writes a document pixel and asks for a dry
frame, `servicePick` reads it inside `drawDryFrame` after the commits have
landed, and the answer is posted back. One frame of lag on a colour, and the
ring's *position* is not part of it — that is known on the UI thread and moves
with the nib, so the marker never trails the pen even while the colour it shows
is a frame old.

**Pen-up waits for that answer**, because a tap has had no frame at all, and it
waits with a 250 ms deadline. Without the deadline a pick whose frame never
arrives leaves the picker on with its ring stuck to the glass and no gesture
that clears it — which happened once on the tablet, and once is enough.

### `tools/pen.sh`

`adb shell input stylus swipe` takes a duration and this tablet ignores it: a
swipe asked for 2500 ms is delivered in about 125. Nothing that has to be
*looked at* while the nib is down can be photographed, which is why the ring
could not be checked at all. `tools/pen.sh` sends the pointer stream itself —
`Pinch`'s trick, one stylus pointer, real sleeps between MOVEs, a pressure, and
a **hold** at the far end. The ring was photographed during a two-second hold.

It is the tool the rest of this plan needs: a reference pane, a drag and a
timer are all things that have to be seen mid-gesture.

### Where it is offered

Two places, deliberately. It is a catalogue entry, so it goes on a toolbar like
anything else — and it is in the colour panel's top row, because that is where
a hand goes when it is thinking about colour and a tool that only exists in the
`+` chooser is a tool a beginner does not know the program has.

## What Lr4 built

Three flags on a sheet — `locked`, `reference`, `desaturate` — as
`LayerStack.Entry` fields, `LayerInfo` fields, three `LayerOp`s, three toggles
under the layers panel's blend picker, three optional booleans in the project
file (format 4), and a rule in `StackCompositor`.

**Fit to page is not among them and is not owed.** `PictureImporter` already
scales an import down to the page, so the button would have had nothing to do.

### Locked is checked where the stroke begins

Not where it lands. A lock enforced at the commit would draw the stroke, show
it, and then throw it away, which reads as the app losing work. `InkSurfaceView`
drops the gesture at pen-down instead — no wet ink, no queued commit, no undo
step — and holds that decision for the gesture, so unlocking with the pen down
does not start a stroke from wherever the nib happens to be.

A pick and a marquee still work on a locked sheet. Neither puts anything on it,
and taking a colour off a locked photograph is the main thing anybody wants to
do with one.

### The flag that reached the button and not the pen

The first version keyed the view's copy of "is this sheet locked" off
`generation`, which is what `activeKeepsStrokes` beside it uses. On the tablet
the padlock lit and the pen went on drawing.

`onLayerOp` deliberately does not bump `generation`: the opacity slider sends
one op per sample and a recomposition per sample is exactly what that
arrangement exists to avoid. So the read stayed stale. It is keyed off
`layerRows` now — the same snapshot the panel itself is showing — which is the
only source that cannot disagree with the lit button.

### Reference is a rule in the compositor, not a filter at the call site

`compose` takes `skipReference`: false for the screen, true for anything
writing a file. A filter at the export's call site would be the export
disagreeing with the screen about which sheets exist, which is the exact class
of defect `StackCompositor` was extracted to prevent.

And the export **says so** — *"saved 25930 B to Pictures/Artiest (1 reference
layer left out)"*, verified on the tablet. `docs/layer-effects-plan.md` trap 4:
a file that silently drops half of what it was is worse than one that was
honest about what it carried.

**An `.ora` keeps its reference sheets.** The PNG is a finished picture and a
photograph does not belong in one; an `.ora` is the drawing's own file, and a
sheet dropped from that is work lost.

### Desaturate is a draw-time filter

Never the pixels. Turning it off is free and the sheet was never edited —
asserted by reading the sheet's own pixel back after composing it grey.

## What Lr2 and Lr3 built

`ref/RefFiles.kt`, `ref/RefPicture.kt`, `ref/RefImport.kt`,
`ui/ReferencePanel.kt`, a **Learn** group with `references` and
`reference_panel` in it (catalogue version 9), a share filter in the manifest,
and eleven tests over the directory.

### Side by side is not a layout

It is the panel fixated to an edge, which the workspace system has done since
U6. Nothing new was needed for the arrangement the user asked for: the pane is
a panel like the layers panel, it opens from a button, it fixates onto a bar of
its own, and it resizes. That is the whole return on P0.

### The pen picks, the fingers move

Split by **pointer type** and by nothing else. A stylus down in the pane starts
a pick; a finger down starts pan-pinch-twist. Nothing is switched, so nothing
can be left switched on — which is the one thing every Clip Studio user names
about its Sub View, and it costs no button here because `PointerType` is on the
event.

The inverse mapping is the risk and it is written down as such: the picture is
drawn through `ContentScale.Fit` and then a `graphicsLayer`, and
`paneToPicture` undoes both in the opposite order. **They are one mapping
written twice**, which is the shape of defect `Matrices.kt` has a page of prose
about — right at scale 1 with no rotation, wrong everywhere else. Checked on
the tablet at a zoom: the picker took `(40, 110, 180)` off a rectangle painted
`(40, 110, 180)`.

### Copied in, not linked to

Krita offers both and is right to on a desktop. Here a photograph moved,
renamed or tidied away by the gallery app would break the link silently, months
later, in a drawing that had been working. So the bytes are copied into the
app's own files at 2048 on the long edge, JPEG 92 — a few hundred kilobytes
rather than the three to eight megabytes it arrived as, which is trap 5.

What that costs is exact colour: a JPEG's blocks move a pixel by a unit or two
and the pen can pick off this picture. Written down rather than discovered.

### Two files per picture, no index

`<id>.jpg` and `<id>.txt`, which is `BrushFiles`' rule: a single index has to be
rewritten whenever anything changes, and that is the shape of bug where adding
your fortieth reference loses the other thirty-nine. Half a picture is
recoverable — the meta can be typed again, the pixels cannot — and the tests
cover both halves going missing on their own.

### Share to artiest

A picture from any app, through Android's own share sheet. `singleTask` is what
makes it safe: without it a share opens a *second* copy of the activity over
the first, which is a second document, a second render thread and an autosave
race between them while the drawing sits behind it.

**Verified as far as the tablet allows.** Driving a share from `adb` cannot
hand over the URI grant that a real sharing app gives, so what was confirmed is
that the intent arrives at the running activity, is recognised, and fails
*legibly* — the user is told the app it came from did not allow this one to
read it, rather than being shown a `SecurityException`.

### Still owed on the pane

**"This drawing"**, the entry that shows your own page whole while the canvas is
zoomed into an eyelash. It needs a composited page thumbnail, which is a render
thread request like `ColourProbe`'s, and it is a separable piece of work rather
than a corner cut: everything above stands without it.

## What Lr7 and Lr8 built

`card/Card.kt`, `card/CardFiles.kt`, `card/CardSnapshot.kt`, `ui/DeckPanel.kt`,
three catalogue entries (`deck`, `deck_panel`, `keep_card`), a fifth shipped
workspace, and flags on `LayerOp.Add`.

### You author it by drawing

A card is four things and the type says so: a title, one note, tags, a drawing.
`MAX_NOTE` is 240 characters and that is the rule rather than a buffer size — a
box that grows invites an essay, and an essay is the thing this must not
become.

The **Keep** button on the bar takes the drawing with nothing written on it. A
form in the way of the one act that happens mid-drawing is a form that stops the
act happening; the card is named later, from the deck, when the hand is free.

### Practice this opens a new drawing, never the one you are in

A thing being learned from has to survive being learned from, and practising
into the drawing the card was made from is the one way this feature could cost
somebody work.

Two sheets go on the new page: the card's drawing at 30 %, locked and marked as
a reference, and a clean one over it. Both had to be right **from birth**, which
is why `LayerOp.Add` now carries opacity, locked and reference — a caller that
added a plain sheet and corrected it afterwards would have to wait for an id the
render thread has not allocated yet, and the ghost would be solid, editable and
exportable for however long that took.

### The deck has two states and no more

The shelf, or one card open. Not a browser, not a folder tree, not a search.
Trap 2 says what this must never become and the shortest way to hold that line
is a panel that cannot be in a third state.

### Lr8: the workspace defined by subtraction

*Learner* ships fifth. Two brushes and an eraser rather than the shelf of
sixteen; no selection panel, no guides, no blend modes. The filter is Draw, Edit
and Learn, and that is the whole of it.

What is *added* is the two things a beginner needs that nothing else on this
tablet gives them, and both are **on the glass rather than behind a button**:
the reference pane docked right, and Keep beside undo on the top bar — one
because the stroke went wrong and one because it went right.

Stabilisation is 0.35: above the app's 0.15 because an unsteady hand is the
first thing that makes a beginner think they cannot draw, below *Inker*'s 0.55
because a line that lags is its own kind of discouraging. It is the second
shipped workspace with anything in its defaults.

**Verified on the DTH-A116**, the whole loop: draw, Keep, open the deck, open
the card, Practice this, and a black stroke lands on a clean sheet over a faint
ghost of what was kept.

### The starter deck is not built

Five or six cards drawn in the app, on a box in perspective, a head from the
side, a hand. They are drawings and they have to be *drawn*, by somebody with a
pen, which is the one part of this plan that is not a software task.

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
