package be.thalos.artiest.engine.guide

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ik14's ellipse, and the only hard arithmetic in the guide framework.
 *
 * The tests that matter are not the round numbers. They are the two properties
 * that say the iteration actually found the *nearest* point rather than a
 * plausible one: the answer is **on** the ellipse, and nothing else on the
 * ellipse is nearer. The second is checked by brute force against a thousand
 * points, which is a slow way to be sure and a fast way to catch an iteration
 * that has quietly stopped converging.
 */
class EllipseGuideTest {

    private val out = FloatArray(2)

    private fun circle(r: Float) = EllipseGuide(0f, 0f, r, r, 0f)

    /** How far off the ellipse a point is, as a fraction. Zero is on it. */
    private fun onIt(g: EllipseGuide, x: Float, y: Float): Float {
        val vx = x - g.xDoc
        val vy = y - g.yDoc
        val ca = cos(g.angleRad)
        val sa = sin(g.angleRad)
        val lx = (vx * ca + vy * sa) / g.radiusX
        val ly = (-vx * sa + vy * ca) / g.radiusY
        return abs(lx * lx + ly * ly - 1f)
    }

    /** The nearest point on the ellipse, found by walking it. */
    private fun brute(g: EllipseGuide, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until 4000) {
            val t = (2.0 * PI * i / 4000.0).toFloat()
            val ex = g.radiusX * cos(t)
            val ey = g.radiusY * sin(t)
            val ca = cos(g.angleRad)
            val sa = sin(g.angleRad)
            val px = g.xDoc + ex * ca - ey * sa
            val py = g.yDoc + ex * sa + ey * ca
            best = minOf(best, hypot(px - x, py - y))
        }
        return best
    }

    @Test
    fun `a circle projects onto itself`() {
        val g = circle(100f)
        assertTrue(g.project(300f, 0f, out))
        assertEquals(100f, out[0], 1e-2f)
        assertEquals(0f, out[1], 1e-2f)

        assertTrue(g.project(0f, -20f, out), "and from inside")
        assertEquals(0f, out[0], 1e-2f)
        assertEquals(-100f, out[1], 1e-2f)
    }

    @Test
    fun `the answer is on the ellipse, wherever it is asked from`() {
        val g = EllipseGuide(120f, -40f, 200f, 60f, 0.7f)
        for (i in 0 until 64) {
            val a = (2.0 * PI * i / 64.0).toFloat()
            for (r in floatArrayOf(0.3f, 1f, 4f, 40f)) {
                val x = g.xDoc + cos(a) * 200f * r
                val y = g.yDoc + sin(a) * 200f * r
                assertTrue(g.project(x, y, out), "from ($x, $y)")
                assertTrue(onIt(g, out[0], out[1]) < 1e-3f, "off the ellipse from ($x, $y)")
            }
        }
    }

    @Test
    fun `nothing on the ellipse is nearer than the answer`() {
        // The property the iteration is *for*. Brute force, because a wrong
        // answer that is still on the ellipse is exactly what a converging-to-
        // the-wrong-root bug looks like and nothing cheaper would catch it.
        for (g in listOf(
            EllipseGuide(0f, 0f, 100f, 100f, 0f),
            EllipseGuide(0f, 0f, 300f, 40f, 0f),
            EllipseGuide(-50f, 90f, 40f, 300f, 1.1f),
            EllipseGuide(10f, 10f, 500f, 25f, -0.4f),
        )) {
            for (i in 0 until 48) {
                val a = (2.0 * PI * i / 48.0).toFloat()
                for (r in floatArrayOf(0.1f, 0.9f, 1.1f, 3f)) {
                    val x = g.xDoc + cos(a) * 260f * r
                    val y = g.yDoc + sin(a) * 260f * r
                    assertTrue(g.project(x, y, out))
                    val mine = hypot(out[0] - x, out[1] - y)
                    val best = brute(g, x, y)
                    assertTrue(
                        mine <= best + 0.5f,
                        "$g from ($x, $y): $mine against $best",
                    )
                }
            }
        }
    }

    @Test
    fun `a point on the long axis inside the evolute is the case that was wrong`() {
        // The defect this class was rewritten for. The obvious iteration steers
        // by a cross product that is exactly zero here, so it sat still and
        // answered the end of the axis: on a 300 by 40 ellipse the point
        // (26, 0) came back 274 pixels from the curve instead of 40.
        val g = EllipseGuide(0f, 0f, 300f, 40f, 0f)
        for (px in floatArrayOf(0.5f, 5f, 26f, 100f, 290f)) {
            assertTrue(g.project(px, 0f, out))
            val mine = hypot(out[0] - px, out[1])
            assertTrue(mine <= brute(g, px, 0f) + 0.5f, "at ($px, 0): $mine")
            assertTrue(onIt(g, out[0], out[1]) < 1e-3f, "off the ellipse at ($px, 0)")
        }
        // And past the cusp of the evolute, where the end of the axis really is
        // the answer.
        assertTrue(g.project(299f, 0f, out))
        assertEquals(300f, out[0], 1f)
    }

    @Test
    fun `just off an axis is the same answer as on it`() {
        // The general branch and the axis branch are different formulae, so the
        // seam between them is somewhere a jump could hide — and a jump here is
        // a kink in a stroke crossing the long axis of a wheel.
        val g = EllipseGuide(0f, 0f, 300f, 40f, 0f)
        val on = FloatArray(2)
        assertTrue(g.project(26f, 0f, on))
        for (eps in floatArrayOf(1e-5f, 1e-4f, 1e-3f, 1e-2f)) {
            assertTrue(g.project(26f, eps, out))
            assertTrue(
                hypot(out[0] - on[0], out[1] - on[1]) < 1f,
                "at eps=$eps the answer jumped to (${out[0]}, ${out[1]}) from (${on[0]}, ${on[1]})",
            )
        }
    }

    @Test
    fun `the ends of the long axis are where a flat ellipse is hardest`() {
        // The corners of the first quadrant, which is where folding the point
        // into it has to be undone correctly — and where an inker is looking,
        // because that is the tightest part of the curve.
        val g = EllipseGuide(0f, 0f, 400f, 30f, 0f)
        for (sign in intArrayOf(-1, 1)) {
            assertTrue(g.project(sign * 600f, 2f, out))
            assertEquals(sign * 400f, out[0], 1f)
            assertTrue(abs(out[1]) < 4f, "${out[1]}")
        }
    }

    @Test
    fun `a rotated ellipse is the same ellipse somewhere else`() {
        val flat = EllipseGuide(0f, 0f, 200f, 50f, 0f)
        val turned = EllipseGuide(0f, 0f, 200f, 50f, (PI / 2).toFloat())
        val a = FloatArray(2)
        assertTrue(flat.project(300f, 0f, a))
        assertTrue(turned.project(0f, 300f, out))
        assertEquals(a[0], out[1], 1e-2f, "the answer turns with it")
        assertEquals(a[1], -out[0], 1e-2f)
    }

    @Test
    fun `the centre has no nearest point`() {
        // Every point of the ellipse is equally near, so picking one would put
        // a kink in a stroke that happened to cross the middle. A hand crossing
        // the centre is drawing a diameter, not an arc.
        val g = EllipseGuide(50f, 50f, 100f, 60f, 0f)
        assertFalse(g.project(50f, 50f, out))
        assertTrue(g.project(50.5f, 50f, out), "and half a pixel off it is fine")
    }

    @Test
    fun `a degenerate ellipse is refused rather than drawn`() {
        for (bad in listOf(
            { EllipseGuide(0f, 0f, 0f, 10f, 0f) },
            { EllipseGuide(0f, 0f, 10f, 0f, 0f) },
            { EllipseGuide(0f, 0f, -10f, 10f, 0f) },
            { EllipseGuide(Float.NaN, 0f, 10f, 10f, 0f) },
        )) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { bad() }
        }
    }

    @Test
    fun `it allocates nothing per sample`() {
        val g = EllipseGuide(0f, 0f, 200f, 70f, 0.3f)
        repeat(2000) { g.project(it.toFloat(), it * 0.7f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(200_000) { g.project(it.toFloat(), it * 0.7f, out) }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    @Test
    fun `a snap over an ellipse pulls part of the way`() {
        val g = circle(100f)
        val half = Snap(g, strength = 0.5f)
        assertTrue(half.apply(200f, 0f, out))
        assertEquals(150f, out[0], 1e-2f, "halfway from 200 to 100")
    }

    @Test
    fun `a circle is what the arithmetic degenerates to, exactly`() {
        // The evolute of a circle is its own centre, so `ex` and `ey` are zero
        // and the iteration has nothing to correct. It is worth pinning,
        // because a divide that only misbehaves when the radii are equal is a
        // bug that never shows up on an ellipse.
        val g = circle(75f)
        for (i in 0 until 32) {
            val a = (2.0 * PI * i / 32.0).toFloat()
            val x = cos(a) * 500f
            val y = sin(a) * 500f
            assertTrue(g.project(x, y, out))
            assertEquals(75f, hypot(out[0], out[1]), 1e-2f)
            // And it is on the ray from the centre, which is what nearest means
            // for a circle.
            assertEquals(0f, out[0] * y - out[1] * x, 1e-1f * sqrt(500f))
        }
    }
}
