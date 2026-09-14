package be.thalos.artiest.doc

import android.graphics.Color
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The picker, asserted as pixels.
 *
 * The whole claim `ColourProbe` makes is *"it answers with what the eye sees"*,
 * and the reason it composes rather than reading a sheet is that the two give
 * different answers in three cases that a user meets on their first afternoon:
 * a blank sheet over a drawing, a sheet at half opacity, and a sheet that is
 * switched off. One test each below.
 *
 * The three annotations are load-bearing, for `StackCompositorTest`'s reason:
 * without NATIVE graphics at SDK 34 every `getPixel` returns 0 and this file
 * would pass while measuring nothing. `the paper answers on an empty page` is
 * the tripwire — it asserts a non-zero colour on a page nothing has touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColourProbeTest {

    private var doc: Document? = null

    @After
    fun tearDown() {
        doc?.close()
    }

    private fun docOf(paper: Int = Color.WHITE): Document =
        Document(8, 4, paper, enforceOffMainThread = false).also { doc = it }

    /** A fresh sheet above the active one, filled with [color]. */
    private fun Document.sheet(color: Int): LayerStack.Entry {
        val layer = layers.newLayer()
        layer.write { it.drawColor(color) }
        layers.apply(LayerOp.Add(layer, "sheet"))
        return layers.active
    }

    @Test
    fun `the paper answers on an empty page`() {
        val d = docOf(Color.WHITE)
        assertEquals(Color.WHITE, ColourProbe().at(d, 2, 2))
    }

    @Test
    fun `a blank sheet over a drawing does not hide it`() {
        val d = docOf()
        d.sheet(Color.RED)
        // The one the pen is on, and there is nothing on it. Reading the active
        // sheet would answer transparent; the eye sees red.
        d.sheet(Color.TRANSPARENT)
        assertEquals(Color.RED, ColourProbe().at(d, 2, 2))
    }

    @Test
    fun `a half-opacity sheet answers with the mix, not with its own colour`() {
        val d = docOf(Color.WHITE)
        val black = d.sheet(Color.BLACK)
        d.layers.apply(LayerOp.SetOpacity(black.id, 0.5f))
        val argb = ColourProbe().at(d, 2, 2)!!
        // Halfway between white and black, within the rounding one blend costs.
        val grey = Color.red(argb)
        assertEquals(true, grey in 120..136, "expected a mid grey, got $grey")
        assertEquals(grey, Color.green(argb))
        assertEquals(grey, Color.blue(argb))
    }

    @Test
    fun `a hidden sheet is not picked from`() {
        val d = docOf(Color.WHITE)
        val red = d.sheet(Color.RED)
        d.layers.apply(LayerOp.SetVisible(red.id, false))
        assertEquals(Color.WHITE, ColourProbe().at(d, 2, 2))
    }

    @Test
    fun `off the page is not a colour`() {
        val d = docOf()
        assertNull(ColourProbe().at(d, -1, 0))
        assertNull(ColourProbe().at(d, 0, -1))
        assertNull(ColourProbe().at(d, 8, 0))
        assertNull(ColourProbe().at(d, 0, 4))
    }

    @Test
    fun `this layer only reads the sheet in the hand`() {
        val d = docOf(Color.WHITE)
        d.sheet(Color.RED)
        d.sheet(Color.BLUE)
        assertEquals(Color.BLUE, ColourProbe().at(d, 2, 2, activeOnly = true))
        assertEquals(Color.BLUE, ColourProbe().at(d, 2, 2))
    }

    @Test
    fun `this layer only answers nothing where the sheet is empty`() {
        val d = docOf(Color.WHITE)
        d.sheet(Color.RED)
        d.sheet(Color.TRANSPARENT)
        // Not transparent black, which is what a naive read hands back and what
        // the user would then be holding as their ink colour.
        assertNull(ColourProbe().at(d, 2, 2, activeOnly = true))
    }

    @Test
    fun `a pick is opaque even from a half-painted pixel`() {
        val d = docOf(Color.WHITE)
        val sheet = d.sheet(Color.TRANSPARENT)
        sheet.layer.write { it.drawColor(0x80FF0000.toInt()) }
        val argb = ColourProbe().at(d, 2, 2, activeOnly = true)!!
        assertEquals(0xFF, Color.alpha(argb))
    }
}
