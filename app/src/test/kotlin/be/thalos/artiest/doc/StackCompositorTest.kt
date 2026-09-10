package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a drawing looks like, asserted as pixels.
 *
 * These are the rules that used to live in two places — `InkSurfaceView` for
 * the screen and `PngExporter` for the file — and were therefore never stated
 * anywhere. Each one below is a thing that was true of the shipped app and
 * true only because both copies happened to agree.
 *
 * The three annotations are load-bearing, for the reason `DabRasterizerTest`'s
 * header gives at length: without NATIVE graphics at SDK 34 Robolectric records
 * draw calls instead of rasterizing them, every `getPixel` returns 0, and a
 * file of pixel assertions passes against a compositor that draws nothing.
 * `the paper is the bottom of the stack` is the tripwire — it asserts a
 * non-zero colour on a canvas nothing else has touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackCompositorTest {

    private val w = 8
    private val h = 4

    private var stack: LayerStack? = null

    @After
    fun tearDown() {
        stack?.close()
    }

    private fun stackOf(): LayerStack =
        LayerStack(w, h, enforceOffMainThread = false).also { stack = it }

    /** A fresh sheet above the active one, filled with [color]. */
    private fun LayerStack.addSheet(color: Int): Int {
        val layer = newLayer()
        layer.write { it.drawColor(color) }
        apply(LayerOp.Add(layer, "sheet"))
        return active.id
    }

    private fun compose(
        stack: LayerStack,
        wet: StackCompositor.Wet? = null,
        paper: Int = Color.WHITE,
    ): Pair<Bitmap, Boolean> {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val read = StackCompositor().compose(
            Canvas(out), stack, paper, w, h, wet,
            0f, 0f, w.toFloat(), h.toFloat(),
        )
        return out to read
    }

    // --- the paper ----------------------------------------------------------

    @Test
    fun `the paper is the bottom of the stack`() {
        // Not a backdrop slid underneath afterwards, which is what the export
        // used to do. The distinction is invisible for source-over sheets and
        // is the whole reason this class exists; see its header.
        val (image, read) = compose(stackOf(), paper = Color.rgb(240, 230, 210))
        assertTrue(read)
        assertEquals(Color.rgb(240, 230, 210), image.getPixel(4, 2))
    }

    @Test
    fun `a document with every sheet hidden is still paper`() {
        val stack = stackOf()
        stack.apply(LayerOp.SetVisible(stack.active.id, false))
        val (image, read) = compose(stack)
        assertTrue(read, "a hidden sheet is not a torn read")
        assertEquals(Color.WHITE, image.getPixel(4, 2))
    }

    // --- the order ----------------------------------------------------------

    @Test
    fun `sheets are painted bottom to top`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        stack.addSheet(Color.BLUE)
        assertEquals(Color.BLUE, compose(stack).first.getPixel(4, 2), "the top sheet wins")
    }

    @Test
    fun `a hidden sheet is not painted`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        val top = stack.addSheet(Color.BLUE)
        stack.apply(LayerOp.SetVisible(top, false))
        assertEquals(Color.RED, compose(stack).first.getPixel(4, 2))
    }

    @Test
    fun `a sheet at zero opacity is not painted`() {
        // Not merely invisible: it is a full-page blit that cannot change a
        // pixel, and skipping it is the only reason to check.
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        val top = stack.addSheet(Color.BLUE)
        stack.apply(LayerOp.SetOpacity(top, 0f))
        val (image, _) = compose(stack)
        assertEquals(Color.RED, image.getPixel(4, 2))
    }

    @Test
    fun `a sheet's opacity mixes it with what is under it`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.BLACK) }
        val top = stack.addSheet(Color.WHITE)
        stack.apply(LayerOp.SetOpacity(top, 0.5f))
        val mid = compose(stack).first.getPixel(4, 2)
        assertTrue(Color.red(mid) in 100..155, "half way between black and white, got ${Color.red(mid)}")
    }

    @Test
    fun `sheetsPainted counts what was drawn, not what the stack holds`() {
        val stack = stackOf()
        stack.addSheet(Color.BLUE)
        val hidden = stack.addSheet(Color.GREEN)
        stack.apply(LayerOp.SetVisible(hidden, false))
        val compositor = StackCompositor()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        compositor.compose(
            Canvas(out), stack, Color.WHITE, w, h, null, 0f, 0f, w.toFloat(), h.toFloat(),
        )
        assertEquals(3, stack.size)
        assertEquals(2, compositor.sheetsPainted)
    }

    // --- the wet stroke -----------------------------------------------------

    /** Wet ink on sheet [position], as a rectangle across the left half. */
    private class Ink(
        override val position: Int,
        override val erases: Boolean,
        private val color: Int = Color.GREEN,
    ) : StackCompositor.Wet {
        override val isOpen: Boolean get() = true
        private val paint = Paint().apply {
            isAntiAlias = false
            if (erases) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        override fun draw(canvas: Canvas) {
            paint.color = color
            canvas.drawRect(0f, 0f, 4f, 4f, paint)
        }
    }

    @Test
    fun `the wet stroke goes inside the stack, not on top of it`() {
        // Ink going onto the lower of two sheets must be hidden by the one
        // above it while it is still wet, or the stroke jumps behind it at
        // pen-up -- which is a stroke that visibly moves after the pen lifts.
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        stack.addSheet(Color.BLUE)
        val image = compose(stack, Ink(position = 0, erases = false)).first
        assertEquals(Color.BLUE, image.getPixel(1, 2), "the sheet above hides the wet ink")
        assertEquals(Color.BLUE, image.getPixel(6, 2))
    }

    @Test
    fun `the wet stroke shows on the sheet it is going onto`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        val image = compose(stack, Ink(position = 0, erases = false)).first
        assertEquals(Color.GREEN, image.getPixel(1, 2))
        assertEquals(Color.RED, image.getPixel(6, 2))
    }

    @Test
    fun `an erasing stroke cuts its own sheet and nothing under it`() {
        // DST_OUT applied straight to the frame would cut through the paper and
        // every sheet already painted, and the stroke would read as a window
        // onto the desk. The offscreen layer is what scopes it.
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        stack.addSheet(Color.BLUE)
        val image = compose(stack, Ink(position = 1, erases = true)).first
        assertEquals(Color.RED, image.getPixel(1, 2), "the sheet below shows through")
        assertEquals(Color.BLUE, image.getPixel(6, 2), "and the rest is untouched")
    }

    @Test
    fun `a wet stroke on a sheet that is hidden draws nothing`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        stack.apply(LayerOp.SetVisible(stack.active.id, false))
        val image = compose(stack, Ink(position = 0, erases = false)).first
        assertEquals(Color.WHITE, image.getPixel(1, 2))
    }

    @Test
    fun `a closed wet stroke is not drawn`() {
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        val closed = object : StackCompositor.Wet {
            override val position: Int get() = 0
            override val isOpen: Boolean get() = false
            override val erases: Boolean get() = false
            override fun draw(canvas: Canvas) = error("a closed stroke must not be asked to draw")
        }
        assertEquals(Color.RED, compose(stack, closed).first.getPixel(1, 2))
    }

    // --- teardown -----------------------------------------------------------

    @Test
    fun `a sheet closed underneath the compositor is reported`() {
        // The export turns this into a failure; the screen draws what it got.
        // Either way it must not be silent, because the alternative is a file
        // with a layer missing from it and a success result beside it.
        val stack = stackOf()
        stack.active.layer.write { it.drawColor(Color.RED) }
        stack.active.layer.close()
        val (image, read) = compose(stack)
        assertFalse(read)
        assertEquals(Color.WHITE, image.getPixel(4, 2), "paper, not a torn read")
    }
}
