package be.thalos.artiest.ui

/**
 * Which way a dock's slots run.
 *
 * This is the one thing [ToolbarLayout] never had to know, and it is the whole
 * of what a dock adds to it: a bar is a line of slots, and a dock is a line of
 * slots with a direction. Everything else about placement — what fits, what
 * collides, what a saved string means — is unchanged, which is why the docking
 * feature could be built without touching the algebra underneath it.
 */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Where a toolbar can attach.
 *
 * Four edges and a floating panel, which is the set the user asked for. Each
 * one carries its own slot count rather than sharing a constant, because a
 * screen is not square: a bar across the bottom of a tablet in landscape has
 * room for twenty-four 44dp slots and a column up the side has room for about
 * half that, and pretending otherwise would put items past the end of the
 * screen where nothing can reach them.
 *
 * **[defaultSlots] is deliberately longer than anything ships filled**, and that
 * headroom is load-bearing rather than tidy. `DockStore` only ever widens a
 * saved layout, never shortens it, so this number is how an existing user gets
 * room for a control that did not exist when they arranged their bars. It is not
 * hypothetical: it is what happened the day Undo and Redo were added to a saved
 * bar with sixteen slots and sixteen in use, and again at W10, when two brush
 * presets wanted four slots that twenty did not have. Raising a number here is
 * the whole of the fix, both times.
 *
 * [id] is persisted, and is deliberately not [name], for the reason
 * [ToolItem.id] is not [ToolItem.name]: renaming a Kotlin constant is a
 * refactor, renaming a persisted key silently empties somebody's toolbar.
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
     * The panel that is not attached to anything.
     *
     * Short on purpose. A floating bar sits over the drawing, so every slot it
     * has is paper it is covering, and eight is about as long as one can be
     * before it stops being a panel and becomes a second toolbar in the way.
     */
    FLOATING("float", "Floating", Axis.HORIZONTAL, 8),
    ;

    val isEdge: Boolean get() = this != FLOATING

    companion object {
        private val BY_ID: Map<String, Dock> = entries.associateBy { it.id }

        fun byId(id: String): Dock? = BY_ID[id]
    }
}

/**
 * Every dock's bar, and the rule that ties them together.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a `Map<Dock, ToolbarLayout>` with three operations on top. It is **not**
 * a new layout engine: [fits] and [place] delegate straight to the bar for the
 * dock in question, so every edge case about widths, collisions and the end of
 * the bar is answered by the code that already answers it, and by the tests
 * that already test it. A dock is one more axis on a structure that had none.
 *
 * ## The one rule a single bar did not need
 *
 * **An item appears in at most one place.** [ToolbarLayout] allows the same
 * item twice, and on a single bar that is merely odd. Across five docks it is a
 * bug with a face: two Eraser buttons in different corners, one of them lit and
 * one of them not, both of them real. So [place] removes the item from wherever
 * else it was, and [of] drops the later of two copies.
 *
 * That rule is also what makes dragging work without any code for dragging.
 * Moving an item from the left edge to the bottom is [place] on the bottom —
 * the removal from the left falls out of the rule, and there is no second path
 * through which a move can go wrong.
 *
 * Immutable, like the bar it is built from, so it can be Compose state and so
 * that every test is one expression.
 */
class DockLayout private constructor(private val bars: Map<Dock, ToolbarLayout>) {

    /** The bar at [dock]. Never null: an unmentioned dock is an empty one. */
    fun bar(dock: Dock): ToolbarLayout = bars.getValue(dock)

    val isEmpty: Boolean get() = bars.values.all { it.isEmpty }

    /** Every placed item, with the dock it sits in. Sorted by dock, then slot. */
    fun all(): List<DockedItem> = Dock.entries.flatMap { dock ->
        bar(dock).placements.map { DockedItem(dock, it) }
    }

    /** Where [item] currently is, or null if it is on no bar. */
    fun locate(item: ToolItem): DockedItem? = all().firstOrNull { it.placement.item == item }

    /** True if [item] is on some bar. What the chooser ticks. */
    operator fun contains(item: ToolItem): Boolean = locate(item) != null

    /**
     * Would [item] go at [slot] in [dock]?
     *
     * The `ignoringSlot` of [ToolbarLayout.fits] is passed through unchanged,
     * and there is a second exemption here that a single bar could not have:
     * an item already in *this* dock does not collide with itself when it is
     * being moved a few slots along. Without that, dragging Undo two slots to
     * the right would be refused by the copy of Undo that the drag is about to
     * remove.
     */
    fun fits(dock: Dock, item: ToolItem, slot: Int, ignoringSlot: Int? = null): Boolean =
        withoutItemIn(dock, item).fits(item, slot, ignoringSlot = ignoringSlot)

    /**
     * Put [item] at [slot] in [dock], taking it out of wherever it was.
     *
     * Throws if it does not fit, exactly as [ToolbarLayout.place] does and for
     * the same reason: a control that silently declines to appear cannot be
     * told apart from one that is broken.
     */
    fun place(dock: Dock, item: ToolItem, slot: Int): DockLayout {
        val cleared = locate(item)?.let { withBar(it.dock, bar(it.dock).remove(it.placement.slot)) }
            ?: this
        return cleared.withBar(dock, cleared.bar(dock).place(item, slot))
    }

    /** Empty the slot [slot] falls in, in [dock]. A no-op on an empty slot. */
    fun remove(dock: Dock, slot: Int): DockLayout = withBar(dock, bar(dock).remove(slot))

    /**
     * Send whatever is in [slot] of [from] to [to], at the first slot it fits.
     *
     * Returns null when the destination has no room, which is the honest answer
     * and the one the caller can act on — the drop is refused and the item stays
     * where the user could still see it. Throwing here would be wrong: unlike
     * [place], this is reached by a gesture, and a gesture that lands somewhere
     * full is a normal thing for a hand to do.
     */
    fun move(from: Dock, slot: Int, to: Dock, toSlot: Int? = null): DockLayout? {
        val moving = bar(from).covering(slot) ?: return null
        val target = toSlot?.takeIf { fits(to, moving.item, it) }
            ?: firstFit(to, moving.item)
            ?: return null
        return place(to, moving.item, target)
    }

    /** The first slot in [dock] that [item] would fit in, or null. */
    fun firstFit(dock: Dock, item: ToolItem): Int? = withoutItemIn(dock, item).firstFit(item)

    /**
     * [dock]'s bar with [item] taken out of it, if it was in it.
     *
     * Every question about whether an item can go somewhere is asked of this
     * rather than of the bar itself, because [place] moves rather than copies:
     * by the time the item lands, the slots it used to hold are free, so a
     * `fits` that still sees it there refuses placements that would in fact
     * succeed. Dragging a four-slot slider two slots along its own dock is
     * exactly that case, and it is the one a hand tries first.
     */
    private fun withoutItemIn(dock: Dock, item: ToolItem): ToolbarLayout {
        val here = locate(item) ?: return bar(dock)
        return if (here.dock == dock) bar(dock).remove(here.slot) else bar(dock)
    }

    /** Every dock emptied. What a reset that keeps the slot counts looks like. */
    fun cleared(): DockLayout = of(bars.mapValues { (_, bar) -> bar.cleared() })

    /**
     * The same items in docks of different lengths.
     *
     * Used by the store, which widens a saved layout to the current defaults so
     * that a release adding a control does not strand a user whose bars are
     * full. Anything that no longer reaches is dropped, by [ToolbarLayout.of].
     */
    fun resized(slotsOf: (Dock) -> Int): DockLayout =
        of(bars.mapValues { (dock, bar) -> bar.resized(slotsOf(dock)) })

    private fun withBar(dock: Dock, bar: ToolbarLayout): DockLayout =
        DockLayout(bars + (dock to bar))

    override fun equals(other: Any?): Boolean = other is DockLayout && other.bars == bars

    override fun hashCode(): Int = bars.hashCode()

    override fun toString(): String =
        "DockLayout(" + Dock.entries.joinToString("; ") { "${it.id}:${bar(it)}" } + ")"

    companion object {

        /**
         * Build a layout from whatever bars are supplied, filling in the rest.
         *
         * Two normalisations, both of them the reason a codec can be careless
         * with its input: a dock nobody mentioned becomes an empty bar of that
         * dock's default length, and an item that appears twice keeps only its
         * first appearance in dock declaration order. Neither raises.
         */
        fun of(bars: Map<Dock, ToolbarLayout>): DockLayout {
            val seen = HashSet<ToolItem>()
            val filled = LinkedHashMap<Dock, ToolbarLayout>(Dock.entries.size)
            for (dock in Dock.entries) {
                val bar = bars[dock] ?: ToolbarLayout.of(dock.defaultSlots, emptyList())
                val kept = bar.placements.filter { seen.add(it.item) }
                filled[dock] = if (kept.size == bar.placements.size) {
                    bar
                } else {
                    ToolbarLayout.of(bar.slotCount, kept)
                }
            }
            return DockLayout(filled)
        }

        /** Every dock empty, at its default length. */
        val EMPTY: DockLayout get() = of(emptyMap())

        /**
         * A fresh install, and it is **not** empty.
         *
         * `ToolbarLayout.DEFAULT` is empty, and on one bar that was defensible:
         * a single row of dashed slots reads as *tap me*, and the user's own
         * description of the feature was "we have a empty toolbar with slots".
         * Five empty docks is five times that bet and it is not a bet worth
         * taking — a first run that shows four empty frames around a white page
         * looks broken rather than inviting.
         *
         * So the default is a working set, arranged by what the control is for,
         * because that is what was asked for in as many words. The grouping is
         * the whole point and is worth stating:
         *
         * - **Left edge — what is in your hand.** Pen, pencil, eraser, colour.
         *   The most-used controls, under the non-drawing hand, and the two
         *   halves separated by an empty slot so the tool group and the colour
         *   do not read as one run of four.
         * - **Top edge — what you did, and what leaves the app.** Undo, redo,
         *   then export and the instruments, with a gap between the two ideas.
         * - **Right edge — where you are looking.** Zoom and fit. Deliberately
         *   opposite the tools: changing the view is not changing the mark, and
         *   putting them on the same edge is how you zoom when you meant to
         *   erase.
         * - **Bottom edge — how the mark comes out.** Size, stabilisation,
         *   grain. The sliders, along the long axis, where a slider has room to
         *   be a slider.
         * - **Floating — empty**, because it is the one dock whose position is
         *   the user's own and there is no sensible guess at it.
         */
        val STARTER: DockLayout
            get() = of(
                mapOf(
                    Dock.LEFT to ToolbarLayout.of(
                        Dock.LEFT.defaultSlots,
                        listOf(
                            Placement(ToolItem.PEN, 0),
                            Placement(ToolItem.PENCIL, 1),
                            Placement(ToolItem.MARKER, 2),
                            Placement(ToolItem.ERASER, 3),
                            Placement(ToolItem.COLOUR, 5),
                        ),
                    ),
                    Dock.TOP to ToolbarLayout.of(
                        Dock.TOP.defaultSlots,
                        listOf(
                            Placement(ToolItem.UNDO, 0),
                            Placement(ToolItem.REDO, 1),
                            Placement(ToolItem.IMPORT, 3),
                            Placement(ToolItem.EXPORT, 4),
                            Placement(ToolItem.STATS, 5),
                        ),
                    ),
                    Dock.RIGHT to ToolbarLayout.of(
                        Dock.RIGHT.defaultSlots,
                        listOf(
                            Placement(ToolItem.ZOOM_IN, 0),
                            Placement(ToolItem.ZOOM_OUT, 1),
                            Placement(ToolItem.FIT, 2),
                            Placement(ToolItem.LAYERS, 4),
                        ),
                    ),
                    Dock.BOTTOM to ToolbarLayout.of(
                        Dock.BOTTOM.defaultSlots,
                        listOf(
                            Placement(ToolItem.SIZE, 0),
                            Placement(ToolItem.SMOOTHING, 4),
                            Placement(ToolItem.GRAIN, 8),
                            Placement(ToolItem.ERASER_SIZE, 12),
                        ),
                    ),
                ),
            )

        /** What a fresh install gets. See [STARTER] for why it is not [EMPTY]. */
        val DEFAULT: DockLayout get() = STARTER
    }
}

/** A placement and the dock it is in. The answer [DockLayout.locate] gives. */
data class DockedItem(val dock: Dock, val placement: Placement) {
    val item: ToolItem get() = placement.item
    val slot: Int get() = placement.slot
}
