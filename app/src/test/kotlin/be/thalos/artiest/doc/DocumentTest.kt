package be.thalos.artiest.doc

import android.graphics.Color
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.MutableBounds
import be.thalos.artiest.engine.ink.Stroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric because a `Document` allocates a `Layer`, and NATIVE at SDK 34 for
 * the reason `LayerTest`'s KDoc gives at length: the default shadow records draw
 * calls instead of rasterizing them, and every pixel assertion below would pass
 * against a canvas that drew nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentTest {

    @Test
    fun `a default document is 3300x2160 landscape and its layer agrees`() {
        val doc = Document(enforceOffMainThread = false)
        assertEquals(3300, doc.widthPx)
        assertEquals(2160, doc.heightPx)
        assertTrue(doc.widthPx > doc.heightPx, "the default page is landscape, like the tablet")
        assertEquals(doc.widthPx, doc.layer.widthPx)
        assertEquals(doc.heightPx, doc.layer.heightPx)
        doc.close()
    }

    @Test
    fun `a zero or negative extent is refused here rather than inside createBitmap`() {
        // The message is the whole assertion. Bitmap.createBitmap throws
        // IllegalArgumentException for these dimensions too, so asserting only
        // the type would stay green with Document's `require` deleted — which is
        // exactly the mutation this test is named for.
        assertEquals(
            "document was 0x3300",
            assertFailsWith<IllegalArgumentException> { Document(0, 3300) }.message,
        )
        assertEquals(
            "document was 2160x-1",
            assertFailsWith<IllegalArgumentException> { Document(2160, -1) }.message,
        )
    }

    /**
     * The invariant restated at the level that owns the paper colour: a document
     * whose paper is opaque white still has a completely transparent layer.
     */
    @Test
    fun `the paper colour is not in the layer`() {
        val doc = Document(64, 64, Color.WHITE, enforceOffMainThread = false)
        assertEquals(Color.WHITE, doc.paperColor)
        assertTrue(
            doc.layer.read { bmp ->
                assertEquals(0, bmp.getPixel(0, 0))
                assertEquals(0, bmp.getPixel(32, 32))
            },
        )
        doc.close()
    }

    /**
     * Phase 1's second forward obligation: one rectangle per committed stroke,
     * in document space, and nothing else about the stroke.
     */
    @Test
    fun `a document retains one bounds per committed stroke in order`() {
        val doc = Document(64, 64, enforceOffMainThread = false)
        assertEquals(0, doc.strokeCount)

        val first = boundsOf(10f, 10f, 2f)
        val second = boundsOf(40f, 30f, 3f)
        doc.recordStroke(first)
        doc.recordStroke(second)

        assertEquals(2, doc.strokeCount)
        assertEquals(first, doc.strokeBoundsAt(0))
        assertEquals(second, doc.strokeBoundsAt(1))
        doc.close()
    }

    /**
     * A stroke that painted nothing has no region for undo to restore, so an
     * index that undo cannot act on never enters the history.
     */
    @Test
    fun `an empty bounds is not a committed stroke`() {
        val doc = Document(64, 64, enforceOffMainThread = false)
        assertFailsWith<IllegalArgumentException> { doc.recordStroke(Bounds.EMPTY) }
        assertEquals(0, doc.strokeCount)
        doc.close()
    }

    @Test
    fun `forgetting strokes empties the history the clear button just erased`() {
        val doc = Document(64, 64, enforceOffMainThread = false)
        doc.recordStroke(boundsOf(10f, 10f, 2f))
        doc.forgetStrokes()
        assertEquals(0, doc.strokeCount)
        doc.close()
    }

    /**
     * The retained bounds is a document-space rectangle a page-sized clip
     * accepts, which is the shape W8 and Phase 3 will ask it for.
     */
    @Test
    fun `a retained bounds converts against the document's own extent`() {
        val doc = Document(64, 64, enforceOffMainThread = false)
        doc.recordStroke(boundsOf(10f, 10f, 2.5f))

        val rect = IntArray(4)
        assertTrue(doc.strokeBoundsAt(0).toPixelRect(rect, doc.widthPx, doc.heightPx))
        // floor(7.5), floor(7.5), ceil(12.5), ceil(12.5).
        assertEquals(listOf(7, 7, 13, 13), rect.toList())
        doc.close()
    }

    @Test
    fun `closing the document closes its layer`() {
        val doc = Document(64, 64, enforceOffMainThread = false)
        assertTrue(doc.layer.isOpen)
        doc.close()
        assertFalse(doc.layer.isOpen)
        assertFalse(doc.layer.read { })
    }

    private fun boundsOf(x: Float, y: Float, radius: Float): Bounds =
        MutableBounds().apply { add(x, y, radius) }.snapshot()
    private fun oneDabStroke(x: Float): Stroke = Stroke.copyOf(
        dabs = floatArrayOf(x, 10f, 2f, 1f, 0f, 1f),
        dabCount = 1,
        colorArgb = Color.BLACK,
        antiAlias = true,
        bounds = Bounds.of(x - 2f, 8f, x + 2f, 12f),
    )

    private class Applied : CommitQueue.Sink {
        val log = StringBuilder()
        override fun onStroke(stroke: Stroke) {
            log.append('S')
        }

        override fun onClear() {
            log.append('C')
        }

        override fun onUndo() {
            log.append('U')
        }

        override fun onRedo() {
            log.append('R')
        }
    }

    @Test
    fun `a commit records the bounds and queues the pixels together`() {
        // The pair is the invariant: a stroke counted in the history but never
        // queued is ink the document claims and the layer does not have, and
        // through W8 that was reachable — the view dropped the pending stroke
        // when the surface was gone, after recordStroke had already run.
        val doc = Document(enforceOffMainThread = false)
        assertTrue(doc.commitStroke(oneDabStroke(100f)))
        assertEquals(1, doc.strokeCount)
        assertEquals(1, doc.pendingCommits)

        val applied = Applied()
        assertEquals(1, doc.drainCommits(applied))
        assertEquals("S", applied.log.toString())
        assertEquals(1, doc.strokeCount, "draining the pixels dropped the history")
        assertEquals(0, doc.pendingCommits)
    }

    @Test
    fun `a stroke that painted nothing is neither recorded nor queued`() {
        val doc = Document(enforceOffMainThread = false)
        val empty = Stroke.copyOf(
            dabs = FloatArray(0),
            dabCount = 0,
            colorArgb = Color.BLACK,
            antiAlias = true,
            bounds = Bounds.EMPTY,
        )
        assertFalse(doc.commitStroke(empty))
        assertEquals(0, doc.strokeCount)
        assertEquals(0, doc.pendingCommits, "an empty stroke queued a blank pass over the layer")
    }

    @Test
    fun `a clear queues behind the stroke it follows and forgets the history at once`() {
        val doc = Document(enforceOffMainThread = false)
        doc.commitStroke(oneDabStroke(100f))
        doc.requestClear()
        // The history is the UI thread's and goes immediately; the pixels are
        // queued behind the stroke, so the blank cannot overtake it.
        assertEquals(0, doc.strokeCount)
        assertEquals(2, doc.pendingCommits)

        val applied = Applied()
        doc.drainCommits(applied)
        assertEquals("SC", applied.log.toString())
    }

    @Test
    fun `closing a document drops what it was never going to draw`() {
        val doc = Document(enforceOffMainThread = false)
        doc.commitStroke(oneDabStroke(100f))
        doc.close()
        assertEquals(0, doc.pendingCommits)
        val applied = Applied()
        assertEquals(0, doc.drainCommits(applied))
    }

}
