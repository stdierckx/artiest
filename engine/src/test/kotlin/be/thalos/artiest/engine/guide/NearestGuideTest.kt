package be.thalos.artiest.engine.guide

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ik13: several guides on one page, and one question asked of a point.
 *
 * `StrokeBuilder` takes one `Snap`, and a page can carry a horizon, two
 * vanishing points and a ruler at once. The rule is nearest-projection, and
 * these tests are mostly about the edges of it: a guide that does not apply, a
 * point exactly on a crossing, and the two sizes that do not need the walk at
 * all.
 */
class NearestGuideTest {

    private val out = FloatArray(2)

    /** Horizontal through y, vertical through x. */
    private fun horizontal(y: Float) = LineGuide(0f, y, 0f)

    private fun vertical(x: Float) = LineGuide(x, 0f, (PI / 2).toFloat())

    /** A guide that never applies. Ik15's ray set is this case with arithmetic. */
    private object Absent : Guide {
        override fun project(xDoc: Float, yDoc: Float, out: FloatArray) = false
    }

    @Test
    fun `the nearer of two lines is the one that wins`() {
        val g = NearestGuide(listOf(horizontal(0f), horizontal(100f)))
        assertTrue(g.project(50f, 30f, out))
        assertEquals(0f, out[1], 1e-4f, "30 is nearer 0 than 100")

        assertTrue(g.project(50f, 70f, out))
        assertEquals(100f, out[1], 1e-4f, "and 70 is nearer 100")
    }

    @Test
    fun `a point on one guide is left exactly on it`() {
        val g = NearestGuide(listOf(horizontal(0f), vertical(400f)))
        assertTrue(g.project(120f, 0f, out))
        assertEquals(120f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
    }

    @Test
    fun `a guide that does not apply is skipped rather than answered`() {
        val g = NearestGuide(listOf(Absent, horizontal(60f)))
        assertTrue(g.project(10f, 10f, out))
        assertEquals(60f, out[1], 1e-4f)
    }

    @Test
    fun `when none of them applies the answer is false`() {
        val g = NearestGuide(listOf(Absent, Absent))
        assertFalse(g.project(10f, 10f, out))
    }

    @Test
    fun `a crossing is decided the same way every time`() {
        // Two guides through one point: whichever the pen is standing on, the
        // distance is zero for both. The tie-break is arbitrary and it has to
        // be *stable*, because a stroke drawn along the intersection would
        // otherwise flicker between the two.
        val first = horizontal(0f)
        val g = NearestGuide(listOf(first, vertical(0f)))
        repeat(8) {
            assertTrue(g.project(0f, 0f, out))
            assertEquals(0f, out[0], 1e-4f)
            assertEquals(0f, out[1], 1e-4f)
        }
        // And off the crossing it is the near one, not the first one.
        assertTrue(g.project(0.5f, 90f, out))
        assertEquals(0f, out[0], 1e-4f, "the vertical, which is half a pixel away")
    }

    @Test
    fun `one guide is handed back rather than wrapped`() {
        // Not a micro-optimisation for its own sake: a page with one ruler on
        // it is the common case, and wrapping it would put an array walk and a
        // scratch write on every one of 321.75 samples a second to choose
        // between one thing.
        val only = horizontal(0f)
        assertSame(only, NearestGuide.of(listOf(only)))
    }

    @Test
    fun `no guides is null and not an empty one`() {
        assertNull(NearestGuide.of(emptyList()))
    }

    @Test
    fun `several are composed`() {
        val g = NearestGuide.of(listOf(horizontal(0f), vertical(0f)))
        assertTrue(g is NearestGuide)
        assertEquals(2, g.size)
    }

    @Test
    fun `it allocates nothing per sample`() {
        // The scratch is a field for the reason Snap's out parameter is: this
        // runs once per digitizer sample at 321.75 Hz, plus once more for the
        // predicted tail, and an allocation here is an allocation inside the
        // window StrokeStats measures.
        val g = NearestGuide(listOf(horizontal(0f), vertical(400f), Absent))
        repeat(2000) { g.project(it.toFloat(), it * 0.5f, out) }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(200_000) { g.project(it.toFloat(), it * 0.5f, out) }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64 * 1024, "grew by ${after - before} bytes")
    }

    @Test
    fun `a snap over a composed guide moves toward whichever was nearest`() {
        val g = NearestGuide.of(listOf(horizontal(0f), horizontal(100f)))!!
        val half = Snap(g, strength = 0.5f)
        assertTrue(half.apply(10f, 20f, out))
        assertEquals(10f, out[1], 1e-4f, "halfway from 20 to 0")
    }
}
