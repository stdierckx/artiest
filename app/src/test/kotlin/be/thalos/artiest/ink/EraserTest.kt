package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.ink.Bounds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W11, at the level that decides whether the eraser is real: does it lower
 * alpha, and does it lower it once.
 *
 * A "white brush" eraser passes neither question and looks identical on a white
 * page, which is exactly why it has to be tested rather than looked at. The
 * layer is alpha-carrying by design — `Document`'s invariant is that the paper
 * is never in the layer — so painting white fills transparent pixels with
 * opaque white, and the export, the next layer down and any change of paper
 * colour all show it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EraserTest {

    private fun layer(): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        val p = Paint()
        p.color = Color.BLACK
        Canvas(it).drawRect(8f, 8f, 56f, 56f, p)
    }

    private fun dab(scratch: ScratchLayer, alpha: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        p.alpha = alpha
        scratch.canvasInDocSpace()!!.drawCircle(32f, 32f, 10f, p)
    }

    @Test
    fun `erasing lowers alpha rather than painting a colour`() {
        val bmp = layer()
        assertEquals(255, Color.alpha(bmp.getPixel(32, 32)), "the ink was not there to erase")
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        dab(scratch, 255)
        scratch.compositeInto(Canvas(bmp), 1f, null, erase = true)
        assertEquals(0, Color.alpha(bmp.getPixel(32, 32)), "the pixel is not transparent")
        assertEquals(255, Color.alpha(bmp.getPixel(12, 12)), "ink outside the dab was taken too")
    }

    /**
     * The reason the eraser goes through the scratch buffer at all. Eight
     * overlapping dabs at 30% take out 94% where one should take out 30% --
     * beading, in reverse. The control is beside it.
     */
    @Test
    fun `an eraser crossing itself does not take more than its opacity`() {
        val direct = layer()
        val ep = Paint(Paint.ANTI_ALIAS_FLAG)
        ep.color = Color.BLACK
        ep.alpha = 77
        ep.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        val dc = Canvas(direct)
        repeat(8) { dc.drawCircle(32f, 32f, 10f, ep) }
        val beaded = Color.alpha(direct.getPixel(32, 32))

        val indirect = layer()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        repeat(8) { dab(scratch, 255) }
        scratch.compositeInto(Canvas(indirect), 77f / 255f, null, erase = true)
        val flat = Color.alpha(indirect.getPixel(32, 32))

        assertTrue(beaded < 40, "the control did not bead: it left $beaded")
        assertTrue(
            abs(flat - (255 - 77)) <= 3,
            "the scratch should have left ${255 - 77}, but left $flat",
        )
    }

    /**
     * The shared paint's xfermode has to be cleared again. A leftover DST_OUT
     * turns the next ordinary stroke into an eraser, which looks like undo
     * being broken rather than like a paint bug.
     */
    @Test
    fun `an erase does not leave the next stroke erasing`() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val scratch = ScratchLayer()

        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        dab(scratch, 255)
        scratch.compositeInto(Canvas(bmp), 1f, null, erase = true)

        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        dab(scratch, 255)
        scratch.compositeInto(Canvas(bmp), 1f, null, erase = false)
        assertEquals(255, Color.alpha(bmp.getPixel(32, 32)), "the second stroke erased instead of painting")
    }

    @Test
    fun `a partial erase leaves partial ink`() {
        val bmp = layer()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        dab(scratch, 255)
        scratch.compositeInto(Canvas(bmp), 0.5f, null, erase = true)
        val left = Color.alpha(bmp.getPixel(32, 32))
        assertTrue(left in 100..155, "half an erase left $left")
    }
}
