package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import be.thalos.artiest.canvas.DabBatch
import be.thalos.artiest.canvas.VectorStress
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.MaskCache
import be.thalos.artiest.engine.brush.MaskGenerator
import be.thalos.artiest.engine.brush.MaskSpec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertTrue

/**
 * What a dab costs as the nib gets large, and which of the cheaper paths is
 * worth taking. The evidence behind `docs/big-nib-plan.md`.
 *
 * ## What these numbers are
 *
 * **Host numbers on software Skia**, the same arrangement `CompositeBench`
 * uses — Robolectric's NATIVE graphics mode running real Skia against real
 * bitmaps on this machine's CPU. Unlike that bench's stack rows, *every* row
 * here is CPU work in the app as well: the mask generator is the engine's own
 * arithmetic, and the dab blits and the composite go into `Bitmap`s the render
 * thread owns — `ScratchLayer`'s buffer and `Layer`'s pixels.
 *
 * What does not carry across is the absolute scale. A tablet's CPU is several
 * times slower at fill rate than this one, and the multiplier is exactly what
 * the plan's first work item goes to the device to find. **The ratios between
 * the rows are the part to trust**, because every row is the same kind of work
 * on the same machine.
 *
 * ## Why it asserts nothing about time
 *
 * `DabLoopBench`'s rule: counts are asserted, times are only reported. A timing
 * assertion fails on a loaded laptop, has its tolerance widened until it cannot
 * fail, and then reports nothing.
 *
 * Run one and read the file:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*BigNibBench' \
 *     -Dartiest.bench.out=/tmp/big-nib.txt
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigNibBench {

    private val log = File(System.getProperty("artiest.bench.out") ?: "/tmp/big-nib.txt")

    private fun say(line: String) {
        log.appendText(line + "\n")
        println(line)
    }

    private val w = 2160
    private val h = 3300

    /** A shipped preset, read from the assets the APK carries. */
    private fun brush(name: String): Brush =
        BrushCodec.decode(File("src/main/assets/brushes/$name.brush").readText())!!

    /**
     * The commit path, nib by nib: what one stroke costs and where it goes.
     *
     * The three columns that matter are the last three. `blit ms` is the dab
     * loop into the scratch buffer, `scr ms` is opening and clearing that
     * buffer, `comp ms` is the one composite onto the sheet.
     */
    @Test
    fun `what a stroke costs, nib by nib`() {
        val nibs = listOf(
            "ink-2-fineliner", "pencil-1-hard", "chalk-details",
            "bristles-3-large-smooth", "airbrush-soft",
        )
        val scene = VectorStress.scene(12, w, h, seed = 5)
        say("")
        say(
            "nib                      size  hard  dabs  maskpx  maskKiB  gen ms  " +
                "blit ms  scr ms  comp ms  TOTAL ms  per stroke  us/dab",
        )
        for (name in nibs) {
            val pen = brush(name)
            val builder = StrokeBuilder(pen)
            val rast = DabRasterizer(w, h, StampCache()).also {
                it.hardness = pen.hardness
                it.tip = Tips.find(pen.tip)
            }
            val scratch = ScratchLayer(maxWidth = w, maxHeight = h)
            val sheet = Layer(w, h, enforceOffMainThread = false)
            val strokes = scene.map { build(it, builder) }
            val dabs = strokes.sumOf { it.dabCount }

            var maxD = 0f
            for (s in strokes) for (i in 0 until s.dabCount) maxD = maxOf(maxD, s.radius(i) * 2f)
            val genStart = System.nanoTime()
            val mask = MaskGenerator.generate(
                MaskSpec(maxD, pen.hardness, 1f, 0f, Tips.find(pen.tip)),
            )
            val genMs = (System.nanoTime() - genStart) / 1e6

            render(strokes, rast, scratch, sheet)
            sheet.blank()

            var blit = 0L
            var scr = 0L
            var comp = 0L
            val t0 = System.nanoTime()
            for (s in strokes) {
                val a = System.nanoTime()
                scratch.begin(s.bounds)
                val b = System.nanoTime()
                rast.drawDry(scratch.canvasInDocSpace()!!, s)
                val c = System.nanoTime()
                sheet.write { scratch.compositeInto(it, pen.opacity) }
                val d = System.nanoTime()
                scr += b - a; blit += c - b; comp += d - c
            }
            val total = (System.nanoTime() - t0) / 1e6
            say(
                "%-22s %5.0f %5.2f %5d %7d %8d %7.1f %8.1f %7.1f %8.1f %9.1f %11.1f %7.0f".format(
                    name, pen.sizeMax, pen.hardness, dabs, mask.width, mask.byteCount / 1024,
                    genMs, blit / 1e6, scr / 1e6, comp / 1e6, total, total / scene.size,
                    blit / 1e3 / dabs,
                ),
            )
            assertTrue(dabs > 0, "$name laid no dabs")
            sheet.close()
        }
    }

    /** One 600 px soft dab, every way it could be drawn. */
    @Test
    fun `one big soft dab, six ways`() {
        val dst = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(dst)
        val mask = alpha8(600)
        say("")
        say("path                                        ms/$N dabs   ms/dab")

        val filtered = Paint().apply { isFilterBitmap = true; color = Color.BLACK; alpha = 90 }
        val point = Paint().apply { isFilterBitmap = false; color = Color.BLACK; alpha = 90 }
        row("ALPHA_8 mask, filtered, fractional (today)") { at ->
            c.drawBitmap(mask, at + 0.37f, at + 0.11f, filtered)
        }
        row("ALPHA_8 mask, point, fractional") { at ->
            c.drawBitmap(mask, at + 0.37f, at + 0.11f, point)
        }
        row("ALPHA_8 mask, point, integer") { at ->
            c.drawBitmap(mask, at, at, point)
        }

        // Pre-coloured: faster, but it costs the one-mask-serves-every-colour
        // property StampCache exists for, for 20% over a point-sampled blit.
        val argb = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(mask, 0f, 0f, Paint().apply { color = Color.BLACK })
        val plain = Paint().apply { isFilterBitmap = false; alpha = 90 }
        row("ARGB_8888 pre-coloured, point, integer") { at ->
            c.drawBitmap(argb, at, at, plain)
        }

        // No bitmap at all. Skia's gradient blitter is not the cheap path.
        val m = Matrix()
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 90
            shader = RadialGradient(
                0f, 0f, 300f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        row("RadialGradient drawCircle") { at ->
            m.setTranslate(at + 300f, at + 300f)
            grad.shader!!.setLocalMatrix(m)
            c.drawCircle(at + 300f, at + 300f, 300f, grad)
        }

        // A scratch buffer at a quarter of document resolution: sixteen times
        // fewer destination pixels, and one upscale at composite time.
        val quarter = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val qc = Canvas(quarter)
        val qmask = alpha8(150)
        row("quarter-res scratch, point, integer", span = 220) { at ->
            qc.drawBitmap(qmask, at, at, point)
        }
        val up = Paint().apply { isFilterBitmap = true }
        val t0 = System.nanoTime()
        repeat(20) { c.drawBitmap(quarter, null, RectF(0f, 0f, 1600f, 1600f), up) }
        say(
            "  ...plus its upscale at composite, 1600x1600, once a stroke: %.1f ms"
                .format((System.nanoTime() - t0) / 1e6 / 20),
        )
    }

    /**
     * Does building the mask smaller and letting the blit scale it up help?
     *
     * It does not help the blit — that is destination-bound — but it is the
     * whole of the generation cost and the whole of the cache footprint.
     */
    @Test
    fun `a small mask scaled up against one built full size`() {
        val dst = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(dst)
        val paint = Paint().apply { isFilterBitmap = true; color = Color.BLACK; alpha = 90 }
        say("")
        say("wanted  built at   gen ms   blit ms/$N   KiB")
        for (built in intArrayOf(600, 256, 128, 64)) {
            val g0 = System.nanoTime()
            val m = MaskGenerator.generate(MaskSpec(built.toFloat(), 0.4f, 1f, 0f))
            val genMs = (System.nanoTime() - g0) / 1e6
            val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(m.alpha))
            val scale = 602f / m.width
            val rect = RectF(0f, 0f, m.width * scale, m.height * scale)
            repeat(20) { c.drawBitmap(bmp, null, rect, paint) }
            val t0 = System.nanoTime()
            repeat(N) {
                rect.offsetTo((it % 900).toFloat(), (it % 900).toFloat())
                c.drawBitmap(bmp, null, rect, paint)
            }
            say(
                "%6d %9d %8.1f %12.1f %5d".format(
                    600, built, genMs, (System.nanoTime() - t0) / 1e6, m.byteCount / 1024,
                ),
            )
        }
    }

    /**
     * The latent one: a big soft nib whose size is driven by pressure.
     *
     * `MaskCache`'s budget is 4 MiB and a 600 px mask is 353 KiB, so eleven
     * buckets fill it. A pressure ramp walks a hundred and fifty.
     */
    @Test
    fun `a size ramp on a big nib against the mask budget`() {
        say("")
        for ((label, maxD, hard) in listOf(
            Triple("600 px soft nib", 600f, 0.4f),
            Triple(" 24 px pencil  ", 24f, 0.85f),
        )) {
            val cache = MaskCache()
            val t0 = System.nanoTime()
            var dabs = 0
            for (pass in 0 until 4) {
                for (step in 0 until 60) {
                    val p = if (pass % 2 == 0) step / 60f else 1f - step / 60f
                    cache.get(MaskSpec(maxD * 0.02f + p * maxD * 0.98f, hard, 1f, 0f))
                    dabs++
                }
            }
            say(
                "%s ramped: %3d dabs, %7.1f ms of mask work, %s"
                    .format(label, dabs, (System.nanoTime() - t0) / 1e6, cache),
            )
        }
    }

    /**
     * The scratch buffer across a stroke, batch by batch, as the wet pass
     * drives it: one batch per 90 Hz frame.
     *
     * `allocs` is the column to read. `ensureCovers` releases its bitmap before
     * reallocating, so **every batch that reaches left or up of the origin
     * allocates a whole new buffer**, however much room the old one had.
     */
    @Test
    fun `the scratch buffer across a stroke, batch by batch`() {
        say("")
        say(
            "nib                    strokes  dabs  allocs  growths  scratch ms  " +
                "dab ms  comp ms  used at end",
        )
        for (name in listOf("pencil-1-hard", "airbrush-soft")) {
            val pen = brush(name)
            val builder = StrokeBuilder(pen)
            val rast = DabRasterizer(w, h, StampCache()).also {
                it.hardness = pen.hardness
                it.tip = Tips.find(pen.tip)
            }
            val scratch = ScratchLayer(maxWidth = w, maxHeight = h)
            val sheet = Layer(w, h, enforceOffMainThread = false)
            val scene = VectorStress.scene(8, w, h, seed = 11)
            val perFrame = (VectorStress.SAMPLE_HZ / 90f).toInt().coerceAtLeast(1)

            var scratchNs = 0L
            var dabNs = 0L
            var compNs = 0L
            var dabs = 0
            var used = ""
            for (record in scene) {
                builder.begin(Color.BLACK)
                var drawn = 0
                var i = 0
                var first = true
                while (i < record.count) {
                    val end = minOf(i + perFrame, record.count)
                    while (i < end) {
                        val nanos = 1_000_000_000L + (record.timeMillis(i) * 1e6f).toLong()
                        builder.addTilt(record.tilt(i), record.orientation(i), nanos)
                        builder.add(record.x(i), record.y(i), record.pressure(i), nanos)
                        i++
                    }
                    if (builder.dabCount == drawn) continue
                    val b = batchBounds(builder, drawn)
                    val a0 = System.nanoTime()
                    if (first) { scratch.begin(b); first = false } else scratch.ensureCovers(b)
                    val a1 = System.nanoTime()
                    val sc = scratch.canvasInDocSpace()!!
                    val batch = DabBatch(2048)
                    batch.colorArgb = Color.BLACK
                    while (drawn < builder.dabCount && batch.count < batch.capacity) {
                        batch.add(
                            builder.x(drawn), builder.y(drawn), builder.radius(drawn),
                            builder.aspect(drawn), builder.rotation(drawn), builder.flow(drawn),
                        )
                        drawn++
                    }
                    rast.drawInto(sc, batch, 1f)
                    val a2 = System.nanoTime()
                    scratchNs += a1 - a0
                    dabNs += a2 - a1
                }
                dabs += builder.end().dabCount
                used = "${scratch.usedWidth}x${scratch.usedHeight}"
                val c0 = System.nanoTime()
                sheet.write { scratch.compositeInto(it, pen.opacity) }
                compNs += System.nanoTime() - c0
            }
            say(
                "%-22s %7d %5d %7d %8d %11.1f %7.1f %8.1f  %s".format(
                    name, scene.size, dabs, scratch.allocations, scratch.growths,
                    scratchNs / 1e6, dabNs / 1e6, compNs / 1e6, used,
                ),
            )
            assertTrue(scratch.allocations > 0)
            sheet.close()
        }
    }

    // ---- plumbing ----------------------------------------------------------

    private val N = 200

    /**
     * [N] dabs, walked down the diagonal so no two land on the same pixels and
     * the destination cache is treated the way a stroke treats it. [span] is
     * how far they walk before wrapping, which is the destination's own size
     * less the dab's.
     */
    private fun row(name: String, span: Int = 900, block: (Float) -> Unit) {
        repeat(20) { block((it % span).toFloat()) }
        val t0 = System.nanoTime()
        repeat(N) { block((it % span).toFloat()) }
        val ms = (System.nanoTime() - t0) / 1e6
        say("%-43s %10.1f %8.3f".format(name, ms, ms / N))
    }

    private fun alpha8(d: Int, hardness: Float = 0.4f): Bitmap {
        val m = MaskGenerator.generate(MaskSpec(d.toFloat(), hardness, 1f, 0f))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ALPHA_8)
        b.copyPixelsFromBuffer(ByteBuffer.wrap(m.alpha))
        return b
    }

    private fun batchBounds(b: StrokeBuilder, from: Int): Bounds {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var bo = -Float.MAX_VALUE
        for (i in from until b.dabCount) {
            val rad = b.radius(i) + 1f
            l = minOf(l, b.x(i) - rad); t = minOf(t, b.y(i) - rad)
            r = maxOf(r, b.x(i) + rad); bo = maxOf(bo, b.y(i) + rad)
        }
        return Bounds.of(l, t, r, bo)
    }

    private fun render(
        strokes: List<Stroke>,
        rast: DabRasterizer,
        scratch: ScratchLayer,
        sheet: Layer,
    ) {
        for (s in strokes) {
            scratch.begin(s.bounds)
            rast.drawDry(scratch.canvasInDocSpace()!!, s)
            sheet.write { scratch.compositeInto(it, 1f) }
        }
    }

    private fun build(record: VectorStress.Record, builder: StrokeBuilder): Stroke {
        builder.begin(Color.BLACK)
        for (i in 0 until record.count) {
            val nanos = 1_000_000_000L + (record.timeMillis(i) * 1_000_000f).toLong()
            builder.addTilt(record.tilt(i), record.orientation(i), nanos)
            builder.add(record.x(i), record.y(i), record.pressure(i), nanos)
        }
        return builder.end()
    }
}
