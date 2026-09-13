package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The thing a tap is answered against.
 *
 * Two failures are worth more than the rest and both are quiet. A hit test that
 * is a *little* too tight selects nothing when the pen is on visible ink, which
 * is the complaint users actually file; and a simplification that drops the
 * last sample loses the taper, which is the end of every stroke and therefore
 * the part most often aimed at.
 */
class StrokePolylineTest {

    private val pen = Brush().apply {
        sizeMin = 2f
        sizeMax = 20f
    }

    @Test
    fun `a straight line thins to points two document pixels apart`() {
        val line = polylineOf(400) { i -> Triple(100f + i * 0.25f, 200f, 1f) }
        // 400 samples a quarter pixel apart is 100 px of path; at a two pixel
        // step that is about 51 points, not 400.
        assertTrue(line.pointCount in 45..55, "${line.pointCount} points")
        assertEquals(100f, line.x(0))
        assertEquals(200f, line.y(0))
        assertEquals(StrokePolyline.MIN_STEP_DOC, 2f)
    }

    @Test
    fun `the last sample is kept whatever the spacing`() {
        // 21 samples, the last one a tenth of a pixel past the previous kept
        // point: the distance filter alone would drop it.
        val line = polylineOf(21) { i ->
            val x = if (i == 20) 100f + 19 * 2f + 0.1f else 100f + i * 2f
            Triple(x, 50f, 1f)
        }
        val lastX = line.x(line.pointCount - 1)
        assertEquals(100f + 19 * 2f + 0.1f, lastX, 1e-3f)
    }

    @Test
    fun `a single sample is a dot with a half width`() {
        val dot = polylineOf(1) { Triple(10f, 10f, 1f) }
        assertEquals(1, dot.pointCount)
        assertEquals(20f, pen.sizeFor(1f, 0f))
        assertEquals(10f, dot.halfWidth(0))
        assertTrue(dot.hits(10f, 19f, 0f))
        assertFalse(dot.hits(10f, 40f, 0f))
    }

    @Test
    fun `no samples is a polyline that hits nothing`() {
        assertSame(StrokePolyline.EMPTY, StrokePolyline.of(FloatArray(0), 0, pen))
        assertFalse(StrokePolyline.EMPTY.hits(0f, 0f, 100f))
        assertTrue(StrokePolyline.EMPTY.bounds.isEmpty)
        assertEquals(Float.MAX_VALUE, StrokePolyline.EMPTY.distanceTo(0f, 0f))
    }

    // ------------------------------------------------------------ hit tests

    @Test
    fun `a point on the spine is inside the ink`() {
        val line = polylineOf(200) { i -> Triple(100f + i, 300f, 1f) }
        assertEquals(0f, line.distanceTo(150f, 300f))
        assertTrue(line.hits(150f, 300f, 0f))
    }

    @Test
    fun `a point just outside the band is outside by the amount it is outside`() {
        val line = polylineOf(200) { i -> Triple(100f + i, 300f, 1f) }
        // Half-width is 10 at full pressure, so 314 is four past the edge.
        assertEquals(4f, line.distanceTo(150f, 314f), 1e-3f)
        assertFalse(line.hits(150f, 314f, 1f))
        assertTrue(line.hits(150f, 314f, 5f))
    }

    @Test
    fun `the half width is interpolated along a taper rather than stepping`() {
        // Pressure falls from 1 to 0 over the length, so the band narrows.
        val n = 200
        val line = polylineOf(n) { i -> Triple(100f + i, 300f, 1f - i / (n - 1f)) }
        val near = line.distanceTo(120f, 305f)
        val far = line.distanceTo(280f, 305f)
        assertEquals(0f, near, "the thick end should swallow 5 px")
        assertTrue(far > 3f, "the thin end should not; it was $far")
    }

    @Test
    fun `a gap between two points is covered by the segment between them`() {
        // Two samples 100 px apart: a per-point test would answer 40 px in the
        // middle, and the correct answer is zero.
        val line = polylineOf(2) { i -> Triple(100f + i * 100f, 50f, 1f) }
        assertEquals(2, line.pointCount)
        assertEquals(0f, line.distanceTo(150f, 50f))
    }

    @Test
    fun `the bounds is the centreline inflated by its own half width`() {
        val line = polylineOf(100) { i -> Triple(100f + i, 300f, 1f) }
        assertEquals(90f, line.bounds.left, 1e-3f)
        assertEquals(290f, line.bounds.top, 1e-3f)
        assertEquals(209f, line.bounds.right, 1e-3f)
        assertEquals(310f, line.bounds.bottom, 1e-3f)
    }

    /**
     * The half-width is `sizeFor(pressure, elapsed)`, which is the size a
     * brush with no sensors paints. A brush that scatters paints outside that,
     * and a hit test that did not know would miss the ink the user is pointing
     * at — so the overshoot is added at the test rather than baked into the
     * stored band, and this is the assertion that it is added at all.
     */
    @Test
    fun `a scattering brush is tappable where its ink actually lands`() {
        val tight = Brush().apply { sizeMin = 2f; sizeMax = 20f }
        val loose = Brush().apply {
            sizeMin = 2f
            sizeMax = 20f
            scatter.min = 0f
            scatter.max = 12f
        }
        val samples = samplesOf(100) { i -> Triple(100f + i, 300f, 1f) }
        val a = StrokePolyline.of(samples, 100, tight)
        val b = StrokePolyline.of(samples, 100, loose)
        assertEquals(0f, a.maxOvershoot)
        assertEquals(12f, b.maxOvershoot)
        assertFalse(a.hits(150f, 318f, 0f))
        assertTrue(b.hits(150f, 318f, 0f))
    }

    // ------------------------------------------------------------- the cache

    @Test
    fun `a record builds its centreline once and drops it on request`() {
        val log = SampleLog()
        for (i in 0 until 300) log.add(100f + i, 200f, 1f, 0f, 0f, i * 3.1f)
        val record = StrokeRecord(
            id = 1, brush = 0, colorArgb = -1, erase = false, seed = 1, dabBase = 0,
            clip = StrokeRecord.NO_CLIP, bounds = Bounds.of(90f, 190f, 410f, 210f),
            packed = log.pack(), sampleCount = 300,
        )
        val first = record.polyline(pen)
        assertSame(first, record.polyline(pen))
        record.dropDerived()
        assertTrue(first !== record.polyline(pen))

        // A different brush is a different answer, not a stale one.
        val wide = Brush().apply { sizeMin = 40f; sizeMax = 60f }
        val other = record.polyline(wide)
        assertTrue(other.halfWidth(0) > first.halfWidth(0))
    }

    /**
     * `docs/inker-plan.md` budgets "a few hundred bytes per stroke" for this.
     * Ik0's longest stroke is 700 document pixels.
     */
    @Test
    fun `a long stroke costs a few hundred bytes of centreline`() {
        val line = polylineOf(700) { i -> Triple(100f + i, 300f, 1f) }
        // Four floats a point since Ik8 added the sample index, which is what
        // makes a cut expressible. 5.6 KB for the longest stroke in Ik0's
        // scene, against records that are already kilobytes.
        assertTrue(line.byteCount < 6000, "${line.byteCount} bytes for 700 px")
        assertTrue(abs(line.pointCount - 350) < 20, "${line.pointCount} points")
    }

    private fun samplesOf(n: Int, at: (Int) -> Triple<Float, Float, Float>): FloatArray {
        val out = FloatArray(n * StrokeRecord.STRIDE)
        for (i in 0 until n) {
            val (x, y, p) = at(i)
            val o = i * StrokeRecord.STRIDE
            out[o] = x
            out[o + 1] = y
            out[o + 2] = p
            out[o + 3] = 0f
            out[o + 4] = 0f
            out[o + 5] = i * 3.1f
        }
        return out
    }

    private fun polylineOf(n: Int, at: (Int) -> Triple<Float, Float, Float>): StrokePolyline =
        StrokePolyline.of(samplesOf(n, at), n, pen)
}
