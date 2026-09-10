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
 * merely claimed — and it still holds now that bars are made and destroyed at
 * run time and items have two dimensions.
 */
class DockLayoutTest {

    private val empty get() = DockLayout.EMPTY
    private val here = BarSpot(0.4f, 0.5f)

    // ---- footprints ------------------------------------------------------

    @Test
    fun `a button is one slot whichever way the bar runs`() {
        assertEquals(1, ToolItem.PEN.slotsIn(Axis.HORIZONTAL))
        assertEquals(1, ToolItem.PEN.slotsIn(Axis.VERTICAL))
        assertEquals(1, ToolItem.PEN.depthIn(Axis.VERTICAL))
    }

    @Test
    fun `a slider turns with the bar, so it is always long along it`() {
        assertEquals(4, ToolItem.SIZE.slotsIn(Axis.HORIZONTAL))
        assertEquals(4, ToolItem.SIZE.slotsIn(Axis.VERTICAL))
        assertEquals(1, ToolItem.SIZE.depthIn(Axis.HORIZONTAL))
        assertEquals(1, ToolItem.SIZE.depthIn(Axis.VERTICAL))
    }

    @Test
    fun `a panel keeps its shape, so only which side eats slots changes`() {
        // Six by ten on screen in both cases. This is the whole reason the
        // footprint is in screen terms and not in the dock's frame: a colour
        // wheel that were 396 by 264 on the top edge would owe every panel a
        // responsive layout forever.
        val p = ToolItem.COLOUR_PANEL
        assertEquals(6, p.slotsIn(Axis.HORIZONTAL))
        assertEquals(11, p.depthIn(Axis.HORIZONTAL))
        assertEquals(11, p.slotsIn(Axis.VERTICAL))
        assertEquals(6, p.depthIn(Axis.VERTICAL))
    }

    @Test
    fun `the span a bar gives an item follows its own axis`() {
        assertEquals(6, empty.edge(Dock.TOP).spanOf(ToolItem.COLOUR_PANEL))
        assertEquals(11, empty.edge(Dock.LEFT).spanOf(ToolItem.COLOUR_PANEL))
    }

    // ---- the four edges --------------------------------------------------

    @Test
    fun `every edge exists, empty, at its own length`() {
        for (dock in Dock.EDGES) {
            val bar = empty.edge(dock)
            assertTrue(bar.isEmpty, dock.id)
            assertEquals(dock.id, bar.id)
            assertEquals(dock.defaultSlots, bar.slots.slotCount, dock.id)
        }
        assertTrue(empty.isEmpty)
        assertTrue(empty.floating.isEmpty())
    }

    @Test
    fun `an item lives in one place, so placing it elsewhere moves it`() {
        val one = empty.place("left", ToolItem.PEN, 0)
        val two = one.place("bottom", ToolItem.PEN, 3)

        assertTrue(two.edge(Dock.LEFT).isEmpty, "the left edge let go of it")
        assertEquals(ToolItem.PEN, two.edge(Dock.BOTTOM).slots.covering(3)?.item)
        assertEquals("bottom", two.locate(ToolItem.PEN)?.bar?.id)
        assertEquals(1, two.all().size)
    }

    @Test
    fun `an item does not collide with itself when it moves within its own bar`() {
        val layout = empty.place("bottom", ToolItem.SIZE, 0)
        assertTrue(layout.fits("bottom", ToolItem.SIZE, 2))
        val moved = layout.place("bottom", ToolItem.SIZE, 2)
        assertEquals(1, moved.edge(Dock.BOTTOM).slots.placements.size)
        assertEquals(2, moved.locate(ToolItem.SIZE)?.slot)
    }

    @Test
    fun `a different item in the way still blocks`() {
        val layout = empty
            .place("bottom", ToolItem.SIZE, 0)
            .place("bottom", ToolItem.SMOOTHING, 4)
        assertFalse(layout.fits("bottom", ToolItem.GRAIN, 2), "2..5 runs into smoothing")
        assertTrue(layout.fits("bottom", ToolItem.GRAIN, 8))
        assertFailsWith<IllegalArgumentException> { layout.place("bottom", ToolItem.GRAIN, 2) }
    }

    @Test
    fun `a panel needs its whole footprint, on whichever edge`() {
        // Eleven slots of a twelve-slot side edge. It fits, and it leaves that edge
        // good for very little else — which is the user's choice to make.
        assertTrue(empty.fits("left", ToolItem.COLOUR_PANEL, 0))
        assertFalse(empty.fits("left", ToolItem.COLOUR_PANEL, 2))
        assertTrue(empty.fits("top", ToolItem.COLOUR_PANEL, 3))
    }

    @Test
    fun `move lands where it is asked to when there is room`() {
        val layout = empty.place("top", ToolItem.UNDO, 3)
        val moved = assertNotNull(layout.move("top", 3, "right", 2))
        assertEquals("right", moved.locate(ToolItem.UNDO)?.bar?.id)
        assertEquals(2, moved.locate(ToolItem.UNDO)?.slot)
    }

    @Test
    fun `a drop onto an occupied slot slides to the nearest room rather than evicting`() {
        val layout = empty
            .place("right", ToolItem.ZOOM_IN, 2)
            .place("top", ToolItem.FIT, 0)
        val moved = assertNotNull(layout.move("top", 0, "right", 2))
        assertEquals(0, moved.locate(ToolItem.FIT)?.slot)
        assertEquals(2, moved.locate(ToolItem.ZOOM_IN)?.slot, "the one already there did not move")
    }

    @Test
    fun `a drop onto a bar with no room is refused rather than thrown`() {
        // A gesture that lands somewhere full is a normal thing for a hand to
        // do, so the answer is null and the caller keeps what it had.
        val tight = empty.place("top", ToolItem.COLOUR_PANEL, 0)
        val (withBar, id) = tight.addFloating(ToolItem.STATS, here)
        // That bar was made to hold one button plus two spare slots, so a
        // six-slot panel has nowhere to go on it.
        assertNull(withBar.move("top", 0, id))
        assertNull(withBar.move("left", 0, "top"), "nothing in the source slot")
        assertNull(withBar.move("nonesuch", 0, "top"), "no such bar")
    }

    // ---- bars that come and go -------------------------------------------

    @Test
    fun `fixate is three ordinary operations, and the first makes a bar`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val bar = assertNotNull(next.bar(id))
        assertEquals(Dock.FLOATING, bar.dock)
        assertEquals(here, bar.spot)
        assertEquals(0, next.locate(ToolItem.COLOUR_PANEL)?.slot)
        // Sized to the panel plus room to drop something else in beside it.
        assertEquals(ToolItem.COLOUR_PANEL.slotsIn(Axis.HORIZONTAL) + 2, bar.slots.slotCount)
        assertEquals(1, next.floating.size)
    }

    @Test
    fun `floating bars get their own names, and a closed one frees its name`() {
        val (a, first) = empty.addFloating(ToolItem.STATS, here)
        val (b, second) = a.addFloating(ToolItem.CLEAR, here)
        assertEquals("f1", first)
        assertEquals("f2", second)

        val closed = b.closeBar(first)
        assertNull(closed.bar(first))
        assertNull(closed.locate(ToolItem.STATS), "what was on it went with it")
        assertNotNull(closed.locate(ToolItem.CLEAR), "the other one is untouched")

        val (c, third) = closed.addFloating(ToolItem.FIT, here)
        assertEquals("f1", third, "the freed name is reused rather than climbing forever")
        assertEquals(2, c.floating.size)
    }

    @Test
    fun `an edge cannot be closed, only emptied`() {
        val layout = empty.place("left", ToolItem.PEN, 0)
        val closed = layout.closeBar("left")
        assertNotNull(closed.bar("left"), "the edge is still there")
        assertTrue(closed.edge(Dock.LEFT).isEmpty)
        assertEquals(4, closed.edges.size)
    }

    @Test
    fun `moving a bar only moves one that floats`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val there = BarSpot(0.1f, 0.9f)
        assertEquals(there, next.moveBar(id, there).bar(id)?.spot)
        assertNull(next.moveBar("left", there).bar("left")?.spot, "an edge has no position")
    }

    @Test
    fun `an emptied floating bar is tidied away, an emptied edge is not`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val emptied = next.remove(id, 0)
        assertNotNull(emptied.bar(id), "it survives being empty for as long as the drag might")
        assertNull(emptied.tidied().bar(id))
        assertEquals(4, emptied.tidied().edges.size)
    }

    @Test
    fun `resizing a floating bar resizes the panel that is its whole reason`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val bigger = next.resizeFloating(id, 9, 14)
        assertEquals(9, bigger.bar(id)?.slots?.covering(0)?.span)
        assertEquals(14, bigger.bar(id)?.slots?.covering(0)?.depth)
        assertEquals(9, bigger.bar(id)?.slots?.slotCount, "the bar is the panel's window")
        assertEquals(14, bigger.bar(id)?.depthCells)
    }

    @Test
    fun `a bar of buttons resizes in length only`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val longer = next.resizeFloating(id, 6, 9)
        assertEquals(6, longer.bar(id)?.slots?.slotCount)
        assertEquals(1, longer.bar(id)?.depthCells, "a button is a button however long the bar")
    }

    @Test
    fun `a resize is clamped rather than refused, and an edge cannot be resized`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        assertEquals(2, next.resizeFloating(id, -3, 1).bar(id)?.slots?.slotCount)
        assertEquals(24, next.resizeFloating(id, 900, 1).bar(id)?.slots?.slotCount)
        assertEquals(
            Dock.LEFT.defaultSlots,
            next.resizeFloating("left", 4, 4).edge(Dock.LEFT).slots.slotCount,
        )
    }

    @Test
    fun `a chosen size follows the control along a bar of the same direction`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val sized = next.resizeFloating(id, 8, 9)
        // Bottom runs the same way as a floating bar, so the size carries.
        val moved = sized.place("bottom", ToolItem.COLOUR_PANEL, 0)
        assertEquals(8, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.span)
        assertEquals(9, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.depth)

        // Turning it on its side has nothing sensible to carry, so it goes back
        // to what the catalogue says.
        val turned = sized.place("left", ToolItem.COLOUR_PANEL, 0)
        assertEquals(11, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.span)
        assertEquals(6, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.depth)
    }

    @Test
    fun `docking a floating bar into an edge empties it and closes it`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val withTwo = next.place(id, ToolItem.CLEAR, 1)
        val docked = assertNotNull(withTwo.dockInto(id, "top"))
        assertNull(docked.bar(id), "the bar it came from is gone")
        assertEquals("top", docked.locate(ToolItem.STATS)?.bar?.id)
        assertEquals("top", docked.locate(ToolItem.CLEAR)?.bar?.id)
    }

    @Test
    fun `docking is refused whole when the edge cannot take all of it`() {
        // The panel takes eleven of the right edge's twelve slots, so one of the
        // two controls fits and the other does not. Half a bar arriving is worse
        // than none: the half left behind is on a bar that was about to close.
        val full = empty.place("right", ToolItem.COLOUR_PANEL, 0)
        val (one, id) = full.addFloating(ToolItem.STATS, here)
        val two = one.place(id, ToolItem.CLEAR, 1)

        assertNull(two.dockInto(id, "right"))
        assertEquals(id, two.locate(ToolItem.STATS)?.bar?.id, "and nothing moved")
        assertEquals(id, two.locate(ToolItem.CLEAR)?.bar?.id)

        assertNotNull(two.dockInto(id, "top"), "somewhere with room takes both")
        assertNull(two.dockInto("left", "top"), "an edge is not a bar you can dock")
        assertNull(two.dockInto(id, id))
    }

    @Test
    fun `of keeps the first copy of a duplicated item and drops the rest`() {
        val layout = DockLayout.of(
            listOf(
                Bar("left", Dock.LEFT, null,
                    ToolbarLayout.of(12, listOf(Placement(ToolItem.ERASER, 0, 1)))),
                Bar("top", Dock.TOP, null,
                    ToolbarLayout.of(24, listOf(Placement(ToolItem.ERASER, 5, 1)))),
            ),
        )
        // Edges are filled in in Dock.EDGES order, and left comes first.
        assertEquals("left", layout.locate(ToolItem.ERASER)?.bar?.id)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `contains and locate agree with each other`() {
        val layout = empty.place("left", ToolItem.PENCIL, 1)
        assertTrue(ToolItem.PENCIL in layout)
        assertFalse(ToolItem.PEN in layout)
        assertEquals("left", layout.locate(ToolItem.PENCIL)?.bar?.id)
        assertNull(layout.locate(ToolItem.PEN))
    }

    @Test
    fun `widening keeps every item where it was, on every bar`() {
        val old = DockLayout.of(
            listOf(
                Bar("left", Dock.LEFT, null,
                    ToolbarLayout.of(4, listOf(Placement(ToolItem.PEN, 3, 1)))),
                Bar("top", Dock.TOP, null,
                    ToolbarLayout.of(6, listOf(Placement(ToolItem.UNDO, 5, 1)))),
            ),
        )
        val grown = old.resized { maxOf(it.slots.slotCount, it.dock.defaultSlots) }
        assertEquals(3, grown.locate(ToolItem.PEN)?.slot)
        assertEquals(5, grown.locate(ToolItem.UNDO)?.slot)
        assertEquals(Dock.LEFT.defaultSlots, grown.edge(Dock.LEFT).slots.slotCount)
        assertNotNull(grown.firstFit("left", ToolItem.ERASER), "the new room is usable")
    }

    // ---- the default -----------------------------------------------------

    @Test
    fun `the starter layout survives its own normalisation`() {
        val starter = DockLayout.STARTER
        assertEquals(14, starter.all().size)
        assertEquals(starter.all().size, starter.all().map { it.item }.toSet().size)
        assertTrue(starter.floating.isEmpty(), "a floating bar is something the user made")

        // The grouping is the feature, so it is pinned rather than left to
        // whatever the constant happens to say next month.
        for (item in listOf(ToolItem.PEN, ToolItem.PENCIL, ToolItem.ERASER, ToolItem.COLOUR)) {
            assertEquals("left", starter.locate(item)?.bar?.id, item.id)
        }
        for (item in listOf(ToolItem.UNDO, ToolItem.REDO, ToolItem.EXPORT, ToolItem.STATS)) {
            assertEquals("top", starter.locate(item)?.bar?.id, item.id)
        }
        for (item in listOf(ToolItem.ZOOM_IN, ToolItem.ZOOM_OUT, ToolItem.FIT)) {
            assertEquals("right", starter.locate(item)?.bar?.id, item.id)
        }
        for (item in listOf(ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN)) {
            assertEquals("bottom", starter.locate(item)?.bar?.id, item.id)
        }

        // Every edge still has somewhere for the next control to land, which is
        // the headroom rule Dock.defaultSlots exists for.
        for (dock in Dock.EDGES) {
            assertNotNull(starter.firstFit(dock.id, ToolItem.CLEAR), dock.id)
        }
    }

    @Test
    fun `the starter layout leaves a gap between groups`() {
        // The separators are load-bearing: out of arrange mode an unfilled slot
        // is drawn as a narrow gap, and that gap is the only thing saying that
        // the eraser and the colour are two ideas rather than a run of four.
        assertNull(DockLayout.STARTER.edge(Dock.LEFT).slots.covering(3))
        assertNull(DockLayout.STARTER.edge(Dock.TOP).slots.covering(2))
    }
}
