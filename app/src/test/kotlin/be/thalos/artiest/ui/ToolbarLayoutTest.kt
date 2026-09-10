package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The allocator's rules, stated as tests.
 *
 * Note what is not here: a [ToolItem]. This file used to place tools and ask
 * whether tools fit; it places spans now, and the item is only along for the
 * ride so a renderer can find it later. That is the whole of what made a second
 * dimension cheap — see `docs/panels-plan.md`.
 */
class ToolbarLayoutTest {

    private val empty get() = ToolbarLayout.empty(12)

    /** Any item will do: these tests are about the number, not the tool. */
    private fun at(slot: Int, span: Int, item: ToolItem = ToolItem.CLEAR) =
        Placement(item, slot, span)

    @Test
    fun `a fresh bar is empty and its slots are all free`() {
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.usedSlots)
        for (s in 0 until 12) assertNull(empty.covering(s))
    }

    @Test
    fun `a span may not hang off the end of the bar`() {
        assertTrue(empty.fits(4, 8))
        assertFalse(empty.fits(4, 9))
        assertFalse(empty.fits(4, -1))
        assertFalse(empty.fits(0, 0), "a span of nothing is not a placement")
    }

    @Test
    fun `a wide placement is found from every slot it covers`() {
        val bar = empty.place(at(3, 4))
        for (s in 3..6) assertEquals(4, bar.covering(s)?.span, "slot $s")
        assertNull(bar.covering(2))
        assertNull(bar.covering(7))
        assertEquals(4, bar.usedSlots)
    }

    @Test
    fun `a neighbour blocks an overlapping placement`() {
        val bar = empty.place(at(4, 4)) // covers 4..7
        assertFalse(bar.fits(4, 2)) // would cover 2..5
        assertTrue(bar.fits(4, 0)) // covers 0..3
        assertTrue(bar.fits(4, 8)) // covers 8..11
    }

    @Test
    fun `placing on a filled slot replaces what was there`() {
        val bar = empty.place(at(5, 1, ToolItem.CLEAR)).place(at(5, 1, ToolItem.FIT))
        assertEquals(1, bar.placements.size)
        assertEquals(ToolItem.FIT, bar.covering(5)?.item)
    }

    @Test
    fun `a placement does not collide with the one it is replacing`() {
        // Slot 0 holds a four-wide slider and the user picks a different one.
        // Without the ignore rule this reads as a collision with itself and the
        // chooser greys out every wide item on the one gesture that exists to
        // change them.
        val bar = empty.place(at(0, 4, ToolItem.SIZE))
        assertTrue(bar.fits(4, 0, ignoringSlot = 0))
        assertFalse(bar.fits(4, 0))
        val swapped = bar.place(at(0, 4, ToolItem.SMOOTHING))
        assertEquals(ToolItem.SMOOTHING, swapped.covering(0)?.item)
        assertEquals(1, swapped.placements.size)
    }

    @Test
    fun `replacing still has to fit, and a bad replacement throws`() {
        // One slot at 0, four slots at 2. Widening the first to four would need
        // 0..3, which the second holds. Widening in place is the case that has
        // to fail, and it is a caller error rather than a silent no-op.
        val bar = empty.place(at(0, 1)).place(at(2, 4))
        assertFalse(bar.fits(4, 0, ignoringSlot = 0))
        assertFailsWith<IllegalArgumentException> { bar.place(at(0, 4)) }
    }

    @Test
    fun `a span of less than one slot is not a placement`() {
        assertFailsWith<IllegalArgumentException> { at(0, 0) }
    }

    @Test
    fun `removing an empty slot changes nothing, and says so by identity`() {
        val bar = empty.place(at(5, 1))
        assertSame(bar, bar.remove(0))
        assertTrue(bar.remove(5).isEmpty)
    }

    @Test
    fun `removing works from any slot the placement covers`() {
        assertTrue(empty.place(at(3, 4)).remove(6).isEmpty)
    }

    @Test
    fun `firstFit finds the leftmost gap that is big enough`() {
        val bar = empty.place(at(0, 1)).place(at(2, 1))
        assertEquals(3, bar.firstFit(4))
        assertEquals(1, bar.firstFit(1))

        val full = ToolbarLayout.of(4, listOf(at(0, 4)))
        assertNull(full.firstFit(4))
        assertNull(full.firstFit(1))
    }

    @Test
    fun `of drops overlaps and keeps the earlier one`() {
        val bar = ToolbarLayout.of(12, listOf(at(0, 4, ToolItem.SIZE), at(2, 1, ToolItem.FIT)))
        assertEquals(1, bar.placements.size)
        assertEquals(ToolItem.SIZE, bar.placements.single().item)
    }

    @Test
    fun `of drops placements that do not fit in the bar`() {
        val bar = ToolbarLayout.of(
            12,
            listOf(
                at(9, 4, ToolItem.SIZE), // runs to 13, off the end
                at(-1, 1, ToolItem.CLEAR), // before the start
                at(11, 1, ToolItem.FIT), // the last slot, fine
            ),
        )
        assertEquals(listOf(at(11, 1, ToolItem.FIT)), bar.placements)
    }

    @Test
    fun `of sorts, so two layouts built in different orders are equal`() {
        val a = ToolbarLayout.of(12, listOf(at(5, 1, ToolItem.CLEAR), at(1, 1, ToolItem.FIT)))
        val b = ToolbarLayout.of(12, listOf(at(1, 1, ToolItem.FIT), at(5, 1, ToolItem.CLEAR)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, a.placements.first().slot)
    }

    @Test
    fun `a zero-slot bar is not a bar`() {
        assertFailsWith<IllegalArgumentException> { ToolbarLayout.of(0, emptyList()) }
    }

    @Test
    fun `shrinking the bar drops what no longer reaches and keeps the rest`() {
        val long = ToolbarLayout.of(
            16,
            listOf(
                at(0, 1, ToolItem.PEN),
                at(1, 1, ToolItem.PENCIL),
                at(4, 1, ToolItem.UNDO),
                at(6, 4, ToolItem.SIZE), // covers 6..9
                at(12, 1, ToolItem.STATS),
            ),
        )
        val bar = long.resized(8)
        assertEquals(8, bar.slotCount)
        assertEquals(ToolItem.PEN, bar.covering(0)?.item)
        assertEquals(ToolItem.UNDO, bar.covering(4)?.item)
        // The slider starts at 6 and needs four, so it no longer reaches and is
        // gone rather than clipped: half a control is not one. Stats is simply
        // past the end.
        assertEquals(3, bar.placements.size)
    }

    @Test
    fun `growing the bar keeps every placement where it was`() {
        // What DockStore.load does to a bar saved by an older build: nothing may
        // move, or a release that adds a control silently rearranges the bar.
        val old = ToolbarLayout.of(8, listOf(at(0, 1, ToolItem.FIT), at(6, 1, ToolItem.CLEAR)))
        val grown = old.resized(12)
        assertEquals(12, grown.slotCount)
        assertEquals(old.placements, grown.placements)
        assertTrue(grown.fits(1, 8), "the new room is at the end and usable")
    }
}
