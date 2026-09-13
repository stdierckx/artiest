package be.thalos.artiest.canvas

import be.thalos.artiest.engine.brush.BrushPreset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the pen does when it comes down, now that no toggle answers it.
 *
 * The rule used to have three inputs — a toolbar toggle, the barrel button and
 * the brush — and one of them has gone. These tests are the record of what the
 * remaining two mean, and each is a thing a hand does:
 *
 * - pick the eraser off the shelf and draw;
 * - pick a pencil and turn the pen over;
 * - pick a pencil, name a rubber, and turn the pen over;
 * - pick the eraser and turn the pen over, which must not be a double negative.
 *
 * No Android and no view: [PenChoice] takes two brushes and a boolean, which is
 * why it was taken out of `InkSurfaceView` at all.
 */
class PenChoiceTest {

    private val pencil get() = BrushPreset.PENCIL.create()
    private val hard get() = BrushPreset.HARD_ERASER.create()
    private val soft get() = BrushPreset.SOFT_ERASER.create()

    @Test
    fun `a drawing brush draws`() {
        val ink = pencil
        val c = PenChoice.of(ink, rubber = null, barrel = false)
        assertSame(ink, c.from)
        assertFalse(c.erase)
        assertFalse(c.borrowed)
    }

    @Test
    fun `an eraser in the hand erases, with its own strength`() {
        val ink = soft
        val c = PenChoice.of(ink, rubber = null, barrel = false)
        assertSame(ink, c.from)
        assertTrue(c.erase)
        // The whole reason the soft eraser is a second tool: its flow and its
        // opacity are the tool, so nothing may force them to full.
        assertFalse(c.borrowed, "a real eraser keeps its own translucency")
    }

    @Test
    fun `the barrel borrows the drawing brush when no rubber is named`() {
        val ink = pencil
        val c = PenChoice.of(ink, rubber = null, barrel = true)
        assertSame(ink, c.from, "a pencil rubs out with the pencil's tilt")
        assertTrue(c.erase)
        assertTrue(c.borrowed, "and at full strength, not at the pencil's flow")
    }

    @Test
    fun `the barrel uses the named rubber, at that rubber's own strength`() {
        val rubber = soft
        val c = PenChoice.of(pencil, rubber = rubber, barrel = true)
        assertSame(rubber, c.from)
        assertTrue(c.erase)
        assertFalse(c.borrowed, "it was chosen for its softness, so keep it")
    }

    @Test
    fun `turning an eraser over is not a double negative`() {
        // The one case a toggle would have got wrong, and the one a user tries
        // by accident: the pen is already a rubber and the hand rolls it. It
        // goes on rubbing out — it does not start drawing, and it does not
        // swap itself for the named rubber either.
        val ink = hard
        val rubber = soft
        val c = PenChoice.of(ink, rubber = rubber, barrel = true)
        assertSame(ink, c.from, "the eraser in the hand is the one that is used")
        assertTrue(c.erase)
        assertFalse(c.borrowed)
    }
}
