package be.thalos.artiest.engine.brush

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseCurveTest {

    /**
     * The test W2's acceptance criterion rests on. `p.pow(3f)` and `p * p * p`
     * are not the same float for every p, and the dab goldens compare floats
     * exactly — so if [ResponseCurve.CUBIC] ever stops taking the multiply
     * path, the goldens move and this says why before the diff does.
     */
    @Test
    fun `the cubic is bit-identical to the multiply Brush used`() {
        var p = 0f
        while (p <= 1f) {
            assertEquals(p * p * p, ResponseCurve.CUBIC.evaluate(p), "p=$p")
            p += 1f / 512f
        }
        // And specifically not the pow path, on a value where they differ.
        val differs = (0..4096).map { it / 4096f }.firstOrNull { it * it * it != it.pow(3f) }
        if (differs != null) {
            assertEquals(differs * differs * differs, ResponseCurve.CUBIC.evaluate(differs))
        }
    }

    @Test
    fun `linear is the identity`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(p, ResponseCurve.LINEAR.evaluate(p))
        }
    }

    /**
     * The arithmetic that makes the plan's "three-point curve reproduces the
     * cubic" claim untrue, kept as a test so the amendment cannot be quietly
     * undone. A three-point fit is out by more than a doc pixel of dab width.
     */
    @Test
    fun `a three point curve does not reproduce the cubic`() {
        val three = ResponseCurve.of(0f to 0f, 0.5f to 0.125f, 1f to 1f)
        assertEquals(0.0625f, three.evaluate(0.25f), 1e-6f)
        assertEquals(0.015625f, ResponseCurve.CUBIC.evaluate(0.25f), 1e-6f)
        val sizeErrorPx = (three.evaluate(0.25f) - ResponseCurve.CUBIC.evaluate(0.25f)) * 22.5f
        assertTrue(sizeErrorPx > 1f, "the fit was out by ${sizeErrorPx}px, expected over 1")
    }

    @Test
    fun `a table interpolates between its points and holds outside them`() {
        val c = ResponseCurve.of(0.2f to 0.1f, 0.6f to 0.9f)
        assertEquals(0.1f, c.evaluate(0f), "below the first point holds")
        assertEquals(0.1f, c.evaluate(0.2f))
        assertEquals(0.5f, c.evaluate(0.4f), 1e-6f)
        assertEquals(0.9f, c.evaluate(0.6f))
        assertEquals(0.9f, c.evaluate(1f), "above the last point holds")
    }

    @Test
    fun `a single point curve is a constant`() {
        val c = ResponseCurve.of(0.5f to 0.3f)
        assertEquals(0.3f, c.evaluate(0f))
        assertEquals(0.3f, c.evaluate(0.5f))
        assertEquals(0.3f, c.evaluate(1f))
    }

    /**
     * The clamp `Brush.sizeFor` documents: a negative input became a
     * negative radius and reached MutableBounds as an inverted rectangle.
     */
    @Test
    fun `inputs outside the range and NaN are clamped rather than propagated`() {
        assertEquals(0f, ResponseCurve.CUBIC.evaluate(-1f))
        assertEquals(1f, ResponseCurve.CUBIC.evaluate(2f))
        assertEquals(0f, ResponseCurve.CUBIC.evaluate(Float.NaN))
        assertEquals(0f, ResponseCurve.LINEAR.evaluate(Float.NEGATIVE_INFINITY))
        assertEquals(1f, ResponseCurve.LINEAR.evaluate(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `FULL ignores its input`() {
        assertEquals(1f, ResponseCurve.FULL.evaluate(0f))
        assertEquals(1f, ResponseCurve.FULL.evaluate(1f))
    }

    @Test
    fun `an unsorted or ragged curve is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            ResponseCurve.of(0.5f to 0f, 0.2f to 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseCurve.ofPoints(floatArrayOf(0f, 1f), floatArrayOf(0f))
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseCurve.ofPoints(FloatArray(0), FloatArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseCurve.of(0f to 0f, Float.NaN to 1f)
        }
        assertFailsWith<IllegalArgumentException> { ResponseCurve.power(0f) }
        assertFailsWith<IllegalArgumentException> { ResponseCurve.power(-2f) }
    }

    @Test
    fun `power returns the shared instances for the two exponents the pen uses`() {
        assertTrue(ResponseCurve.power(1f) === ResponseCurve.LINEAR)
        assertTrue(ResponseCurve.power(3f) === ResponseCurve.CUBIC)
        assertFalse(ResponseCurve.power(2f).equals(ResponseCurve.CUBIC))
    }

    /** A curve is copied in, so a caller's array cannot mutate it afterwards. */
    @Test
    fun `control points are copied at construction`() {
        val xs = floatArrayOf(0f, 1f)
        val ys = floatArrayOf(0f, 1f)
        val c = ResponseCurve.ofPoints(xs, ys)
        ys[1] = 100f
        assertEquals(1f, c.evaluate(1f))
    }

    @Test
    fun `equality distinguishes the two forms`() {
        assertEquals(ResponseCurve.of(0f to 0f, 1f to 1f), ResponseCurve.of(0f to 0f, 1f to 1f))
        assertFalse(ResponseCurve.LINEAR == ResponseCurve.of(0f to 0f, 1f to 1f))
        assertEquals(ResponseCurve.power(2f), ResponseCurve.power(2f))
    }
}
