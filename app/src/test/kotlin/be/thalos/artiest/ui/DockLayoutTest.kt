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
 * merely claimed — and it still holds now that a bar is a *shape* rather than a
 * line, which was the harder version of the same bet.
 *
 * Every case here was carried over from when a placement was a slot and a span.
 * The helpers at the top are why they still read the same way: [put] and [at]
 * turn "the fourth slot along" into a cell, so what changed in the port is the
 * arithmetic and not one of the rules.
 */
class DockLayoutTest {

    private val empty get() = DockLayout.EMPTY
    private val here = BarSpot(0.4f, 0.5f)

    /** The cell [slot] along a surface, whichever way it runs. */
    private fun DockLayout.at(surfaceId: String, slot: Int): Cell =
        surface(surfaceId)!!.cellAt(slot)

    private fun DockLayout.put(surfaceId: String, item: ToolItem, slot: Int): DockLayout =
        place(surfaceId, item, at(surfaceId, slot))

    private fun DockLayout.goes(surfaceId: String, item: ToolItem, slot: Int): Boolean =
        fits(surfaceId, item, at(surfaceId, slot))

    /** How far along its own surface a placed item sits. */
    private val DockedItem.slot: Int get() = surface.slotOf(cell)

    private fun DockLayout.slotOf(item: ToolItem): Int? = locate(item)?.slot

    private fun DockLayout.on(item: ToolItem): String? = locate(item)?.surface?.id

    // ---- footprints ------------------------------------------------------

    private fun sizeOn(dock: Dock, item: ToolItem): Pair<Int, Int> {
        val region = dock.defaultRegion()
        return RegionLayout.naturalSize(item, region, Cell(0, 0))
    }

    /** The along-the-bar and across-the-bar numbers, for a bar on [dock]. */
    private fun spanAndDepth(dock: Dock, item: ToolItem): Pair<Int, Int> {
        val (w, h) = sizeOn(dock, item)
        return if (dock.axis == Axis.HORIZONTAL) w to h else h to w
    }

    @Test
    fun `a button is one cell whichever way the bar runs`() {
        assertEquals(1 to 1, spanAndDepth(Dock.TOP, ToolItem.PEN))
        assertEquals(1 to 1, spanAndDepth(Dock.LEFT, ToolItem.PEN))
    }

    @Test
    fun `a slider turns with the bar, so it is always long along it`() {
        assertEquals(4 to 1, spanAndDepth(Dock.TOP, ToolItem.SIZE))
        assertEquals(4 to 1, spanAndDepth(Dock.LEFT, ToolItem.SIZE))
        // And on screen that is four wide on the top and four tall on the left.
        assertEquals(4 to 1, sizeOn(Dock.TOP, ToolItem.SIZE))
        assertEquals(1 to 4, sizeOn(Dock.LEFT, ToolItem.SIZE))
    }

    @Test
    fun `a panel keeps its shape, so only which side eats slots changes`() {
        // Six by eleven on screen in both cases. This is the whole reason the
        // footprint is in screen terms and not in the bar's frame: a colour
        // wheel that were 396 by 264 on the top edge would owe every panel a
        // responsive layout forever.
        val p = ToolItem.COLOUR_PANEL
        assertEquals(6 to 11, sizeOn(Dock.TOP, p))
        assertEquals(6 to 11, sizeOn(Dock.LEFT, p))
        assertEquals(6 to 11, spanAndDepth(Dock.TOP, p))
        assertEquals(11 to 6, spanAndDepth(Dock.LEFT, p))
    }

    @Test
    fun `the footprint a surface gives an item follows its own shape`() {
        assertEquals(6 to 11, empty.edge(Dock.TOP).footprintOf(ToolItem.COLOUR_PANEL, Cell(0, 0)))
        assertEquals(6 to 11, empty.edge(Dock.LEFT).footprintOf(ToolItem.COLOUR_PANEL, Cell(0, 0)))
        assertEquals(4 to 1, empty.edge(Dock.BOTTOM).footprintOf(ToolItem.SIZE, Cell(0, 0)))
        assertEquals(1 to 4, empty.edge(Dock.LEFT).footprintOf(ToolItem.SIZE, Cell(0, 0)))
    }

    // ---- the four edges --------------------------------------------------

    @Test
    fun `every edge exists, empty, at its own length`() {
        for (dock in Dock.EDGES) {
            val surface = empty.edge(dock)
            assertTrue(surface.isEmpty, dock.id)
            assertEquals(dock.id, surface.id)
            assertEquals(dock.defaultSlots, surface.slotCount, dock.id)
            assertTrue(surface.region.isStrip, "${dock.id} starts as a plain bar")
            assertEquals(dock.axis, surface.axis, dock.id)
        }
        assertTrue(empty.isEmpty)
        assertTrue(empty.floating.isEmpty())
    }

    @Test
    fun `an item lives in one place, so placing it elsewhere moves it`() {
        val one = empty.put("left", ToolItem.PEN, 0)
        val two = one.put("bottom", ToolItem.PEN, 3)

        assertTrue(two.edge(Dock.LEFT).isEmpty, "the left edge let go of it")
        assertEquals(ToolItem.PEN, two.edge(Dock.BOTTOM).slots.covering(Cell(3, 0))?.item)
        assertEquals("bottom", two.on(ToolItem.PEN))
        assertEquals(1, two.all().size)
    }

    @Test
    fun `an item does not collide with itself when it moves within its own bar`() {
        val layout = empty.put("bottom", ToolItem.SIZE, 0)
        assertTrue(layout.goes("bottom", ToolItem.SIZE, 2))
        val moved = layout.put("bottom", ToolItem.SIZE, 2)
        assertEquals(1, moved.edge(Dock.BOTTOM).slots.placements.size)
        assertEquals(2, moved.slotOf(ToolItem.SIZE))
    }

    @Test
    fun `a different item in the way still blocks`() {
        val layout = empty
            .put("bottom", ToolItem.SIZE, 0)
            .put("bottom", ToolItem.SMOOTHING, 4)
        assertFalse(layout.goes("bottom", ToolItem.GRAIN, 2), "2..5 runs into smoothing")
        assertTrue(layout.goes("bottom", ToolItem.GRAIN, 8))
        assertFailsWith<IllegalArgumentException> { layout.put("bottom", ToolItem.GRAIN, 2) }
    }

    @Test
    fun `a panel needs its whole length, on whichever edge`() {
        // Eleven cells of a twelve-cell side edge. It fits, and it leaves that
        // edge good for very little else — which is the user's choice to make.
        assertTrue(empty.goes("left", ToolItem.COLOUR_PANEL, 0))
        assertFalse(empty.goes("left", ToolItem.COLOUR_PANEL, 2))
        assertTrue(empty.goes("top", ToolItem.COLOUR_PANEL, 3))
    }

    @Test
    fun `a bar is as thick as the thickest thing on it, and a shape is not`() {
        // The one asymmetry the second dimension introduced. A bar grows across
        // itself to hold a panel, which is what it always did; a shape somebody
        // drew does not, because growing it would redraw their toolbar.
        assertTrue(empty.goes("left", ToolItem.COLOUR_PANEL, 0))

        val shaped = empty.reshape("left", CellRegion.l(arm = 12, foot = 6))
        assertFalse(shaped.goes("left", ToolItem.COLOUR_PANEL, 0))
        assertTrue(shaped.goes("left", ToolItem.PEN, 0))
    }

    @Test
    fun `move lands where it is asked to when there is room`() {
        val layout = empty.put("top", ToolItem.UNDO, 3)
        val moved = assertNotNull(
            layout.move("top", layout.at("top", 3), "right", layout.at("right", 2))
        )
        assertEquals("right", moved.on(ToolItem.UNDO))
        assertEquals(2, moved.slotOf(ToolItem.UNDO))
    }

    @Test
    fun `a drop onto an occupied cell slides to the nearest room rather than evicting`() {
        val layout = empty
            .put("right", ToolItem.ZOOM_IN, 2)
            .put("top", ToolItem.FIT, 0)
        val moved = assertNotNull(
            layout.move("top", layout.at("top", 0), "right", layout.at("right", 2))
        )
        assertEquals(0, moved.slotOf(ToolItem.FIT))
        assertEquals(2, moved.slotOf(ToolItem.ZOOM_IN), "the one already there did not move")
    }

    @Test
    fun `a drop onto a surface with no room is refused rather than thrown`() {
        // A gesture that lands somewhere full is a normal thing for a hand to
        // do, so the answer is null and the caller keeps what it had.
        val tight = empty.put("top", ToolItem.COLOUR_PANEL, 0)
        val (withBar, id) = tight.addFloating(ToolItem.STATS, here)
        // That bar was made to hold one button plus two spare cells, so a
        // six-cell panel has nowhere to go on it.
        assertNull(withBar.move("top", Cell(0, 0), id))
        assertNull(withBar.move("left", Cell(0, 0), "top"), "nothing in the source cell")
        assertNull(withBar.move("nonesuch", Cell(0, 0), "top"), "no such surface")
    }

    // ---- surfaces that come and go ---------------------------------------

    @Test
    fun `fixate is three ordinary operations, and the first makes a surface`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val surface = assertNotNull(next.surface(id))
        assertEquals(Dock.FLOATING, surface.dock)
        assertEquals(here, surface.spot)
        assertEquals(Cell(0, 0), next.locate(ToolItem.COLOUR_PANEL)?.cell)
        // Sized to the panel plus room to drop something else in beside it.
        assertEquals(ToolItem.COLOUR_PANEL.cellsWide + 2, surface.slotCount)
        assertEquals(1, next.floating.size)
    }

    @Test
    fun `floating surfaces get their own names, and a closed one frees its name`() {
        val (a, first) = empty.addFloating(ToolItem.STATS, here)
        val (b, second) = a.addFloating(ToolItem.CLEAR, here)
        assertEquals("f1", first)
        assertEquals("f2", second)

        val closed = b.closeSurface(first)
        assertNull(closed.surface(first))
        assertNull(closed.locate(ToolItem.STATS), "what was on it went with it")
        assertNotNull(closed.locate(ToolItem.CLEAR), "the other one is untouched")

        val (c, third) = closed.addFloating(ToolItem.FIT, here)
        assertEquals("f1", third, "the freed name is reused rather than climbing forever")
        assertEquals(2, c.floating.size)
    }

    @Test
    fun `an edge cannot be closed, only emptied`() {
        val layout = empty.put("left", ToolItem.PEN, 0)
        val closed = layout.closeSurface("left")
        assertNotNull(closed.surface("left"), "the edge is still there")
        assertTrue(closed.edge(Dock.LEFT).isEmpty)
        assertEquals(4, closed.edges.size)
    }

    @Test
    fun `moving a surface only moves one that floats`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val there = BarSpot(0.1f, 0.9f)
        assertEquals(there, next.moveSurface(id, there).surface(id)?.spot)
        assertNull(next.moveSurface("left", there).surface("left")?.spot, "an edge has no position")
    }

    @Test
    fun `an emptied floating surface is tidied away, an emptied edge is not`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val emptied = next.remove(id, Cell(0, 0))
        assertNotNull(emptied.surface(id), "it survives being empty for as long as the drag might")
        assertNull(emptied.tidied().surface(id))
        assertEquals(4, emptied.tidied().edges.size)
    }

    @Test
    fun `resizing a floating bar resizes the panel that is its whole reason`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val bigger = next.resizeFloating(id, 9, 14)
        val placed = assertNotNull(bigger.surface(id)?.slots?.covering(Cell(0, 0)))
        assertEquals(9, placed.w)
        assertEquals(14, placed.h)
        assertEquals(9, bigger.surface(id)?.slotCount, "the bar is the panel's window")
        assertEquals(14, bigger.surface(id)?.depthCells)
    }

    @Test
    fun `a bar of buttons resizes in length only`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val longer = next.resizeFloating(id, 6, 9)
        assertEquals(6, longer.surface(id)?.slotCount)
        assertEquals(1, longer.surface(id)?.depthCells, "a button is a button however long the bar")
    }

    @Test
    fun `a resize is clamped rather than refused, and an edge cannot be resized`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        assertEquals(2, next.resizeFloating(id, -3, 1).surface(id)?.slotCount)
        assertEquals(24, next.resizeFloating(id, 900, 1).surface(id)?.slotCount)
        assertEquals(
            Dock.LEFT.defaultSlots,
            next.resizeFloating("left", 4, 4).edge(Dock.LEFT).slotCount,
        )
    }

    @Test
    fun `a chosen size follows the control along a bar of the same direction`() {
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val sized = next.resizeFloating(id, 8, 9)
        // Bottom runs the same way as a floating bar, so the size carries.
        val moved = sized.put("bottom", ToolItem.COLOUR_PANEL, 0)
        assertEquals(8, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.w)
        assertEquals(9, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.h)

        // Turning it on its side has nothing sensible to carry, so it goes back
        // to what the catalogue says.
        val turned = sized.put("left", ToolItem.COLOUR_PANEL, 0)
        assertEquals(6, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.w)
        assertEquals(11, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.h)
    }

    @Test
    fun `a resized panel is how deep the placement says, not how deep the catalogue says`() {
        // The one thing the bar's own thickness must be read from. Asking the
        // catalogue instead was a real defect with a visible face: shrinking a
        // fixated colour panel made the card smaller and left the toolbar under
        // it at eleven cells, so the wheel sat in a grey rectangle twice its
        // height. This pins the disagreement the renderer has to resolve.
        val (next, id) = empty.addFloating(ToolItem.COLOUR_PANEL, here)
        val sized = next.resizeFloating(id, 6, 5)
        val surface = assertNotNull(sized.surface(id))

        assertEquals(5, surface.depthCells, "the bar is as deep as its deepest placement")
        assertEquals(5, surface.slots.placements.single().h)
        assertEquals(
            11,
            ToolItem.COLOUR_PANEL.cellsTall,
            "and the catalogue still says eleven, which is why the two must not be confused",
        )
    }

    @Test
    fun `docking a floating bar into an edge empties it and closes it`() {
        val (next, id) = empty.addFloating(ToolItem.STATS, here)
        val withTwo = next.put(id, ToolItem.CLEAR, 1)
        val docked = assertNotNull(withTwo.dockInto(id, "top"))
        assertNull(docked.surface(id), "the bar it came from is gone")
        assertEquals("top", docked.on(ToolItem.STATS))
        assertEquals("top", docked.on(ToolItem.CLEAR))
    }

    @Test
    fun `docking is refused whole when the edge cannot take all of it`() {
        // The panel takes eleven of the right edge's twelve cells, so one of the
        // two controls fits and the other does not. Half a bar arriving is worse
        // than none: the half left behind is on a bar that was about to close.
        val full = empty.put("right", ToolItem.COLOUR_PANEL, 0)
        val (one, id) = full.addFloating(ToolItem.STATS, here)
        val two = one.put(id, ToolItem.CLEAR, 1)

        assertNull(two.dockInto(id, "right"))
        assertEquals(id, two.on(ToolItem.STATS), "and nothing moved")
        assertEquals(id, two.on(ToolItem.CLEAR))

        assertNotNull(two.dockInto(id, "top"), "somewhere with room takes both")
        assertNull(two.dockInto("left", "top"), "an edge is not a bar you can dock")
        assertNull(two.dockInto(id, id))
    }

    @Test
    fun `of keeps the first copy of a duplicated item and drops the rest`() {
        val layout = DockLayout.of(
            listOf(
                surfaceOf(Dock.LEFT, ToolItem.ERASER to Cell(0, 0)),
                surfaceOf(Dock.TOP, ToolItem.ERASER to Cell(5, 0)),
            ),
        )
        // Edges are filled in in Dock.EDGES order, and left comes first.
        assertEquals("left", layout.on(ToolItem.ERASER))
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `contains and locate agree with each other`() {
        val layout = empty.put("left", ToolItem.PENCIL, 1)
        assertTrue(ToolItem.PENCIL in layout)
        assertFalse(ToolItem.PEN in layout)
        assertEquals("left", layout.on(ToolItem.PENCIL))
        assertNull(layout.locate(ToolItem.PEN))
    }

    @Test
    fun `widening keeps every item where it was, on every bar`() {
        val old = DockLayout.of(
            listOf(
                surfaceOf(Dock.LEFT, ToolItem.PEN to Cell(0, 3), length = 4),
                surfaceOf(Dock.TOP, ToolItem.UNDO to Cell(5, 0), length = 6),
            ),
        )
        val grown = old.reshaped {
            CellRegion.strip(maxOf(it.slotCount, it.dock.defaultSlots), it.axis)
        }
        assertEquals(3, grown.slotOf(ToolItem.PEN))
        assertEquals(5, grown.slotOf(ToolItem.UNDO))
        assertEquals(Dock.LEFT.defaultSlots, grown.edge(Dock.LEFT).slotCount)
        assertNotNull(grown.firstFit("left", ToolItem.ERASER), "the new room is usable")
    }

    // ---- shapes ----------------------------------------------------------

    @Test
    fun `reshaping keeps what still fits and drops what does not`() {
        val layout = empty
            .put("bottom", ToolItem.SIZE, 0)
            .put("bottom", ToolItem.SMOOTHING, 4)
            .put("bottom", ToolItem.GRAIN, 8)
        val shorter = layout.reshape("bottom", CellRegion.strip(6, Axis.HORIZONTAL))

        assertEquals(0, shorter.slotOf(ToolItem.SIZE))
        assertNull(shorter.locate(ToolItem.SMOOTHING), "4..7 no longer reaches")
        assertNull(shorter.locate(ToolItem.GRAIN))
    }

    @Test
    fun `an L keeps its arm and its foot and fills round the corner`() {
        val l = CellRegion.l(arm = 6, foot = 5)
        var layout = empty.reshape("left", l).reflow("left", FlowOrder.DOWN_THEN_RIGHT)
        for (item in listOf(
            ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.ERASER,
            ToolItem.COLOUR, ToolItem.LAYERS, ToolItem.ZOOM_IN,
        )) {
            layout = layout.place("left", item, assertNotNull(layout.firstFit("left", item)))
        }
        assertEquals(Cell(0, 0), layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 5), layout.locate(ToolItem.LAYERS)?.cell, "the corner")
        assertEquals(Cell(1, 5), layout.locate(ToolItem.ZOOM_IN)?.cell, "and into the foot")
    }

    @Test
    fun `a slider stands up in the arm and lies flat in the foot`() {
        val l = CellRegion.l(arm = 8, foot = 6)
        val layout = empty.reshape("left", l).reflow("left", FlowOrder.DOWN_THEN_RIGHT)
        assertEquals(1 to 4, layout.surface("left")!!.footprintOf(ToolItem.SIZE, Cell(0, 0)))
        assertEquals(4 to 1, layout.surface("left")!!.footprintOf(ToolItem.SIZE, Cell(2, 7)))
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
            assertEquals("left", starter.on(item), item.id)
        }
        for (item in listOf(ToolItem.UNDO, ToolItem.REDO, ToolItem.EXPORT, ToolItem.STATS)) {
            assertEquals("top", starter.on(item), item.id)
        }
        for (item in listOf(ToolItem.ZOOM_IN, ToolItem.ZOOM_OUT, ToolItem.FIT)) {
            assertEquals("right", starter.on(item), item.id)
        }
        for (item in listOf(ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN)) {
            assertEquals("bottom", starter.on(item), item.id)
        }

        // Every edge still has somewhere for the next control to land, which is
        // the headroom rule Dock.defaultSlots exists for.
        for (dock in Dock.EDGES) {
            assertNotNull(starter.firstFit(dock.id, ToolItem.CLEAR), dock.id)
        }
    }

    @Test
    fun `the starter layout leaves a gap between groups`() {
        // The separators are load-bearing: out of arrange mode an unfilled cell
        // is drawn as a narrow gap, and that gap is the only thing saying that
        // the eraser and the colour are two ideas rather than a run of four.
        assertNull(DockLayout.STARTER.edge(Dock.LEFT).slots.covering(Cell(0, 3)))
        assertNull(DockLayout.STARTER.edge(Dock.TOP).slots.covering(Cell(2, 0)))
    }

    @Test
    fun `the starter layout stands sliders up on a side edge`() {
        // Not in the starter, but the same arithmetic the starter runs: a four
        // cell slider on the left edge is one cell wide and four tall.
        val layout = DockLayout.EMPTY.put("left", ToolItem.SIZE, 0)
        val p = assertNotNull(layout.locate(ToolItem.SIZE)?.placement)
        assertEquals(1, p.w)
        assertEquals(4, p.h)
    }

    // ---- helpers ---------------------------------------------------------

    private fun surfaceOf(
        dock: Dock,
        vararg at: Pair<ToolItem, Cell>,
        length: Int = dock.defaultSlots,
    ): Surface {
        val region = CellRegion.strip(length, dock.axis)
        return Surface(
            dock.id, dock, null, dock.defaultFlow(),
            SurfaceLayout.of(
                region,
                at.map { (item, cell) ->
                    val (w, h) = RegionLayout.naturalSize(item, region, cell)
                    CellPlacement(item, cell.x, cell.y, w, h)
                },
            ),
        )
    }
}
