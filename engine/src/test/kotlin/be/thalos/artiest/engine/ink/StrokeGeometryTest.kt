package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where strokes cross, which is the arithmetic behind the one feature
 * `docs/inker-plan.md` says inkers use vector layers for: overshoot a junction
 * on purpose, then rub the overshoot back to it in one gesture.
 *
 * The cases that matter are the degenerate ones. A crossing exactly through a
 * polyline vertex is reported by both of the segments that share it, and
 * reporting it twice would cut a stroke twice a hair apart and leave a record
 * between them that draws nothing. A stroke "crosses" its own neighbouring
 * segments by construction, so a self-crossing test that did not skip them
 * would find a junction every two pixels.
 */
class StrokeGeometryTest {

    private val pen = Brush().apply { sizeMin = 3f; sizeMax = 8f }

    /** A polyline through the given document points, one sample each. */
    private fun line(vararg xy: Float): StrokePolyline {
        val n = xy.size / 2
        val samples = FloatArray(n * StrokeRecord.STRIDE)
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            samples[o] = xy[i * 2]
            samples[o + 1] = xy[i * 2 + 1]
            samples[o + 2] = 1f
            samples[o + 5] = i * 3.1f
        }
        return StrokePolyline.of(samples, n, pen)
    }

    /** A straight run of [n] samples, so the thinning keeps a known mapping. */
    private fun run(x0: Float, y0: Float, x1: Float, y1: Float, n: Int): StrokePolyline {
        val samples = FloatArray(n * StrokeRecord.STRIDE)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val o = i * StrokeRecord.STRIDE
            samples[o] = x0 + (x1 - x0) * t
            samples[o + 1] = y0 + (y1 - y0) * t
            samples[o + 2] = 1f
            samples[o + 5] = i * 3.1f
        }
        return StrokePolyline.of(samples, n, pen)
    }

    private fun crossingsOf(a: StrokePolyline, b: StrokePolyline): FloatArray {
        val out = FloatList()
        StrokeGeometry.crossings(a, b, out)
        return out.toArray()
    }

    // ------------------------------------------------------------- crossings

    @Test
    fun `a plain X crosses once, half way along`() {
        val across = run(0f, 50f, 100f, 50f, 101)
        val down = run(50f, 0f, 50f, 100f, 101)
        val cuts = crossingsOf(across, down)
        assertEquals(1, cuts.size, cuts.toList().toString())
        assertTrue(abs(cuts[0] - 50f) < 2f, "crossed at sample ${cuts[0]} of 100")
    }

    @Test
    fun `strokes that do not meet cross nowhere`() {
        assertEquals(0, crossingsOf(run(0f, 10f, 100f, 10f, 51), run(0f, 80f, 100f, 80f, 51)).size)
        // Near but not touching, and their rectangles overlap: the early-out
        // must not be doing the deciding.
        assertEquals(0, crossingsOf(run(0f, 10f, 50f, 10f, 26), run(60f, 0f, 60f, 40f, 21)).size)
    }

    @Test
    fun `a stroke that ends exactly on another is a junction`() {
        val across = run(0f, 50f, 100f, 50f, 101)
        val stub = run(50f, 0f, 50f, 50f, 51)
        assertEquals(1, crossingsOf(across, stub).size)
    }

    @Test
    fun `two strokes that run together cross nowhere`() {
        // Collinear and overlapping. There is no junction along a stretch where
        // two lines coincide, and picking one would cut at an arbitrary place.
        assertEquals(0, crossingsOf(run(0f, 10f, 100f, 10f, 51), run(20f, 10f, 80f, 10f, 31)).size)
    }

    @Test
    fun `a crossing through a vertex is reported once`() {
        // The bent line's vertex sits exactly on the straight one.
        val bent = line(0f, 0f, 50f, 50f, 100f, 0f)
        val across = run(0f, 50f, 100f, 50f, 101)
        val cuts = crossingsOf(across, bent)
        assertEquals(1, cuts.size, "the shared vertex was counted twice: ${cuts.toList()}")
    }

    @Test
    fun `a stroke crossed three times is cut three times`() {
        val across = run(0f, 50f, 300f, 50f, 301)
        val zigzag = line(50f, 0f, 100f, 100f, 150f, 0f, 200f, 100f)
        val cuts = crossingsOf(across, zigzag)
        assertEquals(3, cuts.size, cuts.toList().toString())
        assertTrue(cuts[0] < cuts[1] && cuts[1] < cuts[2], "not ascending: ${cuts.toList()}")
    }

    // -------------------------------------------------------- self crossings

    @Test
    fun `a loop crosses itself once`() {
        val loop = line(
            0f, 0f, 60f, 0f, 60f, 60f, 0f, 60f, 0f, 30f, 90f, 30f,
        )
        val out = FloatList()
        StrokeGeometry.selfCrossings(loop, out)
        assertEquals(1, out.count, out.toArray().toList().toString())
    }

    @Test
    fun `a straight stroke does not cross itself`() {
        val out = FloatList()
        StrokeGeometry.selfCrossings(run(0f, 0f, 200f, 0f, 101), out)
        assertEquals(0, out.count, "neighbouring segments were counted as junctions")
    }

    @Test
    fun `a curve that does not close does not cross itself`() {
        val out = FloatList()
        val arc = FloatArray(60 * StrokeRecord.STRIDE)
        for (i in 0 until 60) {
            val a = i / 59f * 3f
            val o = i * StrokeRecord.STRIDE
            arc[o] = 100f + 80f * Math.cos(a.toDouble()).toFloat()
            arc[o + 1] = 100f + 80f * Math.sin(a.toDouble()).toFloat()
            arc[o + 2] = 1f
            arc[o + 5] = i * 3.1f
        }
        StrokeGeometry.selfCrossings(StrokePolyline.of(arc, 60, pen), out)
        assertEquals(0, out.count)
    }

    // -------------------------------------------------------------- brackets

    @Test
    fun `a tap lands where it landed, in sample indices`() {
        val across = run(0f, 50f, 100f, 50f, 101)
        assertTrue(abs(StrokeGeometry.positionOf(across, 25f, 50f) - 25f) < 2f)
        assertTrue(abs(StrokeGeometry.positionOf(across, 25f, 62f) - 25f) < 2f, "off the line")
        // Past the end clamps to the end rather than extrapolating.
        assertTrue(StrokeGeometry.positionOf(across, 500f, 50f) >= 99f)
    }

    @Test
    fun `the bracket is the crossing either side of the tap`() {
        val across = run(0f, 50f, 300f, 50f, 301)
        val zigzag = line(50f, 0f, 100f, 100f, 150f, 0f, 200f, 100f)
        val cuts = FloatList()
        StrokeGeometry.crossings(across, zigzag, cuts)
        val out = FloatArray(2)

        // Between the first and second crossing.
        val at = StrokeGeometry.positionOf(across, 90f, 50f)
        assertTrue(StrokeGeometry.bracket(across, cuts, at, out))
        assertTrue(out[0] < at && out[1] > at, "${out.toList()} does not bracket $at")
        assertTrue(abs(out[0] - cuts[0]) < 0.01f && abs(out[1] - cuts[1]) < 0.01f)
    }

    /**
     * The commonest gesture the feature exists for: a tail sticking out past a
     * junction has a crossing on one side and the end of the stroke on the
     * other, and taking the whole tail is what the hand meant.
     */
    @Test
    fun `past the last crossing the bracket runs to the end of the stroke`() {
        val across = run(0f, 50f, 300f, 50f, 301)
        val down = run(100f, 0f, 100f, 100f, 101)
        val cuts = FloatList()
        StrokeGeometry.crossings(across, down, cuts)
        val out = FloatArray(2)
        val at = StrokeGeometry.positionOf(across, 280f, 50f)
        assertTrue(StrokeGeometry.bracket(across, cuts, at, out))
        assertTrue(abs(out[0] - cuts[0]) < 0.01f, "the near edge should be the junction")
        assertEquals(300f, out[1], 1f, "the far edge should be the end of the stroke")
    }

    @Test
    fun `with no crossings the bracket is the whole stroke`() {
        val across = run(0f, 50f, 100f, 50f, 101)
        val out = FloatArray(2)
        assertTrue(StrokeGeometry.bracket(across, FloatList(), 40f, out))
        assertEquals(0f, out[0])
        assertEquals(100f, out[1])
    }

    // ------------------------------------------------------------ the list

    @Test
    fun `the float list sorts and drops repeats`() {
        val l = FloatList(2)
        for (v in floatArrayOf(5f, 1f, 5.05f, 3f, 1f, 9f)) l.add(v)
        l.sortAscending()
        l.dropRepeats(StrokeGeometry.SAME_POINT)
        assertEquals(4, l.count)
        assertEquals(listOf(1f, 3f, 5f, 9f), l.toArray().toList())
        l.clear()
        assertTrue(l.isEmpty)
    }
}
