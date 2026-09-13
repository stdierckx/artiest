package be.thalos.artiest.engine.guide

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeCorpus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ik12: the one line of the guide framework the *ink* has to know about.
 *
 * Two of these tests are the ones `docs/guides-plan.md` names as traps, and
 * both are about *where* the snap happens rather than what it computes.
 *
 * **After the smoothing.** Snapping before it puts the stabilizer's lag across
 * the guide, so the line drifts off the ruler and creeps back — it looks like
 * the ruler is loose. The test is a stroke drawn with heavy stabilisation.
 *
 * **Before the spline.** The resampler interpolates between the points it is
 * given, so snapping its *output* would move the knots and leave the curve
 * between them bulging off the guide. The test asserts that every *dab* is on
 * the line, not only every sample, on a stroke with a dozen dabs between
 * samples.
 */
class SnapStageTest {

    private val dt = StrokeCorpus.DT_NANOS

    private fun builder(pen: Brush = Brush()) = StrokeBuilder(pen)

    /** A wandering stroke roughly along a line, fed sample by sample. */
    private fun wander(b: StrokeBuilder, n: Int = 200, amplitude: Float = 30f): Stroke {
        b.begin(0xFF000000.toInt(), seed = 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            b.add(200f + t * 600f, 400f + amplitude * sin(t * 9f), 0.8f, i * dt)
        }
        return b.end()
    }

    /** How far the worst dab is from the line y = 400. */
    private fun worstOff(s: Stroke, line: Float = 400f): Float {
        var worst = 0f
        for (i in 0 until s.dabCount) worst = maxOf(worst, abs(s.y(i) - line))
        return worst
    }

    // ------------------------------------------------------------ the line

    @Test
    fun `a stroke drawn against a ruler lands on the ruler`() {
        val b = builder()
        b.snap = Snap(LineGuide(0f, 400f, 0f))
        val s = wander(b)
        assertTrue(s.dabCount > 100, "only ${s.dabCount} dabs")
        assertTrue(worstOff(s) < 0.2f, "the worst dab was ${worstOff(s)} px off the ruler")
    }

    @Test
    fun `no guide changes nothing`() {
        val a = wander(builder())
        val two = wander(builder().also { it.snap = null })
        assertEquals(a.dabCount, two.dabCount)
        for (i in 0 until a.dabCount) {
            assertEquals(a.x(i), two.x(i))
            assertEquals(a.y(i), two.y(i))
        }
    }

    @Test
    fun `a strength of zero changes nothing`() {
        val a = wander(builder())
        val two = wander(builder().also { it.snap = Snap(LineGuide(0f, 400f, 0f), strength = 0f) })
        assertEquals(a.dabCount, two.dabCount)
        for (i in 0 until a.dabCount) assertEquals(a.y(i), two.y(i))
    }

    @Test
    fun `half strength goes half way`() {
        val s = wander(builder().also { it.snap = Snap(LineGuide(0f, 400f, 0f), strength = 0.5f) })
        val free = wander(builder())
        assertTrue(worstOff(s) > 10f, "it snapped hard at half strength: ${worstOff(s)}")
        assertTrue(worstOff(s) < worstOff(free) * 0.6f, "${worstOff(s)} vs ${worstOff(free)}")
    }

    @Test
    fun `a diagonal ruler works like a flat one`() {
        val b = builder()
        val angle = (PI / 5).toFloat()
        b.snap = Snap(LineGuide(200f, 400f, angle))
        b.begin(0xFF000000.toInt(), seed = 1)
        for (i in 0 until 150) {
            val t = i / 149f
            b.add(200f + t * 500f, 400f + t * 100f + 25f * sin(t * 7f), 0.8f, i * dt)
        }
        val s = b.end()
        val nx = -sin(angle)
        val ny = cos(angle)
        var worst = 0f
        for (i in 0 until s.dabCount) {
            worst = maxOf(worst, abs((s.x(i) - 200f) * nx + (s.y(i) - 400f) * ny))
        }
        assertTrue(worst < 0.2f, "the worst dab was $worst px off a diagonal ruler")
    }

    // ----------------------------------------------------- where it happens

    @Test
    fun `the snap survives heavy stabilisation`() {
        val b = builder(Brush().apply { stabilization = 0.9f })
        b.snap = Snap(LineGuide(0f, 400f, 0f))
        val s = wander(b, amplitude = 60f)
        assertEquals(0.9f, b.smoothingStrength)
        assertTrue(worstOff(s) < 0.2f, "stabilisation pulled it ${worstOff(s)} px off the ruler")
    }

    @Test
    fun `every dab is on the line, not only every sample`() {
        val b = builder(Brush().apply { sizeMin = 2f; sizeMax = 4f })
        b.snap = Snap(LineGuide(0f, 400f, 0f))
        b.begin(0xFF000000.toInt(), seed = 2)
        for (i in 0 until 20) {
            b.add(200f + i * 40f, 400f + 40f * sin(i * 0.9f), 1f, i * dt * 12)
        }
        val s = b.end()
        assertTrue(s.dabCount > 200, "only ${s.dabCount} dabs for 20 samples")
        assertTrue(worstOff(s) < 0.2f, "a dab between samples was ${worstOff(s)} px off")
    }

    // --------------------------------------------------------- the falloff

    @Test
    fun `a reach means the pull dies away with distance`() {
        val out = FloatArray(2)
        val snap = Snap(LineGuide(0f, 400f, 0f), strength = 1f, reachDoc = 100f)
        assertTrue(snap.apply(300f, 400f, out))
        assertEquals(400f, out[1], 1e-3f)
        assertTrue(snap.apply(300f, 410f, out))
        assertTrue(abs(out[1] - 400f) < 1f, "at 10 px it moved to ${out[1]}")
        // Half way out: pulled half the way, which is smoothstep's midpoint.
        assertTrue(snap.apply(300f, 450f, out))
        assertEquals(425f, out[1], 1f)
        // Past the reach: not pulled at all. `out` is scratch either way — see
        // `Snap.apply` — so the answer is the boolean and nothing else.
        assertFalse(snap.apply(300f, 520f, out))
    }

    @Test
    fun `no reach means everywhere`() {
        val out = FloatArray(2)
        assertTrue(Snap(LineGuide(0f, 400f, 0f)).apply(300f, 5000f, out))
        assertEquals(400f, out[1], 1e-3f)
    }

    // --------------------------------------------------- the predicted tail

    /**
     * `docs/inker-plan.md`'s tripwire: *"a wet tail that wanders off the guide
     * and jumps back"*. The tail is the part of the stroke the eye is on, so it
     * is the most visible thing a guide can get wrong.
     */
    @Test
    fun `a predicted point goes through the same guide`() {
        val b = builder()
        val out = FloatArray(2)
        assertFalse(b.snapPredicted(500f, 470f, out), "no guide should mean no snap")
        b.snap = Snap(LineGuide(0f, 400f, 0f))
        assertTrue(b.snapPredicted(500f, 470f, out))
        assertEquals(500f, out[0], 1e-3f)
        assertEquals(400f, out[1], 1e-3f)
    }

    // ----------------------------------------------------------- the guide

    @Test
    fun `a line through two points is the line through them`() {
        val out = FloatArray(2)
        val g = LineGuide.through(100f, 100f, 200f, 200f)
        assertTrue(g.project(200f, 100f, out))
        assertEquals(150f, out[0], 1e-3f)
        assertEquals(150f, out[1], 1e-3f)
        assertFailsWith<IllegalArgumentException> { LineGuide.through(5f, 5f, 5f, 5f) }
    }

    @Test
    fun `a guide refuses values it cannot mean`() {
        assertFailsWith<IllegalArgumentException> { LineGuide(Float.NaN, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { LineGuide(0f, 0f, Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Snap(LineGuide(0f, 0f, 0f), strength = 2f) }
        assertFailsWith<IllegalArgumentException> { Snap(LineGuide(0f, 0f, 0f), reachDoc = -1f) }
    }

    /**
     * A guide that does not apply leaves the point alone. Ik15's rays are the
     * case that needs it: a stroke started nowhere near any ray has no ray to
     * snap to, and forcing one would drag it across the page.
     */
    @Test
    fun `a guide that does not apply leaves the ink where it was`() {
        val nowhere = object : Guide {
            override fun project(xDoc: Float, yDoc: Float, out: FloatArray) = false
        }
        val a = wander(builder())
        val two = wander(builder().also { it.snap = Snap(nowhere) })
        assertEquals(a.dabCount, two.dabCount)
        for (i in 0 until a.dabCount) assertEquals(a.y(i), two.y(i))
    }
}
