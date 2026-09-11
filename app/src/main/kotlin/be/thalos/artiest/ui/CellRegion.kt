package be.thalos.artiest.ui

/**
 * One cell of the chrome grid, by its column and row.
 *
 * Cells and not dp: one cell is [Chrome.SLOT], which is 44dp, and that number
 * lives there and nowhere else. Everything in this file counts cells, which is
 * what lets it be tested in a plain JVM with no Compose and no density.
 */
data class Cell(val x: Int, val y: Int)

/**
 * A rectangle of cells, by its top-left corner and its size.
 *
 * [w] and [h] are at least one, checked rather than clamped, for the reason
 * [CellPlacement] checks the same thing: a zero-sized rectangle is a bug in
 * whoever built it, and a rectangle that quietly becomes one cell is a bug that
 * renders. Input that may be junk goes through [of], which returns null instead.
 */
data class CellRect(val x: Int, val y: Int, val w: Int, val h: Int) {

    init {
        require(w >= 1 && h >= 1) { "a ${w}x$h rectangle is not a rectangle" }
    }

    /** Exclusive. */
    val right: Int get() = x + w

    /** Exclusive. */
    val bottom: Int get() = y + h

    val area: Int get() = w * h

    fun contains(cx: Int, cy: Int): Boolean = cx in x until right && cy in y until bottom

    fun overlaps(other: CellRect): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom

    override fun toString(): String = "$x,$y,${w}x$h"

    companion object {
        /**
         * A rectangle, or null when the four numbers do not describe one.
         *
         * The safe door, for numbers that came off disk or out of a file
         * somebody else wrote. Negative sizes, negative positions and sizes
         * larger than any screen all return null; see [CellRegion.MAX_SPAN].
         */
        fun of(x: Int, y: Int, w: Int, h: Int): CellRect? {
            if (w < 1 || h < 1) return null
            if (x < 0 || y < 0) return null
            if (w > CellRegion.MAX_SPAN || h > CellRegion.MAX_SPAN) return null
            if (x > CellRegion.MAX_COORD || y > CellRegion.MAX_COORD) return null
            return CellRect(x, y, w, h)
        }
    }
}

/**
 * The order a shape is filled in, and therefore where the next thing lands.
 *
 * A region is a set of cells and a set has no order, so this is the missing
 * half of "put it in the first free place". It is two decisions: which axis is
 * walked first, and which end of each axis is the start. Eight combinations,
 * all of them named, because `flow: "down_right"` in a file somebody wrote by
 * hand has to mean something to them.
 *
 * [id] is persisted and is not [name], for the reason every other id in this
 * package is not its constant's name.
 */
enum class FlowOrder(
    val id: String,
    /** The axis walked first — the one a run of buttons ends up along. */
    val primary: Axis,
    val reverseX: Boolean,
    val reverseY: Boolean,
) {
    RIGHT_THEN_DOWN("right_down", Axis.HORIZONTAL, false, false),
    LEFT_THEN_DOWN("left_down", Axis.HORIZONTAL, true, false),
    RIGHT_THEN_UP("right_up", Axis.HORIZONTAL, false, true),
    LEFT_THEN_UP("left_up", Axis.HORIZONTAL, true, true),
    DOWN_THEN_RIGHT("down_right", Axis.VERTICAL, false, false),
    DOWN_THEN_LEFT("down_left", Axis.VERTICAL, true, false),
    UP_THEN_RIGHT("up_right", Axis.VERTICAL, false, true),
    UP_THEN_LEFT("up_left", Axis.VERTICAL, true, true),
    ;

    companion object {
        private val BY_ID: Map<String, FlowOrder> = entries.associateBy { it.id }

        fun byId(id: String): FlowOrder? = BY_ID[id]

        /** How a plain bar along [axis] fills: left to right, or top to bottom. */
        fun along(axis: Axis): FlowOrder =
            if (axis == Axis.HORIZONTAL) RIGHT_THEN_DOWN else DOWN_THEN_RIGHT
    }
}

/**
 * A toolbar's shape: any set of cells, stored as a short list of rectangles.
 *
 * ## Why rectangles and not a bitmask
 *
 * This is the **stored** form. It goes into JSON, a person reads it, a model
 * writes it. `"rects": [[0,0,1,12],[0,11,5,1]]` is an L that somebody can see
 * in their head; four hundred booleans is not. The mask is what the packer
 * wants, and it is built here, kept private, and never leaves the file.
 *
 * ## The canonical form, and why every constructor goes through it
 *
 * Two lists of rectangles can describe the same cells, and if the stored form
 * were whatever the author typed then a shape would rewrite its file every time
 * it was touched, a round-trip test would need a notion of equivalence, and
 * `equals` would be wrong. So every region is rasterised to cells and coalesced
 * back the one way described in [ofCells]. Overlapping input is not an error,
 * it is a longer way of writing the same shape.
 *
 * ## Normalising, not validating
 *
 * [of] drops what it cannot use — a rectangle off the grid, a negative size, a
 * shape larger than any screen — exactly as `DockLayout.of` drops a duplicated
 * item. It never throws. Every one of those arrives from a preference string or
 * a file from a stranger, and the failure mode of a drawing app that will not
 * start is that the drawing is unreachable.
 *
 * Immutable, so it can be Compose state and so that a test is one expression.
 */
class CellRegion private constructor(
    /** Disjoint, canonical, top to bottom then left to right. */
    val rects: List<CellRect>,
) {

    /**
     * The smallest rectangle holding every cell.
     *
     * For a region with no cells this is the single cell at the origin, which
     * is a lie that is never read: ask [isEmpty] first. The alternative is a
     * nullable bounds, which would put a `?:` in the renderer's measure pass to
     * describe a shape that is not drawn.
     */
    val bounds: CellRect = if (rects.isEmpty()) CellRect(0, 0, 1, 1) else {
        val x = rects.minOf { it.x }
        val y = rects.minOf { it.y }
        CellRect(x, y, rects.maxOf { it.right } - x, rects.maxOf { it.bottom } - y)
    }

    /** True where the region has a cell, at `(y - bounds.y) * bounds.w + (x - bounds.x)`. */
    private val mask: BooleanArray = BooleanArray(bounds.w * bounds.h).also { m ->
        for (r in rects) {
            for (cy in r.y until r.bottom) {
                val row = (cy - bounds.y) * bounds.w
                for (cx in r.x until r.right) m[row + (cx - bounds.x)] = true
            }
        }
    }

    val cellCount: Int = rects.sumOf { it.area }

    val isEmpty: Boolean get() = rects.isEmpty()

    /** True when this is a plain bar: one rectangle, one cell thick. */
    val isStrip: Boolean get() = rects.size == 1 && (bounds.w == 1 || bounds.h == 1)

    /** Which way a strip runs, or null when this is a shape. A single cell runs across. */
    val stripAxis: Axis?
        get() = if (!isStrip) null
        else if (bounds.w >= bounds.h) Axis.HORIZONTAL else Axis.VERTICAL

    /** How long this is along [axis], in cells. Meaningful for a strip. */
    fun lengthAlong(axis: Axis): Int = if (axis == Axis.HORIZONTAL) bounds.w else bounds.h

    operator fun contains(cell: Cell): Boolean = contains(cell.x, cell.y)

    fun contains(x: Int, y: Int): Boolean {
        if (!bounds.contains(x, y)) return false
        return mask[(y - bounds.y) * bounds.w + (x - bounds.x)]
    }

    /** Is the whole [w] by [h] footprint at [x], [y] inside the shape? */
    fun fits(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (w < 1 || h < 1) return false
        for (cy in y until y + h) for (cx in x until x + w) if (!contains(cx, cy)) return false
        return true
    }

    /**
     * May a [w] by [h] footprint stand with its corner at [x], [y]?
     *
     * **A shape is a shape somebody drew, so the footprint has to be inside
     * it** — corner to corner, every cell. Growing a surface to hold something
     * dropped on it would redraw their toolbar for them, and there is no longer
     * any such thing as a bar that is allowed to grow: since
     * `docs/ui-grid-plan.md` a bar is a shape one cell thick and nothing else.
     *
     * [hangs] is the one exception and it belongs to panels. A colour wheel is
     * six cells by eleven and a toolbar that had to be six by eleven to hold one
     * would be a wall; `ToolKind.PANEL` has always said the card *overhangs* the
     * thing it is anchored to. So a hanging item needs the cell it is anchored
     * to and nothing else, and what it overhangs is the drawing.
     *
     * The overhang is still reserved against everything else on the same
     * surface — that is [SurfaceLayout]'s rectangle test, not this one — so a
     * panel and a button cannot be drawn on top of each other.
     */
    fun accepts(x: Int, y: Int, w: Int, h: Int, hangs: Boolean = false): Boolean {
        if (w < 1 || h < 1 || isEmpty) return false
        return if (hangs) contains(x, y) else fits(x, y, w, h)
    }

    /** Every cell, in [flow] order. The order a packer tries them in. */
    fun cells(flow: FlowOrder): Sequence<Cell> = sequence {
        val xs = (bounds.x until bounds.right).toList().let { if (flow.reverseX) it.reversed() else it }
        val ys = (bounds.y until bounds.bottom).toList().let { if (flow.reverseY) it.reversed() else it }
        if (flow.primary == Axis.HORIZONTAL) {
            for (y in ys) for (x in xs) if (contains(x, y)) yield(Cell(x, y))
        } else {
            for (x in xs) for (y in ys) if (contains(x, y)) yield(Cell(x, y))
        }
    }

    /** Every cell, top to bottom then left to right. What the shape editor edits. */
    fun cellSet(): Set<Cell> = cells(FlowOrder.RIGHT_THEN_DOWN).toMutableSet()

    fun plus(cells: Collection<Cell>): CellRegion = ofCells(cellSet() + cells)

    fun minus(cells: Collection<Cell>): CellRegion = ofCells(cellSet() - cells.toSet())

    /**
     * The same shape with everything outside a [gridW] by [gridH] grid removed.
     *
     * The first half of *clamp, overflow, say so*: a shape made on a tablet and
     * opened on a phone loses the cells that are not there, and whatever was
     * standing on them is the caller's to overflow.
     */
    fun clampedTo(gridW: Int, gridH: Int): CellRegion {
        if (gridW < 1 || gridH < 1) return EMPTY
        if (isEmpty) return this
        if (bounds.right <= gridW && bounds.bottom <= gridH) return this
        return ofCells(cellSet().filter { it.x < gridW && it.y < gridH })
    }

    /** The same shape moved by [dx], [dy]. Cells pushed off the grid are dropped. */
    fun translated(dx: Int, dy: Int): CellRegion =
        ofCells(cellSet().map { Cell(it.x + dx, it.y + dy) })

    /** The same shape with its top-left corner at the origin. */
    fun atOrigin(): CellRegion =
        if (isEmpty || (bounds.x == 0 && bounds.y == 0)) this else translated(-bounds.x, -bounds.y)

    /** The mirror image, left to right, in the same bounding box. */
    fun mirroredX(): CellRegion {
        if (isEmpty) return this
        val b = bounds
        return ofCells(cellSet().map { Cell(b.x + (b.right - 1 - it.x), it.y) })
    }

    /** The mirror image, top to bottom, in the same bounding box. */
    fun mirroredY(): CellRegion {
        if (isEmpty) return this
        val b = bounds
        return ofCells(cellSet().map { Cell(it.x, b.y + (b.bottom - 1 - it.y)) })
    }

    /**
     * Which way a control at [x], [y] should lie.
     *
     * The longest free run through the cell wins, and horizontal wins a tie.
     * This is what [ToolItem.turns] has always meant and has never had
     * a way to say: in an L it is vertical up the arm and horizontal along the
     * foot, so one slider stands and the next lies flat, which is what a person
     * would have done by hand.
     *
     * It asks the *shape*, not what is already placed. A bar whose sliders turn
     * round as their neighbours come and go would be worse than one that is
     * occasionally wrong.
     */
    fun localAxis(x: Int, y: Int): Axis {
        if (!contains(x, y)) return Axis.HORIZONTAL
        var across = 1
        var cx = x - 1
        while (contains(cx, y)) { across++; cx-- }
        cx = x + 1
        while (contains(cx, y)) { across++; cx++ }
        var down = 1
        var cy = y - 1
        while (contains(x, cy)) { down++; cy-- }
        cy = y + 1
        while (contains(x, cy)) { down++; cy++ }
        return if (down > across) Axis.VERTICAL else Axis.HORIZONTAL
    }

    fun localAxis(cell: Cell): Axis = localAxis(cell.x, cell.y)

    override fun equals(other: Any?): Boolean = other is CellRegion && other.rects == rects

    override fun hashCode(): Int = rects.hashCode()

    override fun toString(): String = "CellRegion(" + rects.joinToString(" ") + ")"

    companion object {

        /** No cell may sit further out than this. A grid this big is eleven metres of glass. */
        const val MAX_COORD = 255

        /** Nor may a rectangle be longer than this. The same number as `DockCodec`'s. */
        const val MAX_SPAN = 64

        /** More cells than this in one shape is a file that means harm, not a toolbar. */
        const val MAX_CELLS = 1024

        val EMPTY: CellRegion = CellRegion(emptyList())

        /**
         * A region from rectangles, dropping what cannot be used.
         *
         * The result is canonical, so it is generally *not* the list that went
         * in: overlaps are merged, touching rectangles are joined where they
         * can be, and the order is fixed. Two ways of writing one shape are
         * equal afterwards, which is the property the file format needs.
         */
        fun of(rects: List<CellRect>): CellRegion {
            val usable = rects.filter {
                it.x >= 0 && it.y >= 0 &&
                    it.right <= MAX_COORD + 1 && it.bottom <= MAX_COORD + 1 &&
                    it.w <= MAX_SPAN && it.h <= MAX_SPAN
            }
            if (usable.isEmpty()) return EMPTY
            val cells = HashSet<Cell>()
            outer@ for (r in usable) {
                for (y in r.y until r.bottom) {
                    for (x in r.x until r.right) {
                        cells += Cell(x, y)
                        if (cells.size >= MAX_CELLS) break@outer
                    }
                }
            }
            return ofCells(cells)
        }

        fun of(vararg rects: CellRect): CellRegion = of(rects.toList())

        /**
         * A region from loose cells, coalesced the one deterministic way.
         *
         * > For each row, take the maximal horizontal runs. Then merge a run
         * > into the rectangle directly above it when their `x` and `w` are
         * > identical. Emit top to bottom, left to right.
         *
         * It is **not** the smallest set of rectangles — that problem is
         * NP-hard and its answer would not read any better. What matters is
         * that it is stable: adding one cell changes one rectangle and leaves
         * the rest alone, which is what stops a shape shimmering under the pen
         * that is still drawing it.
         */
        fun ofCells(cells: Collection<Cell>): CellRegion {
            var kept = cells.filter { it.x in 0..MAX_COORD && it.y in 0..MAX_COORD }.toHashSet()
            if (kept.isEmpty()) return EMPTY
            if (kept.size > MAX_CELLS) {
                kept = kept.sortedWith(compareBy({ it.y }, { it.x })).take(MAX_CELLS).toHashSet()
            }

            val byRow = kept.groupBy { it.y }
            val out = ArrayList<CellRect>()
            for (y in byRow.keys.sorted()) {
                val xs = byRow.getValue(y).map { it.x }.sorted()
                var start = xs[0]
                var prev = xs[0]

                fun flush() {
                    val w = prev - start + 1
                    val above = out.indexOfLast { it.x == start && it.w == w && it.bottom == y }
                    if (above >= 0) {
                        out[above] = out[above].copy(h = out[above].h + 1)
                    } else {
                        out += CellRect(start, y, w, 1)
                    }
                }

                for (i in 1 until xs.size) {
                    if (xs[i] == prev + 1) { prev = xs[i]; continue }
                    flush()
                    start = xs[i]
                    prev = xs[i]
                }
                flush()
            }
            return CellRegion(out)
        }

        /** A plain bar: [length] cells along [axis], one cell thick. */
        fun strip(length: Int, axis: Axis): CellRegion {
            val n = length.coerceIn(1, MAX_SPAN)
            return CellRegion(
                listOf(if (axis == Axis.HORIZONTAL) CellRect(0, 0, n, 1) else CellRect(0, 0, 1, n))
            )
        }

        /**
         * An upright arm with a foot along the bottom, both [thickness] thick.
         *
         * [arm] is the total height and [foot] the total width, so `l(8, 6)` is
         * eight cells tall, six wide, and holds thirteen of them.
         */
        fun l(arm: Int, foot: Int, thickness: Int = 1): CellRegion {
            val t = thickness.coerceIn(1, MAX_SPAN)
            val a = arm.coerceIn(t, MAX_SPAN)
            val f = foot.coerceIn(t, MAX_SPAN)
            return of(listOf(CellRect(0, 0, t, a), CellRect(0, a - t, f, t)))
        }

        /** A bar across the top with a stem down the middle: [width] by [height]. */
        fun t(width: Int, height: Int, thickness: Int = 1): CellRegion {
            val th = thickness.coerceIn(1, MAX_SPAN)
            val w = width.coerceIn(th, MAX_SPAN)
            val h = height.coerceIn(th, MAX_SPAN)
            val stem = ((w - th) / 2).coerceAtLeast(0)
            return of(listOf(CellRect(0, 0, w, th), CellRect(stem, 0, th, h)))
        }

        /** Two arms and a foot: a corner you can sit the drawing inside. */
        fun u(width: Int, height: Int, thickness: Int = 1): CellRegion {
            val th = thickness.coerceIn(1, MAX_SPAN)
            val w = width.coerceIn(th * 2, MAX_SPAN)
            val h = height.coerceIn(th, MAX_SPAN)
            return of(
                listOf(
                    CellRect(0, 0, th, h),
                    CellRect(w - th, 0, th, h),
                    CellRect(0, h - th, w, th),
                )
            )
        }

        /** A filled rectangle. The shape a keypad wants. */
        fun block(width: Int, height: Int): CellRegion =
            CellRegion(
                listOf(CellRect(0, 0, width.coerceIn(1, MAX_SPAN), height.coerceIn(1, MAX_SPAN)))
            )
    }
}
