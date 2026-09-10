package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The docking rules, stated as tests.
 *
 * The UI plan made this file a stop condition: *"U4's `DockLayout` cannot be
 * tested on the JVM. Then the generalisation has picked up a rendering
 * dependency and the design is wrong."* Nothing here imports anything from
 * Compose or from `android.*`, which is the condition being met rather than
 * merely claimed.
 */
class DockLayoutTest {

    private val empty get() = DockLayout.EMPTY

    @Test
    fun `every dock exists, empty, at its own length`() {
        for (dock in Dock.entries) {
            assertTrue(empty.bar(dock).isEmpty, dock.id)
            assertEquals(dock.defaultSlots, empty.bar(dock).slotCount, dock.id)
        }
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `an item lives in one place, so placing it elsewhere moves it`() {
        val one = empty.place(Dock.LEFT, ToolItem.PEN, 0)
        val two = one.place(Dock.BOTTOM, ToolItem.PEN, 3)

        assertTrue(two.bar(Dock.LEFT).isEmpty, "the left edge let go of it")
        assertEquals(ToolItem.PEN, two.bar(Dock.BOTTOM).covering(3)?.item)
        assertEquals(DockedItem(Dock.BOTTOM, Placement(ToolItem.PEN, 3)), two.locate(ToolItem.PEN))
        assertEquals(1, two.all().size)
    }

    @Test
    fun `of keeps the first copy of a duplicated item and drops the rest`() {
        val layout = DockLayout.of(
            mapOf(
                Dock.LEFT to ToolbarLayout.of(12, listOf(Placement(ToolItem.ERASER, 0))),
                Dock.TOP to ToolbarLayout.of(24, listOf(Placement(ToolItem.ERASER, 5))),
            ),
        )
        // Dock declaration order decides, and LEFT is declared first.
        assertEquals(Dock.LEFT, layout.locate(ToolItem.ERASER)?.dock)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `an item does not collide with itself when it moves within its own dock`() {
        // A four-slot slider at 0, dragged two slots along. Its new span 2..5
        // overlaps its old span 0..3, and it is the same item, so the overlap is
        // with a placement that is about to stop existing.
        val layout = empty.place(Dock.BOTTOM, ToolItem.SIZE, 0)
        assertTrue(layout.fits(Dock.BOTTOM, ToolItem.SIZE, 2))
        val moved = layout.place(Dock.BOTTOM, ToolItem.SIZE, 2)
        assertEquals(1, moved.bar(Dock.BOTTOM).placements.size)
        assertEquals(2, moved.locate(ToolItem.SIZE)?.slot)
    }

    @Test
    fun `a different item in the way still blocks`() {
        val layout = empty
            .place(Dock.BOTTOM, ToolItem.SIZE, 0)
            .place(Dock.BOTTOM, ToolItem.SMOOTHING, 4)
        assertFalse(layout.fits(Dock.BOTTOM, ToolItem.GRAIN, 2), "2..5 runs into smoothing")
        assertTrue(layout.fits(Dock.BOTTOM, ToolItem.GRAIN, 8))
        assertFailsWith<IllegalArgumentException> {
            layout.place(Dock.BOTTOM, ToolItem.GRAIN, 2)
        }
    }

    @Test
    fun `move lands where it is asked to when there is room`() {
        val layout = empty.place(Dock.TOP, ToolItem.UNDO, 3)
        val moved = assertNotNull(layout.move(Dock.TOP, 3, Dock.RIGHT, 2))
        assertEquals(DockedItem(Dock.RIGHT, Placement(ToolItem.UNDO, 2)), moved.locate(ToolItem.UNDO))
    }

    @Test
    fun `move without a slot takes the first one that fits`() {
        val layout = empty
            .place(Dock.RIGHT, ToolItem.ZOOM_IN, 0)
            .place(Dock.TOP, ToolItem.UNDO, 3)
        val moved = assertNotNull(layout.move(Dock.TOP, 3, Dock.RIGHT))
        assertEquals(1, moved.locate(ToolItem.UNDO)?.slot)
    }

    @Test
    fun `a drop onto an occupied slot slides to the nearest room rather than evicting`() {
        // The gesture version of "does not fit". Replacing here would throw away
        // a control the user never touched, which is the rule the single bar
        // already had: nothing moves that was not dragged.
        val layout = empty
            .place(Dock.RIGHT, ToolItem.ZOOM_IN, 2)
            .place(Dock.TOP, ToolItem.FIT, 0)
        val moved = assertNotNull(layout.move(Dock.TOP, 0, Dock.RIGHT, 2))
        assertEquals(0, moved.locate(ToolItem.FIT)?.slot)
        assertEquals(2, moved.locate(ToolItem.ZOOM_IN)?.slot, "the one already there did not move")
    }

    @Test
    fun `a drop onto a dock with no room is refused rather than thrown`() {
        // A one-slot dock with something in it. A gesture that lands somewhere
        // full is a normal thing for a hand to do, so the answer is null and the
        // caller keeps what it had.
        val tight = DockLayout.of(
            mapOf(
                Dock.FLOATING to ToolbarLayout.of(1, listOf(Placement(ToolItem.STATS, 0))),
                Dock.TOP to ToolbarLayout.of(24, listOf(Placement(ToolItem.UNDO, 0))),
            ),
        )
        assertNull(tight.move(Dock.TOP, 0, Dock.FLOATING))
        assertNull(tight.move(Dock.LEFT, 0, Dock.TOP), "nothing in the source slot")
    }

    @Test
    fun `remove empties the slot the item covers, from anywhere in its span`() {
        val layout = empty.place(Dock.BOTTOM, ToolItem.SIZE, 4)
        assertTrue(layout.remove(Dock.BOTTOM, 6).bar(Dock.BOTTOM).isEmpty)
        assertEquals(layout, layout.remove(Dock.BOTTOM, 0), "an empty slot changes nothing")
    }

    @Test
    fun `contains and locate agree with each other`() {
        val layout = empty.place(Dock.LEFT, ToolItem.PENCIL, 1)
        assertTrue(ToolItem.PENCIL in layout)
        assertFalse(ToolItem.PEN in layout)
        assertEquals(Dock.LEFT, layout.locate(ToolItem.PENCIL)?.dock)
        assertNull(layout.locate(ToolItem.PEN))
    }

    @Test
    fun `widening keeps every item where it was, in every dock`() {
        // What DockStore.load does to a layout saved by an older build: nothing
        // may move, or a release that adds a control silently rearranges
        // somebody's bars.
        val old = DockLayout.of(
            mapOf(
                Dock.LEFT to ToolbarLayout.of(4, listOf(Placement(ToolItem.PEN, 3))),
                Dock.TOP to ToolbarLayout.of(6, listOf(Placement(ToolItem.UNDO, 5))),
            ),
        )
        val grown = old.resized { it.defaultSlots }
        assertEquals(3, grown.locate(ToolItem.PEN)?.slot)
        assertEquals(5, grown.locate(ToolItem.UNDO)?.slot)
        assertEquals(Dock.LEFT.defaultSlots, grown.bar(Dock.LEFT).slotCount)
        assertNotNull(grown.firstFit(Dock.LEFT, ToolItem.ERASER), "the new room is usable")
    }

    @Test
    fun `the starter layout survives its own normalisation`() {
        // A hand-written constant is exactly the kind of thing that quietly
        // loses an entry to an off-by-one width or a repeated item, and `of`
        // drops rather than complains — so the counts are asserted here or
        // nowhere.
        val starter = DockLayout.STARTER
        assertEquals(20, starter.all().size)
        assertEquals(starter.all().size, starter.all().map { it.item }.toSet().size)

        // The grouping is the feature, so it is pinned rather than left to
        // whatever the constant happens to say next month.
        assertEquals(Dock.LEFT, starter.locate(ToolItem.PEN)?.dock)
        assertEquals(Dock.LEFT, starter.locate(ToolItem.PENCIL)?.dock)
        assertEquals(Dock.LEFT, starter.locate(ToolItem.ERASER)?.dock)
        assertEquals(Dock.LEFT, starter.locate(ToolItem.COLOUR)?.dock)
        assertEquals(Dock.TOP, starter.locate(ToolItem.UNDO)?.dock)
        assertEquals(Dock.TOP, starter.locate(ToolItem.REDO)?.dock)
        assertEquals(Dock.RIGHT, starter.locate(ToolItem.ZOOM_IN)?.dock)
        assertEquals(Dock.RIGHT, starter.locate(ToolItem.LAYERS)?.dock)
        assertEquals(Dock.LEFT, starter.locate(ToolItem.MARQUEE)?.dock)
        assertEquals(Dock.RIGHT, starter.locate(ToolItem.SELECTION)?.dock)
        assertEquals(Dock.BOTTOM, starter.locate(ToolItem.ERASER_SIZE)?.dock)
        assertEquals(Dock.RIGHT, starter.locate(ToolItem.FIT)?.dock)
        assertEquals(Dock.BOTTOM, starter.locate(ToolItem.SIZE)?.dock)
        assertEquals(Dock.BOTTOM, starter.locate(ToolItem.GRAIN)?.dock)
        assertTrue(starter.bar(Dock.FLOATING).isEmpty, "no honest guess at where it goes")

        // Every dock still has somewhere for the next control to land, which is
        // the headroom rule Dock.defaultSlots exists for.
        for (dock in Dock.entries) {
            assertNotNull(starter.firstFit(dock, ToolItem.CLEAR), dock.id)
        }
    }

    @Test
    fun `the starter layout leaves a gap between groups`() {
        // The separators are load-bearing: out of arrange mode an unfilled slot
        // is drawn as a narrow gap, and that gap is the only thing saying that
        // the eraser and the colour are two ideas rather than a run of four.
        val left = DockLayout.STARTER.bar(Dock.LEFT)
        assertNull(left.covering(5), "between the tools and the colour")
        val top = DockLayout.STARTER.bar(Dock.TOP)
        assertNull(top.covering(2), "between what you did and what leaves the app")
    }
}
