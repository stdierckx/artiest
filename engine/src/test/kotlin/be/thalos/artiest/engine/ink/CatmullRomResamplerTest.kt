package be.thalos.artiest.engine.ink

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The resampler's job is to put dabs where the samples are not, evenly, and
 * without inventing ink outside the path. Three things can go wrong and all
 * three are tested rather than argued: it can emit a dab it will later wish it
 * had not (the lag), it can bunch dabs at sample boundaries (the carried
 * spacing), and it can overshoot into a hook where the hand changed speed (the
 * parameterization).
 *
 * The overshoot test carries its own control — a uniform Catmull-Rom written
 * out longhand — because "centripetal is better" is only worth asserting if
 * the test can show the thing it is better than actually failing.
 */
class CatmullRomResamplerTest {

    /** Collects dabs and answers with a fixed spacing. */
    private class Recorder(val spacing: Float = 1f) : DabEmitter {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ps = ArrayList<Float>()
        val ts = ArrayList<Float>()
        override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float {
            xs += x
            ys += y
            ps += pressure
            ts += elapsedMillis
            return spacing
        }
        val count get() = xs.size
    }

    private fun dist(i: Int, j: Int, r: Recorder) =
        sqrt((r.xs[j] - r.xs[i]) * (r.xs[j] - r.xs[i]) + (r.ys[j] - r.ys[i]) * (r.ys[j] - r.ys[i]))

    @Test
    fun `the first point emits one dab immediately`() {
        val r = Recorder()
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(10f, 20f, 0.5f, 0f)
        assertEquals(1, r.count, "the nib is on the glass; ink has to appear")
        assertEquals(10f, r.xs[0])
        assertEquals(20f, r.ys[0])
    }

    /**
     * The one-sample lag, stated as a count. Nothing may be emitted for the
     * second point until the third arrives, because a dab emitted earlier
     * would have been fitted against a control point that does not exist yet
     * and there is no way to take it back.
     */
    @Test
    fun `no dab is emitted for a point until the point after it arrives`() {
        val r = Recorder()
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        assertEquals(1, r.count)
        cr.add(50f, 0f, 0.5f, 3f)
        assertEquals(1, r.count, "the second point emitted before its successor arrived")
        cr.add(100f, 0f, 0.5f, 6f)
        assertTrue(r.count > 1, "the third point should have flushed the first segment")
        // ...and what it flushed is the *first* segment, not the second: the
        // furthest dab is still short of the third point.
        assertTrue(r.xs.max() <= 50.001f, "emitted past the flushed segment: ${r.xs.max()}")
    }

    @Test
    fun `end flushes the segment the lag was holding`() {
        val r = Recorder()
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        cr.add(50f, 0f, 0.5f, 3f)
        cr.add(100f, 0f, 0.5f, 6f)
        val beforeEnd = r.xs.max()
        cr.end()
        assertTrue(r.xs.max() > beforeEnd, "end() emitted nothing")
        assertTrue(
            abs(r.xs.max() - 100f) <= 1f,
            "the stroke stopped ${100f - r.xs.max()} px short of the pen; got ${r.xs.max()}",
        )
    }

    @Test
    fun `a straight line comes out evenly spaced and on the line`() {
        val r = Recorder(spacing = 2f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        // Deliberately uneven sample spacing — a hand accelerating — so that
        // even dab spacing is a property of the arc walk and not of the input.
        for ((i, x) in listOf(0f, 5f, 15f, 40f, 45f, 90f, 100f).withIndex()) {
            cr.add(x, 300f, 0.5f, i * 3.1f)
        }
        cr.end()
        assertTrue(r.count > 40, "only ${r.count} dabs over 100 px at 2 px spacing")
        for (i in r.ys.indices) {
            assertEquals(300f, r.ys[i], 1e-3f, "dab $i left the line")
        }
        for (i in 1 until r.count) {
            val d = dist(i - 1, i, r)
            assertEquals(2f, d, 0.02f, "gap $i was $d, not the requested 2")
        }
    }

    /**
     * The spacing debt has to cross the segment boundary. If it were reset per
     * segment there would be a dab at every sample regardless of spacing, and
     * dab density would follow hand speed — which is the one thing the arc
     * walk exists to remove.
     */
    @Test
    fun `spacing is carried across segment boundaries`() {
        val r = Recorder(spacing = 7f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        // Samples 10 px apart, dabs 7 px apart: any per-segment reset shows up
        // as a short gap at every multiple of 10.
        for (i in 0..20) cr.add(i * 10f, 0f, 0.5f, i * 3.1f)
        cr.end()
        for (i in 2 until r.count) {
            val d = dist(i - 1, i, r)
            assertEquals(7f, d, 0.05f, "gap $i was $d; the debt was reset at a boundary")
        }
    }

    /**
     * Centripetal against uniform, on the case that separates them: a long
     * approach, a short segment, and then a hard turn. The uniform tangent at
     * the short segment's end is scaled by the *neighbours'* spacing, so it
     * swings the curve off the path — the hooked tail people describe as the
     * line whipping.
     */
    @Test
    fun `a speed change does not hook the line, and uniform would`() {
        val r = Recorder(spacing = 0.5f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        cr.add(100f, 0f, 0.5f, 3f)
        cr.add(105f, 0f, 0.5f, 6f)
        cr.add(105f, 100f, 0.5f, 9f)
        // Only the 100 -> 105 segment has been flushed at this point.
        val onSegment = r.ys.indices.filter { r.xs[it] in 99f..106f }
        assertTrue(onSegment.isNotEmpty())
        val worst = onSegment.maxOf { abs(r.ys[it]) }
        assertTrue(worst < 1f, "centripetal wandered $worst px off a straight segment")

        // The control: the same four points through a uniform Catmull-Rom,
        // written out here so the comparison is against real arithmetic.
        fun uniformY(u: Float): Float {
            val y0 = 0f; val y1 = 0f; val y2 = 0f; val y3 = 100f
            return 0.5f * (
                2f * y1 +
                    (-y0 + y2) * u +
                    (2f * y0 - 5f * y1 + 4f * y2 - y3) * u * u +
                    (-y0 + 3f * y1 - 3f * y2 + y3) * u * u * u
                )
        }
        val uniformWorst = (0..100).maxOf { abs(uniformY(it / 100f)) }
        assertTrue(
            uniformWorst > 5f,
            "the uniform control did not overshoot ($uniformWorst); the test proves nothing",
        )
        assertTrue(
            worst < uniformWorst / 5f,
            "centripetal ($worst) was not decisively better than uniform ($uniformWorst)",
        )
    }

    @Test
    fun `a dwelling pen does not pile up dabs`() {
        val r = Recorder(spacing = 1f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(50f, 50f, 0.5f, 0f)
        repeat(200) { cr.add(50f, 50f, 0.5f, (it + 1) * 3.1f) }
        cr.end()
        assertEquals(1, r.count, "a stationary pen emitted ${r.count} dabs")
        assertTrue(r.xs.all { it.isFinite() } && r.ys.all { it.isFinite() })
    }

    @Test
    fun `duplicate points inside a moving stroke stay finite`() {
        val r = Recorder(spacing = 1f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        val xs = listOf(0f, 10f, 10f, 10f, 20f, 20f, 40f, 40f, 40f, 80f)
        for ((i, x) in xs.withIndex()) cr.add(x, 0f, 0.5f, i * 3.1f)
        cr.end()
        assertTrue(r.count > 50, "only ${r.count} dabs over 80 px at 1 px spacing")
        for (i in 0 until r.count) {
            assertTrue(r.xs[i].isFinite() && r.ys[i].isFinite(), "dab $i was (${r.xs[i]}, ${r.ys[i]})")
            assertTrue(r.xs[i] >= -1f && r.xs[i] <= 81f, "dab $i escaped the path at x=${r.xs[i]}")
            assertTrue(abs(r.ys[i]) < 0.5f, "dab $i left the line at y=${r.ys[i]}")
        }
    }

    @Test
    fun `a tap is one dab`() {
        val r = Recorder()
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(7f, 9f, 0.3f, 0f)
        cr.end()
        assertEquals(1, r.count)
    }

    @Test
    fun `two points still produce the segment between them`() {
        val r = Recorder(spacing = 1f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        cr.add(20f, 0f, 0.5f, 3f)
        assertEquals(1, r.count, "emitted before end() with only two points")
        cr.end()
        assertTrue(r.count >= 19, "a 20 px segment at 1 px gave ${r.count} dabs")
        assertTrue(abs(r.xs.max() - 20f) <= 1f)
        assertTrue(r.ys.all { abs(it) < 1e-4f }, "a two-point stroke should be straight")
    }

    @Test
    fun `interpolated pressure and time stay inside the endpoints`() {
        val r = Recorder(spacing = 0.5f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        // Pressure swinging hard between samples: a spline fit through these
        // would undershoot below zero, and a negative pressure is a negative
        // radius by the time RoundPen is done with it.
        val ps = listOf(0.9f, 0.02f, 0.95f, 0.01f, 0.99f, 0.02f)
        for ((i, p) in ps.withIndex()) cr.add(i * 20f, 0f, p, i * 3.1f)
        cr.end()
        val lo = ps.min()
        val hi = ps.max()
        for (i in 0 until r.count) {
            assertTrue(r.ps[i] in lo..hi, "dab $i pressure ${r.ps[i]} escaped $lo..$hi")
        }
        for (i in 1 until r.count) {
            assertTrue(r.ts[i] >= r.ts[i - 1] - 1e-4f, "time went backwards at dab $i")
        }
    }

    @Test
    fun `a non positive spacing fails loudly instead of hanging`() {
        val zero = object : DabEmitter {
            override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float) = 0f
        }
        val cr = CatmullRomResampler(zero)
        cr.begin()
        assertFailsWith<IllegalStateException> { cr.add(0f, 0f, 0.5f, 0f) }

        val nan = object : DabEmitter {
            override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float) = Float.NaN
        }
        val cr2 = CatmullRomResampler(nan)
        cr2.begin()
        assertFailsWith<IllegalStateException> { cr2.add(0f, 0f, 0.5f, 0f) }
    }

    @Test
    fun `add after end is refused`() {
        val cr = CatmullRomResampler(Recorder())
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        cr.end()
        assertFailsWith<IllegalStateException> { cr.add(1f, 1f, 0.5f, 3f) }
    }

    @Test
    fun `begin drops the previous stroke entirely`() {
        val r = Recorder(spacing = 1f)
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.add(0f, 0f, 0.5f, 0f)
        cr.add(50f, 0f, 0.5f, 3f)
        cr.add(100f, 0f, 0.5f, 6f)
        cr.end()
        val firstCount = r.count
        cr.begin()
        cr.add(1000f, 1000f, 0.5f, 0f)
        cr.end()
        assertEquals(firstCount + 1, r.count, "the second stroke inherited state from the first")
        assertEquals(1000f, r.xs.last())
        assertEquals(1000f, r.ys.last())
    }

    @Test
    fun `end is idempotent and a stroke that never began produces nothing`() {
        val r = Recorder()
        val cr = CatmullRomResampler(r)
        cr.begin()
        cr.end()
        assertEquals(0, r.count)
        cr.end()
        assertEquals(0, r.count)
    }
}
