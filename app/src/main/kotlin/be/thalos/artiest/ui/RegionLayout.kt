package be.thalos.artiest.ui

/**
 * Fill a shape with controls.
 *
 * This is the half of the workspace system that makes "here is an L, and here
 * are twelve tools" into an arrangement. It is a pure function of its inputs —
 * no Compose, no Android, no state — which is what lets an L be argued about in
 * a plain JVM test before anything is drawn.
 *
 * ## Fixed first, then flow
 *
 * A workspace file may say where a control goes (`"at": [0, 4]`) or leave it to
 * the packer. Both appear in one file and both have to work, so the fixed ones
 * are honoured exactly and then everything else takes the first free cell in
 * [FlowOrder]. A fixed placement that is off the shape or on top of an earlier
 * one **drops to overflow** rather than raising, which is the same discipline
 * `DockLayout.of` and `CellRegion.of` follow and for the same reason: the input
 * is a file somebody else wrote.
 *
 * ## Flow when you are shaping, freeze when you are drawing
 *
 * This runs when the user reshapes a surface, taps Tidy, switches workspace or
 * imports a file. It does **not** run while they are working. A toolbar that
 * rearranges itself is a toolbar where Export is somewhere new every week, and
 * the single thing a toolbar is for is that the hand finds the button without
 * the eye going there. `SurfaceLayout`'s KDoc argues this at length; this file
 * is the other half of it, and the reason both can be true.
 */
object RegionLayout {

    /**
     * One thing to place, and how big it is lying flat.
     *
     * [w] and [h] are the item's own cells — its width and height on a
     * horizontal bar. When [turns] the packer may swap them to match the shape
     * where the item lands, which is what a slider does and a panel does not.
     */
    data class Footprint(val item: ToolItem, val w: Int, val h: Int, val turns: Boolean) {
        init { require(w >= 1 && h >= 1) { "${item.id} is ${w}x$h cells" } }

        companion object {
            /** What the catalogue says about [item]. */
            fun of(item: ToolItem): Footprint =
                Footprint(item, item.cellsWide, item.cellsTall, item.turnsWithDock)
        }
    }

    /**
     * Where everything went, and what would not go anywhere.
     *
     * [overflow] is in input order — fixed first, then flowing — so the chevron
     * that shows it lists the same things in the same order every time. That is
     * the promise `ToolItem`'s rule 1 rests on: a control the user asked for
     * either appears on the bar or appears in the overflow, and never simply
     * stops existing.
     */
    data class Fill(
        val placements: List<CellPlacement>,
        val overflow: List<ToolItem>,
    ) {
        val isComplete: Boolean get() = overflow.isEmpty()
    }

    /**
     * Pack [fixed] then [flowing] into [region], filling in [flow] order.
     *
     * Deterministic: the same call twice gives the same answer, and adding an
     * item at the end never moves one already placed.
     */
    fun pack(
        region: CellRegion,
        flow: FlowOrder,
        fixed: List<CellPlacement> = emptyList(),
        flowing: List<Footprint> = emptyList(),
    ): Fill {
        if (region.isEmpty) {
            return Fill(emptyList(), fixed.map { it.item } + flowing.map { it.item })
        }

        val placed = ArrayList<CellPlacement>(fixed.size + flowing.size)
        val overflow = ArrayList<ToolItem>()
        val taken = HashSet<Cell>()

        fun free(x: Int, y: Int, w: Int, h: Int): Boolean {
            if (!region.accepts(x, y, w, h)) return false
            for (cy in y until y + h) for (cx in x until x + w) if (Cell(cx, cy) in taken) return false
            return true
        }

        fun occupy(p: CellPlacement) {
            for (cy in p.y until p.bottom) for (cx in p.x until p.right) taken += Cell(cx, cy)
            placed += p
        }

        for (p in fixed) {
            if (free(p.x, p.y, p.w, p.h)) occupy(p) else overflow += p.item
        }

        for (f in flowing) {
            var landed: CellPlacement? = null
            for (cell in region.cells(flow)) {
                for ((w, h) in orientations(f, region, cell)) {
                    if (!free(cell.x, cell.y, w, h)) continue
                    landed = CellPlacement(f.item, cell.x, cell.y, w, h)
                    break
                }
                if (landed != null) break
            }
            if (landed != null) occupy(landed) else overflow += f.item
        }

        return Fill(placed.sortedWith(compareBy({ it.y }, { it.x })), overflow)
    }

    /**
     * The sizes to try at [cell], best first.
     *
     * A turning item lies along the shape: upright in an L's arm, flat along
     * its foot. In a shape it is tried the other way round as well, because a
     * four-cell slider in a two-cell foot would otherwise overflow when
     * standing up would have fitted it, and a slider the wrong way round is
     * still a slider.
     *
     * **On a bar there is no second chance**, and that is not a detail. A bar
     * grows across itself to hold a deep item — see [CellRegion.accepts] — so a
     * four-cell slider offered the upright orientation on a horizontal bar
     * would be accepted as one slot wide and four cells *deep*, hanging off the
     * bar like a panel. That is not a slider, it is a mistake with a rendering,
     * and it is what the old engine refused by never having the option.
     */
    private fun orientations(f: Footprint, region: CellRegion, cell: Cell): List<Pair<Int, Int>> {
        if (!f.turns || f.w == f.h) return listOf(f.w to f.h)
        val flat = f.w to f.h
        val upright = f.h to f.w
        region.stripAxis?.let { return listOf(if (it == Axis.VERTICAL) upright else flat) }
        return if (region.localAxis(cell) == Axis.VERTICAL) listOf(upright, flat) else listOf(flat, upright)
    }
}
