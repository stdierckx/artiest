package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertTrue

/**
 * Phase 3's before-picture: what a selection, a stack composite and a
 * resampling blit cost, in Skia, at this document's size.
 *
 * `docs/phase3-plan.md` is built on these numbers and cites them by name. They
 * decided three things it would otherwise have had to guess at: that a
 * full-page selection mask can be rebuilt from scratch on every edit, that a
 * floating selection must be resampled once at drop rather than once a frame,
 * and that the compositor is the thing to look at before layer blend modes are
 * added to it.
 *
 * ## What these numbers are, and what they are not
 *
 * **They are host numbers, on software Skia.** Robolectric's NATIVE graphics
 * mode runs real Skia against real bitmaps on this machine's CPU. That makes
 * three of the rows below directly applicable, because the operations they
 * time genuinely run on the CPU in the app as well:
 *
 * - the selection mask, which is an `ALPHA_8` `Bitmap` the app fills itself;
 * - `Path.op`, which is geometry and touches no pixels at all;
 * - the drop blit and the scratch mask, which both write into `Bitmap`s the
 *   render thread owns — `Layer`'s pixels and `ScratchLayer`'s buffer.
 *
 * **The stack rows are not.** `CanvasFrontBufferedRenderer` holds a
 * `RenderNode` and a `CanvasBufferedRenderer` — read out of the 1.0.4 aar with
 * `javap`, not assumed — so the `Canvas` handed to `onDrawMultiBufferedLayer`
 * is a hardware recording canvas and those blits happen on the GPU. The rows
 * are kept anyway because they are the right *shape*: they say what the work
 * grows with, and they are what a software exporter — `PngExporter`, which
 * genuinely does composite on the CPU — actually pays. The device number is
 * Phase 3's first work item, and until it exists nothing here may be quoted as
 * the app's frame cost.
 *
 * ## Why it asserts, and why it does not assert a time
 *
 * `DabLoopBench`'s rule stands: **counts are asserted, times are only
 * reported.** A timing assertion fails on a loaded laptop, gets its tolerance
 * widened until it cannot fail, and then reports nothing. What is asserted here
 * is that the operations *happened* — the mask has both empty and full pixels,
 * the rotated blit put ink where the matrix says. Without that this file would
 * be vulnerable to the trap `DabRasterizerTest`'s header names: drop the
 * `@GraphicsMode` and Robolectric records draw calls instead of rasterizing
 * them, every timing collapses to nothing, and the bench reports that Skia has
 * become infinitely fast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompositeBench {

    private val w = Document.DEFAULT_WIDTH_PX
    private val h = Document.DEFAULT_HEIGHT_PX

    /** The tablet's panel, which is what a dry frame actually covers. */
    private val viewW = 1600
    private val viewH = 2560

    /** 1600 / 3300, the fit zoom this document lands at on this device. */
    private val fitScale = 0.4848f

    @Test
    fun bench() {
        val report = StringBuilder()
        report.append("artiest composite bench — Phase 3 before-picture\n")
        report.append("HOST, software Skia. See the KDoc: the stack rows are not the app's frame cost.\n")
        report.append("document ${w}x$h, view ${viewW}x$viewH, fit $fitScale\n\n")

        selectionRows(report)
        stackRows(report)
        resampleRows(report)

        println(report)
    }

    // ---- the selection ------------------------------------------------------

    private fun selectionRows(report: StringBuilder) {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(mask)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val lasso = lasso(0)

        report.append(
            row("mask rebuild: clear + AA path fill, full page A_8", 20) {
                maskCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), clear)
                maskCanvas.drawPath(lasso, fill)
            },
        )
        // The mask is what everything below leans on, so it is the one thing
        // here that is checked rather than timed.
        assertTrue(Color.alpha(mask.getPixel(w / 2, h / 2)) > 250, "inside the lasso is opaque")
        assertTrue(Color.alpha(mask.getPixel(4, 4)) == 0, "outside the lasso is empty")

        val other = lasso(3)
        report.append(row("Path.op UNION, two 240-segment lassos", 200) { Path(lasso).op(other, Path.Op.UNION) })

        report.append(
            row("allocate + clear a full page A_8 mask", 20) {
                Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { eraseColor(0); recycle() }
            },
        )

        // Masking the scratch buffer is how ink is confined to the selection:
        // one DST_IN of the mask's sub-rectangle over the buffer's used area.
        val scratch = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val scratchCanvas = Canvas(scratch)
        val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        report.append(
            row("DST_IN a 400x400 scratch by the mask", 200) {
                scratchCanvas.drawBitmap(mask, Rect(1400, 900, 1800, 1300), Rect(0, 0, 400, 400), dstIn)
            },
        )
        report.append("\n")
    }

    // ---- the stack ----------------------------------------------------------

    private fun stackRows(report: StringBuilder) {
        val frame = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frame)
        val fit = Matrix().apply { setScale(fitScale, fitScale) }
        val sheets = Array(LayerStack.MAX_LAYERS) { i ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawColor(Color.argb(50, 10 * i, 20, 30))
            }
        }
        val plain = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
        val multiply = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = false
            blendMode = BlendMode.MULTIPLY
        }

        fun stack(n: Int, blended: Int): String = time(20) {
            frameCanvas.drawColor(Color.WHITE)
            val save = frameCanvas.save()
            frameCanvas.concat(fit)
            for (i in 0 until n) {
                frameCanvas.drawBitmap(sheets[i], 0f, 0f, if (i < blended) multiply else plain)
            }
            frameCanvas.restoreToCount(save)
        }

        for (n in intArrayOf(1, 2, 4, LayerStack.MAX_LAYERS)) {
            report.append(line("dry frame shape: $n plain sheets at fit zoom", stack(n, 0)))
        }
        report.append(line("dry frame shape: 8 sheets, 1 MULTIPLY", stack(LayerStack.MAX_LAYERS, 1)))
        report.append(line("dry frame shape: 8 sheets, 3 MULTIPLY", stack(LayerStack.MAX_LAYERS, 3)))

        // What a cached compositor would cost instead: everything below the
        // active sheet in one bitmap, everything above it in another, and the
        // active sheet between them. Three blits whatever the stack holds.
        val below = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val above = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        report.append(
            row("dry frame shape: cached backdrop, 3 blits", 20) {
                frameCanvas.drawColor(Color.WHITE)
                val save = frameCanvas.save()
                frameCanvas.concat(fit)
                frameCanvas.drawBitmap(below, 0f, 0f, plain)
                frameCanvas.drawBitmap(sheets[0], 0f, 0f, plain)
                frameCanvas.drawBitmap(above, 0f, 0f, plain)
                frameCanvas.restoreToCount(save)
            },
        )
        report.append("\n")
    }

    // ---- the float ----------------------------------------------------------

    private fun resampleRows(report: StringBuilder) {
        val filtered = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(page).drawColor(Color.argb(200, 30, 30, 30))
        val quarter = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
        Canvas(quarter).drawColor(Color.argb(200, 30, 30, 30))

        // The live preview: the float, rotated, into the frame the panel shows.
        val frame = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frame)
        val toView = Matrix().apply { setRotate(17f, w / 2f, h / 2f); postScale(fitScale, fitScale) }
        report.append(row("live: rotate a full page into the view", 20) { frameCanvas.drawBitmap(page, toView, filtered) })
        report.append(row("live: rotate a quarter page into the view", 40) { frameCanvas.drawBitmap(quarter, toView, filtered) })

        // The drop: the same pixels, once, at document resolution, into a
        // `Layer`. This one is a CPU cost in the app as well as here.
        val doc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val docCanvas = Canvas(doc)
        val inDoc = Matrix().apply { setRotate(17f, w / 2f, h / 2f) }
        report.append(row("drop: rotate a full page into the document", 10) { docCanvas.drawBitmap(page, inDoc, filtered) })
        report.append(row("drop: rotate a quarter page into the document", 20) { docCanvas.drawBitmap(quarter, inDoc, filtered) })
        // Same trap as the mask: a blit that drew nothing would be the fastest
        // row in the file.
        assertTrue(Color.alpha(doc.getPixel(w / 2, h / 2)) > 100, "the rotated blit landed")
    }

    /**
     * A path clip on a **software** canvas is antialiased, and that is why the
     * ink is confined by a mask rather than by a clip.
     *
     * The plan's reasoning in one assertion. `Layer`'s canvas is software, so
     * `clipPath` there gives a soft edge; the frame's canvas comes from
     * `CanvasFrontBufferedRenderer`, which holds a `RenderNode`, so the same
     * call there gives a hard one. Clipping both would draw a stair-stepped
     * selection edge under the pen that snapped smooth at pen-up. Nothing in
     * this repo can assert the hardware half without a device, so what is
     * pinned here is the half that can be: the soft edge exists, which is what
     * makes the two paths disagree.
     */
    @Test
    fun `a path clip on a software canvas is antialiased`() {
        val n = 200
        val diagonal = Path().apply {
            moveTo(0f, 0f)
            lineTo(n.toFloat(), 0f)
            lineTo(0f, n.toFloat())
            close()
        }
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            save()
            clipPath(diagonal)
            drawColor(Color.BLACK)
            restore()
        }
        // A band two pixels either side of the hypotenuse x + y = n, because
        // the partly covered pixels are on the boundary itself and a line one
        // pixel inside it is uniformly opaque.
        val alphas = sortedSetOf<Int>()
        for (i in 4 until n - 4) {
            for (d in -2..2) {
                val y = n - i + d
                if (y in 0 until n) alphas.add(Color.alpha(bitmap.getPixel(i, y)))
            }
        }
        assertTrue(alphas.contains(0) || alphas.contains(255), "the edge has fully in or out pixels")
        assertTrue(
            alphas.any { it in 1..254 },
            "a partly covered pixel on the clip edge, so the clip is antialiased: $alphas",
        )
    }

    // ---- plumbing -----------------------------------------------------------

    /** A closed, wobbly outline of the kind a lasso actually produces. */
    private fun lasso(seed: Int): Path {
        val path = Path()
        val n = 240
        for (i in 0 until n) {
            val a = i * 2.0 * Math.PI / n
            val r = 700.0 + 220.0 * sin(a * 5 + seed) + 90.0 * cos(a * 11 + seed)
            val x = (w / 2 + r * cos(a)).toFloat()
            val y = (h / 2 + r * 0.8 * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun row(label: String, reps: Int, block: () -> Unit): String = line(label, time(reps, block))

    private fun line(label: String, ms: String): String = label.padEnd(48) + ms + " ms\n"

    /** Three warm-up runs, then [reps] timed, reported as a mean. */
    private fun time(reps: Int, block: () -> Unit): String {
        repeat(3) { block() }
        val start = System.nanoTime()
        repeat(reps) { block() }
        return String.format("%6.2f", (System.nanoTime() - start) / 1e6 / reps)
    }
}
