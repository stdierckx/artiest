package be.thalos.artiest.doc

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.SampleLog
import be.thalos.artiest.engine.ink.StrokeRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ik5: one history, two kinds of step.
 *
 * The property that matters most is the one `docs/vector-plan.md` trap 3 names
 * and that no amount of care in either step can supply on its own: **a mixed
 * sequence of edits walks back in the order it was made.** Two stacks would
 * give an undo press that walks one of two interleaved sequences with nothing
 * on screen to say which, and the user would learn not to trust the button.
 *
 * The second is the budget. A vector edit's step is kilobytes where a
 * `PixelPatch` is up to 28 MB, and `UndoHistory`'s cap is only a cap if every
 * step reports what it really occupies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorStepTest {

    /** Records what was asked to be repainted, so the step can be tested alone. */
    private class Painter : SheetRebuilder {
        val asked = ArrayList<Pair<Int, Bounds>>()
        override fun rebuild(entry: LayerStack.Entry, damage: Bounds) {
            asked.add(entry.id to damage)
        }
    }

    private fun doc(): Document = Document(640, 480, enforceOffMainThread = false)

    /** An ink layer, and the entry it landed on. */
    private fun inkLayer(d: Document): LayerStack.Entry {
        d.layers.apply(LayerOp.AddVector(d.layers.newLayer(), "Ink"))
        return d.layers.active
    }

    private fun pending(x: Float = 80f, y: Float = 80f, len: Float = 60f): PendingStroke {
        val log = SampleLog()
        for (i in 0 until 30) log.add(x + len * i / 29f, y, 0.8f, 0.1f, 0f, i * 3.1f)
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = 3,
            colorArgb = 0xFF000000.toInt(),
            erase = false,
            brushText = BrushCodec.encode(Brush()),
            bounds = Bounds.of(x - 12f, y - 12f, x + len + 12f, y + 12f),
        )
    }

    // ------------------------------------------------------------ the step

    @Test
    fun `undoing a drawn stroke takes it off the list and repaints where it was`() {
        val d = doc()
        val painter = Painter()
        d.rebuilder = painter
        val entry = inkLayer(d)
        val sheet = entry.vector!!
        val record = sheet.append(pending(), null)
        val step = VectorStep(entry.id, added = listOf(record), removed = emptyList())

        val inverse = step.exchange(d)
        assertEquals(0, sheet.size)
        assertEquals(1, painter.asked.size)
        assertEquals(entry.id, painter.asked[0].first)
        assertEquals(record.bounds, painter.asked[0].second)

        // And the inverse puts it back, with the id it had.
        inverse.exchange(d)
        assertEquals(1, sheet.size)
        assertEquals(record.id, sheet.strokes.single().id)
        assertEquals(2, painter.asked.size)
        d.close()
    }

    @Test
    fun `a step is its own inverse with the lists swapped`() {
        val d = doc()
        val entry = inkLayer(d)
        val sheet = entry.vector!!
        val a = sheet.append(pending(y = 80f), null)
        val b = sheet.append(pending(y = 200f), null)
        val step = VectorStep(entry.id, added = listOf(b), removed = emptyList())
        val back = step.exchange(d) as VectorStep
        assertContentEquals(listOf(b.id), back.removed.map { it.id })
        assertTrue(back.added.isEmpty())
        assertEquals(listOf(a.id), sheet.strokes.map { it.id })
        d.close()
    }

    @Test
    fun `a replacement swaps several for one and back`() {
        val d = doc()
        val entry = inkLayer(d)
        val sheet = entry.vector!!
        val parent = sheet.append(pending(len = 200f), null)
        val ids = sheet.nextIds(2)
        val halves = ids.map { id ->
            StrokeRecord(
                id = id, brush = parent.brush, colorArgb = parent.colorArgb, erase = false,
                seed = parent.seed, dabBase = 0, clip = parent.clip, bounds = parent.bounds,
                packed = parent.copyPackedBytes(), sampleCount = parent.sampleCount,
            )
        }
        sheet.replace(parent.id, halves)
        val step = VectorStep(entry.id, added = halves, removed = listOf(parent))

        val back = step.exchange(d)
        assertEquals(listOf(parent.id), sheet.strokes.map { it.id })
        back.exchange(d)
        assertContentEquals(ids.toList(), sheet.strokes.map { it.id })
        d.close()
    }

    /**
     * A step against a sheet that is gone, or that has stopped keeping its
     * strokes, does nothing and hands back *itself* — so pressing undo past it
     * walks over it rather than stopping on it. The same contract `PixelPatch`
     * has, for the same reason.
     */
    @Test
    fun `a step naming a sheet that is gone walks over itself`() {
        val d = doc()
        val painter = Painter()
        d.rebuilder = painter
        val entry = inkLayer(d)
        val record = entry.vector!!.append(pending(), null)
        val step = VectorStep(entry.id, added = listOf(record), removed = emptyList())
        d.layers.apply(LayerOp.Delete(entry.id))

        assertSame(step, step.exchange(d))
        assertEquals(0, painter.asked.size)

        // And against a sheet that never kept strokes.
        val raster = VectorStep(d.layers.active.id, listOf(record), emptyList())
        assertSame(raster, raster.exchange(d))
        d.close()
    }

    @Test
    fun `a null rebuilder edits the list and paints nothing`() {
        val d = doc()
        d.rebuilder = null
        val entry = inkLayer(d)
        val record = entry.vector!!.append(pending(), null)
        VectorStep(entry.id, listOf(record), emptyList()).exchange(d)
        assertEquals(0, entry.vector!!.size)
        d.close()
    }

    // --------------------------------------------------------------- budget

    /**
     * `UndoHistory`'s cap is only a cap if every step reports what it really
     * occupies, and the whole argument for a vector sheet's undo is the size of
     * this number against a `PixelPatch`'s.
     */
    @Test
    fun `a vector step is kilobytes where a pixel patch is megabytes`() {
        val d = doc()
        val entry = inkLayer(d)
        val sheet = entry.vector!!
        val records = (0 until 10).map { sheet.append(pending(y = 40f + it * 30f), null) }
        val step = VectorStep(entry.id, added = records, removed = emptyList())
        assertTrue(step.bytes < 8 * 1024, "${step.bytes} bytes for ten strokes")
        assertTrue(step.bytes > records.sumOf { it.byteCount.toLong() })

        val patch = PixelPatch.captureAll(entry.id, entry.layer, 640, 480)!!
        assertTrue(patch.bytes > step.bytes * 100, "a full-page patch is ${patch.bytes}")
        patch.recycle()
        d.close()
    }

    @Test
    fun `an empty step is harmless and its damage is empty`() {
        val d = doc()
        val entry = inkLayer(d)
        val step = VectorStep(entry.id, emptyList(), emptyList())
        assertTrue(step.damage().isEmpty)
        step.exchange(d)
        assertEquals(0, entry.vector!!.size)
        d.close()
    }

    // ------------------------------------------------------- the one history

    /**
     * `docs/vector-plan.md` trap 3, as a test: pixel edits and vector edits
     * interleaved walk back in the order they were made.
     *
     * Written against the real `Document` rather than against `UndoHistory`,
     * because the thing that could go wrong is not the deque — it is somebody
     * adding a second history later and routing one kind of step to it.
     */
    @Test
    fun `a mixed sequence walks back in the order it was made`() {
        val d = doc()
        d.rebuilder = Painter()
        val ink = inkLayer(d)
        val sheet = ink.vector!!

        val order = ArrayList<String>()
        // A vector edit, a pixel edit, a vector edit.
        val first = sheet.append(pending(y = 60f), null)
        d.recordVectorEdit(VectorStep(ink.id, listOf(first), emptyList()))
        d.snapshotBeforeStroke(Bounds.of(10f, 10f, 40f, 40f))
        val second = sheet.append(pending(y = 200f), null)
        d.recordVectorEdit(VectorStep(ink.id, listOf(second), emptyList()))
        assertEquals(3, d.undoDepth)

        d.applyUndo()
        order.add(if (sheet.size == 1) "vector" else "pixel")
        d.applyUndo()
        order.add(if (sheet.size == 1) "pixel" else "vector")
        d.applyUndo()
        order.add(if (sheet.size == 0) "vector" else "pixel")
        assertContentEquals(listOf("vector", "pixel", "vector"), order)
        assertEquals(0, sheet.size)
        assertFalse(d.canUndo)

        // Forward again, in the same order.
        d.applyRedo()
        d.applyRedo()
        d.applyRedo()
        assertEquals(2, sheet.size)
        assertEquals(listOf(first.id, second.id), sheet.strokes.map { it.id })
        d.close()
    }

    /**
     * The sheet stays honest when a *pixel* step lands on it — a float drop or
     * a stencil clear, after Ik5 — and does not when a vector step does. That
     * distinction is what replaced Ik3's coarse "any undo spoils everything".
     */
    @Test
    fun `a pixel patch on an ink sheet spoils it and a vector step does not`() {
        val d = doc()
        d.rebuilder = Painter()
        val ink = inkLayer(d)
        val sheet = ink.vector!!
        val record = sheet.append(pending(), null)

        d.recordVectorEdit(VectorStep(ink.id, listOf(record), emptyList()))
        d.applyUndo()
        assertTrue(sheet.intact, "a vector step should not spoil the sheet it edits")

        d.snapshotBeforeStroke(Bounds.of(10f, 10f, 40f, 40f))
        d.applyUndo()
        assertFalse(sheet.intact)
        assertEquals("pixels restored by undo", sheet.spoiledBy)
        d.close()
    }

    @Test
    fun `undoing nothing is not an error`() {
        val d = doc()
        assertFalse(d.applyUndo())
        assertFalse(d.applyRedo())
        assertNull(d.rebuilder)
        d.close()
    }
}
