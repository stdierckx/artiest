package be.thalos.artiest.engine.ink

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Receives dabs from [CatmullRomResampler] and tells it how far to go before
 * the next one.
 *
 * The return value is what keeps the whole stage allocation-free without
 * threading a brush through the geometry. Spacing depends on dab radius,
 * radius depends on pressure, and pressure is only known at the moment a dab
 * is placed — so either the resampler learns about `RoundPen`, or the sink
 * answers the one question the resampler has. This is the second.
 *
 * **The return must be strictly positive.** A zero or negative spacing is an
 * infinite loop inside [CatmullRomResampler.add] — not a wrong picture, a hung
 * UI thread with the pen still on the glass. [RoundPen.spacingFor] floors it at
 * [RoundPen.MIN_SPACING_DOC] for that reason, and the resampler checks the
 * returned value rather than trusting it.
 */
fun interface DabEmitter {

    /**
     * Place a dab at ([x], [y]) in document space with the interpolated
     * [pressure] and [elapsedMillis] since pen-down, and return the arc
     * distance to the next dab. Must be `> 0`.
     */
    fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float
}

/**
 * Fits a centripetal Catmull-Rom spline through the stabilized sample points
 * and walks it at constant arc length, handing each dab position to a
 * [DabEmitter].
 *
 * **Why this stage is not optional.** At 321.75 Hz a hand moving at a
 * comfortable 400 doc px/s leaves 1.2 px between samples, and at that rate raw
 * samples would do. A fast flick is 4000 px/s and leaves 12 px between samples,
 * and a genuine snap is several times that — tens of pixels of gap, which
 * without resampling is a dotted line. The spline is what puts dabs *between*
 * samples; the arc-length walk is what makes them evenly dense whatever the
 * hand did.
 *
 * **Centripetal, alpha = 0.5, and not uniform.** Uniform Catmull-Rom overshoots
 * and can form a cusp or a small loop when three consecutive points are
 * unevenly spaced — which is the normal condition here, because the digitizer
 * samples at a fixed rate and the hand does not move at a fixed speed. The
 * overshoot shows up as a hooked tail where the pen changed speed, and it is
 * exactly the artefact people describe as the line "whipping". The centripetal
 * parameterization is the standard fix and provably has neither cusps nor
 * self-intersections within a segment.
 *
 * **The one-sample lag, which is a real cost and a deliberate one.** A segment
 * from point k to k+1 needs points k-1 and k+2, so nothing can be emitted for
 * a point until the point *after* it has arrived: one sample of latency,
 * 3.1 ms at 321.75 Hz and 4.1 ms at 246.85 Hz. The alternative is to emit the
 * tail speculatively against a duplicated control point and revise it when the
 * real one lands — and revision is precisely what this architecture cannot do.
 * A dab stamped into the front buffer is baked until pen-up; there is no
 * retraction. So the choice is one sample of lag or permanently wrong ink, and
 * it is not close. [end] flushes the held segment against a duplicated final
 * point, which is a revision that never happens because there is nothing after
 * it.
 *
 * Allocation-free after construction: four `FloatArray(4)` control-point slots
 * and twelve scalar coefficients, all reused across strokes. Not thread-safe;
 * one instance lives inside a [StrokeBuilder] on the UI thread.
 */
class CatmullRomResampler(private val emitter: DabEmitter) {

    // The four live control points, oldest at 0. Shifted rather than kept in a
    // modular ring: four slots means three copies of four floats per sample,
    // which is not measurable at 321 Hz, and an index that never wraps is one
    // fewer thing to get wrong at the segment boundaries.
    private val px = FloatArray(4)
    private val py = FloatArray(4)
    private val pp = FloatArray(4)
    private val pt = FloatArray(4)

    /** Cubic coefficients for the current segment, `((a*u + b)*u + c)*u + d`. */
    private var ax = 0f
    private var bx = 0f
    private var cx = 0f
    private var dx = 0f
    private var ay = 0f
    private var by = 0f
    private var cy = 0f
    private var dy = 0f

    private var count = 0

    /**
     * Arc distance still owed before the next dab, carried *across* segments.
     *
     * Resetting this per segment would place a dab at every sample regardless
     * of spacing, which at 321.75 Hz and a slow hand means hundreds of dabs
     * per pixel; and it would make dab density a function of hand speed, which
     * is the one thing the arc-length walk exists to remove.
     */
    private var need = 0f

    private var ended = false

    /** Drops all state. Call at ACTION_DOWN. */
    fun begin() {
        count = 0
        need = 0f
        ended = false
    }

    /**
     * Feed one stabilized point in document space.
     *
     * The first point emits its dab immediately — the nib is on the glass and
     * ink has to appear — and every later point emits the segment that became
     * final when it arrived. See the lag note in the class header.
     */
    fun add(x: Float, y: Float, pressure: Float, elapsedMillis: Float) {
        check(!ended) { "add() after end(); call begin() to start a stroke" }
        push(x, y, pressure, elapsedMillis)
        when {
            count == 1 -> need = spacingFrom(emitter.emit(x, y, pressure, elapsedMillis))
            count >= 3 -> emitSegment()
        }
    }

    /**
     * Close the stroke, flushing the segment held back by the lag.
     *
     * Duplicating the last point as the fourth control point makes the final
     * segment's outgoing tangent point along the incoming one, so the stroke
     * ends straight rather than curling toward a control point that does not
     * exist. A single-point stroke — a tap — has already emitted its one dab
     * and produces nothing here.
     */
    fun end() {
        if (ended || count == 0) {
            ended = true
            return
        }
        push(px[3], py[3], pp[3], pt[3])
        if (count >= 3) emitSegment()
        ended = true
    }

    /**
     * Shift the window and append. The first point fills all four slots, so the
     * opening segment's missing predecessor is the start point itself — the
     * same trick as [end], at the other end.
     */
    private fun push(x: Float, y: Float, pressure: Float, elapsedMillis: Float) {
        if (count == 0) {
            for (i in 0..3) {
                px[i] = x
                py[i] = y
                pp[i] = pressure
                pt[i] = elapsedMillis
            }
        } else {
            for (i in 0..2) {
                px[i] = px[i + 1]
                py[i] = py[i + 1]
                pp[i] = pp[i + 1]
                pt[i] = pt[i + 1]
            }
            px[3] = x
            py[3] = y
            pp[3] = pressure
            pt[3] = elapsedMillis
        }
        count++
    }

    /**
     * Build the cubic for the segment between slots 1 and 2 and walk it.
     *
     * Returns without emitting when the segment is degenerate — slots 1 and 2
     * coincide, which happens whenever the pen dwells or the digitizer repeats
     * a position. That is the one case the centripetal knots cannot absorb:
     * every other duplicate divides a zero difference by a small knot delta and
     * yields zero, but a zero-length *segment* divides by its own knot delta
     * twice and would produce infinities. Skipping is also the right picture —
     * a stationary pen should not accumulate dabs.
     */
    private fun emitSegment() {
        val d01 = knot(px[0], py[0], px[1], py[1])
        val d12 = knot(px[1], py[1], px[2], py[2])
        val d23 = knot(px[2], py[2], px[3], py[3])
        if (d12 <= DEGENERATE_KNOT) return

        prepareAxis(px[0], px[1], px[2], px[3], d01, d12, d23) { a, b, c, d ->
            ax = a; bx = b; cx = c; dx = d
        }
        prepareAxis(py[0], py[1], py[2], py[3], d01, d12, d23) { a, b, c, d ->
            ay = a; by = b; cy = c; dy = d
        }

        // Subdivision is sized off the straight-line chord. A centripetal
        // segment's arc length exceeds its chord by well under 2x for any
        // curvature a hand produces, so the sub-chord stays under 0.5 doc px —
        // which is MIN_SPACING_DOC, the finest spacing any dab can ask for.
        // Placement within a sub-chord is linear, and a chord's deviation from
        // the curve it subtends falls off as the square of its length, so at
        // this scale the placement error is far below the pixel the dab is
        // eventually rasterized into.
        val chord = dist(px[1], py[1], px[2], py[2])
        val steps = (ceil(chord / STEP_DOC).toInt()).coerceIn(MIN_STEPS, MAX_STEPS)

        var prevX = px[1]
        var prevY = py[1]
        val p1 = pp[1]
        val p2 = pp[2]
        val t1 = pt[1]
        val t2 = pt[2]
        val inv = 1f / steps

        for (i in 1..steps) {
            val u = i * inv
            val curX = ((ax * u + bx) * u + cx) * u + dx
            val curY = ((ay * u + by) * u + cy) * u + dy
            val sub = dist(prevX, prevY, curX, curY)
            if (sub > 0f) {
                var travelled = 0f
                while (need <= sub - travelled) {
                    travelled += need
                    val f = travelled / sub
                    // Pressure and time are interpolated linearly in u, not
                    // through the spline. A Catmull-Rom fit through pressure
                    // overshoots exactly where it overshoots position, and an
                    // overshoot below zero is a negative radius. Linear cannot
                    // leave the interval its endpoints span.
                    val uu = (i - 1 + f) * inv
                    val emitted = emitter.emit(
                        prevX + (curX - prevX) * f,
                        prevY + (curY - prevY) * f,
                        p1 + (p2 - p1) * uu,
                        t1 + (t2 - t1) * uu,
                    )
                    need = spacingFrom(emitted)
                }
                need -= sub - travelled
            }
            prevX = curX
            prevY = curY
        }
    }

    /**
     * Non-uniform Catmull-Rom tangents, converted to cubic coefficients over
     * `u` in 0..1.
     *
     * `m1` and `m2` are the standard Barry-Goldman tangents rescaled by the
     * segment's own knot span so the segment can be evaluated on the unit
     * interval; `a`..`d` are the Hermite basis collapsed into a Horner cubic,
     * so evaluation is three multiplies and three adds per axis.
     */
    private inline fun prepareAxis(
        v0: Float,
        v1: Float,
        v2: Float,
        v3: Float,
        d01: Float,
        d12: Float,
        d23: Float,
        out: (Float, Float, Float, Float) -> Unit,
    ) {
        val m1 = ((v1 - v0) / d01 - (v2 - v0) / (d01 + d12) + (v2 - v1) / d12) * d12
        val m2 = ((v2 - v1) / d12 - (v3 - v1) / (d12 + d23) + (v3 - v2) / d23) * d12
        out(
            2f * v1 - 2f * v2 + m1 + m2,
            -3f * v1 + 3f * v2 - 2f * m1 - m2,
            m1,
            v1,
        )
    }

    /**
     * The centripetal knot delta: the square root of the distance between two
     * control points, floored so a duplicate point divides by a small number
     * rather than by zero.
     *
     * The floor is safe precisely because the parameterization is centripetal.
     * A near-duplicate at distance d contributes a numerator of d and a
     * denominator of sqrt(d), so the tangent term goes as sqrt(d) and vanishes
     * as the points merge — the ratio is self-limiting, and the floor only ever
     * catches the exactly-equal case, which [emitSegment] handles separately
     * when it is the segment's own endpoints.
     */
    private fun knot(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val d = sqrt(dist(x0, y0, x1, y1))
        return if (d > DEGENERATE_KNOT) d else DEGENERATE_KNOT
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        // Not hypot(): it is a slow, overflow-safe routine, and these are
        // document coordinates on a 2160 x 3300 canvas whose squares cannot
        // come near overflowing a float.
        return sqrt(dx * dx + dy * dy)
    }

    private fun spacingFrom(v: Float): Float {
        check(v > 0f && v.isFinite()) {
            "DabEmitter returned a spacing of $v; the walk would not terminate"
        }
        return v
    }

    companion object {

        /** Target sub-chord length in document pixels. See [emitSegment]. */
        const val STEP_DOC: Float = 0.25f

        /**
         * A short segment still gets eight samples, because "short" here is a
         * chord and the curve between two nearly coincident points can still
         * bow out if the neighbours are far away.
         */
        const val MIN_STEPS: Int = 8

        /**
         * Caps the walk at 4096 sub-steps, which is 1024 doc px of chord at
         * [STEP_DOC] — half the width of the document in the gap between two
         * consecutive samples. Reaching it means the input is not a hand, and
         * the cap trades resolution nobody can see for a bounded loop.
         */
        const val MAX_STEPS: Int = 4096

        /**
         * Knot floor. The square root of a distance, so this is `sqrt(1e-12)`
         * worth of separation — far below a float's resolution at document
         * scale, which is the point: nothing that is not exactly a duplicate
         * lands here.
         */
        const val DEGENERATE_KNOT: Float = 1e-6f
    }
}
