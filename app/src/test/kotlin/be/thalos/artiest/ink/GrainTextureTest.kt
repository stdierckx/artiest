package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.brush.GrainSpec
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
 * W8, at the level that matters: does the grain reach the pixels, and does it
 * stay with the paper rather than with the stroke.
 *
 * The second question is the one that separates canvas-space texture from the
 * usual mistake. Grain locked to the dab swims along with the stroke and reads
 * as a dirty brush; grain locked to the document is a surface the stroke is
 * drawn *on*. `the grain belongs to the paper` is that difference, measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrainTextureTest {

    private fun surface() = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)

    private fun fill(scratch: ScratchLayer, l: Float, t: Float, r: Float, b: Float) {
        val p = Paint()
        p.color = Color.BLACK
        scratch.canvasInDocSpace()!!.drawRect(l, t, r, b, p)
    }

    private fun coverage(bmp: Bitmap): Long {
        var a = 0L
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            a += Color.alpha(bmp.getPixel(x, y)).toLong()
        }
        return a
    }

    @Test
    fun `an inactive grain hands back no shader at all`() {
        assertNull(GrainTexture().shaderFor(GrainSpec(strength = 0f), 0, 0))
    }

    @Test
    fun `an active grain builds one tile and reuses it`() {
        val g = GrainTexture()
        val spec = GrainSpec(strength = 0.8f)
        assertNotNull(g.shaderFor(spec, 0, 0))
        assertNotNull(g.shaderFor(spec, 40, 40))
        assertEquals(1L, g.builds, "moving the origin should not rebuild the tile")
        g.shaderFor(spec.copy(strength = 0.4f), 0, 0)
        assertEquals(2L, g.builds, "a changed spec should rebuild")
    }

    @Test
    fun `grain takes paint away from the stroke`() {
        fun paint(strength: Float): Long {
            val out = surface()
            val scratch = ScratchLayer()
            scratch.begin(Bounds.of(16f, 16f, 80f, 80f))
            fill(scratch, 16f, 16f, 80f, 80f)
            val g = GrainTexture()
            val spec = GrainSpec(strength = strength, scaleDocPx = 64f)
            scratch.compositeInto(
                Canvas(out), 1f, g.shaderFor(spec, scratch.originX, scratch.originY),
            )
            return coverage(out)
        }
        val plain = paint(0f)
        val textured = paint(1f)
        assertTrue(plain > 0, "the control drew nothing")
        assertTrue(
            textured < plain,
            "grain did not reduce coverage: $textured against $plain",
        )
        assertTrue(textured > 0, "grain removed the stroke entirely")
    }

    /**
     * The canvas-space property. The same stroke shape drawn at two different
     * places on the page must pick up *different* grain, because the paper
     * under it is different. If the two came out identical the texture would be
     * locked to the stroke, which is the failure this whole design avoids.
     */
    @Test
    fun `the grain belongs to the paper, not to the stroke`() {
        fun strip(originX: Int): IntArray {
            val out = surface()
            val scratch = ScratchLayer()
            scratch.begin(Bounds.of(0f, 0f, 64f, 64f))
            fill(scratch, 0f, 0f, 64f, 64f)
            val g = GrainTexture()
            val spec = GrainSpec(strength = 1f, scaleDocPx = 48f)
            // Pretend the buffer sits at a different place on the page.
            scratch.compositeInto(Canvas(out), 1f, g.shaderFor(spec, originX, 0))
            return IntArray(48) { Color.alpha(out.getPixel(it, 20)) }
        }
        val here = strip(0)
        val elsewhere = strip(23)
        assertTrue(
            !here.contentEquals(elsewhere),
            "the grain followed the stroke instead of staying on the page",
        )
    }

    /**
     * The wet pass composites the scratch on every frame. Compositing twice
     * must not multiply the grain in twice, or a stroke would fade while it was
     * being drawn -- which is why the grain is composed at draw time rather
     * than applied to the buffer.
     */
    @Test
    fun `drawing the same buffer twice does not double the grain`() {
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 80f, 80f))
        fill(scratch, 16f, 16f, 80f, 80f)
        val g = GrainTexture()
        val spec = GrainSpec(strength = 1f, scaleDocPx = 64f)
        val shader = g.shaderFor(spec, scratch.originX, scratch.originY)

        val once = surface()
        scratch.drawOnto(Canvas(once), 1f, shader)
        val twice = surface()
        scratch.drawOnto(Canvas(twice), 1f, shader)

        assertEquals(
            coverage(once), coverage(twice),
            "the second draw of the same buffer produced a different result",
        )
    }
}
