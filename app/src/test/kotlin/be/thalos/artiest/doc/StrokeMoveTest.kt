package be.thalos.artiest.doc

import android.graphics.Matrix
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.SampleLog
import be.thalos.artiest.engine.ink.StrokeRecord
import be.thalos.artiest.engine.ink.StrokeTransform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ik9: dragging, turning and resizing picked strokes.
 *
 * The property worth stating is what a moved stroke *is*: the same hand
 * movement, somewhere else, redrawn there. Not pixels that slid across the
 * page. That is why the grain regenerates where it lands — `GrainTexture`'s
 * shader is anchored to the page and nothing here touches a pixel —
 * and it is `docs/vector-plan.md` trap 2 satisfied by construction rather than
 * by care.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeMoveTest {

    private val pen = Brush().apply { sizeMin = 4f; sizeMax = 12f }

    private fun sheet() = VectorSheet(1000, 800)

    private fun add(s: VectorSheet, y: Float) = s.append(pending(y), null)

    private fun pending(y: Float): PendingStroke {
        val log = SampleLog()
        for (i in 0 until 40) log.add(100f + i * 5f, y, 0.9f, 0.2f, 0.5f, i * 3.1f)
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = 5,
            colorArgb = 0xFF000000.toInt(),
            erase = false,
            brushText = BrushCodec.encode(pen),
            bounds = Bounds.of(94f, y - 6f, 301f, y + 6f),
        )
    }

    private fun values(m: Matrix): FloatArray = FloatArray(9).also { m.getValues(it) }

    private fun move(s: VectorSheet, ids: LongArray, m: Matrix): VectorStep? {
        val step = StrokeMove.plan(StrokeOp.Transform(values(m)), ids, s, 1)
        if (step != null) StrokeMove.apply(step, s)
        return step
    }

    private fun samplesOf(r: StrokeRecord): FloatArray =
        FloatArray(r.floatCount).also { r.decodeInto(it) }

    // ---------------------------------------------------------------- move

    @Test
    fun `a drag moves the ink and changes nothing else about it`() {
        val s = sheet()
        val a = add(s, 200f)
        val before = samplesOf(a)
        val step = assertNotNull(move(s, longArrayOf(a.id), Matrix().apply { setTranslate(60f, -25f) }))
        val now = s.strokes.single()
        val after = samplesOf(now)
        for (i in 0 until a.sampleCount) {
            val o = i * StrokeRecord.STRIDE
            assertEquals(before[o] + 60f, after[o], 0.1f)
            assertEquals(before[o + 1] - 25f, after[o + 1], 0.1f)
            assertEquals(before[o + 2], after[o + 2], 0.01f, "pressure moved")
            assertEquals(before[o + 3], after[o + 3], 0.01f, "tilt moved")
            assertEquals(before[o + 4], after[o + 4], 0.02f, "orientation moved without a turn")
            assertEquals(before[o + 5], after[o + 5], 0.2f, "the clock moved")
        }
        assertEquals(a.seed, now.seed)
        assertEquals(a.dabBase, now.dabBase)
        assertEquals(a.brush, now.brush, "a plain move should not add a brush")
        assertEquals(1, s.brushes.size)
        assertEquals(listOf(a.id), step.removed.map { it.id })
    }

    /**
     * Orientation is the pen's azimuth *on the page*, so a stroke turned a
     * quarter turn was drawn by a hand holding the pen a quarter turn round. A
     * chisel nib that did not turn with its stroke would be a different mark.
     */
    @Test
    fun `a turn takes the pen's azimuth with it`() {
        val s = sheet()
        val a = add(s, 200f)
        val before = samplesOf(a)
        val quarter = (Math.PI / 2).toFloat()
        move(s, longArrayOf(a.id), Matrix().apply { setRotate(90f, 300f, 300f) })
        val after = samplesOf(s.strokes.single())
        for (i in 0 until a.sampleCount) {
            val o = i * StrokeRecord.STRIDE
            assertEquals(before[o + 4] + quarter, after[o + 4], 0.03f)
            assertEquals(before[o + 3], after[o + 3], 0.01f, "tilt is not a page angle")
        }
    }

    @Test
    fun `a turn puts the ink where the matrix says`() {
        val s = sheet()
        val a = add(s, 200f)
        val m = Matrix().apply { setRotate(90f, 300f, 300f) }
        move(s, longArrayOf(a.id), m)
        val after = samplesOf(s.strokes.single())
        val probe = floatArrayOf(100f, 200f)
        m.mapPoints(probe)
        assertEquals(probe[0], after[0], 0.2f)
        assertEquals(probe[1], after[1], 0.2f)
    }

    // --------------------------------------------------------------- scale

    /**
     * Mapping the samples moves the dabs further apart; it does not make them
     * bigger, because a dab's size comes from the brush. A stroke scaled by two
     * that stayed thin is a stretched stroke rather than a bigger one, so the
     * nib is scaled with the mark — Ik10's re-brush, reused.
     */
    @Test
    fun `scaling takes the nib with it`() {
        val s = sheet()
        val a = add(s, 200f)
        move(s, longArrayOf(a.id), Matrix().apply { setScale(2f, 2f, 0f, 0f) })
        val now = s.strokes.single()
        assertEquals(2, s.brushes.size)
        assertEquals(24f, s.brushAt(now.brush).sizeMax, 0.1f)
        assertEquals(8f, s.brushAt(now.brush).sizeMin, 0.1f)
        assertTrue(now.bounds.width > a.bounds.width * 1.8f, "${now.bounds} vs ${a.bounds}")
    }

    @Test
    fun `strokes scaled together share one new brush`() {
        val s = sheet()
        val ids = (0 until 4).map { add(s, 100f + it * 80f).id }.toLongArray()
        move(s, ids, Matrix().apply { setScale(1.5f, 1.5f, 0f, 0f) })
        assertEquals(2, s.brushes.size, "four strokes should share one scaled nib")
    }

    /**
     * Half a percent of scale is below the mask cache's own 3% size bucket, so
     * a table entry per pixel of drag would be a table nobody could read for a
     * difference nobody could see.
     */
    @Test
    fun `a scale too small to see adds no brush`() {
        val s = sheet()
        val a = add(s, 200f)
        move(s, longArrayOf(a.id), Matrix().apply { setScale(1.002f, 1.002f, 0f, 0f) })
        assertEquals(1, s.brushes.size)
    }

    @Test
    fun `a non-uniform scale becomes a uniform nib of the same area`() {
        val m = FloatArray(9)
        Matrix().apply { setScale(4f, 1f) }.getValues(m)
        assertEquals(2f, StrokeTransform.scaleOf(m), 0.001f)
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `an identity transform makes no step`() {
        val s = sheet()
        val a = add(s, 200f)
        assertNull(move(s, longArrayOf(a.id), Matrix()))
        assertNull(move(s, LongArray(0), Matrix().apply { setTranslate(10f, 10f) }))
        assertTrue(StrokeTransform.isIdentity(values(Matrix())))
        assertTrue(!StrokeTransform.isIdentity(values(Matrix().apply { setTranslate(4f, 0f) })))
    }

    @Test
    fun `the op keeps its own copy of the matrix`() {
        val s = sheet()
        val a = add(s, 200f)
        val shared = FloatArray(9)
        Matrix().apply { setTranslate(50f, 0f) }.getValues(shared)
        val op = StrokeOp.Transform(shared)
        Matrix().apply { setTranslate(-500f, -500f) }.getValues(shared)
        val step = assertNotNull(StrokeMove.plan(op, longArrayOf(a.id), s, 1))
        StrokeMove.apply(step, s)
        assertEquals(150f, samplesOf(s.strokes.single())[0], 0.2f)
    }

    // --------------------------------------------------------------- undo

    @Test
    fun `a move undoes and redoes as one step`() {
        val d = Document(1000, 800, enforceOffMainThread = false)
        d.rebuilder = object : SheetRebuilder {
            override fun rebuild(entry: LayerStack.Entry, damage: Bounds) = Unit
        }
        d.layers.apply(LayerOp.AddVector(d.layers.newLayer(), "Ink"))
        val entry = d.layers.active
        val s = entry.vector!!
        val a = s.append(pending(200f), null)
        val was = samplesOf(a)

        val step = assertNotNull(
            StrokeMove.plan(
                StrokeOp.Transform(values(Matrix().apply { setTranslate(70f, 40f) })),
                longArrayOf(a.id), s, entry.id,
            )
        )
        StrokeMove.apply(step, s)
        d.recordVectorEdit(step)
        assertEquals(170f, samplesOf(s.strokes.single())[0], 0.2f)

        d.applyUndo()
        assertEquals(a.id, s.strokes.single().id)
        assertContentEquals(was.map { Math.round(it * 4) }, samplesOf(s.strokes.single()).map { Math.round(it * 4) })
        d.applyRedo()
        assertEquals(170f, samplesOf(s.strokes.single())[0], 0.2f)
        d.close()
    }

    @Test
    fun `the damage covers where it was and where it went`() {
        val s = sheet()
        val a = add(s, 200f)
        val step = assertNotNull(
            move(s, longArrayOf(a.id), Matrix().apply { setTranslate(300f, 0f) })
        )
        val damage = step.damage()
        val moved = s.strokes.single().bounds
        assertTrue(damage.left <= a.bounds.left + 1f, "$damage did not cover where it was")
        assertTrue(damage.right >= moved.right - 1f, "$damage did not cover where it went")
        // The recomputed extent is a little tighter than the one the commit
        // declared, because it comes from the size response rather than from a
        // fixture's guess. Within a nib's width is the right tolerance.
        assertTrue(abs(damage.width - (a.bounds.width + 300f)) < 14f, "$damage")
    }
}
