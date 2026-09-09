package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
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

    /**
     * The eraser takes the ink out completely, whatever the brush was set to.
     *
     * **The bug this pins.** The eraser is a mode, not a preset: it borrows
     * whatever brush is selected and only changes how the stroke composites. So
     * with the pencil chosen it inherited the pencil's flow — which starts at
     * 0.02 and only reaches 0.85 under full pressure — *and* the pencil's 0.90
     * opacity ceiling *and* its grain mask, three separate reasons to leave ink
     * behind. Leaning on the eraser as hard as the hardware allows took out
     * roughly two thirds of the pixel; a normal wipe took out a quarter. That
     * is the "eraser is too soft" report, and it is arithmetic rather than
     * taste.
     *
     * `DabRasterizer.solid` is the per-dab half of the fix and
     * `InkSurfaceView.compositeAlpha` is the composite half. Both are needed:
     * either one alone still leaves a fraction of the ink.
     */
    @Test
    fun `a solid rasterizer ignores the brush's flow`() {
        val faint = Stroke.copyOf(
            // The last stride slot is the dab's own flow. A tenth, which is
            // what a pencil held lightly asks for.
            dabs = floatArrayOf(32f, 32f, 10f, 1f, 0f, 0.1f),
            dabCount = 1,
            colorArgb = Color.BLACK,
            antiAlias = true,
            bounds = Bounds.of(22f, 22f, 42f, 42f),
        )
        val r = DabRasterizer(docWidthPx = 64, docHeightPx = 64)

        val soft = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        r.drawDry(Canvas(soft), faint, flowOverride = 0.45f)
        val softAlpha = Color.alpha(soft.getPixel(32, 32))
        assertTrue(softAlpha < 40, "the control was already solid at $softAlpha")

        val hard = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        r.solid = true
        r.drawDry(Canvas(hard), faint, flowOverride = 0.45f)
        assertEquals(255, Color.alpha(hard.getPixel(32, 32)), "a solid dab was not solid")
    }

    /**
     * And the two halves together, end to end: a faint pencil dab, erased,
     * leaves nothing at all.
     */
    @Test
    fun `a faint pencil stroke used as an eraser still clears the pixel`() {
        val bmp = layer()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        // What the pencil's own flow would have painted, had `solid` not been
        // set: a tenth of the ink. The eraser must not be this.
        dab(scratch, 26)
        scratch.compositeInto(Canvas(bmp), 1f, null, erase = true)
        assertTrue(
            Color.alpha(bmp.getPixel(32, 32)) > 200,
            "the control erased far more than a tenth",
        )

        val full = layer()
        val s2 = ScratchLayer()
        s2.begin(Bounds.of(16f, 16f, 48f, 48f))
        dab(s2, 255)
        s2.compositeInto(Canvas(full), 1f, null, erase = true)
        assertEquals(0, Color.alpha(full.getPixel(32, 32)), "the eraser left ink behind")
    }
}
