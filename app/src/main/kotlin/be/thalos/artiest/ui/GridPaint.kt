package be.thalos.artiest.ui

/**
 * Drawing a toolbar, as arithmetic.
 *
 * The pen paints cells and what is under it when the hand lifts is a surface.
 * That sentence is the whole feature, and this is the half of it that has no
 * Compose in it — which is what lets the awkward cases be argued about in a
 * plain JVM test rather than discovered on a tablet.
 *
 * ## One stroke, one surface
 *
 * **A stroke belongs to the surface it started on.** Start on a toolbar and you
 * are growing that toolbar; start on bare grid and you are making a new one.
 * Cells another surface already owns are skipped for the length of the stroke,
 * so two toolbars can be drawn touching and neither eats the other — which is
 * the overlap rule `docs/ui-expansion-plan.md` wanted at shape time and could
 * not have while a surface's position was a dock rather than a cell.
 *
 * It is deliberately not *the surface nearest the pen* or *whichever surface the
 * stroke ends on*. Both of those change their mind halfway through a gesture,
 * and a gesture that changes its mind is one you cannot aim.
 *
 * ## Nothing is applied until the hand lifts
 *
 * A [Stroke] is collected as a set of cells and turned into a layout once, by
 * [applyTo]. Painting straight into the layout would mean a reshape, a
 * re-coalesce and a write to `SharedPreferences` on every pointer event, and the
 * preview would be the thing being saved.
 *
 * The preview itself is the cells: see `GridBoard`.
 */
object GridPaint {

    /**
     * A stroke in progress.
     *
     * [surfaceId] is the surface it started on, or null for a stroke that is
     * making a new one. [cells] is everything the pen has been over, whether or
     * not it will be used — filtering happens in [applyTo], because a cell the
     * pen crossed and could not have is still a cell the preview should not
     * light up.
     */
    data class Stroke(
        val surfaceId: String?,
        val erasing: Boolean,
        val cells: Set<Cell> = emptySet(),
    ) {
        fun plus(cell: Cell): Stroke =
            if (cell in cells) this else copy(cells = cells + cell)

        val isEmpty: Boolean get() = cells.isEmpty()
    }

    /**
     * Begin a stroke at [cell].
     *
     * Erasing starts on whatever owns the cell, and on nothing at all if nothing
     * does — rubbing at bare grid is not an error, it is a miss.
     */
    fun begin(layout: DockLayout, cell: Cell, erasing: Boolean): Stroke =
        Stroke(layout.surfaceAt(cell)?.id, erasing).plus(cell)

    /**
     * The layout this stroke makes.
     *
     * Adding: every cell the stroke covers that no *other* surface owns, joined
     * to the surface it started on, or made into a new one.
     *
     * Erasing: every cell the stroke covers taken off whichever surface owns it,
     * and a surface left with no cells closed. A control standing on a cell that
     * goes has nowhere to stand and goes with it — that is what rubbing out
     * means, and it is why the eraser is a deliberate second gesture rather than
     * the same one backwards.
     */
    fun applyTo(layout: DockLayout, stroke: Stroke): DockLayout {
        if (stroke.isEmpty) return layout
        if (stroke.erasing) {
            var out = layout
            for (cell in stroke.cells) {
                val surface = out.surfaceAt(cell) ?: continue
                out = out.reshape(surface.id, surface.region.minus(listOf(cell)))
            }
            return out
        }

        val target = stroke.surfaceId
        val free = stroke.cells.filter { cell ->
            val owner = layout.surfaceAt(cell)
            owner == null || owner.id == target
        }
        if (free.isEmpty()) return layout
        if (target == null) return layout.addSurface(CellRegion.ofCells(free)).first
        val surface = layout.surface(target) ?: return layout
        return layout.reshape(target, surface.region.plus(free))
    }

    /**
     * The cells this stroke will actually use, for the preview.
     *
     * The preview has to agree with [applyTo] cell for cell or the outline
     * under the pen is a promise the lift does not keep.
     */
    fun previewOf(layout: DockLayout, stroke: Stroke): Set<Cell> {
        if (stroke.erasing) return stroke.cells.filter { layout.surfaceAt(it) != null }.toSet()
        return stroke.cells.filter { cell ->
            val owner = layout.surfaceAt(cell)
            owner == null || owner.id == stroke.surfaceId
        }.toSet()
    }
}
