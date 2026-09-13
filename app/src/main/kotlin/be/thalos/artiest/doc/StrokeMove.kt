package be.thalos.artiest.doc

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.StrokeRecord
import be.thalos.artiest.engine.ink.StrokeTransform

/**
 * Ik9: dragging, turning and resizing picked strokes.
 *
 * The arithmetic is `StrokeTransform`'s and it is pure. What lives here is the
 * two things that are the *sheet's*: which brush the moved strokes name, and
 * what rectangle they now paint.
 *
 * ## Scaling the nib with the mark
 *
 * Mapping the samples moves the dabs further apart; it does not make them
 * bigger, because a dab's size comes from the brush. So a stroke scaled by two
 * would stretch and stay thin. The answer is Ik10's, reused: intern a copy of
 * the brush with its size range scaled by the same factor, and let the moved
 * records name that. One new table entry per (brush, scale) pair, deduplicated
 * like every other.
 *
 * A move — no scale — therefore adds no brush at all, which is the common case.
 *
 * **Render thread**, like the sheet it reads.
 */
object StrokeMove {

    /**
     * What [op] would do to these strokes, or null for nothing.
     *
     * Null for an identity transform, which is what a tap on the box produces
     * and what a drag that came back to where it started produces — neither
     * should put a press of the undo button between the user and the last thing
     * they actually did.
     */
    fun plan(
        op: StrokeOp.Transform,
        picked: LongArray,
        sheet: VectorSheet,
        layerId: Int,
    ): VectorStep? {
        if (picked.isEmpty()) return null
        if (StrokeTransform.isIdentity(op.matrix)) return null
        val scale = StrokeTransform.scaleOf(op.matrix)
        val removed = ArrayList<StrokeRecord>(picked.size)
        val added = ArrayList<StrokeRecord>(picked.size)
        for (id in picked) {
            val old = sheet.byId(id) ?: continue
            val pen = sheet.brushAt(old.brush)
            val brush = if (scaledAway(scale)) sheet.brushIndexOf(scaledText(pen, scale)) else old.brush
            val moved = StrokeTransform.mapped(
                old, op.matrix, sheet.nextIds(1)[0], brush, Bounds.EMPTY,
            )
            removed.add(old)
            added.add(withBounds(moved, sheet.brushAt(brush), old.bounds))
        }
        if (removed.isEmpty()) return null
        return VectorStep(layerId, added = added, removed = removed)
    }

    /** Apply a planned move. See `StrokeEraser.apply` for why it is shaped so. */
    fun apply(step: VectorStep, sheet: VectorSheet) {
        if (step.removed.isNotEmpty()) {
            sheet.remove(LongArray(step.removed.size) { step.removed[it].id })
        }
        for (r in step.added) sheet.add(r)
        sheet.reorder()
    }

    /**
     * A scale far enough from 1 to be worth a table entry.
     *
     * Half a percent. Below it the nib would round to the same mask bucket
     * anyway — `MaskTolerance` quantises size at 3% — so a table entry per
     * pixel of drag would be a table nobody could read for a difference nobody
     * could see.
     */
    private fun scaledAway(scale: Float): Boolean = kotlin.math.abs(scale - 1f) > 0.005f

    private fun scaledText(pen: Brush, scale: Float): String {
        val copy = BrushCodec.decode(BrushCodec.encode(pen)) ?: Brush()
        copy.sizeMin = pen.sizeMin * scale
        copy.sizeMax = pen.sizeMax * scale
        return BrushCodec.encode(copy)
    }

    /**
     * The record again with the rectangle it now paints, unioned with the one
     * it painted before.
     *
     * Both, for `StrokeRestyle`'s reason: the old rectangle is the ground a
     * rebuild has to clear, and the new one is where the ink is going.
     */
    private fun withBounds(record: StrokeRecord, pen: Brush, before: Bounds): StrokeRecord {
        val line = record.polyline(pen)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until line.pointCount) {
            val half = line.halfWidth(i) + pen.scatter.max
            l = minOf(l, line.x(i) - half)
            t = minOf(t, line.y(i) - half)
            r = maxOf(r, line.x(i) + half)
            b = maxOf(b, line.y(i) + half)
        }
        val now = if (l > r) before else Bounds.of(l, t, r, b).unionWith(before)
        record.dropDerived()
        return StrokeRecord(
            id = record.id,
            brush = record.brush,
            colorArgb = record.colorArgb,
            erase = record.erase,
            seed = record.seed,
            dabBase = record.dabBase,
            clip = record.clip,
            bounds = now,
            packed = record.copyPackedBytes(),
            sampleCount = record.sampleCount,
        )
    }
}
