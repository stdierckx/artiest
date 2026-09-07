package be.thalos.artiest.engine.xform

import kotlin.math.PI

/**
 * Where the document sits under the viewport: four floats, not a `Matrix`.
 *
 * `android.graphics.Matrix` is a stub in JVM unit tests — every method returns
 * a default and asserts nothing — so a Matrix-backed transform could only be
 * tested on a device, which is the one thing this module exists to avoid.
 * Components buy more than testability though: a clamp on `scale` and a
 * normalized `rotationRad` are statable as invariants here, where reading them
 * back out of matrix entries means undoing a decomposition first. `Matrices.kt`
 * in `:app` is the only place that turns this into a `Matrix`.
 *
 * Immutable, and every operation returns a new instance. The render thread
 * snapshots `docToView` at ACTION_DOWN and holds that snapshot for the whole
 * stroke, so a gesture mutating a shared transform underneath it would move
 * ink already committed at the old one.
 *
 * [txDoc]/[tyDoc] are in **document** units, not pixels — the suffix is not
 * decoration. Translating in view pixels means dividing by [scale] at every
 * use, and the one call site that forgets produces a pan that accelerates as
 * you zoom in.
 */
data class CanvasTransform(
    val scale: Float = 1f,
    val rotationRad: Float = 0f,
    val txDoc: Float = 0f,
    val tyDoc: Float = 0f,
) {
    init {
        // Rejected rather than clamped, because clamping in the constructor
        // would make `copy(scale = 9f)` look like it succeeded and silently
        // produce a different transform than the caller wrote. Gestures go
        // through [zoomedTo], which clamps; anything reaching here out of range
        // is a bug upstream, and this is where it stops being invisible.
        require(scale in MIN_SCALE..MAX_SCALE) {
            "scale $scale outside $MIN_SCALE..$MAX_SCALE — use zoomedTo() to clamp"
        }
        // Checked because NaN is reachable, not as defensive habit: a rotate
        // gesture takes atan2 of the vector between two pointers, and two
        // pointers reported at the same coordinate give atan2(0, 0) — NaN,
        // which then propagates through every later frame silently, since NaN
        // comparisons are false and nothing downstream throws. Same for
        // translation once a pan divides by a scale that arrived as zero.
        require(rotationRad.isFinite()) { "rotationRad was $rotationRad" }
        require(txDoc.isFinite() && tyDoc.isFinite()) {
            "translation was ($txDoc, $tyDoc)"
        }
    }

    /** [target] clamped into the legal range. The pinch-gesture entry point. */
    fun zoomedTo(target: Float): CanvasTransform = copy(scale = clampScale(target))

    /**
     * Relative zoom, which is what a pinch actually reports. Composing factors
     * and clamping once at the end means a pinch that overshoots the ceiling
     * and comes back lands where the fingers say, rather than being pinned at
     * 8.0 by the overshoot.
     */
    fun zoomedBy(factor: Float): CanvasTransform {
        require(factor > 0f) { "zoom factor must be positive, was $factor" }
        return zoomedTo(scale * factor)
    }

    /** Pan by a document-space delta. */
    fun pannedBy(dxDoc: Float, dyDoc: Float): CanvasTransform =
        copy(txDoc = txDoc + dxDoc, tyDoc = tyDoc + dyDoc)

    /**
     * Rotate, keeping the angle in (-PI, PI].
     *
     * Normalized on every step rather than at the end: rotation accumulates for
     * as long as the canvas is open, and an unbounded float fed to sin/cos
     * loses mantissa bits to the integer turns it is carrying. A few hundred
     * turns is enough to make a slow twist visibly quantize.
     */
    fun rotatedBy(deltaRad: Float): CanvasTransform =
        copy(rotationRad = normalizeRotation(rotationRad + deltaRad))

    companion object {

        /**
         * The floor is 0.5, not 0.35. Bilinear filtering is honest to about 2x
         * minification and 0.35 is past it, so a document zoomed further out
         * shimmers while panning. Fixing that means a mip chain, and generating
         * one over a full layer costs ~11 ms — a guaranteed dropped frame — for
         * nothing Phase 1 gains.
         */
        const val MIN_SCALE = 0.5f

        const val MAX_SCALE = 8.0f

        val IDENTITY = CanvasTransform()

        // Compared against in Float, not against kotlin.math.PI directly: the
        // Float nearest to pi is *larger* than the Double one, so `r > PI`
        // would be true for exactly half a turn and wrap it to -pi.
        private val PI_F = PI.toFloat()
        private val TWO_PI = (2.0 * PI).toFloat()

        fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

        private fun normalizeRotation(rad: Float): Float {
            var r = rad % TWO_PI
            if (r > PI_F) r -= TWO_PI else if (r <= -PI_F) r += TWO_PI
            return r
        }
    }
}
