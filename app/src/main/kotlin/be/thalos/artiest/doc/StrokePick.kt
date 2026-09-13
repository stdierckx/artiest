package be.thalos.artiest.doc

import android.graphics.Path
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.StrokeRecord

/**
 * What a gesture asks of a sheet's strokes, on its way to the render thread:
 * which ones are picked, and which ones the eraser took.
 *
 * A sealed hierarchy beside `SelectOp` rather than more members of it, because
 * the two answer different questions — *which pixels* and *which strokes* — and
 * a single type would mean every consumer of either had a `when` with half its
 * branches saying "not mine". They share the queue, which is the thing that
 * actually has to be shared: "select this" and "rub that out" both mean *after
 * everything I have drawn*, and either applied straight from the UI thread
 * would land in front of a stroke the render thread has not stamped yet — and
 * then fail to find it, which looks like the app ignoring the pen.
 *
 * Picking and erasing are one hierarchy rather than two because they arrive
 * from the same gestures, through the same queue, against the same sheet, and
 * because the eraser's own "take the picked strokes" is both at once.
 */
sealed interface StrokeOp {

    /**
     * The topmost stroke whose ink covers this point, within [slopDoc].
     *
     * Topmost and not nearest: which stroke a tap picks is decided by draw
     * order, because that is what the eye sees.
     */
    class Tap(
        val xDoc: Float,
        val yDoc: Float,
        val slopDoc: Float,
        val mode: SelectMode,
    ) : StrokeOp

    /**
     * Every stroke whose centreline the lasso encloses.
     *
     * The [path] is copied at construction for the reason `SelectOp.Shape`
     * copies its own: a `Path` is mutable, `Marquee` reuses one across
     * gestures, and an op holding the builder's path describes whatever the
     * next gesture draws.
     */
    class Lasso(path: Path, val mode: SelectMode) : StrokeOp {
        val path: Path = Path(path)
    }

    /** Every stroke on the sheet. */
    object All : StrokeOp

    /** Nothing. */
    object None : StrokeOp

    /** Everything that was not picked, and nothing that was. */
    object Invert : StrokeOp

    /**
     * The eraser, in one of its three modes.
     *
     * [path] is the eraser's own centreline in document space and [radiusDoc]
     * is how wide its nib is, so "what it touched" is the same question the
     * pen's own dabs answer. The path is copied for the reason [Lasso]'s is.
     *
     * **Three modes and not three ops**, because they differ only in how much
     * of a touched stroke goes: the search for *which* strokes were touched and
     * the machinery that replaces them are the same in all three, and three
     * classes would be three copies of that.
     */
    class Erase(path: Path, val radiusDoc: Float, val mode: EraseMode) : StrokeOp {
        val path: Path = Path(path)
    }

    /** Take the picked strokes off the sheet. */
    object DeletePicked : StrokeOp

    /**
     * Give the picked strokes a different colour, a different nib, or both.
     *
     * **All four of `docs/inker-plan.md`'s restyle operations are this one.**
     * The plan lists recolour, re-brush, scale the width and re-stabilise as
     * four things; three of them are a *brush* — width is `sizeMin`/`sizeMax`,
     * stabilisation is `stabilization`, and re-brush is the whole of it — so
     * the caller scales or tunes a copy of the brush and hands over its text.
     * One op, one undo step, one repaint.
     *
     * Null means "leave it alone", which is what makes "recolour without
     * changing the nib" expressible.
     */
    class Restyle(
        val colorArgb: Int?,
        /** `BrushCodec.encode` of the brush to give them, or null. */
        val brushText: String?,
    ) : StrokeOp
}

/**
 * How much of a stroke the eraser takes.
 *
 * `docs/inker-plan.md` calls the middle one *"the single most-praised vector
 * feature in CSP and the reason inkers use vector layers at all"*, and the
 * reason is the gesture it replaces: an inker overshoots a junction on purpose,
 * because stopping exactly on one makes a timid line, and then rubs the
 * overshoot back. On a raster sheet that is a careful second gesture; here it
 * is a tap.
 */
enum class EraseMode {
    /** Anything the eraser touches goes, whole. */
    WHOLE,

    /**
     * The stretch between the crossings either side of the touch.
     *
     * Falling off the end is the normal case rather than a failure: a tail
     * sticking out past a junction has a crossing on one side and the end of
     * the stroke on the other, and taking the whole tail is what the hand
     * meant.
     */
    TO_JUNCTION,

    /** Exactly the stretch the eraser passed over. */
    PART,
}

/**
 * What the UI thread is told about the picked strokes.
 *
 * Immutable and published whole, the same shape `SelectionInfo` has and for the
 * same reason: the render thread owns the sheet and the UI thread draws a
 * highlight, and handing over a live object would be handing over something
 * that changes while it is being drawn.
 *
 * [outline] is the picked strokes' centrelines in **document** space, as one
 * path. Centrelines and not outlines: a stroke's outline is the expensive
 * derived thing this whole design avoids computing, and a highlight drawn along
 * the spine reads as "this one" perfectly well — it is what a vector editor's
 * own highlight is. Null when nothing is picked.
 */
class StrokePickInfo(
    /** Which sheet these ids belong to. Ids are unique within a sheet only. */
    val layerId: Int,
    val ids: LongArray,
    val outline: Path?,
    val bounds: Bounds,
) {
    val count: Int get() = ids.size

    val active: Boolean get() = ids.isNotEmpty()

    companion object {
        val NONE = StrokePickInfo(0, LongArray(0), null, Bounds.EMPTY)
    }
}

/**
 * Which strokes are picked, on the sheet they are picked on.
 *
 * **Render thread**, like the sheet it points into and for the same reason:
 * every change to it arrives through `CommitQueue`, so it cannot disagree with
 * the strokes it names about what order things happened in. The UI thread reads
 * [snapshot] and nothing else.
 *
 * The set is cleared whenever the pen moves to another sheet, and that is not
 * tidiness. A stroke id is unique **within a sheet**, so a set held across a
 * sheet change would name other strokes — and the first thing done to it, an
 * erase or a drag, would happen to them.
 */
class StrokePick {

    private var ids = LinkedHashSet<Long>()

    /** The sheet [ids] belong to, or 0 when nothing is picked. */
    var layerId: Int = 0
        private set

    @Volatile
    var snapshot: StrokePickInfo = StrokePickInfo.NONE
        private set

    val count: Int get() = ids.size

    val active: Boolean get() = ids.isNotEmpty()

    fun contains(id: Long): Boolean = ids.contains(id)

    /** In draw order, which is ascending id for strokes as drawn. */
    fun toArray(): LongArray = ids.toLongArray().also { it.sort() }

    /**
     * Apply [op] against [sheet] on layer [layerId], and republish.
     *
     * Returns true when the picked set changed, which is what the caller uses
     * to decide whether a redraw is worth asking for. False is not an error — a
     * tap on blank paper with nothing picked is an ordinary thing to do.
     */
    fun apply(op: StrokeOp, sheet: VectorSheet, layerId: Int): Boolean {
        if (this.layerId != layerId) {
            ids = LinkedHashSet()
            this.layerId = layerId
        }
        val before = ids.size
        val was = if (before == 0) null else LinkedHashSet(ids)
        when (op) {
            is StrokeOp.Tap -> {
                val hit = sheet.hit(op.xDoc, op.yDoc, op.slopDoc)
                combine(if (hit == null) LongArray(0) else longArrayOf(hit), op.mode)
            }
            is StrokeOp.Lasso -> combine(sheet.hits(op.path), op.mode)
            StrokeOp.All -> {
                ids = LinkedHashSet()
                for (r in sheet.strokes) ids.add(r.id)
            }
            StrokeOp.None -> ids = LinkedHashSet()
            StrokeOp.Invert -> {
                val kept = ids
                ids = LinkedHashSet()
                for (r in sheet.strokes) if (!kept.contains(r.id)) ids.add(r.id)
            }
            // The two that edit the sheet rather than the set. They come here
            // only to be refused: `StrokeEraser` owns them, and the pruning
            // afterwards is what moves this object.
            is StrokeOp.Erase, StrokeOp.DeletePicked, is StrokeOp.Restyle -> return false
        }
        if (ids.size == before && (was == null || was == ids)) return false
        publish(sheet)
        return true
    }

    /**
     * Pick exactly these, and republish.
     *
     * For an edit that *replaces* records: a restyled or split stroke is a new
     * record with a new id, and without this the user would watch their
     * selection vanish for having changed its colour. Ids the sheet does not
     * have are skipped rather than refused, because an edit that dropped a
     * piece — a fragment too short to draw — did the right thing.
     */
    fun setTo(wanted: LongArray, sheet: VectorSheet, layerId: Int): Boolean {
        val next = LinkedHashSet<Long>(wanted.size * 2)
        for (id in wanted) if (sheet.byId(id) != null) next.add(id)
        if (this.layerId == layerId && next == ids) return false
        ids = next
        this.layerId = layerId
        publish(sheet)
        return true
    }

    /** Forget everything and republish. Called when the pen changes sheet. */
    fun clear(): Boolean {
        if (ids.isEmpty() && layerId == 0) return false
        ids = LinkedHashSet()
        layerId = 0
        snapshot = StrokePickInfo.NONE
        return true
    }

    /**
     * Drop ids the sheet no longer has, after an edit removed them.
     *
     * Without it, erasing a picked stroke would leave it picked, and the next
     * operation on the set would be a no-op nobody could explain.
     */
    fun prune(sheet: VectorSheet): Boolean {
        val before = ids.size
        ids.removeAll { sheet.byId(it) == null }
        if (ids.size == before) return false
        publish(sheet)
        return true
    }

    /** Rebuild the published snapshot from the sheet as it is now. */
    fun republish(sheet: VectorSheet) = publish(sheet)

    private fun combine(hits: LongArray, mode: SelectMode) {
        when (mode) {
            SelectMode.NEW -> {
                ids = LinkedHashSet()
                for (id in hits) ids.add(id)
            }
            SelectMode.ADD -> for (id in hits) ids.add(id)
            SelectMode.SUBTRACT -> for (id in hits) ids.remove(id)
            SelectMode.INTERSECT -> {
                val keep = HashSet<Long>(hits.size * 2)
                for (id in hits) keep.add(id)
                ids.removeAll { !keep.contains(it) }
            }
        }
    }

    private fun publish(sheet: VectorSheet) {
        if (ids.isEmpty()) {
            snapshot = StrokePickInfo(layerId, LongArray(0), null, Bounds.EMPTY)
            return
        }
        val ordered = toArray()
        val outline = Path()
        var bounds = Bounds.EMPTY
        for (id in ordered) {
            val record: StrokeRecord = sheet.byId(id) ?: continue
            bounds = bounds.unionWith(record.bounds)
            val line = record.polyline(sheet.brushAt(record.brush))
            if (line.pointCount == 0) continue
            outline.moveTo(line.x(0), line.y(0))
            for (i in 1 until line.pointCount) outline.lineTo(line.x(i), line.y(i))
            // A one-point stroke is a dot, and a path with a single moveTo
            // draws nothing at all -- so it gets a segment to itself rather
            // than being invisible in the highlight while being selected.
            if (line.pointCount == 1) outline.lineTo(line.x(0) + 0.5f, line.y(0))
        }
        snapshot = StrokePickInfo(layerId, ordered, outline, bounds)
    }
}
