package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import be.thalos.artiest.engine.ink.MutableBounds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one test that ties `:engine`'s `Bounds` to a real rasterizer.
 *
 * `Bounds` derives, from the half-open pixel convention, that
 * `[floor(c - r), ceil(c + r))` is the *exact* set of pixels an antialiased
 * dab of centre c and radius r can touch — and therefore that padding a
 * layer-raster rectangle "for antialiasing" is superstition. That is a claim
 * about Skia, and `:engine` cannot test it: it compiles against no android.jar,
 * which is the whole reason it is a separate module. So it is tested here, and
 * only here.
 *
 * The failure it exists to catch is one pixel of stale ink along the edge of
 * every redrawn region, which on the tablet is diagnosed by squinting.
 *
 * `@GraphicsMode(NATIVE)` with `@Config(sdk = [34])` is load-bearing and not
 * decoration: Robolectric's default `ShadowLegacyCanvas` *records* draw calls
 * instead of rasterizing them, so every `getPixel` returns 0 and a test of this
 * shape passes because nothing was ever drawn. The partial-coverage assertion
 * at the end is the tripwire for that — only a real rasterizer produces an
 * alpha strictly between 0 and 255 — so a Robolectric upgrade that drops back
 * to the legacy shadows fails here instead of going quietly green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DabFootprintTest {

    private val size = 96
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val pixels = IntArray(size * size)

    // :spike's dab paint, exactly: Paint(Paint.ANTI_ALIAS_FLAG), filled.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /** Stamps the dabs, then returns every pixel with non-zero alpha. */
    private fun paintedPixels(dabs: List<Triple<Float, Float, Float>>): List<Pair<Int, Int>> {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for ((x, y, r) in dabs) canvas.drawCircle(x, y, r, paint)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val hit = ArrayList<Pair<Int, Int>>()
        for (y in 0 until size) {
            for (x in 0 until size) if (pixels[y * size + x] ushr 24 != 0) hit.add(x to y)
        }
        return hit
    }

    private fun boundsRectOf(dabs: List<Triple<Float, Float, Float>>): IntArray {
        val acc = MutableBounds()
        for ((x, y, r) in dabs) acc.add(x, y, r)
        val out = IntArray(4)
        assertTrue(acc.toPixelRect(out, size, size), "nothing to redraw for $dabs")
        return out
    }

    /**
     * The claim, swept: over 600 randomized dabs, no pixel Skia gives non-zero
     * alpha falls outside the rectangle `Bounds` reports. The centres are
     * fractional and the radii run from sub-pixel to 15 px, because an
     * outward-rounding bug hides on integral centres.
     */
    @Test
    fun `no antialiased dab paints outside the rectangle Bounds reports`() {
        val rnd = Random(4242L)
        var exact = 0
        repeat(600) {
            val cx = 20f + rnd.nextFloat() * 56f
            val cy = 20f + rnd.nextFloat() * 56f
            val r = 0.2f + rnd.nextFloat() * 15f
            val dab = listOf(Triple(cx, cy, r))
            val (l, t, right, bottom) = boundsRectOf(dab).toList()
            val hit = paintedPixels(dab)
            assertTrue(hit.isNotEmpty(), "dab ($cx, $cy, r=$r) painted nothing")
            for ((x, y) in hit) {
                assertTrue(
                    x >= l && x < right && y >= t && y < bottom,
                    "dab ($cx, $cy, r=$r): Skia inked ($x, $y), outside Bounds' [$l, $t, $right, $bottom)",
                )
            }
            val hl = hit.minOf { it.first }; val hr = hit.maxOf { it.first } + 1
            val ht = hit.minOf { it.second }; val hb = hit.maxOf { it.second } + 1
            if (hl == l && ht == t && hr == right && hb == bottom) exact++
        }
        // The rectangle is tight, not merely a safe over-estimate: it matches
        // the inked extent on both axes for most dabs, and is at most one row
        // or column larger on the rest. If this collapses toward 0 someone has
        // started padding the layer-raster path.
        assertTrue(exact > 300, "only $exact of 600 dabs had a tight rectangle")
    }

    /** The same over a whole stroke, which is what actually gets committed. */
    @Test
    fun `no stroke paints outside its accumulated bounds`() {
        val rnd = Random(31337L)
        repeat(60) {
            val n = 2 + rnd.nextInt(20)
            val dabs = (0 until n).map {
                Triple(24f + rnd.nextFloat() * 48f, 24f + rnd.nextFloat() * 48f, 0.5f + rnd.nextFloat() * 8f)
            }
            val (l, t, right, bottom) = boundsRectOf(dabs).toList()
            for ((x, y) in paintedPixels(dabs)) {
                assertTrue(
                    x >= l && x < right && y >= t && y < bottom,
                    "stroke of $n dabs: Skia inked ($x, $y), outside Bounds' [$l, $t, $right, $bottom)",
                )
            }
        }
    }

    /**
     * The rasterizer is real. Without this the two tests above would pass on
     * ShadowLegacyCanvas, where nothing is ever inked and "no pixel outside the
     * bounds" is vacuously true.
     */
    @Test
    fun `the rasterizer produces genuine partial coverage`() {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawCircle(48f, 48f, 7f, paint)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val alphas = pixels.map { it ushr 24 }.toSet()
        assertTrue(255 in alphas, "no fully covered pixel: the canvas did not rasterize")
        assertTrue(0 in alphas, "no untouched pixel")
        assertTrue(
            alphas.any { it in 1..254 },
            "no partial coverage: this is ShadowLegacyCanvas, not Skia. Alphas seen: $alphas",
        )
        assertEquals(Color.BLACK, pixels[48 * size + 48], "the dab centre is opaque black")
    }
}
