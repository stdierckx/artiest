package be.thalos.artiest.engine.guide

/**
 * Something a stroke can be drawn against: a ruler, a curve, a ray from a
 * vanishing point.
 *
 * ## One method, and it is a projection
 *
 * `docs/guides-plan.md` prices the guide framework at 8–12 days *before item 16
 * draws a single ray*, and `docs/inker-plan.md` takes that price with one
 * condition: every guide, from an infinite straight line to a three-point
 * perspective ray, has to be **one question asked of a point** — where would
 * this point be if it were on the guide? Everything else about a guide is
 * chrome: where its handles are, how it is dragged, what it looks like. The ink
 * only ever asks this.
 *
 * That is what keeps the snap stage one line in `StrokeBuilder` rather than a
 * `when` over guide types on the hottest path in the engine.
 *
 * ## Answering false
 *
 * A guide that does not apply at a point answers false and the point is left
 * alone. A perspective ray set is the case: a stroke started nowhere near any
 * ray has no ray to snap to, and forcing one would drag it across the page.
 *
 * ## Threading, and how pure "pure" is
 *
 * Called from `StrokeBuilder.add`, on the UI thread, once per digitizer sample
 * at 321.75 Hz — and once more per predicted-tail point. Implementations must
 * allocate nothing, and must be pure **within one stroke**: the same point
 * twice has to give the same answer, because the predicted tail asks about
 * points the real stroke will ask about again a frame later, and a guide that
 * drifted between the two would put a kink at the join.
 *
 * *Within one stroke* and not absolutely, because [begin] exists. Ik14's
 * parallel ruler is the case that needs it and it is not an exception that can
 * be designed away: "every stroke comes out parallel to this angle" is a line
 * through **where the stroke started**, so the guide cannot be built until the
 * pen has landed. A guide that latched its origin on the first `project` call
 * instead would be one whose answer depended on whether the predicted tail had
 * run yet, which is the same defect one layer down.
 */
interface Guide {

    /**
     * The point on the guide nearest ([xDoc], [yDoc]), written into [out] at
     * indices 0 and 1. False when this guide does not apply there, in which
     * case [out] is not written.
     */
    fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean

    /**
     * A stroke has started at ([xDoc], [yDoc]). Nothing, for a guide that is a
     * fixed thing on the page — which is all of them but one.
     *
     * Called from `StrokeBuilder.add` on the first sample, with the **raw**
     * point rather than the smoothed one: the stabilizer has nothing to smooth
     * yet on the first sample, and a rebuild feeds the same first sample back,
     * so this is the one value that is identical live and on a replay. That is
     * what keeps a stroke drawn against a parallel ruler deterministic without
     * storing anything extra.
     */
    fun begin(xDoc: Float, yDoc: Float) = Unit
}

/**
 * A guide and how hard it pulls.
 *
 * **The falloff is what separates a ruler you lean on from one that fights
 * you**, and it is `docs/inker-plan.md`'s Ik14 brought forward into the
 * interface rather than retrofitted: adding it later would mean changing every
 * guide and every caller, and the shape of the blend is the same for all of
 * them.
 *
 * Immutable, and cheap enough to rebuild whenever a slider moves.
 */
class Snap(
    val guide: Guide,
    /**
     * How much of the way to the guide a point is moved, 0..1, at the guide
     * itself. 1 is a hard ruler; 0.6 is a hand that is being helped.
     */
    val strength: Float = 1f,
    /**
     * How far from the guide the pull reaches, in document pixels, or 0 for
     * "everywhere".
     *
     * Zero is the ruler's own behaviour and the one a ruler wants: while it is
     * on, it is on. A finite reach is for the guides that are furniture rather
     * than instruments — a perspective grid you want to draw *near* without
     * being locked to.
     */
    val reachDoc: Float = 0f,
) {

    init {
        require(strength.isFinite() && strength >= 0f && strength <= 1f) {
            "strength was $strength"
        }
        require(reachDoc.isFinite() && reachDoc >= 0f) { "reach was $reachDoc" }
    }

    /**
     * Move ([xDoc], [yDoc]) toward the guide, writing the result into [out].
     * False when nothing moved.
     *
     * **[out] is scratch either way**: a false answer may still have left the
     * projection in it, because the reach is measured from that projection and
     * checking it first would mean computing it twice. Read [out] only when
     * this answers true. The alternative is a copy on a path that runs at
     * 321.75 Hz to keep a promise no caller needs.
     *
     * The blend is linear in the *position*, not in the angle: a point a tenth
     * of the way pulled is a tenth of the way there. Interpolating an angle
     * instead would make a stroke curve toward the guide rather than approach
     * it, which reads as the ruler bending.
     */
    fun apply(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        if (strength <= 0f) return false
        if (!guide.project(xDoc, yDoc, out)) return false
        val px = out[0]
        val py = out[1]
        val dx = px - xDoc
        val dy = py - yDoc
        val pull = if (reachDoc <= 0f) strength else {
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d >= reachDoc) return false
            // Smoothstep rather than linear or squared, and the shape is the
            // point. It is flat at **both** ends: a hand near the guide does
            // not feel the pull changing as it moves, and the pull dies away at
            // the edge of the reach without a corner. A linear falloff has a
            // corner at the boundary that reads as a click; a plain square is
            // already down to 0.81 a tenth of the way in, so a ruler with any
            // reach at all would feel loose everywhere but exactly on it.
            val t = 1f - d / reachDoc
            strength * t * t * (3f - 2f * t)
        }
        out[0] = xDoc + dx * pull
        out[1] = yDoc + dy * pull
        return true
    }
}

/**
 * An infinite straight line through a point, at an angle.
 *
 * The simplest guide there is, and the one the snap stage is tested against.
 * Ik14 owns the rest — parallels, ellipses, curves — and every one of them is
 * this class's `project` with different arithmetic in it.
 *
 * Infinite rather than a segment, because a ruler is: you lay it down and draw
 * along it, and a stroke that ran off the end of a ruler and un-snapped would
 * be a stroke with a kink in it exactly where the hand was going fastest.
 */
class LineGuide(
    val xDoc: Float,
    val yDoc: Float,
    /** Radians. 0 is along +x. */
    val angleRad: Float,
) : Guide {

    private val dx: Float = kotlin.math.cos(angleRad)
    private val dy: Float = kotlin.math.sin(angleRad)

    init {
        require(xDoc.isFinite() && yDoc.isFinite()) { "line through ($xDoc, $yDoc)" }
        require(angleRad.isFinite()) { "angle was $angleRad" }
    }

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        val vx = xDoc - this.xDoc
        val vy = yDoc - this.yDoc
        val t = vx * dx + vy * dy
        out[0] = this.xDoc + dx * t
        out[1] = this.yDoc + dy * t
        return true
    }

    override fun toString(): String = "LineGuide(($xDoc, $yDoc) at $angleRad rad)"

    companion object {
        /** The line through two points. Refuses a zero-length pair. */
        fun through(x0: Float, y0: Float, x1: Float, y1: Float): LineGuide {
            require(x0 != x1 || y0 != y1) { "a line needs two different points" }
            return LineGuide(x0, y0, kotlin.math.atan2(y1 - y0, x1 - x0))
        }
    }
}
