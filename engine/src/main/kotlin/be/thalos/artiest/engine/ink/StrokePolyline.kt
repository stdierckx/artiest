package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import kotlin.math.max
import kotlin.math.min

/**
 * A stroke's centreline, simplified, with a half-width at every point: what
 * "did I tap this stroke" is answered against.
 *
 * ## Why not against the samples, and why not against the dabs
 *
 * The samples are 321.75 a second and most of them are a tenth of a pixel
 * apart, so testing against them is hundreds of times the work for an answer
 * that does not change. The dabs are worse: they are the derived, expensive,
 * brush-specific thing this whole design exists not to keep, and producing them
 * means re-running the stroke.
 *
 * So a hit test runs against a third thing — the path, thinned to points two
 * document pixels apart, carrying the half-width the size response gives at
 * each one. **Two pixels is `Marquee.MIN_STEP_DOC_PX`**, the same number the
 * lasso already thins its own path to, chosen there for the same reason and
 * reused here so there is one answer in the program to "how fine is fine
 * enough for a gesture".
 *
 * A 400 px stroke is about 200 points, 2.4 KB. That is a few hundred bytes to
 * low kilobytes a stroke against records that are already kilobytes, and it is
 * the first thing dropped under memory pressure — see
 * [StrokeRecord.dropDerived].
 *
 * ## What the half-width is, and what it is not
 *
 * It is `Brush.sizeFor(pressure, elapsed) / 2`: the nominal size response,
 * evaluated without a `DabContext`. A brush whose size is driven by tilt or
 * speed paints wider than this in places, and a brush with scatter throws ink
 * up to `scatter.max` outside it — so [maxOvershoot] reports how far the real
 * mark can lie outside this band, and every hit test adds it. Getting that
 * wrong in the safe direction costs a tap that selects a stroke whose ink is a
 * pixel and a half away; getting it wrong in the other direction costs a tap on
 * visible ink that selects nothing, which is the one users report.
 *
 * Immutable. Built by [of], off the render thread or on it; nothing here
 * allocates after construction.
 */
class StrokePolyline private constructor(
    /** x, y, halfWidth, sampleIndex quads. Private so no caller can alias it. */
    private val points: FloatArray,
    val pointCount: Int,
    /**
     * The centreline's own extent, inflated by each point's half-width. Close
     * to but not the same as [StrokeRecord.bounds], which is the extent the dab
     * loop actually painted and includes scatter; this one is the early-out for
     * a hit test and is allowed to be the smaller of the two.
     */
    val bounds: Bounds,
    /** See the class note. Added to every distance before it is compared. */
    val maxOvershoot: Float,
) {

    /** Point [i]'s x, in document space. */
    fun x(i: Int): Float = points[i * STRIDE]

    /** Point [i]'s y. */
    fun y(i: Int): Float = points[i * STRIDE + 1]

    /** Half the stroke's width at point [i], in document pixels. */
    fun halfWidth(i: Int): Float = points[i * STRIDE + 2]

    /**
     * Which sample of the record point [i] came from.
     *
     * **The thing that makes a cut expressible.** Ik8 erases part of a stroke,
     * and "part" has to be said in the record's own terms — a range of samples —
     * because that is what a new record is built out of. Without this the
     * geometry could say *where* to cut and nothing could act on it.
     *
     * A float only because the array is one; it is a whole number.
     */
    fun sampleAt(i: Int): Int = points[i * STRIDE + 3].toInt()

    /** What this costs in memory. */
    val byteCount: Int get() = points.size * 4

    /**
     * Distance from ([xDoc], [yDoc]) to the edge of the painted band, in
     * document pixels; **0 inside it**, and [Float.MAX_VALUE] for a polyline
     * with no points.
     *
     * Clamped at zero rather than signed, because every caller asks "how far
     * outside", and a signed answer would invite a caller to compare depths
     * inside two overlapping strokes — which is not what decides which stroke a
     * tap picks. Draw order decides that, and the sheet owns it.
     */
    fun distanceTo(xDoc: Float, yDoc: Float): Float {
        if (pointCount == 0) return Float.MAX_VALUE
        if (pointCount == 1) {
            return max(0f, dist(xDoc, yDoc, x(0), y(0)) - halfWidth(0))
        }
        var best = Float.MAX_VALUE
        for (i in 0 until pointCount - 1) {
            val ax = x(i)
            val ay = y(i)
            val bx = x(i + 1)
            val by = y(i + 1)
            val t = projection(xDoc, yDoc, ax, ay, bx, by)
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t
            // The half-width is interpolated with the point, so a taper reads
            // as a taper rather than as a rectangle ending in a step.
            val hw = halfWidth(i) + (halfWidth(i + 1) - halfWidth(i)) * t
            val d = dist(xDoc, yDoc, px, py) - hw
            if (d < best) best = d
            if (best <= 0f) return 0f
        }
        return max(0f, best)
    }

    /**
     * True when ([xDoc], [yDoc]) lands on this stroke's ink, within [slopDoc]
     * document pixels of forgiveness.
     *
     * [maxOvershoot] is added here rather than baked into the stored
     * half-widths, so that the band a hit test uses stays visibly separate from
     * the band the stroke actually paints — the second is what Ik8's split has
     * to be exact about, and merging the two would quietly widen it.
     */
    fun hits(xDoc: Float, yDoc: Float, slopDoc: Float): Boolean {
        val reach = slopDoc + maxOvershoot
        if (bounds.isEmpty) return false
        if (xDoc < bounds.left - reach || xDoc > bounds.right + reach) return false
        if (yDoc < bounds.top - reach || yDoc > bounds.bottom + reach) return false
        return distanceTo(xDoc, yDoc) <= reach
    }

    override fun toString(): String = "StrokePolyline($pointCount points, $bounds)"

    companion object {

        /** Floats per point: x, y, halfWidth, sample index. */
        const val STRIDE: Int = 4

        /**
         * How far apart kept points are, in document pixels.
         *
         * `Marquee.MIN_STEP_DOC_PX`, restated rather than imported because
         * `:engine` cannot see `:app`. If one of the two moves, the other is
         * wrong; the pair is pinned in `StrokePolylineTest`.
         */
        const val MIN_STEP_DOC: Float = 2f

        /**
         * Build the centreline for [record] under [pen].
         *
         * Allocates one float array for the decoded samples and one for the
         * result. Both are transient except the result, which the record then
         * caches.
         */
        fun of(record: StrokeRecord, pen: Brush): StrokePolyline {
            val n = record.sampleCount
            if (n == 0) return EMPTY
            val raw = FloatArray(n * StrokeRecord.STRIDE)
            record.decodeInto(raw)
            return of(raw, n, pen)
        }

        /**
         * The same, from already-decoded samples — six floats each, in
         * [StrokeRecord.STRIDE] order.
         *
         * Public because the live path has the samples in hand at pen-up and
         * would otherwise pack them, hand them to a record, and immediately
         * decode them again.
         */
        fun of(samples: FloatArray, count: Int, pen: Brush): StrokePolyline {
            require(count >= 0 && samples.size >= count * StrokeRecord.STRIDE) {
                "need ${count * StrokeRecord.STRIDE} floats, array holds ${samples.size}"
            }
            if (count == 0) return EMPTY
            val out = FloatArray(count * STRIDE)
            var kept = 0
            var lastX = 0f
            var lastY = 0f
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY

            fun keep(x: Float, y: Float, hw: Float, sample: Int) {
                val o = kept * STRIDE
                out[o] = x
                out[o + 1] = y
                out[o + 2] = hw
                out[o + 3] = sample.toFloat()
                left = min(left, x - hw)
                top = min(top, y - hw)
                right = max(right, x + hw)
                bottom = max(bottom, y + hw)
                lastX = x
                lastY = y
                kept++
            }

            for (i in 0 until count) {
                val s = i * StrokeRecord.STRIDE
                val x = samples[s]
                val y = samples[s + 1]
                val hw = pen.sizeFor(samples[s + 2], samples[s + 5]) * 0.5f
                if (kept == 0) {
                    keep(x, y, hw, i)
                    continue
                }
                val dx = x - lastX
                val dy = y - lastY
                if (dx * dx + dy * dy < MIN_STEP_DOC * MIN_STEP_DOC) continue
                keep(x, y, hw, i)
            }
            // The last sample is kept whatever the spacing, because the tail of
            // a stroke is where the taper is and a dropped tail is a stroke
            // whose last few pixels cannot be tapped. One extra point.
            val lastS = (count - 1) * StrokeRecord.STRIDE
            if (kept < count &&
                (samples[lastS] != lastX || samples[lastS + 1] != lastY)
            ) {
                keep(
                    samples[lastS],
                    samples[lastS + 1],
                    pen.sizeFor(samples[lastS + 2], samples[lastS + 5]) * 0.5f,
                    count - 1,
                )
            }

            val bounds =
                if (kept == 0) Bounds.EMPTY else Bounds.of(left, top, right, bottom)
            return StrokePolyline(
                points = if (kept * STRIDE == out.size) out else out.copyOf(kept * STRIDE),
                pointCount = kept,
                bounds = bounds,
                maxOvershoot = overshootOf(pen),
            )
        }

        /**
         * How far outside the nominal band a brush can actually paint.
         *
         * Scatter throws a dab up to `scatter.max` document pixels off the
         * path, and a size driven by a sensor other than pressure can evaluate
         * above what `sizeFor(pressure, elapsed)` returns — bounded by
         * `sizeMax / 2`, which is what the option's own maximum means. Taking
         * the larger of the two rather than the sum, because a dab that is
         * thrown *and* at full size is the same dab counted twice and the sum
         * would put the tap band an inch wide on a large scattered nib.
         */
        private fun overshootOf(pen: Brush): Float {
            val scatter = if (pen.scatter.max > 0f) pen.scatter.max else 0f
            val sensed = if (pen.size.inputCount > 0) pen.size.max * 0.5f else 0f
            return max(scatter, sensed)
        }

        /** No points, no ink, nothing hits it. */
        val EMPTY: StrokePolyline =
            StrokePolyline(FloatArray(0), 0, Bounds.EMPTY, 0f)

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        /**
         * Where ([px], [py]) falls along segment a..b, clamped to 0..1.
         *
         * A zero-length segment answers 0 rather than dividing: two samples can
         * legitimately land on the same quantised point, and the distance to
         * either end of a degenerate segment is the same number.
         */
        private fun projection(
            px: Float,
            py: Float,
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float {
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            if (len2 <= 0f) return 0f
            val t = ((px - ax) * vx + (py - ay) * vy) / len2
            return if (t < 0f) 0f else if (t > 1f) 1f else t
        }
    }
}
