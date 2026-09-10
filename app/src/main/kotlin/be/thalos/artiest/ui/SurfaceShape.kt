package be.thalos.artiest.ui

/**
 * The shapes a toolbar can be, and what each one means on each edge.
 *
 * Five presets, because five is what a menu can hold and because between them
 * they cover what people actually build: a bar, a corner, a bar with something
 * hanging off it, a bracket round the paper, and a keypad. Anything else is
 * drawn, which is the point of the whole system — these exist so that the
 * common cases take one tap rather than a minute of painting.
 *
 * **A preset is anchored, not absolute.** An L on the left edge has its arm
 * down the left and its foot along the bottom; the same L on the right edge is
 * mirrored, so its arm is down the right. Otherwise picking "L" on the right
 * would give a shape whose corner points away from the corner it is sitting in,
 * which is not what anybody means by an L there.
 */
enum class SurfaceShape(val label: String) {
    BAR("Bar"),
    L("L"),
    T("T"),
    U("U"),
    BLOCK("Block"),
    ;

    /**
     * This shape, sized and turned for [dock].
     *
     * The long dimension is the dock's own default length, so a preset is
     * always as long as a plain bar there would have been — picking "L" on the
     * left edge does not silently shorten the left edge. The short dimension is
     * a constant, because a foot you cannot fit five buttons on is not a foot.
     */
    fun regionFor(dock: Dock): CellRegion {
        val long = dock.defaultSlots
        val upright = dock.axis == Axis.VERTICAL
        val region = when (this) {
            BAR -> return dock.defaultRegion()
            L -> if (upright) CellRegion.l(arm = long, foot = SHORT) else CellRegion.l(arm = SHORT, foot = long)
            T -> if (upright) CellRegion.t(width = SHORT, height = long) else CellRegion.t(width = long, height = SHORT)
            U -> if (upright) CellRegion.u(width = SHORT, height = long) else CellRegion.u(width = long, height = SHORT)
            BLOCK -> if (upright) CellRegion.block(SHORT, BLOCK_LONG) else CellRegion.block(BLOCK_LONG, SHORT)
        }
        // Presets are drawn for the top-left corner. An edge on the other side
        // of the screen wants the mirror image, so that the corner of an L
        // points into the corner it is sitting in.
        return when (dock) {
            Dock.RIGHT -> region.mirroredX()
            Dock.TOP -> region.mirroredY()
            else -> region
        }
    }

    companion object {
        /** How thick the short leg of a preset is. Five buttons wide. */
        private const val SHORT = 5

        /** A block is a keypad, not a wall: enough for a couple of rows of tools. */
        private const val BLOCK_LONG = 4

        /** Which preset [region] already is, or null when it is something drawn. */
        fun of(region: CellRegion, dock: Dock): SurfaceShape? =
            entries.firstOrNull { it.regionFor(dock) == region }
    }
}
