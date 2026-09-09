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
        assertNull(GrainTexture().shaderFor(GrainSpec(strength = 0f)))
    }

    @Test
    fun `an active grain builds one tile and reuses it`() {
        val g = GrainTexture()
        val spec = GrainSpec(strength = 0.8f)
        assertNotNull(g.shaderFor(spec))
        assertNotNull(g.shaderFor(spec))
        assertEquals(1L, g.builds, "asking twice should not rebuild the tile")
        g.shaderFor(spec.copy(strength = 0.4f))
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
            scratch.compositeInto(Canvas(out), 1f, g.shaderFor(spec))
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
    /**
     * The canvas-space property, and the bug that broke it.
     *
     * The same shape drawn at two different places on the page must pick up
     * *different* grain, because the paper under it is different — and the same
     * shape drawn twice at the *same* place must pick up the same grain, which
     * is what makes a stroke keep its texture when the pen lifts. The grain
     * used to be anchored to the scratch buffer's origin, which moves as the
     * stroke grows and again between the wet pass and the commit, so it failed
     * the second half: the stroke visibly re-textured itself at pen-up.
     */
    @Test
    fun `the grain belongs to the paper, and stays put`() {
        fun strip(atX: Float, bufferOrigin: Float): IntArray {
            val out = surface()
            val scratch = ScratchLayer()
            // The buffer's own origin is varied independently of where the
            // ink lands, which is exactly what happens as a stroke grows.
            scratch.begin(Bounds.of(bufferOrigin, 0f, bufferOrigin + 90f, 90f))
            fill(scratch, atX, 10f, atX + 40f, 60f)
            val g = GrainTexture()
            scratch.compositeInto(
                Canvas(out), 1f, g.shaderFor(GrainSpec(strength = 1f, scaleDocPx = 48f)),
            )
            return IntArray(40) { Color.alpha(out.getPixel(atX.toInt() + it, 30)) }
        }
        assertTrue(
            !strip(4f, 0f).contentEquals(strip(40f, 0f)),
            "the grain followed the stroke instead of staying on the page",
        )
        assertTrue(
            strip(4f, 0f).contentEquals(strip(4f, -30f)),
            "the grain moved when the buffer did: a stroke would re-texture itself at pen-up",
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
        val shader = g.shaderFor(spec)

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
