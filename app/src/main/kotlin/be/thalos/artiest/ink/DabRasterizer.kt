package be.thalos.artiest.ink

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import be.thalos.artiest.canvas.DabBatch
import be.thalos.artiest.engine.brush.MaskSpec
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
 * it. Hardness, opacity and the second blend mode are Phase 2, and `Brush`
 * carries the tripwire comment saying why none of them may ship before the
 * scratch buffer does.
 */
class DabRasterizer(
    /** The document's extent, in document pixels. See [drawWet]'s clip. */
    private val docWidthPx: Int,
    private val docHeightPx: Int,
    /** Where stamped dabs come from. Null leaves [Mode.STAMP] unavailable. */
    private val stamps: StampCache? = null,
) {

    /**
     * Which dab the two draw paths lay down.
     *
     * **Both paths stay in the build, on purpose.** W5's job is to A/B the
     * stamp against Phase 1's circle on the device, and an A/B whose control
     * has been deleted is a measurement of one thing. Phase 1's number to beat
     * or to consciously spend is p50 0.119 ms an event.
     */
    enum class Mode {
        /** `drawCircle`, which is `:spike`'s dab exactly. The control. */
        CIRCLE,

        /** An `ALPHA_8` mask blit coloured by the paint. The candidate. */
        STAMP,
    }

    /** Defaults to the control, so nothing changes until something switches it. */
    var mode: Mode = Mode.CIRCLE

    /**
     * Nib hardness for stamped dabs.
     *
     * Here rather than on `Stroke` because Phase 1's dab list is `(x, y,
     * radius)` and the eight goldens are that serialization; widening it to
     * carry a value that is fixed at 1 until W7 would move every golden file to
     * record a constant. `InkSurfaceView` pushes the brush's value in at stroke
     * start, which is the same discipline `Stroke.antiAlias` follows.
     */
    var hardness: Float = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Subpixel dab placement. Dabs are spaced an eighth of a diameter
        // apart, so snapping each to the pixel grid puts a visible ripple along
        // a slow diagonal -- the dabs land in a staircase instead of on the
        // path. Filtering costs a bilinear fetch per pixel and buys the
        // placement back.
        isFilterBitmap = true
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
            dab(canvas, batch.x(i), batch.y(i), batch.radius(i))
            i++
        }
        canvas.restoreToCount(save)
    }

    /**
     * A batch of dabs into an already-document-space canvas, at [flow].
     *
     * The scratch buffer's own canvas is document space, so no matrix and no
     * clip: the buffer is exactly the stroke's region and anything outside it
     * has nowhere to land.
     */
    fun drawInto(canvas: Canvas, batch: DabBatch, flow: Float) {
        val n = batch.size
        if (n == 0) return
        paint.color = batch.colorArgb
        paint.isAntiAlias = batch.antiAlias
        if (flow < 1f) paint.alpha = (flow.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        var i = 0
        while (i < n) {
            dab(canvas, batch.x(i), batch.y(i), batch.radius(i))
            i++
        }
    }

    /** The document-space rectangle a batch's dabs cover, rim included. */
    fun boundsOf(batch: DabBatch, out: FloatArray) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var i = 0
        while (i < batch.size) {
            val x = batch.x(i)
            val y = batch.y(i)
            val rad = batch.radius(i) + 1f
            if (x - rad < l) l = x - rad
            if (y - rad < t) t = y - rad
            if (x + rad > r) r = x + rad
            if (y + rad > b) b = y + rad
            i++
        }
        out[0] = l; out[1] = t; out[2] = r; out[3] = b
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
    fun drawDry(canvas: Canvas, stroke: Stroke, flowOverride: Float = 1f) {
        paint.color = stroke.colorArgb
        paint.isAntiAlias = stroke.antiAlias
        // Flow is per-dab paint and belongs on the dab; opacity is per-stroke
        // and belongs on the composite. Applying flow here and opacity there is
        // what makes the two independent rather than one slider spelled twice.
        if (flowOverride < 1f) {
            paint.alpha = (flowOverride.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        val n = stroke.dabCount
        var i = 0
        while (i < n) {
            dab(canvas, stroke.x(i), stroke.y(i), stroke.radius(i))
            i++
        }
    }

    /**
     * One dab, by whichever path [mode] selects.
     *
     * A zero or negative radius is skipped rather than drawn. `drawCircle`
     * treats it as nothing, but `MaskSpec` refuses a non-positive diameter --
     * correctly, since a mask with no extent is not a dab -- so without this
     * the stamp path would throw where the circle path silently did nothing.
     * Two rasterizers that disagree about degenerate input are not an A/B.
     *
     * The stamp is blitted at its natural size, not scaled to the exact
     * requested radius. That is the tolerance idea working as intended: the
     * mask is built at the bucket's diameter, within 3% of what was asked for,
     * and scaling it back would cost a matrix per dab to undo the quantisation
     * the cache exists to exploit.
     */
    private fun dab(canvas: Canvas, x: Float, y: Float, radius: Float) {
        if (!(radius > 0f)) return
        val cache = stamps
        if (mode == Mode.CIRCLE || cache == null) {
            canvas.drawCircle(x, y, radius, paint)
            return
        }
        val mask = cache.stampFor(MaskSpec(radius * 2f, hardness, 1f, 0f))
        canvas.drawBitmap(cache.bitmapOf(mask), x - mask.hotspotX, y - mask.hotspotY, paint)
    }
}
