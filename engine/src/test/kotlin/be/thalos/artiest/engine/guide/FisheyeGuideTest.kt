package be.thalos.artiest.engine.guide

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Five-point curvilinear perspective.
 *
 * The tests that carry the weight are the two about *exactness*: an arc really
 * does pass through both of its vanishing points and through the place the pen
 * landed, and a stroke started on a diameter really does come out straight. The
 * construction has no fitting in it, so those are equalities rather than
 * tolerances on a guess.
 */
class FisheyeGuideTest {

    private val out = FloatArray(2)

    /** A fisheye of radius 500 at the origin, cross aligned with the axes. */
    private fun eye() = FisheyeGuide(0f, 0f, 500f, 0f)

    private fun walk(g: FisheyeGuide, ox: Float, oy: Float, dx: Float, dy: Float, n: Int = 20) {
        g.begin(ox, oy)
        for (i in 1..n) g.advance(ox + dx * i, oy + dy * i)
    }

    // ---- the circle through three points -----------------------------------

    @Test
    fun `the circle through three points passes through all three`() {
        val c = FloatArray(3)
        assertTrue(FisheyeGuide.circleThrough(-500f, 0f, 500f, 0f, 0f, 300f, c))
        for (p in listOf(-500f to 0f, 500f to 0f, 0f to 300f)) {
            assertEquals(c[2], hypot(p.first - c[0], p.second - c[1]), 0.01f, "$p")
        }
    }

    @Test
    fun `three points on a line have no circle, which is the honest answer`() {
        val c = FloatArray(3)
        assertFalse(FisheyeGuide.circleThrough(-500f, 0f, 500f, 0f, 0f, 0f, c))
        assertFalse(FisheyeGuide.circleThrough(-500f, 0f, 500f, 0f, 100f, 0f, c))
        // And nearly on a line, at page scale, still has no useful one.
        assertFalse(FisheyeGuide.circleThrough(-500f, 0f, 500f, 0f, 0f, 1e-5f, c))
    }

    @Test
    fun `collinear means the same thing at every page size`() {
        // The determinant is scaled against the triangle, so a 64-pixel page
        // and a 3300-pixel one agree about what a straight line is.
        val c = FloatArray(3)
        for (scale in floatArrayOf(1f, 64f, 3300f)) {
            assertFalse(
                FisheyeGuide.circleThrough(-scale, 0f, scale, 0f, 0f, 0f, c),
                "at $scale",
            )
            assertTrue(
                FisheyeGuide.circleThrough(-scale, 0f, scale, 0f, 0f, scale * 0.3f, c),
                "at $scale",
            )
        }
    }

    // ---- the three families ------------------------------------------------

    @Test
    fun `a stroke away from the middle is straight through the middle`() {
        val g = eye()
        // Starting up and to the right of centre, moving further out.
        walk(g, 200f, 150f, 3f, 2.25f)
        assertTrue(g.project(600f, 450f, out))
        // On the line from the centre through the start, which is y = 0.75x.
        assertEquals(0.75f * out[0], out[1], 0.01f)
    }

    @Test
    fun `a horizontal stroke bows onto the arc through the side points`() {
        val g = eye()
        // Start well above the middle and move sideways: the horizontal family.
        walk(g, 0f, 300f, 3f, 0f)
        assertTrue(g.project(300f, 300f, out))
        // The arc goes through (-500, 0), (500, 0) and (0, 300), so it is the
        // circle centred at (0, -166.67) with radius 466.67. The answer is on
        // it, and it has *dropped* from y = 300, which is the bow.
        val c = FloatArray(3)
        assertTrue(FisheyeGuide.circleThrough(-500f, 0f, 500f, 0f, 0f, 300f, c))
        assertEquals(c[2], hypot(out[0] - c[0], out[1] - c[1]), 0.05f, "off its own arc")
        assertTrue(out[1] < 300f, "the arc bows back toward the side points: ${out[1]}")
    }

    @Test
    fun `a vertical stroke bows onto the arc through the top and bottom`() {
        val g = eye()
        walk(g, 300f, 0f, 0f, 3f)
        assertTrue(g.project(300f, 300f, out))
        val c = FloatArray(3)
        assertTrue(FisheyeGuide.circleThrough(0f, 500f, 0f, -500f, 300f, 0f, c))
        assertEquals(c[2], hypot(out[0] - c[0], out[1] - c[1]), 0.05f)
        assertTrue(out[0] < 300f, "it bows back toward the axis: ${out[0]}")
    }

    @Test
    fun `a stroke started on a diameter comes out straight`() {
        // The collinear case, and it is the right answer rather than a fallback
        // that had to be forgiven: a horizontal line through the middle of a
        // fisheye really is straight.
        val g = eye()
        walk(g, -200f, 0f, 3f, 0f)
        for (x in intArrayOf(-400, 0, 400, 900)) {
            assertTrue(g.project(x.toFloat(), 120f, out))
            assertEquals(0f, out[1], 0.01f, "at x=$x")
            assertEquals(x.toFloat(), out[0], 0.01f)
        }
    }

    @Test
    fun `nothing snaps until the hand has said which way it is going`() {
        val g = eye()
        g.begin(0f, 300f)
        assertFalse(g.project(4f, 300f, out))
        g.advance(4f, 300f)
        assertFalse(g.project(4f, 300f, out))
        g.advance(40f, 300f)
        assertTrue(g.project(100f, 300f, out))
    }

    @Test
    fun `once it has chosen a family it keeps it`() {
        val g = eye()
        walk(g, 0f, 300f, 3f, 0f)
        assertTrue(g.project(300f, 300f, out))
        val wasY = out[1]
        for (i in 1..200) g.advance(0f, 300f + i * 5f)
        assertTrue(g.project(300f, 300f, out))
        assertEquals(wasY, out[1], 0.01f, "it changed family")
    }

    @Test
    fun `the cross turns with the guide`() {
        val upright = FisheyeGuide(0f, 0f, 500f, 0f)
        val turned = FisheyeGuide(0f, 0f, 500f, (Math.PI / 2).toFloat())
        assertEquals(500f, upright.eastX(), 0.01f)
        assertEquals(0f, upright.eastY(), 0.01f)
        assertEquals(0f, turned.eastX(), 0.01f)
        assertEquals(500f, turned.eastY(), 0.01f)
    }

    @Test
    fun `a fisheye with no radius is refused`() {
        assertFailsWith<IllegalArgumentException> { FisheyeGuide(0f, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { FisheyeGuide(0f, 0f, -5f, 0f) }
        assertFailsWith<IllegalArgumentException> { FisheyeGuide(Float.NaN, 0f, 5f, 0f) }
    }

    @Test
    fun `a pen-down on the exact centre still finds a family`() {
        // The radial family has no direction there and is skipped; the other
        // two are the diameters, and both are straight lines through it.
        val g = eye()
        walk(g, 0f, 0f, 3f, 0f)
        assertTrue(g.project(400f, 90f, out))
        assertEquals(0f, out[1], 0.01f, "the horizontal diameter")
    }

    @Test
    fun `it allocates nothing per sample`() {
        val g = eye()
        walk(g, 0f, 300f, 3f, 0f)
        repeat(2000) { g.project(it.toFloat(), it * 0.7f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(200_000) {
            g.advance(it.toFloat(), 300f)
            g.project(it.toFloat(), it * 0.7f, out)
        }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    @Test
    fun `a snap over a fisheye pulls part of the way onto the arc`() {
        val g = eye()
        walk(g, 0f, 300f, 3f, 0f)
        val half = Snap(g, strength = 0.5f)
        val full = FloatArray(2)
        assertTrue(g.project(400f, 300f, full))
        assertTrue(half.apply(400f, 300f, out))
        assertEquals((300f + full[1]) * 0.5f, out[1], 0.05f)
        assertTrue(abs(out[1] - 300f) > 0.5f, "it did move")
    }
}
