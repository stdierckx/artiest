package be.thalos.artiest.engine.guide

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeCorpus
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ik15: which ray the hand meant.
 *
 * `docs/guides-plan.md` calls item 18 *"the part that decides whether
 * perspective feels usable"*, so most of these are about the choice rather than
 * about the projection. The two that matter most are the one that proves the
 * predicted tail cannot make the decision, and the one that proves the same
 * stroke decides the same way on a replay — without which a drawing would
 * quietly change the first time anything repainted it.
 */
class PerspectiveGuideTest {

    private val out = FloatArray(2)
    private val dt = StrokeCorpus.DT_NANOS

    /** The classic two-point set: a horizon at y = 0, points far left and right. */
    private fun twoPoint() = PerspectiveGuide(floatArrayOf(-4000f, 0f, 4000f, 0f), 2)

    /** Walk a stroke from [ox], [oy] in direction ([dx], [dy]) for [n] steps. */
    private fun walk(g: PerspectiveGuide, ox: Float, oy: Float, dx: Float, dy: Float, n: Int = 20) {
        g.begin(ox, oy)
        for (i in 1..n) g.advance(ox + dx * i, oy + dy * i)
    }

    /** How far off the line from [ox],[oy] to the point at [ray] the answer is. */
    private fun offRay(g: PerspectiveGuide, ray: Int, ox: Float, oy: Float): Float {
        val rx = g.xAt(ray) - ox
        val ry = g.yAt(ray) - oy
        val len = hypot(rx, ry)
        return abs((out[0] - ox) * ry - (out[1] - oy) * rx) / len
    }

    // ---- the choice --------------------------------------------------------

    @Test
    fun `nothing snaps until the hand has said which way it is going`() {
        val g = twoPoint()
        g.begin(0f, 500f)
        assertFalse(g.project(2f, 500f, out), "two pixels in, there is no direction yet")
        g.advance(2f, 500f)
        assertFalse(g.project(4f, 500f, out))
        g.advance(7f, 500f)
        assertFalse(g.project(7f, 500f, out), "still inside the deciding distance")
        g.advance(20f, 500f)
        assertTrue(g.project(40f, 520f, out), "and now it has one")
    }

    @Test
    fun `a stroke going right takes the right-hand point`() {
        val g = twoPoint()
        walk(g, 0f, 500f, 3f, -0.2f)
        assertTrue(g.project(600f, 800f, out))
        assertTrue(offRay(g, 1, 0f, 500f) < 0.01f, "not the right-hand ray")
    }

    @Test
    fun `a stroke going left takes the left-hand point`() {
        val g = twoPoint()
        walk(g, 0f, 500f, -3f, -0.2f)
        assertTrue(g.project(-600f, 800f, out))
        assertTrue(offRay(g, 0, 0f, 500f) < 0.01f, "not the left-hand ray")
    }

    @Test
    fun `drawing away from a point is as much along its ray as drawing toward it`() {
        // The score is the *absolute* cosine, because a ray is a line and not
        // an arrow. A signed test would send every receding line to the wrong
        // point, which is most of the lines in a perspective drawing.
        val g = twoPoint()
        // Start to the right of the right-hand point, moving further right:
        // away from it, but exactly along its ray.
        walk(g, 5000f, 0f, 3f, 0f)
        assertTrue(g.project(6000f, 90f, out))
        assertEquals(0f, out[1], 0.01f, "it should be on the horizon ray")
    }

    @Test
    fun `the third point is chosen for a stroke going up`() {
        // Three-point: two on the horizon and one high above, which is what a
        // building seen from the street needs.
        val g = PerspectiveGuide(floatArrayOf(-4000f, 0f, 4000f, 0f, 200f, -6000f), 3)
        walk(g, 200f, 900f, 0f, -3f)
        assertTrue(g.project(260f, 300f, out))
        assertTrue(offRay(g, 2, 200f, 900f) < 0.01f, "not the upward ray")
    }

    @Test
    fun `a tie is broken toward the point the hand is heading for`() {
        // The textbook case, not a corner one: two points placed symmetrically
        // on a horizon and a stroke drawn horizontally from midway between
        // them score identically against both. A first-wins rule would send it
        // to the left-hand point, which is not what anybody means.
        val g = twoPoint()
        walk(g, 0f, 500f, 3f, 0f)
        assertTrue(g.project(600f, 800f, out))
        assertTrue(offRay(g, 1, 0f, 500f) < 0.01f, "a horizontal stroke going right")

        val back = twoPoint()
        walk(back, 0f, 500f, -3f, 0f)
        assertTrue(back.project(-600f, 800f, out))
        assertTrue(offRay(back, 0, 0f, 500f) < 0.01f, "and one going left")
    }

    @Test
    fun `once it has chosen it does not change its mind`() {
        // A stroke that re-decided per sample would swap rays in the middle and
        // put a corner in the line.
        val g = twoPoint()
        walk(g, 0f, 500f, 3f, 0f)
        assertTrue(g.project(600f, 500f, out))
        // Now drag the hand hard the other way. The ray is already chosen.
        for (i in 1..200) g.advance(-i * 5f, 500f)
        assertTrue(g.project(-600f, 900f, out))
        assertTrue(offRay(g, 1, 0f, 500f) < 0.01f, "it changed its mind")
    }

    @Test
    fun `a lock snaps from the very first sample`() {
        val g = PerspectiveGuide(floatArrayOf(-4000f, 0f, 4000f, 0f), 2, lockedRay = 0)
        g.begin(0f, 500f)
        // No `advance` at all, and no deciding distance: a locked set needs no
        // direction, so there is no unguided head.
        assertTrue(g.project(100f, 520f, out))
        assertTrue(offRay(g, 0, 0f, 500f) < 0.01f)
    }

    @Test
    fun `a lock that names a point this set does not have is ignored`() {
        val g = PerspectiveGuide(floatArrayOf(-4000f, 0f, 4000f, 0f), 2, lockedRay = 7)
        g.begin(0f, 500f)
        assertFalse(g.project(100f, 520f, out), "it falls back to choosing")
        g.advance(60f, 500f)
        assertTrue(g.project(100f, 520f, out))
    }

    @Test
    fun `a pen-down on the only vanishing point there is snaps to nothing`() {
        // A question with no answer gets no answer, rather than a ray picked
        // out of the rounding.
        val g = PerspectiveGuide(floatArrayOf(500f, 500f), 1)
        walk(g, 500f, 500f, 0.0001f, 0f, n = 4)
        assertFalse(g.project(600f, 600f, out))
    }

    // ---- the projection ----------------------------------------------------

    @Test
    fun `the ray is a line and does not stop at the point`() {
        // A line stopping dead at a vanishing point would be a line with a full
        // stop in the middle of the page.
        val g = PerspectiveGuide(floatArrayOf(0f, 0f), 1)
        walk(g, 100f, 100f, -3f, -3f)
        assertTrue(g.project(-500f, -520f, out), "past the point")
        assertEquals(out[0], out[1], 0.01f, "still on the 45 degree ray")
    }

    @Test
    fun `a point pushed far away gives rays that are parallel for all practical purposes`() {
        // `docs/guides-plan.md` item 20, and there is no code for it: the
        // arithmetic degenerates on its own.
        val far = PerspectiveGuide(floatArrayOf(1e6f, 0f), 1)
        val a = FloatArray(2)
        far.begin(0f, 0f)
        far.advance(40f, 0f)
        far.project(500f, 300f, a)
        val b = PerspectiveGuide(floatArrayOf(1e6f, 0f), 1)
        b.begin(0f, 800f)
        b.advance(40f, 800f)
        b.project(500f, 1100f, out)
        // Two strokes 800 pixels apart, and their rays differ in angle by less
        // than a thousandth of a degree.
        val angleA = kotlin.math.atan2(a[1] - 0f, a[0] - 0f)
        val angleB = kotlin.math.atan2(out[1] - 800f, out[0] - 0f)
        assertTrue(abs(angleA - angleB) < 1e-3f, "$angleA against $angleB")
    }

    @Test
    fun `a set of no points, or four, is refused`() {
        assertFailsWith<IllegalArgumentException> { PerspectiveGuide(FloatArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { PerspectiveGuide(FloatArray(8), 4) }
        assertFailsWith<IllegalArgumentException> { PerspectiveGuide(FloatArray(2), 2) }
    }

    @Test
    fun `it allocates nothing per sample`() {
        val g = twoPoint()
        walk(g, 0f, 500f, 3f, 0f)
        repeat(2000) { g.project(it.toFloat(), it * 0.7f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(200_000) {
            g.advance(it.toFloat(), 500f)
            g.project(it.toFloat(), it * 0.7f, out)
        }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    // ---- the two that are about the builder --------------------------------

    @Test
    fun `the predicted tail cannot choose the ray`() {
        // The reason `Guide.advance` exists. `snapPredicted` asks about points
        // the pen has not been to; if that could decide, a perspective guide
        // would pick its ray from a guess about the future -- and a guess that
        // is wrong puts the whole stroke on the wrong point.
        val pen = Brush()
        val b = StrokeBuilder(pen)
        val g = twoPoint()
        b.snap = Snap(g)
        b.begin(0xFF000000.toInt())
        b.add(0f, 500f, 0.8f, 0L)
        // A tail extrapolating hard to the left, before any real travel.
        repeat(30) { assertFalse(b.snapPredicted(-500f - it * 10f, 500f, out)) }
        // The real hand goes right, and that is what decides.
        var t = dt
        for (i in 1..30) {
            b.add(i * 4f, 500f, 0.8f, t)
            t += dt
        }
        val stroke = b.end()
        // Every dab past the deciding distance is on the ray to the right-hand
        // point, which slopes up toward the horizon -- so this is a real test
        // of *which* ray and not of "the stroke stayed horizontal".
        val rx = g.xAt(1) - 0f
        val ry = g.yAt(1) - 500f
        val len = hypot(rx, ry)
        var checked = 0
        for (i in 0 until stroke.dabCount) {
            if (stroke.x(i) < PerspectiveGuide.DECIDE_DOC * 2f) continue
            val off = abs((stroke.x(i) - 0f) * ry - (stroke.y(i) - 500f) * rx) / len
            assertTrue(off < 0.5f, "dab $i is $off off the right-hand ray")
            checked++
        }
        assertTrue(checked > 5, "only $checked dabs to check")
    }

    @Test
    fun `the same stroke decides the same way twice`() {
        // Without this a drawing would quietly change the first time anything
        // repainted it: a rebuild feeds the raw samples back through the same
        // hooks, so the decision has to come out the same.
        val pen = Brush().apply { stabilization = 0.8f }
        fun run(): FloatArray {
            val b = StrokeBuilder(pen)
            b.snap = Snap(twoPoint())
            b.begin(0xFF000000.toInt(), seed = 11)
            var t = 0L
            for (i in 0 until 80) {
                b.add(i * 5f, 500f + (i % 9) * 4f, 0.7f, t)
                t += dt
            }
            val s = b.end()
            return FloatArray(s.dabCount * 2) { k ->
                if (k % 2 == 0) s.x(k / 2) else s.y(k / 2)
            }
        }
        val first = run()
        val second = run()
        assertEquals(first.size, second.size)
        for (i in first.indices) assertEquals(first[i], second[i], 0f, "dab float $i")
    }
}
