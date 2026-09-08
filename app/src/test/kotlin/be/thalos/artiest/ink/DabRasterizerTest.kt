package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import be.thalos.artiest.canvas.DabBatch
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The render path's one arithmetic hazard, pinned against real Skia.
 *
 * `CanvasFrontBufferedRenderer` invokes the front-buffer callback once per
 * queued param on a **single** `RecordingCanvas`, and issues no save/restore of
 * its own between invocations. A bare `canvas.concat(m)` therefore compounds
 * across a flush: the second batch draws at M squared, the third at M cubed.
 * At identity — which is where anyone first tests — it is invisible. It appears
 * the moment the canvas is zoomed, as ink that lands further from the pen the
 * longer the flush was, and it is not the kind of thing that reads as a matrix
 * bug when you see it.
 *
 * So `the second batch is not double transformed` carries its own control: the
 * same two draws written the wrong way, asserted to actually land at M squared.
 * If the control ever stops compounding, the real assertion has stopped
 * proving anything and this test says so instead of going quietly green.
 *
 * `@GraphicsMode(NATIVE)` with `@Config(sdk = [34])` is load-bearing, for the
 * same reason `DabFootprintTest` gives: Robolectric's default shadow *records*
 * draw calls instead of rasterizing them, every `getPixel` returns 0, and a
 * test of this shape passes because nothing was ever drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DabRasterizerTest {

    private val size = 200
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val rasterizer = DabRasterizer(docWidthPx = 150, docHeightPx = 150)

    private fun clear() = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    private fun batch(vararg dabs: Triple<Float, Float, Float>, color: Int = Color.BLACK): DabBatch {
        val b = DabBatch(16)
        b.colorArgb = color
        b.antiAlias = true
        for ((x, y, r) in dabs) b.add(x, y, r)
        return b
    }

    private fun painted(x: Int, y: Int): Boolean = Color.alpha(bitmap.getPixel(x, y)) > 0

    /** Scale 2 about the origin: doc (20,20) is view (40,40). */
    private fun doubling(): Matrix = Matrix().apply { setScale(2f, 2f) }

    @Test
    fun `the second batch is not double transformed`() {
        clear()
        val m = doubling()
        rasterizer.drawWet(canvas, m, batch(Triple(20f, 20f, 4f)))
        rasterizer.drawWet(canvas, m, batch(Triple(20f, 20f, 4f)))

        assertTrue(painted(40, 40), "the dab is not where one application of M puts it")
        assertTrue(!painted(80, 80), "the second batch drew at M squared: the concat compounded")
    }

    @Test
    fun `the control confirms a bare concat really does compound`() {
        // The buggy body, written longhand. Without this the assertion above
        // could pass on a canvas where concat does nothing at all.
        clear()
        val m = doubling()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }
        canvas.concat(m)
        canvas.drawCircle(20f, 20f, 4f, paint)
        canvas.concat(m)
        canvas.drawCircle(20f, 20f, 4f, paint)

        assertTrue(painted(80, 80), "the control did not compound; the hazard is no longer real")
    }

    @Test
    fun `the canvas is handed back at the save count it arrived with`() {
        clear()
        val before = canvas.saveCount
        rasterizer.drawWet(canvas, doubling(), batch(Triple(20f, 20f, 4f)))
        assertEquals(before, canvas.saveCount, "drawWet leaked a save")
    }

    @Test
    fun `an empty batch draws nothing and touches nothing`() {
        clear()
        val before = canvas.saveCount
        rasterizer.drawWet(canvas, doubling(), batch())
        assertEquals(before, canvas.saveCount)
        assertTrue(!painted(40, 40))
    }

    @Test
    fun `each batch paints in its own colour`() {
        clear()
        val m = Matrix()
        rasterizer.drawWet(canvas, m, batch(Triple(40f, 40f, 6f), color = Color.RED))
        rasterizer.drawWet(canvas, m, batch(Triple(120f, 40f, 6f), color = Color.BLUE))

        assertEquals(Color.RED, bitmap.getPixel(40, 40))
        assertEquals(
            Color.BLUE,
            bitmap.getPixel(120, 40),
            "the second batch inherited the first batch's colour from the shared Paint",
        )
    }

    @Test
    fun `wet ink is clipped to the document, exactly as the layer clips dry ink`() {
        // The front buffer is the whole view; the layer is the whole document.
        // On this hardware the document does not reach the view edges, so
        // without the clip a stroke drawn in the margin is wet ink that
        // disappears at pen-up. The document here is 150x150 inside a 200x200
        // canvas, so 170 is a margin the pen can genuinely reach.
        clear()
        rasterizer.drawWet(canvas, Matrix(), batch(Triple(170f, 100f, 8f)))
        assertTrue(!painted(170, 100), "wet ink painted past the edge of the document")

        // And the clip is a clip, not a blanket refusal: a dab inside the
        // document still lands, and one straddling the edge keeps its inside
        // half.
        rasterizer.drawWet(canvas, Matrix(), batch(Triple(100f, 100f, 8f)))
        assertTrue(painted(100, 100))
        rasterizer.drawWet(canvas, Matrix(), batch(Triple(150f, 40f, 10f)))
        assertTrue(painted(145, 40), "the inside half of an edge dab was clipped away too")
        assertTrue(!painted(155, 40))
    }

    @Test
    fun `a dry stroke goes into the layer untransformed`() {
        // The layer IS document space, so drawDry takes no matrix. This pins
        // that: a stroke at doc (60,60) lands at bitmap (60,60), not somewhere
        // a view transform would have put it.
        clear()
        val dabs = floatArrayOf(60f, 60f, 5f, 100f, 60f, 5f)
        val stroke = Stroke.copyOf(
            dabs = dabs,
            dabCount = 2,
            colorArgb = Color.BLACK,
            antiAlias = true,
            bounds = Bounds.of(55f, 55f, 105f, 65f),
        )
        rasterizer.drawDry(canvas, stroke)

        assertTrue(painted(60, 60))
        assertTrue(painted(100, 60))
        assertTrue(!painted(80, 60), "the gap between two 5 px dabs 40 px apart was filled")
    }

    @Test
    fun `a wet batch and the dry stroke of the same dabs land on the same pixels`() {
        // The one property the whole two-layer design rests on: a stroke must
        // not move when it crosses from the front buffer to the layer. At
        // identity the two paths have to agree pixel for pixel, and they are
        // separate code paths with separate Paint state.
        clear()
        rasterizer.drawWet(canvas, Matrix(), batch(Triple(70f, 90f, 7f)))
        val wet = IntArray(size * size)
        bitmap.getPixels(wet, 0, size, 0, 0, size, size)

        clear()
        val stroke = Stroke.copyOf(
            dabs = floatArrayOf(70f, 90f, 7f),
            dabCount = 1,
            colorArgb = Color.BLACK,
            antiAlias = true,
            bounds = Bounds.of(63f, 83f, 77f, 97f),
        )
        rasterizer.drawDry(canvas, stroke)
        val dry = IntArray(size * size)
        bitmap.getPixels(dry, 0, size, 0, 0, size, size)

        var differing = 0
        for (i in wet.indices) if (wet[i] != dry[i]) differing++
        assertEquals(0, differing, "$differing pixels moved when the stroke went dry")

        // And the tripwire that says a rasterizer ran at all: an antialiased
        // circle has edge pixels that are neither empty nor opaque. Two blank
        // bitmaps also agree on every pixel.
        var partial = 0
        for (p in dry) {
            val a = Color.alpha(p)
            if (a in 1..254) partial++
        }
        assertTrue(partial > 0, "nothing was rasterized; the legacy Canvas shadow is back")
    }
}
