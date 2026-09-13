package be.thalos.artiest.engine.ink

/**
 * Where strokes cross, and where along a stroke a point falls.
 *
 * **Ik8's arithmetic, and it is the reason the Inker is worth building.**
 * `docs/inker-plan.md` calls erase-to-the-nearest-intersection *"the single
 * most-praised vector feature in CSP and the reason inkers use vector layers at
 * all"*, and the whole of it is this: given a tap on a stroke, find the two
 * places where another stroke crosses it on either side, and remove what is
 * between them. An inker overshoots a junction deliberately, then rubs the
 * overshoot back to it in one gesture.
 *
 * ## Against centrelines, and what that costs
 *
 * Crossings are computed between [StrokePolyline]s — the thinned centrelines —
 * and not between the strokes' outlines. Two consequences, and both are the
 * right trade:
 *
 * - **Two strokes that overlap without their spines crossing are not a
 *   junction.** A thick stroke laid alongside a thin one touches it for its
 *   whole length and crosses it nowhere, which is what a person means too: the
 *   junction is where the *lines* meet.
 * - A crossing is located to within the centreline's own two-pixel step, and
 *   then refined exactly on the segment pair it was found on. So the answer is
 *   exact for the polygon, and the polygon is within half a step of the stroke.
 *
 * ## Positions, and why they are sample indices
 *
 * Everything here answers in **record sample indices**, because that is what a
 * cut is made of: a new record is built from a range of samples. A position is
 * a `Float` so that a crossing between two polyline points can be reported
 * where it is rather than at the nearer end — `12.5` means half-way between
 * sample 12 and sample 13. [StrokeSplitter] rounds when it has to, and it is
 * the only place that decides how.
 *
 * Pure, JVM, no pixels. `:engine`.
 */
object StrokeGeometry {

    /**
     * Every place [other] crosses [line], as positions along [line], ascending.
     *
     * Answers into [out], which is cleared first and grown as needed; the count
     * is its size. A caller-owned accumulator rather than a returned list,
     * because Ik8's erase asks this of every stroke near the eraser and a list
     * per stroke is a list per stroke.
     *
     * A crossing found at the shared endpoint of two consecutive segments is
     * reported once, not twice. Without that, a stroke crossing exactly through
     * a polyline vertex would put two cuts a hair apart and leave a fragment of
     * a record between them that draws nothing.
     */
    fun crossings(line: StrokePolyline, other: StrokePolyline, out: FloatList) {
        out.clear()
        if (line.pointCount < 2 || other.pointCount < 2) return
        if (!line.bounds.intersects(other.bounds)) return
        for (i in 0 until line.pointCount - 1) {
            val ax = line.x(i)
            val ay = line.y(i)
            val bx = line.x(i + 1)
            val by = line.y(i + 1)
            for (j in 0 until other.pointCount - 1) {
                val t = segmentCross(
                    ax, ay, bx, by,
                    other.x(j), other.y(j), other.x(j + 1), other.y(j + 1),
                )
                if (t < 0f) continue
                val at = positionOf(line, i, t)
                // The shared-endpoint case: `t == 1` on one segment is `t == 0`
                // on the next, and both report the same point.
                if (out.count > 0 && Math.abs(out[out.count - 1] - at) < SAME_POINT) continue
                out.add(at)
            }
        }
        out.sortAscending()
        out.dropRepeats(SAME_POINT)
    }

    /**
     * Where a stroke crosses **itself**, as positions along [line], ascending.
     *
     * A loop is a junction too — an inker draws one to close a shape and then
     * rubs the tail back to where it met itself — and a self-crossing is
     * exactly the case [crossings] cannot find, because a polyline always
     * "crosses" its own neighbouring segments at their shared endpoint.
     *
     * So segments closer together than [SELF_SKIP] along the line are not
     * compared. Two, which is one segment of slack: adjacent segments meet by
     * construction, and the one after that can only meet by doubling back
     * inside four document pixels, which no hand does.
     */
    fun selfCrossings(line: StrokePolyline, out: FloatList) {
        out.clear()
        if (line.pointCount < 4) return
        for (i in 0 until line.pointCount - 1) {
            for (j in i + SELF_SKIP until line.pointCount - 1) {
                val t = segmentCross(
                    line.x(i), line.y(i), line.x(i + 1), line.y(i + 1),
                    line.x(j), line.y(j), line.x(j + 1), line.y(j + 1),
                )
                if (t < 0f) continue
                out.add(positionOf(line, i, t))
            }
        }
        out.sortAscending()
        out.dropRepeats(SAME_POINT)
    }

    /**
     * The position along [line] nearest to ([xDoc], [yDoc]), in sample indices.
     *
     * Where a tap lands, in the terms a cut is made of. Negative for a polyline
     * with no segments, which is a stroke that is one dot and cannot be cut.
     */
    fun positionOf(line: StrokePolyline, xDoc: Float, yDoc: Float): Float {
        if (line.pointCount == 0) return -1f
        if (line.pointCount == 1) return line.sampleAt(0).toFloat()
        var best = Float.MAX_VALUE
        var at = -1f
        for (i in 0 until line.pointCount - 1) {
            val ax = line.x(i)
            val ay = line.y(i)
            val vx = line.x(i + 1) - ax
            val vy = line.y(i + 1) - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 <= 0f) 0f else {
                (((xDoc - ax) * vx + (yDoc - ay) * vy) / len2).coerceIn(0f, 1f)
            }
            val dx = xDoc - (ax + vx * t)
            val dy = yDoc - (ay + vy * t)
            val d = dx * dx + dy * dy
            if (d < best) {
                best = d
                at = positionOf(line, i, t)
            }
        }
        return at
    }

    /**
     * The two entries of [cuts] that bracket [at], as a pair of sample
     * positions, or the ends of [line] where there is no crossing on that side.
     *
     * Erase-to-the-nearest-intersection, once the crossings are known. Written
     * into [out] as two floats — first the one before, then the one after — and
     * answering false when the stroke has no samples to cut.
     *
     * **Falling off the end is the normal case, not a failure.** The commonest
     * gesture this feature exists for is rubbing back an overshoot: a tail that
     * sticks out past a junction has a crossing on one side and the end of the
     * stroke on the other, and taking the whole tail is exactly what the hand
     * meant.
     */
    fun bracket(line: StrokePolyline, cuts: FloatList, at: Float, out: FloatArray): Boolean {
        if (line.pointCount == 0 || out.size < 2) return false
        var before = line.sampleAt(0).toFloat()
        var after = line.sampleAt(line.pointCount - 1).toFloat()
        for (i in 0 until cuts.count) {
            val c = cuts[i]
            if (c <= at && c > before) before = c
            if (c >= at && c < after) after = c
        }
        if (after < before) return false
        out[0] = before
        out[1] = after
        return true
    }

    /**
     * Where the two segments cross, as a fraction along the first, or -1.
     *
     * The standard determinant form. Parallel and collinear both answer -1:
     * collinear segments overlap rather than cross, and "the junction" is not a
     * point there — reporting one would cut a stroke at an arbitrary place
     * along a stretch where two lines run together.
     *
     * The endpoints are inclusive on both segments, because a stroke that ends
     * exactly on another is a junction to an inker and the arithmetic has no
     * reason to disagree. [crossings] is what stops the shared endpoint of two
     * consecutive segments being reported twice.
     */
    private fun segmentCross(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        val rx = bx - ax
        val ry = by - ay
        val sx = dx - cx
        val sy = dy - cy
        val denom = rx * sy - ry * sx
        if (denom == 0f) return -1f
        val qpx = cx - ax
        val qpy = cy - ay
        val t = (qpx * sy - qpy * sx) / denom
        val u = (qpx * ry - qpy * rx) / denom
        if (t < 0f || t > 1f || u < 0f || u > 1f) return -1f
        return t
    }

    /** Segment [i] at fraction [t], as a sample position. */
    private fun positionOf(line: StrokePolyline, i: Int, t: Float): Float {
        val a = line.sampleAt(i).toFloat()
        val b = line.sampleAt(i + 1).toFloat()
        return a + (b - a) * t
    }

    /**
     * How close two crossings have to be to be the same one, in sample
     * indices. A fifth of a sample: at 321.75 Hz that is 0.6 ms of hand
     * movement, which is nothing, and it is well below the one-sample
     * resolution a cut is made at.
     */
    const val SAME_POINT: Float = 0.2f

    /** See [selfCrossings]. */
    const val SELF_SKIP: Int = 2
}

/**
 * A growable list of floats, so that asking "where does this stroke cross" of
 * fifty strokes allocates nothing.
 *
 * `IdList`'s counterpart, and it exists for the same reason: a
 * `MutableList<Float>` boxes every entry, on a path an eraser drag runs eight
 * times a second.
 */
class FloatList(initialCapacity: Int = 16) {

    private var values = FloatArray(if (initialCapacity < 1) 1 else initialCapacity)

    var count: Int = 0
        private set

    operator fun get(i: Int): Float = values[i]

    val isEmpty: Boolean get() = count == 0

    fun clear() {
        count = 0
    }

    fun add(v: Float) {
        if (count == values.size) values = values.copyOf(values.size * 2)
        values[count++] = v
    }

    fun toArray(): FloatArray = values.copyOf(count)

    fun sortAscending() {
        if (count > 1) java.util.Arrays.sort(values, 0, count)
    }

    /** Drop entries within [epsilon] of the one before. Sort first. */
    fun dropRepeats(epsilon: Float) {
        if (count < 2) return
        var w = 1
        for (r in 1 until count) {
            if (values[r] - values[w - 1] > epsilon) values[w++] = values[r]
        }
        count = w
    }

    override fun toString(): String = "FloatList($count)"
}
