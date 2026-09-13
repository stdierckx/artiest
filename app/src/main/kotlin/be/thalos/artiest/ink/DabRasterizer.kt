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

    /**
     * The picture this brush stamps, already resolved from its name.
     *
     * Here for the same reason [hardness] is: it is fixed for the whole stroke,
     * and `Stroke`'s dab list is a golden file. `InkSurfaceView.armRasterizer`
     * looks the name up in the `TipLibrary` once at stroke start, so nothing on
     * the render thread ever hashes a string.
     *
     * A tip forces the stamp path, because `drawCircle` cannot express a
     * picture at all — the same argument an elliptical dab makes.
     */
    var tip: be.thalos.artiest.engine.brush.Tip? = null

    /**
     * Whether a large dab is blitted at a whole pixel. See [SNAP_ABOVE_PX].
     *
     * A field only so the device can measure both, which
     * `docs/big-nib-plan.md`'s second stop condition requires: *"anything under
     * 10x means the device's blitter does not have the integer fast path this
     * rests on."* A host bench cannot answer that about a tablet, and an A/B in
     * one run on the tablet can. Nothing in the app ever sets it false.
     */
    var snapLargeDabs: Boolean = true

    /**
     * Paint every dab at the ink's full alpha, ignoring flow.
     *
     * Set while erasing. An eraser is not a brush made of white paint: it takes
     * ink out, and how much it takes out has nothing to do with how much
     * graphite the pencil in the other hand lays down. Without this the eraser
     * inherited the pencil's flow — 0.02 to 0.85 on pressure, then again
     * through a 0.90 composite and a grain mask — so a full-pressure wipe
     * removed at best two thirds of the ink and usually far less. That is the
     * "eraser is too soft" report, and it is arithmetic rather than taste.
     */
    var solid: Boolean = false

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
        baseAlpha = android.graphics.Color.alpha(batch.colorArgb)
        val save = canvas.save()
        canvas.concat(docToView)
        canvas.clipRect(0f, 0f, docWidthPx.toFloat(), docHeightPx.toFloat())
        var i = 0
        while (i < n) {
            dab(
                canvas, batch.x(i), batch.y(i), batch.radius(i),
                batch.aspect(i), batch.rotation(i), batch.flow(i),
            )
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
        baseAlpha = android.graphics.Color.alpha(batch.colorArgb)
        strokeFlow = flow
        var i = 0
        while (i < n) {
            dab(
                canvas, batch.x(i), batch.y(i), batch.radius(i),
                batch.aspect(i), batch.rotation(i), batch.flow(i),
            )
            i++
        }
        strokeFlow = 1f
    }

    /** The stroke colour's own alpha, so per-dab flow scales it rather than replacing it. */
    private var baseAlpha: Int = 255

    /** The brush's flow, for dabs that carry none of their own. */
    private var strokeFlow: Float = 1f

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
        baseAlpha = android.graphics.Color.alpha(stroke.colorArgb)
        // Flow is per-dab paint and belongs on the dab; opacity is per-stroke
        // and belongs on the composite. Applying flow here and opacity there is
        // what makes the two independent rather than one slider spelled twice.
        strokeFlow = flowOverride
        val n = stroke.dabCount
        var i = 0
        while (i < n) {
            dab(
                canvas, stroke.x(i), stroke.y(i), stroke.radius(i),
                stroke.aspect(i), stroke.rotation(i), stroke.flow(i),
            )
            i++
        }
        strokeFlow = 1f
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
    private fun dab(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        aspect: Float = 1f,
        rotation: Float = 0f,
        dabFlow: Float = 1f,
    ) {
        if (!(radius > 0f)) return
        // Per-dab flow scales the colour's own alpha rather than replacing it,
        // so a translucent ink stays translucent. Applied for every dab because
        // pressure varies within a stroke -- graphite gets darker where you
        // lean on it, and a per-stroke alpha cannot express that.
        val f = if (solid) 1f else strokeFlow * dabFlow
        paint.alpha =
            if (f >= 1f) baseAlpha else (baseAlpha * f.coerceIn(0f, 1f) + 0.5f).toInt()
        val cache = stamps
        // An elliptical dab has no circle path. drawCircle cannot express it at
        // all, so a shaped brush forces the stamp regardless of [mode] rather
        // than silently drawing round dabs and looking like a broken preset.
        // Hardness counts as shaped for exactly the reason aspect does:
        // `drawCircle` has no soft rim, so a CIRCLE-mode pencil at hardness
        // 0.72 drew a hard-edged dab and the setting did nothing visible.
        val shaped = aspect < 1f || hardness < 1f || tip != null
        if ((mode == Mode.CIRCLE && !shaped) || cache == null) {
            canvas.drawCircle(x, y, radius, paint)
            return
        }
        val mask = cache.stampFor(MaskSpec(radius * 2f, hardness, aspect, rotation, tip))
        val left = x - mask.hotspotX
        val top = y - mask.hotspotY
        if (snapLargeDabs && snappable(mask.width)) {
            canvas.drawBitmap(
                cache.bitmapOf(mask), Math.round(left).toFloat(), Math.round(top).toFloat(), paint,
            )
            return
        }
        canvas.drawBitmap(cache.bitmapOf(mask), left, top, paint)
    }

    /**
     * Whether a dab of this mask width may be blitted at a whole pixel.
     *
     * **Two conditions, each measured, and both must hold** — see
     * [SNAP_ABOVE_PX] for the size and [SNAP_SOFT_PX] for the rim.
     *
     * A [tip] is excluded outright, because [hardness] does not describe its
     * edge — `MaskGenerator` ignores hardness over a picture — so the rim
     * arithmetic here would be reading a field that means nothing about it. No
     * shipped tipped brush is near the size where this would pay, and a rule
     * that guesses about a picture's edge is worse than one that declines to.
     */
    private fun snappable(maskWidth: Int): Boolean {
        if (tip != null) return false
        if (maskWidth < SNAP_ABOVE_PX) return false
        return maskWidth * (1f - hardness) * 0.5f >= SNAP_SOFT_PX
    }

    companion object {

        /**
         * Above this mask width, a dab is *eligible* for a whole-pixel blit.
         *
         * ## Why the whole-pixel blit exists
         *
         * Skia has a fast path for a bitmap blit that is a pure integer
         * translate at 1:1, and it takes it whether or not [isFilterBitmap] is
         * set — the flag stops mattering when there is nothing to interpolate.
         * A fractional offset leaves that path and every destination pixel
         * becomes a bilinear fetch. Measured in `BigNibBench`, on one 600 px
         * soft dab:
         *
         * | | ms per dab |
         * |---|---|
         * | filtered, fractional offset | 3.887 |
         * | point-sampled, fractional offset | 0.797 |
         * | **whole-pixel offset** | **0.164** |
         *
         * Twenty-four times, for a rounding. On the DTH-A116 the same brush's
         * wet pass measured 22.2 ms a batch against an 11.1 ms frame with 18
         * batch-ring spills — which is what "the airbrush takes a second to
         * appear" is, seen from the render thread — and a device A/B through
         * the commit path measured the rounding worth **7.9x** there.
         *
         * ## Why there is a size gate at all
         *
         * [isFilterBitmap] is set for a stated and correct reason: dabs are
         * spaced a fraction of a diameter apart, so snapping each to the pixel
         * grid puts a visible ripple along a slow diagonal. At an eighth of a
         * diameter a 10 px nib lays dabs 1.25 px apart, and rounding two
         * neighbours to the same pixel is a *bead*, not a ripple — a different
         * defect from the rim one below, and one nothing about softness fixes.
         * 64 px keeps the dab spacing at 8 px and the question academic.
         *
         * It is a **mask width** rather than the asked-for diameter because the
         * mask is what is blitted: `MaskTolerance` quantises size, and the
         * bitmap's own extent is the thing whose offset is being rounded.
         */
        const val SNAP_ABOVE_PX: Int = 64

        /**
         * How many pixels the rim must fall off over before half a pixel of
         * placement error is lost in it.
         *
         * **This is the condition that actually decides it, and the
         * measurement is one-sided enough to be worth writing out.**
         * `DabFootprintTest` draws a slow shallow diagonal — the case where
         * consecutive dabs round in different directions — and counts the rim
         * pixels that move by more than an eighth of the channel, per 1000
         * document pixels of stroke:
         *
         * | diameter | hardness 1.0 | hardness 0.4 |
         * |---|---|---|
         * | 64 | 1367 | **0** |
         * | 128 | 1459 | **0** |
         * | 300 | 1570 | **0** |
         * | 600 | 147 | **0** |
         *
         * A hard rim ripples at every size; a soft rim never does. Size alone
         * was the rule `docs/big-nib-plan.md` expected to find and it is the
         * wrong one — the plan's third stop condition allowed for exactly this
         * and named the answer: *ships gated on hardness, soft rims only, which
         * is the whole of the reported problem*.
         *
         * Two pixels because that is where a half-pixel shift stops being most
         * of the gradient. The airbrush this work began with has a rim 180 px
         * wide; the pencil's is 0.75 and the chalk's is 0.
         */
        const val SNAP_SOFT_PX: Float = 2f
    }
}
