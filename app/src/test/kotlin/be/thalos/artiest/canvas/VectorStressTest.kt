package be.thalos.artiest.canvas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ik0's scene, checked for the properties the measurement rests on.
 *
 * A bench whose input is not what it claims produces a number that is worse
 * than no number, because it will be written into a plan and believed. Three
 * claims are worth pinning: the scene is the **same** scene on every run, it
 * arrives at the **rate the tablet reports at**, and it contains the pressure
 * ramps that are where the dabs actually come from.
 */
class VectorStressTest {

    @Test
    fun `the same seed is the same scene`() {
        val a = VectorStress.scene(20, 3300, 2160, seed = 7)
        val b = VectorStress.scene(20, 3300, 2160, seed = 7)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].count, b[i].count)
            assertTrue(a[i].samples.contentEquals(b[i].samples), "stroke $i differs")
        }
    }

    @Test
    fun `a different seed is a different scene`() {
        val a = VectorStress.scene(20, 3300, 2160, seed = 7)
        val b = VectorStress.scene(20, 3300, 2160, seed = 8)
        assertTrue(a.indices.any { !a[it].samples.contentEquals(b[it].samples) })
    }

    @Test
    fun `samples arrive at the rate the tablet reports at`() {
        val record = VectorStress.scene(1, 3300, 2160).single()
        val step = record.timeMillis(1) - record.timeMillis(0)
        assertEquals(1000f / VectorStress.SAMPLE_HZ, step, 1e-3f)
        // And the whole stroke is an inker's mark rather than a painter's
        // sweep: a third of a second to a second and a bit.
        val span = record.timeMillis(record.count - 1)
        assertTrue(span in 250f..1700f, "a stroke lasted $span ms")
    }

    /**
     * The onset and the taper are where a stroke's dabs come from — the radius
     * is smallest there, so the spacing is smallest and the dabs are densest.
     * A scene of flat-pressure strokes would price the rebuild at a fraction of
     * what it costs.
     */
    @Test
    fun `every stroke ramps in and tapers out`() {
        for (record in VectorStress.scene(30, 3300, 2160)) {
            val first = record.pressure(0)
            val last = record.pressure(record.count - 1)
            var peak = 0f
            for (i in 0 until record.count) peak = maxOf(peak, record.pressure(i))
            assertTrue(first < peak * 0.5f, "it started at full pressure")
            assertTrue(last < peak * 0.5f, "it ended at full pressure")
        }
    }

    @Test
    fun `pressure stays inside the range a digitizer reports`() {
        for (record in VectorStress.scene(30, 3300, 2160)) {
            for (i in 0 until record.count) {
                val p = record.pressure(i)
                assertTrue(p > 0f && p <= 1f, "pressure was $p")
            }
        }
    }

    /**
     * Strokes may run off the page — a hand does — but a scene that is mostly
     * off it would be measuring the clip rather than the ink.
     */
    @Test
    fun `the scene lands on the page`() {
        val w = 3300
        val h = 2160
        var on = 0
        var total = 0
        for (record in VectorStress.scene(100, w, h)) {
            for (i in 0 until record.count) {
                total++
                if (record.x(i) in 0f..w.toFloat() && record.y(i) in 0f..h.toFloat()) on++
            }
        }
        assertTrue(on > total * 0.9, "only $on of $total samples were on the page")
    }

    @Test
    fun `the packed form is the nine bytes the plan predicts`() {
        val scene = VectorStress.scene(10, 3300, 2160)
        val (raw, packed) = VectorStress.bytesOf(scene)
        assertEquals(VectorStress.samplesOf(scene) * 9, packed)
        assertEquals(VectorStress.samplesOf(scene) * 24, raw)
    }

    @Test
    fun `strokes spread over the page rather than clumping`() {
        val w = 3300
        val h = 2160
        val scene = VectorStress.scene(100, w, h)
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        for (r in scene) {
            val x = r.x(r.count / 2)
            val y = r.y(r.count / 2)
            if (x < w / 2f) left++ else right++
            if (y < h / 2f) top++ else bottom++
        }
        assertTrue(abs(left - right) < 30, "$left left against $right right")
        assertTrue(abs(top - bottom) < 30, "$top top against $bottom bottom")
    }
}
