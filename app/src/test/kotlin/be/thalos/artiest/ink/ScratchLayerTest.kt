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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bug the scratch buffer exists to prevent, stated as a measurement.
 *
 * `Brush`'s tripwire has said since Phase 1 that a translucent brush must not
 * ship before this buffer does, and the reason is arithmetic: dabs are laid
 * eight to a nib diameter, so a stroke that composites each dab separately at
 * 30% reaches 94% after five overlaps. Every place the stroke crosses itself
 * goes black, and a slow curve becomes a string of dark beads.
 *
 * The fix is that the stroke accumulates on its own surface, where its overlaps
 * are free, and composites **once** at the stroke's opacity — so the stroke can
 * never be darker than its opacity however often it crosses itself. That is
 * what `a stroke crossing itself is no darker where it crosses` measures, and
 * it carries its own control so it cannot pass by accident.
 *
 * The three annotations are load-bearing for the reason `DabRasterizerTest`'s
 * header gives: without NATIVE graphics at SDK 34, Robolectric records draw
 * calls instead of rasterizing them and every alpha read below is 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScratchLayerTest {

    private fun surface() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private fun opaqueDab(c: Canvas, x: Float, y: Float, r: Float, alpha: Int = 255) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        p.alpha = alpha
        c.drawCircle(x, y, r, p)
    }

    /**
     * The whole point, with its control beside it.
     *
     * Five overlapping dabs. Drawn straight onto the destination at 30% each,
     * the overlap compounds toward opaque. Accumulated on the scratch at full
     * strength and composited once at 30%, the overlap is exactly 30% — the
     * same as a single dab.
     */
    @Test
    fun `a stroke crossing itself is no darker where it crosses`() {
        val direct = surface()
        val dc = Canvas(direct)
        repeat(5) { opaqueDab(dc, 32f, 32f, 12f, alpha = 77) }
        val beaded = Color.alpha(direct.getPixel(32, 32))

        val indirect = surface()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        val sc = scratch.canvasInDocSpace()!!
        repeat(5) { opaqueDab(sc, 32f, 32f, 12f) }
        scratch.compositeInto(Canvas(indirect), 77f / 255f)
        val flat = Color.alpha(indirect.getPixel(32, 32))

        assertTrue(beaded > 200, "the control did not bead: it reached only $beaded")
        assertTrue(
            abs(flat - 77) <= 2,
            "the scratch should have stayed at the stroke's own 77, but reached $flat",
        )
    }

    /** One dab must look the same either way, or the buffer is not transparent. */
    @Test
    fun `a single dab is unchanged by going through the buffer`() {
        val direct = surface()
        opaqueDab(Canvas(direct), 32f, 32f, 12f, alpha = 77)

        val indirect = surface()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        opaqueDab(scratch.canvasInDocSpace()!!, 32f, 32f, 12f)
        scratch.compositeInto(Canvas(indirect), 77f / 255f)

        assertEquals(
            Color.alpha(direct.getPixel(32, 32)),
            Color.alpha(indirect.getPixel(32, 32)),
        )
    }

    @Test
    fun `the buffer lands where the document says`() {
        val out = surface()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(40f, 40f, 56f, 56f))
        opaqueDab(scratch.canvasInDocSpace()!!, 48f, 48f, 6f)
        scratch.compositeInto(Canvas(out), 1f)
        assertEquals(255, Color.alpha(out.getPixel(48, 48)), "the dab moved")
        assertEquals(0, Color.alpha(out.getPixel(20, 20)), "something painted outside the stroke")
    }

    @Test
    fun `growing keeps what was already drawn`() {
        val out = surface()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(8f, 8f, 16f, 16f))
        opaqueDab(scratch.canvasInDocSpace()!!, 12f, 12f, 4f)
        assertTrue(scratch.ensureCovers(Bounds.of(8f, 8f, 56f, 56f)))
        opaqueDab(scratch.canvasInDocSpace()!!, 50f, 50f, 4f)
        scratch.compositeInto(Canvas(out), 1f)
        assertEquals(255, Color.alpha(out.getPixel(12, 12)), "the first dab was lost in the growth")
        assertEquals(255, Color.alpha(out.getPixel(50, 50)), "the second dab did not land")
    }

    @Test
    fun `reuse between strokes clears the previous one`() {
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        opaqueDab(scratch.canvasInDocSpace()!!, 32f, 32f, 12f)
        scratch.compositeInto(Canvas(surface()), 1f)

        val second = surface()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        scratch.compositeInto(Canvas(second), 1f)
        assertEquals(0, Color.alpha(second.getPixel(32, 32)), "the previous stroke came back")
    }

    /**
     * A page of hatching is hundreds of similar strokes, and reallocating a
     * multi-megabyte bitmap for each on the render thread is the kind of cost
     * that shows up as a stutter every stroke rather than as a slow app.
     */
    @Test
    fun `similar strokes reuse one allocation`() {
        val scratch = ScratchLayer()
        repeat(20) {
            scratch.begin(Bounds.of(10f + it, 10f, 60f + it, 60f))
            opaqueDab(scratch.canvasInDocSpace()!!, 30f, 30f, 5f)
            scratch.compositeInto(Canvas(surface()), 1f)
        }
        assertEquals(1L, scratch.allocations, "twenty similar strokes should allocate once")
    }

    @Test
    fun `a closed buffer refuses to hand out a canvas`() {
        val scratch = ScratchLayer()
        assertFalse(scratch.isOpen)
        assertEquals(null, scratch.canvasInDocSpace())
        assertFalse(scratch.ensureCovers(Bounds.of(0f, 0f, 8f, 8f)))
    }

    @Test
    fun `abandon discards without compositing`() {
        val out = surface()
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        opaqueDab(scratch.canvasInDocSpace()!!, 32f, 32f, 12f)
        scratch.abandon()
        assertFalse(scratch.isOpen)
        assertEquals(0, Color.alpha(out.getPixel(32, 32)))
    }

    /**
     * The F16 arm has to actually work before it can be measured. Eight bits of
     * alpha quantise to 1/255, so a pencil at 3% flow spends its first eight
     * dabs inside one step; F16 has no such floor.
     */
    @Test
    fun `the F16 arm allocates and draws`() {
        val scratch = ScratchLayer(Bitmap.Config.RGBA_F16)
        scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
        assertEquals(Bitmap.Config.RGBA_F16, scratch.peekBitmap()?.config)
        opaqueDab(scratch.canvasInDocSpace()!!, 32f, 32f, 12f)
        val out = surface()
        scratch.compositeInto(Canvas(out), 1f)
        assertEquals(255, Color.alpha(out.getPixel(32, 32)))
    }

    /**
     * The quantisation claim above, measured rather than asserted from theory:
     * how many dabs at 3% it takes each format to register anything at all.
     */
    @Test
    fun `eight bit alpha has a floor that F16 does not`() {
        fun buildUp(config: Bitmap.Config): Int {
            val scratch = ScratchLayer(config)
            scratch.begin(Bounds.of(16f, 16f, 48f, 48f))
            val c = scratch.canvasInDocSpace()!!
            repeat(4) { opaqueDab(c, 32f, 32f, 12f, alpha = 8) }
            val out = surface()
            scratch.compositeInto(Canvas(out), 1f)
            return Color.alpha(out.getPixel(32, 32))
        }
        val eight = buildUp(Bitmap.Config.ARGB_8888)
        val f16 = buildUp(Bitmap.Config.RGBA_F16)
        // Both must register *something*; the interesting number is printed so
        // W6's format decision is made against a measurement rather than a
        // belief about float buffers.
        println("four dabs at 3% flow: ARGB_8888 -> $eight, RGBA_F16 -> $f16")
        assertTrue(eight > 0, "8-bit lost four dabs entirely")
        assertTrue(f16 > 0, "F16 lost four dabs entirely")
    }

    /**
     * The growth policy the device forced. A zigzag across the page
     * reallocated 25 times in one stroke when growth only rounded to a grain,
     * because each sweep pushed the bounds out by more than a grain and every
     * push copied the whole buffer. Doubling makes it logarithmic.
     */
    @Test
    fun `growth is logarithmic, not linear`() {
        val scratch = ScratchLayer()
        scratch.begin(Bounds.of(0f, 0f, 8f, 8f))
        for (i in 1..40) {
            scratch.ensureCovers(Bounds.of(0f, 0f, i * 50f, i * 50f))
        }
        assertTrue(scratch.growths <= 8, "grew ${scratch.growths} times over 40 extensions")
    }

    /**
     * The cap the device forced, the hard way: at RGBA_F16 a 2048x2048 buffer
     * is 33.5 MiB, so one more doubling asks for 134 and the process is killed.
     * A stroke cannot be bigger than its page.
     */
    @Test
    fun `the buffer never grows past the page`() {
        val scratch = ScratchLayer(maxWidth = 300, maxHeight = 300)
        scratch.begin(Bounds.of(0f, 0f, 8f, 8f))
        scratch.ensureCovers(Bounds.of(0f, 0f, 290f, 290f))
        assertTrue(scratch.width <= 300, "grew to ${scratch.width}")
        assertTrue(scratch.height <= 300, "grew to ${scratch.height}")
        // And still covers what was asked for, or the cap has become a clip.
        opaqueDab(scratch.canvasInDocSpace()!!, 285f, 285f, 3f)
    }
}
