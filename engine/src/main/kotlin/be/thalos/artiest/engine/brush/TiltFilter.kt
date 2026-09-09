package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A low-pass over tilt and orientation, because the raw signals are too noisy
 * to steer a dab's shape with.
 *
 * The plan asks for "their own low-pass filter" and the word *own* is load
 * bearing: `Stabilizer` smooths position and pressure, and tilt cannot ride
 * along with them. Position noise is sub-pixel and tilt noise is degrees; a
 * filter tuned to leave the nib responsive leaves the tilt jittering, and one
 * tuned to settle the tilt drags the nib behind the pen.
 *
 * **Orientation is filtered as a vector, not as an angle, and that is the whole
 * reason this is a class.** Orientation wraps at ±PI. Averaging 3.13 and -3.13
 * as numbers gives 0 — the pen apparently swinging a half turn to point the
 * opposite way — when the two readings are a hundredth of a radian apart. So
 * the filter carries `(cos, sin)`, smooths those, and reads the angle back out
 * with `atan2`. Near the wrap it does the right thing by construction, and
 * there is no special case to get wrong.
 *
 * **Time-based, not sample-based**, for the reason [Brush.onsetMillis]
 * spells out at length: this device samples at 246.85 Hz against a 60 Hz panel
 * and 321.75 Hz against 90 Hz, so a per-sample coefficient changes the filter's
 * time constant by a third when the refresh rate changes. The coefficient is
 * recomputed from the actual gap between samples.
 */
class TiltFilter(
    /**
     * Time constant in milliseconds: the time to close about 63% of a step.
     *
     * 40 ms by default. Long enough to kill the per-sample jitter that makes a
     * tilt-driven ellipse shimmer while the pen is held still, short enough
     * that rolling the pen through a quarter turn during a stroke is followed
     * rather than smeared. W10 is where this gets judged by eye.
     */
    var timeConstantMillis: Float = DEFAULT_TIME_CONSTANT_MS,
) {

    private var started = false
    private var tilt = 0f
    private var ox = 1f
    private var oy = 0f

    /** The smoothed tilt in radians, 0 (perpendicular) upward. */
    val tiltRad: Float get() = tilt

    /** The smoothed orientation in radians, -PI..PI. */
    val orientationRad: Float get() = atan2(oy, ox)

    /** Whether any sample has been fed in yet. */
    val isStarted: Boolean get() = started

    /**
     * Forget the stroke. The next [update] snaps to its input rather than
     * easing out of the previous stroke's pose, which would put a curl on the
     * first dabs of every stroke after the first.
     */
    fun reset() {
        started = false
        tilt = 0f
        ox = 1f
        oy = 0f
    }

    /**
     * Fold in one sample, [dtMillis] after the previous one.
     *
     * The first sample is taken whole: a filter that eases from a default pose
     * opens every stroke with a tilt the pen does not have.
     *
     * A non-positive or non-finite [dtMillis] holds the current state rather
     * than dividing by it. Samples within one batch can share a timestamp, and
     * `MotionEvents` backdates `buttonState` across a batch for a related
     * reason — a zero gap is a real thing to receive, not a caller error.
     */
    fun update(tiltRad: Float, orientationRad: Float, dtMillis: Float) {
        val t = if (tiltRad.isFinite()) tiltRad else 0f
        val o = if (orientationRad.isFinite()) orientationRad else 0f
        val cx = cos(o)
        val cy = sin(o)
        if (!started) {
            started = true
            tilt = t
            ox = cx
            oy = cy
            return
        }
        if (!(dtMillis > 0f)) return
        val a = alphaFor(dtMillis)
        tilt += (t - tilt) * a
        ox += (cx - ox) * a
        oy += (cy - oy) * a
        // Renormalise, or repeated blending of unit vectors walks the length
        // toward zero and atan2 loses precision on a shrinking vector.
        val len = kotlin.math.sqrt(ox * ox + oy * oy)
        if (len > 1e-6f) {
            ox /= len
            oy /= len
        } else {
            ox = cx
            oy = cy
        }
    }

    /**
     * The blend weight for a gap of [dtMillis].
     *
     * `1 - exp(-dt/tau)` rather than `dt / (tau + dt)`: the exponential is the
     * exact solution of the one-pole, so the filter's response does not depend
     * on how the samples happen to be spaced. That matters here because they
     * are not evenly spaced — a batch of historical samples arrives with gaps
     * of 3.1 ms and the current sample can be 16 ms behind the one before it.
     */
    private fun alphaFor(dtMillis: Float): Float {
        val tau = timeConstantMillis
        if (!(tau > 0f)) return 1f
        return 1f - exp(-dtMillis / tau)
    }

    companion object {
        const val DEFAULT_TIME_CONSTANT_MS: Float = 40f

        /** Shortest angle from [from] to [to], in -PI..PI. For tests and tools. */
        fun angleDelta(from: Float, to: Float): Float {
            var d = to - from
            val twoPi = (2.0 * PI).toFloat()
            while (d > PI.toFloat()) d -= twoPi
            while (d < -PI.toFloat()) d += twoPi
            return d
        }
    }
}
