package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp
import be.thalos.artiest.doc.Selection
import be.thalos.artiest.engine.ink.Bounds
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Only being able to draw in the selection", as pixels.
 *
 * The line of the request that matters most, and the one with a stop condition
 * against it: *"S3 cannot make the wet stroke and the committed stroke agree at
 * the selection edge. Stop. A selection whose edge moves at pen-up is not a
 * selection, it is a hint."*
 *
 * That agreement is why the stencil is applied to the **scratch buffer** rather
 * than as a clip on either canvas. The two canvases disagree about clipping —
 * `Layer`'s is software and antialiases a path clip, the frame's is a
 * `RenderNode`'s and does not — so a clipped implementation would have a
 * stair-stepped wet edge that snapped smooth as the pen lifted. Masking the
 * buffer means both passes read the *same pixels*, and `the wet pass and the
 * commit put down the same edge` is that stated as a test rather than as a
 * paragraph.
 *
 * The three annotations are load-bearing: see `ScratchLayerTest`'s header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionInkTest {

    private val w = 64
    private val h = 40

    private var doc: Document? = null
    private var sel: Selection? = null
    private val scratch = ScratchLayer(maxWidth = 256, maxHeight = 256)

    @After
    fun tearDown() {
        scratch.release()
        sel?.close()
        doc?.close()
    }

    private fun document(): Document =
        Document(w, h, Color.WHITE, enforceOffMainThread = false).also { doc = it }

    private fun selection(l: Float, t: Float, r: Float, b: Float): Selection =
        Selection(w, h).also {
            sel = it
            it.apply(SelectOp.Shape(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, SelectMode.NEW))
        }

    /** A solid block of ink across [bounds], accumulated on the scratch. */
    private fun inkAcross(bounds: Bounds, color: Int = Color.BLACK) {
        scratch.begin(bounds)
        val canvas = scratch.canvasInDocSpace() ?: error("the scratch would not open")
        canvas.drawRect(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            Paint().apply { this.color = color; isAntiAlias = false },
        )
    }

    private fun frame(): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    // --- the confinement ----------------------------------------------------

    @Test
    fun `ink outside the stencil never reaches the sheet`() {
        val document = document()
        val stencil = selection(10f, 10f, 30f, 30f)
        inkAcross(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
        scratch.maskBy(stencil.maskBitmap()!!)

        document.layer.write { scratch.compositeInto(it, 1f) }

        assertEquals(Color.BLACK, pixel(document, 20, 20), "inside the stencil")
        assertEquals(0, pixel(document, 50, 20), "outside it, still transparent")
        assertEquals(0, pixel(document, 20, 35), "and below it")
    }

    @Test
    fun `the stencil's soft edge survives into the sheet`() {
        // The whole reason this is an ALPHA_8 mask and not a clip. A hard edge
        // here would mean the stencil had become a clip with extra steps.
        val document = document()
        val stencil = Selection(w, h).also { sel = it }
        val oval = Path().apply { addOval(12f, 8f, 52f, 32f, Path.Direction.CW) }
        stencil.apply(SelectOp.Shape(oval, SelectMode.NEW))
        inkAcross(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
        scratch.maskBy(stencil.maskBitmap()!!)

        document.layer.write { scratch.compositeInto(it, 1f) }

        var soft = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = Color.alpha(pixel(document, x, y))
                if (a in 1..254) soft++
            }
        }
        assertTrue(soft > 0, "the stencil's rim landed as a hard edge")
    }

    @Test
    fun `the wet pass and the commit put down the same edge`() {
        // The stop condition, as an assertion. Both passes read the same
        // buffer, so the only way they can differ is if one of them clips.
        val document = document()
        val stencil = Selection(w, h).also { sel = it }
        val oval = Path().apply { addOval(12f, 8f, 52f, 32f, Path.Direction.CW) }
        stencil.apply(SelectOp.Shape(oval, SelectMode.NEW))
        inkAcross(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
        scratch.maskBy(stencil.maskBitmap()!!)

        // The wet pass: paint the buffer onto the frame without consuming it.
        val (wetFrame, wetCanvas) = frame()
        scratch.drawOnto(wetCanvas, 1f)

        // The commit: the same buffer, into the sheet.
        document.layer.write { scratch.compositeInto(it, 1f) }
        val (dryFrame, dryCanvas) = frame()
        document.layer.read { dryCanvas.drawBitmap(it, 0f, 0f, null) }

        val wet = IntArray(w * h)
        val dry = IntArray(w * h)
        wetFrame.getPixels(wet, 0, w, 0, 0, w, h)
        dryFrame.getPixels(dry, 0, w, 0, 0, w, h)
        var differing = 0
        for (i in wet.indices) if (wet[i] != dry[i]) differing++
        assertEquals(0, differing, "the wet stroke and the committed one are not the same pixels")
    }

    @Test
    fun `a buffer entirely outside the stencil contributes nothing`() {
        val document = document()
        val stencil = selection(0f, 0f, 10f, 10f)
        inkAcross(Bounds.of(30f, 20f, 50f, 35f))
        scratch.maskBy(stencil.maskBitmap()!!)

        document.layer.write { scratch.compositeInto(it, 1f) }
        assertEquals(0, pixel(document, 40, 27), "ink landed outside the stencil")
    }

    @Test
    fun `a buffer hanging off the page is cleared where it hangs off`() {
        // The mask covers the page and nothing beyond it, so a stroke that runs
        // off the sheet has a tail with no coverage to consult. It is not
        // selected: the page is the largest thing a selection can be, and
        // `TileMode.DECAL` is what makes that fall out rather than needing
        // rectangle arithmetic.
        val document = document()
        val stencil = selection(0f, 0f, w.toFloat(), h.toFloat())
        inkAcross(Bounds.of(-20f, 10f, 20f, 30f))
        scratch.maskBy(stencil.maskBitmap()!!)

        // Read the buffer itself, because the off-page half never reaches a
        // sheet -- the layer canvas is page-sized and clips it away -- so
        // asserting through the sheet would pass whether or not it was cleared.
        val buffer = scratch.peekBitmap()!!
        val offPage = -10 - scratch.originX
        val onPage = 5 - scratch.originX
        val row = 20 - scratch.originY
        assertEquals(0, buffer.getPixel(offPage, row), "the tail off the page")
        assertEquals(Color.BLACK, buffer.getPixel(onPage, row), "the part on it")

        document.layer.write { scratch.compositeInto(it, 1f) }
        assertEquals(Color.BLACK, pixel(document, 5, 20), "and that is what lands")
    }

    @Test
    fun `an A8 mask drawn as a bitmap cannot confine ink, which is why there is a shader`() {
        // The version that looks right and is not, carried longhand because it
        // fails in the direction that reads as success. Skia draws an ALPHA_8
        // bitmap as a *mask* -- colouring the paint through it -- and a mask
        // blit only visits pixels the mask covers. So DST_IN leaves everything
        // outside the selection exactly as it was, every pixel inside is
        // correct, and the symptom is "the selection does nothing" rather than
        // anything that looks like a blend bug.
        val stencil = selection(10f, 10f, 30f, 30f)
        val mask = stencil.maskBitmap()!!

        val ink = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(ink)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            mask,
            0f,
            0f,
            Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                isAntiAlias = false
                isFilterBitmap = false
            },
        )
        assertEquals(Color.BLACK, ink.getPixel(20, 20), "inside is right, which is the trap")
        assertEquals(
            Color.BLACK,
            ink.getPixel(50, 20),
            "if this ever starts clearing, `ScratchLayer.maskBy` can drop its shader",
        )
    }

    @Test
    fun `erasing is confined by the stencil too`() {
        // It falls out rather than being implemented: a masked buffer
        // subtracted with DST_OUT can only subtract where the mask let it
        // through.
        val document = document()
        document.layer.write { it.drawColor(Color.BLACK) }
        val stencil = selection(10f, 10f, 30f, 30f)
        inkAcross(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
        scratch.maskBy(stencil.maskBitmap()!!)

        document.layer.write { scratch.compositeInto(it, 1f, erase = true) }

        assertEquals(0, pixel(document, 20, 20), "rubbed out inside the stencil")
        assertEquals(Color.BLACK, pixel(document, 50, 20), "and left alone outside it")
    }

    // --- clear --------------------------------------------------------------

    @Test
    fun `clear inside a stencil leaves the outside alone`() {
        val document = document()
        document.layer.write { it.drawColor(Color.BLACK) }
        val stencil = selection(10f, 10f, 30f, 30f)

        document.layer.blank(stencil.maskBitmap()!!)

        assertEquals(0, pixel(document, 20, 20), "inside")
        assertEquals(Color.BLACK, pixel(document, 50, 20), "outside")
    }

    // --- undo ---------------------------------------------------------------

    @Test
    fun `an undo snapshot is narrowed to the stencil`() {
        // A stroke across the page with a small stencil on it can only have
        // changed pixels inside that stencil, so the rectangle worth keeping is
        // the overlap. Measured as bytes rather than asserted structurally,
        // because bytes are what the 48 MB history budget is spent in.
        val wide = document()
        wide.snapshotBeforeStroke(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
        val unconfined = wide.historyBytes

        val narrow = Document(w, h, Color.WHITE, enforceOffMainThread = false)
        try {
            narrow.selection.apply(
                SelectOp.Shape(
                    Path().apply { addRect(10f, 10f, 20f, 20f, Path.Direction.CW) },
                    SelectMode.NEW,
                ),
            )
            narrow.snapshotBeforeStroke(Bounds.of(0f, 0f, w.toFloat(), h.toFloat()))
            assertTrue(
                narrow.historyBytes < unconfined,
                "the stencil did not narrow the patch: $unconfined vs ${narrow.historyBytes}",
            )
            assertTrue(narrow.canUndo, "and it is still a step that can be walked back")
        } finally {
            narrow.close()
        }
    }

    @Test
    fun `a stroke entirely outside the stencil records no undo step`() {
        // Nothing changed, so there is nothing to walk back. An entry that
        // restores nothing is an undo press that appears to do nothing.
        val document = document()
        document.selection.apply(
            SelectOp.Shape(
                Path().apply { addRect(0f, 0f, 10f, 10f, Path.Direction.CW) },
                SelectMode.NEW,
            ),
        )
        document.snapshotBeforeStroke(Bounds.of(40f, 25f, 55f, 35f))
        assertTrue(!document.canUndo, "a stroke that could not have painted recorded a step")
    }

    private fun pixel(document: Document, x: Int, y: Int): Int {
        var c = 0
        document.layer.read { c = it.getPixel(x, y) }
        return c
    }
}
