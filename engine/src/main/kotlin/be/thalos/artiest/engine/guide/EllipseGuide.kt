package be.thalos.artiest.engine.guide

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An ellipse to draw around.
 *
 * `docs/guides-plan.md` item 13, and the entry that justifies the whole Tier 1
 * list to an inker: **this is how wheels, cups and cylinders in perspective are
 * actually drawn.** A circle seen at an angle is an ellipse, freehand ellipses
 * are the thing hands are worst at, and the correction is exactly the one a
 * guide can make — the hand supplies the sweep and the guide supplies the shape.
 *
 * Placed by a centre, a half-width, a half-height and a rotation. The app's
 * `Guideline` stores those as three points — the centre and the two radius
 * handles — because that is what a hand drags; this class takes the numbers.
 *
 * ## The projection, which is the only hard arithmetic in the framework
 *
 * There is no closed form for the nearest point on an ellipse. The obvious
 * iteration — start from the angle the *circle* would give and walk the
 * parametric angle toward the foot of the perpendicular — converges beautifully
 * from outside and **does not converge at all** from a point near the long axis
 * inside the evolute: the cross product it steers by is zero there, so it sits
 * still and answers the end of the axis. On a 300 by 40 ellipse the point
 * (26, 0) came back 274 pixels from the nearest point on the curve. A test
 * found it, by brute force against four thousand points, which is the only way
 * a wrong answer that is still *on* the ellipse can be caught.
 *
 * So this is Eberly's method instead: bisection on the root of a function that
 * is monotone by construction, which cannot fail to converge and needs no
 * starting guess. Thirty-odd halvings exhaust a float's mantissa, and at
 * 321.75 Hz that is ten thousand halvings a second of three flops each — which
 * is nothing beside the dab loop it feeds.
 *
 * Everything is folded into the first quadrant, and the axes are swapped when
 * the ellipse is taller than it is wide, because the root-finding wants the
 * major axis first. Both are undone at the end.
 *
 * ## What it does at the centre
 *
 * Answers **false**. Every point of the ellipse is equally near the centre, so
 * there is no nearest one, and picking any of them would put a kink in a stroke
 * that happened to pass through the middle. A hand crossing the centre of an
 * ellipse guide is drawing a diameter, not an arc.
 */
class EllipseGuide(
    val xDoc: Float,
    val yDoc: Float,
    /** Half-width, along the rotated x axis. */
    val radiusX: Float,
    /** Half-height, along the rotated y axis. */
    val radiusY: Float,
    /** Radians. 0 puts [radiusX] along +x. */
    val angleRad: Float,
) : Guide {

    private val cosA: Float = cos(angleRad)
    private val sinA: Float = sin(angleRad)

    init {
        require(xDoc.isFinite() && yDoc.isFinite()) { "ellipse at ($xDoc, $yDoc)" }
        require(radiusX.isFinite() && radiusX > 0f) { "radiusX was $radiusX" }
        require(radiusY.isFinite() && radiusY > 0f) { "radiusY was $radiusY" }
        require(angleRad.isFinite()) { "angle was $angleRad" }
    }

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        // Into the ellipse's own frame: translate, then rotate back.
        val vx = xDoc - this.xDoc
        val vy = yDoc - this.yDoc
        val lx = vx * cosA + vy * sinA
        val ly = -vx * sinA + vy * cosA
        if (abs(lx) < AT_CENTRE && abs(ly) < AT_CENTRE) return false

        // First quadrant, and major axis first. Both are undone below.
        //
        // **In double from here to the end of the arithmetic.** Eberly's root
        // lands at s = -1 + tiny for a point near the long axis, and in float
        // the sum `s + 1` there has about one significant bit left — which came
        // out as a nearest point 2 pixels *outside* an ellipse 40 pixels tall.
        // The dab loop is float and this is not on it: it runs once per sample,
        // not once per dab, and a double divide is a few nanoseconds.
        val swap = radiusY > radiusX
        val e0 = (if (swap) radiusY else radiusX).toDouble()
        val e1 = (if (swap) radiusX else radiusY).toDouble()
        val y0 = abs((if (swap) ly else lx).toDouble())
        val y1 = abs((if (swap) lx else ly).toDouble())

        var x0: Double
        var x1: Double
        if (y1 > TINY) {
            if (y0 > TINY) {
                val z0 = y0 / e0
                val z1 = y1 / e1
                val g = z0 * z0 + z1 * z1 - 1.0
                if (g == 0.0) {
                    // Already on it. Saying so exactly matters: this is every
                    // sample after the first of a stroke that is following the
                    // guide, which is most of them.
                    x0 = y0
                    x1 = y1
                } else {
                    val r0 = (e0 / e1) * (e0 / e1)
                    val s = root(r0, z0, z1, g)
                    x0 = r0 * y0 / (s + r0)
                    x1 = y1 / (s + 1.0)
                }
            } else {
                // On the minor axis: the end of it.
                x0 = 0.0
                x1 = e1
            }
        } else {
            // On the major axis, which is the case the angle iteration this
            // replaced got wrong. Inside the evolute's cusp the nearest point
            // is off the end of the axis; outside it, it is the end of the axis.
            val numer = e0 * y0
            val denom = e0 * e0 - e1 * e1
            if (numer < denom) {
                val ratio = numer / denom
                x0 = e0 * ratio
                x1 = e1 * kotlin.math.sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))
            } else {
                x0 = e0
                x1 = 0.0
            }
        }

        // Signs back, axes back, frame back.
        val sx = (if ((if (swap) ly else lx) < 0f) -x0 else x0).toFloat()
        val sy = (if ((if (swap) lx else ly) < 0f) -x1 else x1).toFloat()
        val fx = if (swap) sy else sx
        val fy = if (swap) sx else sy
        out[0] = this.xDoc + fx * cosA - fy * sinA
        out[1] = this.yDoc + fx * sinA + fy * cosA
        return true
    }

    /**
     * The root of Eberly's `g(s)`, by bisection.
     *
     * `g` is strictly decreasing on the bracket, so halving cannot miss it and
     * there is no starting guess to get wrong — which is the whole reason this
     * is here instead of the angle iteration. The loop also stops when the
     * bracket collapses to a single float, which is what usually ends it well
     * before [ITERATIONS].
     */
    private fun root(r0: Double, z0: Double, z1: Double, g0: Double): Double {
        val n0 = r0 * z0
        var lo = z1 - 1.0
        var hi = if (g0 < 0.0) 0.0 else hypot(n0, z1) - 1.0
        var s = 0.0
        repeat(ITERATIONS) {
            s = (lo + hi) * 0.5
            if (s == lo || s == hi) return s
            val a = n0 / (s + r0)
            val b = z1 / (s + 1.0)
            val g = a * a + b * b - 1.0
            when {
                g > 0.0 -> lo = s
                g < 0.0 -> hi = s
                else -> return s
            }
        }
        return s
    }

    override fun toString(): String =
        "EllipseGuide(($xDoc, $yDoc) ${radiusX}x$radiusY at $angleRad rad)"

    companion object {

        /**
         * Enough halvings to take the bracket well past a float's precision,
         * and the loop usually stops earlier because the bracket collapses to
         * a single double.
         *
         * It is a cap and not a schedule: bisection is unconditionally
         * convergent, so reaching this number is not a failure, it is the
         * answer to within the arithmetic.
         */
        const val ITERATIONS: Int = 48

        /**
         * Nearer than this to the centre and there is no nearest point.
         *
         * A sixteenth of a pixel, which is the quantum a stored sample is
         * rounded to: below it the point *is* the centre as far as anything
         * that saves a stroke is concerned.
         */
        private const val AT_CENTRE = 1f / 16f

        /**
         * Below this a coordinate is *on* the axis and the analytic case is
         * used rather than the general one.
         *
         * Not an epsilon for rounding: the two branches choose between two
         * genuinely different formulae, and the general one divides by a
         * quantity that goes to zero here. A test walks the seam between them
         * at four magnitudes, because a jump there is a kink in a stroke
         * crossing the long axis of a wheel.
         */
        private const val TINY = 1e-9

    }
}
