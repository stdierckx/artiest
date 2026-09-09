package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The toolbar's rules, stated as tests.
 *
 * These are cheap to write and they are the reason this feature could be built
 * with no tablet in the room: everything about *where an item may go* is
 * decidable on the JVM, and only how it looks is not.
 */
class ToolbarLayoutTest {

    private val empty get() = ToolbarLayout.of(12, emptyList())

    @Test
    fun `a fresh bar is empty and its slots are all free`() {
        assertTrue(ToolbarLayout.DEFAULT.isEmpty)
        assertEquals(0, ToolbarLayout.DEFAULT.usedSlots)
        for (s in 0 until ToolbarLayout.DEFAULT_SLOTS) {
            assertNull(ToolbarLayout.DEFAULT.covering(s))
        }
    }

    @Test
    fun `an item may not hang off the end of the bar`() {
        // SIZE is four slots wide, so 8 is the last left edge that fits in 12.
        assertTrue(empty.fits(ToolItem.SIZE, 8))
        assertFalse(empty.fits(ToolItem.SIZE, 9))
        assertFalse(empty.fits(ToolItem.SIZE, -1))
    }

    @Test
    fun `a wide item is found from every slot it covers`() {
        val bar = empty.place(ToolItem.SIZE, 3)
        for (s in 3..6) {
            assertEquals(ToolItem.SIZE, bar.covering(s)?.item, "slot $s")
        }
        assertNull(bar.covering(2))
        assertNull(bar.covering(7))
        assertEquals(4, bar.usedSlots)
    }

    @Test
    fun `a neighbour blocks an overlapping placement`() {
        val bar = empty.place(ToolItem.SIZE, 4) // covers 4..7
        assertFalse(bar.fits(ToolItem.COLOUR, 2)) // would cover 2..5
        assertTrue(bar.fits(ToolItem.COLOUR, 0))  // covers 0..3
        assertTrue(bar.fits(ToolItem.COLOUR, 8))  // covers 8..11
    }

    @Test
    fun `placing on a filled slot replaces what was there`() {
        val bar = empty.place(ToolItem.CLEAR, 5).place(ToolItem.FIT, 5)
        assertEquals(1, bar.placements.size)
        assertEquals(ToolItem.FIT, bar.covering(5)?.item)
    }

    @Test
    fun `an item does not collide with the item it is replacing`() {
        // The long-press gesture: slot 0 holds a four-wide slider and the user
        // picks a different four-wide slider. Without the ignore rule this
        // reads as a collision with itself and the chooser greys out every
        // wide item on the one gesture that exists to change them.
        val bar = empty.place(ToolItem.SIZE, 0)
        assertTrue(bar.fits(ToolItem.SMOOTHING, 0, ignoringSlot = 0))
        assertFalse(bar.fits(ToolItem.SMOOTHING, 0))
        val swapped = bar.place(ToolItem.SMOOTHING, 0)
        assertEquals(ToolItem.SMOOTHING, swapped.covering(0)?.item)
        assertEquals(1, swapped.placements.size)
    }

    @Test
    fun `replacing still has to fit, and a bad replacement throws`() {
        // CLEAR at 0 (0..1), COLOUR at 2 (2..5). Swapping the two-wide CLEAR
        // for a four-wide slider would need 0..3, which COLOUR holds. Widening
        // an item in place is the case that has to fail, and it is a caller
        // error rather than a silent no-op.
        val bar = empty.place(ToolItem.CLEAR, 0).place(ToolItem.COLOUR, 2)
        assertFalse(bar.fits(ToolItem.SIZE, 0, ignoringSlot = 0))
        assertFailsWith<IllegalArgumentException> { bar.place(ToolItem.SIZE, 0) }
    }

    @Test
    fun `removing an empty slot changes nothing, and says so by identity`() {
        val bar = empty.place(ToolItem.CLEAR, 5)
        assertSame(bar, bar.remove(0))
        assertTrue(bar.remove(5).isEmpty)
    }

    @Test
    fun `removing works from any slot the item covers`() {
        val bar = empty.place(ToolItem.SIZE, 3)
        assertTrue(bar.remove(6).isEmpty)
    }

    @Test
    fun `firstFit finds the leftmost gap that is big enough`() {
        val bar = empty.place(ToolItem.CLEAR, 0).place(ToolItem.FIT, 2)
        assertEquals(3, bar.firstFit(ToolItem.SIZE))
        assertEquals(3, bar.firstFit(ToolItem.CLEAR))

        val full = ToolbarLayout.of(4, listOf(Placement(ToolItem.CLEAR, 1)))
        assertNull(full.firstFit(ToolItem.SIZE))
        assertEquals(0, full.firstFit(ToolItem.FIT))
    }

    @Test
    fun `of drops overlaps and keeps the earlier one`() {
        val bar = ToolbarLayout.of(
            12,
            listOf(Placement(ToolItem.SIZE, 0), Placement(ToolItem.COLOUR, 2)),
        )
        assertEquals(1, bar.placements.size)
        assertEquals(ToolItem.SIZE, bar.placements.single().item)
    }

    @Test
    fun `of drops placements that do not fit in the bar`() {
        val bar = ToolbarLayout.of(
            12,
            listOf(
                Placement(ToolItem.SIZE, 9),   // runs to 13, off the end
                Placement(ToolItem.CLEAR, -1), // before the start
                Placement(ToolItem.FIT, 11),   // the last slot, fine
            ),
        )
        assertEquals(listOf(Placement(ToolItem.FIT, 11)), bar.placements)
    }

    @Test
    fun `of sorts, so two layouts built in different orders are equal`() {
        val a = ToolbarLayout.of(12, listOf(Placement(ToolItem.CLEAR, 5), Placement(ToolItem.FIT, 1)))
        val b = ToolbarLayout.of(12, listOf(Placement(ToolItem.FIT, 1), Placement(ToolItem.CLEAR, 5)))
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
        val bar = ToolbarLayout.STARTER.resized(8)
        assertEquals(8, bar.slotCount)
        assertEquals(ToolItem.COLOUR, bar.covering(0)?.item)
        assertEquals(ToolItem.SIZE, bar.covering(4)?.item)
        // Everything from slot 8 on no longer reaches, and is gone rather than
        // clipped: half a slider is not a control.
        assertEquals(2, bar.placements.size)
    }

    @Test
    fun `the starter layout survives its own normalisation`() {
        // A hand-written constant is exactly the kind of thing that quietly
        // loses an entry to an off-by-one width, and `of` drops rather than
        // complains — so the count is asserted here or nowhere.
        assertEquals(7, ToolbarLayout.STARTER.placements.size)
        assertEquals(15, ToolbarLayout.STARTER.usedSlots)
        assertEquals(ToolbarLayout.DEFAULT_SLOTS, ToolbarLayout.STARTER.slotCount)
    }
}
