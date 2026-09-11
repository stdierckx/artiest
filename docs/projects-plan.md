# P0.5 — Projects: keeping the work

> Authored 2026-09-11, against the code as it stands. `docs/master-plan.md`
> puts this beside P0 and says it must not run late: *"I would not spend another
> 500 days on features layered over a program that cannot save."* The workspace
> system is done, so this is next.
>
> It answers one question: **what does it mean to keep a drawing, and what has
> to exist for that to be true.**

## The problem, stated exactly

The app exports a PNG and nothing else. `Document` says so in its own header —
*"Phase 1 has no autosave, so process death loses everything that was not
exported"* — and `docs/phase3-plan.md` repeats it: *"nothing about a document is
persisted yet"*.

What that costs today, in order of how much it hurts:

1. **Eight layers become one PNG.** The export flattens, so the only artefact
   that survives is the one you cannot go on working in.
2. **Closing the app loses the drawing.** Not a crash — the ordinary
   home-button, come-back-tomorrow case.
3. **There is one drawing.** No second sketch without destroying the first.

And what it blocks, which is the reason this is P0.5 rather than a feature
somewhere in the middle: **pages and animation frames are both "a
document-sized thing, one of many, only one of them live"**. Neither can be
built on a program with nowhere to put a document.

## What a project is

> **A project is a directory, and the app keeps it up to date while you draw.**

```
filesDir/projects/<id>/
    project.json          what it is called, how big, what the sheets are
    layers/0.png          one PNG per sheet, bottom to top, with alpha
    layers/1.png
    thumbnail.png         what the gallery shows. 400 px on the long side
```

Four decisions are packed into that, and each one had a plausible alternative.

### Why a directory and not one file

Because the app saves **while you draw**, and a single file cannot be updated in
part. A project is roughly 8 × 27 MiB of live pixels; re-encoding and rewriting
all of it after every stroke is seconds of work for one stroke's worth of
change. A directory lets the save be **only the sheets that actually changed**,
which is almost always one.

The cost is that a project is not a thing you can attach to an email. That is
what the export is for, and the export is nearly free precisely because of this
choice — see below.

### Why not `.ora` as the working format

OpenRaster is a zip, and a zip is one file. Everything above applies. It is
also, as a container, exactly the same shape as the directory: `mimetype`,
`stack.xml`, `data/*.png`, `mergedimage.png`, `Thumbnails/thumbnail.png`.

So `.ora` is the **export**, and it is cheap: the layer PNGs in the directory
are already encoded, so writing an `.ora` is a stack of `store`-mode zip entries
plus a small XML file plus one merged image. No re-encoding of layers at all.
That is the reason the working format and the interchange format are allowed to
be different here: they are the same bytes in two wrappers.

Krita, GIMP and MyPaint open `.ora`. Note for the licence file: OpenRaster is a
published specification, not code, and nothing GPL is read or copied to
implement it — see `NOTICE`.

### Why PNG per sheet and not one raw blob

A PNG is self-describing, readable by everything, and roughly a tenth of the
size of raw ARGB on drawn artwork and a hundredth on an empty sheet. The encode
is the expensive part — measured at 177 ms for a full-size layer on the host in
`PngExporter`'s notes — and that is why the save is incremental and off the pen
thread rather than why it should be raw.

### Why the working copies are app-private

`filesDir`, like workspaces. They are not documents the user manages with a file
browser; they are the app's own state, and the export is how work leaves. The
consequence is stated rather than hidden: **uninstalling the app deletes your
projects**, which is why export exists in the same work item as the gallery and
not two items later.

## The file

```json
{
  "artiest_project": 1,
  "id": "morning-sketch",
  "name": "Morning sketch",
  "created": 1757606400000,
  "modified": 1757610000000,
  "revision": 12,
  "width": 3300,
  "height": 2160,
  "paper": "#ffffff",
  "active": 1,
  "layers": [
    {"file": "layers/0.png", "name": "Layer 1", "opacity": 1.0, "visible": true, "blend": "normal"},
    {"file": "layers/1.png", "name": "Ink",     "opacity": 0.8, "visible": true, "blend": "multiply"}
  ]
}
```

Everything about it follows `docs/workspace-format.md`, because that decoder was
written against files from strangers and this one will have to be too the day an
`.ora` import lands:

- **A version number first**, and a file from a newer build is refused whole
  rather than half-read.
- **`id` is a slug and is the directory name.** `Workspace.slug` already exists
  and is already tested against accents, apostrophes and `../`.
- **`active` is an index, not an id.** Layer ids are assigned by `LayerStack` at
  runtime and restart at 1 in every new stack; an id in a file would name a
  different sheet on the next launch. Order is the only stable identity a saved
  stack has.
- **`blend` is `LayerBlend.id`**, which exists and is stable for exactly this
  reason — see that file's header, which names this format.
- **Unknown fields are reported, not fatal.** Same rule as the workspace
  importer.

### What is deliberately not in it

| Not saved | Why |
|---|---|
| The undo history | 48 MiB of pixel patches to make the last twenty minutes undoable *after a restart*. It is not worth its own file format, and every program the user knows behaves this way. |
| The selection, and floating pixels | Transient by construction. A float that survived a restart would be pixels lifted off a sheet with no gesture in progress to put them back. |
| The workspace | Already its own file. A project opened in *Sketcher* opens in whatever workspace you are in, which is the whole point of the two being separate. |
| The brush in your hand | `BrushStore` already keeps it, per install and not per project. Revisit when brushes become files — `docs/brush-shelf-plan.md`. |

## Saving without the pen feeling it

This is the part that is actually hard, and the rules come straight from
`Layer`'s contract and from what `PngExporter` already had to learn.

**1. Only what changed.** `Layer` gains a `revision` counter, incremented inside
`write`, under the lock that is already taken. The saver remembers the revision
it last wrote for each sheet and skips the ones that have not moved. One stroke
is one sheet is one encode.

**2. One blit under the lock, never an encode.** Exactly what `PngExporter`
does and for the numbers in its header: allocate the transient bitmap first,
copy under the lock (~14 ms), release, then encode off-lock (~200 ms). A PNG
encode inside `Layer`'s lock would stall the render thread for a fifth of a
second while somebody is drawing.

**3. Off the main thread, always.** `Dispatchers.IO`. `Layer.read` refuses the
main thread outright, so there is no accidental version of this.

**4. On a debounce, and at the door.** A save is scheduled a few seconds after
the last change and cancelled by the next one, so a burst of hatching is one
save. And `onPause` saves immediately, because the process may not come back —
that is the case this whole item exists for.

**5. The write is atomic per file.** Write `layers/0.png.tmp`, then rename.
A half-written PNG that replaced a good one would be a sheet that comes back
empty, and the failure would arrive a week later with no way to tell what did
it. `project.json` is written last and the same way: it is the file that says
what the directory means, so it must never name a sheet that is not there yet.

**6. The thumbnail is the same compositor the screen uses.** `StackCompositor`
into a scaled-down canvas. Not a second loop — `PngExporter` learned that lesson
already and its header says why.

### What has to be measured, on the tablet, before this is called done

| Number | Where it must land |
|---|---|
| Save of one changed sheet | under 400 ms, none of it on the pen thread |
| Longest stall the pen can feel during a save | one frame, and it should be zero |
| Open an 8-sheet project | under 2 s, with the gallery still responsive |
| Peak memory during open | below the point at which the ninth layer would be refused |

The instrument already exists: `ChromeCounters` and the diagnostic overlay from
W9–W14. This item adds a `save` line to it.

## Opening one

A project is opened **into the document that is already there**, not by building
a new one. `Document` is allocated in `onCreate` at the size the device probe
allows, and the view, the render thread and every pointer into it are bound to
that instance. Replacing it is a second piece of work with its own risks, and
nothing about opening a project needs it.

So opening is one new queued operation:

> **`LayerOp.Open`** — carries a ready-made list of sheets, with names,
> opacities, visibilities, blends and which one is active. The render thread
> closes the old sheets and installs the new ones in one step.

Queued, like every other change to the stack, for the reason `CommitQueue`
gives: a stroke finished a millisecond ago and not yet stamped is part of what
the user drew, and an open applied from the UI thread would land in front of it.
One op and not twenty, because "open this project" is one thing: a sequence of
Add and Delete would be visible as a flicker, would have to guess at ids that
the render thread has not assigned yet, and could fail halfway.

The undo history is cleared as the open lands. Its patches name sheets that no
longer exist, and `PixelPatch.recapture` would be restoring pixels into whatever
happened to take their place.

### The one limit, stated rather than discovered

**The document is the size the device chose, and a project records the size it
was made at.** Open a project made at another size and the sheets are drawn into
the corner of the page at 1:1 and the difference is reported. Nothing is scaled
and nothing is silently cropped away — what does not fit is still in the file.

Every project made on this tablet has the same size, so this is the
cross-device case only. Choosing a page size when you make a project (A4,
square, the screen) is the natural next step and is **not** in this item; it
arrives with a swappable `Document`, and this format already carries the two
numbers it will need.

## The gallery

A screen, not a menu: thumbnails big enough to recognise a drawing by, which is
the only thing a project list is for.

- **New** — makes a project, names it *Drawing 2*, opens it.
- **Tap** — opens it. The current one is saved first.
- **Rename**, **Duplicate**, **Delete** — the same three the workspace menu
  grew, with the same shapes: a text field that takes focus and arrives
  selected, and a delete that asks with the name in the question.
- **Export** — PNG as today, and `.ora`.

**The app opens where you left off.** The last project is reopened and the
gallery is one tap away, on a button in the toolbar like everything else. A
gallery between the user and the paper on every launch is a tax on the common
case — the tablet's own launcher is already a gallery — and the one thing this
item promises is that the drawing is still there when you come back.

That is a reading of *"the app opens on your projects"* and not a transcription
of it. It is one line to flip if it is wrong.

## Work items

| # | Item | Done when |
|---|---|---|
| **Pj1** | `Project`, `ProjectJson`, `ProjectFiles`. The pure half: types, the format, the directory, ids, names, clashes, corrupt files. No pixels, no UI. | A project round-trips through JSON; a file from a newer build is refused; two projects named the same get their own directories; a corrupt file is not in the list and does not stop the others. All on a plain JVM. |
| **Pj2** | `Layer.revision`, `ProjectSaver`. Sheets out to PNG, atomically, off the pen thread, only what changed. | A drawing with three sheets writes three PNGs and a `project.json`; drawing on one of them and saving again rewrites one file; the pen thread is never blocked on an encode. |
| **Pj3** | `LayerOp.Open`, `ProjectLoader`. Sheets back in. | Save, close the app, reopen: the same sheets, in the same order, with the same names, opacities, visibilities and blends, and the same one active. The undo history is empty rather than wrong. |
| **Pj4** | `ProjectStore` and the lifetime: which project is current, autosave on a debounce, save on pause, first run makes one. | Draw, press home, kill the app, start it: the drawing is there. A fresh install starts in *Drawing 1* rather than on a screen asking a question. |
| **Pj5** | The gallery: the screen, the button, new/open/rename/duplicate/delete. | A second drawing can be made and got back to without losing the first. |
| **Pj6** | Export `.ora`, and import one as a new project. | A project exported from here opens in Krita with its layers intact; an `.ora` from Krita opens here as a project, with what could not be read reported rather than dropped in silence. |
| **Pj7** | Measure it on the tablet, and the `save` line in the overlay. | The four numbers above are taken and written into this document. |

Pj1–Pj5 is the feature. Pj6 is what makes the work leave the tablet. Pj7 is the
gate, in the same sense U10 is a gate for the workspace system.

## Stop conditions

- **If a save can be felt by the pen**, the debounce is not the answer and the
  encode moves to a worker that yields between sheets. A drawing app that
  hiccups while you draw has traded the wrong thing for safety.
- **If an 8-sheet open takes longer than two seconds**, sheets load bottom-first
  and the page appears as they arrive rather than the app waiting for the whole
  stack.
- **If the atomic rename turns out not to be atomic on this filesystem**, the
  save writes to a second directory and swaps, and that is discovered here
  rather than by a user with one copy of a drawing.

## Risks

- **A save that runs while the app is being killed.** `onPause` starts one; the
  process may not live to finish it. The rename is what makes that safe: the
  old file is intact until the new one is complete. The worst case is losing
  what was drawn since the last save, which is seconds.
- **Two savers at once.** Opening a project while a save is in flight has to
  wait for it, or the directory being written is the one being replaced. One
  mutex around the whole save, held across the open.
- **Memory during open.** Decoding a PNG into a new `Layer` is 27.19 MiB plus
  the decoded bitmap, per sheet. Decode straight into the sheet and release
  immediately — `PictureImporter.paint` is the shape, and it already does this.
- **The ninth layer.** `LayerStack` caps the stack. A project with more sheets
  than the cap loads what fits and says so; it does not fail and it does not
  silently drop the top of somebody's drawing.

## What this unlocks

```
projects ──┬──► pages (comics)      one project, many documents
           ├──► frames (animation)  the same subsystem, the same file layout
           ├──► .ora export         work that can leave the tablet
           └──► page size at New    a document that can be made, not only probed
```

The directory layout above is deliberately one level away from holding a
*sequence*: `layers/` becomes `pages/<n>/layers/`, and the loader's "one
document live at a time" is already true because there has only ever been one.
That is the piece of work `docs/master-plan.md` warns is the most expensive
mistake available if it is built twice.
