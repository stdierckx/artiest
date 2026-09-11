package be.thalos.artiest.ui

/**
 * Which way a run of cells goes.
 *
 * This is [CellRegion]'s business — a shape knows which way it runs at every
 * cell, which a dock never could. What is left of the word here is the two
 * answers a control can be drawn with: a slider lying flat, or a slider
 * standing up.
 */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * One side of the screen, as an answer to *where should this go the first time
 * it is seen*.
 *
 * Not a dock. Nothing is attached to it, nothing stays on it, and no part of
 * the interface offers it. See [Anchor].
 */
enum class Side(val id: String) {
    LEFT("left"),
    TOP("top"),
    RIGHT("right"),
    BOTTOM("bottom"),
    ;

    companion object {
        private val BY_ID: Map<String, Side> = entries.associateBy { it.id }

        fun byId(id: String): Side? = BY_ID[id]
    }
}

/**
 * Where a surface goes the first time it meets a screen, before it has cells of
 * its own.
 *
 * Since `docs/ui-grid-plan.md` a surface's position **is** the cells it is drawn
 * on, and there is nothing else. That leaves one gap: a surface that has never
 * been on a screen has no cells to be drawn on yet. Three things arrive that
 * way and all three are the same problem.
 *
 * - A layout saved by a build that had docks. The edge it named is remembered
 *   here and honoured once.
 * - The arrangement a fresh install starts with, which has to hug the edges of
 *   a screen nobody has measured yet.
 * - A workspace file somebody wrote by hand, or had a model write, where
 *   `"anchor": "bottom"` is a great deal easier to mean than counting rows.
 *
 * [DockLayout.settled] turns every anchor into cells and throws the anchor
 * away, and it is saved that way. **An anchor is resolved once and is then not
 * a thing any more**, which is the difference between this and a dock: a dock
 * kept hold of its bar for ever, and this lets go the moment it has answered.
 */
sealed class Anchor {

    /** Against a side of the screen, centred along it. */
    data class Edge(val side: Side) : Anchor()

    /**
     * At a fraction of the screen in each axis.
     *
     * What a floating bar's position used to be, and the one form that can
     * survive a screen it was not measured on. Kept for reading old saves; a
     * file written today says cells.
     */
    data class Spot(val x: Float, val y: Float) : Anchor()

    /** The cell this anchor comes to on a [gridW] by [gridH] grid, for a [w] by [h] shape. */
    fun originOn(gridW: Int, gridH: Int, w: Int, h: Int): Cell {
        val maxX = (gridW - w).coerceAtLeast(0)
        val maxY = (gridH - h).coerceAtLeast(0)
        return when (this) {
            is Edge -> when (side) {
                Side.LEFT -> Cell(0, maxY / 2)
                Side.RIGHT -> Cell(maxX, maxY / 2)
                Side.TOP -> Cell(maxX / 2, 0)
                Side.BOTTOM -> Cell(maxX / 2, maxY)
            }

            is Spot -> Cell(
                (x * gridW).toInt().coerceIn(0, maxX),
                (y * gridH).toInt().coerceIn(0, maxY),
            )
        }
    }

    companion object {
        /** An edge by its persisted name, or null. */
        fun edge(id: String): Edge? = Side.byId(id)?.let { Edge(it) }
    }
}

/**
 * One toolbar: the shape it was drawn as, where that shape sits, and what is
 * standing on it.
 *
 * **The region is in screen cells.** That one sentence replaces the dock, the
 * alignment, the mirroring and the floating position that used to be in this
 * file: `region.bounds.x` and `region.bounds.y` are where the surface is, and
 * moving it is [CellRegion.translated]. The renderer offsets by those two
 * numbers and has nothing else to decide.
 *
 * [id] is what everything keys on. It is arbitrary — `s1`, `s2`, … for a
 * surface drawn with the pen — and it is persisted, so it may not be
 * regenerated when a layout is read back.
 *
 * [anchor] is set only on a surface that has never met a screen. See [Anchor].
 */
class Surface(
    val id: String,
    /** Which way it fills. Where the next thing dropped on it lands. */
    val flow: FlowOrder,
    val slots: SurfaceLayout,
    val anchor: Anchor? = null,
) {
    val region: CellRegion get() = slots.region

    /** Where its top-left corner is, in screen cells. */
    val origin: Cell get() = region.bounds.let { Cell(it.x, it.y) }

    /**
     * The one direction this surface runs, for the things that still need one.
     *
     * A strip has an answer and a shape does not. Everything that can ask
     * per-cell should ask [CellRegion.localAxis] instead; this is for the few
     * callers handed one axis for a whole surface.
     */
    val axis: Axis get() = region.stripAxis ?: Axis.HORIZONTAL

    val isEmpty: Boolean get() = slots.isEmpty

    fun with(
        slots: SurfaceLayout = this.slots,
        flow: FlowOrder = this.flow,
        anchor: Anchor? = this.anchor,
    ): Surface = Surface(id, flow, slots, anchor)

    /** The same surface reshaped, dropping whatever no longer fits. */
    fun reshaped(region: CellRegion): Surface = with(slots = slots.reshaped(region))

    /**
     * The same surface, and everything on it, moved by [dx], [dy] cells.
     *
     * Both the shape and the placements move, because a placement's cell is in
     * the same frame the region is: screen cells. Moving one without the other
     * is the bug this method exists so that nobody writes.
     */
    fun translated(dx: Int, dy: Int): Surface {
        if (dx == 0 && dy == 0) return this
        val region = region.translated(dx, dy)
        val moved = slots.placements.map { it.copy(x = it.x + dx, y = it.y + dy) }
        return with(slots = SurfaceLayout.of(region, moved))
    }

    /**
     * A plain bar cut back to its last item, or null when there is no last item.
     *
     * **Only for a layout that came from a build with docks.** A bar was as long
     * as its dock said, whether or not anything stood on the end of it, and the
     * renderer hid the tail by drawing only as far as the last control. Nothing
     * hides a tail any more — a shape is what you drew, all of it — so a
     * twelve-cell bar holding five buttons migrates as seven cells of grey
     * hanging off the bottom. The user never drew those cells; a default did.
     *
     * A shape is left alone, because a shape *was* drawn. An empty bar answers
     * null, because an empty bar was a dock rather than a toolbar.
     */
    fun trimmedToContents(): Surface? {
        if (slots.isEmpty) return null
        val axis = region.stripAxis ?: return this
        val b = region.bounds
        val end = slots.placements.maxOf {
            if (axis == Axis.HORIZONTAL) it.right - b.x else it.bottom - b.y
        }
        if (end >= region.lengthAlong(axis)) return this
        return reshaped(CellRegion.strip(end, axis).translated(b.x, b.y))
    }

    /** The same surface with its top-left corner at [cell]. */
    fun movedTo(cell: Cell): Surface = translated(cell.x - origin.x, cell.y - origin.y)

    /**
     * How many cells [item] would take at [at], the way round it wants to be.
     *
     * It asks the *shape*, which is what makes a slider stand up in an L's arm
     * and lie flat along its foot. See [RegionLayout.naturalSize].
     */
    fun footprintOf(item: ToolItem, at: Cell): Pair<Int, Int> =
        RegionLayout.naturalSize(item, region, at)

    override fun equals(other: Any?): Boolean =
        other is Surface && other.id == id && other.flow == flow &&
            other.slots == slots && other.anchor == anchor

    override fun hashCode(): Int = listOf(id, flow, slots, anchor).hashCode()

    override fun toString(): String = "$id(${anchor?.let { "$it " } ?: ""}$slots)"
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
 * Moving an item from one surface to another is [place] on the second — the
 * removal falls out of the rule, and there is no second path through which a
 * move can go wrong.
 *
 * ## Every surface is one the user made
 *
 * There are no compulsory surfaces. Four of them existed because an enum had
 * four entries; now a surface exists because somebody drew it and stops
 * existing when they rub it out. [addSurface] and [closeSurface] are the whole
 * of it.
 *
 * Immutable, so it can be Compose state and so that every test is one
 * expression.
 */
class DockLayout private constructor(val surfaces: List<Surface>) {

    val isEmpty: Boolean get() = surfaces.all { it.isEmpty }

    fun surface(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** Every placed item, with the surface it is on. */
    fun all(): List<DockedItem> =
        surfaces.flatMap { s -> s.slots.placements.map { DockedItem(s, it) } }

    /** Where [item] currently is, or null if it is on no surface. */
    fun locate(item: ToolItem): DockedItem? = all().firstOrNull { it.item == item }

    /** True if [item] is on some surface. What the chooser ticks. */
    operator fun contains(item: ToolItem): Boolean = locate(item) != null

    /** The surface whose shape covers [cell], or null. Later surfaces win. */
    fun surfaceAt(cell: Cell): Surface? = surfaces.lastOrNull { cell in it.region }

    /** True while some surface has never met a screen. See [settled]. */
    val hasAnchors: Boolean get() = surfaces.any { it.anchor != null }

    /**
     * Every anchored surface put on a [gridW] by [gridH] grid, anchor dropped.
     *
     * The one thing in the system that moves a surface without being asked, and
     * it happens once per surface ever. See [Anchor] for the three ways an
     * anchored surface arrives.
     */
    fun settled(gridW: Int, gridH: Int): DockLayout {
        if (!hasAnchors || gridW < 1 || gridH < 1) return this
        return DockLayout(
            surfaces.map { s ->
                val anchor = s.anchor ?: return@map s
                val b = s.region.bounds
                s.movedTo(anchor.originOn(gridW, gridH, b.w, b.h)).with(anchor = null)
            }
        )
    }

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
        return without(s, item).fits(w, h, cell.x, cell.y, ignoring, item.hangs)
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
     * Give the panel at [cell] of [surfaceId] a size the user dragged out.
     *
     * Only a panel, because only a panel has a size of its own: everything else
     * is as big as the catalogue says and a handle on it would be a handle that
     * does nothing. Clamped rather than refused — a drag that asks for a card of
     * minus three cells is a hand, not an error.
     */
    fun resizePanel(surfaceId: String, cell: Cell, w: Int, h: Int): DockLayout {
        val s = surface(surfaceId) ?: return this
        val panel = s.slots.covering(cell)?.takeIf { it.hangs } ?: return this
        val sized = panel.copy(
            w = w.coerceIn(MIN_PANEL, MAX_PANEL),
            h = h.coerceIn(MIN_PANEL, MAX_PANEL),
        )
        if (sized == panel) return this
        val without = s.slots.remove(panel.cell)
        if (!without.fits(sized)) return this
        return withSurface(s.with(slots = without.place(sized)))
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
            free.fits(w, h, cell.x, cell.y, hangs = item.hangs)
        }
    }

    /** The first cell on any surface that [item] would fit in, with its surface. */
    fun anyFit(item: ToolItem): Pair<String, Cell>? {
        for (s in surfaces) firstFit(s.id, item)?.let { return s.id to it }
        return null
    }

    /**
     * A new surface of this shape, and what it is called.
     *
     * The whole of "draw a toolbar": the shape editor hands over the cells, and
     * the id is generated because the user never names one.
     */
    fun addSurface(region: CellRegion, flow: FlowOrder = FlowOrder.RIGHT_THEN_DOWN):
        Pair<DockLayout, String> {
        val id = nextId()
        return DockLayout(surfaces + Surface(id, flow, SurfaceLayout.empty(region))) to id
    }

    /**
     * A new surface holding [item], with its corner at [at].
     *
     * This is the whole of "fixate". It is sized to the item plus a little room
     * rather than to a constant: a surface is paper the drawing cannot use, so
     * it is as big as it has to be and no bigger, and the spare cells are there
     * so that something else can be dropped in beside it.
     */
    fun addSurface(item: ToolItem, at: Cell): Pair<DockLayout, String> {
        val span = if (item.hangs) 1 else item.cellsWide
        val region = CellRegion.strip(span + SPARE_CELLS, Axis.HORIZONTAL).translated(at.x, at.y)
        val (next, id) = addSurface(region)
        return next.place(id, item, Cell(region.bounds.x, region.bounds.y)) to id
    }

    /**
     * Give a surface a new shape, keeping what still fits.
     *
     * The single entry point for every way of changing one: a shape drawn with
     * the pen, a cell rubbed out, or a screen that turned out to be smaller.
     * What no longer fits is dropped here and is the caller's to overflow — see
     * `docs/ui-expansion-plan.md`, *clamp, overflow, say so*.
     *
     * A reshape to nothing closes the surface, because a toolbar with no cells
     * is not a toolbar you can find again.
     */
    fun reshape(surfaceId: String, region: CellRegion): DockLayout {
        val s = surface(surfaceId) ?: return this
        if (region.isEmpty) return closeSurface(surfaceId)
        return withSurface(s.reshaped(region))
    }

    /** Move a whole surface so its corner is at [cell]. */
    fun moveSurface(surfaceId: String, cell: Cell): DockLayout {
        val s = surface(surfaceId) ?: return this
        return withSurface(s.movedTo(cell))
    }

    /** Change which way a surface fills. Nothing already placed moves. */
    fun reflow(surfaceId: String, flow: FlowOrder): DockLayout {
        val s = surface(surfaceId) ?: return this
        return withSurface(s.with(flow = flow))
    }

    /**
     * Close everything on a surface up, in flow order.
     *
     * This is the whole of *flow when you are shaping, freeze when you are
     * drawing*, and it is one call to [RegionLayout.pack]. It is a menu item
     * and not an ambient behaviour: a toolbar that tidied itself every time
     * something was added would be a toolbar where Export moves, which is the
     * one thing a toolbar must not do.
     *
     * **It never loses a control.** If anything cannot be re-packed — which
     * takes an item that will not fit the way round the new order wants it —
     * nothing moves at all. A tidy that quietly drops the eraser is worse than
     * an untidy bar, and "some of it moved" is the worst answer of the three.
     */
    fun tidy(surfaceId: String): DockLayout {
        val s = surface(surfaceId) ?: return this
        if (s.isEmpty) return this
        val order = s.region.cells(s.flow).toList().withIndex().associate { it.value to it.index }
        val flowing = s.slots.placements
            .sortedBy { order[it.cell] ?: Int.MAX_VALUE }
            .map { RegionLayout.Footprint.of(it.item) }
        val fill = RegionLayout.pack(s.region, s.flow, flowing = flowing)
        if (fill.overflow.isNotEmpty()) return this
        return withSurface(s.with(slots = SurfaceLayout.of(s.region, fill.placements)))
    }

    /** Close a surface, and everything on it. */
    fun closeSurface(surfaceId: String): DockLayout =
        DockLayout(surfaces.filter { it.id != surfaceId })

    /** Nothing on the screen at all. */
    fun cleared(): DockLayout = EMPTY

    /**
     * Surfaces with nothing left on them, dropped.
     *
     * Dragging the last control off one leaves a shape over the drawing that
     * does nothing. It is not deleted as it happens — it has to survive being
     * empty for as long as the drag is being undone, and it has to survive
     * being empty for the whole of the time it is being drawn — so it is tidied
     * when the layout is saved.
     */
    fun tidied(): DockLayout = DockLayout(surfaces.filter { !it.isEmpty })

    /**
     * The same items on surfaces of different shapes.
     *
     * Anything that no longer reaches is dropped, by [SurfaceLayout.of].
     */
    fun reshaped(regionOf: (Surface) -> CellRegion): DockLayout =
        of(surfaces.map { it.reshaped(regionOf(it)) })

    /**
     * The same layout on a grid this size, and what would not fit.
     *
     * **Clamp, overflow, say so** — the rule from `docs/workspace-plan.md`, in
     * that order, and this is the first two thirds of it.
     *
     * **Shift, then cut.** A surface drawn against the right edge of a wide
     * screen is whole on a narrow one, only further in; only what is still off
     * the grid after the shift is lost. That is new, and it is what absolute
     * cells cost and pay for: the old model had an edge to re-hang a bar on and
     * this one has arithmetic instead.
     *
     * **This is for drawing, and it is never saved.** A workspace made on a
     * tablet and opened on a phone must come back whole when it goes home
     * again, so the clamp is applied on the way to the screen and the layout on
     * disk is untouched. That is also why [Fitted.layout] is `this` — the same
     * instance, not an equal one — when nothing had to be cut: a rotation that
     * changes nothing must not recompose the chrome, and a layout that fits
     * both ways round must not move.
     */
    fun fittedTo(gridW: Int, gridH: Int): Fitted {
        if (gridW < 1 || gridH < 1) return Fitted(this, emptyMap())
        var cut = false
        val overflow = LinkedHashMap<String, List<ToolItem>>()
        val out = ArrayList<Surface>(surfaces.size)
        for (surface in surfaces) {
            val b = surface.region.bounds
            val dx = -(b.right - gridW).coerceAtLeast(0).coerceAtMost(b.x)
            val dy = -(b.bottom - gridH).coerceAtLeast(0).coerceAtMost(b.y)
            val shifted = surface.translated(dx, dy)
            val region = shifted.region.clampedTo(gridW, gridH)
            if (region == surface.region) {
                out += surface
                continue
            }
            cut = true
            val fits = shifted.slots.placements.filter {
                region.accepts(it.x, it.y, it.w, it.h, it.hangs)
            }
            val lost = shifted.slots.placements
                .filter { it !in fits }
                .sortedWith(compareBy({ it.y }, { it.x }))
                .map { it.item }
            if (lost.isNotEmpty()) overflow[surface.id] = lost
            out += shifted.with(slots = SurfaceLayout.of(region, fits))
        }
        return Fitted(if (cut) DockLayout(out) else this, overflow)
    }

    /** A layout cut to a screen, and the controls that did not survive the cut. */
    data class Fitted(
        val layout: DockLayout,
        /** By surface id, in flow order. What the overflow chevron lists. */
        val overflow: Map<String, List<ToolItem>>,
    ) {
        val isWhole: Boolean get() = overflow.isEmpty()
    }

    private fun without(surface: Surface, item: ToolItem): SurfaceLayout {
        val here = locate(item) ?: return surface.slots
        return if (here.surface.id == surface.id) surface.slots.remove(here.cell) else surface.slots
    }

    private fun withSurface(surface: Surface): DockLayout =
        DockLayout(surfaces.map { if (it.id == surface.id) surface else it })

    private fun nextId(): String {
        val taken = surfaces.mapNotNull { it.id.removePrefix(ID_PREFIX).toIntOrNull() }.toSet()
        var n = 1
        while (n in taken) n++
        return "$ID_PREFIX$n"
    }

    override fun equals(other: Any?): Boolean = other is DockLayout && other.surfaces == surfaces

    override fun hashCode(): Int = surfaces.hashCode()

    override fun toString(): String = "DockLayout(" + surfaces.joinToString("; ") + ")"

    companion object {
        /** A drawn surface is this and a number. Persisted, so it does not move. */
        const val ID_PREFIX = "s"

        /** Room to drop something else in beside a freshly fixated panel. */
        private const val SPARE_CELLS = 2

        /** A panel smaller than this is not readable; larger than this is not a screen. */
        private const val MIN_PANEL = 2
        private const val MAX_PANEL = 24

        /**
         * Build a layout from whatever surfaces are supplied.
         *
         * Two normalisations, and together they are why a codec can be careless
         * with its input: a surface repeating an id already seen is dropped, and
         * an item that appears twice keeps only its first appearance. Neither
         * raises.
         *
         * **Overlapping shapes are not one of them.** Two surfaces claiming the
         * same cell is refused where it can be refused for free — the pen will
         * not paint over a cell another surface owns — and tolerated here,
         * because a file is not a gesture and quietly deleting half of somebody's
         * toolbar because two rectangles touch is a worse answer than drawing
         * them both. The later one is on top.
         */
        fun of(surfaces: List<Surface>): DockLayout {
            val seen = HashSet<ToolItem>()
            val ids = HashSet<String>()
            val out = ArrayList<Surface>(surfaces.size)
            for (surface in surfaces) {
                if (!ids.add(surface.id)) continue
                val kept = surface.slots.placements.filter { seen.add(it.item) }
                out += if (kept.size == surface.slots.placements.size) {
                    surface
                } else {
                    surface.with(slots = SurfaceLayout.of(surface.region, kept))
                }
            }
            return DockLayout(out)
        }

        /** Nothing at all. A blank screen, and a grid under it while arranging. */
        val EMPTY: DockLayout = DockLayout(emptyList())

        /**
         * A fresh install, and it is **not** empty.
         *
         * A screen with nothing on it but a drawing is what *Clean* is for, and
         * it is the wrong first impression: a first run that shows a white page
         * and no controls looks broken rather than inviting.
         *
         * So the default is a working set, arranged by what the control is for,
         * and it is **anchored rather than positioned** — see [Anchor] — because
         * the screen it will hug has not been measured yet.
         *
         * - **Left — what is in your hand.** Pen, pencil, marker, eraser,
         *   with an empty cell separating the four tools from the colour and
         *   the marquee, so the six do not read as one run.
         * - **Top — what you did, and what leaves the app.** Undo and redo, then
         *   import, export and the instruments, with a gap between the two ideas.
         * - **Right — where you are looking, and what you are looking at.**
         *   Zoom, fit, layers and the selection, deliberately opposite the
         *   tools: changing the view is not changing the mark, and putting them
         *   on the same side is how you zoom when you meant to erase.
         * - **Bottom — how the mark comes out.** The four sliders, lying flat,
         *   where a slider has room to be a slider. It is sixteen cells long,
         *   which is longer than a small phone, and the last slider goes to the
         *   overflow chevron there rather than being left out everywhere.
         *
         * It holds **every control [DockStore] would introduce**, so a fresh
         * install is exactly this and the separators are not quietly filled in
         * by the mechanism that exists for upgrades.
         */
        val STARTER: DockLayout
            get() = of(
                listOf(
                    anchored(
                        "s1", Side.LEFT, 1, 7,
                        ToolItem.PEN to Cell(0, 0),
                        ToolItem.PENCIL to Cell(0, 1),
                        ToolItem.MARKER to Cell(0, 2),
                        ToolItem.ERASER to Cell(0, 3),
                        ToolItem.COLOUR to Cell(0, 5),
                        ToolItem.MARQUEE to Cell(0, 6),
                    ),
                    anchored(
                        "s2", Side.TOP, 6, 1,
                        ToolItem.UNDO to Cell(0, 0),
                        ToolItem.REDO to Cell(1, 0),
                        ToolItem.IMPORT to Cell(3, 0),
                        ToolItem.EXPORT to Cell(4, 0),
                        ToolItem.STATS to Cell(5, 0),
                    ),
                    anchored(
                        "s3", Side.RIGHT, 1, 5,
                        ToolItem.ZOOM_IN to Cell(0, 0),
                        ToolItem.ZOOM_OUT to Cell(0, 1),
                        ToolItem.FIT to Cell(0, 2),
                        ToolItem.LAYERS to Cell(0, 3),
                        ToolItem.SELECTION to Cell(0, 4),
                    ),
                    anchored(
                        "s4", Side.BOTTOM, 16, 1,
                        ToolItem.SIZE to Cell(0, 0),
                        ToolItem.SMOOTHING to Cell(4, 0),
                        ToolItem.GRAIN to Cell(8, 0),
                        ToolItem.ERASER_SIZE to Cell(12, 0),
                    ),
                ),
            )

        /** What a fresh install gets. See [STARTER] for why it is not [EMPTY]. */
        val DEFAULT: DockLayout get() = STARTER

        /**
         * A strip of [w] by [h] cells at the origin, waiting for a screen.
         *
         * The one convenience for writing a default by hand: the cells are
         * relative to the shape's own corner, exactly as they would be in a
         * workspace file, and [settled] puts the corner somewhere real.
         */
        fun anchored(
            id: String,
            side: Side,
            w: Int,
            h: Int,
            vararg at: Pair<ToolItem, Cell>,
        ): Surface {
            val region = CellRegion.block(w, h)
            val flow = FlowOrder.along(if (w >= h) Axis.HORIZONTAL else Axis.VERTICAL)
            return Surface(
                id = id,
                flow = flow,
                slots = SurfaceLayout.of(
                    region,
                    at.map { (item, cell) ->
                        val (iw, ih) = RegionLayout.naturalSize(item, region, cell)
                        CellPlacement(item, cell.x, cell.y, iw, ih)
                    },
                ),
                anchor = Anchor.Edge(side),
            )
        }
    }
}
