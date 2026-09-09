package be.thalos.artiest.engine.input

import kotlin.math.exp

/**
 * The smoothing stage, between prediction and the Catmull-Rom fit.
 *
 * A first-order exponential low-pass on x, y and pressure, with its time
 * constant **integrated over each sample's dt** rather than applied once per
 * sample. That distinction is the entire reason this class exists instead of a
 * three-line lerp, and it is not a refinement — a per-sample lerp is a filter
 * whose cutoff frequency is the sample rate, and this digitizer's sample rate
 * is not a constant. It reports **246.85 Hz against a 60 Hz panel and 321.75 Hz
 * against 90 Hz** (Phase 0, `docs/phase1-plan.md`). The same `out += 0.3 *
 * (in - out)` at those two rates is two different filters:
 *
 * - 246.85 Hz, dt = 4.051 ms: a 0.3 lerp settles to 1/e in ~2.8 samples = 11.4 ms.
 * - 321.75 Hz, dt = 3.108 ms: the same 0.3 lerp settles in ~2.8 samples = 8.7 ms.
 *
 * So switching the panel to 90 Hz would cut the settling time by 23%
 * with nobody touching the slider, and every stabilization value anyone tuned
 * at one rate would be wrong at the other. With dt integration,
 * `alpha = 1 - exp(-dt / tau)` is 0.491 at 246.85 Hz and 0.404 at 321.75 Hz for
 * the same 6 ms tau — two different alphas describing the *same* filter,
 * sampled two ways. `StabilizerTest` states that exactly: the discrete output
 * matches `1 - exp(-t / tau)` at the sample's own wall-clock time, at both
 * rates, to within 1e-4.
 *
 * **At `strength == 0` this is bit-exact identity**, and that is a short
 * circuit rather than arithmetic that happens to come out right. `alpha` would
 * be exactly 1.0 there, but `out + 1.0f * (in - out)` is not `in` in float — it
 * is `in` rounded through two more operations, and it differs in the last bit
 * for values far from the state. The trace corpus and the dab goldens both
 * compare exact floats, so "almost identity" would show up as a golden that
 * drifts depending on where the previous stroke ended.
 *
 * Allocation-free by construction: [push] writes to [x], [y] and [pressure]
 * rather than returning a sample. The budget is one `PenSample` per digitizer
 * sample for the whole path (W9 traces it); a stage that returned a fresh
 * `PenSample` would double that on its own.
 *
 * Not thread-safe, and does not need to be: one instance lives on the UI thread
 * inside `StrokeBuilder` for the life of a stroke. W11's predictor takes a
 * [copyStateTo] fork so speculative points never mutate the filter the next
 * real sample runs through.
 */
class Stabilizer(val strength: Float) {

    init {
        require(strength.isFinite() && strength >= 0f && strength <= 1f) {
            "strength was $strength; expected 0..1"
        }
    }

    /**
     * Exactly zero when [strength] is zero, which is what [push]'s identity
     * branch tests. `0f * TAU_MAX_NANOS` is `0f` for any finite constant, so
     * this is an exact comparison and not a tolerance.
     */
    private val tauNanos: Float = strength * TAU_MAX_NANOS

    /** Smoothed x, valid after the first [push] of a stroke. */
    var x: Float = 0f
        private set

    /** Smoothed y. See [x]. */
    var y: Float = 0f
        private set

    /**
     * Smoothed pressure.
     *
     * Smoothed for the same reason as position and with the same time constant:
     * pressure drives dab radius, so pressure jitter is width jitter, and a
     * stroke whose width flickers reads as a rendering fault rather than as a
     * noisy sensor. Filtering it here rather than in `Brush` keeps the
     * single time constant that makes the rate-invariance argument above hold
     * for the whole stage.
     */
    var pressure: Float = 0f
        private set

    private var lastTimeNanos: Long = 0L

    /**
     * False until the first [push] after a [reset]. The first sample of a
     * stroke has no previous state and no dt, so it is passed through
     * unfiltered — anything else would drag the first dab toward wherever the
     * last stroke ended.
     */
    private var primed: Boolean = false

    /** Drops all state. Call at ACTION_DOWN, not at pen-up. */
    fun reset() {
        primed = false
        x = 0f
        y = 0f
        pressure = 0f
        lastTimeNanos = 0L
    }

    /** Feed one sample. Reads five of [PenSample]'s fields and no more. */
    fun push(sample: PenSample) {
        push(sample.x, sample.y, sample.pressure, sample.eventTimeNanos)
    }

    /**
     * Feed one sample by parts, for tests and for W11's speculative tail, which
     * has coordinates and a timestamp but no `PenSample` to spare.
     *
     * `dt <= 0` holds the output where it is rather than dividing by it. Two
     * ways to get there and both are real: `MotionEvent` history entries can
     * carry a repeated `eventTime` when the digitizer batches faster than the
     * clock it is stamped against, and a rebased trace can hand back a
     * timestamp that has gone backwards. Holding is the correct response to
     * "no time has passed" — the filter's output is a function of elapsed time,
     * and none elapsed. The backwards case then recovers on the next sample:
     * [lastTimeNanos] moves back with it, so the following dt is large, alpha
     * approaches 1, and the filter snaps to the input instead of staying stuck
     * behind a clock it cannot reconcile.
     */
    fun push(x: Float, y: Float, pressure: Float, timeNanos: Long) {
        if (tauNanos == 0f || !primed) {
            this.x = x
            this.y = y
            this.pressure = pressure
            lastTimeNanos = timeNanos
            primed = true
            return
        }
        val dtNanos = timeNanos - lastTimeNanos
        lastTimeNanos = timeNanos
        if (dtNanos <= 0L) return
        // Long -> Float loses precision above 2^24 ns (16.8 ms), which is
        // deliberately fine: the value is a dt, alpha saturates at 1 long
        // before that, and the error at 16.8 ms is one part in 16 million of a
        // number the exponential has already flattened.
        val alpha = 1f - exp(-dtNanos.toFloat() / tauNanos)
        this.x += alpha * (x - this.x)
        this.y += alpha * (y - this.y)
        this.pressure += alpha * (pressure - this.pressure)
    }

    /**
     * Copy this filter's live state into [other], which must have been built
     * with the same [strength].
     *
     * This is W11's fork, preallocated rather than allocated per prediction.
     * The state is the three smoothed floats plus the timestamp and the primed
     * flag — the plan says "three floats", and the three floats are the part
     * that matters, but a fork that dropped [lastTimeNanos] would compute its
     * first alpha against a dt measured from zero, saturate, and hand back the
     * raw predicted point as if smoothing were off.
     *
     * The strength check is not defensive noise: forking into a differently
     * tuned filter is silent — it produces a plausible smoothed tail that
     * simply does not match the ink it is extending.
     */
    fun copyStateTo(other: Stabilizer) {
        require(other.strength == strength) {
            "fork strength ${other.strength} does not match $strength"
        }
        other.x = x
        other.y = y
        other.pressure = pressure
        other.lastTimeNanos = lastTimeNanos
        other.primed = primed
    }

    override fun toString(): String =
        "Stabilizer(strength=$strength, tau=${tauNanos / 1_000_000f}ms, primed=$primed)"

    companion object {

        /**
         * Time constant at `strength == 1`, in nanoseconds: 40 ms.
         *
         * The scale is chosen so the default sits somewhere useful rather than
         * at an extreme. At the plan's default 0.15 that is a 6 ms tau, which
         * is one and a half samples at 246.85 Hz and just under two at
         * 321.75 Hz — enough to take the tremor off a slow line without the pen
         * visibly outrunning its ink. At the far end, 40 ms is between three
         * and four 90 Hz frames of lag, which is
         * deliberately past the point of usefulness: the top of the slider
         * should be obviously too much, or nobody learns what the middle does.
         */
        const val TAU_MAX_NANOS: Float = 40_000_000f

        /** The plan's default, and the toolbar slider's initial position. */
        const val DEFAULT_STRENGTH: Float = 0.15f

        /**
         * The rate-invariance claim from this class's header, as something a
         * test can call rather than something a comment asserts.
         *
         * Returns the per-sample alpha for a given [strength] at a given sample
         * rate. Two rates, one strength, and the *time-domain* response has to
         * match — which is what `StabilizerTest` checks by running both rates
         * over the same wall-clock span and comparing where the output lands,
         * not by comparing these alphas to each other (they differ, and that is
         * the point).
         */
        fun alphaAt(strength: Float, sampleRateHz: Float): Float {
            val tau = strength * TAU_MAX_NANOS
            if (tau == 0f) return 1f
            return 1f - exp(-(1e9f / sampleRateHz) / tau)
        }

        /**
         * True when the two measured digitizer rates produce the same filter,
         * for documentation's sake — see [alphaAt]. Kept as a function so the
         * header's arithmetic has a single home.
         */
        fun rateInvarianceHolds(strength: Float): Boolean {
            val slow = alphaAt(strength, 246.85f)
            val fast = alphaAt(strength, 321.75f)
            return strength == 0f || fast < slow
        }
    }
}
