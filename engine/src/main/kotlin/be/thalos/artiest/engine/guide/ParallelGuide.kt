package be.thalos.artiest.engine.guide

import kotlin.math.cos
import kotlin.math.sin

/**
 * Every stroke comes out parallel to one angle.
 *
 * `docs/guides-plan.md` item 10, and the cheapest useful thing in Tier 1: manga
 * speed lines and hatching are both *many lines at one angle in different
 * places*, which is a ruler you would otherwise have to drag between every
 * stroke.
 *
 * ## The one guide that is not a fixed thing on the page
 *
 * A ruler is a line and you draw along it. A parallel ruler is an **angle**, and
 * the line is wherever the pen landed — so this is the guide [Guide.begin]
 * exists for, and the reason that method is on the interface at all.
 *
 * The origin is latched from the stroke's first raw sample. That is the one
 * value which is identical live and on a replay: the stabilizer has nothing to
 * smooth on the first sample, and a rebuild feeds the same first sample back
 * through the same call. So a stroke drawn against a parallel ruler re-renders
 * exactly, and nothing extra has to be stored to make it so.
 *
 * ## Before the first sample it does not apply
 *
 * [project] answers false until [begin] has been called, rather than guessing at
 * an origin. Nothing asks before then in the live path — `StrokeBuilder` calls
 * `begin` on the first sample and snaps on the same one — but the predicted tail
 * and a caller measuring a hit band both can, and an origin of (0, 0) would put
 * a speed line through the top-left corner of the page.
 */
class ParallelGuide(
    /** Radians. 0 is along +x. */
    val angleRad: Float,
) : Guide {

    private val dx: Float = cos(angleRad)
    private val dy: Float = sin(angleRad)

    init {
        require(angleRad.isFinite()) { "angle was $angleRad" }
    }

    /**
     * Where this stroke started, or NaN before it has.
     *
     * Two plain floats and no lock. It is written on the UI thread at pen-down
     * and read on the UI thread at every sample of the same stroke; the render
     * thread's rebuild uses its own builder and its own `Snap` out of the
     * sheet's table, so the two never share one of these.
     */
    private var originX: Float = Float.NaN
    private var originY: Float = Float.NaN

    override fun begin(xDoc: Float, yDoc: Float) {
        originX = xDoc
        originY = yDoc
    }

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        val ox = originX
        val oy = originY
        if (ox.isNaN() || oy.isNaN()) return false
        val vx = xDoc - ox
        val vy = yDoc - oy
        val t = vx * dx + vy * dy
        out[0] = ox + dx * t
        out[1] = oy + dy * t
        return true
    }

    override fun toString(): String = "ParallelGuide($angleRad rad)"

    companion object {
        /** The angle from one point to another, which is how a hand sets it. */
        fun through(x0: Float, y0: Float, x1: Float, y1: Float): ParallelGuide {
            require(x0 != x1 || y0 != y1) { "an angle needs two different points" }
            return ParallelGuide(kotlin.math.atan2(y1 - y0, x1 - x0))
        }
    }
}
