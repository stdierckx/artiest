package be.thalos.artiest.ink

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import be.thalos.artiest.canvas.DabBatch
import be.thalos.artiest.engine.ink.Stroke

/**
 * Stamps dabs. The only place in the app that turns the engine's floats into
 * `Canvas` calls, and it does nothing else.
 *
 * One instance per render thread, holding one reused `Paint`. Paint is not
 * thread-safe and neither is this; `InkSurfaceView` keeps a single instance
 * that only the renderer's handler thread ever touches.
 *
 * Antialiased filled circles, which is `:spike`'s dab exactly — `Paint(
 * Paint.ANTI_ALIAS_FLAG)`, `Style.FILL` — because `:spike` is the measured A/B
 * control and a candidate that rasterizes differently is not being compared to
 * it. Hardness, opacity and the second blend mode are Phase 2, and `RoundPen`
 * carries the tripwire comment saying why none of them may ship before the
 * scratch buffer does.
 */
class DabRasterizer(
    /** The document's extent, in document pixels. See [drawWet]'s clip. */
    private val docWidthPx: Int,
    private val docHeightPx: Int,
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /**
     * Wet ink: one batch into the front-buffered layer, through [docToView].
     *
     * **The save/restore pair is load-bearing and is the single easiest thing
     * to get wrong on this path.** `CanvasFrontBufferedRenderer` invokes its
     * front-buffer callback once per queued param on **one** `RecordingCanvas`,
     * with no `save()`/`restore()` of its own between invocations. A bare
     * `canvas.concat(m)` therefore compounds: the second batch of a flush draws
     * at M squared, the third at M cubed. At identity that is invisible, which
     * is why it survives casual testing — it appears the moment anyone zooms.
     * `DabRasterizerTest` draws two batches onto one canvas and checks the
     * second landed where the first transform puts it.
     *
     * Both calls allocate nothing, so the per-event budget is unaffected.
     *
     * **The clip to the document is what keeps the two layers agreeing.** Dry
     * ink is clipped for free — the layer bitmap is exactly the document, so a
     * dab at a negative coordinate simply does not land. The front buffer is
     * the whole *view*, and the two do not coincide: fitted to this tablet's
     * landscape window, the 2160x3300 page occupies view x 560..1639 of 2200,
     * leaving a **measured 560 px margin of bare surface on each side**.
     * Without this clip a stroke started in a margin paints wet ink there and
     * then loses it at pen-up, when the layer blit replaces it — ink that
     * appears under the pen and vanishes when you lift, which reads as a
     * dropped stroke rather than as drawing off the edge of the page. Found on
     * the tablet at W8, by drawing from the margin and comparing a mid-stroke
     * screenshot against the committed one; `DabRasterizerTest` pins it.
     */
    fun drawWet(canvas: Canvas, docToView: Matrix, batch: DabBatch) {
        val n = batch.size
        if (n == 0) return
        paint.color = batch.colorArgb
        paint.isAntiAlias = batch.antiAlias
        val save = canvas.save()
        canvas.concat(docToView)
        canvas.clipRect(0f, 0f, docWidthPx.toFloat(), docHeightPx.toFloat())
        var i = 0
        while (i < n) {
            canvas.drawCircle(batch.x(i), batch.y(i), batch.radius(i), paint)
            i++
        }
        canvas.restoreToCount(save)
    }

    /**
     * Dry ink: a finished stroke into the layer bitmap.
     *
     * No matrix, and that is not an omission. The layer *is* document space —
     * one bitmap pixel is one document pixel — so a stroke whose dabs are
     * already in document coordinates goes in untransformed. The document-to-
     * view matrix belongs on the blit of the layer into the surface, which
     * happens once per frame instead of once per dab, and applying it here
     * would bake the current zoom into the pixels permanently.
     *
     * Index loop rather than a range or an iterator: this runs once per dab per
     * commit, on the render thread, inside `layerLock`.
     */
    fun drawDry(canvas: Canvas, stroke: Stroke) {
        paint.color = stroke.colorArgb
        paint.isAntiAlias = stroke.antiAlias
        val n = stroke.dabCount
        var i = 0
        while (i < n) {
            canvas.drawCircle(stroke.x(i), stroke.y(i), stroke.radius(i), paint)
            i++
        }
    }
}
