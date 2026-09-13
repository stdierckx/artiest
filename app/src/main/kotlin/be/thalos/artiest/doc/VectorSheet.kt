package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.IdList
import be.thalos.artiest.engine.ink.StrokeGrid
import be.thalos.artiest.engine.ink.StrokeRecord

/**
 * One stroke's input on its way from the UI thread to the render thread, beside
 * the pixels the commit already carries.
 *
 * It is not a [StrokeRecord] yet and cannot be, because three of a record's
 * fields are the *sheet's* to assign and the sheet lives on the other thread:
 * the id, the index into the brush table, and the index into the clip table.
 * Handing over the brush as text and letting the sheet intern it is what keeps
 * `docs/inker-plan.md`'s rule that **a record names a table entry, not a
 * library id** — retuning a preset must not reach back and change strokes drawn
 * with it last week.
 *
 * Built at pen-up, on the UI thread, out of a `SampleLog` that was filled one
 * sample at a time while the pen was down. The packing is a few kilobytes of
 * memcpy; nothing here is per-sample work.
 */
class PendingStroke(
    val samples: ByteArray,
    val sampleCount: Int,
    val seed: Int,
    val colorArgb: Int,
    val erase: Boolean,
    /** `BrushCodec.encode` of the brush as it was at pen-down. */
    val brushText: String,
    /** What the dab loop accumulated. See [StrokeRecord.bounds]. */
    val bounds: Bounds,
)

/**
 * The strokes that made a sheet, and the index that finds them.
 *
 * `docs/inker-plan.md`'s one idea, as a class:
 *
 * > **A vector sheet is not a new kind of layer. It is a raster sheet that kept
 * > the strokes that made it, and can therefore rebuild itself.**
 *
 * So this holds no pixels. The pixels are in the `Layer` beside it, exactly as
 * they always were, and everything downstream — `StackCompositor`,
 * `PngExporter`, `Thumbnails`, blend modes, opacity, the layers panel, the
 * `.ora` export — goes on seeing what it has always seen. What is new is that
 * the sheet can say *which strokes* painted any rectangle of itself, which is
 * what Ik4 rebuilds from and what Ik7 selects with.
 *
 * ## Three tables and a list
 *
 * The strokes are a list in draw order, bottom-most first. Beside them:
 *
 * - **`brushes`**, `BrushCodec` text, deduplicated. Consecutive strokes drawn
 *   with one nib share an entry, which is the normal case, so a page of inking
 *   has two or three of them.
 * - **`clips`**, the selection paths that were live when strokes were drawn.
 *   Usually empty. A stroke drawn into a selection is *clipped pixels*, and a
 *   record that remembered only the samples would re-render outside the stencil
 *   the first time it was touched — silently, and long after the fact.
 * - **A [StrokeGrid]** over the page, so a tap measures a handful of strokes
 *   rather than all of them.
 *
 * ## Threading
 *
 * **Render thread, and only the render thread.** Every mutation arrives through
 * `CommitQueue`, which is the ordering argument this repo has already made four
 * times: a method that is called is a thing that has already happened, a value
 * that is enqueued is a thing that will happen in its turn. The UI thread never
 * touches this; what it sees is `LayerInfo.vector`, a boolean.
 */
class VectorSheet(
    val widthPx: Int,
    val heightPx: Int,
    cellPx: Int = StrokeGrid.DEFAULT_CELL_PX,
) {

    private val records = ArrayList<StrokeRecord>()
    private val brushText = ArrayList<String>()
    private val brushCache = ArrayList<Brush?>()
    private val clipPaths = ArrayList<Path>()
    private val grid = StrokeGrid(widthPx, heightPx, cellPx)

    /**
     * Where each id sits in [records].
     *
     * Kept rather than searched for, because `hit` has to answer *topmost* and
     * the grid answers *which*. Rebuilt whole on a structural edit — O(n) at
     * the speed a hand deletes strokes — and updated in place on an append,
     * which is the only operation that happens at pen rate.
     */
    private val position = HashMap<Long, Int>()

    private var nextId: Long = 1L

    /**
     * Whether the strokes still account for every pixel on the sheet.
     *
     * **The one thing Ik3 cannot do on its own**, said out loud rather than
     * left as a trap. The pixels are the truth about what the sheet looks like;
     * the records are what it can be *rebuilt* from, and the two agree only as
     * long as every change to the pixels also reaches the records. Drawing
     * does. Three things do not, and all three land before Ik5:
     *
     * - **Undo and redo.** They restore a rectangle of pixels through
     *   `PixelPatch`; the record list does not move. That is `DocStep`'s job.
     * - **A clear inside a selection.** It blanks part of the sheet, which on a
     *   record list is a partial erase — Ik8's split, not a deletion.
     * - **Dropping floating pixels**, which paints something that was never a
     *   stroke here.
     *
     * A sheet that has had one of those done to it is **spoiled**, and Ik4 will
     * refuse to rebuild it: the pixels stay, the strokes stay, and nothing
     * silently repaints the sheet into something the user did not draw.
     * Spoiling is coarse on purpose — anything that spoils one sheet spoils all
     * of them — because an over-cautious refusal costs a feature that has not
     * shipped yet, and a missed one costs a drawing.
     *
     * Ik5 and Ik8 remove the callers. When the last one goes, so does this.
     */
    var intact: Boolean = true
        private set

    /** Why it was spoiled, for the readout and for a test's message. */
    var spoiledBy: String = ""
        private set

    /** See [intact]. Idempotent; the first reason is the one kept. */
    fun spoil(reason: String) {
        if (!intact) return
        intact = false
        spoiledBy = reason
    }

    private val scratchIds = IdList()

    /** Bottom-most first: the order they were drawn and are re-rendered in. */
    val strokes: List<StrokeRecord> get() = records

    /** `BrushCodec` text, deduplicated. See the class note. */
    val brushes: List<String> get() = brushText

    /** Deduplicated; usually empty. See the class note. */
    val clips: List<Path> get() = clipPaths

    val size: Int get() = records.size

    val isEmpty: Boolean get() = records.isEmpty()

    /** What the records cost in memory, samples only. */
    val byteCount: Long
        get() {
            var n = 0L
            for (r in records) n += r.byteCount
            return n
        }

    // ------------------------------------------------------------- mutation

    /**
     * Turn [pending] into a record and put it on top.
     *
     * [clip] is the selection path that was live when the stroke was drawn, or
     * null. It is interned here rather than at the call site so that the
     * "consecutive strokes under one selection share one entry" case costs one
     * comparison instead of a table.
     */
    fun append(pending: PendingStroke, clip: Path?): StrokeRecord {
        val record = StrokeRecord(
            id = nextId++,
            brush = internBrush(pending.brushText),
            colorArgb = pending.colorArgb,
            erase = pending.erase,
            seed = pending.seed,
            dabBase = 0,
            clip = internClip(clip),
            bounds = pending.bounds,
            packed = pending.samples,
            sampleCount = pending.sampleCount,
        )
        put(record)
        return record
    }

    /**
     * Put a record back, keeping its id — undo's half of [remove], and what
     * `LayerOp.Open` uses to load a sheet from disk.
     *
     * Appends rather than inserting at its old position, and the two are the
     * same thing wherever it matters: ids ascend in draw order, so a record
     * restored out of order would break that. Ik5 restores in draw order, and
     * [reorder] is what puts the list back in it when a batch has been undone
     * piecemeal.
     */
    fun add(record: StrokeRecord) {
        if (record.id >= nextId) nextId = record.id + 1
        put(record)
    }

    /**
     * Sort the list by id, which is draw order. Cheap and idempotent; called
     * after a batch of [add]s that may have arrived in any order.
     */
    fun reorder() {
        records.sortBy { it.id }
        reindex()
    }

    /** Take these out, and answer with what was taken, in draw order. */
    fun remove(ids: LongArray): List<StrokeRecord> {
        if (ids.isEmpty()) return emptyList()
        val wanted = HashSet<Long>(ids.size * 2)
        for (id in ids) wanted.add(id)
        val taken = ArrayList<StrokeRecord>(ids.size)
        val kept = ArrayList<StrokeRecord>(records.size)
        for (r in records) {
            if (wanted.contains(r.id)) {
                taken.add(r)
                grid.remove(r.id)
            } else {
                kept.add(r)
            }
        }
        if (taken.isEmpty()) return emptyList()
        records.clear()
        records.addAll(kept)
        reindex()
        return taken
    }

    /**
     * Swap one record for several, in its place — Ik8's split.
     *
     * The replacements keep their own ids, which the caller allocates through
     * [nextIds] so that they sort between the record being replaced and the one
     * above it. Answers the record that was there, or null if it was not.
     */
    fun replace(id: Long, with: List<StrokeRecord>): StrokeRecord? {
        val at = position[id] ?: return null
        val old = records[at]
        grid.remove(id)
        records.removeAt(at)
        records.addAll(at, with)
        for (r in with) {
            if (r.id >= nextId) nextId = r.id + 1
            grid.add(r.id, r.bounds)
        }
        reindex()
        return old
    }

    /** [n] fresh ids, ascending, reserved. */
    fun nextIds(n: Int): LongArray = LongArray(n) { nextId++ }

    fun clear() {
        records.clear()
        brushText.clear()
        brushCache.clear()
        clipPaths.clear()
        position.clear()
        grid.clear()
        nextId = 1L
        // A whole clear is the one pixel operation outside drawing that the
        // record list *can* express exactly: nothing is left, and nothing is
        // recorded. So it un-spoils.
        intact = true
        spoiledBy = ""
    }

    // -------------------------------------------------------------- reading

    fun byId(id: Long): StrokeRecord? = position[id]?.let { records[it] }

    /**
     * The brush record [index] names, decoded once and kept.
     *
     * A brush this sheet has never heard of answers a default one rather than
     * throwing. That is the right shape for a *reader*: a clip or brush index
     * out of range means the file was written by something else, and a drawing
     * that opens with one stroke drawn by the wrong nib is recoverable where a
     * crash in the render thread is not.
     */
    fun brushAt(index: Int): Brush {
        if (index < 0 || index >= brushText.size) return Brush()
        brushCache[index]?.let { return it }
        val decoded = BrushCodec.decode(brushText[index]) ?: Brush()
        brushCache[index] = decoded
        return decoded
    }

    /** The clip path [index] names, or null. See [brushAt] about the range. */
    fun clipAt(index: Int): Path? =
        if (index < 0 || index >= clipPaths.size) null else clipPaths[index]

    /**
     * Strokes whose bounds meet [damage], in draw order.
     *
     * Ik4's input. The bounds and not the ink: narrowing further would mean
     * building the centreline of every candidate, which costs more than
     * re-drawing the handful this over-reports.
     */
    fun overlapping(damage: Bounds): List<StrokeRecord> {
        if (damage.isEmpty || records.isEmpty()) return emptyList()
        grid.query(damage, scratchIds)
        if (scratchIds.isEmpty) return emptyList()
        val out = ArrayList<StrokeRecord>(scratchIds.count)
        for (i in 0 until scratchIds.count) byId(scratchIds.id(i))?.let { out.add(it) }
        // The grid answers in ascending id order, which is draw order for
        // strokes as drawn — but a split puts new ids in the middle of the
        // list, so the list's own order is the authority.
        out.sortBy { position[it.id] ?: 0 }
        return out
    }

    /**
     * The topmost stroke whose *ink* covers this point, or null.
     *
     * Two stages, and the split is the whole reason the index exists: the grid
     * narrows a page of strokes to the handful whose rectangles contain the
     * point, and [be.thalos.artiest.engine.ink.StrokePolyline] decides which of
     * those the point is actually on.
     *
     * Topmost and not nearest. Which stroke a tap picks is decided by draw
     * order, because that is what the eye sees: the one on top is the one being
     * pointed at.
     */
    fun hit(xDoc: Float, yDoc: Float, slopDoc: Float): Long? {
        if (records.isEmpty()) return null
        grid.near(xDoc, yDoc, slopDoc + widestBrush(), scratchIds)
        var best: Long? = null
        var bestAt = -1
        for (i in 0 until scratchIds.count) {
            val id = scratchIds.id(i)
            val at = position[id] ?: continue
            if (at <= bestAt) continue
            val r = records[at]
            if (r.polyline(brushAt(r.brush)).hits(xDoc, yDoc, slopDoc)) {
                best = id
                bestAt = at
            }
        }
        return best
    }

    /**
     * Every stroke whose ink meets this document-space path, in draw order.
     *
     * The lasso. A [Region] scan-converts the path once and each candidate's
     * centreline is walked against it, which is the same two-stage shape as
     * [hit]: the grid narrows by rectangle, the region decides.
     *
     * A point test and not a stroke-width one, so a lasso that passes between
     * two points of a thick stroke without containing either misses it. That is
     * the same approximation `Marquee` already makes about its own path, and
     * the points are two document pixels apart.
     */
    fun hits(path: Path): LongArray {
        if (records.isEmpty() || path.isEmpty) return LongArray(0)
        val box = RectF()
        path.computeBounds(box, true)
        if (box.isEmpty) return LongArray(0)
        grid.query(Bounds.of(box.left, box.top, box.right, box.bottom), scratchIds)
        if (scratchIds.isEmpty) return LongArray(0)
        val region = Region()
        val clip = Rect(
            Math.floor(box.left.toDouble()).toInt(),
            Math.floor(box.top.toDouble()).toInt(),
            Math.ceil(box.right.toDouble()).toInt(),
            Math.ceil(box.bottom.toDouble()).toInt(),
        )
        region.setPath(path, Region(clip))
        val out = ArrayList<StrokeRecord>()
        for (i in 0 until scratchIds.count) {
            val r = byId(scratchIds.id(i)) ?: continue
            val line = r.polyline(brushAt(r.brush))
            for (p in 0 until line.pointCount) {
                if (region.contains(line.x(p).toInt(), line.y(p).toInt())) {
                    out.add(r)
                    break
                }
            }
        }
        out.sortBy { position[it.id] ?: 0 }
        return LongArray(out.size) { out[it].id }
    }

    /** Give back every derived centreline. See `StrokeRecord.dropDerived`. */
    fun dropDerived() {
        for (r in records) r.dropDerived()
    }

    override fun toString(): String =
        "VectorSheet(${records.size} strokes, ${brushText.size} brushes, " +
            "${clipPaths.size} clips, ${byteCount / 1024} KiB" +
            (if (intact) ")" else ", spoiled by $spoiledBy)")

    // ------------------------------------------------------------- internals

    private fun put(record: StrokeRecord) {
        position[record.id] = records.size
        records.add(record)
        grid.add(record.id, record.bounds)
    }

    private fun reindex() {
        position.clear()
        for (i in records.indices) position[records[i].id] = i
    }

    /**
     * Intern brush text, scanning from the newest entry.
     *
     * From the end because the case that happens is *consecutive strokes with
     * the same nib*: a user picks a brush and draws twenty strokes with it. A
     * `HashMap<String, Int>` would answer in one step and would also hold a
     * second copy of every brush's text as a key, which is the larger of the
     * two costs on a table that has three entries.
     */
    private fun internBrush(text: String): Int {
        for (i in brushText.indices.reversed()) if (brushText[i] == text) return i
        brushText.add(text)
        brushCache.add(null)
        return brushText.size - 1
    }

    private fun internClip(clip: Path?): Int {
        if (clip == null || clip.isEmpty) return StrokeRecord.NO_CLIP
        // Same argument as [internBrush], and more strongly: a selection is
        // made once and then drawn into, so the live one is nearly always the
        // last entry. Paths compare by geometry through `Path.op`, which is
        // dear, so the cheap test comes first.
        for (i in clipPaths.indices.reversed()) {
            if (samePath(clipPaths[i], clip)) return i
        }
        clipPaths.add(Path(clip))
        return clipPaths.size - 1
    }

    /**
     * Whether two paths enclose the same region.
     *
     * `Path` has no equals worth the name, so this is the trick `Selection`
     * already uses: the symmetric difference of two identical regions is empty.
     * Guarded by a bounds comparison, because the usual answer is "no" and
     * `Path.op` is not something to run down a table to find it out.
     */
    private fun samePath(a: Path, b: Path): Boolean {
        val ab = RectF()
        val bb = RectF()
        a.computeBounds(ab, true)
        b.computeBounds(bb, true)
        if (ab != bb) return false
        val diff = Path()
        if (!diff.op(a, b, Path.Op.XOR)) return false
        return diff.isEmpty
    }

    /**
     * How far the widest brush in the table can paint outside a centreline.
     *
     * The grid is queried by rectangle and a record's bounds is its *ink*, so
     * this is only needed to keep a tap near the edge of a big soft nib from
     * being narrowed away before the polyline gets to judge it. Computed over
     * the table rather than per record because the table has three entries.
     */
    private fun widestBrush(): Float {
        var w = 0f
        for (i in brushText.indices) {
            val b = brushAt(i)
            val half = (if (b.sizeMax > b.eraseSizeMax) b.sizeMax else b.eraseSizeMax) * 0.5f
            val reach = half + b.scatter.max
            if (reach > w) w = reach
        }
        return w
    }
}
