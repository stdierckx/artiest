package be.thalos.artiest.engine.guide

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ik14's French curve.
 *
 * The arithmetic is fifteen lines and the tests are mostly about the *ends*,
 * because that is where a curve guide differs from every other kind: a ruler is
 * infinite and an ellipse is closed, so neither of them has one.
 */
class CurveGuideTest {

    private val out = FloatArray(2)

    /** A quarter circle of radius 100, centred at the origin, in 33 points. */
    private fun arc(n: Int = 33): CurveGuide {
        val p = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = (PI / 2 * i / (n - 1)).toFloat()
            p[i * 2] = cos(t) * 100f
            p[i * 2 + 1] = sin(t) * 100f
        }
        return CurveGuide(p, n)
    }

    private fun line(): CurveGuide = CurveGuide(floatArrayOf(0f, 0f, 100f, 0f), 2)

    @Test
    fun `a two-point curve is a segment`() {
        val g = line()
        assertTrue(g.project(50f, 40f, out))
        assertEquals(50f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }

    @Test
    fun `past the end it clamps, which is what a French curve does`() {
        // The line follows the curve and stops where the curve stops. It reads
        // as a stop rather than a blob because the dab emitter spaces by arc
        // length: a point that has stopped moving lays no more dabs.
        val g = line()
        assertTrue(g.project(500f, 0f, out))
        assertEquals(100f, out[0], 1e-3f)
        assertTrue(g.project(-500f, 0f, out))
        assertEquals(0f, out[0], 1e-3f)
    }

    @Test
    fun `a traced arc is followed all the way round`() {
        val g = arc()
        for (i in 0 until 32) {
            val t = (PI / 2 * i / 31.0).toFloat()
            // A point well outside the arc, on the ray from the centre.
            assertTrue(g.project(cos(t) * 300f, sin(t) * 300f, out))
            // Within the chord error of a 33-point quarter circle, which is
            // about a tenth of a pixel at radius 100.
            assertEquals(100f, hypot(out[0], out[1]), 0.5f, "at $t")
        }
    }

    @Test
    fun `the nearest segment wins, not the first one that is close`() {
        // A curve that doubles back on itself: a hand does this all the time,
        // and a projection that took the first segment within some tolerance
        // would snap to the wrong limb.
        val g = CurveGuide(floatArrayOf(0f, 0f, 100f, 0f, 100f, 60f, 0f, 60f), 4)
        assertTrue(g.project(50f, 55f, out))
        assertEquals(60f, out[1], 1e-3f, "the far limb, which is 5 away and not 55")
    }

    @Test
    fun `a hand that stopped dead does not divide by nothing`() {
        // Two samples in the same place is a zero-length segment, and the
        // parametric clamp would divide by its squared length.
        val g = CurveGuide(floatArrayOf(0f, 0f, 0f, 0f, 100f, 0f), 3)
        assertTrue(g.project(50f, 20f, out))
        assertEquals(50f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }

    @Test
    fun `a curve needs two points`() {
        assertFailsWith<IllegalArgumentException> { CurveGuide(floatArrayOf(0f, 0f), 1) }
        assertFailsWith<IllegalArgumentException> { CurveGuide(floatArrayOf(0f, 0f), 2) }
        assertFailsWith<IllegalArgumentException> {
            CurveGuide(floatArrayOf(0f, Float.NaN, 1f, 1f), 2)
        }
    }

    @Test
    fun `the points are copied in`() {
        // `StrokeRecord`'s rule: the caller reuses its buffer, and a guide that
        // aliased it would change shape after it was handed over.
        val p = floatArrayOf(0f, 0f, 100f, 0f)
        val g = CurveGuide(p, 2)
        p[2] = -999f
        assertEquals(100f, g.xAt(1), 1e-3f)
    }

    @Test
    fun `it allocates nothing per sample`() {
        val g = arc(64)
        repeat(2000) { g.project(it.toFloat(), it * 0.7f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(100_000) { g.project(it.toFloat(), it * 0.7f, out) }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    @Test
    fun `a five hundred point curve is still affordable per sample`() {
        // The cap exists because `project` is O(n) per sample. At 512 points and
        // 321.75 Hz that is 165 000 segment tests a second, and this is the
        // measurement that says the number was chosen rather than guessed.
        val g = arc(CurveGuide.MAX_POINTS)
        repeat(20_000) { g.project(it.toFloat(), it * 0.3f, out) }
        val started = System.nanoTime()
        repeat(100_000) { g.project(it.toFloat(), it * 0.3f, out) }
        val perCall = (System.nanoTime() - started) / 100_000.0
        // Ten microseconds is three times the whole per-sample budget at
        // 321.75 Hz, so this is a floor under a regression rather than a claim
        // about the machine it ran on.
        assertTrue(perCall < 10_000.0, "$perCall ns a sample")
    }
}
