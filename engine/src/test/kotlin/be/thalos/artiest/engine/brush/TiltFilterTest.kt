package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TiltFilterTest {

    @Test
    fun `the first sample is taken whole`() {
        val f = TiltFilter()
        assertFalse(f.isStarted)
        f.update(0.5f, 1.2f, 3.1f)
        assertTrue(f.isStarted)
        assertEquals(0.5f, f.tiltRad, 1e-6f)
        assertEquals(1.2f, f.orientationRad, 1e-6f)
    }

    /**
     * The bug this class exists for. Orientation wraps at PI, and two readings
     * a hundredth of a radian apart across the wrap average, as plain numbers,
     * to zero — the pen apparently swinging a half turn. Filtered as a vector
     * it stays where it is.
     */
    @Test
    fun `orientation near the wrap does not swing half a turn`() {
        val f = TiltFilter()
        val a = 3.13f
        val b = -3.13f
        f.update(0f, a, 4f)
        repeat(20) { f.update(0f, b, 4f) }
        val o = f.orientationRad
        // Must be near +-PI, never near zero.
        assertTrue(abs(abs(o) - PI.toFloat()) < 0.05f, "orientation drifted to $o")
        val naive = (a + b) / 2f
        assertEquals(0f, naive, 1e-6f, "the naive average really is zero, which is the bug")
    }

    @Test
    fun `a held pose is converged to and then held`() {
        val f = TiltFilter(timeConstantMillis = 10f)
        f.update(0f, 0f, 4f)
        repeat(50) { f.update(0.8f, 0.7f, 4f) }
        assertEquals(0.8f, f.tiltRad, 1e-3f)
        assertEquals(0.7f, f.orientationRad, 1e-3f)
    }

    /**
     * Time-based and not sample-based, which is the whole reason the gap is a
     * parameter: this device samples at 246.85 Hz against a 60 Hz panel and
     * 321.75 Hz against 90 Hz. The same elapsed time must give the same
     * convergence however it is chopped up.
     */
    @Test
    fun `the same elapsed time converges the same however the samples are spaced`() {
        val coarse = TiltFilter(timeConstantMillis = 20f)
        val fine = TiltFilter(timeConstantMillis = 20f)
        coarse.update(0f, 0f, 4f)
        fine.update(0f, 0f, 4f)
        repeat(10) { coarse.update(1f, 0f, 4f) }   // 40 ms in 10 steps
        repeat(40) { fine.update(1f, 0f, 1f) }     // 40 ms in 40 steps
        assertEquals(coarse.tiltRad, fine.tiltRad, 2e-3f)
    }

    @Test
    fun `a zero or negative gap holds the state rather than dividing by it`() {
        val f = TiltFilter()
        f.update(0f, 0f, 4f)
        f.update(1f, 0f, 4f)
        val held = f.tiltRad
        f.update(1f, 0f, 0f)
        assertEquals(held, f.tiltRad)
        f.update(1f, 0f, -5f)
        assertEquals(held, f.tiltRad)
        f.update(1f, 0f, Float.NaN)
        assertEquals(held, f.tiltRad)
    }

    @Test
    fun `reset makes the next stroke snap rather than ease out of the last`() {
        val f = TiltFilter()
        repeat(20) { f.update(1.0f, 2.0f, 4f) }
        f.reset()
        assertFalse(f.isStarted)
        f.update(0.1f, -1.0f, 4f)
        assertEquals(0.1f, f.tiltRad, 1e-6f)
        assertEquals(-1.0f, f.orientationRad, 1e-6f)
    }

    @Test
    fun `a non finite reading is absorbed rather than poisoning the state`() {
        val f = TiltFilter()
        f.update(0.5f, 0.5f, 4f)
        f.update(Float.NaN, Float.NaN, 4f)
        assertTrue(f.tiltRad.isFinite(), "tilt became ${f.tiltRad}")
        assertTrue(f.orientationRad.isFinite(), "orientation became ${f.orientationRad}")
    }

    /**
     * The short way from -3.13 to +3.13 runs *backwards* through -PI, so the
     * delta is negative even though the target is the larger number. That is
     * the whole point of the function and it is easy to get backwards.
     */
    @Test
    fun `angleDelta takes the short way round`() {
        val gap = (2.0 * PI).toFloat() - 6.26f   // 0.0232
        assertEquals(-gap, TiltFilter.angleDelta(-3.13f, 3.13f), 1e-4f)
        assertEquals(gap, TiltFilter.angleDelta(3.13f, -3.13f), 1e-4f)
        assertEquals(1f, TiltFilter.angleDelta(0f, 1f), 1e-6f)
        assertEquals(0f, TiltFilter.angleDelta(2f, 2f), 1e-6f)
    }
}
