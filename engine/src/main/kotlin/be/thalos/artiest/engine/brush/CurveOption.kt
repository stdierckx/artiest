package be.thalos.artiest.engine.brush

/**
 * One brush parameter that follows sensors: a `(min, max)` range plus the
 * sensor/curve pairs that decide where in it to sit.
 *
 * This is the shape every dynamic brush parameter takes — size, opacity, flow,
 * scatter, rotation, aspect — so `Brush` is a handful of these rather than a
 * handful of bespoke fields with bespoke curves. The output is always
 * `min + (max - min) * combined`, and `combined` is in 0..1.
 *
 * **The empty case returns [max], not [min].** A parameter with no sensors
 * attached is "constant, at the value the slider says", which is the value a
 * user has just dragged to; opening at the floor instead would mean every
 * parameter starts life switched off. That makes [Combine.MULTIPLY]'s identity
 * the natural one and [Combine.MAXIMUM]'s a special case, which is stated here
 * rather than left as a surprise in [combined].
 */
class CurveOption(
    var min: Float,
    var max: Float,
    var combine: Combine = Combine.MULTIPLY,
) {

    /** How several sensors are folded into one 0..1. */
    enum class Combine {
        /**
         * Product. Any sensor near zero pulls the result to zero, so this is
         * "all of these must be true" — pressure *and* not-too-fast.
         */
        MULTIPLY,

        /**
         * Largest. Any sensor near one pulls the result to one, so this is
         * "any of these will do" — the shape an onset floor wants.
         */
        MAXIMUM,
    }

    private val sensors = ArrayList<Sensor>(2)
    private val curves = ArrayList<ResponseCurve>(2)

    val inputCount: Int get() = sensors.size

    fun sensorAt(i: Int): Sensor = sensors[i]

    fun curveAt(i: Int): ResponseCurve = curves[i]

    /**
     * Attach [sensor] through [curve]. Returns this, so a preset reads as one
     * expression.
     *
     * A sensor may be attached more than once with different curves. That is
     * not guarded against because it is occasionally what you want — the same
     * reading combined twice at different strengths — and a rule forbidding it
     * would have to be explained at every call site instead of here.
     */
    fun drive(sensor: Sensor, curve: ResponseCurve = ResponseCurve.LINEAR): CurveOption {
        sensors.add(sensor)
        curves.add(curve)
        return this
    }

    /** Detach everything. The option becomes the constant [max]. */
    fun clearInputs(): CurveOption {
        sensors.clear()
        curves.clear()
        return this
    }

    /**
     * The combined sensor reading in 0..1, before the range is applied.
     *
     * Hand-rolled loops over `ArrayList` rather than `fold` or `maxOf`: this
     * runs once per option per dab, and the golden corpus's largest stroke is
     * 19,826 dabs. An iterator allocation here is 19,826 allocations there.
     */
    fun combined(c: DabContext): Float {
        val n = sensors.size
        if (n == 0) return 1f
        if (combine == Combine.MULTIPLY) {
            var acc = 1f
            var i = 0
            while (i < n) {
                acc *= curves[i].evaluate(sensors[i].read(c))
                i++
            }
            return acc
        }
        var acc = 0f
        var i = 0
        while (i < n) {
            val v = curves[i].evaluate(sensors[i].read(c))
            if (v > acc) acc = v
            i++
        }
        return acc
    }

    /** The parameter's value for this dab. */
    fun valueFor(c: DabContext): Float = min + (max - min) * combined(c)

    /**
     * The value for an explicit 0..1 fraction, bypassing the sensors.
     *
     * Exists for the pen's size path, which has to apply an onset floor
     * *between* the curve and the range — see `Brush.sizeFor`. Without this the
     * floor would have to be folded in as a MAXIMUM sensor, which is the
     * arrangement W7 rejected: the floor is wall-clock and one-sided, and
     * expressing it as a sensor makes it look like a tunable when it is a fix
     * for a specific perceptual complaint.
     */
    fun valueForFraction(f: Float): Float {
        var v = f
        if (!(v > 0f)) v = 0f
        if (v > 1f) v = 1f
        return min + (max - min) * v
    }

    override fun toString(): String {
        val ins = if (sensors.isEmpty()) "constant" else
            sensors.indices.joinToString(" ${combine.name.lowercase()} ") {
                "${sensors[it]}:${curves[it]}"
            }
        return "CurveOption($min..$max, $ins)"
    }
}
