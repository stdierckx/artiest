package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor

/**
 * Turning a point into a cell, and a cell back into a point.
 *
 * A few lines of arithmetic, in a file of their own, for one reason: they are
 * the inverse of how the renderer places a surface, and if the two ever
 * disagree then a drop lands somewhere the user did not aim and **nothing says
 * why**. Having them here means they can be tested on the JVM, against the same
 * numbers the renderer uses, without a single line of Compose.
 *
 * The identity they invert is stated once and holds everywhere:
 *
 * > A cell is `1 × Chrome.SLOT`. Cell `x, y` of the screen has its corner at
 * > `x × SLOT, y × SLOT` from the chrome's own origin, and a placement of *w*
 * > by *h* cells is `w × SLOT` by `h × SLOT`. There are no exceptions and there
 * > is no padding inside the grid.
 *
 * That identity is what the grid bought. There used to be a per-surface run
 * origin reported by `onGloballyPositioned`, because a bar's position depended
 * on which edge it was docked to, how long it was and how far it had been
 * scrolled — every one of those a term added back by hand, and every one of
 * them a chance to be a few cells out. A surface is now at the cells it was
 * drawn on, so there is one frame and nothing to add back.
 */
object DropMath {

    /**
     * Which cell of a [gridW] by [gridH] grid the point at [local] falls in.
     *
     * Null off the grid, rather than the nearest cell: a point past the last
     * column is not in the last column, and answering with it would invent a
     * target the user cannot see.
     */
    fun cellAt(local: Offset, slotPx: Float, gridW: Int, gridH: Int): Cell? {
        if (slotPx <= 0f) return null
        val x = floor(local.x / slotPx).toInt()
        val y = floor(local.y / slotPx).toInt()
        if (x !in 0 until gridW || y !in 0 until gridH) return null
        return Cell(x, y)
    }

    /** Where the top-left corner of [cell] sits, from the chrome's origin. */
    fun originOf(cell: Cell, slotPx: Float): Offset = Offset(cell.x * slotPx, cell.y * slotPx)

    /** How big a [w] by [h] footprint is, in pixels. */
    fun sizeOf(w: Int, h: Int, slotPx: Float): Offset = Offset(w * slotPx, h * slotPx)

    /**
     * Where a panel fixated from a button at [anchor] should put its corner.
     *
     * What *"fixate puts it in the neighbourhood of where it was opened"* comes
     * to. Far enough across to clear the toolbar the button is on: the button is
     * usually on one, and a new surface landing on top of it is in the
     * neighbourhood in the least useful sense. Towards the middle rather than
     * always to the right, because a button on the right of the screen pushed
     * further right lands off it.
     */
    fun cellBeside(anchor: Offset, w: Int, h: Int, slotPx: Float): Cell {
        if (slotPx <= 0f || w <= 0 || h <= 0) return Cell(0, 0)
        val gridW = (w / slotPx).toInt().coerceAtLeast(1)
        val gridH = (h / slotPx).toInt().coerceAtLeast(1)
        val inward = if (anchor.x > w / 2f) -CLEAR_OF_THE_BAR else CLEAR_OF_THE_BAR
        val x = floor((anchor.x + inward) / slotPx).toInt().coerceIn(0, gridW - 1)
        val y = floor((anchor.y + slotPx) / slotPx).toInt().coerceIn(0, gridH - 1)
        return Cell(x, y)
    }

    /** One toolbar's thickness and then some, in pixels at a tablet's density. */
    private const val CLEAR_OF_THE_BAR = 190f
}
