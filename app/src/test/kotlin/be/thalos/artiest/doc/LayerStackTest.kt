package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stack's rules, at the level where they can be wrong.
 *
 * Most of what this file asserts is *identity*, not arithmetic: the pen stays
 * on the sheet it was on when the sheets are reordered, an operation naming a
 * deleted sheet does nothing rather than hitting whatever took its place, and
 * a rejected add releases the pixels it was carrying. Every one of those is a
 * bug that produces a plausible-looking screen and loses work, which is exactly
 * the kind that has to be caught by a test rather than by looking.
 *
 * The three annotations are the ones `LayerTest`'s header explains: without
 * `@GraphicsMode(NATIVE)` a `Bitmap` here is the legacy shadow, every
 * `getPixel` returns zero, and the duplicate test would pass while comparing
 * nothing to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerStackTest {

    private fun stack() = LayerStack(32, 32, enforceOffMainThread = false)

    private fun paint(layer: Layer, colour: Int) {
        val p = Paint().apply { this.color = colour; isAntiAlias = false }
        layer.write { it.drawRect(4f, 4f, 12f, 12f, p) }
    }

    private fun pixel(layer: Layer, x: Int, y: Int): Int {
        var c = 0
        layer.read { c = it.getPixel(x, y) }
        return c
    }

    @Test
    fun `a new stack is one named sheet with the pen on it`() {
        val s = stack()
        assertEquals(1, s.size)
        assertEquals(1, s.snapshot.size)
        assertEquals("Layer 1", s.snapshot[0].name)
        assertEquals(s.snapshot[0].id, s.activeId)
        assertTrue(s.snapshot[0].visible)
        assertEquals(1f, s.snapshot[0].opacity)
        s.close()
    }

    /**
     * Above the sheet being worked on and not on top of the pile. A user on the
     * third of five who asks for a new layer wants it over *this* one; putting
     * it at the top puts it over work they deliberately left above.
     */
    @Test
    fun `add puts the sheet above the active one and moves the pen to it`() {
        val s = stack()
        val bottom = s.activeId
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        s.apply(LayerOp.Add(s.newLayer(), "three"))
        // The pen was on "two" when "three" was added, so "three" is in the
        // middle and not at the top.
        s.apply(LayerOp.SetActive(bottom))
        s.apply(LayerOp.Add(s.newLayer(), "four"))
        assertEquals(listOf("Layer 1", "four", "two", "three"), s.snapshot.map { it.name })
        assertEquals("four", s.snapshot.first { it.id == s.activeId }.name)
        s.close()
    }

    /**
     * The cap is memory, not taste: eight full-page sheets is 217.5 MiB. What
     * matters as much as the refusal is that the refused operation releases the
     * bitmap it was carrying — the UI thread allocated it and forgot it, so
     * anything else leaks 27.19 MiB per press of a button that appears to do
     * nothing.
     */
    @Test
    fun `a ninth sheet is refused and its pixels are released`() {
        val s = stack()
        repeat(LayerStack.MAX_LAYERS - 1) { assertTrue(s.apply(LayerOp.Add(s.newLayer(), "x"))) }
        assertEquals(LayerStack.MAX_LAYERS, s.size)
        val spare = s.newLayer()
        assertFalse(s.apply(LayerOp.Add(spare, "too many")))
        assertFalse(spare.isOpen, "the refused layer's 27 MiB was leaked")
        assertEquals(LayerStack.MAX_LAYERS, s.size)
        s.close()
    }

    @Test
    fun `delete removes the sheet and frees its pixels`() {
        val s = stack()
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        val doomed = s.active
        val pixels = doomed.layer
        assertTrue(s.apply(LayerOp.Delete(doomed.id)))
        assertEquals(1, s.size)
        assertFalse(pixels.isOpen, "the deleted sheet's pixels were not released")
        assertNull(s.byId(doomed.id))
        s.close()
    }

    /**
     * A document with no sheets has nowhere to put the next stroke, and every
     * way out of that state is worse than a button that declines.
     */
    @Test
    fun `the last sheet cannot be deleted`() {
        val s = stack()
        assertFalse(s.apply(LayerOp.Delete(s.activeId)))
        assertEquals(1, s.size)
        assertTrue(s.active.layer.isOpen)
        s.close()
    }

    @Test
    fun `duplicate copies the pixels and lands above the original`() {
        val s = stack()
        paint(s.active.layer, Color.RED)
        val from = s.activeId
        assertTrue(s.apply(LayerOp.Duplicate(from, s.newLayer(), "copy")))
        assertEquals(2, s.size)
        assertEquals(listOf("Layer 1", "copy"), s.snapshot.map { it.name })
        val copy = assertNotNull(s.byId(s.activeId))
        assertEquals("copy", copy.name)
        assertEquals(Color.RED, pixel(copy.layer, 8, 8), "the copy is blank")
        assertEquals(0, pixel(copy.layer, 20, 20), "the copy is not alpha-carrying")
        // And it is a copy: painting one must not move the other.
        paint(copy.layer, Color.BLUE)
        assertEquals(Color.RED, pixel(assertNotNull(s.byId(from)).layer, 8, 8))
        s.close()
    }

    @Test
    fun `duplicate carries the original's opacity and visibility`() {
        val s = stack()
        val from = s.activeId
        s.apply(LayerOp.SetOpacity(from, 0.4f))
        s.apply(LayerOp.SetVisible(from, false))
        s.apply(LayerOp.Duplicate(from, s.newLayer(), "copy"))
        val copy = s.snapshot.first { it.name == "copy" }
        assertEquals(0.4f, copy.opacity)
        assertFalse(copy.visible)
        s.close()
    }

    /**
     * **The one that loses work if it is wrong.** Reordering must not move the
     * pen: a user who drags a sheet down the list and then draws would be
     * drawing on whatever slid into the old position, and would not find out
     * until they hid a layer.
     */
    @Test
    fun `reordering keeps the pen on the sheet it was on`() {
        val s = stack()
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        s.apply(LayerOp.Add(s.newLayer(), "three"))
        val pen = assertNotNull(s.byId(s.activeId))
        assertEquals("three", pen.name)
        assertTrue(s.apply(LayerOp.Move(pen.id, 0)))
        assertEquals(listOf("three", "Layer 1", "two"), s.snapshot.map { it.name })
        assertSame(pen, s.active, "the pen moved to a different sheet")
        assertEquals(0, s.activePosition)
        s.close()
    }

    @Test
    fun `a move outside the stack is refused`() {
        val s = stack()
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        assertFalse(s.apply(LayerOp.Move(s.activeId, 7)))
        assertFalse(s.apply(LayerOp.Move(s.activeId, -1)))
        assertEquals(listOf("Layer 1", "two"), s.snapshot.map { it.name })
        s.close()
    }

    @Test
    fun `name, opacity and visibility reach the snapshot`() {
        val s = stack()
        val id = s.activeId
        assertTrue(s.apply(LayerOp.SetName(id, "sky")))
        assertTrue(s.apply(LayerOp.SetOpacity(id, 0.25f)))
        assertTrue(s.apply(LayerOp.SetVisible(id, false)))
        val info = s.snapshot.single()
        assertEquals("sky", info.name)
        assertEquals(0.25f, info.opacity)
        assertFalse(info.visible)
        s.close()
    }

    @Test
    fun `an opacity outside zero to one is brought back inside it`() {
        val s = stack()
        s.apply(LayerOp.SetOpacity(s.activeId, 4f))
        assertEquals(1f, s.snapshot.single().opacity)
        s.apply(LayerOp.SetOpacity(s.activeId, -1f))
        assertEquals(0f, s.snapshot.single().opacity)
        s.close()
    }

    /**
     * The queue is asynchronous by design, so an operation aimed at a sheet
     * that has since been deleted is an ordinary outcome rather than an error —
     * and it must not land on whatever sheet has taken that position.
     */
    @Test
    fun `an operation naming a deleted sheet does nothing`() {
        val s = stack()
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        val gone = s.activeId
        s.apply(LayerOp.Delete(gone))
        assertFalse(s.apply(LayerOp.SetName(gone, "ghost")))
        assertFalse(s.apply(LayerOp.SetOpacity(gone, 0.1f)))
        assertFalse(s.apply(LayerOp.Move(gone, 0)))
        assertFalse(s.apply(LayerOp.SetActive(gone)))
        val spare = s.newLayer()
        assertFalse(s.apply(LayerOp.Duplicate(gone, spare, "ghost")))
        assertFalse(spare.isOpen, "the refused duplicate leaked its pixels")
        assertEquals(listOf("Layer 1"), s.snapshot.map { it.name })
        s.close()
    }

    /**
     * Ids are identity and positions are not. A reused id would let an undo
     * patch recorded against a deleted sheet restore its pixels into a sheet
     * created afterwards, which is ink appearing in a drawing from a layer that
     * no longer exists.
     */
    @Test
    fun `ids are never reused`() {
        val s = stack()
        val seen = HashSet<Int>()
        seen.add(s.activeId)
        repeat(6) {
            s.apply(LayerOp.Add(s.newLayer(), "x"))
            assertTrue(seen.add(s.activeId), "id ${s.activeId} came round again")
            s.apply(LayerOp.Delete(s.activeId))
        }
        s.close()
    }

    @Test
    fun `the suggested name never collides with one already there`() {
        val s = stack()
        s.apply(LayerOp.SetName(s.activeId, "Layer 2"))
        val name = s.suggestName()
        assertFalse(s.snapshot.any { it.name == name }, "suggested $name, which is taken")
        s.close()
    }

    /**
     * Thumbnails are a full-page read scaled down; doing that for a panel
     * nobody is looking at would put a real cost on the commit path in exchange
     * for a picture that is never drawn. The dirty flags are kept either way,
     * so opening the panel shows the drawing as it is now.
     */
    @Test
    fun `thumbnails are not built until someone is looking`() {
        val s = stack()
        paint(s.active.layer, Color.RED)
        s.touchAll()
        assertFalse(s.refreshThumbnails())
        assertNull(s.snapshot.single().thumbnail)

        s.wantThumbnails = true
        assertTrue(s.refreshThumbnails())
        val thumb = assertNotNull(s.snapshot.single().thumbnail)
        assertEquals(LayerStack.THUMB_WIDTH, thumb.width)
        // Nothing left stale, so a second call does no work and says so.
        assertFalse(s.refreshThumbnails())
        s.close()
    }

    /**
     * **The one that caught a blank thumbnail on the tablet.** A pencil line is
     * a couple of document pixels wide and the thumbnail is a twenty-six-fold
     * reduction, so a single filtered `drawBitmap` misses it nine times in ten
     * — the picture comes back empty for a sheet that is visibly drawn on.
     *
     * The document here is 2048 wide against a 128 px thumbnail, a sixteenfold
     * reduction, with a three-pixel line: enough that one-step filtering finds
     * nothing and halving finds it every time.
     */
    @Test
    fun `a hairline on a full-size page survives the reduction`() {
        val s = LayerStack(2048, 1344, enforceOffMainThread = false)
        val p = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            strokeWidth = 3f
        }
        s.active.layer.write { it.drawLine(100f, 672f, 1948f, 672f, p) }
        s.wantThumbnails = true
        s.touchAll()
        assertTrue(s.refreshThumbnails())
        val thumb = assertNotNull(s.snapshot.single().thumbnail)

        // A three-pixel line averaged into a sixteen-pixel cell is 19% covered,
        // so this is faint on purpose -- it is what a page with one thin line
        // on it actually looks like from far away. What it must not be is zero.
        assertTrue(darkest(thumb) > 20, "the line reduced to nothing: ${darkest(thumb)}")

        // The control, and the reason this test exists. The obvious code is one
        // `drawBitmap` from 2048 to 128; a bilinear filter samples a 2x2
        // neighbourhood however far apart the taps are, so at sixteenfold it
        // walks straight past a three-pixel line.
        val oneStep = Bitmap.createBitmap(128, 84, Bitmap.Config.ARGB_8888)
        s.active.layer.read {
            Canvas(oneStep).drawBitmap(
                it,
                Rect(0, 0, 2048, 1344),
                Rect(0, 0, 128, 84),
                Paint().apply { isFilterBitmap = true },
            )
        }
        assertTrue(
            darkest(thumb) > darkest(oneStep) * 2,
            "halving found ${darkest(thumb)} where one step found ${darkest(oneStep)}",
        )
        s.close()
    }

    private fun darkest(bmp: Bitmap): Int {
        var most = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val a = Color.alpha(bmp.getPixel(x, y))
                if (a > most) most = a
            }
        }
        return most
    }

    /**
     * One per call, not all of them: eight full-page rescales inside a single
     * render callback is a dropped frame on the thread that is also drawing
     * ink.
     */
    @Test
    fun `at most one thumbnail is built per refresh`() {
        val s = stack()
        s.apply(LayerOp.Add(s.newLayer(), "two"))
        s.apply(LayerOp.Add(s.newLayer(), "three"))
        s.wantThumbnails = true
        s.touchAll()
        assertTrue(s.refreshThumbnails())
        assertEquals(1, s.snapshot.count { it.thumbnail != null })
        assertTrue(s.refreshThumbnails())
        assertEquals(2, s.snapshot.count { it.thumbnail != null })
        assertTrue(s.refreshThumbnails())
        assertEquals(3, s.snapshot.count { it.thumbnail != null })
        assertFalse(s.refreshThumbnails())
        s.close()
    }
}
