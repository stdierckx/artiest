package be.thalos.artiest.ui

/**
 * One item, the slot its leading edge sits in, and how many slots it takes.
 *
 * **[span] is carried rather than looked up, and that is the point.** It used to
 * be `item.slots`, which meant this file — and every question about whether
 * something fits — had to know what a [ToolItem] is. It no longer does. A span
 * is computed by whoever knows which way the bar runs (see [DockLayout]) and
 * handed in, and from here down a button, a slider and a whole colour panel are
 * the same thing: an interval.
 *
 * Only the leading edge is stored; the covered range is derived, which is why
 * there is no way to represent a placement whose two ends disagree.
 */
data class Placement(
    val item: ToolItem,
    val slot: Int,
    val span: Int,
    /**
     * How far this sticks out across the bar, in cells.
     *
     * One for everything that lives inside a bar, and the reason it is carried
     * rather than asked of the item is the same reason [span] is: a panel can be
     * resized, and once the user has chosen a size it is a property of this
     * placement and not of the catalogue.
     */
    val depth: Int = 1,
) {
    init {
        require(span >= 1) { "${item.id} spans $span slots" }
        require(depth >= 1) { "${item.id} is $depth cells deep" }
    }

    /** Exclusive. */
    val endSlot: Int get() = slot + span

    fun covers(s: Int): Boolean = s in slot until endSlot
}

/**
 * A toolbar as data: a fixed row of slots, some of them filled.
 *
 * **Why fixed slots rather than a row that grows.** A bar that reflows when you
 * add something is a bar where Export is in a different place every week, and
 * the single thing a toolbar is *for* is that your hand knows where the button
 * is without your eye going there. Fixed slots cost an empty-slot affordance and
 * an occasional "that does not fit"; a reflowing bar costs the muscle memory,
 * which is the whole asset. If the bar is longer than the screen it scrolls —
 * position stays absolute, only the viewport moves.
 *
 * **Why the chooser disables rather than the layout rearranges.** Ask to put a
 * six-slot panel into a two-slot gap and there are three possible kindnesses:
 * refuse, shove the neighbours along, or drop it somewhere else that fits. The
 * last two both move an item the user did not touch. So [fits] is a public
 * query, the chooser greys out what would not go, and [place] treats a bad
 * placement as a programming error and throws — because a control that silently
 * declines to appear is indistinguishable from one that is broken.
 *
 * **It knows nothing about tools, docks or directions.** It allocates intervals.
 * That is what lets the same twenty-odd tests cover a row of buttons and a
 * floating panel without knowing which is which, and it is why adding a second
 * dimension to the UI did not add a second layout engine — see
 * `docs/panels-plan.md`.
 *
 * The type is immutable. Every operation returns a new layout, which is what
 * makes it usable as Compose state and trivial to test.
 */
class ToolbarLayout private constructor(
    val slotCount: Int,
    /** Sorted by slot, non-overlapping, entirely within `0 until slotCount`. */
    val placements: List<Placement>,
) {

    val isEmpty: Boolean get() = placements.isEmpty()

    /** How many slots are spoken for. */
    val usedSlots: Int get() = placements.sumOf { it.span }

    /** The placement covering [slot], or null if the slot is empty. */
    fun covering(slot: Int): Placement? = placements.firstOrNull { it.covers(slot) }

    /**
     * Would something [span] slots long fit with its leading edge at [slot]?
     *
     * [ignoringSlot] names a placement that is about to be removed — the one
     * being replaced when the user picks something else for a filled slot.
     * Without it, every item would appear to collide with the thing it is
     * replacing, and the chooser would grey out the entire catalogue on exactly
     * the gesture that exists to change an item.
     */
    fun fits(span: Int, slot: Int, ignoringSlot: Int? = null): Boolean {
        if (span < 1) return false
        if (slot < 0 || slot + span > slotCount) return false
        val ignored = ignoringSlot?.let { covering(it) }
        val end = slot + span
        return placements.none { p ->
            p !== ignored && p.slot < end && slot < p.endSlot
        }
    }

    /**
     * Put [placement] in, replacing whatever was covering its leading slot.
     *
     * Throws if it does not fit. See the class KDoc: the caller is expected to
     * have asked [fits] first, and a silent no-op here would surface as a
     * chooser entry that does nothing when tapped.
     */
    fun place(placement: Placement): ToolbarLayout {
        require(fits(placement.span, placement.slot, ignoringSlot = placement.slot)) {
            "${placement.item.id} (${placement.span} slots) does not fit at " +
                "${placement.slot} in a $slotCount-slot bar"
        }
        val replaced = covering(placement.slot)
        val kept = placements.filter { it !== replaced }
        return ToolbarLayout(slotCount, (kept + placement).sortedBy { it.slot })
    }

    /** Empty the slot [slot] falls in. A no-op on an already-empty slot. */
    fun remove(slot: Int): ToolbarLayout {
        val hit = covering(slot) ?: return this
        return ToolbarLayout(slotCount, placements.filter { it !== hit })
    }

    /** The first slot something [span] long would fit in, or null if it is too full. */
    fun firstFit(span: Int): Int? =
        (0..slotCount - span).firstOrNull { fits(span, it) }

    /** A bar of the same shape with nothing in it. */
    fun cleared(): ToolbarLayout = ToolbarLayout(slotCount, emptyList())

    /**
     * The same placements in a bar of a different length, dropping anything
     * that no longer reaches. Used when a release lengthens a bar, or when the
     * screen decides how many slots it can show.
     */
    fun resized(newSlotCount: Int): ToolbarLayout = of(newSlotCount, placements)

    override fun equals(other: Any?): Boolean =
        other is ToolbarLayout && other.slotCount == slotCount && other.placements == placements

    override fun hashCode(): Int = 31 * slotCount + placements.hashCode()

    override fun toString(): String =
        "ToolbarLayout($slotCount, " + placements.joinToString { "${it.slot}=${it.item.id}" } + ")"

    companion object {
        /**
         * Build a layout, dropping anything that cannot be represented.
         *
         * Normalising here rather than validating is what lets the codec be
         * careless with its input and still hand back something sane: an out of
         * range placement, or one overlapping a placement already accepted, is
         * dropped rather than raised. Earlier placements win, so the result is a
         * function of the input order and the tests say so.
         */
        fun of(slotCount: Int, placements: List<Placement>): ToolbarLayout {
            require(slotCount >= 1) { "a toolbar with $slotCount slots is not a toolbar" }
            val kept = ArrayList<Placement>(placements.size)
            for (p in placements) {
                if (p.slot < 0 || p.endSlot > slotCount) continue
                if (kept.any { it.slot < p.endSlot && p.slot < it.endSlot }) continue
                kept += p
            }
            kept.sortBy { it.slot }
            return ToolbarLayout(slotCount, kept)
        }

        /** An empty bar of [slotCount] slots. */
        fun empty(slotCount: Int): ToolbarLayout = of(slotCount, emptyList())
    }
}
