# The UI expansion — implementation plan

> Authored 2026-09-10. `docs/workspace-plan.md` argues *why* and sketches the
> model; this document is the build. It names the files, the types, the
> signatures, the tests, the migration and the order, against the code as it
> stands at `f1992a1`.
>
> It is the plan for **P0** in `docs/master-plan.md`, and it is a phase: ten
> work items, **45–62 days**, each one landable on its own and each one leaving
> the app working.

## What was built

| # | Item | State |
|---|---|---|
| U1 | `CellRegion` + `RegionLayout` | **done** |
| U2 | `SurfaceLayout`, `Surface`, the migration | **done** |
| U3 | The renderer | **done**, one claim unverified — see below |
| U4 | Shaping: presets, draw-it, Tidy | **done**, with two stated departures |
| U5 | `catalogue.json` | **done** |
| U6 | `Workspace` + filter | **done** |
| U7 | JSON, import, validation | **done** |
| U8 | Three shipped workspaces + search | **done** |
| U9 | Overflow, clamping, rotation | **done** |
| U10 | Measure it | **instrument built, reading not taken** |

Three things are owed, and they are written here rather than left to be found:

1. **U10 is a gate and it is open.** The counter ships and the procedure is
   `docs/ui-measurement.md`; the sixty seconds with a pen have not been spent.
   Until they are, the workspace system is not finished.
2. **The pen through the notch is asserted, not proved.** `ChromeSurface.kt`
   argues why a stroke started in the hollow of an L reaches the canvas — no
   pointer modifier on the container, no child in the notch — and a pointer path
   is not something to be sure of from reading. Same document, last section.
3. **Two surfaces may still overlap.** U4 wanted that refused at shape time. The
   shape board is anchored and capped at half the screen, which keeps the
   ordinary cases apart, but a real check needs every surface's position in
   screen cells, and nothing computes that yet.

Two deliberate departures from what is written below, both argued in their
commits: the compact string went to `v4` in U2 rather than U7, because U4 lets
the user draw a shape and a `v3` string has nowhere to put one; and U4b is
"drag the ends" only for a surface that has ends — a shape is sized by the
preset you pick or the cells you paint.

## Ground truth — what exists today

Seventeen files, 6 631 lines, in `app/src/main/kotlin/be/thalos/artiest/ui/`.
Four of them are the system this expands:

| File | Lines | What it is |
|---|---|---|
| `ToolbarLayout.kt` | 172 | `Placement` + the 1-D interval allocator |
| `Dock.kt` | 519 | `Axis`, `Dock`, `BarSpot`, `Bar`, `DockLayout` |
| `DockCodec.kt` | 229 | the `v3` line format, and `v1`/`v2` migrations |
| `DockHost.kt` | 1 319 | the renderer, Arrange mode, drag, the chooser |

Plus `ToolItem.kt` (340, the catalogue), `DockStore.kt` (170,
`SharedPreferences` and the *introduce* rule), and `Chrome` in
`ArtiestTheme.kt` (`SLOT = 44.dp`).

Tests: `ToolbarLayoutTest`, `DockLayoutTest`, `DockCodecTest`, `ToolIconsTest` —
plain JVM, no Compose. **That is the safety net this whole plan leans on**, and
every item below says what it adds to it.

### The four facts that make this cheap

1. `ToolbarLayout` *"allocates intervals"* and knows nothing about tools. A
   second engine can sit beside it and be swapped in one commit.
2. `Placement` already carries `span` **and** `depth`. The second dimension is
   already in the data model; it is only ever used across the bar, never along.
3. `DockCodec` deliberately writes no sizes, and never throws.
4. `DockHost` is handed `slotContent: (ToolItem, Axis) -> Unit` and calls it.
   The renderer does not know what a control is, so a new renderer needs no
   change in `MainActivity` beyond one argument.

### The three facts that make it work

1. **`SlotCell` sizes everything as `slots × SLOT`, with no exceptions**, and
   `DockDrag.slotAt` is the inverse of exactly that. The 2-D version is the same
   identity in two axes, which is why the drop arithmetic gets *simpler*, not
   harder.
2. `runOrigin` is reported by the run from **inside** its own scroll container,
   so it already carries padding and scroll. Nothing in the drop path has to
   learn about the new geometry.
3. `DockLayout.of` normalises rather than validates — missing edge, missing
   position, duplicated item. Every new failure mode has a place to be dropped
   quietly, already written and already tested.

---

## The shape of the change

```
today                             after

Placement(item, slot, span,       CellPlacement(item, x, y, w, h)
          depth)

ToolbarLayout                     SurfaceLayout
  slotCount: Int                    region: CellRegion
  placements: List<Placement>       placements: List<CellPlacement>
  fits/place/remove/firstFit        fits/place/remove/firstFit   (same names)

Bar(id, dock, spot, slots)        Surface(id, anchor, region, flow,
                                          spot, slots)

DockLayout(bars)                  DockLayout(surfaces)          (name kept)

—                                 RegionLayout   (the packer, new)
—                                 Workspace + WorkspaceStore    (new)
—                                 WorkspaceJson                 (new)
```

Two deliberate decisions about naming, both to keep the diff readable:

- **`DockLayout` keeps its name.** It is referenced by `MainActivity`,
  `DockHost` and two test files; renaming it to `Workspace` would collide with
  the *actual* workspace object, which is a different thing (a `DockLayout` plus
  a filter plus defaults). The arrangement is not the workspace.
- **`Dock` keeps its name and its five entries**, and gains an anchor and a
  default region. `Dock.LEFT` becomes "anchored to the left edge, region is a
  strip one cell wide and twelve tall". The persisted ids (`left`, `top`,
  `right`, `bottom`, `f1`…) do not move, which is what keeps every `v3` string
  on every existing install readable.

**One engine, not two.** `ToolbarLayout` is not kept alongside `SurfaceLayout`.
A strip is a region one cell thick, so keeping both would mean two answers to
"does this fit" and one of them going stale. `ToolbarLayoutTest`'s cases are
**ported**, not discarded — see U2.

---

## U1 — `CellRegion` and `RegionLayout` · 5–8 days

Pure data. No Compose, no Android, no `ToolItem` beyond a footprint. New files
`ui/CellRegion.kt` and `ui/RegionLayout.kt`, new test
`ui/RegionLayoutTest.kt`.

### The types

```kotlin
/** A rectangle of cells. Cells, not dp: 44dp is Chrome.SLOT and lives there. */
data class CellRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    init { require(w >= 1 && h >= 1) }
    val right: Int get() = x + w      // exclusive
    val bottom: Int get() = y + h
    fun contains(cx: Int, cy: Int): Boolean
    fun overlaps(other: CellRect): Boolean
}

/**
 * A shape, as the union of a short list of rectangles.
 *
 * Rectangles and not a bitmask because this is the *stored* form: it goes in
 * JSON, a human reads it, a model writes it. The mask is what the packer builds
 * and it never leaves this file.
 */
class CellRegion private constructor(val rects: List<CellRect>) {
    val bounds: CellRect
    val cellCount: Int
    operator fun contains(x: Int, y: Int): Boolean
    fun fits(x: Int, y: Int, w: Int, h: Int): Boolean   // whole footprint inside
    fun cells(flow: FlowOrder): Sequence<Cell>
    fun plus(cells: Collection<Cell>): CellRegion
    fun minus(cells: Collection<Cell>): CellRegion
    fun clampedTo(gridW: Int, gridH: Int): CellRegion
    fun localAxis(x: Int, y: Int): Axis

    companion object {
        fun of(rects: List<CellRect>): CellRegion       // normalises, never throws
        fun ofCells(cells: Collection<Cell>): CellRegion // coalesces
        fun strip(length: Int, axis: Axis): CellRegion
        fun l(arm: Int, foot: Int, thickness: Int = 1): CellRegion
        // …and t(), u(), block() for the presets in U4
    }
}

enum class FlowOrder(val primary: Axis, val reverseX: Boolean, val reverseY: Boolean) {
    RIGHT_THEN_DOWN, DOWN_THEN_RIGHT, LEFT_THEN_DOWN, UP_THEN_RIGHT, …
}
```

`of` normalises the way `ToolbarLayout.of` and `DockLayout.of` do: negative or
zero-sized rectangles are dropped, not raised; an empty list is a region with no
cells, which renders as nothing and holds nothing.

### Coalescing — the one algorithm with a choice in it

`ofCells` turns a set of painted cells into rectangles, and it must be
**deterministic**, because it runs while the user's pen is moving and the shape
must not shimmer.

> For each row, take maximal horizontal runs. Then merge a run with the run
> directly above it when their `x` and `w` are identical. Emit top-to-bottom,
> left-to-right.

An L drawn down the left and along the bottom comes out as two rectangles, which
is what the JSON should say. It is not the minimal rectangle cover — that
problem is NP-hard and the answer would not be nicer to read. Row-runs are
stable under adding one cell, which is the property that matters.

### `localAxis` — which way a slider turns

For the cell being filled: measure the free run inside the region rightwards +
leftwards, and downwards + upwards. Longer wins; horizontal wins a tie. In an L
that is *vertical in the upright arm, horizontal in the foot*, which is what
`ToolItem.turnsWithDock` has always meant and has never had a way to express.

### The packer

```kotlin
object RegionLayout {

    data class Footprint(val item: ToolItem, val w: Int, val h: Int, val turns: Boolean)

    data class Fill(
        val placements: List<CellPlacement>,
        val overflow: List<ToolItem>,
    )

    /**
     * Fixed placements first (they are the file's `at`, and they are honoured
     * exactly), then everything else by first fit along [flow].
     */
    fun pack(
        region: CellRegion,
        flow: FlowOrder,
        fixed: List<CellPlacement>,
        flowing: List<Footprint>,
    ): Fill
}
```

Rules, each a test:

- A fixed placement outside the region, or overlapping an earlier one, **drops
  to overflow** rather than raising. Same discipline as everywhere else.
- A flowing item is tried at each cell in flow order; it lands at the first cell
  where its whole footprint is inside the region and free.
- A turning item is measured against `region.localAxis(cell)` and may be tried
  in both orientations — natural first.
- Anything that never fits goes to `overflow`, **in input order**, so the
  chevron's contents are predictable.

Cost: a few hundred cells × a few dozen items, once per authoring action. Do not
optimise it. Do write the bitmask, because `contains` against a rect list inside
a double loop is the one place this could get silly.

### Tests (all plain JVM)

1. A strip packs identically to what `ToolbarLayout` produces today — the
   **equivalence test**, and it is the one that lets U2 delete a file.
2. An L packs `down-then-right` into arm-then-foot with no gap at the corner.
3. A four-cell slider will not turn the corner and lands below it, not across it.
4. An item one cell too wide overflows; the items after it still place.
5. A region with a hole (three rectangles making a C) never places across it.
6. `ofCells` of a hand-drawn L is exactly two rectangles.
7. `ofCells` is stable: adding one cell to the middle of a run changes one
   rectangle, not all of them.
8. `clampedTo` on a smaller grid keeps every cell that exists and no others.
9. Packing is a pure function of its inputs — the same call twice, same answer.

### Stop condition

**If test 2 does not produce the arrangement you would have made by hand**, the
flow idea is wrong. Fall back to shapes with manual placement — still a real
feature, and U4 through U9 all still stand. Do not proceed to U3 until 1 and 2
are green.

---

## U2 — `SurfaceLayout` and `Surface` · 5–7 days

The model migration. Nothing changes on screen; every existing test either still
passes or has a ported twin. **This is the risky commit**, so it is the one that
touches no pixels.

### Step by step

1. **`CellPlacement`** replaces `Placement` in a new file `ui/CellPlacement.kt`:

   ```kotlin
   data class CellPlacement(
       val item: ToolItem,
       val x: Int, val y: Int,
       val w: Int, val h: Int,
   ) {
       init { require(w >= 1 && h >= 1) }
       fun covers(cx: Int, cy: Int): Boolean
       val rect: CellRect
   }
   ```

   `slot`/`span`/`depth` become `x`/`y`/`w`/`h`. On a horizontal strip
   `slot == x`, `span == w`, `depth == h`; on a vertical one `slot == y`,
   `span == h`, `depth == w`. Write those four identities down as a test, because
   they are the whole of the migration and every later bug will be one of them
   inverted.

2. **`SurfaceLayout`** replaces `ToolbarLayout`, same method names:

   ```kotlin
   class SurfaceLayout private constructor(
       val region: CellRegion,
       val placements: List<CellPlacement>,
   ) {
       fun covering(x: Int, y: Int): CellPlacement?
       fun fits(w: Int, h: Int, x: Int, y: Int, ignoring: CellPlacement? = null): Boolean
       fun place(p: CellPlacement): SurfaceLayout      // throws, as before
       fun remove(x: Int, y: Int): SurfaceLayout
       fun firstFit(w: Int, h: Int, flow: FlowOrder): Cell?
       fun reshaped(region: CellRegion): SurfaceLayout // was resized()
       fun cleared(): SurfaceLayout
       companion object { fun of(region, placements): SurfaceLayout }
   }
   ```

   `place` still throws and `of` still drops — those two behaviours and the
   reasons for them are copied verbatim from `ToolbarLayout`'s KDoc, because
   they were argued once and the argument has not changed.

3. **Port `ToolbarLayoutTest`.** Every case, with a `strip(n)` helper, into
   `SurfaceLayoutTest`. Then delete `ToolbarLayout.kt`, `Placement` and
   `ToolbarLayoutTest.kt` in the same commit. Twenty-odd cases surviving the
   move is the evidence that the new engine is the old engine plus a dimension.

4. **`Bar` becomes `Surface`** in `Dock.kt`:

   ```kotlin
   class Surface(
       val id: String,
       val dock: Dock,          // the anchor
       val spot: BarSpot?,      // floating only, unchanged
       val region: CellRegion,
       val flow: FlowOrder,
       val slots: SurfaceLayout,
   )
   ```

   `spanOf`/`depthOf` become `footprintOf(item, at: Cell): Pair<Int, Int>`, which
   asks `region.localAxis` instead of `dock.axis`. `axis` stays as a property for
   the strip case and is what `slotContent` is still handed.

5. **`Dock` gains a default region.** `defaultSlots` becomes
   `defaultRegion(gridW, gridH)`: `LEFT` is `strip(12, VERTICAL)`, `TOP` is
   `strip(24, HORIZONTAL)`, and so on. The numbers do not change.

6. **`DockLayout`** keeps every method — `place`, `move`, `fits`, `firstFit`,
   `addFloating`, `dockInto`, `closeBar`, `tidied`, `resized` → `reshaped`,
   `STARTER`, `DEFAULT`. Signatures gain a `Cell` where they took a `slot`.
   `DockLayoutTest` is ported the same way `ToolbarLayoutTest` is.

7. **`DockCodec` learns to read what it wrote** — see U7 for `v4`. In this item
   it only has to keep decoding `v1`/`v2`/`v3` into the new model: a `v3` bar's
   `slotCount` becomes a strip region of that length along the dock's axis, and
   `slot=` becomes a cell. `DockCodecTest` gains one case per old version
   asserting the resulting *region* as well as the placements.

8. **`DockHost` compiles again.** Mechanical: `bar` → `surface`, `slot` → cell,
   `slotAt` → `cellAt`. No behaviour change; the strip renderer stays exactly as
   it is until U3.

### Stop condition

**Every ported test green, and the app visually identical.** If the four edges
do not render pixel-for-pixel as before, stop and find out why here — not in U3,
where a real layout change would hide it.

---

## U3 — The renderer · 8–12 days

The one item with real Compose risk. New file `ui/ChromeSurface.kt`; `DockHost`
loses `BarRun`'s `Row`/`Column` and keeps everything else.

### The composable

A custom `Layout`, because a shape is not a row:

```kotlin
@Composable
private fun ChromeSurface(
    surface: Surface,
    …,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }
    Layout(
        content = { for (p in surface.slots.placements) SlotCell(...) },
        modifier = Modifier
            .surfaceGround(surface.region)                   // see below
            .onGloballyPositioned { drag.runOrigin[surface.id] = it.positionInRoot() },
    ) { measurables, _ ->
        val b = surface.region.bounds
        layout((b.w * slotPx).roundToInt(), (b.h * slotPx).roundToInt()) {
            measurables.forEachIndexed { i, m ->
                val p = surface.slots.placements[i]
                m.measure(Constraints.fixed((p.w * slotPx).toInt(), (p.h * slotPx).toInt()))
                 .place(((p.x - b.x) * slotPx).toInt(), ((p.y - b.y) * slotPx).toInt())
            }
        }
    }
}
```

Two consequences worth stating before they are discovered:

- **The identity survives.** Every cell is still `cells × SLOT` and nothing else,
  so `cellAt(point) = floor((point - runOrigin) / slotPx)` in both axes is exact.
  `DockDrag.slotAt` becomes two lines shorter than it is now.
- **Scrolling goes away for shaped surfaces.** A region is exactly as big as it
  is; there is nothing to scroll. Strips keep the existing scroll so nothing
  regresses today, and everything else uses overflow (U9). Say this in the KDoc
  or somebody will add a scroll back.

### The ground — and the answer to A3's stop condition

An L must read as **one shape**, not as two bars that touch. The trick is three
lines and it is worth naming here so nobody spends a week on it:

```kotlin
val path = android.graphics.Path()
for (r in region.rects) path.op(rectPath(r), Path.Op.UNION)
paint.pathEffect = android.graphics.CornerPathEffect(CORNER.toPx())
```

`Path.op(UNION)` merges the rectangles into one outline, and `CornerPathEffect`
rounds **every** corner of that outline — the outer ones and, crucially, the
inner one at the notch. An L then has the same radius everywhere, which is
exactly what makes it look drawn rather than assembled. Both APIs are API 19;
`minSdk` is 29.

Then:

- ground at `Chrome.BAR_ALPHA`, hairline at `BAR_OUTLINE_ALPHA`, from the same
  path — `drawBehind` with `drawIntoCanvas { it.nativeCanvas.drawPath(...) }`.
- `Modifier.clip(GenericShape { … })` from the same path, so a panel overhanging
  the surface is clipped to the shape and not to the bounding box.
- cache the `Path` in `remember(region, slotPx)`. It is rebuilt on shape change,
  never per frame.

### The pen must still reach the paper — trap 4, made concrete

The notch of an L is a hole in the chrome, and a stroke started there must go to
the canvas. Compose only consumes a pointer where an interactive node is, so the
rule is:

> **The surface container carries no pointer modifier outside Arrange mode.**
> Ground and outline are `drawBehind`; only the cells are interactive; the notch
> has no child and therefore no target.

In Arrange mode the container *does* take a `pointerInput` (that is where shape
editing lives), and in Arrange mode nobody is drawing. Gate it on `arranging`
and write the reason above the `if`.

This is the one claim in this document that must be **verified on the tablet**
before it is believed: draw a stroke starting inside an L's notch, and check
`RejectionCounters` says the pen owned it.

### Tests

Compose is not unit-tested in this repo and this item does not change that. What
*is* testable, and should be:

- `cellAt` × `placeAt` round-trip, as a pure function extracted from `DockDrag`
  into `ui/DropMath.kt` (it needs no Compose — it is arithmetic).
- the region → `Path` builder's rectangle count and bounds, JVM-testable through
  `android.graphics.Path` under Robolectric **only if** that is already
  available; otherwise assert the rectangle list handed to it and check the
  painting by eye. Do not add Robolectric for this alone.

### Stop condition

**If the corner join cannot be made to look deliberate**, drop non-rectangular
regions and keep everything else. U5–U10 are untouched by that decision, and
workspaces carry most of the value. Spend two days on it, not two weeks.

---

## U4 — Shaping a surface · 6–10 days

Three ways in, in the order they should be built — presets first, because that
is what most people want and it is what proves the renderer.

### 4a. Presets (2 days)

A menu in Arrange mode on a surface's grip: **Bar · L · T · U · Block**, each a
`CellRegion` factory from U1, each honouring the anchor. Applying one calls
`reshape(surfaceId, region)` on `DockLayout`, which reflows: existing placements
that still fit stay where they are, the rest go to overflow. Undoable by
re-picking the previous shape, which is one tap.

### 4b. Drag the ends (2–3 days)

Generalises `ResizeHandle` (`DockHost.kt:752`), which today resizes a floating
bar along and across. For a rectangle region it is the same gesture, clamped by
`MIN_CELLS`/`MAX_CELLS` as it is now. For a multi-rectangle region, the handle
resizes **the rectangle it is on**, which is the honest reading and needs no new
concept.

### 4c. Draw your toolbar (3–5 days)

The interaction nobody else has, and the reason this app should do it.

- In Arrange mode, a **Shape** toggle turns the whole screen into a cell grid,
  faintly drawn.
- A pen drag adds every cell it crosses to a `MutableSet<Cell>`, previewed live
  through `CellRegion.ofCells` — which is why U1 test 7 (stability) exists.
- The **eraser end** (`ToolType.ERASER`, already distinguished in
  `engine/input/ToolType.kt`) removes cells. So does a second drag with a
  Subtract toggle, for people without an eraser end.
- On lift: coalesce, clamp to the grid, and pack whatever was on the surface
  into the new shape. Items with nowhere to go land in overflow, visibly.
- Cells belonging to another surface are refused, greyed, and not painted. Two
  surfaces may not overlap — that is a rule, and it is checked here rather than
  discovered at render time.

**Tidy** is a menu item on the surface, not an ambient behaviour: it re-packs
every placement in flow order and is one undoable action. This is the whole of
the *"flow when shaping, freeze when drawing"* resolution, and it is four lines
of code — `pack(region, flow, fixed = emptyList(), flowing = all)`.

### Tests

Shape editing is gesture code, so the testable core is the pure part: a recorded
list of touched cells → a region. Record three real drags from the tablet as
fixtures and assert the rectangles. That is worth more than any amount of mocked
Compose.

---

## U5 — The catalogue, published · 3–4 days

Small, and it unblocks the whole AI story.

1. `ToolItem` gains **nothing behavioural**. It already has `id`, `label`,
   `short`, `cellsWide`, `cellsTall`, `group`, `kind`, `turnsWithDock`.
2. New `ui/ToolCatalogue.kt`:

   ```kotlin
   object ToolCatalogue {
       fun json(): String            // every entry, every field, sorted by id
       val version: Int              // bumped when an entry is added or removed
   }
   ```

3. A JVM test asserting: every `ToolItem` appears; ids are unique; no id
   contains a character the JSON schema forbids; the file parses.
4. A Gradle task `:app:catalogueJson` that writes `docs/catalogue.json`, and a
   test that fails when the checked-in file is stale. That is what stops the
   published vocabulary drifting from the enum — it cannot, because the build
   says so.

The file is the thing you hand a model along with the schema in U7. It is also
what the import screen uses to explain *"this workspace names three tools this
build does not have"*.

---

## U6 — The workspace object · 5–8 days

```kotlin
data class Workspace(
    val id: String,               // slug, stable, the filename
    val name: String,
    val description: String,
    val author: String,
    val revision: Int,
    val layout: DockLayout,
    val filter: CatalogueFilter,
    val defaults: WorkspaceDefaults,
)

data class CatalogueFilter(
    val groups: Set<ToolGroup>?,  // null = all
    val hide: Set<String>,        // ids
    val show: Set<String>,        // ids, wins over groups — the escape hatch's home
) {
    operator fun contains(item: ToolItem): Boolean
}

data class WorkspaceDefaults(
    val brush: String?,           // BrushPreset id — see docs/brush-shelf-plan.md
    val shelf: List<String>,
    val stabilisation: Float?,
    // page setup, guides, and the rest arrive with the features that own them
)
```

### Where it lives

Two stores, on purpose, and the split is the one `DockCodec` already justifies:

| What | Where | Written when |
|---|---|---|
| The current workspace's **arrangement** | `SharedPreferences`, `DockCodec` string, existing key | every drag |
| The workspace **file** (filter, defaults, name, and the arrangement) | `filesDir/workspaces/<id>.json` | switch, rename, export, and on a debounce after a drag |
| Which workspace is current | `SharedPreferences`, one key | switch |

A drag writes the fast path only. Crash after a drag: the arrangement is in
prefs and is restored, the file catches up on next save. Losing the fast path
would mean writing a JSON file on every drop, which is the kind of thing that
shows up as a dropped frame six months later.

`WorkspaceStore(context)`: `list()`, `load(id)`, `save(ws)`, `current()`,
`switchTo(id)`, `duplicate(id)`, `delete(id)`, `import(json)`, `export(id)`.

### Switching

Switching is: load the file, set `docks`, apply the filter to the chooser, apply
the defaults that a subsystem exposes a setter for. It must be **instant** — no
animation, no dialog, no reload of the document. The stop condition from the
workspace plan applies: *if switching takes long enough to see, it is a mode
change rather than a personality change, and it will not be used.*

### The filter, and the two rules that keep it honest

1. **The chooser (`DockHost.kt:1061`) filters; the layout does not.** A tool
   already placed but outside the filter keeps working and keeps its cell. A
   filter is about what you are *offered*, never about what is taken away — that
   distinction is what makes switching safe.
2. **`DockStore.introduce` respects the filter for placement and not for the
   offered set.** A newly shipped tool hidden by the current workspace is
   recorded as offered and simply not placed; switching to *Everything* later
   must not resurrect it, because the offered-set rule ("offered-then-deleted
   and offered-then-kept look identical") is the one that protects a deliberate
   removal.

---

## U7 — The file format · 5–8 days

New `ui/WorkspaceJson.kt`. `org.json` is in the platform, so no dependency.

### The schema

As `docs/workspace-plan.md` gives it, with the three properties that make it
work: **`at` is optional**, **no sizes anywhere**, **unknown things drop**.
Written out as `docs/workspace-format.md` — a reference with a worked example, a
field table, and the rules — because that document is half of what you hand a
model.

### Decoding returns a report, not a workspace

```kotlin
data class Decoded(
    val workspace: Workspace?,     // null only if it is not a workspace file at all
    val dropped: List<String>,     // human-readable, shown on the import screen
)

fun decode(text: String): Decoded
```

Every drop is one line the user can read: *"`perspective_ruler` — this build has
no such tool"*, *"surface `corner` — rectangle 3 is off the screen"*. The
importer shows them; it does not hide them and it does not fail on them.

### The `v4` compact string

`DockCodec` gains a region and a flow per bar and `VERSION` becomes `v4`:

```
v4|left:R0,0,1,12:down_right:0,0=pen,0,1=pencil
```

Rules, all inherited: `v1`, `v2`, `v3` still decode (a `v3` bar becomes a strip);
`v4` is what is written; a `v4` string read by an older build is unreadable and
falls back to the default, which is why the **workspace file** is the sharing
format and the compact string is never sent anywhere.

### Untrusted input — the checks, spelled out

A workspace file is a file from a stranger. The importer enforces, rather than
assumes:

- size ceiling (64 KiB), surface count ceiling (16), item count ceiling (256),
  rectangle count ceiling (32 per surface) — every one of these is an
  allocation sized by a number somebody typed, exactly as `MAX_SLOTS` already is;
- strings are length-capped and stripped of control characters before they reach
  a `Text`;
- **no URLs, no paths, no code, no colours-as-resource-names.** The format has no
  field that could carry one, and the decoder rejects any unknown key rather
  than passing it through on export;
- import **never overwrites the current workspace** — it always creates a new
  one, with a `-2` suffix on a name clash;
- a preview screen before it is applied, showing the shape, the tool count and
  the dropped list.

### Tests

- round-trip: workspace → JSON → workspace, equal, for the three shipped ones;
- `at`-less file packs deterministically, twice, same result;
- a corpus in `app/src/test/resources/workspaces/` of hostile files — truncated,
  deeply nested, 10 MB of one array, duplicate ids, negative rectangles, a
  surface entirely off-screen, an unknown top-level version — each asserting
  *what was dropped* and that nothing threw;
- forward compatibility: a file with `"artiest_workspace": 2` and unknown fields
  opens, dropping what it cannot do.

---

## U8 — What ships · 3–5 days

Three workspaces, as **assets** parsed by the same decoder an import uses —
which makes the shipped files a permanent test of the importer:

`app/src/main/assets/workspaces/{sketcher,clean,everything}.json`

- **Sketcher** — pen, pencil, eraser, size, stabilisation, colour, undo/redo,
  layers. `groups: [draw, edit]` plus `layers`. Honest today; honest*er* after
  P3.
- **Clean** — the canvas and almost nothing else: one small L in a corner with
  four tools. This is the one that demonstrates the shape.
- **Everything** — the current interface, unfiltered. Nothing is ever lost, and
  it is where the escape hatch sends you if you get stuck.

Plus **the escape hatch**, which is not optional and is the mitigation for
trap 2: a search field in the chooser that matches **the whole catalogue**,
filter or no filter, and offers *"Add to this workspace"* on a hit. One tap,
always available, and it adds the id to `filter.show`.

---

## U9 — Overflow, clamping, other screens · 4–6 days

The rule from the workspace plan, in order: **clamp, overflow, say so.**

1. `CellRegion.clampedTo(gridW, gridH)` where the grid is
   `floor(usableWidth / 44dp) × floor(usableHeight / 44dp)`, computed once in
   `DockHost`'s `BoxWithConstraints` and passed down.
2. Anything that no longer fits after clamping goes to overflow, in flow order.
3. **The overflow chevron** at the end of the surface: a one-cell button showing
   a count, opening a menu of what is not shown, each entry tappable to use it
   and draggable to place it. It is the promise that no control silently
   disappears — `ToolItem`'s rule 1, which the whole catalogue is built on.
4. Rotation: the grid changes, regions re-clamp, placements re-pack **only if
   they no longer fit**. A layout that fits both orientations must not move.
   That is a test.

### Tests

Pure, and they are the ones that keep a shared workspace usable on a phone:

- a 24-cell top strip on a 12-cell grid → 12 placed, the rest in overflow, in
  order;
- rotate and rotate back → the original arrangement, exactly;
- a region whose anchor is `bottom-left` on a shorter screen keeps its foot on
  the bottom, not its head at the top;
- a floating surface's `BarSpot` still survives rotation (it already does — do
  not break it).

---

## U10 — Measure it · 2–3 days

The last trap, and the one this project does not tolerate: **a dropped frame
while drawing.**

- Draw for sixty seconds with three surfaces and twenty visible controls, with
  `STATS` on. Compare the frame-time distribution against the same drawing with
  today's four strips.
- The number that matters is the 99th percentile, not the mean.
- If recomposition shows up at all, the fix is known and boring: the surface's
  `Layout` reads `surface.region` and `surface.slots` and nothing else, and
  `slotContent` already isolates every control's own state. Hoist whatever is
  reading the drawing's state into a child.

**Gate: this item is not optional and does not get skipped when U1–U9 run long.**
It is the difference between a UI system and a regression.

---

## The order, and what each one leaves you with

| # | Item | Days | After it, you can | Maps to |
|---|---|---|---|---|
| U1 | `CellRegion` + `RegionLayout` | 5–8 | describe an L and fill it, in a test | A1 |
| U2 | `SurfaceLayout`, `Surface`, migration | 5–7 | the same app, on the new model | A2 |
| U3 | The renderer | 8–12 | **see an L-shaped toolbar** | A3 |
| U4 | Shaping: presets, ends, draw-it | 6–10 | make one, with the pen | A4 |
| U5 | `catalogue.json` | 3–4 | hand a model the vocabulary | A6 |
| U6 | `Workspace` + store + filter | 5–8 | switch program by name | A5 |
| U7 | JSON, import/export, validation | 5–8 | share one; a model writes one | A6 |
| U8 | Three shipped workspaces + search | 3–5 | it is visible on first launch | A7 |
| U9 | Overflow, clamping, rotation | 4–6 | it survives a phone | A8 |
| U10 | Measure while drawing | 2–3 | ship it | — |

**45–62 days.** The workspace plan said 40–60; the difference is U5 pulled out
of A6 and U10 added, and both were worth naming.

### If there is only a fortnight

U1 + U2 + U3, and stop. That gets the model and one L-shaped toolbar on screen,
which is the part that either works or does not. Everything after it is
additive and none of it is load-bearing for the others.

### The one thing to build out of order

**U5 (`catalogue.json`) can be done in an afternoon, any time, today.** It has no
dependencies, it is four hours of work, and it is the file that lets you start
experimenting with AI-authored layouts *before* the system that consumes them
exists. If the experiment says a model cannot author a good layout from a
catalogue and a schema, that is worth knowing before U7 is written.

---

## The traps, with what to do about each

| Trap | Where it bites | Mitigation |
|---|---|---|
| **A wardrobe for three shirts** | 22 catalogue entries today | Build U1–U10 and stop. No gallery, no cloud, no in-app model, no import from other programs. |
| **Hiding a tool is a promise it was not needed** | U6's filter | U8's search, always, one tap, no exceptions |
| **A drawn shape covers the paper** | U4c | Out of scope here and stated as such. The canvas's safe area is its own design. |
| **The pen caught by the interface** | U3, the notch | No pointer modifier on the container outside Arrange; verify on the tablet with `RejectionCounters` |
| **Versioning both ways** | U7 | A `v4` file opens in a `v3` build by dropping. Tests, both directions. |
| **Recomposition cost** | U3, U10 | U10 is a gate, not a nice-to-have |
| **Two surfaces overlapping** | U4 | Refused at shape time, not discovered at render time |
| **The migration** | U2 | The commit that changes the model changes no pixels; the commit that changes pixels changes no model |

---

## What this unblocks

Every one of the sixty features in `docs/vector-plan.md`,
`docs/layer-effects-plan.md`, `docs/guides-plan.md`, `docs/text-plan.md`,
`docs/comics-plan.md` and `docs/animation-plan.md` becomes, on the UI side:

> one `ToolItem` entry, one `when` branch in `ToolSlot`, and a line in whichever
> shipped workspaces want it.

That is the whole return on 45–62 days, and it is why `docs/master-plan.md` puts
it first.

## See also

- `docs/workspace-plan.md` — why, and the design arguments this implements
- `docs/master-plan.md` — where this sits, and what it costs not to do it
- `docs/ui-plan.md`, `docs/panels-plan.md` — the dock and panel systems this
  extends rather than replaces
