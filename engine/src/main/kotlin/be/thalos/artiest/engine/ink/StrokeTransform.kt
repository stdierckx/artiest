package be.thalos.artiest.engine.ink

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Moving, turning and resizing a stroke, by moving its **input**.
 *
 * Ik9. A stroke that is dragged across the page is not pixels that are dragged
 * across the page: it is the same hand movement, somewhere else, redrawn there.
 * That is why the grain regenerates where it lands rather than sliding along
 * with it — `GrainTexture`'s shader is anchored to the page, and
 * `docs/vector-plan.md` trap 2 is satisfied by construction because nothing
 * here touches pixels at all.
 *
 * ## What moves and what does not
 *
 * | Channel | Under a transform |
 * |---|---|
 * | x, y | Mapped by the matrix, which is the whole operation. |
 * | orientation | **Rotated with it.** It is the pen's azimuth *on the page*, so a stroke turned ninety degrees was drawn by a hand holding the pen ninety degrees round — and a chisel nib that did not turn with its stroke would be a different mark. |
 * | tilt | Unchanged. It is the angle of the pen against the glass, and turning the paper does not change how far the pen was leaning. |
 * | pressure, time | Unchanged. Neither is geometry. |
 *
 * ## Scale is two things and the caller owns the second
 *
 * Mapping the samples moves the dabs further apart; it does not make them
 * bigger, because a dab's size comes from the brush. So a stroke scaled by two
 * would stretch and stay thin. [scaleOf] reports the uniform scale the matrix
 * applies so that the caller can intern a brush scaled by the same factor —
 * which is exactly Ik10's re-brush, reused. Keeping that in the caller rather
 * than here is what stops this type needing to know about `Brush` at all.
 *
 * Pure, JVM, no pixels. `:engine`.
 */
object StrokeTransform {

    /**
     * [record]'s samples through the affine [m], as nine floats in
     * `android.graphics.Matrix.getValues` order.
     *
     * The caller supplies the id, the brush index and the bounds, because all
     * three are the *sheet's* to decide — the same split `PendingStroke` makes
     * and for the same reason.
     */
    fun mapped(
        record: StrokeRecord,
        m: FloatArray,
        id: Long,
        brush: Int,
        bounds: Bounds,
    ): StrokeRecord {
        require(m.size >= 9) { "a matrix is nine floats; got ${m.size}" }
        val n = record.sampleCount
        val samples = FloatArray(record.floatCount)
        record.decodeInto(samples)
        // The rotation the matrix applies, taken once: it is the same for every
        // sample, and `atan2` twice a sample at 321.75 Hz is not a thing to do
        // for an answer that cannot change.
        val turn = rotationOf(m)
        val log = SampleLog()
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            val x = samples[o]
            val y = samples[o + 1]
            log.add(
                xDoc = m[0] * x + m[1] * y + m[2],
                yDoc = m[3] * x + m[4] * y + m[5],
                pressure = samples[o + 2],
                tiltRad = samples[o + 3],
                orientationRad = wrap(samples[o + 4] + turn),
                timeMillis = samples[o + 5],
            )
        }
        return StrokeRecord(
            id = id,
            brush = brush,
            colorArgb = record.colorArgb,
            erase = record.erase,
            seed = record.seed,
            dabBase = record.dabBase,
            clip = record.clip,
            guide = record.guide,
            bounds = bounds,
            packed = log.pack(),
            sampleCount = log.count,
        )
    }

    /**
     * The uniform scale [m] applies: the geometric mean of the two axes.
     *
     * The mean and not one of them, because a box dragged by a corner scales
     * both and a nib has one width. A non-uniform scale is therefore rendered
     * as a uniform one of the same area — the alternative is an elliptical nib
     * that changes shape along the stroke, which is a feature nobody asked for
     * and `MaskSpec` would have to grow a field for.
     */
    fun scaleOf(m: FloatArray): Float {
        require(m.size >= 9) { "a matrix is nine floats; got ${m.size}" }
        val sx = sqrt(m[0] * m[0] + m[3] * m[3])
        val sy = sqrt(m[1] * m[1] + m[4] * m[4])
        val s = sqrt(sx * sy)
        return if (s.isFinite() && s > 0f) s else 1f
    }

    /** The rotation [m] applies, in radians. */
    fun rotationOf(m: FloatArray): Float {
        require(m.size >= 9) { "a matrix is nine floats; got ${m.size}" }
        val a = atan2(m[3], m[0])
        return if (a.isFinite()) a else 0f
    }

    /** Whether [m] is the identity, to within a tenth of a document pixel. */
    fun isIdentity(m: FloatArray): Boolean {
        if (m.size < 9) return true
        return kotlin.math.abs(m[0] - 1f) < 1e-4f && kotlin.math.abs(m[1]) < 1e-4f &&
            kotlin.math.abs(m[2]) < 0.1f && kotlin.math.abs(m[3]) < 1e-4f &&
            kotlin.math.abs(m[4] - 1f) < 1e-4f && kotlin.math.abs(m[5]) < 0.1f
    }

    /** Back into -PI..PI, which is the range the packing stores. */
    private fun wrap(a: Float): Float {
        var v = a
        while (v > PI_F) v -= TWO_PI
        while (v < -PI_F) v += TWO_PI
        return v
    }

    private const val PI_F = Math.PI.toFloat()
    private const val TWO_PI = (Math.PI * 2).toFloat()
}
