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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ik8's three eraser modes, against a real sheet.
 *
 * The one worth reading first is **to the junction**. `docs/inker-plan.md`
 * calls it the single most-praised vector feature in CSP and the reason inkers
 * use vector layers at all, and it has its own stop condition: *"if, on the
 * tablet, the junction it picks is not the junction the hand meant, the rest of
 * Tier 1 is not worth building alone."* These tests say which junction the
 * arithmetic picks; the tablet says whether that is the one the hand meant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeEraserTest {

    private val pen = Brush().apply { sizeMin = 6f; sizeMax = 6f }

    private fun sheet() = VectorSheet(800, 600)

    /** A straight stroke of [n] samples from a to b. */
    private fun add(
        sheet: VectorSheet,
        x0: Float, y0: Float, x1: Float, y1: Float,
        n: Int = 120,
    ) = sheet.append(pendingLine(x0, y0, x1, y1, n), null)

    private fun pendingLine(x0: Float, y0: Float, x1: Float, y1: Float, n: Int): PendingStroke {
        val log = SampleLog()
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            log.add(x, y, 1f, 0f, 0f, i * 3.1f)
            l = minOf(l, x - 3f); t = minOf(t, y - 3f)
            r = maxOf(r, x + 3f); b = maxOf(b, y + 3f)
        }
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = 11,
            colorArgb = 0xFF000000.toInt(),
            erase = false,
            brushText = BrushCodec.encode(pen),
            bounds = Bounds.of(l, t, r, b),
        )
    }

    /** An eraser gesture: a short drag through the given points. */
    private fun rub(vararg xy: Float): Path = Path().apply {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
    }

    private fun erase(
        sheet: VectorSheet,
        path: Path,
        mode: EraseMode,
        radius: Float = 8f,
    ): VectorStep? {
        val eraser = StrokeEraser()
        val step = eraser.plan(StrokeOp.Erase(path, radius, mode), sheet, 1)
        if (step != null) eraser.apply(step, sheet)
        return step
    }

    // ----------------------------------------------------------- whole

    @Test
    fun `whole takes every stroke the eraser touched and no others`() {
        val s = sheet()
        val a = add(s, 100f, 100f, 400f, 100f)
        val b = add(s, 100f, 300f, 400f, 300f)
        val step = assertNotNull(erase(s, rub(200f, 95f, 260f, 105f), EraseMode.WHOLE))
        assertEquals(listOf(a.id), step.removed.map { it.id })
        assertTrue(step.added.isEmpty())
        assertEquals(listOf(b.id), s.strokes.map { it.id })
    }

    @Test
    fun `an eraser over blank paper does nothing at all`() {
        val s = sheet()
        add(s, 100f, 100f, 400f, 100f)
        assertNull(
            erase(s, rub(100f, 500f, 300f, 500f), EraseMode.WHOLE),
            "an erase over nothing should not make an undo step",
        )
        assertEquals(1, s.size)
    }

    @Test
    fun `a tap with the eraser takes the stroke under it`() {
        val s = sheet()
        val a = add(s, 100f, 100f, 400f, 100f)
        // A path with no length: a tap. `PathMeasure` measures nothing along
        // it, so the eraser has to fall back to where the path is.
        val step = assertNotNull(erase(s, Path().apply { moveTo(250f, 100f) }, EraseMode.WHOLE))
        assertEquals(listOf(a.id), step.removed.map { it.id })
    }

    // ------------------------------------------------------------- part

    @Test
    fun `part takes the stretch it passed over and leaves the ends`() {
        val s = sheet()
        val a = add(s, 100f, 100f, 400f, 100f)
        val step = assertNotNull(erase(s, rub(240f, 100f, 260f, 100f), EraseMode.PART))
        assertEquals(listOf(a.id), step.removed.map { it.id })
        assertEquals(2, step.added.size, "the stroke should be in two pieces")
        assertEquals(2, s.size)
        // The head ends before the rub and the tail starts after it.
        val head = s.strokes[0]
        val tail = s.strokes[1]
        assertTrue(head.bounds.right < 250f, "the head reaches ${head.bounds.right}")
        assertTrue(tail.bounds.left > 250f, "the tail starts at ${tail.bounds.left}")
        assertTrue(tail.dabBase > 0, "the tail forgot which dab it starts on")
        assertTrue(tail.startMillis > 0f, "the tail forgot where it was in the stroke")
    }

    @Test
    fun `part at an end leaves one piece`() {
        val s = sheet()
        add(s, 100f, 100f, 400f, 100f)
        val step = assertNotNull(erase(s, rub(100f, 100f, 160f, 100f), EraseMode.PART))
        assertEquals(1, step.added.size)
        assertTrue(s.strokes.single().bounds.left > 140f)
    }

    /**
     * A drag that crosses one stroke twice must not take the untouched middle
     * with it — that is a stretch of ink the user watched the eraser miss.
     */
    @Test
    fun `crossing a stroke twice leaves what was between the two touches`() {
        val s = sheet()
        add(s, 100f, 200f, 500f, 200f)
        val step = assertNotNull(
            erase(s, rub(180f, 200f, 190f, 200f, 190f, 60f, 400f, 60f, 400f, 200f, 410f, 200f),
                EraseMode.PART)
        )
        assertEquals(3, step.added.size, "the middle was taken: ${step.added.size} pieces")
        assertEquals(3, s.size)
    }

    // ------------------------------------------------------ to the junction

    /**
     * The gesture the feature exists for: a line overshoots a junction, and one
     * tap on the overshoot takes it back to the crossing.
     */
    @Test
    fun `to the junction rubs an overshoot back to the line it crossed`() {
        val s = sheet()
        val across = add(s, 100f, 200f, 500f, 200f)
        add(s, 300f, 60f, 300f, 340f)
        val step = assertNotNull(erase(s, rub(450f, 200f, 460f, 200f), EraseMode.TO_JUNCTION))
        assertEquals(listOf(across.id), step.removed.map { it.id })
        assertEquals(1, step.added.size)
        val left = step.added.single()
        // What survives runs from the start of the stroke to the junction.
        assertTrue(left.bounds.left < 110f, "${left.bounds}")
        assertTrue(left.bounds.right in 290f..320f, "it stopped at ${left.bounds.right}, not 300")
    }

    @Test
    fun `to the junction between two crossings takes only the span between them`() {
        val s = sheet()
        add(s, 100f, 200f, 600f, 200f)
        add(s, 250f, 60f, 250f, 340f)
        add(s, 450f, 60f, 450f, 340f)
        val step = assertNotNull(erase(s, rub(350f, 200f, 360f, 200f), EraseMode.TO_JUNCTION))
        assertEquals(2, step.added.size, "it should leave a head and a tail")
        val head = step.added[0]
        val tail = step.added[1]
        assertTrue(head.bounds.right in 240f..270f, "the head stopped at ${head.bounds.right}")
        assertTrue(tail.bounds.left in 435f..465f, "the tail started at ${tail.bounds.left}")
    }

    /**
     * With nothing crossing it, to-the-junction takes the whole stroke — which
     * is what "back to the nearest junction" means when there is none.
     */
    @Test
    fun `to the junction with no crossing takes the stroke`() {
        val s = sheet()
        val a = add(s, 100f, 200f, 500f, 200f)
        val step = assertNotNull(erase(s, rub(300f, 200f, 310f, 200f), EraseMode.TO_JUNCTION))
        assertEquals(listOf(a.id), step.removed.map { it.id })
        assertTrue(step.added.isEmpty())
        assertTrue(s.isEmpty)
    }

    // ------------------------------------------------------------- the step

    @Test
    fun `an erase undoes and redoes as one step`() {
        val d = Document(800, 600, enforceOffMainThread = false)
        d.rebuilder = object : SheetRebuilder {
            override fun rebuild(entry: LayerStack.Entry, damage: Bounds) = Unit
        }
        d.layers.apply(LayerOp.AddVector(d.layers.newLayer(), "Ink"))
        val entry = d.layers.active
        val s = entry.vector!!
        val a = s.append(pendingLine(100f, 100f, 400f, 100f, 120), null)
        s.append(pendingLine(100f, 300f, 400f, 300f, 120), null)

        val eraser = StrokeEraser()
        val step = assertNotNull(
            eraser.plan(StrokeOp.Erase(rub(240f, 100f, 260f, 100f), 8f, EraseMode.PART), s, entry.id)
        )
        eraser.apply(step, s)
        d.recordVectorEdit(step)
        assertEquals(3, s.size)

        d.applyUndo()
        assertEquals(2, s.size)
        assertEquals(listOf(a.id, a.id + 1), s.strokes.map { it.id })

        d.applyRedo()
        assertEquals(3, s.size)
        d.close()
    }

    @Test
    fun `deleting the picked strokes takes exactly those`() {
        val s = sheet()
        val a = add(s, 100f, 100f, 400f, 100f)
        val b = add(s, 100f, 300f, 400f, 300f)
        val eraser = StrokeEraser()
        val step = assertNotNull(eraser.planDelete(longArrayOf(b.id), s, 1))
        eraser.apply(step, s)
        assertEquals(listOf(a.id), s.strokes.map { it.id })
        assertNull(eraser.planDelete(LongArray(0), s, 1))
        assertNull(eraser.planDelete(longArrayOf(9999L), s, 1))
    }

    @Test
    fun `the damage rectangle covers what went and what came back`() {
        val s = sheet()
        val a = add(s, 100f, 100f, 400f, 100f)
        val step = assertNotNull(erase(s, rub(240f, 100f, 260f, 100f), EraseMode.PART))
        val damage = step.damage()
        assertTrue(damage.left <= a.bounds.left + 1f, "$damage vs ${a.bounds}")
        assertTrue(damage.right >= a.bounds.right - 1f, "$damage vs ${a.bounds}")
    }
}
