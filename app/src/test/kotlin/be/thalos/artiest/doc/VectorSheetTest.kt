package be.thalos.artiest.doc

import android.graphics.Path
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sheet that keeps its strokes.
 *
 * Two of these tests are worth more than the rest.
 *
 * **The brush table.** `docs/inker-plan.md`'s risk table says a record must
 * name a *table entry* and not a library id, because retuning a preset must not
 * reach back and change strokes drawn with it last week. That is a property of
 * the interning, and it is the kind of thing that is true on the day it is
 * written and quietly stops being true.
 *
 * **`intact`.** Ik3 ships before Ik5 and Ik8, so there are three things that
 * move a sheet's pixels without moving its records. A sheet that has had one of
 * them done to it must say so, because a rebuild that silently repaints a
 * drawing into something the user did not draw is the defect they find months
 * later in a file they have already sent somewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// `Path.op` and `Region.setPath` are the clip table's and the lasso's whole
// implementation, and both are no-ops under the legacy graphics shim: the
// tests pass while nothing happens.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorSheetTest {

    private val w = 1200
    private val h = 900

    private fun sheet() = VectorSheet(w, h)

    private fun pending(
        x: Float = 100f,
        y: Float = 100f,
        len: Float = 60f,
        brush: Brush = Brush(),
        seed: Int = 1,
    ): PendingStroke {
        val log = SampleLog()
        val n = 40
        for (i in 0 until n) {
            log.add(x + len * i / (n - 1f), y, 0.8f, 0.1f, 0f, i * 3.1f)
        }
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = seed,
            colorArgb = 0xFF000000.toInt(),
            erase = false,
            brushText = BrushCodec.encode(brush),
            bounds = Bounds.of(x - 12f, y - 12f, x + len + 12f, y + 12f),
        )
    }

    // ---------------------------------------------------------------- adding

    @Test
    fun `a stroke lands on top with an ascending id`() {
        val s = sheet()
        val a = s.append(pending(), null)
        val b = s.append(pending(y = 300f), null)
        assertEquals(2, s.size)
        assertTrue(b.id > a.id)
        assertContentEquals(listOf(a.id, b.id), s.strokes.map { it.id })
        assertEquals(a.id, s.byId(a.id)?.id)
    }

    @Test
    fun `an empty sheet answers nothing rather than failing`() {
        val s = sheet()
        assertTrue(s.isEmpty)
        assertNull(s.hit(10f, 10f, 8f))
        assertEquals(emptyList(), s.overlapping(Bounds.of(0f, 0f, 500f, 500f)))
        assertEquals(0, s.hits(Path().apply { addRect(0f, 0f, 500f, 500f, Path.Direction.CW) }).size)
        assertEquals(0L, s.byteCount)
    }

    // ----------------------------------------------------------- brush table

    @Test
    fun `strokes drawn with one nib share one table entry`() {
        val s = sheet()
        val pen = Brush().apply { sizeMax = 18f }
        repeat(20) { s.append(pending(y = 50f + it * 20f, brush = pen), null) }
        assertEquals(1, s.brushes.size)
        assertTrue(s.strokes.all { it.brush == 0 })
    }

    @Test
    fun `a different nib is a different table entry`() {
        val s = sheet()
        val thin = Brush().apply { sizeMax = 6f }
        val fat = Brush().apply { sizeMax = 60f }
        val a = s.append(pending(brush = thin), null)
        val b = s.append(pending(y = 300f, brush = fat), null)
        val c = s.append(pending(y = 500f, brush = thin), null)
        assertEquals(2, s.brushes.size)
        assertEquals(a.brush, c.brush)
        assertTrue(a.brush != b.brush)
        assertEquals(6f, s.brushAt(a.brush).sizeMax)
        assertEquals(60f, s.brushAt(b.brush).sizeMax)
    }

    /**
     * The risk table's line, as a test: *"the record names a table entry, not a
     * library id; retuning a preset does not reach back."*
     */
    @Test
    fun `retuning the brush afterwards does not reach back into the strokes`() {
        val s = sheet()
        val pen = Brush().apply { sizeMax = 18f }
        val record = s.append(pending(brush = pen), null)
        pen.sizeMax = 120f
        assertEquals(18f, s.brushAt(record.brush).sizeMax)
    }

    @Test
    fun `a brush index from nowhere answers a default rather than crashing`() {
        val s = sheet()
        assertEquals(Brush().sizeMax, s.brushAt(7).sizeMax)
        assertEquals(Brush().sizeMax, s.brushAt(-1).sizeMax)
        assertNull(s.clipAt(3))
    }

    // ------------------------------------------------------------ clip table

    @Test
    fun `consecutive strokes under one selection share one clip`() {
        val s = sheet()
        val clip = Path().apply { addRect(50f, 50f, 400f, 400f, Path.Direction.CW) }
        val a = s.append(pending(), clip)
        val b = s.append(pending(y = 200f), Path(clip))
        assertEquals(1, s.clips.size)
        assertEquals(a.clip, b.clip)
        assertEquals(0, a.clip)
    }

    @Test
    fun `no selection is no clip, which is the usual case`() {
        val s = sheet()
        val r = s.append(pending(), null)
        assertEquals(StrokeRecord.NO_CLIP, r.clip)
        assertEquals(0, s.clips.size)
        assertEquals(StrokeRecord.NO_CLIP, s.append(pending(), Path()).clip)
    }

    @Test
    fun `a different selection is a second clip`() {
        val s = sheet()
        val one = Path().apply { addRect(50f, 50f, 400f, 400f, Path.Direction.CW) }
        val two = Path().apply { addRect(60f, 50f, 400f, 400f, Path.Direction.CW) }
        s.append(pending(), one)
        s.append(pending(y = 200f), two)
        assertEquals(2, s.clips.size)
    }

    // ----------------------------------------------------------- finding it

    @Test
    fun `a tap on the ink finds the stroke and a tap beside it does not`() {
        val s = sheet()
        val r = s.append(pending(x = 200f, y = 400f, len = 200f), null)
        assertEquals(r.id, s.hit(300f, 400f, 0f))
        assertNull(s.hit(300f, 700f, 0f))
    }

    @Test
    fun `the topmost stroke wins a tap where two overlap`() {
        val s = sheet()
        s.append(pending(x = 200f, y = 400f, len = 200f), null)
        val top = s.append(pending(x = 200f, y = 400f, len = 200f, seed = 2), null)
        assertEquals(top.id, s.hit(300f, 400f, 0f))
    }

    @Test
    fun `overlapping answers the strokes in a rectangle, in draw order`() {
        val s = sheet()
        val a = s.append(pending(x = 100f, y = 100f), null)
        val b = s.append(pending(x = 100f, y = 700f), null)
        val c = s.append(pending(x = 100f, y = 120f), null)
        assertContentEquals(
            listOf(a.id, c.id),
            s.overlapping(Bounds.of(80f, 80f, 400f, 200f)).map { it.id },
        )
        assertEquals(3, s.overlapping(Bounds.of(0f, 0f, 1200f, 900f)).size)
        assertTrue(s.overlapping(Bounds.of(900f, 800f, 1000f, 850f)).isEmpty())
        assertEquals(b.id, s.overlapping(Bounds.of(80f, 650f, 400f, 800f)).single().id)
    }

    @Test
    fun `a lasso takes the strokes whose centreline it encloses`() {
        val s = sheet()
        val inside = s.append(pending(x = 200f, y = 200f, len = 100f), null)
        s.append(pending(x = 200f, y = 800f, len = 100f), null)
        val lasso = Path().apply { addRect(150f, 150f, 400f, 300f, Path.Direction.CW) }
        assertContentEquals(longArrayOf(inside.id), s.hits(lasso))
        assertEquals(0, s.hits(Path()).size)
    }

    // --------------------------------------------------------------- editing

    @Test
    fun `removing takes them out of the list and out of the index`() {
        val s = sheet()
        val a = s.append(pending(x = 100f, y = 100f), null)
        val b = s.append(pending(x = 100f, y = 400f), null)
        val taken = s.remove(longArrayOf(a.id))
        assertEquals(listOf(a.id), taken.map { it.id })
        assertEquals(1, s.size)
        assertNull(s.hit(150f, 100f, 4f))
        assertEquals(b.id, s.hit(150f, 400f, 4f))
        assertEquals(emptyList(), s.remove(longArrayOf(9999L)))
    }

    @Test
    fun `a removed stroke can be put back with the id it had`() {
        val s = sheet()
        val a = s.append(pending(x = 100f, y = 100f), null)
        s.append(pending(x = 100f, y = 400f), null)
        val taken = s.remove(longArrayOf(a.id)).single()
        s.add(taken)
        s.reorder()
        assertEquals(2, s.size)
        assertEquals(a.id, s.strokes.first().id)
        assertEquals(a.id, s.hit(150f, 100f, 4f))
    }

    @Test
    fun `replace swaps one record for several, in its place`() {
        val s = sheet()
        val a = s.append(pending(x = 100f, y = 100f), null)
        val b = s.append(pending(x = 100f, y = 400f), null)
        val ids = s.nextIds(2)
        val halves = ids.mapIndexed { i, id ->
            StrokeRecord(
                id = id, brush = a.brush, colorArgb = a.colorArgb, erase = false,
                seed = a.seed, dabBase = i * 20, clip = a.clip, bounds = a.bounds,
                packed = a.copyPackedBytes(), sampleCount = a.sampleCount,
            )
        }
        assertEquals(a.id, s.replace(a.id, halves)?.id)
        assertEquals(3, s.size)
        assertContentEquals(listOf(ids[0], ids[1], b.id), s.strokes.map { it.id })
        assertNull(s.byId(a.id))
        assertNull(s.replace(9999L, halves))
    }

    @Test
    fun `clear empties everything including the tables`() {
        val s = sheet()
        s.append(pending(), Path().apply { addRect(0f, 0f, 100f, 100f, Path.Direction.CW) })
        s.clear()
        assertTrue(s.isEmpty)
        assertEquals(0, s.brushes.size)
        assertEquals(0, s.clips.size)
        assertNull(s.hit(120f, 100f, 8f))
        // Ids start again, because the sheet they were unique within is gone.
        assertEquals(1L, s.append(pending(), null).id)
    }

    // ----------------------------------------------------------- the honesty

    @Test
    fun `a fresh sheet is intact and drawing keeps it that way`() {
        val s = sheet()
        assertTrue(s.intact)
        repeat(5) { s.append(pending(y = 100f + it * 40f), null) }
        assertTrue(s.intact)
        assertEquals("", s.spoiledBy)
    }

    @Test
    fun `spoiling keeps the first reason and clearing undoes it`() {
        val s = sheet()
        s.spoil("an undo")
        s.spoil("a redo")
        assertFalse(s.intact)
        assertEquals("an undo", s.spoiledBy)
        // A whole clear is the one pixel operation outside drawing that the
        // record list can express exactly: nothing left, nothing recorded.
        s.clear()
        assertTrue(s.intact)
    }

    // ---------------------------------------------------------------- budget

    /**
     * `docs/inker-plan.md` reserves 8.7 MiB for a thousand strokes. Ik1 proved
     * the packing; this proves the sheet does not multiply it.
     */
    @Test
    fun `a thousand strokes cost what the plan reserved`() {
        val s = sheet()
        repeat(1000) { s.append(pending(x = (it % 20) * 55f, y = (it / 20) * 22f), null) }
        assertEquals(1000, s.size)
        assertTrue(s.byteCount < 2L * 1024 * 1024, "${s.byteCount} bytes")
        assertEquals(1, s.brushes.size)
    }
}
