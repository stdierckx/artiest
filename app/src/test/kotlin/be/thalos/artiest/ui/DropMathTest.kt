package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The drop arithmetic, and the identity it inverts.
 *
 * This is the only part of the renderer that can be tested without a device,
 * and it is the part where being wrong is silent: a drop that lands one cell
 * out looks like a user's mistake, not like a bug. So the round trip is
 * asserted in both directions and at both edges of every cell.
 *
 * `Offset` comes from Compose but is a pair of floats with no Android behind
 * it, so this still runs in a plain JVM — which is the condition
 * `docs/ui-expansion-plan.md` sets for this file existing at all.
 */
class DropMathTest {

    private val slot = 44f

    private fun at(x: Float, y: Float) = Offset(x, y)

    @Test
    fun `a cell and its origin are inverses of each other`() {
        val region = CellRegion.l(arm = 6, foot = 5)
        for (cell in region.cells(FlowOrder.RIGHT_THEN_DOWN)) {
            val origin = DropMath.originOf(region, cell, slot)
            assertEquals(cell, DropMath.cellAt(region, origin, slot), "top-left of $cell")
            assertEquals(
                cell,
                DropMath.cellAt(region, origin + at(slot - 1f, slot - 1f), slot),
                "bottom-right of $cell",
            )
        }
    }

    @Test
    fun `a bar reads only the distance along it`() {
        val bottom = CellRegion.strip(24, Axis.HORIZONTAL)
        assertEquals(Cell(0, 0), DropMath.cellAt(bottom, at(0f, 0f), slot))
        assertEquals(Cell(3, 0), DropMath.cellAt(bottom, at(3.5f * slot, 0f), slot))
        // Halfway down a bar carrying a colour wheel is still that bar's fourth
        // cell. A bar is a line; across it there is nothing to say.
        assertEquals(Cell(3, 0), DropMath.cellAt(bottom, at(3.5f * slot, 7f * slot), slot))

        val left = CellRegion.strip(12, Axis.VERTICAL)
        assertEquals(Cell(0, 5), DropMath.cellAt(left, at(0f, 5.2f * slot), slot))
        assertEquals(Cell(0, 5), DropMath.cellAt(left, at(4f * slot, 5.2f * slot), slot))
    }

    @Test
    fun `a point past the end of a bar is the caller's problem, not this one's`() {
        // Clamped at nought and not at the far end: past the end, `firstFit` has
        // a better answer than the nearest cell does, and it is the one the drop
        // path already falls back to.
        val bar = CellRegion.strip(6, Axis.HORIZONTAL)
        assertEquals(Cell(0, 0), DropMath.cellAt(bar, at(-90f, 0f), slot))
        assertEquals(Cell(9, 0), DropMath.cellAt(bar, at(9.5f * slot, 0f), slot))
    }

    @Test
    fun `the notch of an L is not on the L`() {
        val l = CellRegion.l(arm = 6, foot = 5)
        // Cell 3,2 is in the bounding box and in the hollow of the shape.
        assertNull(DropMath.cellAt(l, at(3.5f * slot, 2.5f * slot), slot))
        assertEquals(Cell(0, 2), DropMath.cellAt(l, at(0.5f * slot, 2.5f * slot), slot))
        assertEquals(Cell(3, 5), DropMath.cellAt(l, at(3.5f * slot, 5.5f * slot), slot))
    }

    @Test
    fun `a shape reads from its own bounds, not from the origin of the grid`() {
        // A shape whose top-left cell is 4,3. Its first cell is at pixel nought.
        val away = CellRegion.of(CellRect(4, 3, 2, 2))
        assertEquals(Offset.Zero, DropMath.originOf(away, Cell(4, 3), slot))
        assertEquals(Cell(4, 3), DropMath.cellAt(away, at(1f, 1f), slot))
        assertEquals(Cell(5, 4), DropMath.cellAt(away, at(1.5f * slot, 1.5f * slot), slot))
    }

    @Test
    fun `a footprint is exactly its cells and no padding`() {
        assertEquals(Offset(4 * slot, slot), DropMath.sizeOf(4, 1, slot))
        assertEquals(Offset(6 * slot, 11 * slot), DropMath.sizeOf(6, 11, slot))
    }

    @Test
    fun `nothing is answered before the first layout pass`() {
        val bar = CellRegion.strip(6, Axis.HORIZONTAL)
        assertNull(DropMath.cellAt(bar, Offset.Zero, 0f), "no density yet")
        assertNull(DropMath.cellAt(CellRegion.EMPTY, Offset.Zero, slot))
    }
}
