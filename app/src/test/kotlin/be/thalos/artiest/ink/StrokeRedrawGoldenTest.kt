package be.thalos.artiest.ink

import android.graphics.Color
import be.thalos.artiest.canvas.VectorStress
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.MutableBounds
import be.thalos.artiest.engine.ink.SampleLog
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeCodec
import be.thalos.artiest.engine.ink.StrokeRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ik2's golden, and the plan's second stop condition: **a record renders to the
 * same pixels every time it is rendered.**
 *
 * ## What this is refuting
 *
 * Ik0 rendered the same input twice into two sheets and counted the pixels that
 * differed. The pen and the fineliner came out at zero. The pencil came out at
 * **105 052 per million — one tenth of the page** — and the cause was one line:
 * `BrushPreset.PENCIL` scatters by 1.5 document pixels, and scatter drew from a
 * stream whose value depended on how many dabs had been drawn before it.
 *
 * On a vector sheet that number is not a curiosity. Undo, redo, moving a
 * stroke, changing its colour and zooming in would each have altered the
 * drawing by that much, and nobody would have been able to say which of them
 * did it. `docs/inker-plan.md` says so plainly: *"Do not proceed on 'close
 * enough'."* So this test counts pixels, and the number it allows is zero.
 *
 * ## The whole chain, not just the builder
 *
 * The strokes here are replayed **from packed records**, not from the floats
 * that made them. So a failure can be the hash, the packing, the delta code, or
 * the dab loop, and all four are on the path a real re-render will take.
 *
 * The two renders are separated by unrelated strokes drawn with other seeds
 * into a throwaway sheet, which is the part a single-shot comparison misses:
 * the defect being guarded against is *state carried between strokes*, and a
 * back-to-back pair of renders would not disturb it.
 *
 * Robolectric with NATIVE graphics, like `ScratchLayerTest` and `BigNibBench`:
 * real Skia, real bitmaps, the app's own `DabRasterizer` and `ScratchLayer`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeRedrawGoldenTest {

    // A quarter-page. Big enough that 12 strokes of 200-700 document pixels
    // overlap and composite over each other, small enough that two sheets and a
    // scratch buffer fit in a unit test's heap.
    private val w = 1100
    private val h = 720

    private fun preset(p: BrushPreset): Brush = Brush().also { p.applyTo(it) }

    private fun asset(name: String): Brush =
        BrushCodec.decode(File("src/main/assets/brushes/$name.brush").readText())!!

    /**
     * The nib Ik0 measured at 105 052 ppm, plus two that were already at zero.
     *
     * The two zeroes are not padding: a fix that made every stroke identical to
     * every other stroke would take the pencil to zero as well, and the only
     * thing that catches that is the nibs whose behaviour must not move.
     */
    private fun nibs(): List<Pair<String, Brush>> = listOf(
        "pencil" to preset(BrushPreset.PENCIL),
        "pen" to preset(BrushPreset.PEN),
        "ink-2-fineliner" to asset("ink-2-fineliner"),
    )

    @Test
    fun `a record renders to the same pixels every time`() {
        for ((name, pen) in nibs()) {
            val records = sceneRecords(pen, seedBase = 90_000)
            val first = renderAll(records, pen)
            churn(pen)
            val second = renderAll(records, pen)
            val differing = differingPixels(first, second)
            assertEquals(
                0,
                differing,
                "$name re-rendered ${ppm(differing)} pixels per million differently",
            )
        }
    }

    /**
     * The pencil, named on its own, because it is the number
     * `docs/inker-plan.md` records and the one a reader will come here to
     * check.
     */
    @Test
    fun `the pencil's hundred thousand per million is gone`() {
        val pen = preset(BrushPreset.PENCIL)
        assertTrue(pen.scatter.max > 0f, "the pencil stopped scattering; this test is now vacuous")
        val records = sceneRecords(pen, seedBase = 4242)
        val a = renderAll(records, pen)
        churn(pen)
        val b = renderAll(records, pen)
        assertEquals(0, differingPixels(a, b))
    }

    /**
     * A record that names a different seed draws a different stroke, which is
     * what keeps the grain from repeating across a page of strokes. Without
     * this the test above passes on an engine that has stopped drawing grain.
     */
    @Test
    fun `a different seed in the record changes what is drawn`() {
        val pen = preset(BrushPreset.PENCIL)
        val one = renderAll(sceneRecords(pen, seedBase = 1), pen)
        val two = renderAll(sceneRecords(pen, seedBase = 2), pen)
        assertTrue(
            differingPixels(one, two) > 1000,
            "two seeds drew the same page",
        )
    }

    /**
     * The split property, in pixels: a record replayed with a `dabBase` draws
     * something else, because its dabs carry other indices. Ik8 depends on it,
     * and it is the half of the hash that a test of "the same input gives the
     * same output" cannot see.
     */
    @Test
    fun `a dabBase shifts the grain`() {
        val pen = preset(BrushPreset.PENCIL)
        val base = sceneRecords(pen, seedBase = 7)
        val shifted = base.map { r ->
            StrokeRecord(
                id = r.id, brush = r.brush, colorArgb = r.colorArgb, erase = r.erase,
                seed = r.seed, dabBase = 37, clip = r.clip, bounds = r.bounds,
                packed = r.copyPackedBytes(), sampleCount = r.sampleCount,
            )
        }
        assertTrue(differingPixels(renderAll(base, pen), renderAll(shifted, pen)) > 1000)
    }

    // -------------------------------------------------------------- the rig

    /**
     * `VectorStress`'s page of inking, packed into records the way Ik3's commit
     * path will pack it.
     *
     * The bounds each record carries is the one the dab loop accumulated for
     * it, which is what a real commit has in hand at pen-up — computing it any
     * other way would be testing a bounds this code does not use.
     */
    private fun sceneRecords(pen: Brush, seedBase: Int): List<StrokeRecord> {
        val builder = StrokeBuilder(pen)
        val log = SampleLog()
        val out = ArrayList<StrokeRecord>()
        VectorStress.scene(12, w, h, seed = 3).forEachIndexed { i, s ->
            val seed = seedBase + i
            log.reset()
            builder.begin(Color.BLACK, seed)
            for (j in 0 until s.count) {
                val nanos = 1_000_000_000L + (s.timeMillis(j) * 1_000_000f).toLong()
                builder.addTilt(s.tilt(j), s.orientation(j), nanos)
                builder.add(s.x(j), s.y(j), s.pressure(j), nanos)
                log.add(s.x(j), s.y(j), s.pressure(j), s.tilt(j), s.orientation(j), s.timeMillis(j))
            }
            val stroke = builder.end()
            out.add(
                StrokeRecord(
                    id = i.toLong(),
                    brush = 0,
                    colorArgb = Color.BLACK,
                    erase = false,
                    seed = seed,
                    dabBase = 0,
                    clip = StrokeRecord.NO_CLIP,
                    bounds = stroke.bounds,
                    packed = log.pack(),
                    sampleCount = log.count,
                )
            )
        }
        return out
    }

    /** Replay one record through the builder, exactly as Ik4's rebuild will. */
    private fun replay(record: StrokeRecord, builder: StrokeBuilder, scratch: FloatArray): Stroke {
        val n = record.decodeInto(scratch)
        builder.begin(record.colorArgb, record.seed, record.dabBase)
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            val nanos = 1_000_000_000L + (scratch[o + 5] * 1_000_000f).toLong()
            builder.addTilt(scratch[o + 3], scratch[o + 4], nanos)
            builder.add(scratch[o], scratch[o + 1], scratch[o + 2], nanos)
        }
        return builder.end()
    }

    private fun renderAll(records: List<StrokeRecord>, pen: Brush): IntArray {
        val builder = StrokeBuilder(pen)
        val rast = DabRasterizer(w, h, StampCache()).also { it.hardness = pen.hardness }
        val scratch = ScratchLayer(maxWidth = w, maxHeight = h)
        val sheet = Layer(w, h, enforceOffMainThread = false)
        var widest = 0
        for (r in records) {
            val stroke = replay(r, builder, FloatArray(r.floatCount))
            widest = maxOf(widest, stroke.dabCount)
            scratch.begin(stroke.bounds)
            rast.drawDry(scratch.canvasInDocSpace()!!, stroke)
            sheet.write { scratch.compositeInto(it, pen.opacity) }
        }
        assertTrue(widest > 50, "the scene laid only $widest dabs; it is not testing much")
        val pixels = IntArray(w * h)
        sheet.read { it.getPixels(pixels, 0, w, 0, 0, w, h) }
        scratch.release()
        return pixels
    }

    /**
     * Strokes with other seeds, into a sheet nobody looks at. The point is to
     * leave the engine in a different state than it was in before the first
     * render — which is the state a real session is in when it re-renders.
     */
    private fun churn(pen: Brush) {
        val builder = StrokeBuilder(pen)
        val rast = DabRasterizer(w, h, StampCache()).also { it.hardness = pen.hardness }
        val scratch = ScratchLayer(maxWidth = w, maxHeight = h)
        val sheet = Layer(w, h, enforceOffMainThread = false)
        for (k in 0 until 5) {
            builder.begin(Color.BLACK, seed = 500_000 + k)
            for (i in 0 until 40) {
                val nanos = 1_000_000_000L + i * 3_107_855L
                builder.add(20f + i * 7f, 30f + k * 11f, 0.5f, nanos)
            }
            val s = builder.end()
            scratch.begin(s.bounds)
            rast.drawDry(scratch.canvasInDocSpace()!!, s)
            sheet.write { scratch.compositeInto(it, pen.opacity) }
        }
        scratch.release()
    }

    private fun differingPixels(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }

    private fun ppm(n: Int): Long = n.toLong() * 1_000_000L / (w.toLong() * h)
}
