package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
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

    // ---- the eraser's own width, end to end ---------------------------------

    private val docW = 300
    private val docH = 160

    /**
     * One horizontal stroke through the whole path — builder, rasterizer,
     * scratch, composite — and how many rows of it came out.
     *
     * The whole path and not the width arithmetic, because the arithmetic is
     * already checked in `:engine` and what this file has to know is whether a
     * wider *number* becomes a wider *band of cleared pixels*. Between the two
     * sit the dab spacing, the stamp cache's mask size and the scratch buffer's
     * extent, any of which could quietly cap it.
     */
    private fun strokeRows(brush: Brush, erasing: Boolean): Int {
        val builder = StrokeBuilder(brush)
        builder.begin(Color.BLACK)
        var x = 40f
        var t = 0L
        while (x <= docW - 40f) {
            builder.add(
                PenSample(
                    x = x, y = docH / 2f, pressure = 1f, tilt = 0f, orientation = 0f,
                    distance = 0f, toolType = ToolType.STYLUS, buttonState = 0,
                    eventTimeNanos = t, source = PenSample.Source.CURRENT,
                ),
            )
            x += 2f
            t += 3_107_855L
        }
        val stroke = builder.end()

        val target = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
        if (erasing) {
            // Something to take away. A full sheet of ink, so the cleared band
            // is measured against a background that was uniformly there.
            val fill = Paint()
            fill.color = Color.BLACK
            Canvas(target).drawRect(0f, 0f, docW.toFloat(), docH.toFloat(), fill)
        }

        val rasterizer = DabRasterizer(docW, docH, StampCache())
        rasterizer.hardness = brush.hardness
        rasterizer.solid = erasing
        val scratch = ScratchLayer()
        scratch.begin(stroke.bounds)
        rasterizer.drawDry(scratch.canvasInDocSpace()!!, stroke)
        scratch.compositeInto(
            Canvas(target),
            if (erasing) 1f else brush.opacity,
            null,
            erase = erasing,
        )

        var rows = 0
        for (y in 0 until docH) {
            val a = Color.alpha(target.getPixel(docW / 2, y))
            val hit = if (erasing) a < 249 else a > 6
            if (hit) rows++
        }
        return rows
    }

    /**
     * The user's report: "the eraser may be a bit bigger, it may also be
     * adjustable". Both halves are one property — the eraser has a width of its
     * own — and this is that property seen from the pixels.
     */
    @Test
    fun `the eraser clears a wider band than the brush paints`() {
        val brush = BrushPreset.PENCIL.create()
        brush.sizeMax = 48f
        brush.eraseSizeMax = 96f
        val painted = strokeRows(brush, erasing = false)
        brush.erase = true
        val cleared = strokeRows(brush, erasing = true)
        assertTrue(painted > 0, "the control stroke painted nothing")
        assertTrue(
            cleared > painted * 1.6f,
            "a 2x eraser cleared $cleared rows where the brush painted $painted",
        )
    }

    /** And the slider reaches the pixels: a bigger number is a bigger band. */
    @Test
    fun `raising the eraser's width widens what it takes`() {
        val brush = BrushPreset.PENCIL.create()
        brush.sizeMax = 48f
        brush.erase = true
        brush.eraseSizeMax = 48f
        val small = strokeRows(brush, erasing = true)
        brush.eraseSizeMax = 150f
        val big = strokeRows(brush, erasing = true)
        assertTrue(big > small * 2f, "48px cleared $small rows and 150px cleared $big")
    }
}
