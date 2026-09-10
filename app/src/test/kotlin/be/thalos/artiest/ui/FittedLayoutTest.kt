package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A workspace on somebody else's screen.
 *
 * **Clamp, overflow, say so**, in that order. A shape made on a tablet loses
 * the cells a phone does not have; whatever was standing on them is handed back
 * rather than lost; and it comes home whole when the tablet does.
 *
 * The one that matters most is the identity check: when nothing has to be cut,
 * the answer is the *same instance* that went in. That is what makes "a layout
 * that fits both ways round does not move" true rather than merely likely — a
 * rotation that changes nothing recomposes nothing.
 */
class FittedLayoutTest {

    private val tablet = 24 to 12
    private val phone = 12 to 8

    private fun DockLayout.on(grid: Pair<Int, Int>) = fittedTo(grid.first, grid.second)

    @Test
    fun `a layout that fits is not touched, and is not even copied`() {
        val layout = DockLayout.STARTER
        val fitted = layout.on(tablet)
        assertSame(layout, fitted.layout, "the same instance, so nothing recomposes")
        assertTrue(fitted.isWhole)
    }

    @Test
    fun `rotate and rotate back gives the original, exactly`() {
        // The clamp is a picture of the layout and never the layout, so a
        // rotation is always applied to the whole thing rather than to the last
        // picture of it. Chaining one clamped result into the next is precisely
        // the mistake this design exists to make impossible, and the assertion
        // below is the difference: what is rotated back is `layout`, which was
        // never cut, and it comes back the same instance.
        val layout = DockLayout.STARTER

        val portrait = layout.on(12 to 24).layout
        assertEquals(12, portrait.edge(Dock.TOP).slotCount, "cut for the picture")
        assertEquals(24, layout.edge(Dock.TOP).slotCount, "and not for the layout")

        assertSame(layout, layout.on(24 to 12).layout, "so going back is going back")
    }

    @Test
    fun `what a narrow screen hides comes back when the screen is wide again`() {
        val layout = DockLayout.STARTER
        // Eight by eight cells: 352dp square, which is a small phone. The grain
        // slider sits at cells 8..11 of the bottom bar and has nowhere to be.
        assertEquals(listOf(ToolItem.GRAIN), layout.on(8 to 8).overflow["bottom"])
        assertTrue(layout.on(tablet).isWhole)
        assertEquals(Cell(8, 0), layout.on(tablet).layout.locate(ToolItem.GRAIN)?.cell)
    }

    @Test
    fun `a twenty-four cell bar on a twelve cell grid places twelve and overflows the rest`() {
        val layout = DockLayout.EMPTY
            .place("bottom", ToolItem.SIZE, Cell(0, 0))
            .place("bottom", ToolItem.SMOOTHING, Cell(4, 0))
            .place("bottom", ToolItem.GRAIN, Cell(8, 0))
            .place("bottom", ToolItem.ERASER_SIZE, Cell(12, 0))
            .place("bottom", ToolItem.OPACITY, Cell(16, 0))

        val fitted = layout.on(phone)
        val bottom = fitted.layout.edge(Dock.BOTTOM)

        assertEquals(12, bottom.slotCount)
        assertEquals(
            listOf(ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN),
            bottom.slots.placements.map { it.item },
        )
        assertEquals(
            listOf(ToolItem.ERASER_SIZE, ToolItem.OPACITY),
            fitted.overflow["bottom"],
            "in flow order, so the chevron lists the same things every time",
        )
    }

    @Test
    fun `the overflow is per toolbar, so the chevron is on the right one`() {
        val layout = DockLayout.EMPTY
            .place("bottom", ToolItem.SIZE, Cell(20, 0))
            .place("top", ToolItem.STATS, Cell(0, 0))
        val fitted = layout.on(phone)
        assertEquals(listOf(ToolItem.SIZE), fitted.overflow["bottom"])
        assertEquals(null, fitted.overflow["top"], "nothing fell off the top")
    }

    @Test
    fun `an L on a shorter screen keeps its foot on the bottom, not its head at the top`() {
        // The foot is the part you reach. Clamping cuts cells off the far end
        // of the shape, and for an anchored surface the far end is the one
        // nearest the middle of the screen — so what is kept is the corner.
        val layout = DockLayout.EMPTY.reshape("left", CellRegion.l(arm = 12, foot = 8))
        val small = layout.on(4 to 12).layout.edge(Dock.LEFT).region

        assertTrue(small.contains(0, 11), "the corner is still there")
        assertTrue(small.contains(3, 11), "and some of the foot")
        assertTrue(!small.contains(4, 11), "and no more than the screen has")
        assertTrue(small.contains(0, 0), "the arm is whole, it was only one cell wide")
    }

    @Test
    fun `nothing is lost from the layout itself, only from the picture of it`() {
        val layout = DockLayout.EMPTY
            .place("bottom", ToolItem.SIZE, Cell(20, 0))
        val fitted = layout.on(phone)

        assertEquals(null, fitted.layout.locate(ToolItem.SIZE), "not on the screen")
        assertEquals(Cell(20, 0), layout.locate(ToolItem.SIZE)?.cell, "still in the layout")
    }

    @Test
    fun `a floating surface's position survives a change of screen`() {
        val (layout, id) = DockLayout.EMPTY.addFloating(ToolItem.STATS, BarSpot(0.8f, 0.9f))
        for (grid in listOf(tablet, phone, 8 to 6)) {
            assertEquals(
                BarSpot(0.8f, 0.9f),
                layout.on(grid).layout.surface(id)?.spot,
                grid.toString(),
            )
        }
    }

    @Test
    fun `a grid nobody could draw on changes nothing rather than emptying everything`() {
        val layout = DockLayout.STARTER
        assertSame(layout, layout.fittedTo(0, 0).layout)
        assertSame(layout, layout.fittedTo(-3, 9).layout)
    }
}
