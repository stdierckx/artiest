package be.thalos.artiest.doc

import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.ink.Bounds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo at the level the policy tests cannot reach: actual pixels.
 *
 * `UndoHistoryTest` proves the chains behave; this proves a patch put back is
 * the thing that was there, alpha included. The three annotations are the same
 * ones `LayerTest`'s header explains at length — without `@Config(sdk = [34])`
 * Robolectric runs at SDK 21 on the legacy bitmap shadow, every `getPixel`
 * returns zero, and a test like this one passes while asserting nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PixelPatchTest {

    private fun layer() = Layer(32, 32, enforceOffMainThread = false)

    private fun fill(layer: Layer, x: Int, y: Int, w: Int, h: Int, colour: Int) {
        val paint = Paint().apply { this.color = colour; isAntiAlias = false }
        layer.write {
            it.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        }
    }

    private fun pixel(layer: Layer, x: Int, y: Int): Int {
        var c = 0
        layer.read { c = it.getPixel(x, y) }
        return c
    }

    /**
     * The bug `Mode.SRC` exists to prevent, stated as a test.
     *
     * Restoring with ordinary source-over compositing would put the red back
     * over the blue and leave the pixel at 20,20 opaque — the ink appears to
     * come back and its shadow stays. Only a replacing blit can lower alpha,
     * and the assertion at 20,20 is the one that fails without it.
     */
    @Test
    fun `a restored patch brings back both colour and transparency`() {
        val layer = layer()
        // A red square, then a blue one drawn half over it. Undoing the blue
        // has to give back red *and* the transparent pixels beside it.
        fill(layer, 4, 4, 8, 8, Color.RED)

        val patch = assertNotNull(PixelPatch.captureAll(layer, 32, 32))
        fill(layer, 8, 8, 16, 16, Color.BLUE)
        assertEquals(Color.BLUE, pixel(layer, 10, 10))
        assertEquals(Color.BLUE, pixel(layer, 20, 20))

        assertTrue(patch.restoreInto(layer))
        assertEquals(Color.RED, pixel(layer, 10, 10), "ink under the undone stroke")
        assertEquals(0, pixel(layer, 20, 20), "a pixel the undone stroke had made opaque")
        assertEquals(0, pixel(layer, 30, 30), "a pixel nothing ever touched")
        layer.close()
    }

    @Test
    fun `undo then redo returns to the drawn state exactly`() {
        val layer = layer()
        fill(layer, 4, 4, 8, 8, Color.RED)

        val before = assertNotNull(PixelPatch.captureAll(layer, 32, 32))
        fill(layer, 8, 8, 16, 16, Color.BLUE)

        // Exactly what Document.exchange does, in both directions.
        val after = assertNotNull(before.recapture(layer))
        before.restoreInto(layer)
        assertEquals(Color.RED, pixel(layer, 10, 10))

        val undone = assertNotNull(after.recapture(layer))
        after.restoreInto(layer)
        assertEquals(Color.BLUE, pixel(layer, 10, 10), "redo did not put the stroke back")
        assertEquals(Color.BLUE, pixel(layer, 20, 20))

        undone.restoreInto(layer)
        assertEquals(Color.RED, pixel(layer, 10, 10), "a second undo did not walk back again")
        layer.close()
    }

    @Test
    fun `a capture covers the antialiased rim just outside the bounds`() {
        val layer = layer()
        // A stroke reporting exactly 10..20 is captured with a pixel of margin
        // either side, because antialiasing tints the pixel outside the
        // geometry and a patch one pixel short leaves an outline behind.
        val patch = assertNotNull(
            PixelPatch.capture(layer, Bounds.of(10f, 10f, 20f, 20f), 32, 32),
        )
        fill(layer, 9, 9, 13, 13, Color.GREEN)
        patch.restoreInto(layer)

        // Without the pad the rect would be 10..19; with it, 9..20. Both ends
        // of that extra rim are what this asserts, because a patch one pixel
        // short leaves the antialiased edge of every undone stroke behind.
        assertEquals(0, pixel(layer, 9, 9), "the rim before the bounds was not restored")
        assertEquals(0, pixel(layer, 20, 20), "the rim after the bounds was not restored")
        // And no further: one pixel of insurance, not an unbounded snapshot.
        assertEquals(Color.GREEN, pixel(layer, 21, 21), "the patch reached past its rim")
        layer.close()
    }

    @Test
    fun `a stroke entirely off the page snapshots nothing`() {
        val layer = layer()
        assertNull(PixelPatch.capture(layer, Bounds.of(-100f, -100f, -50f, -50f), 32, 32))
        layer.close()
    }

    @Test
    fun `a closed layer captures nothing rather than throwing`() {
        val layer = layer()
        layer.close()
        assertNull(PixelPatch.captureAll(layer, 32, 32))
    }

    @Test
    fun `a patch reports the bytes it is holding`() {
        val layer = layer()
        val patch = assertNotNull(PixelPatch.captureAll(layer, 32, 32))
        assertEquals(32L * 32L * 4L, patch.bytes, "ARGB_8888 is four bytes a pixel")
        patch.recycle()
        layer.close()
    }
}
