package be.thalos.artiest.doc

import android.graphics.Path
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.SampleLog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ik7: which strokes are picked.
 *
 * Two properties are load-bearing and neither is about the geometry.
 *
 * **A picked set belongs to one sheet.** A stroke id is unique *within* a
 * sheet, so a set held across a sheet change names other strokes — and the
 * first thing done to it, an erase or a drag, happens to them. That is a
 * silent, destructive failure, so it is a test rather than a comment.
 *
 * **A picked set cannot outlive the strokes in it.** Undoing a stroke that was
 * picked must un-pick it, or the next operation is a no-op nobody can explain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokePickTest {

    private fun sheet() = VectorSheet(600, 400)

    private fun pending(x: Float, y: Float, len: Float = 80f): PendingStroke {
        val log = SampleLog()
        for (i in 0 until 30) log.add(x + len * i / 29f, y, 0.9f, 0.1f, 0f, i * 3.1f)
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = 1,
            colorArgb = 0xFF000000.toInt(),
            erase = false,
            brushText = BrushCodec.encode(Brush().apply { sizeMax = 10f }),
            bounds = Bounds.of(x - 6f, y - 6f, x + len + 6f, y + 6f),
        )
    }

    private fun lasso(l: Float, t: Float, r: Float, b: Float) =
        Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    // ---------------------------------------------------------------- taps

    @Test
    fun `a tap on a stroke picks it and a tap on paper picks nothing`() {
        val s = sheet()
        val pick = StrokePick()
        val a = s.append(pending(100f, 100f), null)
        s.append(pending(100f, 300f), null)

        assertTrue(pick.apply(StrokeOp.Tap(140f, 100f, 4f, SelectMode.NEW), s, 7))
        assertContentEquals(longArrayOf(a.id), pick.toArray())
        assertEquals(7, pick.layerId)
        assertTrue(pick.snapshot.active)
        assertNotNull(pick.snapshot.outline)

        assertTrue(pick.apply(StrokeOp.Tap(140f, 200f, 4f, SelectMode.NEW), s, 7))
        assertEquals(0, pick.count)
        assertNull(pick.snapshot.outline)
    }

    @Test
    fun `the topmost stroke wins where two overlap`() {
        val s = sheet()
        val pick = StrokePick()
        s.append(pending(100f, 100f), null)
        val top = s.append(pending(100f, 100f), null)
        pick.apply(StrokeOp.Tap(140f, 100f, 4f, SelectMode.NEW), s, 1)
        assertContentEquals(longArrayOf(top.id), pick.toArray())
    }

    @Test
    fun `a tap that changes nothing says so`() {
        val s = sheet()
        val pick = StrokePick()
        s.append(pending(100f, 100f), null)
        assertFalse(
            pick.apply(StrokeOp.Tap(140f, 380f, 4f, SelectMode.NEW), s, 1),
            "a tap on blank paper with nothing picked changed nothing",
        )
    }

    // --------------------------------------------------------------- modes

    @Test
    fun `add, subtract and intersect do what they say`() {
        val s = sheet()
        val pick = StrokePick()
        val a = s.append(pending(20f, 60f), null)
        val b = s.append(pending(20f, 140f), null)
        val c = s.append(pending(20f, 220f), null)

        pick.apply(StrokeOp.Lasso(lasso(0f, 0f, 400f, 180f), SelectMode.NEW), s, 1)
        assertContentEquals(longArrayOf(a.id, b.id), pick.toArray())

        pick.apply(StrokeOp.Lasso(lasso(0f, 180f, 400f, 400f), SelectMode.ADD), s, 1)
        assertContentEquals(longArrayOf(a.id, b.id, c.id), pick.toArray())

        pick.apply(StrokeOp.Lasso(lasso(0f, 0f, 400f, 100f), SelectMode.SUBTRACT), s, 1)
        assertContentEquals(longArrayOf(b.id, c.id), pick.toArray())

        pick.apply(StrokeOp.Lasso(lasso(0f, 180f, 400f, 400f), SelectMode.INTERSECT), s, 1)
        assertContentEquals(longArrayOf(c.id), pick.toArray())
    }

    @Test
    fun `all, none and invert`() {
        val s = sheet()
        val pick = StrokePick()
        val ids = (0 until 4).map { s.append(pending(20f, 40f + it * 80f), null).id }

        pick.apply(StrokeOp.All, s, 1)
        assertContentEquals(ids.toLongArray(), pick.toArray())

        pick.apply(StrokeOp.Lasso(lasso(0f, 0f, 400f, 100f), SelectMode.NEW), s, 1)
        pick.apply(StrokeOp.Invert, s, 1)
        assertContentEquals(ids.drop(1).toLongArray(), pick.toArray())

        pick.apply(StrokeOp.None, s, 1)
        assertEquals(0, pick.count)
    }

    // ------------------------------------------------------ the two rules

    /**
     * The destructive one. Ids are unique within a sheet; carrying a set to
     * another sheet would name other strokes there.
     */
    @Test
    fun `moving to another sheet drops the picked set rather than renaming it`() {
        val one = sheet()
        val two = sheet()
        val pick = StrokePick()
        val a = one.append(pending(100f, 100f), null)
        val b = two.append(pending(100f, 100f), null)
        assertEquals(a.id, b.id, "the fixture depends on the two ids colliding")

        pick.apply(StrokeOp.All, one, 1)
        assertEquals(1, pick.count)
        pick.apply(StrokeOp.Tap(500f, 380f, 4f, SelectMode.NEW), two, 2)
        assertEquals(0, pick.count, "the set survived a sheet change")
        assertEquals(2, pick.layerId)
    }

    @Test
    fun `a stroke that is gone is no longer picked`() {
        val s = sheet()
        val pick = StrokePick()
        val a = s.append(pending(20f, 60f), null)
        val b = s.append(pending(20f, 140f), null)
        pick.apply(StrokeOp.All, s, 1)

        s.remove(longArrayOf(a.id))
        assertTrue(pick.prune(s))
        assertContentEquals(longArrayOf(b.id), pick.toArray())
        assertFalse(pick.prune(s), "pruning twice should change nothing")
    }

    @Test
    fun `clearing forgets the sheet as well as the set`() {
        val s = sheet()
        val pick = StrokePick()
        s.append(pending(100f, 100f), null)
        pick.apply(StrokeOp.All, s, 5)
        assertTrue(pick.clear())
        assertEquals(0, pick.layerId)
        assertFalse(pick.snapshot.active)
        assertFalse(pick.clear(), "clearing twice should change nothing")
    }

    // ------------------------------------------------------- the highlight

    /**
     * The highlight is the picked strokes' *centrelines*, not their outlines: a
     * stroke's outline is the expensive derived thing this design avoids
     * computing, and a line along the spine is what a vector editor's own
     * highlight is.
     */
    @Test
    fun `the published outline follows the picked strokes and nothing else`() {
        val s = sheet()
        val pick = StrokePick()
        val a = s.append(pending(100f, 100f), null)
        s.append(pending(100f, 300f), null)
        pick.apply(StrokeOp.Tap(140f, 100f, 4f, SelectMode.NEW), s, 1)

        val box = android.graphics.RectF()
        assertNotNull(pick.snapshot.outline).computeBounds(box, true)
        assertTrue(box.top > 90f && box.bottom < 110f, "the outline covers the other stroke: $box")
        assertEquals(a.bounds.left, pick.snapshot.bounds.left, 1f)
        assertContentEquals(longArrayOf(a.id), pick.snapshot.ids)
    }

    @Test
    fun `an empty sheet answers nothing to everything`() {
        val s = sheet()
        val pick = StrokePick()
        assertFalse(pick.apply(StrokeOp.All, s, 1))
        assertFalse(pick.apply(StrokeOp.Invert, s, 1))
        assertFalse(pick.apply(StrokeOp.Lasso(lasso(0f, 0f, 600f, 400f), SelectMode.NEW), s, 1))
        assertEquals(0, pick.count)
    }

    /**
     * The lasso op copies its path, for the reason `SelectOp.Shape` does:
     * `Marquee` reuses one across gestures, and an op holding the builder's
     * path describes whatever the *next* gesture draws.
     */
    @Test
    fun `a lasso op keeps its own copy of the path`() {
        val s = sheet()
        val pick = StrokePick()
        val a = s.append(pending(20f, 60f), null)
        val shared = lasso(0f, 0f, 400f, 100f)
        val op = StrokeOp.Lasso(shared, SelectMode.NEW)
        shared.reset()
        shared.addRect(500f, 300f, 600f, 400f, Path.Direction.CW)
        pick.apply(op, s, 1)
        assertContentEquals(longArrayOf(a.id), pick.toArray())
    }
}
