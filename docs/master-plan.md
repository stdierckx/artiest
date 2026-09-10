# The master plan — what order, and why

> Authored 2026-09-10, over six feature documents written the same day
> (`brushes`, `brush-shelf`, `vector`, `layer-effects`, `guides`, `text`,
> `comics`) plus `workspace-plan.md`. It does not restate them. It answers one
> question: **in what order, and what does each one unlock.**

## First, the number nobody has said out loud

Added together, the feature documents describe roughly **500 working days**.
Every one of them was scored honestly and none of them is padded. That is not a
backlog, it is a decade of evenings, and it means the ordering decision is not
"what first" but **"what do we deliberately not build for the next two years."**

Which is why the organising principle below is not a priority list.

## The organising principle: finish one artist before starting another

A workspace named *Inker* is a promise. If the inker's workspace opens onto a
perspective ruler that does not exist, a vector eraser that is half built and a
brush shelf with three brushes on it, the promise is broken in a way that is
worse than never having made it — because the user chose that workspace by name
and was told this program was for them.

So:

> **Pick one artist. Ship everything that artist needs. Then pick the next one.**

A completely served sketcher beats five half-served identities, and it is the
only way a feature list this long produces something usable at any point along
the way rather than only at the end.

Which artists, and what each one actually costs:

| Workspace | What has to exist for the name to be honest | Rough days |
|---|---|---|
| **Sketcher** | Brush shelf, a few more brushes, hold-to-snap shapes, mirror, stabilisation *(shipped)*, layers *(shipped)* | **50–75** |
| **Inker** | Sketcher, plus vector strokes Tier 0, the three eraser modes, select-and-move, the guide framework, perspective | **150–200** |
| **Painter** | Sketcher, plus blend modes, clipping, alpha lock, blur, layer groups, brush import | **120–160** |
| **Comic / Webtoon** | Inker, plus text, balloons, panels, and multi-page | **250+** |
| **Logo / vector** | Vector layers in full, booleans, SVG export, text outlines. **Honestly a different program**, and the one identity I would not chase. | **200+** |
| **Animator** | Sketcher, plus the sequence subsystem, a timeline, onion skin, playback and MP4 export — `docs/animation-plan.md` | **60–85** |

## The order

### P0 — The workspace system · 45–62 days · `docs/workspace-plan.md`

> The build itself is `docs/ui-expansion-plan.md` — ten work items against the
> code as it stands, with the migration, the tests and the stop conditions.

**First, and your instinct is right.** Not because it is the most wanted feature,
but because of what it does to the cost of everything after it: with the
catalogue and the workspace system in place, each of the ~60 features below adds
**one line** and appears in the workspaces that want it. Without it, the app
accumulates buttons until someone has to redesign the interface with sixty tools
already wired into it — and that redesign never goes well.

It is also the only item in any of these documents that gets *better* as the
feature list grows.

### P0.5 — Saving a drawing · 15–25 days · (Phase 4's `.ora`, brought forward)

**One thing I would put beside P0, and it is not a feature.** As it stands the
app exports a PNG and nothing else: layers, undo history and the document itself
are not persisted — `docs/phase3-plan.md` says so plainly ("nothing about a
document is persisted yet"). **A multi-layer drawing does not survive closing the
app.**

Everything in this document assumes people are making work worth keeping. I would
not spend another 500 days on features layered over a program that cannot save.
It is also the gate for pages, for frames, and for sharing.

Your call whether it runs before P0, after it, or alongside — but it should not
run *late*.

### P1 — The brush shelf · 20–30 days · `docs/brush-shelf-plan.md`

Small, self-contained, and it is what makes a workspace feel like a workspace:
*Sketcher* means a shelf of sketching brushes, not three buttons. It also turns a
brush from a function into a file, which is what brush import later needs.

### P2 — Finish Phase 3 · `docs/phase3-plan.md`

Selection, the one compositor, and **S0's measurement on the tablet**. This is
the quiet gate: blend modes, every layer effect, guide overlays, marching ants,
and the transform handles that text, panels and vector objects all need are
downstream of it. It is already planned and half-argued; leaving it unfinished
taxes four later plans.

### P3 — Complete the sketcher · 30–45 days

- Hold-to-snap shapes and mirror — `docs/guides-plan.md` Tier 0, 10–14 days
- A real brush set: import a curated CC0 bundle — `docs/brushes-plan.md`, trimmed
- Alpha lock, clipping, merge — `docs/layer-effects-plan.md` Tier 0

**This is the first moment the app is finished for somebody**, and it is the test
of whether the workspace idea works end to end. If *Sketcher* does not feel like
a complete program at the end of P3, the identity-first principle is wrong and
better to learn it here than after the comic work.

### P4 — Choose the second artist

Three real options, and they diverge hard:

- **Painter** (120–160 days) — layer groups, masks, effects, brush import in
  full. The most self-contained, and it reuses everything P2 built.
- **Inker** (150–200 days) — vector strokes, the eraser modes, the guide
  framework, perspective. The most *distinctive*, and the gateway to comics.
- **Comic** — do not pick this second. It needs text, balloons, panels *and*
  pages, and pages need P0.5 finished and then some.

My recommendation is **Inker**, for one reason: it is the only path where the
expensive item (vector strokes) is also the item that makes text sharp, makes
SVG export possible and makes the logo/vector workspace conceivable later. It
buys the most future.

### P5 and beyond

Text → balloons → panels → pages, in that order, because each genuinely needs
the one before it. Then animation, if it is wanted.

## The dependency map, in one place

```
workspace system ────────────────────────► everything is cheaper
save/open a document ──┬──► pages (comics)
                       └──► frames (animation)
brush shelf ──► brush import ──► a real brush set
Phase 3 (compositor + S0) ──┬──► blend modes ──► layer effects ──► groups & masks
                            ├──► overlays ──► guides ──► perspective
                            └──► transform handles ──┬──► text objects
                                                     ├──► panels
                                                     └──► vector objects
vector layers ──┬──► sharp text at any zoom
                ├──► SVG export
                └──► editable ink (the inker's whole case)
text ──┬──► balloons ──► comics
       └──► story editor (also needs pages)
```

## Two things worth knowing before you choose

**1. Pages and animation frames are the same subsystem.** A comic page and an
animation frame are both "a document-sized thing, one of many, only one of them
in memory". Twenty pages of eight layers is 4.3 GiB on a device with 4.4 GiB
free; a hundred animation frames is worse. Solve it once — a document that holds
a sequence, backed by files, with one member live — and **two identities unlock
from one piece of work.** Building it twice would be the most expensive mistake
available in this document.

**2. Animation now has a plan — `docs/animation-plan.md`.** Written after this
document, and it changes one line of the table above: a flipbook animator is
**23–36 days on top of the sequence subsystem**, not the open-ended unknown it
was. It also argues that the pencil test — rough frames with real graphite —
is the one animation feature this app would be better at than any of its Android
rivals, which makes *Animator* a stronger candidate for the second or third
artist than its size suggests.

## What this means for the workspaces you ship first

`docs/workspace-plan.md` item A7 says three workspaces ship with the system.
Given the order above, they should be:

- **Sketcher** — honest after P3, and the reason P3 exists.
- **Clean** — the canvas and almost nothing else, for drawing without the
  furniture. Honest today.
- **Everything** — the current interface, unfiltered, so nothing is ever lost.

*Inker*, *Painter*, *Webtoon* and *Animator* ship **when the tools behind them
do**, and not before. A workspace with a name and nothing behind it is the one
way this feature can make the app feel worse instead of better.

## The documents this orders

| Plan | Days | Gate |
|---|---|---|
| `workspace-plan.md` · built by `ui-expansion-plan.md` | 45–62 | none |
| `brush-shelf-plan.md` | 20–30 | none |
| `brushes-plan.md` (Krita import) | 30–45 | brush shelf |
| `guides-plan.md` | 10–14 (Tier 0) / +25–40 (guides & perspective) | overlays for the framework |
| `layer-effects-plan.md` | 10–15 (best bundle) / 40+ (groups & masks) | Phase 3 |
| `vector-plan.md` | 30–45 (best bundle) / 80+ (with node editing) | Phase 3 |
| `text-plan.md` | 15–20 | transform handles |
| `comics-plan.md` | 8–11 now / 250+ complete | text, groups, pages |
| `animation-plan.md` | 23–36 (flipbook) / 60+ (with sound and camera) | the sequence subsystem |
