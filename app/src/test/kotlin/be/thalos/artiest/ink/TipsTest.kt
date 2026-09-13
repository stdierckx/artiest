package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wb5: reading a picture as a nib.
 *
 * Everything here is about **which channel the mark is in**, because that is
 * the one question a PNG does not answer about itself and the one that is
 * catastrophic to get wrong — the two readings of a tip are its picture and a
 * solid slab, with nothing in between.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TipsTest {

    @After
    fun tearDown() {
        Tips.library.clear()
    }

    private fun rgba(vararg pixels: Int) = intArrayOf(*pixels)

    // ---- which channel the mark is in ---------------------------------------

    @Test
    fun `a picture drawn on transparency is read from its alpha`() {
        val cover = Tips.coverageOf(
            rgba(Color.argb(0, 0, 0, 0), Color.argb(128, 0, 0, 0), Color.argb(255, 0, 0, 0)),
        )
        assertEquals(0, cover[0].toInt() and 0xFF)
        assertEquals(128, cover[1].toInt() and 0xFF)
        assertEquals(255, cover[2].toInt() and 0xFF)
    }

    @Test
    fun `black ink on white paper is read from its grey`() {
        val cover = Tips.coverageOf(
            rgba(Color.WHITE, Color.GRAY, Color.BLACK),
        )
        assertEquals(0, cover[0].toInt() and 0xFF)
        assertTrue((cover[1].toInt() and 0xFF) in 100..160)
        assertEquals(255, cover[2].toInt() and 0xFF)
    }

    /**
     * `oil_knife.png`, which is what the first version of this got wrong.
     *
     * A 300x300 grey-plus-alpha PNG whose mark is entirely in the grey and
     * whose alpha is opaque for all but four of its 90,000 pixels. Under "any
     * transparency at all", four pixels turned a palette knife into a solid
     * slab; under a floor, the channel is correctly read as empty.
     */
    @Test
    fun `a few stray translucent pixels do not decide the whole picture`() {
        val n = 10_000
        val pixels = IntArray(n) { if (it < n / 2) Color.WHITE else Color.BLACK }
        pixels[0] = Color.argb(200, 255, 255, 255)
        pixels[1] = Color.argb(120, 255, 255, 255)
        val cover = Tips.coverageOf(pixels)
        assertEquals(0, cover[n / 2 - 1].toInt() and 0xFF, "white paper painted")
        assertEquals(255, cover[n - 1].toInt() and 0xFF, "black ink did not paint")
    }

    @Test
    fun `a real rim of antialiasing is believed`() {
        // A 16x16 tip with a one-pixel translucent rim: 60 of 256 pixels, well
        // over the floor, and the alpha is the mark.
        val pixels = IntArray(256)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val edge = x == 0 || y == 0 || x == 15 || y == 15
                pixels[y * 16 + x] = Color.argb(if (edge) 90 else 255, 0, 0, 0)
            }
        }
        val cover = Tips.coverageOf(pixels)
        assertEquals(90, cover[0].toInt() and 0xFF)
        assertEquals(255, cover[8 * 16 + 8].toInt() and 0xFF)
    }

    // ---- the library ---------------------------------------------------------

    @Test
    fun `a bitmap becomes a tip that knows its own size`() {
        val bitmap = Bitmap.createBitmap(12, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val tip = Tips.adopt("slab", bitmap)
        assertNotNull(tip)
        assertEquals(12, tip.width)
        assertEquals(6, tip.height)
        assertEquals(12, tip.span)
        assertEquals(255, tip.alphaAt(3, 3))
        bitmap.recycle()
    }

    @Test
    fun `a directory of pictures loads, and rubbish in it does not stop the rest`() {
        val dir = File.createTempFile("tips", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        File(dir, "good.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        File(dir, "broken.png").writeText("this is not a picture")
        File(dir, "notes.txt").writeText("nor is this")

        assertEquals(1, Tips.loadDirectory(dir))
        assertNotNull(Tips.find("good"))
        assertNull(Tips.find("broken"))
        assertNull(Tips.find("notes"))
        dir.deleteRecursively()
    }

    @Test
    fun `a directory that is not there is not an error`() {
        assertEquals(0, Tips.loadDirectory(File("/no/such/place")))
    }
}
