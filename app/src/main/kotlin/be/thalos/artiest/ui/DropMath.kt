package be.thalos.artiest.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor

/**
 * Turning a point into a cell, and a cell back into a point.
 *
 * Four lines of arithmetic, in a file of their own, for one reason: they are
 * the inverse of how the renderer sizes a cell, and if the two ever disagree
 * then a drop lands somewhere the user did not aim and **nothing says why**.
 * Having them here means they can be tested on the JVM, against the same
 * numbers the renderer uses, without a single line of Compose.
 *
 * The identity they invert is stated once and holds everywhere:
 *
 * > A cell is `1 × Chrome.SLOT`, and a placement of *w* by *h* cells is
 * > `w × SLOT` by `h × SLOT`. There are no exceptions and there is no padding
 * > inside the grid.
 *
 * Everything is measured from the **run's own origin**, which the run reports
 * from inside its own scroll container. That is what keeps a scroll offset, a
 * bar's padding and a grip sitting in front of the cells out of this file
 * entirely — every one of those was once a term added back by hand, and every
 * one of them was a chance to be a few cells out.
 */
object DropMath {

    /**
     * Which cell of [region] the point at [local] falls in, or null.
     *
     * **A bar and a shape are read differently, and they have to be.**
     *
     * A bar is a line. Across it the run is as thick as its deepest item, so a
     * drop halfway down a left edge carrying a colour wheel is still a drop on
     * the left edge — only the distance *along* the bar means anything, and a
     * point past the end is clamped to the end rather than refused, because the
     * caller has a better answer for that than this does (`firstFit`).
     *
     * A shape is two-dimensional and its notch is not part of it. A point in
     * the hollow of an L is not on the L, and answering with the nearest cell
     * would be inventing a target the user cannot see.
     */
    fun cellAt(region: CellRegion, local: Offset, slotPx: Float): Cell? {
        if (slotPx <= 0f || region.isEmpty) return null
        val b = region.bounds
        val axis = region.stripAxis
        if (axis != null) {
            val along = if (axis == Axis.HORIZONTAL) local.x else local.y
            val n = floor(along / slotPx).toInt().coerceAtLeast(0)
            return if (axis == Axis.HORIZONTAL) Cell(b.x + n, b.y) else Cell(b.x, b.y + n)
        }
        val cell = Cell(
            b.x + floor(local.x / slotPx).toInt(),
            b.y + floor(local.y / slotPx).toInt(),
        )
        return if (cell in region) cell else null
    }

    /** Where the top-left corner of [cell] sits, relative to the run's origin. */
    fun originOf(region: CellRegion, cell: Cell, slotPx: Float): Offset {
        val b = region.bounds
        return Offset((cell.x - b.x) * slotPx, (cell.y - b.y) * slotPx)
    }

    /** How big a [w] by [h] footprint is, in pixels. */
    fun sizeOf(w: Int, h: Int, slotPx: Float): Offset = Offset(w * slotPx, h * slotPx)
}
