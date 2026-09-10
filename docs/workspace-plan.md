# Workspaces — a UI an artist shapes around their own work

> Authored 2026-09-10, against the dock system as it stands (`Dock.kt`,
> `ToolbarLayout.kt`, `DockCodec.kt`, `DockHost.kt`) and against five feature
> plans that between them would add roughly sixty entries to a catalogue that
> currently holds twenty-two. This is the plan for the thing the app is meant to
> be known for, so it argues its case rather than asserting it.

## The claim

Every drawing program ships one interface and lets you nudge it. The bet here is
different: **the interface is a document.** It has a shape you draw, contents
that flow into that shape, a text format you can read, write, share and hand to
an AI, and a name — *Sketcher*, *Inker*, *Webtoon* — that says which artist it is
for. Switching workspace does not rearrange buttons; it changes which program you
are using.

That is worth building **before** the sixty features, for one reason that has
nothing to do with ambition: **with the system, every later feature costs one
line in a catalogue. Without it, the sixtieth feature costs a UI redesign.**

## Does the current system give us enough flexibility?

Honest answer: **three of the four hard parts are already right, and the fourth
is missing rather than wrong.** Nothing here is a rewrite.

**What is already right, and better than it needed to be:**

- **`ToolbarLayout` knows nothing about tools, docks or directions.** Its own
  KDoc says so: *"It allocates intervals."* That is precisely the property that
  lets a second layout engine sit beside it — or replace it — without touching
  the twenty-odd tests that cover it.
- **Sizes come from the catalogue, not from the file.** `DockCodec` deliberately
  does not write spans: *"Writing the span down would mean a release that resizes
  a panel leaves every saved layout laid out to a number no code believes any
  more."* **This is what makes AI-authored layouts possible at all** — a
  generated file names items, not geometry, and cannot get the geometry wrong.
- **The codec never throws and never loses a bar somebody built.** Unknown ids,
  unknown docks and unreadable positions each drop one thing and keep the rest.
  A workspace file arriving from a stranger or from a model is exactly that
  problem, and the discipline for it is already written and tested.
- **A placement already carries its own size** (`span` × `depth`), so a panel
  the user resized is a property of the placement. Two dimensions already exist
  in the data model.

**What is missing, precisely:**

| Missing | Today | Needed for |
|---|---|---|
| **A shape** | A bar is a 1-D run of slots with an axis. An L is two separate bars. | L, T, U, block and hand-drawn surfaces |
| **Flow** | Deliberately absent. Items go where you put them; the chooser greys out what will not fit. | "Give me these fourteen tools in this shape" — and AI authoring, which cannot place by hand |
| **A workspace** | One layout in `SharedPreferences`. | Named sets, switching, sharing, per-goal tool filtering |
| **An interchange format** | A compact private string, `v3\|left:12:0=pen,…` | Something a human reads, a model writes, and a friend receives |

### The objection this plan has to answer, because the code already made it

`ToolbarLayout`'s KDoc argues against exactly what you are asking for:

> *"A bar that reflows when you add something is a bar where Export is in a
> different place every week, and the single thing a toolbar is for is that your
> hand knows where the button is without your eye going there."*

That argument is correct and it does not go away. **The resolution is that flow
is an authoring step, not a runtime behaviour.**

> **Flow when you are shaping the workspace. Freeze when you are drawing.**

You draw a shape, drop in fourteen tools, the packer arranges them — and the
result is written down as absolute cell positions. From that moment nothing
moves on its own. Adding a fifteenth tool later puts it in the first free cell;
it does not re-flow the other fourteen. A **Tidy** command re-flows on request,
because sometimes you do want that, and it is one undoable action rather than an
ambient behaviour.

That single rule keeps the muscle memory, gives you freeform authoring, and
makes AI generation safe: the model proposes an arrangement, the packer realises
it, you adjust, and it freezes.

## The model

### Cells, and a shape made of rectangles

Everything is already measured in 44 dp cells. Make that explicit and
two-dimensional:

- A **surface** is what a bar becomes: an id, an anchor, a **region**, a flow
  order, and its placements.
- A **region** is a list of cell rectangles. Their union is the shape. An L is
  two rectangles; a T is three; a hand-drawn blob is however many the painting
  produced, coalesced.
- A **placement** is an item at a cell position with a width and height in cells
  — which is what `Placement` already is, with one dimension added.

Rectangles rather than a bitmask because a rectangle list is short, readable in
JSON, easy to hand-write, and trivially testable. A mask is the internal form the
packer builds; it is never the stored form.

### Anchors, so a workspace survives a different screen

A region is stored **relative to an anchor** — a corner, an edge, or a floating
fraction (which `BarSpot` already does for floating bars, and for the reason its
KDoc gives). `{"anchor": "bottom-left", "shape": [...]}` means those cells are
counted from that corner, so the same file works on a taller screen, a rotated
one, or a phone.

When the screen genuinely cannot hold the region, the rules are, in order:
**clamp** the region to what exists, **overflow** the items that no longer fit
into a chevron at the end of the surface, and **say so** — never silently drop a
control, which is the one failure mode `ToolItem`'s rule 1 exists to prevent.

### The packer

`RegionLayout`, a sibling of `ToolbarLayout`, in the same spirit: it knows
nothing about tools. Given a region mask, a flow order and a list of footprints,
it returns positions.

- **Flow order** is a reading order over the region: primary axis, then
  secondary, per surface — `right-then-down`, `down-then-right`, and their
  reverses. An L drawn down the left edge and along the bottom flows
  `down-then-right`, which is what makes the corner feel like one bar rather
  than two.
- **Placement is first-fit** along that order: walk cells in flow order, place
  each item at the first position where its whole footprint is inside the region
  and free. Deterministic, stable, and it produces the arrangement a person
  would have made by hand.
- **Sliders turn with the grain.** `turnsWithDock` already exists and already
  means this; the packer asks the surface for the local axis at the cell it is
  filling, which for an L is "vertical in the upright arm, horizontal in the
  foot".
- **Anything that does not fit goes to overflow**, in order, visible.

Cost is trivial — a few hundred cells, a few dozen items, once per authoring
action. It is pure data, so it lives in a plain JVM test beside
`ToolbarLayoutTest`, and every interesting case (an L, a T, a slider that will
not turn a corner, an item one cell too wide, a region with a hole) is a unit
test rather than a thing you check by eye on a tablet.

### What a workspace holds, beyond toolbars

A workspace that only arranges buttons is a skin. The point is that it is a
**working set**, so it also carries:

- **The catalogue filter** — which tools exist at all in this workspace. This is
  the answer to "the UI will be flooded": *a webtoon workspace does not hide the
  perspective ruler, it does not have one.* With a permanent escape hatch: a
  search that finds any tool in the whole catalogue, always, and offers to add it
  to this workspace.
- **Defaults** — which brushes are on the shelf (`docs/brush-shelf-plan.md`),
  the starting brush, stabilisation, page setup and bleed for a comic workspace,
  guides for a perspective one.
- **Identity** — a name, an author, a one-line description, and a version.

## The file format

Two formats, on purpose, and the split is the same one `DockCodec` already
makes for a good reason.

- **The compact string stays** for `SharedPreferences`. It is written on every
  drag; it must be small and fast, and it already works.
- **JSON is the interchange format**: export, import, share, and what a model
  reads and writes. `org.json` is in the platform, so this costs no dependency.

A workspace file:

```json
{
  "artiest_workspace": 1,
  "name": "Inker",
  "description": "Clean line work: two brushes, the eraser, and the rulers.",
  "author": "stephane",
  "surfaces": [
    {
      "id": "corner",
      "anchor": "bottom-left",
      "shape": [ { "x": 0, "y": 0, "w": 1, "h": 10 },
                 { "x": 0, "y": 0, "w": 9, "h": 1 } ],
      "flow": "up-then-right",
      "items": [
        { "id": "pen" },
        { "id": "pencil" },
        { "id": "eraser" },
        { "id": "size" },
        { "id": "colour" },
        { "id": "undo", "at": { "x": 8, "y": 0 } }
      ]
    }
  ],
  "catalogue": {
    "groups": [ "draw", "edit", "canvas" ],
    "hide": [ "stats" ]
  },
  "defaults": {
    "brush": "inker-fine",
    "shelf": [ "inker-fine", "inker-broad", "pencil" ],
    "stabilisation": 0.35
  }
}
```

Three properties make this work, and each is a deliberate choice:

1. **`at` is optional.** Give a position and it is honoured exactly; leave it out
   and the packer places the item. So a hand-tuned workspace round-trips
   losslessly, and a model can write a file with no coordinates at all and get a
   sensible arrangement. **This one rule is what makes AI authoring practical.**
2. **No sizes anywhere.** Widths and heights come from the catalogue, for the
   reason `DockCodec` already gives.
3. **Everything unknown is dropped, never fatal.** An id from a later build, a
   group that no longer exists, a shape rectangle off the screen — each drops one
   thing, and the import screen lists what it dropped.

### The catalogue, published

The app can write `catalogue.json`: every item's id, label, group, footprint,
kind, and which build it appeared in. That is the vocabulary — hand it to a
model with the schema above and it can author a workspace without guessing a
single identifier. It is generated from the enum, so it cannot drift.

### Untrusted input

A workspace from the internet is a file from a stranger. It names controls; it
carries no code, no URLs, no scripts, and the importer must enforce that rather
than assume it. Strict validation, a preview before it is applied, and the
current workspace is never overwritten — an import always creates a new one.

## How you shape a surface

Three ways in, and the first is the one that fits a drawing app:

1. **Draw it.** In Arrange mode, drag the pen across the screen and the cells you
   touch become the surface. Drag again with the eraser end to take cells away.
   You are drawing your toolbar, on the same glass you draw everything else on.
   This is the interaction nobody else has and it is worth building for that
   reason alone.
2. **Shape presets** — bar, L, T, U, block, corner — because most people want an
   L and should get it in one tap.
3. **Drag the ends**, which floating bars already do.

Then: drop tools in from the chooser (which already exists), or pick a whole
group at once; **Tidy** re-flows; drag to fine-tune; it freezes as you go.

## Work plan

| # | Item | What it delivers | Days |
|---|---|---|---|
| **A1** | `CellRegion` + `RegionLayout` — the shape and the packer, pure data, JVM tests | An L can be described and filled. Nothing on screen yet. | 5–8 |
| **A2** | `Surface` replaces `Bar` in the model; the four edges become surfaces whose region is a 1-cell strip | The old world expressed in the new model, pixel-identical on screen | 4–6 |
| **A3** | Renderer: `DockHost` draws surfaces of any shape, including the corner join | An L-shaped toolbar exists | 8–12 |
| **A4** | Shape editing: presets, drag-the-ends, and the draw-your-toolbar gesture | You can make one | 6–10 |
| **A5** | Workspace object: name, catalogue filter, defaults, switching | Switching changes the program | 5–8 |
| **A6** | JSON schema, import/export, validation, `catalogue.json` | Share it; a model can write one | 5–8 |
| **A7** | Three shipped workspaces — *Sketcher*, *Inker*, *Clean* — plus the always-available search | The feature is visible on first launch | 3–5 |
| **A8** | Overflow, clamping, and the different-screen tests | It survives rotation and a phone | 3–5 |

**Total: 40–60 days.** That is a phase, and it should be called one.

### Stop conditions

- **If A1's packer cannot produce, by first-fit, the arrangement a person would
  have made by hand for an L**, the flow idea is wrong and the honest fallback is
  shape-plus-manual-placement — still a real feature, without the AI story.
- **If the corner join in A3 cannot be made to look deliberate** rather than like
  two bars that happen to touch, drop non-rectangular surfaces and keep
  workspaces. Workspaces carry most of the value; the L is what makes it feel
  personal.
- **If switching a workspace takes long enough to see**, it is a mode change
  rather than a personality change, and it will not be used.

## The traps

1. **A wardrobe for three shirts.** Right now there are twenty-two catalogue
   entries. A workspace system is *most* of a system with nothing to arrange.
   The mitigation is discipline, not scope: build A1–A8 and stop. No gallery, no
   cloud, no in-app model, no import from other programs. Those are worth doing
   when there are sixty tools and not before.
2. **Hiding a tool is a promise that it was not needed.** Every filter needs the
   escape hatch: search finds anything, always, and adding it to this workspace
   is one tap.
3. **Freeform surfaces cover the paper.** A shape you drew is a shape over your
   drawing. The canvas needs to know its safe area, and a surface probably wants
   a way to get out of the way — which is a whole design of its own and is *not*
   in this plan.
4. **The pen must not be caught by the interface.** More UI on the glass means
   more edges where a stroke starts on a toolbar. `InputRouter` and
   `StrokeExclusivity` already arbitrate this; a freeform surface makes the
   perimeter longer and more interesting, and that is a real risk to the thing
   this project is actually good at.
5. **Versioning both ways.** A workspace written by a later build must open in an
   earlier one, dropping what it cannot do. `DockCodec` already lives by this
   rule; the JSON must inherit it, tests included.
6. **Recomposition cost.** Several surfaces of many items, all Compose. Measure
   before believing; a dropped frame while drawing is the one bug this project
   does not tolerate.

## Why this is the differentiator, stated plainly

Performance is invisible until it is missing. **A workspace is visible on the
first launch, it has the user's name on it, and it is the thing they will show
someone else.** It is also the only item in any of these plans that gets *better*
as the feature list grows, rather than making the app heavier — which is the
exact opposite of what happens to every other program on this list once it
reaches sixty tools.

## See also

- `docs/master-plan.md` — where this sits in the order, and what it unblocks
- `docs/ui-plan.md`, `docs/panels-plan.md` — the dock and panel systems this
  extends rather than replaces
