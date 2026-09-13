package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.RectF
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
 * Ik13: the guides on a drawing, and the one snap they turn into.
 *
 * The tests worth reading are the three about *edges*: a ruler whose two ends
 * were dragged onto each other, a guide switched off that can still be picked
 * up, and the clip that stops an infinite line being an infinite number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideSetTest {

    private val scratch = FloatArray(2)

    private fun ruler(id: Long, x0: Float, y0: Float, x1: Float, y1: Float, on: Boolean = true) =
        Guideline(id, GuideKind.RULER, floatArrayOf(x0, y0, x1, y1), on)

    private fun set(vararg lines: Guideline) = GuideSet().also { it.load(lines.toList()) }

    // ---- one guide, one question -------------------------------------------

    @Test
    fun `a ruler projects onto its own infinite line`() {
        val line = ruler(1, 0f, 0f, 100f, 0f)
        val g = assertNotNull(line.guide)
        assertTrue(g.project(40f, 25f, scratch))
        assertEquals(40f, scratch[0], 1e-3f)
        assertEquals(0f, scratch[1], 1e-3f)

        // Past the end of the segment, because a ruler is infinite: a stroke
        // that ran off the end and un-snapped would have a kink in it exactly
        // where the hand was going fastest.
        assertTrue(g.project(5000f, 25f, scratch))
        assertEquals(5000f, scratch[0], 1e-2f)
        assertEquals(0f, scratch[1], 1e-3f)
    }

    @Test
    fun `a ruler whose ends are on top of each other is not a guide`() {
        // Reachable with a real hand and not an error: the guide does not apply
        // until one end is moved again.
        val line = ruler(1, 300f, 300f, 300f, 300f)
        assertNull(line.guide)
        assertFalse(line.live)
        assertNull(set(line).snap)
    }

    @Test
    fun `a guide switched off pulls nothing`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f, on = false))
        assertNull(set.snap)
        set.setOn(1, true)
        assertNotNull(set.snap)
        set.setOn(1, false)
        assertNull(set.snap)
    }

    @Test
    fun `a guide switched off can still be picked up`() {
        // A guide you could not grab until you had switched it back on would be
        // a guide that hides from you.
        val set = set(ruler(1, 0f, 0f, 100f, 0f, on = false))
        assertNotNull(set.lineNear(50f, 3f, 10f))
        assertNotNull(set.handleNear(100f, 2f, 10f))
    }

    @Test
    fun `strength zero is the same as no guide at all`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f))
        assertNotNull(set.snap)
        set.strength = 0f
        assertNull(set.snap, "a pull of nothing is not a pull")
        set.strength = 1f
        assertNotNull(set.snap)
    }

    // ---- the set -----------------------------------------------------------

    @Test
    fun `put adds a guide and replaces one with the same id`() {
        val set = GuideSet()
        set.put(ruler(1, 0f, 0f, 100f, 0f))
        assertEquals(1, set.size)
        set.put(ruler(1, 0f, 50f, 100f, 50f))
        assertEquals(1, set.size, "the same id is a replace, which is what a drag is")
        assertEquals(50f, set[0].yAt(0), 1e-3f)
        set.put(ruler(2, 0f, 0f, 0f, 100f))
        assertEquals(2, set.size)
    }

    @Test
    fun `an id given from outside does not collide with the next one made`() {
        val set = GuideSet()
        set.put(ruler(7, 0f, 0f, 100f, 0f))
        assertTrue(set.nextId() > 7L, "a fresh id has to clear the ones already here")
    }

    @Test
    fun `a load takes its ids from the file`() {
        val set = set(ruler(3, 0f, 0f, 10f, 0f), ruler(9, 0f, 0f, 0f, 10f))
        assertEquals(2, set.size)
        assertEquals(10L, set.nextId())
        assertNotNull(set.byId(9))
        assertNull(set.byId(4))
    }

    @Test
    fun `every change moves the revision`() {
        val set = GuideSet()
        val start = set.revision
        set.put(ruler(1, 0f, 0f, 100f, 0f))
        assertTrue(set.revision > start)
        val afterAdd = set.revision
        set.setOn(1, false)
        assertTrue(set.revision > afterAdd)
        val afterOff = set.revision
        set.setOn(1, false)
        assertEquals(afterOff, set.revision, "and a change that changes nothing does not")
    }

    @Test
    fun `removing and clearing`() {
        val set = set(ruler(1, 0f, 0f, 10f, 0f), ruler(2, 0f, 0f, 0f, 10f))
        assertTrue(set.remove(1))
        assertFalse(set.remove(1))
        assertEquals(1, set.size)
        set.clear()
        assertTrue(set.isEmpty)
        assertNull(set.snap)
    }

    // ---- several guides ----------------------------------------------------

    @Test
    fun `two guides snap to whichever is nearer`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f), ruler(2, 0f, 400f, 100f, 400f))
        val snap = assertNotNull(set.snap)
        assertTrue(snap.apply(50f, 30f, scratch))
        assertEquals(0f, scratch[1], 1e-3f)
        assertTrue(snap.apply(50f, 370f, scratch))
        assertEquals(400f, scratch[1], 1e-3f)
    }

    @Test
    fun `a guide that is off is not one of the candidates`() {
        val set = set(
            ruler(1, 0f, 0f, 100f, 0f),
            ruler(2, 0f, 400f, 100f, 400f, on = false),
        )
        val snap = assertNotNull(set.snap)
        assertTrue(snap.apply(50f, 370f, scratch))
        assertEquals(0f, scratch[1], 1e-3f, "the far one, because the near one is off")
    }

    @Test
    fun `the reach is what stops a guide reaching across the page`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f))
        set.reachDoc = 50f
        val snap = assertNotNull(set.snap)
        assertTrue(snap.apply(10f, 20f, scratch), "inside the reach")
        assertFalse(snap.apply(10f, 80f, scratch), "and outside it, nothing moves")
    }

    // ---- what the hand grabs -----------------------------------------------

    @Test
    fun `the nearer guide is the one under the finger`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f), ruler(2, 0f, 20f, 100f, 20f))
        assertEquals(1L, assertNotNull(set.lineNear(50f, 2f, 12f)).id)
        assertEquals(2L, assertNotNull(set.lineNear(50f, 18f, 12f)).id)
        assertNull(set.lineNear(50f, 200f, 12f), "and nothing near enough is nothing")
    }

    @Test
    fun `a handle is found before the body it sits on`() {
        // The rule every caller follows, and the reason it matters: a handle is
        // on its own line, so a hit test that took the body first could never
        // grab an end.
        val set = set(ruler(1, 0f, 0f, 100f, 0f))
        val (line, index) = assertNotNull(set.handleNear(98f, 3f, 12f))
        assertEquals(1L, line.id)
        assertEquals(1, index, "the far end")
        assertNull(set.handleNear(50f, 0f, 12f), "the middle of a ruler is not a handle")
    }

    @Test
    fun `dragging a handle pivots about the other end`() {
        val line = ruler(1, 0f, 0f, 100f, 0f)
        val turned = line.withPoint(1, 0f, 100f)
        assertEquals(0f, turned.xAt(0), 1e-3f, "the other end did not move")
        assertEquals(0f, turned.yAt(0), 1e-3f)
        val g = assertNotNull(turned.guide)
        assertTrue(g.project(30f, 40f, scratch))
        assertEquals(0f, scratch[0], 1e-3f, "and the ruler is vertical now")
        assertEquals(40f, scratch[1], 1e-3f)
    }

    @Test
    fun `moving a guide moves every point of it`() {
        val moved = ruler(1, 0f, 0f, 100f, 0f).movedBy(5f, 7f)
        assertEquals(5f, moved.xAt(0), 1e-3f)
        assertEquals(7f, moved.yAt(0), 1e-3f)
        assertEquals(105f, moved.xAt(1), 1e-3f)
        assertEquals(7f, moved.yAt(1), 1e-3f)
    }

    @Test
    fun `an edit keeps the id and the on-ness`() {
        val line = ruler(4, 0f, 0f, 100f, 0f, on = false)
        for (edit in listOf(line.movedBy(1f, 1f), line.withPoint(0, 9f, 9f))) {
            assertEquals(4L, edit.id)
            assertFalse(edit.on)
            assertEquals(GuideKind.RULER, edit.kind)
        }
    }

    // ---- drawing -----------------------------------------------------------

    @Test
    fun `an infinite ruler is cut to the page before it is drawn`() {
        // Not tidiness. A segment from minus a billion to plus a billion put
        // through a view matrix at 800% is where float coordinates stop being
        // representable and the line vanishes — which reads as the guide
        // disappearing when you zoom in on it.
        val path = Path()
        val clip = RectF(0f, 0f, 1000f, 800f)
        set(ruler(1, 0f, 0f, 1000f, 800f)).outline(path, clip)
        assertFalse(path.isEmpty)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertTrue(bounds.left >= -1f && bounds.top >= -1f, "$bounds")
        assertTrue(bounds.right <= 1001f && bounds.bottom <= 801f, "$bounds")
    }

    @Test
    fun `a ruler that misses the page draws nothing`() {
        val path = Path()
        set(ruler(1, -500f, -500f, 500f, -500f)).outline(path, RectF(0f, 0f, 1000f, 800f))
        assertTrue(path.isEmpty, "a horizontal line 500 above the page")
    }

    @Test
    fun `a ruler along an edge is still drawn`() {
        val path = Path()
        set(ruler(1, 0f, 0f, 1000f, 0f)).outline(path, RectF(0f, 0f, 1000f, 800f))
        assertFalse(path.isEmpty, "the top edge is on the page, not off it")
    }

    // ---- what a stroke keeps -----------------------------------------------

    @Test
    fun `a snap is written down whole and read back`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f), ruler(2, 0f, 400f, 100f, 400f))
        set.strength = 0.8f
        set.reachDoc = 40f
        val text = assertNotNull(set.snapText)
        val back = assertNotNull(GuideText.decodeSnap(text))
        // Same answer as the live snap, which is the only property that
        // matters: a rebuild has to lay the stroke where it was laid.
        val live = assertNotNull(set.snap)
        val a = FloatArray(2)
        val b = FloatArray(2)
        for (y in intArrayOf(5, 20, 380, 395)) {
            assertEquals(live.apply(50f, y.toFloat(), a), back.apply(50f, y.toFloat(), b), "y=$y")
            assertEquals(a[0], b[0], 1e-3f, "y=$y")
            assertEquals(a[1], b[1], 1e-3f, "y=$y")
        }
    }

    @Test
    fun `a guide that is off is left out of what a stroke keeps`() {
        // Interned by text, so a guide that did not touch the stroke must not
        // be in the description: two sittings that drew the same line against
        // the same ruler would otherwise take two table entries.
        val set = set(ruler(1, 0f, 0f, 100f, 0f), ruler(2, 0f, 400f, 100f, 400f, on = false))
        val withOff = assertNotNull(set.snapText)
        set.remove(2)
        assertEquals(withOff, set.snapText)
    }

    @Test
    fun `no live guide is nothing to write down`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f, on = false))
        assertNull(set.snapText)
        assertNull(set.snap)
    }

    @Test
    fun `the text and the snap are published together`() {
        // Both or neither, always: a stroke that got one from before an edit
        // and the other from after it would be interned under a description of
        // a ruler it was not drawn against.
        val set = GuideSet()
        assertNull(set.snap)
        assertNull(set.snapText)
        set.put(ruler(1, 0f, 0f, 100f, 0f))
        assertNotNull(set.snap)
        assertNotNull(set.snapText)
        set.clear()
        assertNull(set.snap)
        assertNull(set.snapText)
    }

    @Test
    fun `a moved stroke's ruler moves with it`() {
        // The reason GuideText.mapSnap exists. A record stores the raw samples,
        // so a rebuild computes snap(smooth(M x)) -- which is M snap(smooth(x))
        // only when the guide is mapped too. Leave it and the stroke springs
        // back onto the ruler it was drawn along.
        val set = set(ruler(1, 0f, 0f, 100f, 0f))
        val text = assertNotNull(set.snapText)
        val m = android.graphics.Matrix().apply { setTranslate(0f, 250f) }
        val moved = assertNotNull(GuideText.mapSnap(text, m, 1f))
        val snap = assertNotNull(GuideText.decodeSnap(moved))
        assertTrue(snap.apply(50f, 240f, scratch))
        assertEquals(250f, scratch[1], 1e-3f, "the ruler is 250 lower now")
    }

    @Test
    fun `a scaled stroke's reach scales with it`() {
        val set = set(ruler(1, 0f, 0f, 100f, 0f))
        set.reachDoc = 40f
        val text = assertNotNull(set.snapText)
        val m = android.graphics.Matrix().apply { setScale(2f, 2f) }
        val moved = assertNotNull(GuideText.mapSnap(text, m, 2f))
        val snap = assertNotNull(GuideText.decodeSnap(moved))
        // A reach is a distance in document pixels, so a stroke at twice the
        // size was drawn against a ruler that now reaches twice as far.
        assertTrue(snap.apply(10f, 60f, scratch), "60 is inside a reach of 80")
        assertFalse(snap.apply(10f, 90f, scratch), "and 90 is outside it")
    }

    @Test
    fun `the on and off guides are drawn apart`() {
        val set = set(ruler(1, 0f, 0f, 1000f, 0f), ruler(2, 0f, 400f, 1000f, 400f, on = false))
        val clip = RectF(0f, 0f, 1000f, 800f)
        val live = Path().also { set.outline(it, clip, on = true) }
        val dim = Path().also { set.outline(it, clip, on = false) }
        val liveBounds = RectF().also { live.computeBounds(it, true) }
        val dimBounds = RectF().also { dim.computeBounds(it, true) }
        assertEquals(0f, liveBounds.top, 1e-3f)
        assertEquals(400f, dimBounds.top, 1e-3f)
    }
}
