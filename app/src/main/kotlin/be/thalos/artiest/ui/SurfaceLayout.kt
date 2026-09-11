package be.thalos.artiest.ui

/**
 * A toolbar as data: a shape of cells, some of them filled.
 *
 * This is `ToolbarLayout` with a second dimension, and every argument in that
 * file's KDoc was kept because none of them stopped being true. They are
 * restated here rather than referred to, because the file they were in is gone.
 *
 * **Why fixed cells rather than a shape that grows.** A bar that reflows when
 * you add something is a bar where Export is in a different place every week,
 * and the single thing a toolbar is *for* is that your hand knows where the
 * button is without your eye going there. Fixed cells cost an empty-cell
 * affordance and an occasional "that does not fit"; a reflowing bar costs the
 * muscle memory, which is the whole asset. [RegionLayout] is where reflowing
 * lives, and it runs when the user is *shaping* a workspace, never while they
 * are drawing in one.
 *
 * **Why the chooser disables rather than the layout rearranges.** Ask to put a
 * six-cell panel into a two-cell gap and there are three possible kindnesses:
 * refuse, shove the neighbours along, or drop it somewhere else that fits. The
 * last two both move an item the user did not touch. So [fits] is a public
 * query, the chooser greys out what would not go, and [place] treats a bad
 * placement as a programming error and throws — because a control that silently
 * declines to appear is indistinguishable from one that is broken.
 *
 * **It knows nothing about tools, docks or directions.** It allocates
 * rectangles. That is what lets one set of tests cover a row of buttons, an
 * L-shaped toolbar and a floating panel without knowing which is which.
 *
 * The type is immutable. Every operation returns a new layout, which is what
 * makes it usable as Compose state and trivial to test.
 */
class SurfaceLayout private constructor(
    /** The shape. Everything placed is inside it — see [CellRegion.accepts]. */
    val region: CellRegion,
    /** Non-overlapping, ordered top to bottom then left to right. */
    val placements: List<CellPlacement>,
) {

    val isEmpty: Boolean get() = placements.isEmpty()

    /** How many cells are spoken for. */
    val usedCells: Int get() = placements.sumOf { it.w * it.h }

    /** The placement covering the cell, or null if it is empty. */
    fun covering(x: Int, y: Int): CellPlacement? = placements.firstOrNull { it.covers(x, y) }

    fun covering(cell: Cell): CellPlacement? = covering(cell.x, cell.y)

    /**
     * Would a [w] by [h] footprint fit with its corner at [x], [y]?
     *
     * [ignoring] names a cell whose occupant is about to be removed — the one
     * being replaced when the user picks something else for a filled cell.
     * Without it, every item would appear to collide with the thing it is
     * replacing, and the chooser would grey out the entire catalogue on exactly
     * the gesture that exists to change an item.
     */
    fun fits(w: Int, h: Int, x: Int, y: Int, ignoring: Cell? = null): Boolean {
        if (w < 1 || h < 1) return false
        if (!region.accepts(x, y, w, h)) return false
        val ignored = ignoring?.let { covering(it) }
        return placements.none { p ->
            p !== ignored && p.x < x + w && x < p.right && p.y < y + h && y < p.bottom
        }
    }

    fun fits(p: CellPlacement, ignoring: Cell? = null): Boolean =
        fits(p.w, p.h, p.x, p.y, ignoring)

    /**
     * Put [placement] in, replacing whatever was covering its top-left cell.
     *
     * Throws if it does not fit. See the class KDoc: the caller is expected to
     * have asked [fits] first, and a silent no-op here would surface as a
     * chooser entry that does nothing when tapped.
     */
    fun place(placement: CellPlacement): SurfaceLayout {
        require(fits(placement, ignoring = placement.cell)) {
            "${placement.item.id} (${placement.w}x${placement.h}) does not fit at " +
                "${placement.x},${placement.y} in $region"
        }
        val replaced = covering(placement.x, placement.y)
        val kept = placements.filter { it !== replaced }
        return SurfaceLayout(region, sort(kept + placement))
    }

    /** Empty the cell. A no-op on an already-empty one. */
    fun remove(x: Int, y: Int): SurfaceLayout {
        val hit = covering(x, y) ?: return this
        return SurfaceLayout(region, placements.filter { it !== hit })
    }

    fun remove(cell: Cell): SurfaceLayout = remove(cell.x, cell.y)

    /** The first cell in [flow] order a [w] by [h] footprint would fit in, or null. */
    fun firstFit(w: Int, h: Int, flow: FlowOrder): Cell? =
        region.cells(flow).firstOrNull { fits(w, h, it.x, it.y) }

    /** A surface of the same shape with nothing on it. */
    fun cleared(): SurfaceLayout = SurfaceLayout(region, emptyList())

    /**
     * The same placements in a different shape, dropping what no longer fits.
     *
     * Used when a release lengthens a bar, when the screen decides how many
     * cells it can show, and when the user draws a new shape. Was `resized`,
     * which could only ever mean one number.
     */
    fun reshaped(region: CellRegion): SurfaceLayout = of(region, placements)

    override fun equals(other: Any?): Boolean =
        other is SurfaceLayout && other.region == region && other.placements == placements

    override fun hashCode(): Int = 31 * region.hashCode() + placements.hashCode()

    override fun toString(): String =
        "SurfaceLayout($region, " + placements.joinToString { "${it.x},${it.y}=${it.item.id}" } + ")"

    companion object {

        /**
         * Build a layout, dropping anything that cannot be represented.
         *
         * Normalising here rather than validating is what lets the codec be
         * careless with its input and still hand back something sane: a
         * placement outside the shape, or one overlapping a placement already
         * accepted, is dropped rather than raised. Earlier placements win, so
         * the result is a function of the input order and the tests say so.
         */
        fun of(region: CellRegion, placements: List<CellPlacement>): SurfaceLayout {
            val kept = ArrayList<CellPlacement>(placements.size)
            for (p in placements) {
                if (!region.accepts(p.x, p.y, p.w, p.h)) continue
                if (kept.any { it.overlaps(p) }) continue
                kept += p
            }
            return SurfaceLayout(region, sort(kept))
        }

        /** An empty surface of this shape. */
        fun empty(region: CellRegion): SurfaceLayout = SurfaceLayout(region, emptyList())

        private fun sort(placements: List<CellPlacement>): List<CellPlacement> =
            placements.sortedWith(compareBy({ it.y }, { it.x }))
    }
}
