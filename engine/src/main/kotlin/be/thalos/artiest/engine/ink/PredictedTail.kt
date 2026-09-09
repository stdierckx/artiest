package be.thalos.artiest.engine.ink

import kotlin.math.sqrt

/**
 * The speculative stretch of ink between the last dab the pen actually
 * produced and where a predictor thinks the pen is now.
 *
 * **A straight line, not a spline, and that follows from the gate rather than
 * from laziness.** `CatmullRomResampler` needs the point *after* a segment's
 * endpoint before it can fit that segment, so a spline through a predicted
 * point would need a second predicted point to anchor it — speculation on
 * speculation. It would also mean forking the resampler's whole state, four
 * knots and a spacing debt, once per frame. Neither is worth paying for, because
 * `PredictionGate` has already established that the pen is *not* turning
 * sharply: a straight tail is wrong exactly in proportion to the curvature the
 * gate suppresses. Where the gate lets prediction through, the truth over one
 * frame of travel is a nearly straight line.
 *
 * **Nothing here reaches the layer.** These dabs go to the front buffer and
 * only there; `StrokeBuilder` never sees them, so the committed `Stroke` is
 * built from real samples alone. That makes the commit path clean and the
 * *screen* dirty: front-buffer ink is unretractable, so a predicted dab is
 * baked until pen-up. `docs/phase1-plan.md` is explicit that this is the harsher
 * of prediction's two failure modes — a spur that persists rather than one that
 * vanishes next frame — and it is why prediction ships off by default.
 *
 * Stateless between calls and allocation-free: the walk is a loop over floats
 * and the dabs go out through [DabEmitter], the same seam
 * `CatmullRomResampler` uses, so the tail never learns what a brush or a batch
 * is.
 */
class PredictedTail(private val emitter: DabEmitter) {

    /**
     * Walk from the last real dab to the predicted point, emitting dabs.
     *
     * [firstSpacing] is the distance to the first dab. The caller passes the
     * brush's spacing for the last real dab's radius rather than the resampler's
     * live spacing debt, which is private to it and worth about half a dab of
     * accuracy: erring long means the first predicted dab is never *closer* to
     * the last real one than the brush would place it, so the tail cannot make
     * the ink look thicker at the join.
     *
     * Pressure is interpolated across the tail so the width does not step at
     * the join. [elapsedMillis] is not: the tail spans at most one frame, and
     * the only thing that reads it is `Brush`'s onset ramp, which is a
     * 12 ms effect at the very start of a stroke where the predictor has no
     * history to work from anyway.
     *
     * Returns the number of dabs emitted. [maxDabs] is a hard stop, not a
     * tuning knob: the tail's length is whatever an extrapolator said, and an
     * extrapolator handed a bad sample can say a very large number. The cap
     * turns "the app stops responding" into "one frame has a short tail".
     */
    fun emit(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        fromPressure: Float,
        toPressure: Float,
        elapsedMillis: Float,
        firstSpacing: Float,
        maxDabs: Int = DEFAULT_MAX_DABS,
    ): Int {
        require(firstSpacing > 0f && firstSpacing.isFinite()) {
            "firstSpacing was $firstSpacing; the walk would not terminate"
        }
        if (!fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) return 0

        val dx = toX - fromX
        val dy = toY - fromY
        val length = sqrt(dx * dx + dy * dy)
        if (length < firstSpacing) return 0

        val invLength = 1f / length
        val ux = dx * invLength
        val uy = dy * invLength

        var travelled = firstSpacing
        var need = firstSpacing
        var count = 0
        while (travelled <= length && count < maxDabs) {
            val f = travelled * invLength
            val emitted = emitter.emit(
                fromX + ux * travelled,
                fromY + uy * travelled,
                fromPressure + (toPressure - fromPressure) * f,
                elapsedMillis,
            )
            check(emitted > 0f && emitted.isFinite()) {
                "DabEmitter returned a spacing of $emitted; the walk would not terminate"
            }
            need = emitted
            travelled += need
            count++
        }
        return count
    }

    companion object {
        /**
         * 64 dabs, which is one `DabBatch` and also more tail than any honest
         * prediction produces.
         *
         * The predictor aims about one frame ahead — 11.1 ms at 90 Hz. At the
         * default brush's full-press spacing of 3.0 doc px, 64 dabs is 192 doc
         * px of tail, so reaching the cap means the pen was reported to be
         * moving at 17,000 doc px per second. That is not a hand; it is a bad
         * sample, and stopping is the right answer.
         */
        const val DEFAULT_MAX_DABS: Int = 64
    }
}
