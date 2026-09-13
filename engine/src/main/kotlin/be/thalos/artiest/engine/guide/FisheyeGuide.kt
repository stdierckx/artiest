package be.thalos.artiest.engine.guide

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Five-point curvilinear perspective: a fisheye.
 *
 * `docs/guides-plan.md` item 21, which it scores **1 for use and 4 for
 * difficulty** and says "few people use it". That score is about how many
 * artists reach for it, not about whether this one does — and it turned out to
 * be the least difficult of the three points that number was guarding, because
 * the construction is exact rather than approximate.
 *
 * ## Five points and three families
 *
 * A fisheye has a **circle** — the field of view — with four vanishing points
 * on it at the compass positions, and a fifth at the centre. Everything in the
 * drawing belongs to one of three families:
 *
 * - lines going **away** from the viewer are straight and pass through the
 *   centre point;
 * - **horizontal** lines are arcs from the left point to the right one;
 * - **vertical** lines are arcs from the top point to the bottom one.
 *
 * ## The arcs are exact, which is why this is not hard
 *
 * Through any two fixed points and one more there is exactly one circle. So the
 * arc a stroke belongs to is not fitted or approximated: it is *the* circle
 * through the two vanishing points of its family and the place the pen landed,
 * and projecting onto a circle is a normalise and a multiply.
 *
 * Three points that happen to lie on a line have no circle, and that is the
 * honest case rather than a failure — a stroke starting exactly on the
 * horizontal diameter *is* a straight horizontal line in a fisheye. It falls
 * back to the line through the pair, which is the circle of infinite radius it
 * is asking for.
 *
 * ## Which family, and when
 *
 * `PerspectiveGuide`'s rule, for `PerspectiveGuide`'s reason: all three
 * families pass through the pen-down point, so only the stroke's **direction**
 * can separate them, and a direction does not exist until the hand has moved.
 * The tangents of the three at the origin are compared against the direction
 * travelled after [DECIDE_DOC] pixels, and the winner is kept.
 */
class FisheyeGuide(
    /** The middle of the field of view, and the fifth vanishing point. */
    val xDoc: Float,
    val yDoc: Float,
    /** How wide the field of view is, in document pixels. */
    val radius: Float,
    /** Which way the cross of four points is turned. Radians; 0 puts one at +x. */
    val angleRad: Float,
) : Guide {

    init {
        require(xDoc.isFinite() && yDoc.isFinite()) { "fisheye at ($xDoc, $yDoc)" }
        require(radius.isFinite() && radius > 0f) { "radius was $radius" }
        require(angleRad.isFinite()) { "angle was $angleRad" }
    }

    /** The four points on the circle: east, west, then north, south. */
    private val ex: Float = xDoc + cos(angleRad) * radius
    private val ey: Float = yDoc + sin(angleRad) * radius
    private val wx: Float = xDoc - cos(angleRad) * radius
    private val wy: Float = yDoc - sin(angleRad) * radius
    private val nx: Float = xDoc - sin(angleRad) * radius
    private val ny: Float = yDoc + cos(angleRad) * radius
    private val sx: Float = xDoc + sin(angleRad) * radius
    private val sy: Float = yDoc - cos(angleRad) * radius

    fun eastX(): Float = ex

    fun eastY(): Float = ey

    fun northX(): Float = nx

    fun northY(): Float = ny

    /**
     * Where the stroke started, which family it is on, and the circle of that
     * family — all worked out once, at the moment the family is chosen.
     *
     * Per-stroke state and no lock, for `PerspectiveGuide`'s reason: the live
     * guide is one thread's and a rebuild builds its own.
     */
    private var originX: Float = Float.NaN
    private var originY: Float = Float.NaN
    private var family: Int = NO_FAMILY

    /** The chosen arc's circle, or a radius of 0 for "it is a straight line". */
    private var arcX: Float = 0f
    private var arcY: Float = 0f
    private var arcR: Float = 0f

    /** A straight family's direction, normalised. */
    private var lineX: Float = 0f
    private var lineY: Float = 0f

    override fun begin(xDoc: Float, yDoc: Float) {
        originX = xDoc
        originY = yDoc
        family = NO_FAMILY
    }

    override fun advance(xDoc: Float, yDoc: Float) {
        if (family != NO_FAMILY) return
        val ox = originX
        if (ox.isNaN()) return
        val oy = originY
        val tx = xDoc - ox
        val ty = yDoc - oy
        val travel = hypot(tx, ty)
        if (travel < DECIDE_DOC) return
        val ux = tx / travel
        val uy = ty / travel

        var best = -1f
        var bestSigned = 0f
        var found = NO_FAMILY
        for (i in 0 until 3) {
            if (!tangentOf(i, tangent)) continue
            val signed = ux * tangent[0] + uy * tangent[1]
            val score = abs(signed)
            val take = when {
                score > best + TIE -> true
                score < best - TIE -> false
                else -> signed > bestSigned
            }
            if (take) {
                best = score
                bestSigned = signed
                found = i
            }
        }
        if (found == NO_FAMILY) return
        family = found
        settle(found)
    }

    /**
     * The unit tangent of family [i] where the stroke started, into [out].
     *
     * False for a family that has no direction here: the radial one at the very
     * centre, where every direction is radial and none of them is the answer.
     */
    private fun tangentOf(i: Int, out: FloatArray): Boolean {
        if (i == RADIAL) {
            val dx = originX - xDoc
            val dy = originY - yDoc
            val len = hypot(dx, dy)
            if (len < TINY) return false
            out[0] = dx / len
            out[1] = dy / len
            return true
        }
        val ax = if (i == ACROSS) wx else nx
        val ay = if (i == ACROSS) wy else ny
        val bx = if (i == ACROSS) ex else sx
        val by = if (i == ACROSS) ey else sy
        if (!circleThrough(ax, ay, bx, by, originX, originY, circle)) {
            // Collinear: the arc is the straight line through the pair, which
            // is the circle of infinite radius it was asking for.
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len < TINY) return false
            out[0] = dx / len
            out[1] = dy / len
            return true
        }
        // Perpendicular to the radius, which is the tangent of a circle.
        val rx = originX - circle[0]
        val ry = originY - circle[1]
        val len = hypot(rx, ry)
        if (len < TINY) return false
        out[0] = -ry / len
        out[1] = rx / len
        return true
    }

    /** Work out and keep the geometry of family [i], once. */
    private fun settle(i: Int) {
        if (i == RADIAL) {
            val dx = originX - xDoc
            val dy = originY - yDoc
            val len = hypot(dx, dy)
            arcR = 0f
            lineX = dx / len
            lineY = dy / len
            return
        }
        val ax = if (i == ACROSS) wx else nx
        val ay = if (i == ACROSS) wy else ny
        val bx = if (i == ACROSS) ex else sx
        val by = if (i == ACROSS) ey else sy
        if (circleThrough(ax, ay, bx, by, originX, originY, circle)) {
            arcX = circle[0]
            arcY = circle[1]
            arcR = circle[2]
        } else {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            arcR = 0f
            lineX = dx / len
            lineY = dy / len
        }
    }

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        if (family == NO_FAMILY) return false
        if (arcR > 0f) {
            val dx = xDoc - arcX
            val dy = yDoc - arcY
            val len = hypot(dx, dy)
            // The dead centre of the arc's own circle has no nearest point on
            // it, the same way the centre of an ellipse has none.
            if (len < TINY) return false
            out[0] = arcX + dx / len * arcR
            out[1] = arcY + dy / len * arcR
            return true
        }
        // A straight family: the line through the origin along `lineX, lineY`.
        val t = (xDoc - originX) * lineX + (yDoc - originY) * lineY
        out[0] = originX + lineX * t
        out[1] = originY + lineY * t
        return true
    }

    override fun toString(): String = "FisheyeGuide(($xDoc, $yDoc) r=$radius)"

    /** [tangentOf]'s answer. Never escapes. */
    private val tangent = FloatArray(2)

    /** [circleThrough]'s answer: x, y, radius. Never escapes. */
    private val circle = FloatArray(3)

    companion object {

        /** Straight through the middle: the fifth point. */
        const val RADIAL: Int = 0

        /** The arcs from the left point to the right one. */
        const val ACROSS: Int = 1

        /** The arcs from the top point to the bottom one. */
        const val UPRIGHT: Int = 2

        private const val NO_FAMILY = -1

        /** `PerspectiveGuide.DECIDE_DOC`, and the same argument. */
        const val DECIDE_DOC: Float = 8f

        private const val TIE = 1e-3f

        private const val TINY = 1e-4f

        /**
         * The circle through three points, into [out] as x, y, radius.
         *
         * False when they are collinear, which is a real answer and not a
         * failure: the caller wants a straight line then.
         *
         * **In double.** A page coordinate is a few thousand, its square is a
         * few million, and the determinant is a difference of those — the exact
         * shape that loses every significant bit it has in float. It runs once
         * per stroke, not once per sample.
         */
        fun circleThrough(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
            cx: Float,
            cy: Float,
            out: FloatArray,
        ): Boolean {
            val axd = ax.toDouble()
            val ayd = ay.toDouble()
            val bxd = bx.toDouble()
            val byd = by.toDouble()
            val cxd = cx.toDouble()
            val cyd = cy.toDouble()
            val d = 2.0 * (axd * (byd - cyd) + bxd * (cyd - ayd) + cxd * (ayd - byd))
            // Scaled against the size of the triangle, so "collinear" means the
            // same thing on a 64-pixel page and on a 3300-pixel one.
            val span = maxOf(
                hypot(bxd - axd, byd - ayd),
                hypot(cxd - axd, cyd - ayd),
                hypot(cxd - bxd, cyd - byd),
            )
            if (span < 1e-6 || abs(d) < span * span * 1e-6) return false
            val a2 = axd * axd + ayd * ayd
            val b2 = bxd * bxd + byd * byd
            val c2 = cxd * cxd + cyd * cyd
            val ux = (a2 * (byd - cyd) + b2 * (cyd - ayd) + c2 * (ayd - byd)) / d
            val uy = (a2 * (cxd - bxd) + b2 * (axd - cxd) + c2 * (bxd - axd)) / d
            out[0] = ux.toFloat()
            out[1] = uy.toFloat()
            out[2] = hypot(axd - ux, ayd - uy).toFloat()
            return true
        }

        /** `hypot` for doubles, which `kotlin.math` spells the same way. */
        private fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
    }
}
