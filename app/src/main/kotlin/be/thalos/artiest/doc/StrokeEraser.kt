package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.RectF
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.FloatList
import be.thalos.artiest.engine.ink.StrokeGeometry
import be.thalos.artiest.engine.ink.StrokePolyline
import be.thalos.artiest.engine.ink.StrokeRecord
import be.thalos.artiest.engine.ink.StrokeSplitter

/**
 * The eraser, on a sheet that keeps its strokes.
 *
 * Turns one gesture into one `VectorStep`: which records went, which took their
 * place, and the rectangle that has to be repainted. It changes nothing itself —
 * `apply` does — so that the same object can be asked what an erase *would* do,
 * which is what a test wants and what a future preview would.
 *
 * ## The three modes are one search
 *
 * All three start the same way: walk the eraser's own centreline, find every
 * stroke near it, and find which stretches of each are within reach. They
 * differ only in how much of a touched stroke goes:
 *
 * - [EraseMode.WHOLE] takes the stroke.
 * - [EraseMode.PART] takes the stretches it touched, and no more.
 * - [EraseMode.TO_JUNCTION] takes from the crossing before the touch to the
 *   crossing after it.
 *
 * ## Reach
 *
 * A stroke is touched where the eraser's nib overlaps its ink: the distance
 * between the two centrelines is under the eraser's radius plus the stroke's
 * own half-width at that point. That is the same question the pen's dabs answer
 * when they paint, which is what makes the rub land where the user saw the grey
 * circle go.
 *
 * **Render thread**, like the sheet it reads.
 */
class StrokeEraser {

    private val crossings = FloatList()
    private val bracket = FloatArray(2)
    private val touched = ArrayList<Int>()

    /**
     * What [op] would do to [sheet] on layer [layerId], or null for nothing.
     *
     * Null rather than an empty step, because "the eraser passed over blank
     * paper" is an ordinary thing to do and recording an undo step for it would
     * put a press of the undo button between the user and the last thing they
     * actually did.
     */
    fun plan(op: StrokeOp.Erase, sheet: VectorSheet, layerId: Int): VectorStep? {
        val line = eraserLine(op) ?: return null
        val reach = op.radiusDoc
        val box = RectF()
        op.path.computeBounds(box, true)
        val near = sheet.overlapping(
            Bounds.of(box.left - reach, box.top - reach, box.right + reach, box.bottom + reach)
        )
        if (near.isEmpty()) return null

        val removed = ArrayList<StrokeRecord>()
        val added = ArrayList<StrokeRecord>()
        for (record in near) {
            val pen = sheet.brushAt(record.brush)
            val shape = record.polyline(pen)
            if (shape.pointCount == 0) continue
            if (!runsTouching(shape, line, reach)) continue
            removed.add(record)
            when (op.mode) {
                EraseMode.WHOLE -> Unit
                EraseMode.PART -> added.addAll(keepingAround(record, shape, sheet, pen))
                EraseMode.TO_JUNCTION -> added.addAll(toJunction(record, shape, sheet, pen))
            }
        }
        if (removed.isEmpty()) return null
        return VectorStep(layerId, added = added, removed = removed)
    }

    /** Take the picked strokes off, whole. */
    fun planDelete(picked: LongArray, sheet: VectorSheet, layerId: Int): VectorStep? {
        if (picked.isEmpty()) return null
        val removed = ArrayList<StrokeRecord>(picked.size)
        for (id in picked) sheet.byId(id)?.let { removed.add(it) }
        if (removed.isEmpty()) return null
        return VectorStep(layerId, added = emptyList(), removed = removed)
    }

    /**
     * Apply a planned step to [sheet].
     *
     * The same body `VectorStep.exchange` runs in reverse, deliberately: an
     * edit and its undo have to be the same operation or they drift, and the
     * way to make that true is for both to go through the sheet's own `remove`
     * and `add`.
     */
    fun apply(step: VectorStep, sheet: VectorSheet) {
        if (step.removed.isNotEmpty()) {
            sheet.remove(LongArray(step.removed.size) { step.removed[it].id })
        }
        for (r in step.added) sheet.add(r)
        if (step.added.isNotEmpty()) sheet.reorder()
    }

    // ------------------------------------------------------------ internals

    /**
     * The eraser's gesture as a polyline, at a fixed half-width of zero.
     *
     * Built out of the `Path` the gesture produced rather than out of the
     * samples, because by the time an erase reaches the render thread the
     * samples are gone — the gesture is a shape, like a marquee. The reach is
     * carried separately in [StrokeOp.Erase.radiusDoc], which is why the
     * half-widths here are zero.
     */
    private fun eraserLine(op: StrokeOp.Erase): FloatArray? {
        val measure = android.graphics.PathMeasure(op.path, false)
        val point = FloatArray(2)
        val out = ArrayList<Float>(64)
        do {
            val length = measure.length
            if (length <= 0f) {
                // A tap: the path is a point, and `PathMeasure` measures no
                // length along it. Its bounds still says where it is.
                val box = RectF()
                op.path.computeBounds(box, true)
                out.add(box.centerX())
                out.add(box.centerY())
                continue
            }
            val steps = Math.ceil((length / STEP_DOC).toDouble()).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                if (!measure.getPosTan(length * i / steps, point, null)) continue
                out.add(point[0])
                out.add(point[1])
            }
        } while (measure.nextContour())
        if (out.isEmpty()) return null
        return FloatArray(out.size) { out[it] }
    }

    /** Whether any point of [shape] is within [reach] of the eraser. */
    private fun runsTouching(shape: StrokePolyline, eraser: FloatArray, reach: Float): Boolean {
        touched.clear()
        for (i in 0 until shape.pointCount) {
            if (withinReach(shape, i, eraser, reach)) touched.add(i)
        }
        return touched.isNotEmpty()
    }

    private fun withinReach(
        shape: StrokePolyline,
        i: Int,
        eraser: FloatArray,
        reach: Float,
    ): Boolean {
        val x = shape.x(i)
        val y = shape.y(i)
        val limit = reach + shape.halfWidth(i)
        val limit2 = limit * limit
        var j = 0
        while (j + 3 < eraser.size) {
            if (distance2ToSegment(x, y, eraser[j], eraser[j + 1], eraser[j + 2], eraser[j + 3])
                <= limit2
            ) {
                return true
            }
            j += 2
        }
        // A one-point eraser path — a tap — has no segment to walk.
        if (eraser.size == 2) {
            val dx = x - eraser[0]
            val dy = y - eraser[1]
            return dx * dx + dy * dy <= limit2
        }
        return false
    }

    /**
     * The pieces of [record] that the eraser did **not** pass over.
     *
     * The touched points are contiguous runs along the stroke, and what
     * survives is the gaps between them. Computed as the complement rather than
     * as "from the first touch to the last", because a drag that crosses one
     * stroke twice would otherwise take the untouched middle with it — which is
     * a stretch of ink the user watched the eraser miss.
     */
    private fun keepingAround(
        record: StrokeRecord,
        shape: StrokePolyline,
        sheet: VectorSheet,
        pen: Brush,
    ): List<StrokeRecord> {
        val last = record.sampleCount - 1
        val keep = ArrayList<Int>(6)
        var cursor = 0
        var i = 0
        while (i < touched.size) {
            var j = i
            while (j + 1 < touched.size && touched[j + 1] == touched[j] + 1) j++
            val from = shape.sampleAt(touched[i])
            val to = shape.sampleAt(touched[j])
            if (from > cursor) {
                keep.add(cursor)
                keep.add(from - 1)
            }
            cursor = to + 1
            i = j + 1
        }
        if (cursor <= last) {
            keep.add(cursor)
            keep.add(last)
        }
        if (keep.isEmpty()) return emptyList()
        return StrokeSplitter.pieces(record, keep.toIntArray(), sheet.nextIds(keep.size / 2), pen)
    }

    /**
     * The pieces of [record] left after the stretch between the crossings
     * either side of the first touch is taken.
     *
     * The crossings are against every other stroke the eraser was near, plus
     * the stroke's own self-crossings — a loop is a junction too, and closing
     * one and rubbing the tail back to where it met itself is the same gesture
     * as rubbing an overshoot back to another line.
     */
    private fun toJunction(
        record: StrokeRecord,
        shape: StrokePolyline,
        sheet: VectorSheet,
        pen: Brush,
    ): List<StrokeRecord> {
        // **Against the strokes near the *stroke*, not near the eraser.** The
        // gesture this exists for is a tap on an overshoot, and the junction it
        // has to find is at the other end of that overshoot — usually a long
        // way from where the pen touched down. Searching the eraser's own
        // neighbourhood finds nothing and takes the whole stroke, which is the
        // wrong answer wearing the right shape.
        val all = FloatList()
        for (other in sheet.overlapping(record.bounds)) {
            if (other.id == record.id) continue
            val line = other.polyline(sheet.brushAt(other.brush))
            StrokeGeometry.crossings(shape, line, crossings)
            for (k in 0 until crossings.count) all.add(crossings[k])
        }
        StrokeGeometry.selfCrossings(shape, crossings)
        for (k in 0 until crossings.count) all.add(crossings[k])
        all.sortAscending()
        all.dropRepeats(StrokeGeometry.SAME_POINT)

        val at = shape.sampleAt(touched[touched.size / 2]).toFloat()
        if (!StrokeGeometry.bracket(shape, all, at, bracket)) return emptyList()
        val from = Math.round(bracket[0])
        val to = Math.round(bracket[1])
        val pieces = StrokeSplitter.pieceCount(record, from, to)
        if (pieces == 0) return emptyList()
        return StrokeSplitter.without(record, from, to, sheet.nextIds(pieces), pen)
    }

    private fun distance2ToSegment(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 <= 0f) 0f else {
            (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        }
        val dx = px - (ax + vx * t)
        val dy = py - (ay + vy * t)
        return dx * dx + dy * dy
    }

    companion object {
        /**
         * How finely the eraser's own path is walked, in document pixels.
         *
         * Two, which is `StrokePolyline.MIN_STEP_DOC`: the thing it is being
         * compared against is sampled at that step, so a finer walk compares
         * more points against the same information.
         */
        const val STEP_DOC: Float = 2f
    }
}
