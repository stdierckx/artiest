package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset

/**
 * Which way a bar's slots run.
 *
 * This is the one thing [ToolbarLayout] never has to know, and it is the whole
 * of what a dock adds to it: a bar is a line of slots, and a dock is a line of
 * slots with a direction. Since panels arrived it is also what decides which of
 * an item's two dimensions is spent on slots — see [ToolItem.slotsIn].
 */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Where a bar is attached.
 *
 * **This is an attachment, not an identity.** It used to be both: there were
 * five docks and five bars and the enum was the key. There can now be any number
 * of floating bars — the user makes them and closes them — so a bar carries an
 * id of its own and this says only where it sits.
 *
 * [id] is persisted for the four edges, and is deliberately not [name]: renaming
 * a Kotlin constant is a refactor, renaming a persisted key silently empties
 * somebody's toolbar.
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
     * A floating bar sits over the drawing, so every slot it has is paper it is
     * covering. They are made to size rather than to a constant — see
     * [DockLayout.addFloating] — and [defaultSlots] is only the fallback.
     */
    FLOATING("float", "Floating", Axis.HORIZONTAL, 8),
    ;

    val isEdge: Boolean get() = this != FLOATING

    companion object {
        val EDGES: List<Dock> = entries.filter { it.isEdge }

        private val BY_ID: Map<String, Dock> = entries.associateBy { it.id }

        fun byId(id: String): Dock? = BY_ID[id]
    }
}

/**
 * Where a floating bar sits, as a fraction of the window in each axis.
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
 * One toolbar: where it is, how long it is, and what is on it.
 *
 * [id] is `left`, `top`, `right` or `bottom` for the four edges, and `f1`, `f2`
 * … for floating bars. It is what everything else keys on, because a floating
 * bar has no other name and the edges may as well be named the same way.
 *
 * [spot] is set for floating bars and null for edges, which is the type saying
 * out loud that an edge's position is not the user's to choose.
 */
class Bar(
    val id: String,
    val dock: Dock,
    val spot: BarSpot?,
    val slots: ToolbarLayout,
) {
    val axis: Axis get() = dock.axis
    val isEmpty: Boolean get() = slots.isEmpty
    val isFloating: Boolean get() = dock == Dock.FLOATING

    fun with(slots: ToolbarLayout = this.slots, spot: BarSpot? = this.spot): Bar =
        Bar(id, dock, spot, slots)

    /** The slots [item] would take on this bar. The second dimension, applied. */
    fun spanOf(item: ToolItem): Int = item.slotsIn(axis)

    /** How far [item] would stick out of this bar, in cells. */
    fun depthOf(item: ToolItem): Int = item.depthIn(axis)

    /** How thick this bar is across its axis, in cells. One, or its deepest item. */
    val depthCells: Int get() = slots.placements.maxOfOrNull { it.depth } ?: 1

    override fun equals(other: Any?): Boolean =
        other is Bar && other.id == id && other.dock == dock &&
            other.spot == spot && other.slots == slots

    override fun hashCode(): Int = listOf(id, dock, spot, slots).hashCode()

    override fun toString(): String = "$id($dock${spot?.let { "@$it" } ?: ""}, $slots)"
}

/** A placement and the bar it is on. The answer [DockLayout.locate] gives. */
data class DockedItem(val bar: Bar, val placement: Placement) {
    val item: ToolItem get() = placement.item
    val slot: Int get() = placement.slot
}

/**
 * Every bar, and the rules that tie them together.
 *
 * ## What this is, and what it deliberately is not
 *
 * A list of [Bar]s with a handful of operations on top. It is **not** a layout
 * engine: [fits] and [place] delegate straight to the bar in question, so every
 * edge case about spans, collisions and the end of a bar is answered by
 * [ToolbarLayout], and by the tests that already cover it. What this adds is
 * which bar, and the arithmetic that turns an item into a span.
 *
 * ## The one rule a single bar did not need
 *
 * **An item appears in at most one place.** [ToolbarLayout] allows the same item
 * twice, and on one bar that is merely odd. Across several it is a bug with a
 * face: two Eraser buttons in different corners, one lit and one not, both real.
 * So [place] removes the item from wherever else it was, and [of] drops the
 * later of two copies.
 *
 * That rule is also what makes dragging work without any code for dragging.
 * Moving an item from the left edge to a floating panel is [place] on the
 * floating bar — the removal falls out of the rule, and there is no second path
 * through which a move can go wrong.
 *
 * ## Bars come and go
 *
 * The four edges always exist, in [Dock.EDGES] order, and can only be emptied.
 * Floating bars are made by [addFloating] and destroyed by [closeBar], which is
 * how "fixate" and "close" are ordinary operations rather than new ideas.
 *
 * Immutable, so it can be Compose state and so that every test is one
 * expression.
 */
class DockLayout private constructor(val bars: List<Bar>) {

    val isEmpty: Boolean get() = bars.all { it.isEmpty }

    /** The four edges, always present, always in the same order. */
    val edges: List<Bar> get() = bars.filter { !it.isFloating }

    /** The bars the user made, oldest first. */
    val floating: List<Bar> get() = bars.filter { it.isFloating }

    fun bar(id: String): Bar? = bars.firstOrNull { it.id == id }

    /** The bar on [dock]. Only meaningful for the four edges. */
    fun edge(dock: Dock): Bar = bars.first { it.dock == dock && !it.isFloating }

    /** Every placed item, with the bar it is on. */
    fun all(): List<DockedItem> = bars.flatMap { b -> b.slots.placements.map { DockedItem(b, it) } }

    /** Where [item] currently is, or null if it is on no bar. */
    fun locate(item: ToolItem): DockedItem? = all().firstOrNull { it.item == item }

    /** True if [item] is on some bar. What the chooser ticks. */
    operator fun contains(item: ToolItem): Boolean = locate(item) != null

    /**
     * Would [item] go at [slot] on the bar [barId]?
     *
     * The item is taken out of that bar first when it is already on it, because
     * [place] moves rather than copies: by the time it lands, the slots it used
     * to hold are free, so a `fits` that still sees it there refuses placements
     * that would in fact succeed. Dragging a four-slot slider two slots along
     * its own bar is exactly that case, and it is the one a hand tries first.
     */
    fun fits(barId: String, item: ToolItem, slot: Int, ignoringSlot: Int? = null): Boolean {
        val bar = bar(barId) ?: return false
        return without(bar, item).fits(bar.spanOf(item), slot, ignoringSlot = ignoringSlot)
    }

    /**
     * Put [item] at [slot] on [barId], taking it out of wherever it was.
     *
     * Throws if it does not fit, exactly as [ToolbarLayout.place] does and for
     * the same reason: a control that silently declines to appear cannot be told
     * apart from one that is broken.
     */
    fun place(barId: String, item: ToolItem, slot: Int): DockLayout {
        val target = bar(barId) ?: return this
        val was = locate(item)
        val cleared = was
            ?.let { withBar(it.bar.with(slots = it.bar.slots.remove(it.slot))) }
            ?: this
        val fresh = cleared.bar(barId) ?: target
        // A size the user chose follows the control, so long as the bar it is
        // going to runs the same way. Across the turn there is nothing sensible
        // to carry -- a panel six wide and eleven tall does not become eleven
        // wide by being moved -- so it goes back to what the catalogue says.
        val kept = was?.placement?.takeIf { was.bar.axis == fresh.axis }
        return cleared.withBar(
            fresh.with(
                slots = fresh.slots.place(
                    Placement(
                        item = item,
                        slot = slot,
                        span = kept?.span ?: fresh.spanOf(item),
                        depth = kept?.depth ?: fresh.depthOf(item),
                    )
                )
            )
        )
    }

    /**
     * Resize a floating bar, and the panel on it if there is exactly one.
     *
     * A bar of buttons only has a length; a bar holding a panel is that panel's
     * window, so the two numbers are the panel's. Both are the user's choice and
     * both are clamped rather than refused — a drag that asks for a bar of
     * minus three slots is a hand, not an error.
     */
    fun resizeFloating(barId: String, along: Int, across: Int): DockLayout {
        val bar = bar(barId) ?: return this
        if (!bar.isFloating) return this
        val cells = along.coerceIn(MIN_CELLS, MAX_CELLS)
        val deep = across.coerceIn(MIN_CELLS, MAX_CELLS)
        val only = bar.slots.placements.singleOrNull()?.takeIf { it.item.kind == ToolKind.PANEL }
            ?: return withBar(bar.with(slots = bar.slots.resized(cells)))
        return withBar(
            bar.with(
                slots = ToolbarLayout.of(
                    cells,
                    listOf(only.copy(slot = 0, span = cells, depth = deep)),
                )
            )
        )
    }

    /**
     * Empty a floating bar into another, and close it.
     *
     * What dragging a floating bar onto an edge does. Everything on it moves in
     * order, each to the first slot that will take it; if any of them will not
     * fit, nothing moves and the answer is null — half a bar arriving is worse
     * than none, because the half left behind is on a bar that is about to be
     * closed.
     */
    fun dockInto(from: String, to: String): DockLayout? {
        val source = bar(from) ?: return null
        if (!source.isFloating || from == to) return null
        val target = bar(to) ?: return null
        var next = this
        for (placement in source.slots.placements) {
            val slot = next.firstFit(target.id, placement.item) ?: return null
            next = next.place(target.id, placement.item, slot)
        }
        return next.closeBar(from)
    }

    /** Empty the slot [slot] falls in on [barId]. A no-op on an empty slot. */
    fun remove(barId: String, slot: Int): DockLayout {
        val bar = bar(barId) ?: return this
        return withBar(bar.with(slots = bar.slots.remove(slot)))
    }

    /**
     * Send whatever is in [slot] of [from] to [to], at the first slot it fits.
     *
     * Returns null when the destination has no room, which is the honest answer
     * and the one the caller can act on — the drop is refused and the item stays
     * where the user could still see it. Throwing here would be wrong: unlike
     * [place], this is reached by a gesture, and a gesture that lands somewhere
     * full is a normal thing for a hand to do.
     */
    fun move(from: String, slot: Int, to: String, toSlot: Int? = null): DockLayout? {
        val source = bar(from) ?: return null
        val moving = source.slots.covering(slot) ?: return null
        val target = toSlot?.takeIf { fits(to, moving.item, it) }
            ?: firstFit(to, moving.item)
            ?: return null
        return place(to, moving.item, target)
    }

    /** The first slot on [barId] that [item] would fit in, or null. */
    fun firstFit(barId: String, item: ToolItem): Int? {
        val bar = bar(barId) ?: return null
        return without(bar, item).firstFit(bar.spanOf(item))
    }

    /**
     * Make a floating bar holding [item], at [spot], and say what it is called.
     *
     * This is the whole of "fixate". The bar is sized to the item plus a little
     * room, rather than to a constant: a floating bar is paper the drawing
     * cannot use, so it is as long as it has to be and no longer, and the spare
     * slots are there so that something else can be dropped in beside it.
     */
    fun addFloating(item: ToolItem, spot: BarSpot): Pair<DockLayout, String> {
        val id = nextFloatingId()
        val span = item.slotsIn(Dock.FLOATING.axis)
        val bar = Bar(id, Dock.FLOATING, spot, ToolbarLayout.empty(span + SPARE_SLOTS))
        return DockLayout(bars + bar).place(id, item, 0) to id
    }

    /** Move a floating bar. A no-op on an edge, whose position is not the user's. */
    fun moveBar(barId: String, spot: BarSpot): DockLayout {
        val bar = bar(barId) ?: return this
        if (!bar.isFloating) return this
        return withBar(bar.with(spot = spot))
    }

    /**
     * Close a floating bar, and everything on it.
     *
     * An edge cannot be closed — there is nowhere for it to go and no way to get
     * it back — so this empties it instead. A floating bar is a thing the user
     * made, so closing it is closing it.
     */
    fun closeBar(barId: String): DockLayout {
        val bar = bar(barId) ?: return this
        return if (bar.isFloating) {
            DockLayout(bars.filter { it.id != barId })
        } else {
            withBar(bar.with(slots = bar.slots.cleared()))
        }
    }

    /** Every bar emptied, and every floating bar gone. */
    fun cleared(): DockLayout = of(edges.map { it.with(slots = it.slots.cleared()) })

    /**
     * Floating bars with nothing left on them, dropped.
     *
     * Dragging the last control off a floating bar leaves a strip of empty slots
     * over the drawing that does nothing. It is not deleted as it happens — the
     * bar has to survive being empty for as long as the drag is being undone —
     * so it is tidied when the layout is saved.
     */
    fun tidied(): DockLayout = of(bars.filter { !it.isFloating || !it.isEmpty })

    /**
     * The same items on bars of different lengths.
     *
     * Used by the store, which widens a saved layout to the current defaults so
     * that a release adding a control does not strand a user whose bars are
     * full. Anything that no longer reaches is dropped, by [ToolbarLayout.of].
     */
    fun resized(slotsOf: (Bar) -> Int): DockLayout =
        of(bars.map { it.with(slots = it.slots.resized(slotsOf(it))) })

    private fun without(bar: Bar, item: ToolItem): ToolbarLayout {
        val here = locate(item) ?: return bar.slots
        return if (here.bar.id == bar.id) bar.slots.remove(here.slot) else bar.slots
    }

    private fun withBar(bar: Bar): DockLayout =
        DockLayout(bars.map { if (it.id == bar.id) bar else it })

    private fun nextFloatingId(): String {
        val taken = floating.mapNotNull { it.id.removePrefix(FLOAT_PREFIX).toIntOrNull() }.toSet()
        var n = 1
        while (n in taken) n++
        return "$FLOAT_PREFIX$n"
    }

    override fun equals(other: Any?): Boolean = other is DockLayout && other.bars == bars

    override fun hashCode(): Int = bars.hashCode()

    override fun toString(): String = "DockLayout(" + bars.joinToString("; ") + ")"

    companion object {
        /** Floating bar ids are this and a number. Persisted, so it does not move. */
        const val FLOAT_PREFIX = "f"

        /** Room to drop something else in beside a freshly fixated panel. */
        private const val SPARE_SLOTS = 2

        /** A bar smaller than this is not a bar; larger than this is not a screen. */
        private const val MIN_CELLS = 2
        private const val MAX_CELLS = 24

        /**
         * Build a layout from whatever bars are supplied, filling in the rest.
         *
         * Three normalisations, and together they are why a codec can be
         * careless with its input: a missing edge becomes an empty bar of that
         * edge's default length, a floating bar without a position gets one, and
         * an item that appears twice keeps only its first appearance. None of
         * them raises.
         */
        fun of(bars: List<Bar>): DockLayout {
            val seen = HashSet<ToolItem>()
            val out = ArrayList<Bar>(bars.size + Dock.EDGES.size)

            fun keep(bar: Bar) {
                val kept = bar.slots.placements.filter { seen.add(it.item) }
                out += if (kept.size == bar.slots.placements.size) {
                    bar
                } else {
                    bar.with(slots = ToolbarLayout.of(bar.slots.slotCount, kept))
                }
            }

            for (dock in Dock.EDGES) {
                keep(
                    bars.firstOrNull { it.dock == dock && !it.isFloating }
                        ?: Bar(dock.id, dock, null, ToolbarLayout.empty(dock.defaultSlots))
                )
            }
            val ids = HashSet<String>(out.map { it.id })
            for (bar in bars.filter { it.isFloating }) {
                if (!ids.add(bar.id)) continue
                keep(bar.with(spot = bar.spot ?: BarSpot(DEFAULT_X, DEFAULT_Y)))
            }
            return DockLayout(out)
        }

        /** Just the four empty edges. */
        val EMPTY: DockLayout get() = of(emptyList())

        /**
         * A fresh install, and it is **not** empty.
         *
         * `ToolbarLayout` used to default to empty, and on one bar that was
         * defensible: a single row of dashed slots reads as *tap me*. Four empty
         * edges is four times that bet and it is not one worth taking — a first
         * run that shows empty frames around a white page looks broken rather
         * than inviting.
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
         * - **No floating bars**, because a floating bar is something the user
         *   made and there is no honest guess at one.
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

        private fun edgeOf(dock: Dock, vararg at: Pair<ToolItem, Int>): Bar =
            Bar(
                dock.id, dock, null,
                ToolbarLayout.of(
                    dock.defaultSlots,
                    at.map { (item, slot) ->
                        Placement(item, slot, item.slotsIn(dock.axis), item.depthIn(dock.axis))
                    },
                ),
            )

        /** Clear of the left tools and above the bottom sliders, on a first run. */
        private const val DEFAULT_X = 0.32f
        private const val DEFAULT_Y = 0.42f
    }
}
