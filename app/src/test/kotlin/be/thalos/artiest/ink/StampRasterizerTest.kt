package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import be.thalos.artiest.engine.brush.MaskSpec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * W5's candidate path, against real Skia.
 *
 * The question this file answers is the one the A/B cannot: not "is the stamp
 * faster" — that is a device measurement — but "is it drawing the same dab".
 * An A/B between a control and a candidate that rasterize differently is not a
 * comparison of two implementations, it is a comparison of two pictures.
 *
 * The three annotations are load-bearing for the reason `DabRasterizerTest`'s
 * header gives at length: without `@Config(sdk = [34])` and NATIVE graphics,
 * Robolectric records draw calls instead of rasterizing them, every `getPixel`
 * returns 0, and every assertion below passes against a canvas that drew
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StampRasterizerTest {

    private fun surface(): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private fun oneDab(x: Float, y: Float, r: Float, colour: Int = Color.BLACK): Stroke =
        Stroke.copyOf(
            dabs = floatArrayOf(x, y, r, 1f, 0f),
            dabCount = 1,
            colorArgb = colour,
            antiAlias = true,
            // abs, because the degenerate-radius test passes -1 and Bounds
            // rightly refuses an inverted rectangle. The dab list is what is
            // under test there, not the bounds.
            bounds = Bounds.of(x - abs(r), y - abs(r), x + abs(r) + 1f, y + abs(r) + 1f),
        )

    private fun render(mode: DabRasterizer.Mode, stroke: Stroke): Bitmap {
        val bmp = surface()
        val r = DabRasterizer(64, 64, StampCache())
        r.mode = mode
        r.drawDry(Canvas(bmp), stroke)
        return bmp
    }

    // ---- the cache -----------------------------------------------------------

    @Test
    fun `a stamp is uploaded as an ALPHA_8 bitmap the size of its mask`() {
        val c = StampCache()
        val mask = c.stampFor(MaskSpec.round(20f))
        val bmp = c.bitmapOf(mask)
        assertEquals(Bitmap.Config.ALPHA_8, bmp.config)
        assertEquals(mask.width, bmp.width)
        assertEquals(mask.height, bmp.height)
        assertEquals(1L, c.uploads)
    }

    /**
     * The bitmap rides on the mask rather than in a second map, so eviction
     * cannot get out of step. A second request for the same bucket must reuse
     * both, or there are two caches and one of them leaks.
     */
    @Test
    fun `the same bucket reuses one mask and one bitmap`() {
        val c = StampCache()
        val a = c.stampFor(MaskSpec.round(20f))
        val b = c.stampFor(MaskSpec.round(20.1f))
        assertSame(a, b)
        assertSame(c.bitmapOf(a), c.bitmapOf(b))
        assertEquals(1L, c.uploads, "the second lookup uploaded again")
        assertNotNull(a.attachment)
    }

    // ---- the dab -------------------------------------------------------------

    @Test
    fun `a stamped dab paints the paint's colour, not the mask's`() {
        val bmp = render(DabRasterizer.Mode.STAMP, oneDab(32f, 32f, 8f, Color.RED))
        assertEquals(Color.RED, bmp.getPixel(32, 32))
    }

    @Test
    fun `a stamped dab lands centred on the coordinate it was given`() {
        val bmp = render(DabRasterizer.Mode.STAMP, oneDab(32f, 32f, 8f))
        assertEquals(Color.BLACK, bmp.getPixel(32, 32), "the centre")
        // Solid across the diameter, empty well outside it.
        assertEquals(Color.BLACK, bmp.getPixel(26, 32))
        assertEquals(Color.BLACK, bmp.getPixel(38, 32))
        assertEquals(0, bmp.getPixel(32, 20), "12 px above a radius-8 dab")
        assertEquals(0, bmp.getPixel(50, 32))
    }

    /**
     * The A/B's precondition. The stamp is built at the tolerance bucket's
     * diameter, so it is within 3% of the circle rather than identical — but
     * the two must cover the same area to well inside that, or the comparison
     * is measuring a size change.
     */
    @Test
    fun `the stamp and the circle cover the same area`() {
        val stroke = oneDab(32f, 32f, 10f)
        val circle = render(DabRasterizer.Mode.CIRCLE, stroke)
        val stamp = render(DabRasterizer.Mode.STAMP, stroke)
        var ca = 0L
        var sa = 0L
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                ca += Color.alpha(circle.getPixel(x, y)).toLong()
                sa += Color.alpha(stamp.getPixel(x, y)).toLong()
            }
        }
        assertTrue(ca > 0 && sa > 0, "one of the paths drew nothing: circle $ca stamp $sa")
        val diff = abs(sa - ca).toDouble() / ca
        assertTrue(diff < 0.08, "coverage differed by ${"%.1f".format(diff * 100)}%")
    }

    /**
     * `drawCircle` treats a zero radius as nothing; `MaskSpec` refuses a
     * non-positive diameter, correctly, since a mask with no extent is not a
     * dab. Without the guard the stamp path would throw where the control
     * silently did nothing, and two rasterizers that disagree about degenerate
     * input are not an A/B.
     */
    @Test
    fun `a zero or negative radius draws nothing on both paths`() {
        for (mode in DabRasterizer.Mode.entries) {
            for (r in listOf(0f, -1f)) {
                val bmp = render(mode, oneDab(32f, 32f, r))
                var painted = 0
                for (y in 0 until 64) for (x in 0 until 64) {
                    if (bmp.getPixel(x, y) != 0) painted++
                }
                assertEquals(0, painted, "$mode at radius $r painted $painted pixels")
            }
        }
    }

    /** With no cache supplied, the stamp mode has to fall back rather than crash. */
    @Test
    fun `stamp mode without a cache still draws, on the circle path`() {
        val bmp = surface()
        val r = DabRasterizer(64, 64, null)
        r.mode = DabRasterizer.Mode.STAMP
        r.drawDry(Canvas(bmp), oneDab(32f, 32f, 8f))
        assertEquals(Color.BLACK, bmp.getPixel(32, 32))
    }

    @Test
    fun `a stroke of many dabs stamps every one of them`() {
        val n = 20
        val dabs = FloatArray(n * Stroke.STRIDE)
        for (i in 0 until n) {
            val o = i * Stroke.STRIDE
            dabs[o] = 8f + i * 2.4f
            dabs[o + 1] = 32f
            dabs[o + 2] = 4f
            dabs[o + 3] = 1f
        }
        val stroke = Stroke.copyOf(dabs, n, Color.BLACK, true, Bounds.of(0f, 20f, 64f, 44f))
        val bmp = surface()
        val cache = StampCache()
        val r = DabRasterizer(64, 64, cache)
        r.mode = DabRasterizer.Mode.STAMP
        r.drawDry(Canvas(bmp), stroke)
        assertEquals(Color.BLACK, bmp.getPixel(8, 32))
        assertEquals(Color.BLACK, bmp.getPixel(32, 32))
        assertEquals(1L, cache.uploads, "20 dabs of one size should upload one mask")
        assertTrue(cache.masks.hitRate > 0.9)
    }
}
