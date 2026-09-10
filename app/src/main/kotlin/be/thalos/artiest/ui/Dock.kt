package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset

/**
 * Which way a run of cells goes.
 *
 * Since shapes arrived this is mostly [CellRegion]'s business — a shape knows
 * which way it runs at every cell, which a dock never could. What is left here
 * is the anchor's own idea of itself: the left edge runs down, the top edge
 * runs across, and that is what decides the shape a fresh one gets.
 */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Where a surface is attached.
 *
 * **This is an attachment, not an identity.** It used to be both: there were
 * five docks and five bars and the enum was the key. There can now be any number
 * of floating surfaces — the user makes them and closes them — so a surface
 * carries an id of its own and this says only where it sits.
 *
 * [id] is persisted for the four edges, and is deliberately not [name]: renaming
 * a Kotlin constant is a refactor, renaming a persisted key silently empties
 * somebody's toolbar. **The ids do not move**, which is what keeps every layout
 * string written by every previous release readable.
 */
enum class Dock(
    val id: String,
    val label: String,
    val axis: Axis,
    val defaultSlots: Int,
) {
    LEFT("left", "Left edge", Axis.VERTICAL, 12),
    TOP("top", "Top edge", Axis.HORIZONTAL, 24),
    RIGHT("right", "Right edge", Axis.VERTICAL, 12),
    BOTTOM("bottom", "Bottom edge", Axis.HORIZONTAL, 24),

    /**
     * Not attached to anything, and there may be several.
     *
     * A floating surface sits over the drawing, so every cell it has is paper
     * it is covering. They are made to size rather than to a constant — see
     * [DockLayout.addFloating] — and [defaultSlots] is only the fallback.
     */
    FLOATING("float", "Floating", Axis.HORIZONTAL, 8),
    ;

    val isEdge: Boolean get() = this != FLOATING

    /**
     * The shape a fresh surface on this anchor gets: a plain bar, as before.
     *
     * The numbers are the ones this enum has always carried, so a first run
     * looks exactly as it did. What changed is that they are now a *default*
     * rather than the only shape available — the user can draw an L over the
     * top of it and this stays the thing they started from.
     */
    fun defaultRegion(): CellRegion = CellRegion.strip(defaultSlots, axis)

    /** How a fresh surface on this anchor fills: along the edge it is on. */
    fun defaultFlow(): FlowOrder = FlowOrder.along(axis)

    companion object {
        val EDGES: List<Dock> = entries.filter { it.isEdge }

        private val BY_ID: Map<String, Dock> = entries.associateBy { it.id }

        fun byId(id: String): Dock? = BY_ID[id]
    }
}

/**
 * Where a floating surface sits, as a fraction of the window in each axis.
 *
 * Fractions and not pixels, and that is the answer to the question the UI plan
 * left for a human: *"does the floating dock need to survive rotation, or
 * reset?"* A fraction survives it, and costs one line rather than a second saved
 * position per orientation. It is not perfect — a bar three quarters of the way
 * down a landscape window lands three quarters of the way down a portrait one,
 * which is further in absolute terms than the user put it — but it is never
 * off-screen, and the drag to fix it is one gesture.
 */
data class BarSpot(val x: Float, val y: Float) {
    companion object {
        /** Null for a position that cannot be rendered. Clamped, never thrown. */
        fun of(x: Float, y: Float): BarSpot? {
            if (!x.isFinite() || !y.isFinite()) return null
            return BarSpot(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }

        /**
         * Beside a button at [anchor] in a window [w] by [h], in pixels.
         *
         * What "fixate puts it in the neighbourhood of where it was opened"
         * comes to. Far enough across to clear the bar the button is on: the
         * button is usually on an edge, and a new bar landing on top of that
         * edge is in the neighbourhood in the least useful sense.
         */
        fun beside(anchor: Offset, w: Int, h: Int): BarSpot {
            if (w <= 0 || h <= 0) return FALLBACK
            // Towards the middle, not always to the right. A button on the
            // right edge pushed further right lands off the screen and clamps
            // back onto the bar it came from, which is the one place it must
            // not be.
            val inward = if (anchor.x > w / 2f) -CLEAR_OF_THE_BAR else CLEAR_OF_THE_BAR
            return of((anchor.x + inward) / w, (anchor.y + 40f) / h) ?: FALLBACK
        }

        /** One bar's thickness and then some, in pixels at a tablet's density. */
        private const val CLEAR_OF_THE_BAR = 190f

        private val FALLBACK = BarSpot(0.34f, 0.38f)
    }
}

/**
 * One toolbar: where it is, what shape it is, and what is on it.
 *
 * This was `Bar`, and the rename is the whole point of the change: a bar is a
 * line, and this is any shape at all. What it kept is everything that made a
 * bar work — an id, an anchor, a position for the floating ones — because none
 * of that was about being a line.
 *
 * [id] is `left`, `top`, `right` or `bottom` for the four edges, and `f1`, `f2`
 * … for floating surfaces. It is what everything else keys on, because a
 * floating surface has no other name and the edges may as well be named the
 * same way.
 *
 * [spot] is set for floating surfaces and null for edges, which is the type
 * saying out loud that an edge's position is not the user's to choose.
 *
 * The shape lives on [slots] and is reached through [region], rather than being
 * held here as well. Two copies of a shape is one shape and a bug waiting for
 * the day they disagree.
 */
class Surface(
    val id: String,
    val dock: Dock,
    val spot: BarSpot?,
    /** Which way it fills. Where the next thing dropped on it lands. */
    val flow: FlowOrder,
    val slots: SurfaceLayout,
) {
    val region: CellRegion get() = slots.region

    /**
     * The one direction this surface runs, for the things that still need one.
     *
     * A strip has an answer and a shape does not, so a shape falls back to its
     * anchor's. Everything that can ask per-cell should ask [CellRegion.localAxis]
     * instead; this is for the renderer's outer frame and for `slotContent`,
     * which is handed one axis for the whole control.
     */
    val axis: Axis get() = region.stripAxis ?: dock.axis

    val isEmpty: Boolean get() = slots.isEmpty
    val isFloating: Boolean get() = dock == Dock.FLOATING

    fun with(
        slots: SurfaceLayout = this.slots,
        spot: BarSpot? = this.spot,
        flow: FlowOrder = this.flow,
    ): Surface = Surface(id, dock, spot, flow, slots)

    /** The same surface reshaped, dropping whatever no longer fits. */
    fun reshaped(region: CellRegion): Surface = with(slots = slots.reshaped(region))

    /**
     * How many cells [item] would take at [at], the way round it wants to be.
     *
     * This is `spanOf` and `depthOf` in one, and it asks the *shape* rather
     * than the dock — which is what makes a slider stand up in an L's arm and
     * lie flat along its foot. See [RegionLayout.naturalSize].
     */
    fun footprintOf(item: ToolItem, at: Cell): Pair<Int, Int> =
        RegionLayout.naturalSize(item, region, at)

    /** The cell [slot] cells along a strip, from its start. */
    fun cellAt(slot: Int): Cell {
        val b = region.bounds
        return if (axis == Axis.HORIZONTAL) Cell(b.x + slot, b.y) else Cell(b.x, b.y + slot)
    }

    /** How far along a strip a cell is. The inverse of [cellAt]. */
    fun slotOf(cell: Cell): Int {
        val b = region.bounds
        return if (axis == Axis.HORIZONTAL) cell.x - b.x else cell.y - b.y
    }

    /** How long a strip is, in cells. */
    val slotCount: Int get() = region.lengthAlong(axis)

    /** How many cells [p] takes along this surface's axis. */
    fun spanOf(p: CellPlacement): Int = if (axis == Axis.HORIZONTAL) p.w else p.h

    /** How far [p] sticks out across it. */
    fun depthOf(p: CellPlacement): Int = if (axis == Axis.HORIZONTAL) p.h else p.w

    /** How thick this is across its axis, in cells. One, or its deepest item. */
    val depthCells: Int get() = slots.placements.maxOfOrNull { depthOf(it) } ?: 1

    override fun equals(other: Any?): Boolean =
        other is Surface && other.id == id && other.dock == dock &&
            other.spot == spot && other.flow == flow && other.slots == slots

    override fun hashCode(): Int = listOf(id, dock, spot, flow, slots).hashCode()

    override fun toString(): String = "$id($dock${spot?.let { "@$it" } ?: ""}, $slots)"
}

/** A placement and the surface it is on. The answer [DockLayout.locate] gives. */
data class DockedItem(val surface: Surface, val placement: CellPlacement) {
    val item: ToolItem get() = placement.item
    val cell: Cell get() = placement.cell
}

/**
 * Every surface, and the rules that tie them together.
 *
 * ## What this is, and what it deliberately is not
 *
 * A list of [Surface]s with a handful of operations on top. It is **not** a
 * layout engine: [fits] and [place] delegate straight to the surface in
 * question, so every edge case about footprints, collisions and the end of a
 * shape is answered by [SurfaceLayout], and by the tests that already cover it.
 * What this adds is which surface, and the arithmetic that turns an item into a
 * footprint.
 *
 * It also keeps its name. A `DockLayout` is an *arrangement*; a workspace is an
 * arrangement plus a filter plus a set of defaults, and calling this one the
 * other would leave the second thing without a word.
 *
 * ## The one rule a single bar did not need
 *
 * **An item appears in at most one place.** [SurfaceLayout] allows the same item
 * twice, and on one surface that is merely odd. Across several it is a bug with
 * a face: two Eraser buttons in different corners, one lit and one not, both
 * real. So [place] removes the item from wherever else it was, and [of] drops
 * the later of two copies.
 *
 * That rule is also what makes dragging work without any code for dragging.
 * Moving an item from the left edge to a floating panel is [place] on the
 * floating surface — the removal falls out of the rule, and there is no second
 * path through which a move can go wrong.
 *
 * ## Surfaces come and go
 *
 * The four edges always exist, in [Dock.EDGES] order, and can only be emptied.
 * Floating ones are made by [addFloating] and destroyed by [closeSurface],
 * which is how "fixate" and "close" are ordinary operations rather than new
 * ideas.
 *
 * Immutable, so it can be Compose state and so that every test is one
 * expression.
 */
class DockLayout private constructor(val surfaces: List<Surface>) {

    val isEmpty: Boolean get() = surfaces.all { it.isEmpty }

    /** The four edges, always present, always in the same order. */
    val edges: List<Surface> get() = surfaces.filter { !it.isFloating }

    /** The surfaces the user made, oldest first. */
    val floating: List<Surface> get() = surfaces.filter { it.isFloating }

    fun surface(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** The surface on [dock]. Only meaningful for the four edges. */
    fun edge(dock: Dock): Surface = surfaces.first { it.dock == dock && !it.isFloating }

    /** Every placed item, with the surface it is on. */
    fun all(): List<DockedItem> =
        surfaces.flatMap { s -> s.slots.placements.map { DockedItem(s, it) } }

    /** Where [item] currently is, or null if it is on no surface. */
    fun locate(item: ToolItem): DockedItem? = all().firstOrNull { it.item == item }

    /** True if [item] is on some surface. What the chooser ticks. */
    operator fun contains(item: ToolItem): Boolean = locate(item) != null

    /**
     * Would [item] go at [cell] on [surfaceId]?
     *
     * The item is taken out of that surface first when it is already on it,
     * because [place] moves rather than copies: by the time it lands, the cells
     * it used to hold are free, so a `fits` that still sees it there refuses
     * placements that would in fact succeed. Dragging a four-cell slider two
     * cells along its own bar is exactly that case, and it is the one a hand
     * tries first.
     */
    fun fits(surfaceId: String, item: ToolItem, cell: Cell, ignoring: Cell? = null): Boolean {
        val s = surface(surfaceId) ?: return false
        val (w, h) = s.footprintOf(item, cell)
        return without(s, item).fits(w, h, cell.x, cell.y, ignoring = ignoring)
    }

    /**
     * Put [item] at [cell] on [surfaceId], taking it out of wherever it was.
     *
     * Throws if it does not fit, exactly as [SurfaceLayout.place] does and for
     * the same reason: a control that silently declines to appear cannot be
     * told apart from one that is broken.
     */
    fun place(surfaceId: String, item: ToolItem, cell: Cell): DockLayout {
        val target = surface(surfaceId) ?: return this
        val was = locate(item)
        val cleared = was
            ?.let { withSurface(it.surface.with(slots = it.surface.slots.remove(it.cell))) }
            ?: this
        val fresh = cleared.surface(surfaceId) ?: target
        // A size the user chose follows the control, so long as it is going
        // somewhere that runs the same way. Across the turn there is nothing
        // sensible to carry -- a panel six wide and eleven tall does not become
        // eleven wide by being moved -- so it goes back to what the shape says.
        val natural = fresh.footprintOf(item, cell)
        val kept = was?.placement?.takeIf { was.surface.axis == fresh.axis }
        return cleared.withSurface(
            fresh.with(
                slots = fresh.slots.place(
                    CellPlacement(
                        item = item,
                        x = cell.x,
                        y = cell.y,
                        w = kept?.w ?: natural.first,
                        h = kept?.h ?: natural.second,
                    )
                )
            )
        )
    }

    /**
     * Resize a floating strip, and the panel on it if there is exactly one.
     *
     * A bar of buttons only has a length; a bar holding a panel is that panel's
     * window, so the two numbers are the panel's. Both are the user's choice and
     * both are clamped rather than refused — a drag that asks for a bar of
     * minus three cells is a hand, not an error.
     *
     * A surface that is not a strip is left alone: the handle that drives this
     * resizes a *bar*, and a shape is reshaped rather than resized.
     */
    fun resizeFloating(surfaceId: String, along: Int, across: Int): DockLayout {
        val s = surface(surfaceId) ?: return this
        if (!s.isFloating) return this
        val axis = s.region.stripAxis ?: return this
        val cells = along.coerceIn(MIN_CELLS, MAX_CELLS)
        val deep = across.coerceIn(MIN_CELLS, MAX_CELLS)
        val region = CellRegion.strip(cells, axis)
        val only = s.slots.placements.singleOrNull()?.takeIf { it.item.kind == ToolKind.PANEL }
            ?: return withSurface(s.with(slots = s.slots.reshaped(region)))
        val sized = if (axis == Axis.HORIZONTAL) {
            CellPlacement(only.item, 0, 0, cells, deep)
        } else {
            CellPlacement(only.item, 0, 0, deep, cells)
        }
        return withSurface(s.with(slots = SurfaceLayout.of(region, listOf(sized))))
    }

    /**
     * Empty a floating surface into another, and close it.
     *
     * What dragging a floating bar onto an edge does. Everything on it moves in
     * order, each to the first cell that will take it; if any of them will not
     * fit, nothing moves and the answer is null — half a bar arriving is worse
     * than none, because the half left behind is on a bar that is about to be
     * closed.
     */
    fun dockInto(from: String, to: String): DockLayout? {
        val source = surface(from) ?: return null
        if (!source.isFloating || from == to) return null
        val target = surface(to) ?: return null
        var next = this
        for (placement in source.slots.placements) {
            val cell = next.firstFit(target.id, placement.item) ?: return null
            next = next.place(target.id, placement.item, cell)
        }
        return next.closeSurface(from)
    }

    /** Empty the cell on [surfaceId]. A no-op on an empty one. */
    fun remove(surfaceId: String, cell: Cell): DockLayout {
        val s = surface(surfaceId) ?: return this
        return withSurface(s.with(slots = s.slots.remove(cell)))
    }

    /**
     * Send whatever is at [cell] of [from] to [to], at the first cell it fits.
     *
     * Returns null when the destination has no room, which is the honest answer
     * and the one the caller can act on — the drop is refused and the item stays
     * where the user could still see it. Throwing here would be wrong: unlike
     * [place], this is reached by a gesture, and a gesture that lands somewhere
     * full is a normal thing for a hand to do.
     */
    fun move(from: String, cell: Cell, to: String, toCell: Cell? = null): DockLayout? {
        val source = surface(from) ?: return null
        val moving = source.slots.covering(cell) ?: return null
        val target = toCell?.takeIf { fits(to, moving.item, it) }
            ?: firstFit(to, moving.item)
            ?: return null
        return place(to, moving.item, target)
    }

    /** The first cell on [surfaceId] that [item] would fit in, or null. */
    fun firstFit(surfaceId: String, item: ToolItem): Cell? {
        val s = surface(surfaceId) ?: return null
        val free = without(s, item)
        return s.region.cells(s.flow).firstOrNull { cell ->
            val (w, h) = s.footprintOf(item, cell)
            free.fits(w, h, cell.x, cell.y)
        }
    }

    /**
     * Make a floating surface holding [item], at [spot], and say what it is
     * called.
     *
     * This is the whole of "fixate". It is sized to the item plus a little
     * room, rather than to a constant: a floating surface is paper the drawing
     * cannot use, so it is as long as it has to be and no longer, and the spare
     * cells are there so that something else can be dropped in beside it.
     */
    fun addFloating(item: ToolItem, spot: BarSpot): Pair<DockLayout, String> {
        val id = nextFloatingId()
        val axis = Dock.FLOATING.axis
        val span = if (axis == Axis.HORIZONTAL) item.cellsWide else item.cellsTall
        val surface = Surface(
            id = id,
            dock = Dock.FLOATING,
            spot = spot,
            flow = Dock.FLOATING.defaultFlow(),
            slots = SurfaceLayout.empty(CellRegion.strip(span + SPARE_SLOTS, axis)),
        )
        return DockLayout(surfaces + surface).place(id, item, Cell(0, 0)) to id
    }

    /** Move a floating surface. A no-op on an edge, whose position is not the user's. */
    fun moveSurface(surfaceId: String, spot: BarSpot): DockLayout {
        val s = surface(surfaceId) ?: return this
        if (!s.isFloating) return this
        return withSurface(s.with(spot = spot))
    }

    /**
     * Give a surface a new shape, keeping what still fits.
     *
     * The single entry point for every way of changing one: a preset, a dragged
     * end, a shape drawn with the pen, or a screen that turned out to be
     * smaller. What no longer fits is dropped here and is the caller's to
     * overflow — see `docs/ui-expansion-plan.md`, *clamp, overflow, say so*.
     */
    fun reshape(surfaceId: String, region: CellRegion): DockLayout {
        val s = surface(surfaceId) ?: return this
        return withSurface(s.reshaped(region))
    }

    /** Change which way a surface fills. Nothing already placed moves. */
    fun reflow(surfaceId: String, flow: FlowOrder): DockLayout {
        val s = surface(surfaceId) ?: return this
        return withSurface(s.with(flow = flow))
    }

    /**
     * Close a floating surface, and everything on it.
     *
     * An edge cannot be closed — there is nowhere for it to go and no way to
     * get it back — so this empties it instead. A floating surface is a thing
     * the user made, so closing it is closing it.
     */
    fun closeSurface(surfaceId: String): DockLayout {
        val s = surface(surfaceId) ?: return this
        return if (s.isFloating) {
            DockLayout(surfaces.filter { it.id != surfaceId })
        } else {
            withSurface(s.with(slots = s.slots.cleared()))
        }
    }

    /** Every surface emptied, and every floating one gone. */
    fun cleared(): DockLayout = of(edges.map { it.with(slots = it.slots.cleared()) })

    /**
     * Floating surfaces with nothing left on them, dropped.
     *
     * Dragging the last control off one leaves a shape over the drawing that
     * does nothing. It is not deleted as it happens — it has to survive being
     * empty for as long as the drag is being undone — so it is tidied when the
     * layout is saved.
     */
    fun tidied(): DockLayout = of(surfaces.filter { !it.isFloating || !it.isEmpty })

    /**
     * The same items on surfaces of different shapes.
     *
     * Used by the store, which widens a saved layout to the current defaults so
     * that a release adding a control does not strand a user whose bars are
     * full. Anything that no longer reaches is dropped, by [SurfaceLayout.of].
     */
    fun reshaped(regionOf: (Surface) -> CellRegion): DockLayout =
        of(surfaces.map { it.reshaped(regionOf(it)) })

    private fun without(surface: Surface, item: ToolItem): SurfaceLayout {
        val here = locate(item) ?: return surface.slots
        return if (here.surface.id == surface.id) surface.slots.remove(here.cell) else surface.slots
    }

    private fun withSurface(surface: Surface): DockLayout =
        DockLayout(surfaces.map { if (it.id == surface.id) surface else it })

    private fun nextFloatingId(): String {
        val taken = floating.mapNotNull { it.id.removePrefix(FLOAT_PREFIX).toIntOrNull() }.toSet()
        var n = 1
        while (n in taken) n++
        return "$FLOAT_PREFIX$n"
    }

    override fun equals(other: Any?): Boolean = other is DockLayout && other.surfaces == surfaces

    override fun hashCode(): Int = surfaces.hashCode()

    override fun toString(): String = "DockLayout(" + surfaces.joinToString("; ") + ")"

    companion object {
        /** Floating surface ids are this and a number. Persisted, so it does not move. */
        const val FLOAT_PREFIX = "f"

        /** Room to drop something else in beside a freshly fixated panel. */
        private const val SPARE_SLOTS = 2

        /** A bar smaller than this is not a bar; larger than this is not a screen. */
        private const val MIN_CELLS = 2
        private const val MAX_CELLS = 24

        /**
         * Build a layout from whatever surfaces are supplied, filling in the
         * rest.
         *
         * Three normalisations, and together they are why a codec can be
         * careless with its input: a missing edge becomes an empty bar of that
         * edge's default shape, a floating surface without a position gets one,
         * and an item that appears twice keeps only its first appearance. None
         * of them raises.
         */
        fun of(surfaces: List<Surface>): DockLayout {
            val seen = HashSet<ToolItem>()
            val out = ArrayList<Surface>(surfaces.size + Dock.EDGES.size)

            fun keep(surface: Surface) {
                val kept = surface.slots.placements.filter { seen.add(it.item) }
                out += if (kept.size == surface.slots.placements.size) {
                    surface
                } else {
                    surface.with(slots = SurfaceLayout.of(surface.region, kept))
                }
            }

            for (dock in Dock.EDGES) {
                keep(
                    surfaces.firstOrNull { it.dock == dock && !it.isFloating }
                        ?: Surface(
                            dock.id, dock, null, dock.defaultFlow(),
                            SurfaceLayout.empty(dock.defaultRegion()),
                        )
                )
            }
            val ids = HashSet<String>(out.map { it.id })
            for (surface in surfaces.filter { it.isFloating }) {
                if (!ids.add(surface.id)) continue
                keep(surface.with(spot = surface.spot ?: BarSpot(DEFAULT_X, DEFAULT_Y)))
            }
            return DockLayout(out)
        }

        /** Just the four empty edges. */
        val EMPTY: DockLayout get() = of(emptyList())

        /**
         * A fresh install, and it is **not** empty.
         *
         * A single row of dashed slots reads as *tap me*. Four empty edges is
         * four times that bet and it is not one worth taking — a first run that
         * shows empty frames around a white page looks broken rather than
         * inviting.
         *
         * So the default is a working set, arranged by what the control is for:
         *
         * - **Left edge — what is in your hand.** Pen, pencil, eraser, colour,
         *   with an empty slot separating the tools from the colour so the four
         *   do not read as one run.
         * - **Top edge — what you did, and what leaves the app.** Undo and redo,
         *   then export and the instruments, with a gap between the two ideas.
         * - **Right edge — where you are looking.** Zoom and fit, deliberately
         *   opposite the tools: changing the view is not changing the mark, and
         *   putting them on the same edge is how you zoom when you meant to
         *   erase.
         * - **Bottom edge — how the mark comes out.** The sliders, along the
         *   long axis, where a slider has room to be a slider.
         * - **No floating surfaces**, because a floating surface is something
         *   the user made and there is no honest guess at one.
         */
        val STARTER: DockLayout
            get() = of(
                listOf(
                    edgeOf(Dock.LEFT, ToolItem.PEN to 0, ToolItem.PENCIL to 1,
                        ToolItem.ERASER to 2, ToolItem.COLOUR to 4),
                    edgeOf(Dock.TOP, ToolItem.UNDO to 0, ToolItem.REDO to 1,
                        ToolItem.EXPORT to 3, ToolItem.STATS to 4),
                    edgeOf(Dock.RIGHT, ToolItem.ZOOM_IN to 0, ToolItem.ZOOM_OUT to 1,
                        ToolItem.FIT to 2),
                    edgeOf(Dock.BOTTOM, ToolItem.SIZE to 0, ToolItem.SMOOTHING to 4,
                        ToolItem.GRAIN to 8),
                ),
            )

        /** What a fresh install gets. See [STARTER] for why it is not [EMPTY]. */
        val DEFAULT: DockLayout get() = STARTER

        private fun edgeOf(dock: Dock, vararg at: Pair<ToolItem, Int>): Surface {
            val region = dock.defaultRegion()
            val horizontal = dock.axis == Axis.HORIZONTAL
            return Surface(
                dock.id, dock, null, dock.defaultFlow(),
                SurfaceLayout.of(
                    region,
                    at.map { (item, slot) ->
                        val cell = if (horizontal) Cell(slot, 0) else Cell(0, slot)
                        val (w, h) = RegionLayout.naturalSize(item, region, cell)
                        CellPlacement(item, cell.x, cell.y, w, h)
                    },
                ),
            )
        }

        /** Clear of the left tools and above the bottom sliders, on a first run. */
        private const val DEFAULT_X = 0.32f
        private const val DEFAULT_Y = 0.42f
    }
}
