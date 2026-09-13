package be.thalos.artiest.engine.ink

/**
 * A growable list of stroke ids, reused across queries so that asking "which
 * strokes are in this rectangle" two hundred times a minute allocates nothing.
 *
 * Its own type rather than a `MutableList<Long>`, because that boxes every id —
 * a `java.lang.Long` per stroke per query, on the path a drag re-renders
 * through.
 */
class IdList(initialCapacity: Int = 32) {

    private var ids = LongArray(if (initialCapacity < 1) 1 else initialCapacity)

    var count: Int = 0
        private set

    fun id(i: Int): Long = ids[i]

    val isEmpty: Boolean get() = count == 0

    fun clear() {
        count = 0
    }

    fun add(id: Long) {
        if (count == ids.size) ids = ids.copyOf(ids.size * 2)
        ids[count++] = id
    }

    fun contains(id: Long): Boolean {
        for (i in 0 until count) if (ids[i] == id) return true
        return false
    }

    /**
     * Overwrite the id at [i]. Internal: only [StrokeGrid.query] uses it, to
     * compact its own answer in place, and a public setter on a list whose
     * whole contract is "sorted and unique" is a way to break that quietly.
     */
    internal fun setAt(i: Int, id: Long) {
        ids[i] = id
    }

    /** Keep the first [n]. Internal, for the same reason as [setAt]. */
    internal fun truncate(n: Int) {
        require(n in 0..count) { "truncate($n) of $count" }
        count = n
    }

    /** A copy, for a caller that wants to keep the answer. */
    fun toArray(): LongArray = ids.copyOf(count)

    /**
     * Sort ascending and drop repeats, in place.
     *
     * Both halves matter to [StrokeGrid.query] and for different reasons: a
     * stroke spanning four cells is collected four times, and stroke ids
     * ascend in draw order, so sorting is also what puts the answer in the
     * order the strokes have to be repainted in.
     */
    fun sortAndDedupe() {
        if (count < 2) return
        java.util.Arrays.sort(ids, 0, count)
        var w = 1
        for (r in 1 until count) {
            if (ids[r] != ids[w - 1]) ids[w++] = ids[r]
        }
        count = w
    }

    override fun toString(): String = "IdList($count)"
}

/**
 * Which strokes are near a point or a rectangle, without walking all of them.
 *
 * A uniform grid over the page — **256 document pixels a cell**, so Ik0's
 * 3300x2160 page is 117 cells — with each stroke's id filed in every cell its
 * bounds touch. "Which stroke did I tap" goes from a walk over five hundred
 * strokes to a walk over the handful in one cell, and Ik4's damage rectangle
 * goes from re-rendering the sheet to re-rendering what overlaps it.
 *
 * A uniform grid rather than a quadtree or an R-tree, and the reason is the
 * data rather than taste. Strokes on a page of inking are roughly one size and
 * roughly evenly spread — that is what inking *is* — which is the case a
 * uniform grid is best at and the case a tree's balancing pays for and does not
 * need. It also rebuilds in one pass with no allocation per stroke, which
 * matters because opening a project rebuilds it for every sheet.
 *
 * **It is a coarse filter and nothing more.** It never misses a stroke whose
 * bounds meet the query, and it may return one whose *ink* does not — the
 * bounds is a rectangle and the stroke is a curve inside it. Callers narrow
 * with [StrokePolyline]. A stroke that lies entirely off the page is filed in
 * the nearest edge cell, so it stays findable after Ik9 drags it back on.
 *
 * Not thread-safe, and it belongs to the render thread with the sheet it
 * indexes. Every mutation happens inside a `LayerOp`, which is the ordering
 * argument this repo has already made four times.
 */
class StrokeGrid(
    val widthPx: Int,
    val heightPx: Int,
    val cellPx: Int = DEFAULT_CELL_PX,
) {

    init {
        require(widthPx > 0 && heightPx > 0) { "page was ${widthPx}x$heightPx" }
        require(cellPx > 0) { "cell was $cellPx px" }
    }

    val columns: Int = (widthPx + cellPx - 1) / cellPx
    val rows: Int = (heightPx + cellPx - 1) / cellPx

    // Null until a cell has something in it. A blank page of 117 cells is 117
    // null references rather than 117 empty arrays, and most drawings leave
    // most of the margin empty.
    private val cells: Array<LongArray?> = arrayOfNulls(columns * rows)
    private val counts: IntArray = IntArray(columns * rows)

    /**
     * Every stroke's bounds, so [remove] does not have to be handed the same
     * rectangle it was given at [add] — a caller that passes a slightly
     * different one leaves the id filed in a cell forever, and the symptom is a
     * deleted stroke that a tap still selects.
     */
    private val filed = HashMap<Long, Bounds>()

    /** How many distinct strokes are filed. */
    val size: Int get() = filed.size

    /**
     * File [id] under [bounds]. Re-filing an id that is already present moves
     * it, which is what Ik9's drag needs.
     */
    fun add(id: Long, bounds: Bounds) {
        val was = filed.put(id, bounds)
        if (was != null) unfile(id, was)
        if (bounds.isEmpty) return
        forEachCell(bounds) { at -> push(at, id) }
    }

    /** Forget [id]. True when it was filed. */
    fun remove(id: Long): Boolean {
        val was = filed.remove(id) ?: return false
        unfile(id, was)
        return true
    }

    fun clear() {
        java.util.Arrays.fill(cells, null)
        java.util.Arrays.fill(counts, 0)
        filed.clear()
    }

    /** The bounds [id] was filed under, or null. */
    fun boundsOf(id: Long): Bounds? = filed[id]

    /**
     * Fill [out] with the ids whose bounds meet [query], each once, **in
     * ascending id order, which is draw order**.
     *
     * [out] is cleared first. The exact rectangle test happens here rather than
     * being left to the caller, because the grid already holds every stroke's
     * bounds and a cell is 256 px wide: without it, a one-pixel damage
     * rectangle in the corner of a cell returns every stroke in that cell.
     */
    fun query(query: Bounds, out: IdList) {
        out.clear()
        if (query.isEmpty) return
        forEachCell(query) { at ->
            val cell = cells[at]
            if (cell != null) {
                for (i in 0 until counts[at]) out.add(cell[i])
            }
        }
        out.sortAndDedupe()
        if (out.isEmpty) return
        // Compacted in place rather than into a second list: the exact test
        // drops a minority of what the cells returned, and a second IdList
        // would be a second allocation on the path this type exists to keep
        // allocation-free.
        var w = 0
        for (i in 0 until out.count) {
            val id = out.id(i)
            val b = filed[id] ?: continue
            if (b.intersects(query)) {
                if (w != i) out.setAt(w, id)
                w++
            }
        }
        out.truncate(w)
    }

    /** [query] with a fresh list, for a caller that runs once. */
    fun query(query: Bounds): LongArray {
        val out = IdList()
        query(query, out)
        return out.toArray()
    }

    /**
     * The ids near a point, within [slopDoc] document pixels. The same coarse
     * answer [query] gives; Ik7 narrows it with [StrokePolyline.hits].
     */
    fun near(xDoc: Float, yDoc: Float, slopDoc: Float, out: IdList) {
        val s = if (slopDoc > 0f) slopDoc else 0f
        query(Bounds.of(xDoc - s, yDoc - s, xDoc + s, yDoc + s), out)
    }

    override fun toString(): String =
        "StrokeGrid(${columns}x$rows cells of $cellPx px, ${filed.size} strokes)"

    private inline fun forEachCell(b: Bounds, body: (Int) -> Unit) {
        // Clamped rather than skipped: a stroke off the left edge is filed in
        // column 0, and a query off the left edge looks there. Off-page ink is
        // legal — the pen can leave the page mid-stroke — and an index that
        // forgot it would lose the stroke rather than merely misplace it.
        val c0 = clamp((b.left / cellPx).toIntFloor(), columns)
        val c1 = clamp((b.right / cellPx).toIntFloor(), columns)
        val r0 = clamp((b.top / cellPx).toIntFloor(), rows)
        val r1 = clamp((b.bottom / cellPx).toIntFloor(), rows)
        for (r in r0..r1) {
            val base = r * columns
            for (c in c0..c1) body(base + c)
        }
    }

    private fun unfile(id: Long, bounds: Bounds) {
        if (bounds.isEmpty) return
        forEachCell(bounds) { at ->
            val cell = cells[at] ?: return@forEachCell
            val n = counts[at]
            for (i in 0 until n) {
                if (cell[i] == id) {
                    // Order inside a cell carries no meaning — `query` sorts —
                    // so the last element fills the hole and nothing shifts.
                    cell[i] = cell[n - 1]
                    counts[at] = n - 1
                    return@forEachCell
                }
            }
        }
    }

    private fun push(at: Int, id: Long) {
        var cell = cells[at]
        if (cell == null) {
            cell = LongArray(INITIAL_CELL)
            cells[at] = cell
        } else if (counts[at] == cell.size) {
            cell = cell.copyOf(cell.size * 2)
            cells[at] = cell
        }
        cell[counts[at]++] = id
    }

    private fun clamp(v: Int, n: Int): Int = if (v < 0) 0 else if (v >= n) n - 1 else v

    private fun Float.toIntFloor(): Int {
        val f = kotlin.math.floor(this)
        return when {
            f <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
            f >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
            else -> f.toInt()
        }
    }

    companion object {

        /**
         * 256 document pixels.
         *
         * `docs/inker-plan.md`'s arithmetic: a 3300x2160 page is 117 cells of a
         * `LongArray` each. Smaller cells mean a long stroke is filed in dozens
         * of them and every mutation touches dozens; larger cells mean a tap
         * measures every stroke in a quarter of the page. 256 puts a typical
         * inking stroke — Ik0's scene is 200 to 700 px — in one to nine cells.
         */
        const val DEFAULT_CELL_PX: Int = 256

        /** Four ids, 32 bytes. Most cells of most drawings hold fewer. */
        const val INITIAL_CELL: Int = 4
    }
}
