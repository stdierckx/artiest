package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shaping a toolbar: the presets, the painting, and Tidy.
 *
 * Shape editing is gesture code, so what is tested is the pure part of it — a
 * list of points goes in and a shape comes out. That is worth more than any
 * amount of mocked Compose, and it is where the mistakes are: an off-by-one in
 * the cell arithmetic is a shape the user did not draw, and they will read it
 * as the app being wrong rather than their hand.
 */
class SurfaceShapeTest {

    private val slot = 44f

    /** A stroke, as the points a pen would report along it. */
    private fun stroke(from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int = 24) =
        (0..steps).map { i ->
            val t = i / steps.toFloat()
            Offset(
                (from.first + (to.first - from.first) * t) * slot,
                (from.second + (to.second - from.second) * t) * slot,
            )
        }

    private fun paint(
        start: Set<Cell> = emptySet(),
        board: Pair<Int, Int> = 10 to 12,
        erasing: Boolean = false,
        points: List<Offset>,
    ): Set<Cell> {
        var cells = start
        for (p in points) cells = cells.paint(p, slot, board.first, board.second, erasing)
        return cells
    }

    // ---- the presets -------------------------------------------------------

    @Test
    fun `every preset is the shape it is named after, on every edge`() {
        for (dock in Dock.EDGES) {
            for (shape in SurfaceShape.entries) {
                val region = shape.regionFor(dock)
                assertTrue(region.cellCount > 0, "${dock.id} ${shape.label}")
                assertEquals(
                    shape,
                    SurfaceShape.of(region, dock),
                    "${dock.id} ${shape.label} does not recognise itself",
                )
            }
        }
    }

    @Test
    fun `a bar preset is exactly the bar that edge has always had`() {
        for (dock in Dock.EDGES) {
            assertEquals(dock.defaultRegion(), SurfaceShape.BAR.regionFor(dock), dock.id)
        }
    }

    @Test
    fun `an L points its corner into the corner it sits in`() {
        // On the left, the arm is down the left and the foot along the bottom.
        val left = SurfaceShape.L.regionFor(Dock.LEFT)
        assertTrue(left.contains(0, 0), "the top of the arm")
        assertTrue(left.contains(0, left.bounds.bottom - 1), "the corner")
        assertTrue(left.contains(left.bounds.right - 1, left.bounds.bottom - 1), "the toe")
        assertTrue(!left.contains(left.bounds.right - 1, 0), "and nothing at the far top")

        // On the right it is mirrored, so the arm is down the right.
        val right = SurfaceShape.L.regionFor(Dock.RIGHT)
        assertTrue(right.contains(right.bounds.right - 1, 0), "the top of the arm")
        assertTrue(!right.contains(0, 0))
        assertEquals(left.cellCount, right.cellCount, "the same L, turned over")

        // On the top it is flipped, so the long bar is at the top.
        val top = SurfaceShape.L.regionFor(Dock.TOP)
        assertTrue(top.contains(top.bounds.right - 1, 0), "the far end of the bar")
        assertTrue(top.contains(0, 0))
    }

    @Test
    fun `a shape nobody named is not mistaken for a preset`() {
        assertNull(SurfaceShape.of(CellRegion.block(3, 3), Dock.LEFT))
        assertNull(SurfaceShape.of(CellRegion.EMPTY, Dock.LEFT))
    }

    @Test
    fun `changing shape keeps what still fits and drops what does not`() {
        val bar = DockLayout.EMPTY
            .place("left", ToolItem.PEN, Cell(0, 0))
            .place("left", ToolItem.PENCIL, Cell(0, 1))
            .place("left", ToolItem.ERASER, Cell(0, 9))

        // An L on the left is a twelve-cell arm and a foot, so the whole of a
        // twelve-cell bar is still inside it and nothing moves at all.
        val l = bar.reshape("left", SurfaceShape.L.regionFor(Dock.LEFT))
        assertEquals(Cell(0, 0), l.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), l.locate(ToolItem.PENCIL)?.cell)
        assertEquals(Cell(0, 9), l.locate(ToolItem.ERASER)?.cell)

        // A block is five wide and four tall, so the one down at nine is not in
        // it any more. It is dropped rather than moved: a control that turns up
        // somewhere new is worse than one you have to put back.
        val block = bar.reshape("left", SurfaceShape.BLOCK.regionFor(Dock.LEFT))
        assertEquals(Cell(0, 0), block.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), block.locate(ToolItem.PENCIL)?.cell)
        assertNull(block.locate(ToolItem.ERASER))

        // And going back to a bar is one tap, with nothing that survived moved.
        val back = l.reshape("left", SurfaceShape.BAR.regionFor(Dock.LEFT))
        assertEquals(bar.edge(Dock.LEFT).slots.placements, back.edge(Dock.LEFT).slots.placements)
    }

    // ---- Tidy --------------------------------------------------------------

    @Test
    fun `tidy closes the gaps in flow order`() {
        val layout = DockLayout.EMPTY
            .place("bottom", ToolItem.PEN, Cell(3, 0))
            .place("bottom", ToolItem.SIZE, Cell(8, 0))
            .place("bottom", ToolItem.PENCIL, Cell(15, 0))
        val tidy = layout.tidy("bottom")

        assertEquals(Cell(0, 0), tidy.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(1, 0), tidy.locate(ToolItem.SIZE)?.cell)
        assertEquals(Cell(5, 0), tidy.locate(ToolItem.PENCIL)?.cell)
        assertEquals(3, tidy.edge(Dock.BOTTOM).slots.placements.size, "and nothing was lost")
    }

    @Test
    fun `tidy round the corner of an L keeps everything`() {
        var layout = DockLayout.EMPTY
            .reshape("left", SurfaceShape.L.regionFor(Dock.LEFT))
            .reflow("left", FlowOrder.DOWN_THEN_RIGHT)
        for (item in listOf(ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.SIZE)) {
            layout = layout.place("left", item, assertNotNull(layout.firstFit("left", item)))
        }
        val before = layout.all().map { it.item }.toSet()
        val tidy = layout.tidy("left")
        assertEquals(before, tidy.all().map { it.item }.toSet())
    }

    @Test
    fun `tidy on an empty surface, or one that cannot be re-packed, changes nothing`() {
        val empty = DockLayout.EMPTY
        assertEquals(empty, empty.tidy("left"))
        assertEquals(empty, empty.tidy("nonesuch"))
    }

    @Test
    fun `tidy is one action, so doing it twice changes nothing the second time`() {
        val layout = DockLayout.EMPTY
            .place("top", ToolItem.UNDO, Cell(4, 0))
            .place("top", ToolItem.REDO, Cell(9, 0))
        val once = layout.tidy("top")
        assertEquals(once, once.tidy("top"))
    }

    // ---- drawing the shape -------------------------------------------------

    @Test
    fun `a stroke down and then across paints an L`() {
        val down = stroke(0.5f to 0.5f, 0.5f to 5.5f)
        val across = stroke(0.5f to 5.5f, 4.5f to 5.5f)
        val cells = paint(points = down + across)
        val region = CellRegion.ofCells(cells)

        assertEquals(listOf(CellRect(0, 0, 1, 5), CellRect(0, 5, 5, 1)), region.rects)
        assertEquals(10, region.cellCount)
    }

    @Test
    fun `the eraser end takes cells away and the pen puts them back`() {
        val block = CellRegion.block(4, 3).cellSet()
        val rubbed = paint(start = block, erasing = true, points = stroke(0.5f to 1.5f, 3.5f to 1.5f))
        assertEquals(8, rubbed.size, "the middle row is gone")
        assertEquals(
            listOf(CellRect(0, 0, 4, 1), CellRect(0, 2, 4, 1)),
            CellRegion.ofCells(rubbed).rects,
        )

        val back = paint(start = rubbed, points = stroke(0.5f to 1.5f, 3.5f to 1.5f))
        assertEquals(block, back)
    }

    @Test
    fun `a stroke that leaves the board paints nothing outside it`() {
        val cells = paint(
            board = 3 to 3,
            points = stroke(-4.5f to 1.5f, 9.5f to 1.5f, steps = 60),
        )
        assertEquals(setOf(Cell(0, 1), Cell(1, 1), Cell(2, 1)), cells)
    }

    @Test
    fun `painting the same cell twice is one cell`() {
        val cells = paint(points = stroke(1.2f to 1.2f, 1.8f to 1.8f))
        assertEquals(setOf(Cell(1, 1)), cells)
    }

    @Test
    fun `the preview is stable while the stroke is still moving`() {
        // The property `ofCells` exists for: the outline must not shimmer under
        // a pen that has not lifted. Painting a bar one cell at a time, the
        // rectangle already emitted never changes, it only grows.
        var cells = emptySet<Cell>()
        var previous: List<CellRect> = emptyList()
        for (x in 0 until 6) {
            cells = cells.paint(Offset((x + 0.5f) * slot, 0.5f * slot), slot, 10, 12, false)
            val rects = CellRegion.ofCells(cells).rects
            assertEquals(1, rects.size, "one run stays one rectangle")
            if (previous.isNotEmpty()) {
                assertEquals(previous[0].x, rects[0].x, "its start does not move")
                assertEquals(previous[0].y, rects[0].y)
            }
            previous = rects
        }
        assertEquals(listOf(CellRect(0, 0, 6, 1)), previous)
    }

    @Test
    fun `a drawn shape comes back to its own origin`() {
        // The rule the editor states out loud: you choose the shape, the edge
        // chooses where it sits. A shape painted away from the corner is not
        // kept away from it.
        val cells = paint(points = stroke(4.5f to 6.5f, 7.5f to 6.5f))
        val region = CellRegion.ofCells(cells).atOrigin()
        assertEquals(listOf(CellRect(0, 0, 4, 1)), region.rects)
    }
}
