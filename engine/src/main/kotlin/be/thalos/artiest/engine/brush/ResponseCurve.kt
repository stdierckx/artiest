package be.thalos.artiest.engine.brush

import kotlin.math.pow

/**
 * A 0..1 in, 0..1 out response, either a power law or a piecewise-linear
 * table of control points.
 *
 * **Two forms rather than one, and the plan asked for one.** W2's acceptance
 * test is that the W7 dab goldens do not move, and the plan also says
 * `RoundPen`'s cubic becomes "a three-point curve that must reproduce `p³` to
 * within the goldens' tolerance". Those are not compatible and the arithmetic
 * says so: three points through (0,0), (0.5,0.125), (1,1) evaluate to 0.0625 at
 * p=0.25 where `p³` is 0.015625, an error of 0.047 in curve space and about
 * 1.05 doc px of dab width at the default sizes. The goldens compare floats
 * exactly. A three-point curve does not reproduce a cubic; it replaces it.
 *
 * So the cubic stays a cubic — [power] evaluates `p*p*p` by multiplication, not
 * `pow`, so it is bit-identical to what Phase 1's `RoundPen` did — and [ofPoints] exists
 * beside it for the authored curves the brush format needs. The goldens hold
 * because nothing about the default pen's arithmetic changed.
 *
 * Immutable, and cheap to evaluate: this runs once per sensor per dab, a few
 * hundred times a stroke.
 */
class ResponseCurve private constructor(
    private val xs: FloatArray?,
    private val ys: FloatArray?,
    /** Only meaningful when [xs] is null. */
    private val exponent: Float,
) {

    /** True when this is a power law rather than a table. */
    val isPower: Boolean get() = xs == null

    /** Control point count, or 0 for a power law. */
    val pointCount: Int get() = xs?.size ?: 0

    fun pointX(i: Int): Float = requireNotNull(xs)[i]

    fun pointY(i: Int): Float = requireNotNull(ys)[i]

    /** The exponent, for a power law. `NaN` for a table. */
    val power: Float get() = if (xs == null) exponent else Float.NaN

    /**
     * The response at [t], with [t] clamped to 0..1 first.
     *
     * Clamped rather than required in range for the reason `Brush.sizeFor`
     * gives: pressure arrives interpolated along a Catmull-Rom segment, and a
     * negative input here becomes a negative dab radius, which reaches
     * `MutableBounds.add` as a rectangle inverted around the dab centre. The
     * `!(t > 0f)` form also catches NaN.
     */
    fun evaluate(t: Float): Float {
        var p = t
        if (!(p > 0f)) p = 0f
        if (p > 1f) p = 1f
        val x = xs ?: return powerOf(p)
        val y = ys!!
        // Linear scan. Curves have a handful of points and a binary search on
        // four elements costs more in branches than it saves in comparisons.
        if (p <= x[0]) return y[0]
        val last = x.size - 1
        if (p >= x[last]) return y[last]
        var i = 0
        while (i < last && x[i + 1] < p) i++
        val x0 = x[i]
        val x1 = x[i + 1]
        val span = x1 - x0
        if (span <= 0f) return y[i + 1]
        return y[i] + (y[i + 1] - y[i]) * ((p - x0) / span)
    }

    /**
     * The power law, with the two exponents the pen actually uses taking
     * multiply paths.
     *
     * Exactly what `RoundPen.curve` did, moved rather than rewritten. The 3
     * case is not an optimisation detail: it is the reason the goldens are
     * still valid, because `p.pow(3f)` and `p*p*p` are not the same float.
     */
    private fun powerOf(p: Float): Float = when (exponent) {
        3f -> p * p * p
        1f -> p
        else -> p.pow(exponent)
    }

    override fun toString(): String =
        if (xs == null) "ResponseCurve(p^$exponent)"
        else "ResponseCurve(" + xs.indices.joinToString(" ") { "${xs[it]}->${ys!![it]}" } + ")"

    override fun equals(other: Any?): Boolean {
        if (other !is ResponseCurve) return false
        if (isPower != other.isPower) return false
        if (isPower) return exponent == other.exponent
        return xs!!.contentEquals(other.xs) && ys!!.contentEquals(other.ys)
    }

    override fun hashCode(): Int =
        if (xs == null) exponent.hashCode()
        else 31 * xs.contentHashCode() + ys!!.contentHashCode()

    companion object {

        /** `y = x`. */
        val LINEAR: ResponseCurve = ResponseCurve(null, null, 1f)

        /** `y = x³`, the pen's pressure-to-size default. */
        val CUBIC: ResponseCurve = ResponseCurve(null, null, 3f)

        /** `y = 1` for every input: a sensor wired up but not listened to. */
        val FULL: ResponseCurve = ofPoints(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f))

        fun power(exponent: Float): ResponseCurve {
            require(exponent.isFinite() && exponent > 0f) { "exponent was $exponent" }
            return when (exponent) {
                1f -> LINEAR
                3f -> CUBIC
                else -> ResponseCurve(null, null, exponent)
            }
        }

        /**
         * A piecewise-linear curve through [xs] and [ys].
         *
         * `xs` must be ascending and both arrays the same non-empty length.
         * Values are *not* clamped to 0..1: a curve that overshoots is a
         * legitimate thing to author, and clamping it here would hide the
         * authoring mistake at the one place it is cheap to see. [evaluate]
         * clamps its input, not its output.
         */
        fun ofPoints(xs: FloatArray, ys: FloatArray): ResponseCurve {
            require(xs.size == ys.size) { "xs ${xs.size} but ys ${ys.size}" }
            require(xs.isNotEmpty()) { "a curve needs at least one point" }
            for (i in xs.indices) {
                require(xs[i].isFinite() && ys[i].isFinite()) { "point $i is not finite" }
                require(i == 0 || xs[i] > xs[i - 1]) { "xs must ascend, but x[$i] <= x[${i - 1}]" }
            }
            return ResponseCurve(xs.copyOf(), ys.copyOf(), Float.NaN)
        }

        fun of(vararg points: Pair<Float, Float>): ResponseCurve = ofPoints(
            FloatArray(points.size) { points[it].first },
            FloatArray(points.size) { points[it].second },
        )
    }
}
