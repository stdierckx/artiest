package be.thalos.artiest.engine.guide

/**
 * A French curve: a shape you lay on the page and draw along.
 *
 * `docs/guides-plan.md` items 12 and 15, which are the same item twice. The
 * interesting half of a curve guide is not projecting onto a polyline — that is
 * the arithmetic below and it is fifteen lines — it is **getting the polyline**,
 * and the honest answer to that is *draw one*. Ik7 already picks a stroke and
 * Ik1 already keeps its centreline, so a curve guide is a stroke you have
 * already made, turned into furniture.
 *
 * ## A polyline and not a spline
 *
 * The points come from a stroke's own centreline, which `StrokePolyline` has
 * already simplified to two document pixels apart. Fitting a spline through
 * them and projecting onto *that* would be a second approximation of a curve
 * that is already an approximation of a hand movement, and the hand cannot tell
 * the difference at two pixels: a guide is drawn against at a pen tip that is
 * itself several pixels wide.
 *
 * ## What happens at the ends
 *
 * The nearest point is **clamped** to the ends, which is what a physical French
 * curve does: the line follows it and stops where the curve stops. It reads as
 * a stop rather than a blob because the dab emitter spaces by arc length — a
 * point that has stopped moving lays no more dabs.
 *
 * The alternative was to answer false past the ends and let the stroke continue
 * freehand, and it is worse: the ink would jump from the end of the curve to
 * wherever the hand had got to, which is a kink at the moment the hand is going
 * fastest.
 */
class CurveGuide(
    points: FloatArray,
    /** How many points are in [points]; the array may be longer. */
    val pointCount: Int,
) : Guide {

    /** x, y pairs. Copied in, for `StrokeRecord`'s reason: the caller reuses. */
    private val points: FloatArray = points.copyOf(pointCount * 2)

    init {
        require(pointCount >= 2) { "a curve needs two points, not $pointCount" }
        require(points.size >= pointCount * 2) {
            "${points.size} floats for $pointCount points"
        }
        for (i in 0 until pointCount * 2) {
            require(this.points[i].isFinite()) { "a curve point was ${this.points[i]}" }
        }
    }

    fun xAt(i: Int): Float = points[i * 2]

    fun yAt(i: Int): Float = points[i * 2 + 1]

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        var best = Float.MAX_VALUE
        var bx = 0f
        var by = 0f
        var ax = points[0]
        var ay = points[1]
        for (i in 1 until pointCount) {
            val cx = points[i * 2]
            val cy = points[i * 2 + 1]
            val dx = cx - ax
            val dy = cy - ay
            val len2 = dx * dx + dy * dy
            // A zero-length segment is the point itself, which the clamp below
            // would divide by. It happens: a hand that stopped dead leaves two
            // samples in the same place.
            val t = if (len2 < TINY) 0f else {
                (((xDoc - ax) * dx + (yDoc - ay) * dy) / len2).coerceIn(0f, 1f)
            }
            val px = ax + dx * t
            val py = ay + dy * t
            val ex = px - xDoc
            val ey = py - yDoc
            val d2 = ex * ex + ey * ey
            if (d2 < best) {
                best = d2
                bx = px
                by = py
            }
            ax = cx
            ay = cy
        }
        out[0] = bx
        out[1] = by
        return true
    }

    override fun toString(): String = "CurveGuide($pointCount points)"

    companion object {
        /** Below this a segment has no direction and is treated as its own start. */
        private const val TINY = 1e-6f

        /**
         * More points than this in one curve is a curve nobody drew by hand.
         *
         * A three-second stroke is about a thousand samples, and its centreline
         * simplified to two pixels apart is a few hundred points — so this is
         * generous rather than tight. It is here because [project] is O(n) per
         * sample: at 512 points and 321.75 Hz that is 165 000 segment tests a
         * second, which is affordable, and ten times that is not.
         */
        const val MAX_POINTS: Int = 512
    }
}
