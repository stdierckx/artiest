package be.thalos.artiest.doc

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ik10, which is the cheapest item in this plan and the one that shows what a
 * record is for.
 *
 * A restyled stroke keeps its samples, its seed and its `dabBase`, so the mark
 * lands in exactly the same place and the grain falls in exactly the same
 * pattern — a property the pixel version of this operation could not have at
 * any price. What changes is two fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeRestyleTest {

    private val thin = Brush().apply { sizeMin = 2f; sizeMax = 6f }
    private val fat = Brush().apply { sizeMin = 20f; sizeMax = 60f }

    private fun sheet() = VectorSheet(600, 400)

    private fun add(s: VectorSheet, y: Float, pen: Brush = thin, colour: Int = 0xFF000000.toInt()) =
        s.append(pending(y, pen, colour), null)

    private fun pending(y: Float, pen: Brush, colour: Int): PendingStroke {
        val log = SampleLog()
        for (i in 0 until 40) log.add(60f + i * 4f, y, 0.9f, 0f, 0f, i * 3.1f)
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = 77,
            colorArgb = colour,
            erase = false,
            brushText = BrushCodec.encode(pen),
            bounds = Bounds.of(56f, y - 4f, 220f, y + 4f),
        )
    }

    private fun restyle(
        s: VectorSheet,
        ids: LongArray,
        colour: Int? = null,
        pen: Brush? = null,
    ): VectorStep? {
        val step = StrokeRestyle.plan(
            StrokeOp.Restyle(colour, pen?.let { BrushCodec.encode(it) }), ids, s, 1,
        )
        if (step != null) StrokeRestyle.apply(step, s)
        return step
    }

    @Test
    fun `recolouring keeps the samples, the seed and the dab index`() {
        val s = sheet()
        val a = add(s, 100f)
        val step = assertNotNull(restyle(s, longArrayOf(a.id), colour = 0xFFCC0000.toInt()))
        val now = s.strokes.single()
        assertEquals(0xFFCC0000.toInt(), now.colorArgb)
        assertEquals(a.seed, now.seed)
        assertEquals(a.dabBase, now.dabBase)
        assertEquals(a.brush, now.brush)
        assertContentEquals(a.copyPackedBytes(), now.copyPackedBytes())
        assertEquals(listOf(a.id), step.removed.map { it.id })
        assertTrue(now.id != a.id, "a replacement needs its own id")
    }

    @Test
    fun `re-brushing gives them a table entry, deduplicated`() {
        val s = sheet()
        val ids = (0 until 5).map { add(s, 60f + it * 40f).id }.toLongArray()
        assertEquals(1, s.brushes.size)
        restyle(s, ids, pen = fat)
        assertEquals(2, s.brushes.size, "five strokes should share one new entry")
        assertTrue(s.strokes.all { s.brushAt(it.brush).sizeMax == 60f })
    }

    /**
     * Scaling the width and re-stabilising are the same operation as
     * re-brushing, because both are fields on a `Brush`. The plan lists four
     * restyle operations; the code has one.
     */
    @Test
    fun `scaling the width is a re-brush with a wider nib`() {
        val s = sheet()
        val a = add(s, 100f)
        val wider = Brush().apply { sizeMin = thin.sizeMin * 3f; sizeMax = thin.sizeMax * 3f }
        restyle(s, longArrayOf(a.id), pen = wider)
        assertEquals(18f, s.brushAt(s.strokes.single().brush).sizeMax)
    }

    /**
     * A stroke moved onto a wider nib paints outside the rectangle it painted
     * before; a rebuild of only the old rectangle would clip it. And the *old*
     * rectangle still has to be cleared, so the new bounds is the union.
     */
    @Test
    fun `the new bounds covers what it painted and what it will paint`() {
        val s = sheet()
        val a = add(s, 200f)
        val step = assertNotNull(restyle(s, longArrayOf(a.id), pen = fat))
        val now = s.strokes.single()
        assertTrue(now.bounds.height > a.bounds.height * 3f, "${now.bounds} vs ${a.bounds}")
        val damage = step.damage()
        assertTrue(damage.left <= a.bounds.left && damage.right >= a.bounds.right)
        assertTrue(damage.top <= now.bounds.top && damage.bottom >= now.bounds.bottom)
    }

    @Test
    fun `a restyle that changes nothing makes no step`() {
        val s = sheet()
        val a = add(s, 100f, colour = 0xFF112233.toInt())
        assertNull(restyle(s, longArrayOf(a.id), colour = 0xFF112233.toInt()))
        assertNull(restyle(s, longArrayOf(a.id), pen = thin))
        assertNull(restyle(s, LongArray(0), colour = 0xFFFF0000.toInt()))
        assertNull(StrokeRestyle.plan(StrokeOp.Restyle(null, null), longArrayOf(a.id), s, 1))
    }

    @Test
    fun `both at once is one step`() {
        val s = sheet()
        val a = add(s, 100f)
        val b = add(s, 200f)
        val step = assertNotNull(
            restyle(s, longArrayOf(a.id, b.id), colour = 0xFF00AA00.toInt(), pen = fat)
        )
        assertEquals(2, step.removed.size)
        assertEquals(2, step.added.size)
        assertTrue(s.strokes.all { it.colorArgb == 0xFF00AA00.toInt() })
        assertTrue(s.strokes.all { s.brushAt(it.brush).sizeMax == 60f })
    }

    @Test
    fun `a restyle undoes and redoes as one step`() {
        val d = Document(600, 400, enforceOffMainThread = false)
        d.rebuilder = object : SheetRebuilder {
            override fun rebuild(entry: LayerStack.Entry, damage: Bounds) = Unit
        }
        d.layers.apply(LayerOp.AddVector(d.layers.newLayer(), "Ink"))
        val entry = d.layers.active
        val s = entry.vector!!
        val a = s.append(pending(100f, thin, 0xFF000000.toInt()), null)

        val step = assertNotNull(
            StrokeRestyle.plan(
                StrokeOp.Restyle(0xFFCC0000.toInt(), null), longArrayOf(a.id), s, entry.id,
            )
        )
        StrokeRestyle.apply(step, s)
        d.recordVectorEdit(step)
        assertEquals(0xFFCC0000.toInt(), s.strokes.single().colorArgb)

        d.applyUndo()
        assertEquals(0xFF000000.toInt(), s.strokes.single().colorArgb)
        assertEquals(a.id, s.strokes.single().id)
        d.applyRedo()
        assertEquals(0xFFCC0000.toInt(), s.strokes.single().colorArgb)
        d.close()
    }

    /**
     * A restyled stroke is a new record with a new id, so the picked set has to
     * follow it — otherwise the user watches their selection vanish for having
     * changed its colour.
     */
    @Test
    fun `the picked set can be moved onto the replacements`() {
        val s = sheet()
        val a = add(s, 100f)
        val pick = StrokePick()
        pick.apply(StrokeOp.All, s, 1)
        val step = assertNotNull(restyle(s, pick.toArray(), colour = 0xFF0000FF.toInt()))
        assertTrue(pick.prune(s), "the old id should be gone")
        assertEquals(0, pick.count)
        assertTrue(pick.setTo(LongArray(step.added.size) { step.added[it].id }, s, 1))
        assertEquals(1, pick.count)
        assertEquals(step.added.single().id, pick.toArray().single())
        assertTrue(pick.snapshot.active)
        // And an id the sheet does not have is skipped rather than refused.
        assertTrue(pick.setTo(longArrayOf(9999L), s, 1))
        assertEquals(0, pick.count)
        assertEquals(a.id, a.id)
    }
}
