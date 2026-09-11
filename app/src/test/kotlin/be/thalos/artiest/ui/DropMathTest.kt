package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val gridW = 28
    private val gridH = 18

    private fun at(x: Float, y: Float) = Offset(x, y)

    private fun cellAt(p: Offset) = DropMath.cellAt(p, slot, gridW, gridH)

    @Test
    fun `a cell and its origin are inverses of each other`() {
        for (cell in CellRegion.l(arm = 6, foot = 5).cells(FlowOrder.RIGHT_THEN_DOWN)) {
            val origin = DropMath.originOf(cell, slot)
            assertEquals(cell, cellAt(origin), "top-left of $cell")
            assertEquals(
                cell,
                cellAt(origin + at(slot - 1f, slot - 1f)),
                "bottom-right of $cell",
            )
        }
    }

    @Test
    fun `every cell of the grid is the cell it is drawn at`() {
        // The whole of what the grid bought: there is one frame, and a cell is
        // where the renderer puts it. There used to be a per-surface run origin
        // reported by the layout pass, and every term in it was a chance to be
        // a few cells out.
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val cell = Cell(x, y)
                assertEquals(cell, cellAt(DropMath.originOf(cell, slot) + at(1f, 1f)))
            }
        }
    }

    @Test
    fun `a point off the grid is not the nearest cell`() {
        // Null rather than clamped: a point past the last column is not in the
        // last column, and answering with it invents a target nobody can see.
        assertNull(cellAt(at(-1f, 0f)))
        assertNull(cellAt(at(0f, -1f)))
        assertNull(cellAt(at(gridW * slot, 0f)))
        assertNull(cellAt(at(0f, gridH * slot)))
        assertEquals(Cell(gridW - 1, gridH - 1), cellAt(at(gridW * slot - 1f, gridH * slot - 1f)))
    }

    @Test
    fun `a footprint is exactly its cells and no padding`() {
        assertEquals(Offset(4 * slot, slot), DropMath.sizeOf(4, 1, slot))
        assertEquals(Offset(6 * slot, 11 * slot), DropMath.sizeOf(6, 11, slot))
    }

    @Test
    fun `nothing is answered before the first layout pass`() {
        assertNull(DropMath.cellAt(Offset.Zero, 0f, gridW, gridH), "no density yet")
    }

    // ---- fixate -------------------------------------------------------------

    @Test
    fun `a fixated panel lands clear of the toolbar its button is on`() {
        val w = (gridW * slot).toInt()
        val h = (gridH * slot).toInt()
        // A button on the left of the screen: the card goes to its right.
        val fromLeft = DropMath.cellBeside(at(slot, 6 * slot), w, h, slot)
        assertTrue(fromLeft.x > 1, "it landed back on the toolbar at $fromLeft")

        // A button on the right: towards the middle, because further right is
        // off the screen and clamps back onto the bar it came from.
        val fromRight = DropMath.cellBeside(at(w - slot, 6 * slot), w, h, slot)
        assertTrue(fromRight.x < gridW - 2, "it landed back on the toolbar at $fromRight")
    }

    @Test
    fun `a fixated panel is never off the grid`() {
        val w = (gridW * slot).toInt()
        val h = (gridH * slot).toInt()
        for (x in 0..gridW) {
            for (y in 0..gridH) {
                val cell = DropMath.cellBeside(at(x * slot, y * slot), w, h, slot)
                assertTrue(cell.x in 0 until gridW && cell.y in 0 until gridH, "$cell")
            }
        }
        assertEquals(Cell(0, 0), DropMath.cellBeside(Offset.Zero, 0, 0, slot), "no window yet")
    }
}
