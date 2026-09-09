package be.thalos.artiest.ui

/**
 * One item, and the slot its left edge sits in.
 *
 * An item [ToolItem.slots] wide placed at [slot] covers `slot until slot +
 * item.slots`. Only the left edge is stored; the covered span is derived, which
 * is why there is no way to represent a placement whose two ends disagree.
 */
data class Placement(val item: ToolItem, val slot: Int) {
    /** Exclusive. */
    val endSlot: Int get() = slot + item.slots

    fun covers(s: Int): Boolean = s in slot until endSlot
}

/**
 * The toolbar as data: a fixed row of slots, some of them filled.
 *
 * **Why fixed slots rather than a row that grows.** A bar that reflows when you
 * add something is a bar where Export is in a different place every week, and
 * the single thing a toolbar is *for* is that your hand knows where the button
 * is without your eye going there. Fixed slots cost an empty-slot affordance and
 * an occasional "that does not fit"; a reflowing bar costs the muscle memory,
 * which is the whole asset. If the bar is wider than the screen it scrolls —
 * position stays absolute, only the viewport moves.
 *
 * **Why the chooser disables rather than the layout rearranges.** Ask to put a
 * four-slot slider into a two-slot gap and there are three possible kindnesses:
 * refuse, shove the neighbours along, or drop it somewhere else that fits. The
 * last two both move an item the user did not touch, which breaks rule one
 * above. So [fits] is a public query, the chooser greys out what would not go,
 * and [place] treats a bad placement as a programming error and throws —
 * because a control that silently declines to appear is indistinguishable from
 * a control that is broken.
 *
 * The type is immutable. Every operation returns a new layout, which is what
 * makes it usable as Compose state and trivial to test.
 *
 * **It no longer knows how long it is by default.** It used to carry a
 * `DEFAULT_SLOTS`, a `DEFAULT` and a `STARTER`, because for a while there was
 * one bar and those were its answers. There are five bars now and the answers
 * differ per bar — a column up the side of a tablet is about half the length of
 * a row across the bottom — so the length belongs to [Dock] and the starting
 * arrangement belongs to [DockLayout]. What is left here is the algebra, which
 * is the part that was always general.
 */
class ToolbarLayout private constructor(
    val slotCount: Int,
    /** Sorted by slot, non-overlapping, entirely within `0 until slotCount`. */
    val placements: List<Placement>,
) {

    val isEmpty: Boolean get() = placements.isEmpty()

    /** How many slots are spoken for. */
    val usedSlots: Int get() = placements.sumOf { it.item.slots }

    /** The placement covering [slot], or null if the slot is empty. */
    fun covering(slot: Int): Placement? = placements.firstOrNull { it.covers(slot) }

    /**
     * Would [item] fit with its left edge at [slot]?
     *
     * [ignoringSlot] names a placement that is about to be removed — the one
     * being replaced when the user long-presses a filled slot and picks
     * something else. Without it, every item would appear to collide with the
     * thing it is replacing, and the chooser would grey out the entire
     * catalogue on exactly the gesture that exists to change an item.
     */
    fun fits(item: ToolItem, slot: Int, ignoringSlot: Int? = null): Boolean {
        if (slot < 0 || slot + item.slots > slotCount) return false
        val ignored = ignoringSlot?.let { covering(it) }
        val end = slot + item.slots
        return placements.none { p ->
            p !== ignored && p.slot < end && slot < p.endSlot
        }
    }

    /**
     * Put [item] at [slot], replacing whatever was covering that slot.
     *
     * Throws if it does not fit. See the class KDoc: the caller is expected to
     * have asked [fits] first, and a silent no-op here would surface as a
     * chooser entry that does nothing when tapped.
     */
    fun place(item: ToolItem, slot: Int): ToolbarLayout {
        require(fits(item, slot, ignoringSlot = slot)) {
            "${item.id} (${item.slots} slots) does not fit at $slot in a $slotCount-slot bar"
        }
        val replaced = covering(slot)
        val kept = placements.filter { it !== replaced }
        return ToolbarLayout(slotCount, (kept + Placement(item, slot)).sortedBy { it.slot })
    }

    /** Empty the slot [slot] falls in. A no-op on an already-empty slot. */
    fun remove(slot: Int): ToolbarLayout {
        val hit = covering(slot) ?: return this
        return ToolbarLayout(slotCount, placements.filter { it !== hit })
    }

    /** The leftmost slot [item] would fit in, or null if the bar is too full. */
    fun firstFit(item: ToolItem): Int? =
        (0..slotCount - item.slots).firstOrNull { fits(item, it) }

    /** A bar of the same shape with nothing in it. */
    fun cleared(): ToolbarLayout = ToolbarLayout(slotCount, emptyList())

    /**
     * The same placements in a bar of a different length, dropping anything
     * that no longer reaches. Used when the screen decides how many slots the
     * bar can show.
     */
    fun resized(newSlotCount: Int): ToolbarLayout =
        of(newSlotCount, placements)

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
    }
}
