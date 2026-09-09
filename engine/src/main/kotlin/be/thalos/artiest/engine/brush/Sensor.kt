package be.thalos.artiest.engine.brush

import kotlin.math.PI

/**
 * The inputs a brush parameter is allowed to follow, each normalised to 0..1.
 *
 * Every sensor answers the same question — "how much, from 0 to 1" — so a
 * [CurveOption] can combine any of them without knowing which it holds. The
 * normalisation is the interesting part and it is per-sensor, because the raw
 * units are radians, document pixels per millisecond, and milliseconds.
 *
 * **Tilt and orientation are required**, not "where the hardware supplies it".
 * The DTH-A116 reports both, W10's pencil is defined by them, and a sensor that
 * is optional gets a fallback path that is never exercised and quietly wrong.
 * [DISTANCE] is the one that really is unavailable — see its own note.
 */
enum class Sensor {

    /** Normalised digitizer pressure, straight through. */
    PRESSURE,

    /**
     * Nib speed, normalised against [SPEED_MAX_DOC_PX_PER_MS].
     *
     * Speed is the sensor most likely to need retuning per preset, because
     * "fast" depends on the document scale. The reference is a constant rather
     * than a brush field on purpose: one shared definition of fast keeps two
     * presets comparable, and W10 can add a per-brush scale if it turns out to
     * need one.
     */
    SPEED,

    /**
     * Pen tilt from perpendicular, normalised over 0..[TILT_MAX_DEG] degrees.
     *
     * 63 degrees and not 90 because that is the plan's number and it matches
     * the hardware's useful range: past about 60 degrees the digitizer's tilt
     * estimate degrades and the pen body is nearly touching the glass. Beyond
     * the ceiling the sensor saturates at 1 rather than wrapping.
     */
    TILT,

    /**
     * Which way the tilt points, -PI..PI mapped to 0..1.
     *
     * Circular, and that is a trap for anything that averages it — see
     * [TiltFilter], which is why the filter exists as a type rather than as a
     * one-line smoothing step at the call site.
     */
    ORIENTATION,

    /**
     * Direction of travel of the stroke, -PI..PI mapped to 0..1.
     *
     * Circular in exactly the way [ORIENTATION] is, and computed from
     * consecutive dab positions rather than from the digitizer.
     */
    DIRECTION,

    /**
     * Hover height. **Always 0 on this hardware**, and `PenSample.distance`'s
     * KDoc records the 400-sample Phase 0 capture that established it. It is
     * here so the brush format has somewhere to deserialize into and so a brush
     * authored elsewhere round-trips; a preset that reads it gets a constant,
     * which is a dead brush parameter and not a bug in this file.
     */
    DISTANCE,

    /** Milliseconds since pen-down, normalised over [TIME_SPAN_MS]. */
    TIME,

    /** A fresh uniform 0..1 for every dab. */
    RANDOM_DAB,

    /** One uniform 0..1 drawn at pen-down and held for the whole stroke. */
    RANDOM_STROKE,
    ;

    /**
     * This sensor's normalised reading from [c], clamped to 0..1.
     *
     * Clamping here rather than trusting callers: `DIRECTION` and `ORIENTATION`
     * come from `atan2` and are in range by construction, but `PRESSURE`
     * arrives interpolated along a spline and `SPEED` is a ratio that a flick
     * can push past its reference.
     */
    fun read(c: DabContext): Float {
        val v = when (this) {
            PRESSURE -> c.pressure
            SPEED -> c.speedDocPxPerMs / SPEED_MAX_DOC_PX_PER_MS
            TILT -> c.tiltRad / TILT_MAX_RAD
            ORIENTATION -> (c.orientationRad + PI.toFloat()) / TWO_PI
            DIRECTION -> (c.directionRad + PI.toFloat()) / TWO_PI
            DISTANCE -> c.distance
            TIME -> c.elapsedMillis / TIME_SPAN_MS
            RANDOM_DAB -> c.randomDab
            RANDOM_STROKE -> c.randomStroke
        }
        if (!(v > 0f)) return 0f // also catches NaN
        return if (v > 1f) 1f else v
    }

    companion object {

        /** See [TILT]. */
        const val TILT_MAX_DEG: Float = 63f

        val TILT_MAX_RAD: Float = (TILT_MAX_DEG * PI / 180.0).toFloat()

        /**
         * The speed [SPEED] calls 1.0, in document pixels per millisecond.
         *
         * 3 doc px/ms is a brisk stroke on a 3300 px page: the W16 zigzag
         * averages well under it and its fastest sweeps sit near it, so the
         * sensor spends its range on strokes people actually draw rather than
         * saturating on the first flick.
         */
        const val SPEED_MAX_DOC_PX_PER_MS: Float = 3f

        /** The elapsed time [TIME] calls 1.0. One second. */
        const val TIME_SPAN_MS: Float = 1000f

        val TWO_PI: Float = (2.0 * PI).toFloat()
    }
}

/**
 * The per-dab inputs, as one mutable object reused down the stroke.
 *
 * Mutable and reused, which is the opposite of `PenSample`'s choice, and for
 * the opposite reason: nothing retains a [DabContext]. It is filled and read
 * within one iteration of the dab loop, which W0's bench measured at 23.4 ns a
 * dab with essentially no allocation — a fresh context per dab at 19,826 dabs a
 * stroke would put that back.
 */
class DabContext {
    var pressure: Float = 0f
    var speedDocPxPerMs: Float = 0f
    var tiltRad: Float = 0f
    var orientationRad: Float = 0f
    var directionRad: Float = 0f
    var distance: Float = 0f
    var elapsedMillis: Float = 0f
    var randomDab: Float = 0f
    var randomStroke: Float = 0f

    /** Everything back to zero, so a stale field cannot leak into a new stroke. */
    fun reset() {
        pressure = 0f
        speedDocPxPerMs = 0f
        tiltRad = 0f
        orientationRad = 0f
        directionRad = 0f
        distance = 0f
        elapsedMillis = 0f
        randomDab = 0f
        randomStroke = 0f
    }
}
