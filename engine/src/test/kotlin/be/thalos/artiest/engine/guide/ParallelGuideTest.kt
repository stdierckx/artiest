package be.thalos.artiest.engine.guide

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeCorpus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ik14's parallel ruler, and the one thing in the framework that is not a fixed
 * object on the page.
 *
 * The tests that matter are about *when the origin is taken*. A guide that
 * latched it at the wrong moment would still draw parallel lines — it would draw
 * them in the wrong place, and only sometimes, which is the shape of bug that
 * survives a demo.
 */
class ParallelGuideTest {

    private val out = FloatArray(2)
    private val dt = StrokeCorpus.DT_NANOS

    private fun horizontal() = ParallelGuide(0f)

    @Test
    fun `before a stroke starts it does not apply`() {
        // Rather than guessing at (0, 0), which would put a speed line through
        // the top-left corner of the page.
        assertFalse(horizontal().project(100f, 100f, out))
    }

    @Test
    fun `the line goes through wherever the stroke started`() {
        val g = horizontal()
        g.begin(400f, 250f)
        assertTrue(g.project(900f, 40f, out))
        assertEquals(900f, out[0], 1e-3f)
        assertEquals(250f, out[1], 1e-3f, "the y of the first sample, not of this one")
    }

    @Test
    fun `a second stroke gets a second line`() {
        val g = horizontal()
        g.begin(0f, 10f)
        assertTrue(g.project(50f, 90f, out))
        assertEquals(10f, out[1], 1e-3f)

        g.begin(0f, 700f)
        assertTrue(g.project(50f, 90f, out))
        assertEquals(700f, out[1], 1e-3f, "and the first stroke's line is gone")
    }

    @Test
    fun `the angle is the angle, whatever the hand does`() {
        val g = ParallelGuide((PI / 4).toFloat())
        g.begin(0f, 0f)
        // A point far off the 45 degree line through the origin lands on it.
        assertTrue(g.project(100f, 0f, out))
        assertEquals(50f, out[0], 1e-3f)
        assertEquals(50f, out[1], 1e-3f)
    }

    @Test
    fun `two points set the angle, which is how a hand sets it`() {
        val g = ParallelGuide.through(0f, 0f, 10f, 10f)
        assertEquals((PI / 4).toFloat(), g.angleRad, 1e-5f)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ParallelGuide.through(5f, 5f, 5f, 5f)
        }
    }

    // ---- the part that is about the builder --------------------------------

    @Test
    fun `a whole stroke comes out at the angle, through its own first point`() {
        val pen = Brush()
        val b = StrokeBuilder(pen)
        val g = ParallelGuide(0f)
        b.snap = Snap(g)
        b.begin(0xFF000000.toInt())
        // A wandering diagonal. Whatever it does, it has to come out flat.
        var t = 0L
        for (i in 0 until 120) {
            b.add(300f + i * 4f, 700f + i * 3f + (i % 7) * 5f, 0.8f, t)
            t += dt
        }
        val stroke = b.end()
        assertTrue(stroke.dabCount > 50, "${stroke.dabCount}")
        for (i in 0 until stroke.dabCount) {
            assertEquals(700f, stroke.y(i), 0.2f, "dab $i left the line")
        }
    }

    @Test
    fun `the origin is the raw first sample, so a replay lands in the same place`() {
        // The property the whole design rests on: the stabilizer has nothing to
        // smooth on the first sample, so the origin is identical live and on a
        // rebuild — and a stroke drawn against a parallel ruler therefore needs
        // nothing extra stored to re-render exactly.
        val pen = Brush().apply { stabilization = 0.9f }
        fun run(): FloatArray {
            val b = StrokeBuilder(pen)
            b.snap = Snap(ParallelGuide(0f))
            b.begin(0xFF000000.toInt(), seed = 7)
            var t = 0L
            for (i in 0 until 90) {
                b.add(100f + i * 6f, 500f + (i % 11) * 9f, 0.7f, t)
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

    @Test
    fun `a parallel ruler in a set is told about every stroke`() {
        // Not only about the strokes that happen to start beside it: which
        // guide is nearest is a question about a sample and this is a question
        // about a stroke. A ruler told only sometimes is a ruler that works
        // some of the time.
        val parallel = ParallelGuide(0f)
        val far = LineGuide(0f, 5000f, 0f)
        val set = NearestGuide(listOf(far, parallel))
        set.begin(10f, 20f)
        assertTrue(set.project(60f, 25f, out))
        assertEquals(20f, out[1], 1e-3f, "the parallel one, which is far the nearer")
    }

    @Test
    fun `it allocates nothing per sample`() {
        val g = ParallelGuide(0.4f)
        g.begin(0f, 0f)
        repeat(2000) { g.project(it.toFloat(), it * 0.7f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(200_000) { g.project(it.toFloat(), it * 0.7f, out) }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    @Test
    fun `a vertical parallel ruler is vertical`() {
        val g = ParallelGuide((PI / 2).toFloat())
        g.begin(640f, 0f)
        assertTrue(g.project(200f, 900f, out))
        assertEquals(640f, out[0], 1e-3f)
        assertEquals(900f, out[1], 1e-3f)
        assertTrue(abs(out[0] - 640f) < 1e-3f)
    }
}
