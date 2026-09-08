package be.thalos.artiest.engine.input

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two claims worth testing here are the two the class exists for: that
 * `strength == 0` is *bit-exact* identity and not merely close, and that the
 * filter's response is a function of elapsed time rather than of sample count.
 *
 * Everything else is a guard around them. The rate-invariance test in
 * particular is written as a time-domain comparison — same wall clock, two
 * sample rates, same answer — because comparing the two alphas to each other
 * would pass for a per-sample lerp too, which is exactly the bug.
 */
class StabilizerTest {

    private val ms = 1_000_000L

    private fun sample(x: Float, y: Float, p: Float, tNanos: Long) = PenSample(
        x = x,
        y = y,
        pressure = p,
        tilt = 0f,
        orientation = 0f,
        distance = 0f,
        toolType = ToolType.STYLUS,
        buttonState = 0,
        eventTimeNanos = tNanos,
        source = PenSample.Source.CURRENT,
    )

    @Test
    fun `strength zero is bit exact identity over a long noisy run`() {
        val s = Stabilizer(0f)
        var seed = 12345
        repeat(2000) { i ->
            seed = seed * 1103515245 + 12345
            val x = (seed ushr 8).toFloat() * 1e-4f
            seed = seed * 1103515245 + 12345
            val y = (seed ushr 8).toFloat() * 1e-4f
            seed = seed * 1103515245 + 12345
            val p = (seed ushr 8 and 0xFFFF).toFloat() / 65535f
            s.push(x, y, p, i * 3L * ms)
            // toRawBits, not assertEquals on floats: "identity" here means the
            // same bit pattern, because the dab goldens compare exact floats.
            assertEquals(x.toRawBits(), s.x.toRawBits(), "x at $i")
            assertEquals(y.toRawBits(), s.y.toRawBits(), "y at $i")
            assertEquals(p.toRawBits(), s.pressure.toRawBits(), "pressure at $i")
        }
    }

    @Test
    fun `the first sample of a stroke passes through whatever the strength`() {
        for (strength in listOf(0f, 0.15f, 0.5f, 1f)) {
            val s = Stabilizer(strength)
            s.push(100f, 200f, 0.5f, 0L)
            assertEquals(100f.toRawBits(), s.x.toRawBits(), "strength $strength")
            assertEquals(200f.toRawBits(), s.y.toRawBits(), "strength $strength")
            assertEquals(0.5f.toRawBits(), s.pressure.toRawBits(), "strength $strength")
        }
    }

    @Test
    fun `reset makes the next sample a first sample again`() {
        val s = Stabilizer(0.5f)
        s.push(0f, 0f, 0f, 0L)
        s.push(1000f, 0f, 0f, 3 * ms)
        assertTrue(s.x < 1000f, "smoothing did nothing")
        s.reset()
        s.push(1000f, 0f, 0f, 6 * ms)
        assertEquals(1000f.toRawBits(), s.x.toRawBits(), "reset did not drop state")
    }

    /**
     * The whole argument for dt integration, as an assertion, and stated as
     * strongly as it can be: the discrete filter's output has to equal the
     * *continuous* step response `1 - exp(-t / tau)` evaluated at that
     * sample's own wall-clock time. Not "the two rates roughly agree" — that
     * is weaker, and it lets a per-sample lerp through whenever the response
     * has saturated, which after 40 ms at a 6 ms tau it has.
     *
     * Checked at both measured digitizer rates, at three strengths, and at
     * sample counts from one to twenty, because the identity is exact and a
     * tolerance that only holds at one point on the curve is not the claim.
     */
    @Test
    fun `the discrete filter is the continuous one sampled, at both digitizer rates`() {
        for (strength in listOf(0.15f, 0.5f, 1f)) {
            val tau = strength * Stabilizer.TAU_MAX_NANOS
            for (hz in listOf(246.85f, 321.75f)) {
                val dt = (1e9f / hz).toLong()
                for (n in listOf(1, 2, 5, 10, 20)) {
                    val s = Stabilizer(strength)
                    s.push(0f, 0f, 0f, 0L)
                    for (i in 1..n) s.push(1f, 0f, 0f, i * dt)
                    val continuous = 1f - exp(-(n * dt).toFloat() / tau)
                    assertTrue(
                        abs(s.x - continuous) < 1e-4f,
                        "strength $strength at $hz Hz, $n samples: got ${s.x}, want $continuous",
                    )
                }
            }
        }
    }

    /**
     * The negative control, and it has to be a sharp one or the test above
     * proves nothing. A per-sample lerp is given the alpha that makes it
     * *exactly right* at 246.85 Hz; the claim is that the same lerp is then
     * wrong at 321.75 Hz by an amount no tolerance would absorb.
     *
     * Measured at a 40 ms tau and five samples, deliberately, because that is
     * where the response is still climbing. At the default 6 ms tau both
     * curves have saturated within ten samples and agree to a few thousandths
     * — which is exactly how a rate-dependent filter passes a badly chosen
     * invariance test.
     */
    @Test
    fun `a per sample lerp matched at one rate is wrong at the other`() {
        val strength = 1f
        val tau = strength * Stabilizer.TAU_MAX_NANOS
        val matchedAlpha = Stabilizer.alphaAt(strength, 246.85f)
        val n = 5

        fun lerp(hz: Float): Pair<Float, Float> {
            val dt = (1e9f / hz).toLong()
            var out = 0f
            repeat(n) { out += matchedAlpha * (1f - out) }
            return out to (1f - exp(-(n * dt).toFloat() / tau))
        }

        val (slowOut, slowWant) = lerp(246.85f)
        assertTrue(abs(slowOut - slowWant) < 1e-4f, "the control was not matched at 246.85 Hz")
        val (fastOut, fastWant) = lerp(321.75f)
        assertTrue(
            abs(fastOut - fastWant) > 0.05f,
            "the lerp control tracked 321.75 Hz too ($fastOut vs $fastWant); " +
                "the invariance test above is not discriminating",
        )
    }

    @Test
    fun `a repeated timestamp holds the output instead of dividing by zero`() {
        val s = Stabilizer(0.5f)
        s.push(0f, 0f, 0f, 0L)
        s.push(100f, 0f, 0f, 4 * ms)
        val held = s.x
        s.push(500f, 0f, 0f, 4 * ms)
        assertEquals(held.toRawBits(), s.x.toRawBits(), "a zero dt moved the output")
        assertTrue(s.x.isFinite())
    }

    @Test
    fun `a backwards clock recovers on the following sample`() {
        val s = Stabilizer(0.5f)
        s.push(0f, 0f, 0f, 100 * ms)
        s.push(100f, 0f, 0f, 104 * ms)
        s.push(100f, 0f, 0f, 4 * ms) // clock jumped back; held
        s.push(100f, 0f, 0f, 8 * ms) // 4 ms later on the new clock, not 100 ms
        assertTrue(s.x.isFinite(), "went non-finite: ${s.x}")
        assertTrue(s.x in 0f..100f, "left the input range: ${s.x}")
    }

    @Test
    fun `a long gap converges to the input rather than lagging behind it`() {
        val s = Stabilizer(1f)
        s.push(0f, 0f, 0f, 0L)
        s.push(1000f, 0f, 0f, 1000 * ms)
        assertTrue(s.x > 999.9f, "one second of dt should saturate alpha, got ${s.x}")
    }

    @Test
    fun `output never leaves the interval its inputs span`() {
        val s = Stabilizer(0.4f)
        var t = 0L
        var seed = 99
        repeat(500) {
            seed = seed * 1103515245 + 12345
            val p = (seed ushr 8 and 0xFF).toFloat() / 255f
            t += 3 * ms
            s.push(0f, 0f, p, t)
            assertTrue(s.pressure in 0f..1f, "pressure escaped 0..1: ${s.pressure}")
        }
    }

    @Test
    fun `a fork carries the state and does not share it`() {
        val a = Stabilizer(0.3f)
        a.push(0f, 0f, 0f, 0L)
        a.push(100f, 50f, 0.5f, 4 * ms)
        val b = Stabilizer(0.3f)
        a.copyStateTo(b)
        assertEquals(a.x.toRawBits(), b.x.toRawBits())
        assertEquals(a.y.toRawBits(), b.y.toRawBits())
        assertEquals(a.pressure.toRawBits(), b.pressure.toRawBits())

        // The fork continues from the same place — which is only true if the
        // timestamp came across too. It is the field the plan's "three floats"
        // does not name and the one whose absence is silent.
        val beforeX = a.x
        b.push(200f, 50f, 0.5f, 8 * ms)
        a.push(200f, 50f, 0.5f, 8 * ms)
        assertEquals(a.x.toRawBits(), b.x.toRawBits(), "the fork stepped differently")

        // And the real filter is untouched by anything else the fork does.
        b.push(9999f, 0f, 0f, 12 * ms)
        assertTrue(a.x != b.x)
        assertTrue(a.x > beforeX)
    }

    @Test
    fun `forking into a differently tuned filter is refused`() {
        val a = Stabilizer(0.3f)
        assertFailsWith<IllegalArgumentException> { a.copyStateTo(Stabilizer(0.4f)) }
    }

    @Test
    fun `strength outside zero to one is refused`() {
        assertFailsWith<IllegalArgumentException> { Stabilizer(-0.01f) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(1.01f) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `the documented alphas are the ones the class computes`() {
        // The header's numbers, pinned. If TAU_MAX_NANOS moves, this is where
        // the comment is caught lying.
        assertEquals(0.491f, Stabilizer.alphaAt(0.15f, 246.85f), 0.001f)
        assertEquals(0.404f, Stabilizer.alphaAt(0.15f, 321.75f), 0.001f)
        assertEquals(1f, Stabilizer.alphaAt(0f, 321.75f))
        assertTrue(Stabilizer.rateInvarianceHolds(0.15f))
        assertTrue(Stabilizer.rateInvarianceHolds(0f))
    }

    @Test
    fun `push from a PenSample reads the same five fields`() {
        val a = Stabilizer(0.3f)
        val b = Stabilizer(0.3f)
        a.push(sample(0f, 0f, 0f, 0L))
        b.push(0f, 0f, 0f, 0L)
        a.push(sample(10f, 20f, 0.7f, 4 * ms))
        b.push(10f, 20f, 0.7f, 4 * ms)
        assertEquals(b.x.toRawBits(), a.x.toRawBits())
        assertEquals(b.y.toRawBits(), a.y.toRawBits())
        assertEquals(b.pressure.toRawBits(), a.pressure.toRawBits())
    }
}
