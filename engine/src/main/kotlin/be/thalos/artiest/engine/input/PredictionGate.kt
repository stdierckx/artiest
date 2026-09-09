package be.thalos.artiest.engine.input

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Decides whether the pen is doing something a predictor can be trusted with.
 *
 * A motion predictor extrapolates. Extrapolation is right when the pen is
 * carrying on and wrong when it turns, and the wrongness is not symmetric here:
 * `docs/phase1-plan.md` records that front-buffer ink is **unretractable**, so a
 * speculative dab stamped into a corner is a spur that stays until pen-up
 * rather than one that vanishes next frame. That asymmetry is the whole reason
 * this class exists — it is cheaper to lose the prediction on the samples
 * around a corner than to draw a hair off one.
 *
 * **Curvature, not distance.** The first draft of the plan also clamped the
 * prediction to half a frame of travel; that clamp is smaller than the horizon
 * the predictor aims at *by construction*, so it would have discarded most of
 * the prediction on every sample and amounted to scaling prediction down
 * uniformly — the cost of prediction with none of the benefit. The clamp was
 * dropped and the curvature gate kept.
 *
 * **The threshold is a turn *rate*, and W11 changed it from a turn per sample.**
 * The plan said "the heading change between the last two stabilized samples
 * exceeds ~25 degrees", and that is the same mistake `Stabilizer`'s header
 * spends forty lines on: this digitizer reports at **246.85 Hz against a 60 Hz
 * panel and 321.75 Hz against 90 Hz**, and a corner is a fixed amount of turning
 * however often you sample it. Sample faster and the same physical corner is
 * split across more samples, each turning less — so a per-sample threshold gets
 * *less* sensitive exactly when the data gets better, and a gate tuned on one
 * panel stops firing on the other. Heading change per unit *time* is a property
 * of the pen's motion and not of the sampling, and `PredictionGateTest` drives
 * one physical corner at both rates to show the verdict holding, with a
 * per-sample gate alongside it as the control that flips.
 *
 * Not thread-safe, and does not need to be: one instance lives beside the
 * `Stabilizer` on the UI thread for the life of a stroke.
 */
class PredictionGate(
    /** Above this heading change per second, prediction is suppressed. */
    val maxTurnRateRadPerSecond: Float = DEFAULT_MAX_TURN_RATE_RAD_PER_S,
    /** How long a corner keeps prediction suppressed. See [DEFAULT_HOLD_NANOS]. */
    val holdNanos: Long = DEFAULT_HOLD_NANOS,
) {

    init {
        require(maxTurnRateRadPerSecond.isFinite() && maxTurnRateRadPerSecond > 0f) {
            "maxTurnRateRadPerSecond was $maxTurnRateRadPerSecond"
        }
        require(holdNanos >= 0L) { "holdNanos was $holdNanos" }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastTimeNanos = 0L
    private var havePoint = false

    private var segDx = 0f
    private var segDy = 0f
    private var haveSegment = false

    /** When the suppression from the last corner expires. */
    private var holdUntilNanos = 0L

    /**
     * Whether prediction is allowed as of the last [push].
     *
     * True before there is enough history to say otherwise. Two points give a
     * heading and three give a change in one, so the first two samples of a
     * stroke are ungated — which is the right default rather than a gap: the
     * predictor has as little history as this gate does, so it predicts almost
     * nothing there, and suppressing on no evidence would only ever suppress.
     *
     * A corner holds this false for [holdNanos] rather than for the one sample
     * that saw it, and that is not conservatism — it is the difference between
     * the gate working and not. Samples arrive batched, one `MotionEvent` per
     * frame carrying three to five of them, and prediction is asked for **once
     * per event**. A verdict that lasted a single sample would therefore be
     * read only when the corner happened to fall on the last sample of a batch:
     * measured on the tablet, a twelve-legged zigzag tripped the gate on 3
     * reversals out of 11, and the other 8 predicted straight through the
     * corner because the heading had already straightened out by the end of
     * the batch.
     */
    var allows: Boolean = true
        private set

    /** The last measured heading change per second. Diagnostics and tests. */
    var turnRateRadPerSecond: Float = 0f
        private set

    /** The last measured heading change, in radians. Signed; sign is unused. */
    var turnRad: Float = 0f
        private set

    /** Drop all state. Call at ACTION_DOWN, beside `Stabilizer.reset`. */
    fun reset() {
        havePoint = false
        haveSegment = false
        holdUntilNanos = 0L
        allows = true
        turnRateRadPerSecond = 0f
        turnRad = 0f
    }

    /**
     * Feed one **stabilized, document-space** point.
     *
     * Stabilized because the gate is deciding about the line the user sees, and
     * the line the user sees has been through the filter. Feeding raw samples
     * here would measure the digitizer's jitter as curvature and suppress
     * prediction on a straight slow line, which is the case prediction helps
     * most.
     *
     * A segment shorter than [DEGENERATE_MOVE] carries no heading, so it is
     * dropped rather than turned into a huge spurious angle: a dwelling pen
     * produces sub-micron movements whose direction is noise, and `atan2` is
     * perfectly happy to report 180 degrees for two of them. Dropping leaves
     * [allows] where it was, which is the honest answer — nothing new was
     * learned — and a stationary pen predicts nowhere anyway.
     */
    fun push(x: Float, y: Float, timeNanos: Long) {
        if (!havePoint) {
            lastX = x
            lastY = y
            lastTimeNanos = timeNanos
            havePoint = true
            return
        }

        val dx = x - lastX
        val dy = y - lastY
        val dtNanos = timeNanos - lastTimeNanos
        val len = sqrt(dx * dx + dy * dy)
        if (len < DEGENERATE_MOVE || dtNanos <= 0L) return

        lastX = x
        lastY = y
        lastTimeNanos = timeNanos

        if (haveSegment) {
            // Signed angle between the two segments, from the cross and dot
            // products rather than from two atan2 headings differenced: one
            // call instead of two, and no wrap-around case at +/-pi to get
            // wrong.
            val cross = segDx * dy - segDy * dx
            val dot = segDx * dx + segDy * dy
            val turn = atan2(cross, dot)
            turnRad = turn
            val seconds = dtNanos.toFloat() * 1e-9f
            val rate = if (turn < 0f) -turn / seconds else turn / seconds
            turnRateRadPerSecond = rate
            if (rate > maxTurnRateRadPerSecond) holdUntilNanos = timeNanos + holdNanos
            allows = timeNanos >= holdUntilNanos
        }

        segDx = dx
        segDy = dy
        haveSegment = true
    }

    override fun toString(): String =
        "PredictionGate(allows=$allows, turn=${turnRateRadPerSecond} rad/s, " +
            "max=$maxTurnRateRadPerSecond)"

    companion object {

        /**
         * 140 rad/s, which is the plan's 25 degrees restated at the rate it was
         * written for.
         *
         * 25 degrees is 0.43633 rad, and one sample at the measured 321.75 Hz
         * is 3.10786 ms, so 0.43633 / 0.00310786 = **140.4 rad/s**. Rounded to
         * 140. At 246.85 Hz that same rate is a 32.5-degree turn between
         * samples and at 321.75 Hz it is 25 degrees — two different per-sample
         * angles describing one threshold, which is the point.
         *
         * For scale: a hand drawing a 20 mm circle at a comfortable 100 mm/s
         * turns at 10 rad/s, fourteen times under the gate. Reaching 140 rad/s
         * takes a corner turned inside about 4 doc px of travel at that speed,
         * which is a corner and not a curve.
         */
        const val DEFAULT_MAX_TURN_RATE_RAD_PER_S: Float = 140f

        /**
         * 30 ms, which is how long a corner stays relevant here.
         *
         * The bound that matters is the predictor's own lookahead: a corner
         * stops contaminating an extrapolation once it is further into the past
         * than the extrapolation reaches into the future. Measured on this
         * tablet, `SystemMotionEventPredictor` puts the pen a mean of 91 doc px
         * ahead of the last real sample on a 3,300 doc px/s stroke — about
         * **27 ms** of lookahead. 30 ms covers that and covers a whole
         * `MotionEvent` batch at either panel rate (5.3 samples at 60 Hz is
         * 16.6 ms, 3.6 at 90 Hz is 11.1 ms), so a corner anywhere in a batch is
         * still suppressing when prediction is asked for at the end of it.
         */
        const val DEFAULT_HOLD_NANOS: Long = 30_000_000L

        /**
         * Movements below this, in document pixels, carry no heading.
         *
         * 0.01 doc px is a hundredth of the smallest dab spacing
         * (`Brush.MIN_SPACING_DOC`), so nothing that could produce ink is
         * discarded by it.
         */
        const val DEGENERATE_MOVE: Float = 0.01f

        /**
         * The per-sample angle, in radians, that [rate] corresponds to at
         * [sampleRateHz]. For tests and for the header's arithmetic.
         */
        fun turnPerSampleRad(rate: Float, sampleRateHz: Float): Float = rate / sampleRateHz
    }
}
