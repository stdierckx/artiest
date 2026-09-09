package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.Stabilizer
import be.thalos.artiest.engine.input.ToolType
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `StrokeBuilder` is where the four W7 pieces meet, so most of what is worth
 * testing here is the seams: that the bounds it accumulates actually contains
 * the dabs it emitted, that a `Stroke` handed to the render thread cannot be
 * rewritten by the next stroke, and that the buffer growth is invisible.
 *
 * The bounds property is the one to read first. Its failure is a stale rim of
 * ink along a redrawn edge, or — after Phase 3 — an undo that restores a
 * neighbour's pixels, and neither reproduces reliably with a hand on glass.
 */
class StrokeBuilderTest {

    private val dtNanos = 3_107_855L // 321.75 Hz, the measured rate at 90 Hz

    private fun sample(x: Float, y: Float, p: Float, i: Int) = PenSample(
        x = x,
        y = y,
        pressure = p,
        tilt = 0f,
        orientation = 0f,
        distance = 0f,
        toolType = ToolType.STYLUS,
        buttonState = 0,
        eventTimeNanos = i * dtNanos,
        source = PenSample.Source.CURRENT,
    )

    private fun drag(b: StrokeBuilder, n: Int, step: Float = 8f, pressure: Float = 0.6f) {
        for (i in 0 until n) b.add(sample(100f + i * step, 200f, pressure, i))
    }

    @Test
    fun `a stroke's bounds contains every dab it emitted, and no more`() {
        val b = StrokeBuilder()
        b.begin(0xFF000000.toInt())
        // A curve, so the bounds is not trivially the sample bounding box.
        for (i in 0 until 60) {
            val t = i / 59f
            b.add(sample(200f + 400f * t, 500f + 300f * sqrt(t), 0.2f + 0.6f * t, i))
        }
        // Read the dabs off the finished Stroke, not off the builder before
        // end(): end() flushes the segment the resampler's one-sample lag was
        // holding, so a snapshot taken first is short by the tail of the
        // stroke — which is exactly the bug end() exists to prevent.
        val s = b.end()
        val dabs = (0 until s.dabCount).map { Triple(s.x(it), s.y(it), s.radius(it)) }
        assertTrue(dabs.size > 100, "only ${dabs.size} dabs")

        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var bo = Float.NEGATIVE_INFINITY
        for ((x, y, rad) in dabs) {
            l = minOf(l, x - rad); t = minOf(t, y - rad)
            r = maxOf(r, x + rad); bo = maxOf(bo, y + rad)
        }
        assertEquals(l, s.bounds.left, 0f, "left")
        assertEquals(t, s.bounds.top, 0f, "top")
        assertEquals(r, s.bounds.right, 0f, "right")
        assertEquals(bo, s.bounds.bottom, 0f, "bottom")
    }

    @Test
    fun `radii are the pen's curve applied to the interpolated pressure`() {
        val pen = Brush()
        val b = StrokeBuilder(pen)
        b.begin(0xFF112233.toInt())
        drag(b, 40, pressure = 0.6f)
        val s = b.end()
        // Constant pressure through a filter whose output is a convex
        // combination of its inputs is that pressure, so every settled dab is
        // the same size — and that size is the pen's, not something the
        // resampler invented.
        val expected = pen.sizeFor(0.6f, 100f) * 0.5f
        val settled = (s.dabCount / 2 until s.dabCount).map { s.radius(it) }
        for (r in settled) assertEquals(expected, r, 1e-4f)
    }

    @Test
    fun `the emitted stroke does not alias the builder's buffer`() {
        val b = StrokeBuilder()
        b.begin(0xFFFF0000.toInt())
        drag(b, 30)
        val first = b.end()
        val x0 = first.x(0)
        val y0 = first.y(0)
        val n0 = first.dabCount

        // Draw a completely different stroke through the same builder. If the
        // Stroke aliased the buffer, the render thread would be stamping this
        // one while it thought it had the last one.
        b.begin(0xFF00FF00.toInt())
        for (i in 0 until 200) b.add(sample(1500f - i * 4f, 2000f + i * 3f, 0.9f, i))
        b.end()

        assertEquals(n0, first.dabCount)
        assertEquals(x0, first.x(0))
        assertEquals(y0, first.y(0))
        assertEquals(0xFFFF0000.toInt(), first.colorArgb)
    }

    @Test
    fun `growing past the initial buffer keeps every dab already written`() {
        val b = StrokeBuilder()
        b.begin(0)
        // 3 px spacing at full pressure; INITIAL_DABS is 256, so ~800 px of
        // path crosses the first growth and 4000 px crosses several.
        drag(b, 500, step = 8f, pressure = 1f)
        val snapshot = (0 until b.dabCount).map { Triple(b.x(it), b.y(it), b.radius(it)) }
        val s = b.end()
        assertTrue(s.dabCount > StrokeBuilder.INITIAL_DABS, "only ${s.dabCount} dabs; no growth happened")
        for (i in snapshot.indices) {
            assertEquals(snapshot[i].first, s.x(i), "x at $i")
            assertEquals(snapshot[i].second, s.y(i), "y at $i")
            assertEquals(snapshot[i].third, s.radius(i), "radius at $i")
        }
    }

    @Test
    fun `dabs stay evenly spaced across the whole stroke`() {
        val pen = Brush()
        val b = StrokeBuilder(pen)
        b.begin(0)
        drag(b, 300, step = 12f, pressure = 1f)
        val s = b.end()
        val want = pen.spacingFor(pen.sizeFor(1f, 100f) * 0.5f)
        for (i in 2 until s.dabCount) {
            val d = sqrt(
                (s.x(i) - s.x(i - 1)) * (s.x(i) - s.x(i - 1)) +
                    (s.y(i) - s.y(i - 1)) * (s.y(i) - s.y(i - 1)),
            )
            assertEquals(want, d, 0.05f, "gap $i")
        }
    }

    @Test
    fun `a tap is one dab with a real bounds`() {
        val b = StrokeBuilder()
        b.begin(0)
        b.add(sample(300f, 400f, 0.5f, 0))
        val s = b.end()
        assertEquals(1, s.dabCount)
        assertFalse(s.bounds.isEmpty)
        assertEquals(s.x(0) - s.radius(0), s.bounds.left, 0f)
    }

    @Test
    fun `a stroke with no samples is empty in both senses`() {
        val b = StrokeBuilder()
        b.begin(0)
        val s = b.end()
        assertEquals(0, s.dabCount)
        assertTrue(s.bounds.isEmpty, "an empty stroke must not carry a rectangle")
    }

    @Test
    fun `cancel produces nothing and leaves the builder reusable`() {
        val b = StrokeBuilder()
        b.begin(0)
        drag(b, 50)
        assertTrue(b.dabCount > 0)
        b.cancel()
        assertFalse(b.isOpen)
        assertEquals(0, b.dabCount)
        assertTrue(b.boundsSoFar().isEmpty, "a cancelled stroke left its rectangle behind")
        b.begin(0)
        drag(b, 10)
        assertTrue(b.end().dabCount > 0, "the builder did not come back")
    }

    @Test
    fun `add before begin and end before begin are refused`() {
        val b = StrokeBuilder()
        assertFailsWith<IllegalStateException> { b.add(sample(0f, 0f, 0.5f, 0)) }
        assertFailsWith<IllegalStateException> { b.end() }
    }

    @Test
    fun `a non finite sample is refused at the sample that carried it`() {
        val b = StrokeBuilder()
        b.begin(0)
        b.add(sample(10f, 10f, 0.5f, 0))
        assertFailsWith<IllegalArgumentException> { b.add(sample(Float.NaN, 10f, 0.5f, 1)) }
        assertFailsWith<IllegalArgumentException> { b.add(sample(10f, Float.POSITIVE_INFINITY, 0.5f, 2)) }
        assertFailsWith<IllegalArgumentException> { b.add(sample(10f, 10f, Float.NaN, 3)) }
    }

    @Test
    fun `moving the stabilization slider takes effect on the next stroke`() {
        val pen = Brush().apply { stabilization = 0f }
        val b = StrokeBuilder(pen)
        b.begin(0)
        // With no smoothing, a hard corner stays a hard corner.
        b.add(sample(0f, 0f, 0.5f, 0))
        b.add(sample(100f, 0f, 0.5f, 1))
        b.add(sample(100f, 100f, 0.5f, 2))
        val sharp = b.end()

        pen.stabilization = 0.8f
        b.begin(0)
        b.add(sample(0f, 0f, 0.5f, 0))
        b.add(sample(100f, 0f, 0.5f, 1))
        b.add(sample(100f, 100f, 0.5f, 2))
        val smoothed = b.end()

        assertTrue(
            smoothed.bounds.width < sharp.bounds.width - 1f,
            "smoothing did not take: ${smoothed.bounds} vs ${sharp.bounds}",
        )
    }

    @Test
    fun `the dab cap fails loudly rather than doubling a buffer forever`() {
        // A degenerate brush pinned at the minimum spacing, dragged far enough
        // to pass the cap. 524,288 dabs at half a document pixel is 262,144 px
        // of path: not a drawing, which is the whole argument for crashing.
        // stabilization = 0 matters here and is not incidental. The builder
        // feeds the resampler *stabilized* points, so with smoothing on, a
        // 150,000 px jump between two samples arrives at the spline as a
        // 60,000 px one and the path is shorter than the samples suggest. Any
        // test that reasons about raw sample positions has to turn it off.
        val pen = Brush().apply {
            sizeMin = 0f
            sizeMax = 0f
            onsetMillis = 0f
            stabilization = 0f
        }
        val b = StrokeBuilder(pen)
        b.begin(0)
        b.add(sample(0f, 0f, 0f, 0))
        b.add(sample(150_000f, 0f, 0f, 1))
        val e = assertFailsWith<IllegalStateException> {
            b.add(sample(300_000f, 0f, 0f, 2))
            b.add(sample(450_000f, 0f, 0f, 3))
        }
        assertTrue(e.message!!.contains("dabs"), "unhelpful message: ${e.message}")
    }

    @Test
    fun `antiAlias and colour are frozen at pen down`() {
        val pen = Brush()
        val b = StrokeBuilder(pen)
        b.begin(0xFF203040.toInt())
        drag(b, 20)
        val s = b.end()
        assertEquals(0xFF203040.toInt(), s.colorArgb)
        assertTrue(s.antiAlias)
        pen.antiAlias = false
        assertTrue(s.antiAlias, "the finished stroke followed the pen")
    }
    @Test
    fun `the by-parts overload and the PenSample one are the same stroke, bit for bit`() {
        // The app calls the by-parts overload because its coordinates come out
        // of CanvasTransform.viewToDoc rather than out of a PenSample, and a
        // second PenSample per digitizer sample is not in the budget. The two
        // must therefore be one code path and not two that agree today: this
        // compares raw bits, so a divergence of one ulp fails here rather than
        // showing up as a golden that only moves on the tablet.
        val pen = Brush()
        val viaSample = StrokeBuilder(pen)
        val viaParts = StrokeBuilder(pen)
        viaSample.begin(0x11223344)
        viaParts.begin(0x11223344)

        for (i in 0 until 40) {
            val x = 100f + i * 7.3f
            val y = 200f + i * i * 0.11f
            val p = 0.2f + i * 0.02f
            viaSample.add(sample(x, y, p, i))
            viaParts.add(x, y, p, i * dtNanos)
        }
        val a = viaSample.end()
        val b = viaParts.end()

        assertEquals(a.dabCount, b.dabCount)
        assertTrue(a.dabCount > 0, "the stroke produced no dabs, so nothing was compared")
        for (i in 0 until a.dabCount) {
            assertEquals(a.x(i).toRawBits(), b.x(i).toRawBits(), "dab $i x")
            assertEquals(a.y(i).toRawBits(), b.y(i).toRawBits(), "dab $i y")
            assertEquals(a.radius(i).toRawBits(), b.radius(i).toRawBits(), "dab $i radius")
        }
    }

    @Test
    fun `a speculative fork cannot drag the real ink toward a guess`() {
        // W11's tail is smoothed through a copy of the stroke's filter. If it
        // were the filter itself, every predicted point would move the state
        // the next real sample runs through, and the ink would lean toward
        // wherever the predictor had been guessing — a bias with no symptom
        // except that the line is subtly wrong.
        val pen = Brush()
        val b = StrokeBuilder(pen)
        b.begin(0xFF000000.toInt())
        for (i in 0 until 20) b.add(sample(100f + i * 6f, 200f, 0.6f, i))

        val xBefore = b.smoothedX
        val yBefore = b.smoothedY
        val pressureBefore = b.smoothedPressure
        val dabsBefore = b.dabCount

        val fork = Stabilizer(b.smoothingStrength)
        b.forkSmoothing(fork)
        // A wild guess, far off the line and at the wrong pressure.
        fork.push(9000f, -4000f, 0.05f, 20 * dtNanos)
        assertTrue(fork.x > 1000f, "the fork did not actually move")

        assertEquals(xBefore.toRawBits(), b.smoothedX.toRawBits(), "the fork moved the real filter")
        assertEquals(yBefore.toRawBits(), b.smoothedY.toRawBits())
        assertEquals(pressureBefore.toRawBits(), b.smoothedPressure.toRawBits())
        assertEquals(dabsBefore, b.dabCount, "the fork emitted dabs into the stroke")

        // And the next real sample lands exactly where it would have without
        // the fork ever existing.
        val control = StrokeBuilder(Brush())
        control.begin(0xFF000000.toInt())
        for (i in 0 until 20) control.add(sample(100f + i * 6f, 200f, 0.6f, i))
        b.add(sample(220f, 200f, 0.6f, 20))
        control.add(sample(220f, 200f, 0.6f, 20))
        assertEquals(control.smoothedX.toRawBits(), b.smoothedX.toRawBits())
        assertEquals(control.dabCount, b.dabCount)
    }


    @Test
    fun `the ink's leading edge trails the pen by a measurable time, and W16 needs the number`() {
        // The engine's own contribution to tip lag, end to end, on the JVM.
        //
        // The 240 fps film measures one gap: pen tip to ink. That gap is the
        // digitizer's, the framework's, this pipeline's and the panel's, added
        // together, and the film cannot separate them. This is the pipeline's
        // share, and it is not small — two structural terms, both by design:
        //
        //  - Catmull-Rom needs four knots to emit the segment between the
        //    middle two, so the newest dab is one sample behind the newest
        //    sample. One sample is 3.11 ms at 321.75 Hz.
        //  - the stabilizer settles a fixed time behind a steady pen; see
        //    `StabilizerTest`.
        //
        // On top of those, dabs land on an arc-length grid, so the last one can
        // be up to one spacing short of where the spline actually ends. That is
        // real ink the eye can see missing, so it belongs in the number.
        val rateHz = 321.75f
        val plain = tipLagMs(strength = 0f, rateHz = rateHz)
        val smoothed = tipLagMs(strength = 0.15f, rateHz = rateHz)

        // Measured: 3.21 ms, against a 3.11 ms sample interval. One sample for
        // the spline's knots plus 0.1 ms of spacing quantum, and nothing else
        // hiding in there.
        assertEquals(3.21f, plain, 0.1f, "lag with no smoothing")
        assertEquals(7.71f, smoothed, 0.1f, "lag at the shipped default")

        // The default adds the stabilizer's share and nothing more, which is
        // what makes the film's two runs subtractable — and 4.5 ms of it, on a
        // software path whose every other term put together is under 3.3.
        assertEquals(4.5f, smoothed - plain, 0.1f)

        // The same at the other panel rate: the spline's share tracks the
        // sample interval and the smoothing's does not, which is the whole
        // point of integrating the filter over dt.
        val slow = tipLagMs(strength = 0f, rateHz = 246.85f)
        val slowSmoothed = tipLagMs(strength = 0.15f, rateHz = 246.85f)
        assertEquals(4.07f, slow, 0.1f)
        assertEquals(4.5f, slowSmoothed - slow, 0.1f)
    }

    /**
     * Feed a constant-velocity straight stroke and report how far the last dab
     * is behind the last sample pushed, expressed as time.
     */
    private fun tipLagMs(strength: Float, rateHz: Float): Float {
        val pen = Brush().apply { stabilization = strength }
        val builder = StrokeBuilder(pen)
        builder.begin(0)
        val dtNanos = (1e9f / rateHz).toLong()
        val velocityPxPerMs = 2f
        var t = 0L
        var x = 0f
        repeat(600) {
            // Full pressure throughout, so the onset ramp is over long before
            // the measurement and the radius — and therefore the dab spacing —
            // is constant.
            builder.add(x, 0f, 1f, t)
            t += dtNanos
            x += velocityPxPerMs * (dtNanos / 1e6f)
        }
        val lastSampleX = x - velocityPxPerMs * (dtNanos / 1e6f)
        val lastDabX = builder.x(builder.dabCount - 1)
        return (lastSampleX - lastDabX) / velocityPxPerMs
    }
}
