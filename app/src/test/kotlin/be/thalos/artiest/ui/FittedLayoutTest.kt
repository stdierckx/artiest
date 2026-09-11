package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A workspace on somebody else's screen.
 *
 * **Clamp, overflow, say so**, in that order. A shape drawn on a tablet loses
 * the cells a phone does not have; whatever was standing on them is handed back
 * rather than lost; and it comes home whole when the tablet does.
 *
 * Absolute cells added a step in front of the cut: **shift, then cut.** A
 * toolbar against the right edge of a wide screen is whole on a narrow one, only
 * further in, and only what is still off the grid after the shift is lost. The
 * old model had an edge to re-hang a bar on; this one has arithmetic.
 *
 * The one that matters most is still the identity check: when nothing has to be
 * cut, the answer is the *same instance* that went in. That is what makes "a
 * layout that fits both ways round does not move" true rather than merely
 * likely — a rotation that changes nothing recomposes nothing.
 */
class FittedLayoutTest {

    private val tablet = 24 to 12
    private val phone = 12 to 8

    private fun DockLayout.on(grid: Pair<Int, Int>) = fittedTo(grid.first, grid.second)

    /** A plain bar of [length] cells with its corner at [at], holding [items]. */
    private fun bar(
        id: String,
        at: Cell,
        length: Int,
        axis: Axis = Axis.HORIZONTAL,
        vararg items: Pair<ToolItem, Cell>,
    ): Surface {
        val region = CellRegion.strip(length, axis).translated(at.x, at.y)
        return Surface(
            id = id,
            flow = FlowOrder.along(axis),
            slots = SurfaceLayout.of(
                region,
                items.map { (item, cell) ->
                    val (w, h) = RegionLayout.naturalSize(item, region, cell)
                    CellPlacement(item, cell.x, cell.y, w, h)
                },
            ),
        )
    }

    private val starter get() = DockLayout.STARTER.settled(tablet.first, tablet.second)

    @Test
    fun `a layout that fits is not touched, and is not even copied`() {
        val layout = starter
        val fitted = layout.on(tablet)
        assertSame(layout, fitted.layout, "the same instance, so nothing recomposes")
        assertTrue(fitted.isWhole)
    }

    @Test
    fun `a toolbar off the right edge slides in rather than losing its end`() {
        val layout = DockLayout.of(
            listOf(bar("a", Cell(16, 2), 8, Axis.HORIZONTAL, ToolItem.SIZE to Cell(20, 2))),
        )
        val fitted = layout.on(20 to 12)
        assertTrue(fitted.isWhole, "lost ${fitted.overflow}")
        assertEquals(Cell(12, 2), fitted.layout.surface("a")?.origin)
        assertEquals(Cell(16, 2), fitted.layout.locate(ToolItem.SIZE)?.cell, "and what is on it")
    }

    @Test
    fun `what will not fit even at the edge is cut, and handed back`() {
        val layout = DockLayout.of(
            listOf(
                bar(
                    "a", Cell(0, 0), 20, Axis.HORIZONTAL,
                    ToolItem.SIZE to Cell(0, 0),
                    ToolItem.SMOOTHING to Cell(4, 0),
                    ToolItem.GRAIN to Cell(8, 0),
                    ToolItem.ERASER_SIZE to Cell(12, 0),
                    ToolItem.OPACITY to Cell(16, 0),
                ),
            ),
        )
        val fitted = layout.on(phone)
        val kept = assertNotNull(fitted.layout.surface("a"))

        assertEquals(12, kept.region.cellCount)
        assertEquals(
            listOf(ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN),
            kept.slots.placements.map { it.item },
        )
        assertEquals(
            listOf(ToolItem.ERASER_SIZE, ToolItem.OPACITY),
            fitted.overflow["a"],
            "in flow order, so the chevron lists the same things every time",
        )
    }

    @Test
    fun `rotate and rotate back gives the original, exactly`() {
        // The clamp is a picture of the layout and never the layout, so a
        // rotation is always applied to the whole thing rather than to the last
        // picture of it. Chaining one clamped result into the next is precisely
        // the mistake this design exists to make impossible.
        val layout = DockLayout.of(listOf(bar("a", Cell(0, 0), 24)))

        val portrait = layout.on(12 to 24).layout
        assertEquals(12, portrait.surface("a")?.region?.cellCount, "cut for the picture")
        assertEquals(24, layout.surface("a")?.region?.cellCount, "and not for the layout")

        assertSame(layout, layout.on(24 to 12).layout, "so going back is going back")
    }

    @Test
    fun `the overflow is per toolbar, so the chevron is on the right one`() {
        val layout = DockLayout.of(
            listOf(
                bar("a", Cell(0, 0), 24, Axis.HORIZONTAL, ToolItem.SIZE to Cell(20, 0)),
                bar("b", Cell(0, 2), 2, Axis.HORIZONTAL, ToolItem.STATS to Cell(0, 2)),
            ),
        )
        val fitted = layout.on(phone)
        assertEquals(listOf(ToolItem.SIZE), fitted.overflow["a"])
        assertNull(fitted.overflow["b"], "nothing fell off the other one")
    }

    @Test
    fun `a shape too big for the screen slides in, and only then loses cells`() {
        // Twelve cells of arm and eight of foot, drawn four rows down. On a
        // four-by-ten grid the shift takes the whole thing back to the top, and
        // what is cut after that is whatever is still past the far edge.
        val l = CellRegion.l(arm = 12, foot = 8).translated(0, 4)
        val layout = DockLayout.of(
            listOf(Surface("a", FlowOrder.DOWN_THEN_RIGHT, SurfaceLayout.empty(l))),
        )
        val small = assertNotNull(layout.on(4 to 10).layout.surface("a")).region

        assertTrue(small.contains(0, 0), "the shift put the top of the arm on screen")
        assertTrue(small.contains(0, 9), "and it runs to the bottom of the glass")
        assertTrue(!small.contains(0, 10), "and no further")
        assertEquals(10, small.cellCount, "ten cells of arm, and the foot is past the edge")
    }

    @Test
    fun `nothing is lost from the layout itself, only from the picture of it`() {
        val layout = DockLayout.of(
            listOf(bar("a", Cell(0, 0), 24, Axis.HORIZONTAL, ToolItem.SIZE to Cell(20, 0))),
        )
        val fitted = layout.on(phone)

        assertNull(fitted.layout.locate(ToolItem.SIZE), "not on the screen")
        assertEquals(Cell(20, 0), layout.locate(ToolItem.SIZE)?.cell, "still in the layout")
    }

    @Test
    fun `a grid nobody could draw on changes nothing rather than emptying everything`() {
        val layout = starter
        assertSame(layout, layout.fittedTo(0, 0).layout)
        assertSame(layout, layout.fittedTo(-3, 9).layout)
    }
}
