package be.thalos.artiest.project

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A clip that does not survive a save is a clip that does not work: the first
 * edit after reopening would re-render a stroke outside the stencil it was
 * drawn in, silently, long after.
 *
 * So the tests are not about the text. They are about **the region**: a path is
 * decoded and the two are scan-converted and compared pixel for pixel, because
 * what a clip is for is deciding which pixels ink lands on and nothing else
 * about it matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PathTextTest {

    private val page = Rect(0, 0, 600, 400)

    private fun regionOf(p: Path): Region = Region().also { it.setPath(p, Region(page)) }

    /** How many pixels of the page the two paths disagree about. */
    private fun disagreement(a: Path, b: Path): Int {
        val ra = regionOf(a)
        val rb = regionOf(b)
        val xor = Region()
        xor.op(ra, rb, Region.Op.XOR)
        var n = 0
        val it = android.graphics.RegionIterator(xor)
        val r = Rect()
        while (it.next(r)) n += r.width() * r.height()
        return n
    }

    private fun area(p: Path): Int {
        var n = 0
        val it = android.graphics.RegionIterator(regionOf(p))
        val r = Rect()
        while (it.next(r)) n += r.width() * r.height()
        return n
    }

    @Test
    fun `a rectangle comes back as the same region`() {
        val rect = Path().apply { addRect(100f, 50f, 300f, 200f, Path.Direction.CW) }
        val back = PathText.decode(PathText.encode(rect))!!
        assertEquals(0, disagreement(rect, back))
    }

    @Test
    fun `a circle comes back within a pixel of its edge`() {
        val circle = Path().apply { addCircle(300f, 200f, 120f, Path.Direction.CW) }
        val back = PathText.decode(PathText.encode(circle))!!
        val off = disagreement(circle, back)
        // The perimeter is about 754 px; a flattening within half a step can
        // only disagree along it, so anything above the perimeter means the
        // shape itself moved rather than its edge.
        assertTrue(off < 800, "$off pixels disagree, of ${area(circle)}")
        assertTrue(off.toDouble() / area(circle) < 0.02, "$off of ${area(circle)}")
    }

    /**
     * The one that would look like a rendering bug three features away: a
     * selection with a hole is two contours wound opposite ways, and a rebuild
     * that reversed one would fill the hole in.
     */
    @Test
    fun `a hole is still a hole`() {
        val donut = Path().apply {
            addRect(100f, 50f, 400f, 350f, Path.Direction.CW)
            addRect(180f, 130f, 320f, 270f, Path.Direction.CCW)
        }
        val back = PathText.decode(PathText.encode(donut))!!
        assertTrue(area(donut) == 300 * 300 - 140 * 140, "the fixture is wrong: ${area(donut)}")
        assertTrue(
            disagreement(donut, back) < 1400,
            "${disagreement(donut, back)} pixels disagree",
        )
        // And the hole is still a hole, asked of the region rather than of the
        // path: a path that had lost its winding would still "contain" the
        // point in the sense `Path` means.
        assertTrue(!regionOf(back).contains(250, 200), "the hole filled in")
        assertTrue(regionOf(back).contains(140, 90), "the ring itself went missing")
    }

    @Test
    fun `two separate islands both survive`() {
        val two = Path().apply {
            addRect(20f, 20f, 120f, 120f, Path.Direction.CW)
            addRect(400f, 250f, 550f, 380f, Path.Direction.CW)
        }
        val back = PathText.decode(PathText.encode(two))!!
        assertEquals(0, disagreement(two, back))
        assertTrue(PathText.encode(two).contains(';'), "two contours should be two groups")
    }

    @Test
    fun `an empty path is empty text and empty text is nothing`() {
        assertEquals("", PathText.encode(Path()))
        assertNull(PathText.decode(""))
        assertNull(PathText.decode("   "))
    }

    // ------------------------------------------------------------- refusals

    /**
     * A clip with a point missing is a clip of a slightly different shape,
     * which would confine ink somewhere *almost* right. That is worse than not
     * reading it at all, because the pixels on disk are still correct and a
     * sheet that declines to rebuild keeps them.
     */
    @Test
    fun `a malformed point refuses the whole path rather than skipping it`() {
        assertNull(PathText.decode("10.0,10.0 20.0,x 30.0,30.0"))
        assertNull(PathText.decode("10.0,10.0 20.0 30.0,30.0"))
        assertNull(PathText.decode("10.0,10.0 ,20.0 30.0,30.0"))
        assertNull(PathText.decode("10.0,10.0 20.0, 30.0,30.0"))
        assertNull(PathText.decode("10.0,10.0 20.0,NaN 30.0,30.0"))
        assertNull(PathText.decode("10.0,10.0 20.0,Infinity 30.0,30.0"))
    }

    @Test
    fun `a contour with fewer than three points encloses nothing and is dropped`() {
        assertNull(PathText.decode("10.0,10.0"))
        assertNull(PathText.decode("10.0,10.0 20.0,20.0"))
        // But a good contour beside a degenerate one survives.
        val back = PathText.decode("10.0,10.0 20.0,20.0;0.0,0.0 100.0,0.0 100.0,100.0")
        assertTrue(back != null && !back.isEmpty)
    }

    // ---------------------------------------------------------------- budget

    /**
     * The size to watch. A lasso around most of the page is the worst thing a
     * hand can produce, and it has to stay something `project.json` can carry.
     */
    @Test
    fun `a lasso around most of the page stays tens of kilobytes`() {
        val lasso = Path()
        val n = 60
        for (i in 0 until n) {
            val a = i / n.toFloat() * 2f * Math.PI.toFloat()
            val x = 300f + 280f * Math.cos(a.toDouble()).toFloat()
            val y = 200f + 180f * Math.sin(a.toDouble()).toFloat()
            if (i == 0) lasso.moveTo(x, y) else lasso.lineTo(x, y)
        }
        lasso.close()
        val text = PathText.encode(lasso)
        assertTrue(text.length < 40_000, "${text.length} characters")
        assertTrue(disagreement(lasso, PathText.decode(text)!!) < 2000)
    }

    @Test
    fun `the bounds survive, which is what the sheet dedupes on`() {
        val p = Path().apply { addRoundRect(RectF(30f, 40f, 330f, 240f), 20f, 20f, Path.Direction.CW) }
        val back = PathText.decode(PathText.encode(p))!!
        val a = RectF()
        val b = RectF()
        p.computeBounds(a, true)
        back.computeBounds(b, true)
        assertTrue(Math.abs(a.left - b.left) < 1.5f, "$a vs $b")
        assertTrue(Math.abs(a.right - b.right) < 1.5f, "$a vs $b")
        assertTrue(Math.abs(a.top - b.top) < 1.5f, "$a vs $b")
        assertTrue(Math.abs(a.bottom - b.bottom) < 1.5f, "$a vs $b")
    }
}
