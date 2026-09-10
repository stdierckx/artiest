package be.thalos.artiest.doc

import android.graphics.Color
import android.graphics.Paint
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Undo, once there is more than one sheet to undo onto.
 *
 * This is the property that makes the layer stack safe rather than merely
 * present. With one sheet, "put these pixels back" has one possible meaning;
 * with five, a patch that restores onto *the active sheet* rather than onto the
 * sheet it came from silently copies one drawing onto another — and the two
 * ways to get there are both natural to write. So the id lives on the patch and
 * this file is what says so.
 *
 * The sink below is `InkSurfaceView`'s commit sink with the rasterizer replaced
 * by a rectangle: the ordering, the snapshot-before-write and the thread are
 * the parts under test, and a real dab rasterizer would only make the assertion
 * about a colour harder to read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerUndoTest {

    private fun document() = Document(32, 32, enforceOffMainThread = false)

    /** A stroke that paints one flat square, so the pixel test reads as a colour. */
    private fun square(colour: Int): Stroke = Stroke.copyOf(
        dabs = floatArrayOf(16f, 16f, 6f, 1f, 0f, 1f),
        dabCount = 1,
        colorArgb = colour,
        antiAlias = false,
        bounds = Bounds.of(10f, 10f, 22f, 22f),
    )

    private fun drain(doc: Document) {
        doc.drainCommits(object : CommitQueue.Sink {
            override fun onStroke(stroke: Stroke) {
                doc.snapshotBeforeStroke(stroke.bounds)
                val paint = Paint().apply {
                    color = stroke.colorArgb
                    isAntiAlias = false
                }
                doc.layer.write { it.drawRect(10f, 10f, 22f, 22f, paint) }
            }

            override fun onClear() {
                doc.snapshotBeforeClear()
                doc.layer.blank()
            }

            override fun onUndo() {
                doc.applyUndo()
            }

            override fun onRedo() {
                doc.applyRedo()
            }

            override fun onLayers(op: LayerOp) {
                doc.layers.apply(op)
            }

            override fun onSelect(op: SelectOp) {
                doc.selection.apply(op)
            }
        })
    }

    private fun pixel(layer: Layer, x: Int, y: Int): Int {
        var c = 0
        layer.read { c = it.getPixel(x, y) }
        return c
    }

    /**
     * **The one that would silently destroy work.** Draw on the bottom sheet,
     * move up, draw again, then undo twice: each press has to clear the sheet
     * that press's stroke went onto, and neither may touch the other.
     */
    @Test
    fun `undo puts the pixels back on the sheet they came from`() {
        val doc = document()
        val bottom = doc.layers.activeId

        doc.commitStroke(square(Color.RED))
        drain(doc)

        doc.requestLayers(LayerOp.Add(doc.newLayer(), "top"))
        drain(doc)
        val top = doc.layers.activeId
        assertTrue(top != bottom, "the new sheet reused the old sheet's id")

        doc.commitStroke(square(Color.BLUE))
        drain(doc)

        val bottomLayer = assertNotNull(doc.layers.byId(bottom)).layer
        val topLayer = assertNotNull(doc.layers.byId(top)).layer
        assertEquals(Color.RED, pixel(bottomLayer, 16, 16))
        assertEquals(Color.BLUE, pixel(topLayer, 16, 16))

        // One press: the blue goes, the red stays -- even though the pen is on
        // the top sheet for both presses.
        doc.requestUndo()
        drain(doc)
        assertEquals(0, pixel(topLayer, 16, 16), "the top sheet's stroke survived undo")
        assertEquals(Color.RED, pixel(bottomLayer, 16, 16), "undo reached the wrong sheet")

        // Two presses: now the red goes as well, without the pen ever moving
        // back down to it.
        doc.requestUndo()
        drain(doc)
        assertEquals(0, pixel(bottomLayer, 16, 16), "the second undo did not reach the bottom")

        // And redo walks back up the same way.
        doc.requestRedo()
        drain(doc)
        assertEquals(Color.RED, pixel(bottomLayer, 16, 16), "redo lost the bottom sheet's stroke")
        doc.requestRedo()
        drain(doc)
        assertEquals(Color.BLUE, pixel(topLayer, 16, 16), "redo lost the top sheet's stroke")

        doc.close()
    }

    /**
     * A patch recorded against a sheet that has since been deleted restores
     * nothing and is consumed anyway. The alternative is an Undo button that
     * stops working — or throws — because of a layer the user got rid of on
     * purpose.
     */
    @Test
    fun `undo walks over a deleted sheet rather than stopping on it`() {
        val doc = document()
        val bottom = doc.layers.activeId

        doc.commitStroke(square(Color.RED))
        drain(doc)
        doc.requestLayers(LayerOp.Add(doc.newLayer(), "top"))
        drain(doc)
        val top = doc.layers.activeId
        doc.commitStroke(square(Color.BLUE))
        drain(doc)

        doc.requestLayers(LayerOp.Delete(top))
        drain(doc)

        doc.requestUndo()
        drain(doc)
        // That press belonged to the deleted sheet, so nothing visible
        // happened. The next one has to reach the red.
        val bottomLayer = assertNotNull(doc.layers.byId(bottom)).layer
        assertEquals(Color.RED, pixel(bottomLayer, 16, 16))
        doc.requestUndo()
        drain(doc)
        assertEquals(0, pixel(bottomLayer, 16, 16), "undo stopped on the deleted sheet")

        doc.close()
    }

    /** Clear empties the sheet the pen is on and leaves the others alone. */
    @Test
    fun `clear empties only the active sheet`() {
        val doc = document()
        val bottom = doc.layers.activeId
        doc.commitStroke(square(Color.RED))
        drain(doc)
        doc.requestLayers(LayerOp.Add(doc.newLayer(), "top"))
        drain(doc)
        doc.commitStroke(square(Color.BLUE))
        drain(doc)

        doc.requestClear()
        drain(doc)

        assertEquals(0, pixel(doc.layer, 16, 16), "the active sheet was not cleared")
        assertEquals(
            Color.RED,
            pixel(assertNotNull(doc.layers.byId(bottom)).layer, 16, 16),
            "Clear reached a sheet the pen was not on",
        )
        doc.close()
    }

    @Test
    fun `closing the document releases every sheet`() {
        val doc = document()
        doc.requestLayers(LayerOp.Add(doc.newLayer(), "two"))
        doc.requestLayers(LayerOp.Add(doc.newLayer(), "three"))
        drain(doc)
        val all = (0 until doc.layers.size).map { doc.layers.entryAt(it).layer }
        assertEquals(3, all.size)
        doc.close()
        for (layer in all) assertFalse(layer.isOpen, "a sheet outlived the document")
    }

    /**
     * A pending Add is holding a full-page bitmap the UI thread allocated. The
     * view going away must release it, or every press that was in flight at
     * teardown costs 27.19 MiB that nothing will ever free.
     */
    @Test
    fun `abandoning the queue releases a layer that was still on its way`() {
        val doc = document()
        val waiting = doc.newLayer()
        doc.requestLayers(LayerOp.Add(waiting, "never arrives"))
        doc.abandonCommits()
        assertFalse(waiting.isOpen, "the abandoned layer's pixels were leaked")
        doc.close()
    }
}
