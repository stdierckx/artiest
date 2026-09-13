package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving pixels around: lift, drag, drop, and the two things that make this
 * design worth having.
 *
 * The first is that **the lift does not touch the sheet**. Everything else
 * follows from it — cancel costs nothing, a move is one press of undo, and
 * twenty nudges resample once — and every one of those is a test below rather
 * than a claim in a header.
 *
 * The second is the resampling. `LayerStack.buildThumbnail` shipped with a
 * single filtered `drawBitmap` at 26:1 and came back blank, because a bilinear
 * filter samples a 2x2 neighbourhood however far apart the taps are. A float
 * scaled down has exactly that problem, and `a float shrunk past half is halved
 * rather than sampled` carries the one-step version as a control so it cannot
 * pass by accident.
 *
 * The three annotations are load-bearing: see `ScratchLayerTest`'s header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingPixelsTest {

    private val w = 64
    private val h = 48

    private var doc: Document? = null

    @After
    fun tearDown() {
        doc?.close()
    }

    private fun document(): Document =
        Document(w, h, Color.WHITE, enforceOffMainThread = false).also { doc = it }

    /** A solid block on the active sheet, and a rectangular stencil over part of it. */
    private fun inked(document: Document, color: Int = Color.BLACK) {
        document.layer.write { it.drawColor(color) }
    }

    private fun selectRect(document: Document, l: Float, t: Float, r: Float, b: Float) {
        document.selection.apply(
            SelectOp.Shape(
                Path().apply { addRect(l, t, r, b, Path.Direction.CW) },
                SelectMode.NEW,
            ),
        )
    }

    private fun pixel(document: Document, x: Int, y: Int): Int {
        var c = 0
        document.layer.read { c = it.getPixel(x, y) }
        return c
    }

    private fun translated(dx: Float, dy: Float) = Matrix().apply { setTranslate(dx, dy) }

    /** A solid block on the active sheet, for the tests that need a shape with a side. */
    private fun block(document: Document, l: Int, t: Int, r: Int, b: Int) {
        document.layer.write { canvas ->
            canvas.drawRect(
                l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(),
                Paint().apply { color = Color.BLACK },
            )
        }
    }

    // --- the lift -----------------------------------------------------------

    @Test
    fun `a lift takes the selected pixels and leaves the sheet alone`() {
        // The whole design in one assertion. Everything else this class does
        // cheaply -- a free cancel, a single undo step -- rests on this.
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 30f, 30f)

        assertTrue(document.applyFloat(FloatOp.LiftSelection))
        val float = assertNotNull(document.floating)

        assertEquals(Color.BLACK, pixel(document, 20, 20), "the source is untouched")
        assertEquals(Color.BLACK, float.pixels.getPixel(10, 10), "and the pixels came across")
        assertEquals(20, float.sourceBounds.width())
    }

    @Test
    fun `a lift with nothing selected is refused`() {
        val document = document()
        inked(document)
        assertFalse(document.applyFloat(FloatOp.LiftSelection))
        assertNull(document.floating)
    }

    @Test
    fun `a second lift is refused while one is in the air`() {
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 30f, 30f)
        assertTrue(document.applyFloat(FloatOp.LiftSelection))
        assertFalse(document.applyFloat(FloatOp.LiftSelection))
    }

    @Test
    fun `a lifted selection carries the stencil's shape, not its bounding box`() {
        // Masked on the way out, or a moved ellipse arrives as a rectangle.
        val document = document()
        inked(document)
        document.selection.apply(
            SelectOp.Shape(
                Path().apply { addOval(8f, 8f, 40f, 40f, Path.Direction.CW) },
                SelectMode.NEW,
            ),
        )
        document.applyFloat(FloatOp.LiftSelection)
        val float = assertNotNull(document.floating)
        val bounds = float.sourceBounds
        assertEquals(0, Color.alpha(float.pixels.getPixel(0, 0)), "the corner of the box is empty")
        assertEquals(
            255,
            Color.alpha(float.pixels.getPixel(bounds.width() / 2, bounds.height() / 2)),
            "and the middle is not",
        )
    }

    @Test
    fun `lifting the whole sheet needs no selection`() {
        // The layer transform, which is the same object with no stencil.
        val document = document()
        inked(document)
        assertTrue(document.applyFloat(FloatOp.LiftLayer))
        val float = assertNotNull(document.floating)
        assertEquals(w, float.sourceBounds.width())
        assertNull(float.mask, "a whole-sheet lift has no stencil")
    }

    // --- the copy -----------------------------------------------------------

    @Test
    fun `a copy leaves the original where it was`() {
        // Duplicate, and the whole of it is one flag. The lift already leaves
        // the sheet alone; a copy is the same lift that never punches the hole.
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 20f, 20f)

        assertTrue(document.applyFloat(FloatOp.CopySelection))
        assertTrue(assertNotNull(document.floating).keepSource)
        document.applyFloat(FloatOp.Move(translated(24f, 0f)))
        assertTrue(document.applyFloat(FloatOp.Drop))

        assertEquals(Color.BLACK, pixel(document, 15, 15), "the original stayed")
        assertEquals(Color.BLACK, pixel(document, 39, 15), "and the copy landed")
    }

    @Test
    fun `a copy with nothing selected is refused`() {
        val document = document()
        inked(document)
        assertFalse(document.applyFloat(FloatOp.CopySelection))
        assertNull(document.floating)
    }

    @Test
    fun `a copy is still one press of undo`() {
        // A block rather than a full sheet, so "the copy is gone" is a real
        // reading: on an inked sheet every pixel is black already and the
        // assertion would pass with the copy still sitting there.
        val document = document()
        block(document, 10, 10, 20, 20)
        selectRect(document, 10f, 10f, 20f, 20f)
        document.applyFloat(FloatOp.CopySelection)
        document.applyFloat(FloatOp.Move(translated(24f, 0f)))
        document.applyFloat(FloatOp.Drop)

        assertEquals(1, document.undoDepth)
        assertTrue(document.applyUndo())
        assertEquals(0, Color.alpha(pixel(document, 39, 15)), "the copy is gone")
        assertEquals(Color.BLACK, pixel(document, 15, 15), "the original never moved")
    }

    // --- the flip -----------------------------------------------------------

    @Test
    fun `a flip mirrors the pixels and leaves them where they were`() {
        // A bar down the left of the selection comes back down the right,
        // inside the same rectangle. Mirroring about the middle of the *page*
        // instead would throw it across the drawing, which is the failure this
        // is written against.
        val document = document()
        block(document, 10, 10, 14, 30)
        selectRect(document, 10f, 10f, 30f, 30f)

        assertTrue(document.applyFloat(FloatOp.Flip(across = true)))
        assertTrue(document.applyFloat(FloatOp.Drop))

        assertEquals(0, Color.alpha(pixel(document, 12, 20)), "the left is empty")
        assertEquals(Color.BLACK, pixel(document, 28, 20), "and the bar is on the right")
    }

    @Test
    fun `a flip down mirrors the other way`() {
        val document = document()
        block(document, 10, 10, 30, 14)
        selectRect(document, 10f, 10f, 30f, 30f)

        document.applyFloat(FloatOp.Flip(across = false))
        document.applyFloat(FloatOp.Drop)

        assertEquals(0, Color.alpha(pixel(document, 20, 12)), "the top is empty")
        assertEquals(Color.BLACK, pixel(document, 20, 28), "and the bar is at the bottom")
    }

    @Test
    fun `flipping twice is where you started`() {
        val document = document()
        block(document, 10, 10, 14, 30)
        selectRect(document, 10f, 10f, 30f, 30f)

        document.applyFloat(FloatOp.Flip(across = true))
        document.applyFloat(FloatOp.Flip(across = true))
        document.applyFloat(FloatOp.Drop)

        assertEquals(Color.BLACK, pixel(document, 12, 20), "back on the left")
        assertEquals(0, Color.alpha(pixel(document, 28, 20)), "and nothing on the right")
    }

    @Test
    fun `a flip lifts for itself, and only once`() {
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 30f, 30f)

        assertNull(document.floating)
        assertTrue(document.applyFloat(FloatOp.Flip(across = true)))
        val float = assertNotNull(document.floating)
        assertTrue(document.applyFloat(FloatOp.Flip(across = false)))
        assertTrue(float === document.floating, "the second flip turned the same pixels")
    }

    @Test
    fun `a flip with nothing selected and nothing floating is refused`() {
        val document = document()
        inked(document)
        assertFalse(document.applyFloat(FloatOp.Flip(across = true)))
        assertNull(document.floating)
    }

    @Test
    fun `a flip after a drag turns the pixels where they are now`() {
        // The mirror goes on *before* what the user has already done, so a
        // float that has been dragged flips about its own axis rather than
        // jumping back to where it was lifted from.
        val document = document()
        block(document, 10, 10, 14, 30)
        selectRect(document, 10f, 10f, 30f, 30f)
        document.applyFloat(FloatOp.LiftSelection)
        document.applyFloat(FloatOp.Move(translated(20f, 0f)))
        document.applyFloat(FloatOp.Flip(across = true))
        document.applyFloat(FloatOp.Drop)

        assertEquals(Color.BLACK, pixel(document, 48, 20), "the bar moved and mirrored")
        assertEquals(0, Color.alpha(pixel(document, 32, 20)), "and left its old side empty")
    }

    // --- the cancel ---------------------------------------------------------

    @Test
    fun `cancelling a float costs nothing and undoes nothing`() {
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 30f, 30f)
        document.applyFloat(FloatOp.LiftSelection)
        document.applyFloat(FloatOp.Move(translated(15f, 0f)))

        assertTrue(document.applyFloat(FloatOp.Cancel))
        assertNull(document.floating)
        assertEquals(Color.BLACK, pixel(document, 20, 20), "the source never moved")
        assertFalse(document.canUndo, "and there is nothing to walk back")
    }

    // --- the drop -----------------------------------------------------------

    @Test
    fun `a drop moves the pixels and leaves a hole behind`() {
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 20f, 20f)
        document.applyFloat(FloatOp.LiftSelection)
        document.applyFloat(FloatOp.Move(translated(24f, 0f)))

        assertTrue(document.applyFloat(FloatOp.Drop))
        assertNull(document.floating)
        assertEquals(0, pixel(document, 15, 15), "where they were")
        assertEquals(Color.BLACK, pixel(document, 39, 15), "where they went")
        assertEquals(Color.BLACK, pixel(document, 50, 30), "and nothing else moved")
    }

    @Test
    fun `a move is one press of undo`() {
        // Two patches -- one for the hole, one for the arrival -- would be two
        // presses for one gesture, which is not what the hand did.
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 20f, 20f)
        document.applyFloat(FloatOp.LiftSelection)
        document.applyFloat(FloatOp.Move(translated(24f, 0f)))
        document.applyFloat(FloatOp.Drop)

        assertEquals(1, document.undoDepth)
        assertTrue(document.applyUndo())
        assertEquals(Color.BLACK, pixel(document, 15, 15), "the pixels are back")
        assertEquals(Color.BLACK, pixel(document, 39, 15), "and so is what was under them")
        assertFalse(document.canUndo)
    }

    @Test
    fun `a drop onto a sheet that has been deleted writes nothing`() {
        // The float holds a layer id and never an index, for the reason
        // `PixelPatch` does. A sheet deleted while pixels are in the air is an
        // ordinary outcome of an asynchronous queue, not an error.
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 20f, 20f)
        document.applyFloat(FloatOp.LiftSelection)

        val gone = document.layers.active.id
        document.layers.apply(LayerOp.Add(document.newLayer(), "second"))
        document.layers.apply(LayerOp.Delete(gone))

        assertTrue(document.applyFloat(FloatOp.Drop))
        assertNull(document.floating)
        assertFalse(document.canUndo, "nothing was written, so nothing is undoable")
    }

    @Test
    fun `a rotated drop lands rotated`() {
        val document = document()
        document.layer.write {
            it.drawRect(10f, 10f, 30f, 14f, Paint().apply { color = Color.BLACK; isAntiAlias = false })
        }
        selectRect(document, 8f, 8f, 32f, 20f)
        document.applyFloat(FloatOp.LiftSelection)
        // A quarter turn about the middle of the lifted box.
        document.applyFloat(
            FloatOp.Move(Matrix().apply { setRotate(90f, 20f, 14f) }),
        )
        document.applyFloat(FloatOp.Drop)

        // The bar was horizontal and is now vertical through the same centre.
        assertEquals(0, Color.alpha(pixel(document, 28, 12)), "the old end of the bar is empty")
        assertTrue(Color.alpha(pixel(document, 20, 20)) > 100, "and the bar runs down the middle")
    }

    @Test
    fun `the stencil follows the pixels to where they landed`() {
        // A moved selection takes its outline with it, because the outline *is*
        // the selection. Leaving the ants around the hole is not where the user
        // is looking and is not what any editor does.
        val document = document()
        inked(document)
        selectRect(document, 10f, 10f, 20f, 20f)
        document.applyFloat(FloatOp.LiftSelection)
        document.applyFloat(FloatOp.Move(translated(24f, 0f)))
        document.applyFloat(FloatOp.Drop)

        assertTrue(document.selection.active)
        assertEquals(34, document.selection.bounds.left)
        assertEquals(44, document.selection.bounds.right)
    }

    @Test
    fun `a whole-sheet transform leaves the stencil alone`() {
        // There is none to move, and `transformBy` has to say so rather than
        // inventing one.
        val document = document()
        inked(document)
        document.applyFloat(FloatOp.LiftLayer)
        document.applyFloat(FloatOp.Move(translated(4f, 0f)))
        document.applyFloat(FloatOp.Drop)
        assertFalse(document.selection.active)
    }

    // --- the resampling -----------------------------------------------------

    @Test
    fun `a float shrunk past half is halved rather than sampled`() {
        // The thumbnail bug, in a new place, with its control. A bilinear
        // filter samples a 2x2 neighbourhood however far apart the taps are, so
        // a thin line falls between them and vanishes. Halving is the fix,
        // because at 2:1 the neighbourhood *is* the pixels being merged.
        val big = 256
        val document = Document(big, big, Color.WHITE, enforceOffMainThread = false)
        try {
            val hair = Paint().apply {
                color = Color.BLACK
                isAntiAlias = false
                strokeWidth = 1f
            }
            document.layer.write { canvas ->
                for (y in 0 until big step 8) {
                    canvas.drawRect(0f, y.toFloat(), big.toFloat(), y + 1f, hair)
                }
            }
            document.selection.apply(
                SelectOp.Shape(
                    Path().apply { addRect(0f, 0f, big.toFloat(), big.toFloat(), Path.Direction.CW) },
                    SelectMode.NEW,
                ),
            )
            document.applyFloat(FloatOp.LiftSelection)
            val float = assertNotNull(document.floating)

            // The control: the same reduction in one filtered step.
            val oneStep = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
            Canvas(oneStep).drawBitmap(
                float.pixels,
                Matrix().apply { setScale(SHRINK, SHRINK) },
                Paint().apply { isFilterBitmap = true; isAntiAlias = true },
            )

            document.applyFloat(FloatOp.Move(Matrix().apply { setScale(SHRINK, SHRINK) }))
            document.applyFloat(FloatOp.Drop)

            // Total coverage, not a pixel count. A correct 1:8 reduction of a
            // pattern that is one part in eight black gives every output pixel
            // an eighth of an alpha; a bilinear step at 1:8 lands most of its
            // taps between the lines and keeps almost none of it. Summing the
            // alpha is the invariant either way: area is conserved by
            // averaging and thrown away by point-pair sampling.
            val halved = inkIn(document, (big * SHRINK).toInt())
            val naive = inkIn(oneStep, (big * SHRINK).toInt())
            assertTrue(
                halved > naive * 2,
                "halving kept $halved of the ink, one filtered step kept $naive",
            )
        } finally {
            document.close()
        }
    }

    /** Total alpha in the top-left [size] square: how much ink survived. */
    private fun inkIn(document: Document, size: Int): Int {
        var found = 0
        document.layer.read { bitmap -> found = inkIn(bitmap, size) }
        return found
    }

    private fun inkIn(bitmap: Bitmap, size: Int): Int {
        var found = 0
        for (y in 0 until size) {
            for (x in 0 until size) found += Color.alpha(bitmap.getPixel(x, y))
        }
        return found
    }

    private companion object {
        /** An eighth, which is three halvings and well past where one step fails. */
        const val SHRINK = 0.125f
    }
}
