package be.thalos.artiest.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.doc.Layer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where an imported picture lands on the page.
 *
 * The decode needs a real file and a real content provider and is left to the
 * device; the fit is arithmetic, and arithmetic is what gets a reference
 * photograph stretched, cropped or half off the sheet. All three of those look
 * plausible on screen — a stretched photo is only obviously wrong once you draw
 * over it — so they are asserted here rather than looked at.
 *
 * The three annotations are the ones `LayerTest`'s header explains: without
 * `@GraphicsMode(NATIVE)` every `getPixel` returns zero and this file passes
 * while measuring nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureImporterTest {

    private fun picture(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.RED)
        return bmp
    }

    private fun pixel(layer: Layer, x: Int, y: Int): Int {
        var c = 0
        layer.read { c = it.getPixel(x, y) }
        return c
    }

    /** A wide picture on a squarer page: full width, letterboxed top and bottom. */
    @Test
    fun `a wide picture is fitted across and centred`() {
        val layer = Layer(300, 300, enforceOffMainThread = false)
        val pic = picture(400, 200)
        assertTrue(PictureImporter.paint(layer, pic, 300, 300))

        // 400x200 into 300x300 is a scale of 0.75, so 300x150 centred at
        // y = 75..225.
        assertEquals(Color.RED, pixel(layer, 150, 150), "the middle of the page is empty")
        assertEquals(Color.RED, pixel(layer, 2, 150), "it did not reach the left edge")
        assertEquals(Color.RED, pixel(layer, 297, 150), "it did not reach the right edge")
        assertEquals(0, pixel(layer, 150, 40), "the letterbox above is not transparent")
        assertEquals(0, pixel(layer, 150, 260), "the letterbox below is not transparent")
        assertEquals(0, Color.alpha(pixel(layer, 2, 2)), "the corner was filled in")
        layer.close()
        pic.recycle()
    }

    /** And the other way up, so the fit is a fit rather than a width rule. */
    @Test
    fun `a tall picture is fitted down and centred`() {
        val layer = Layer(300, 300, enforceOffMainThread = false)
        val pic = picture(100, 400)
        assertTrue(PictureImporter.paint(layer, pic, 300, 300))

        assertEquals(Color.RED, pixel(layer, 150, 150))
        assertEquals(Color.RED, pixel(layer, 150, 2), "it did not reach the top")
        assertEquals(Color.RED, pixel(layer, 150, 297), "it did not reach the bottom")
        assertEquals(0, pixel(layer, 20, 150), "the bars either side are not transparent")
        assertEquals(0, pixel(layer, 280, 150))
        layer.close()
        pic.recycle()
    }

    /**
     * **Not stretched.** The whole reason to import a picture is to draw from
     * it, and a reference whose proportions have been altered is worse than no
     * reference — the drawing made from it is wrong in a way that is invisible
     * until it is placed beside the original.
     */
    @Test
    fun `the picture keeps its own proportions`() {
        val layer = Layer(400, 400, enforceOffMainThread = false)
        val pic = picture(200, 100)
        PictureImporter.paint(layer, pic, 400, 400)

        var top = -1
        var bottom = -1
        for (y in 0 until 400) {
            if (Color.alpha(pixel(layer, 200, y)) > 0) {
                if (top < 0) top = y
                bottom = y
            }
        }
        var left = -1
        var right = -1
        for (x in 0 until 400) {
            if (Color.alpha(pixel(layer, x, 200)) > 0) {
                if (left < 0) left = x
                right = x
            }
        }
        val w = right - left + 1
        val h = bottom - top + 1
        // 2:1 in, 2:1 out, to within the rounding of one pixel each way.
        assertTrue(
            kotlin.math.abs(w.toFloat() / h - 2f) < 0.05f,
            "went in at 2:1 and came out at ${w}x$h",
        )
        layer.close()
        pic.recycle()
    }

    /**
     * A picture smaller than the page **is** enlarged to fill it, and that is a
     * decision rather than an accident.
     *
     * The other choice — place it at its own size and let the user scale it —
     * needs a transform tool, and there is not one yet. Without it, a small
     * reference dropped in at its own size is stuck small for the life of the
     * drawing, which is useless for the thing references are for. Fitting is
     * reversible in the only way that matters here: import it again.
     */
    @Test
    fun `a picture smaller than the page is fitted up to it`() {
        val layer = Layer(400, 400, enforceOffMainThread = false)
        val pic = picture(80, 80)
        PictureImporter.paint(layer, pic, 400, 400)
        assertEquals(Color.RED, pixel(layer, 200, 200))
        assertEquals(Color.RED, pixel(layer, 4, 200), "it was left at its own size")
        assertEquals(Color.RED, pixel(layer, 200, 4))
        layer.close()
        pic.recycle()
    }

    /** A closed document takes nothing, and says so rather than throwing. */
    @Test
    fun `a closed sheet refuses the picture`() {
        val layer = Layer(64, 64, enforceOffMainThread = false)
        layer.close()
        val pic = picture(32, 32)
        assertTrue(!PictureImporter.paint(layer, pic, 64, 64))
        pic.recycle()
    }
}
