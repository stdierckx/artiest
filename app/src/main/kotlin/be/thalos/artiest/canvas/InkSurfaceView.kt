package be.thalos.artiest.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.os.Debug
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import be.thalos.artiest.doc.ColourProbe
import be.thalos.artiest.doc.CommitQueue
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.FloatOp
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.doc.LayerStack
import be.thalos.artiest.doc.PendingStroke
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp
import be.thalos.artiest.doc.StackCompositor
import be.thalos.artiest.doc.EraseMode
import be.thalos.artiest.doc.SheetRebuilder
import be.thalos.artiest.doc.StrokeEraser
import be.thalos.artiest.doc.StrokeMove
import be.thalos.artiest.doc.StrokeRestyle
import be.thalos.artiest.doc.StrokeOp
import be.thalos.artiest.doc.VectorSheet
import be.thalos.artiest.doc.VectorStep
import be.thalos.artiest.engine.ink.DabEmitter
import be.thalos.artiest.engine.ink.PredictedTail
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.adoptBrush
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.MutableBounds
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.SampleLog
import be.thalos.artiest.engine.guide.Snap
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeRecord
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PredictionGate
import be.thalos.artiest.engine.input.RejectionCounters
import be.thalos.artiest.engine.input.Stabilizer
import be.thalos.artiest.engine.input.TwoFingerTap
import be.thalos.artiest.engine.xform.CanvasTransform
import be.thalos.artiest.ink.DabRasterizer
import be.thalos.artiest.ink.GrainTexture
import be.thalos.artiest.ink.ScratchLayer
import be.thalos.artiest.ink.StampCache
import be.thalos.artiest.input.InkInputSink
import be.thalos.artiest.input.InputRouter
import be.thalos.artiest.input.eventAgeNanos
import be.thalos.artiest.input.Predictor
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The canvas: a bare `SurfaceView` driving a `CanvasFrontBufferedRenderer`.
 *
 * Two layers, and the split is the whole design. Wet ink — the stroke under the
 * pen — goes straight into the front-buffered layer, once per input event,
 * synchronously from `onTouchEvent`. Dry ink lives in the document's layer
 * bitmap and is blitted into the multi-buffered layer whenever the scene
 * changes. A stroke crosses from one to the other exactly once, at pen-up.
 *
 * **There is no `setBackgroundColor` here, and adding one is not a style
 * change.** A `SurfaceView` shows its surface through a transparent hole
 * punched in the view hierarchy by `clearSurfaceViewPort()`, and `super.draw()`
 * paints the view's own background *over* that hole. An opaque background hides
 * the surface completely — ink, everything — while the renderer keeps
 * submitting perfectly healthy frames and logcat says nothing. That cost a full
 * session of blank-canvas debugging once already, in `:spike` at `b02a531`, and
 * this comment is its tombstone. Paper white comes from
 * [onDrawMultiBufferedLayer]'s `drawColor`.
 *
 * **Renders are not coalesced onto vsync, deliberately.** Every wet batch is
 * submitted the moment the event arrives. W1 A/B'd this by eye against two arms
 * that waited for the next vsync — a dedicated `SurfaceControl` with its own
 * render thread, and a plain `View` — and both read as smoother *and further
 * behind*. Coalescing here looks like an obvious efficiency win, W2 shows
 * throughput was never the constraint, and taking it would trade away the only
 * thing this path was chosen for.
 *
 * Threading, stated once:
 *
 * - `onTouchEvent`, the [InkSurface] methods and the [InputRouter] are UI
 *   thread.
 * - Both renderer callbacks and everything they touch — the layer bitmap, the
 *   [DabRasterizer], [dryMatrix] — are the library's handler thread.
 * - The two directions are joined by exactly three things: the volatile
 *   [frozenDocToView] and [transform] fields, the [pendingStroke]
 *   `AtomicReference`, and [DabBatchPool]'s sequence counters. No lock is taken
 *   on the input path; one there would put the render thread's scheduling delay
 *   into the pen's latency.
 */
class InkSurfaceView(
    context: Context,
    /**
     * The pixels. Owned by the caller and deliberately **not** allocated here:
     * document size is independent of surface size, the document outlives the
     * view, and a rotation must rebuild the view against the same bitmap rather
     * than reallocate it. See `Document.layer`.
     */
    val document: Document,
) : SurfaceView(context), InkSurface {

    /**
     * The brush the stroke in flight is drawn with. **Render thread only.**
     *
     * A `val` holding a mutable `Brush` rather than a reassignable field:
     * `StrokeBuilder` captures the instance at construction, so replacing it
     * here would leave the builder drawing with the old one and nothing would
     * fail. `Brush`'s own settings are `var`, so the sliders move those.
     *
     * **This is no longer the brush the toolbar configures.** It used to be
     * both, and it stopped being able to be when the eraser got a brush of its
     * own: something has to hold the pencil's numbers while the rubber is on
     * the glass. The toolbar writes [ink] and [rubber]; this one is chosen from
     * those at pen-down by [applyEraseFor] and is a scratch the render thread
     * owns. Nothing outside the render thread may write it.
     */
    val pen: Brush = Brush()

    /**
     * The brush the toolbar has in hand for inking. Written from the UI thread.
     *
     * Read once per stroke, at pen-down, and copied into [pen]. That copy is
     * also the whole of the thread safety: a slider dragged mid-stroke cannot
     * change the stroke being drawn, which is the same guarantee `frozen` gives
     * the transform and for the same reason.
     */
    val ink: Brush = Brush()

    /**
     * The **barrel button's** brush, or null to rub out with [ink]'s own shape.
     *
     * Narrower than it was, and the narrowing is the whole of Us2. This used to
     * be "the eraser's brush", because the eraser was a mode that any brush
     * could be put into. The eraser is two brushes now — `BrushPreset`'s
     * `HARD_ERASER` and `SOFT_ERASER` — and they arrive in [ink] like any
     * other, so the only question left for this field is the one the toolbar
     * cannot answer: what happens when the pen is turned over mid-stroke.
     *
     * Null is the default and the good one. Rubbing out with the tool in your
     * hand means the pencil rubs out with the pencil's tilt and the marker with
     * the marker's wedge, at the width `Brush.eraseSizeMax` gives it. Naming a
     * brush here — the shelf's *Use as eraser* — swaps that for a rubber of
     * your choosing without changing what you draw with.
     *
     * It is not consulted at all while [ink] itself erases. Turning an eraser
     * over is not a gesture with a meaning.
     */
    var rubber: Brush? = null

    /** Ink colour. W15 gives it a swatch; until then it is black. */
    var inkColorArgb: Int = Color.BLACK

    /**
     * Speculative ink, **off by default**.
     *
     * The default is a shipping position rather than a placeholder: W1 judged
     * prediction by eye and it lost, and the cost of being wrong here is
     * asymmetric because front-buffer ink cannot be taken back. See
     * [Predictor]'s header, and W16 for the re-judgement.
     *
     * When false nothing on the input path touches the predictor — not the
     * record, not the gate, not the fork — so W9's measurements keep meaning
     * what they said.
     */
    var predictionEnabled: Boolean = false

    /** Built at attach; null while detached. Diagnostics read [Predictor]. */
    var predictor: Predictor? = null
        private set

    /**
     * The live document-to-view mapping, for the **dry** layer.
     *
     * A `CanvasTransform` and not a `Matrix`, which is the opposite choice from
     * [frozenDocToView] one field below, and the asymmetry is on purpose. This
     * one changes at gesture rate — up to once per frame in W12 — and is
     * immutable four floats, so publishing it volatile costs nothing and the
     * render thread rebuilds its own `Matrix` from it, allocating nothing per
     * frame. The frozen one changes once per pen-down, where an allocation is
     * free and a fresh `Matrix` nobody else holds is the safest thing to hand
     * over.
     */
    @Volatile
    var transform: CanvasTransform = CanvasTransform.IDENTITY
        private set

    /**
     * The transform a request arrived with while a stroke was open, applied at
     * pen-up. Null when there is nothing waiting.
     */
    private var pendingTransform: CanvasTransform? = null

    /**
     * True while the pen is down. The transform is frozen for exactly this
     * long — see [requestTransform].
     */
    private var strokeOpen: Boolean = false

    /** Transform requests held because a stroke was open. See [requestTransform]. */
    var transformDeferrals: Long = 0L
        private set

    /**
     * Ask for a new document-to-view transform. Returns false if it was
     * deferred.
     *
     * **The transform is frozen for the lifetime of a wet stroke, and this is
     * where that rule is enforced rather than assumed.**
     *
     * The reason is more specific than the plan's wording, and worth stating
     * exactly. A transform change does *not* corrupt the stroke itself: the
     * render matrix is snapshotted at pen-down and so is the mapping incoming
     * samples go through, so every dab of a stroke lands consistently whatever
     * happens to this field. What breaks is the relationship between the two
     * layers. The front buffer is drawing wet ink through the frozen matrix
     * while the multi-buffered layer blits the committed ink through this one,
     * and `CanvasFrontBufferedRenderer` never re-records pixels it has already
     * drawn — there is no invalidate, no partial clear, and
     * `SurfaceControlCompat.Transaction` has no `setMatrix` to rescue it at the
     * compositor. So the drawing and the stroke being drawn on it would be at
     * two different scales and positions at once, until pen-up put them back
     * together.
     *
     * `StrokeExclusivity` already keeps a gesture from starting mid-stroke, so
     * the gesture path cannot reach here while the pen is down. The path that
     * can is `surfaceChanged` **without** a destroy — a window resize with the
     * pen still on the glass. Rotation is not that path on this device: it
     * destroys the surface first, and `surfaceDestroyed` abandons the stroke,
     * which was measured rather than assumed. Refusing outright would leave the
     * canvas at a fit that no longer matches the surface, so the request is
     * held and applied at pen-up, which is the first moment it is safe.
     * [transformDeferrals] counts how often that actually happens, because a
     * branch nobody can make fire is a branch nobody knows the state of.
     */
    fun requestTransform(t: CanvasTransform): Boolean {
        if (strokeOpen) {
            pendingTransform = t
            transformDeferrals++
            return false
        }
        setTransform(t)
        return true
    }

    /**
     * The one place [transform] is written, and therefore the one place the
     * chrome drawn over the canvas can be told that it moved.
     *
     * **This was missing and it shipped.** [onTransformChanged] existed, the
     * overlays were wired to it, and nothing ever called it: the guides and the
     * marching ants followed a *button* zoom, because pressing a button
     * recomposes anyway, and stayed nailed to the glass through a pinch, which
     * touches no Compose state at all. The page moved under them. That is the
     * whole of the "the perspective grid stays the same, the page zooms"
     * report, and no amount of correct geometry in `Guideline.outline` could
     * have fixed it — the outline was right and nobody was asking for it again.
     *
     * Guarded on inequality because the gesture path calls this once per frame
     * for the whole of a pinch and a solver that answers with the same
     * transform twice should not cost a redraw of the overlay.
     */
    private fun setTransform(t: CanvasTransform) {
        if (transform == t) return
        transform = t
        // UI thread, every caller: the gesture path, the toolbar buttons and
        // pen-up are all on it. That matters because this ends in a Compose
        // state write.
        onTransformChanged?.invoke()
    }

    /**
     * Set a transform and repaint, for callers that are not driving their own
     * frame loop.
     *
     * [requestTransform] deliberately does not redraw: the gesture path calls
     * it up to once per frame and schedules its own render on the
     * Choreographer, so a redraw inside it would be a second render per frame.
     * Every *other* caller — a toolbar button, a menu item, a keyboard
     * shortcut — has no frame loop of its own, and a transform with no render
     * behind it is a control that does nothing at all until something else
     * happens to repaint. That was shipped and then found by hand: the zoom
     * buttons moved `transform` and left the pixels alone.
     *
     * Returns false if the transform was deferred because a stroke is open, in
     * which case there is nothing to draw yet and the deferred request will be
     * applied at pen-up.
     */
    fun applyTransform(t: CanvasTransform): Boolean {
        if (!requestTransform(t)) return false
        redrawDry()
        return true
    }

    /**
     * Fit the document to the surface, discarding any pan and zoom.
     *
     * The recovery path, and the reason it exists is that a canvas can be
     * gestured somewhere unrecoverable — rotated 40 degrees and zoomed to 8x
     * with the page off screen is four seconds of fumbling away. It also turns
     * [fitOnResize] back on, so the canvas resumes following the window until
     * the next gesture.
     */
    fun fitToView(): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false
        fitOnResize = true
        return applyTransform(
            CanvasTransform.fitTo(w, h, document.widthPx, document.heightPx),
        )
    }

    /**
     * Whether a surface resize refits the document to the new size.
     *
     * True until the user has moved the canvas themselves, which is W12's to
     * decide — a rotation must not throw away a pan and zoom someone set up on
     * purpose, and until there is a gesture there is nothing to throw away.
     *
     * It is not cosmetic while it is on. The fit is taken from the **surface**
     * size in `surfaceChanged`, not from `displayMetrics`: those differ by the
     * system bars, and on this tablet they differ by the whole aspect ratio
     * after a rotation. Fitted once at construction and never again, a rotation
     * leaves a 2160x3300 document drawn at the landscape scale in the corner of
     * a portrait window — measured, W8, and it does not blank or crash, it just
     * looks wrong.
     */
    var fitOnResize: Boolean = true

    /** Diagnostics for W9's allocation trace. See [DabBatchPool]. */
    val batches = DabBatchPool()

    /**
     * W9's measurements: per-event cost and per-stroke allocation.
     *
     * Always on rather than behind a build flag. It costs two `nanoTime` calls
     * per event and four heap reads per stroke, which is well inside the noise
     * of the thing being measured, and a diagnostic that has to be switched on
     * is one nobody has running when the interesting stroke happens.
     */
    val stats = InputStats()

    /**
     * Samples and dabs in the stroke that just finished.
     *
     * Plain fields, read by the debug readout on a poll. The ratio is the one
     * number that says whether the pipeline is being fed properly: a stroke
     * whose dab count is far below its sample count is being resampled to a
     * spacing that is too wide, and one far above it means the pen barely
     * moved. It also makes the difference between "the injected event stream
     * is coarse" and "the fit is dropping ink" a measurement instead of a
     * guess, which is how the doc-space bug above was distinguished from the
     * Catmull-Rom fit's start-up cost.
     */
    var lastStrokeSamples: Int = 0
        private set

    /** See [lastStrokeSamples]. */
    var lastStrokeDabs: Int = 0
        private set

    /**
     * The largest tilt the digitizer reported during the last stroke, in
     * degrees, and the dab widths that came out of it.
     *
     * On the HUD because "tilt does not widen the stroke" has three different
     * causes that look identical on the glass: the hardware not reporting tilt,
     * the app not forwarding it, and the brush not listening. One line
     * separates all three — a tilt of 0 is the first two, a tilt of 50 with a
     * flat width range is the third.
     */
    var lastStrokeTiltDeg: Float = 0f
        private set

    /** See [lastStrokeTiltDeg]. Dab diameters, document pixels. */
    var lastStrokeWidthMin: Float = 0f
        private set

    /** See [lastStrokeTiltDeg]. */
    var lastStrokeWidthMax: Float = 0f
        private set

    /**
     * The lightest and heaviest pressure the digitizer reported during the last
     * stroke.
     *
     * The pencil's whole response is a curve over this number, and until it was
     * on the HUD nobody knew what range a hand actually produces on this pen —
     * "a very light press" was being tuned for as 0.1 on the strength of an
     * assumption. One stroke drawn as lightly as the pen will register answers
     * it.
     */
    var lastStrokePressureMin: Float = 0f
        private set

    /** See [lastStrokePressureMin]. */
    var lastStrokePressureMax: Float = 0f
        private set

    /**
     * The stroke's transform, frozen at pen-down.
     *
     * Written by the UI thread in [beginStroke] and read by the render thread;
     * the volatile write is what publishes the `Matrix`'s native state, which
     * final-field semantics would not, because a `Matrix`'s contents are
     * written after its constructor returns.
     *
     * **Not cleared at pen-up**, and that is deliberate rather than an
     * oversight. Front-buffer renders are dispatched asynchronously, so a batch
     * submitted just before the commit can reach the callback after it; a
     * cleared field would make that batch draw nothing, which is a gap at the
     * tip of the stroke for the one frame before the commit lands. Nothing
     * renders to the front buffer between strokes, so leaving the last one in
     * place costs nothing. Null only before the first stroke, and after
     * [release].
     */
    @Volatile
    private var frozenDocToView: Matrix? = null

    /**
     * Applies the document's queued commits. Render thread only.
     *
     * The only place in the app that writes the layer's pixels, which is the
     * threading contract stated in one line. `Layer.write` refuses the main
     * thread, so the contract is checked rather than trusted.
     */
    /**
     * One block of work for the render thread, run before the next dry frame.
     *
     * **Instrumentation, and the only thing that may use it.** Everything the
     * app does on the render thread goes through `CommitQueue`, whose ordering
     * argument has been made four times; a measurement is not an edit and has
     * no place in that queue. It is a field rather than a queue because there
     * is at most one in flight and the next one cannot start until the readout
     * of the last has been read by a person.
     */
    @Volatile
    private var renderThreadTask: (() -> Unit)? = null

    /**
     * Ik0: what it costs to rebuild a sheet from the strokes that made it.
     *
     * `docs/inker-plan.md`'s whole premise is that a vector sheet is a raster
     * sheet that kept its input, and its first stop condition is a number this
     * produces: *"if re-rendering 300 pencil strokes costs more than a second
     * on the tablet, then every edit is a visible hitch and the honest fallback
     * is vector sheets for opaque nibs only."*
     *
     * Two things make the number trustworthy rather than merely encouraging.
     * It goes through [stampStroke], which is the path a real commit takes, so
     * a translucent nib pays for the scratch buffer and the composite exactly
     * as it does when the pen is down. And it renders into a sheet of its own,
     * so measuring the rebuild does not paint over the drawing — a stress that
     * costs the user their page is a stress nobody runs twice.
     *
     * Two passes per count. The first pays for every dab mask the scene's sizes
     * need and the second does not, and both are real: a cold sheet is what an
     * app resume rebuilds, and a warm one is what an edit or an undo rebuilds.
     */
    fun measureRerender(
        counts: IntArray,
        nibs: List<Pair<String, Brush>>,
        onDone: (String) -> Unit,
    ) {
        if (renderThreadTask != null || !surfaceAlive) return
        renderThreadTask = {
            val report = runCatching { rerenderReport(counts, nibs) }
                .getOrElse { "re-render bench failed: $it" }
            // Also to the log, because the number's destination is a table in
            // `docs/inker-plan.md` and reading it off a tablet screen into a
            // document is how a digit gets transposed.
            android.util.Log.i("artiest.ik0", report)
            post { onDone(report) }
        }
        redrawDry()
    }

    /**
     * The report, one block per nib.
     *
     * **The bench chooses its own brushes and puts the old one back**, rather
     * than measuring whatever is in the hand. The first run of this did the
     * latter and produced a table that was quietly the wrong nib — the debug
     * row's "Pencil ON" and "Stamp" toggles are two more things that have to be
     * in the right state, and a measurement whose meaning depends on which
     * buttons were pressed before it is a measurement nobody can repeat. The
     * plan asks for the pencil and the ink pen by name; this asks for them by
     * name.
     *
     * The pen is restored from its own encoded text, which is how `BrushStore`
     * restores it across a restart — the same round trip, so the same fidelity.
     */
    private fun rerenderReport(counts: IntArray, nibs: List<Pair<String, Brush>>): String {
        val w = document.widthPx
        val h = document.heightPx
        val sheet = Layer(w, h)
        val held = BrushCodec.encode(pen)
        val out = StringBuilder()
        var blockStart = 0
        try {
            for ((label, brush) in nibs) {
                adoptBrush(brush, pen)
                armRasterizer()
                out.append(label).append("  ")
                    .append(if (indirectNeeded()) "indirect" else "direct")
                    // The debug row can put the rasterizer in stamp mode, and
                    // that is a 3x difference on an unshaped nib — so the table
                    // says which it was rather than leaving the reader to
                    // remember which buttons were pressed.
                    .append(' ').append(rasterizer.mode.name.lowercase())
                    .append(if (rasterizer.tip != null) " tip" else "")
                    .append("  size ").append(pen.sizeMin.toInt()).append("..")
                    .append(pen.sizeMax.toInt())
                    .append("  op ").append(fmt2(pen.opacity))
                    .append("  flow ").append(fmt2(pen.flow))
                    .append("  hard ").append(fmt2(pen.hardness))
                    .append(if (pen.grain.isActive) "  grain" else "")
                    .append('\n')
                out.append(
                    "strokes samples    dabs  cold ms  warm ms ms/strk   KiB  " +
                        "hit  patch ms  nosnap ms\n",
                )
                val builder = StrokeBuilder(pen)
                var drift = -1L
                for (n in counts) {
                    val scene = VectorStress.scene(n, w, h)
                    val (raw, _) = VectorStress.bytesOf(scene)
                    sheet.blank()
                    val cold = renderScene(scene, builder, sheet)
                    sheet.blank()
                    val warm = renderScene(scene, builder, sheet)
                    val patch = renderScene(touching(scene, builder, w, h), builder, sheet)
                    // The same pass with the whole-pixel rule turned off. On
                    // the host a 600 px dab is 23.8x dearer that way; whether
                    // the tablet's blitter has the same integer fast path is
                    // the question `docs/big-nib-plan.md` stops on, and it can
                    // only be answered here.
                    rasterizer.snapLargeDabs = false
                    sheet.blank()
                    val loose = renderScene(scene, builder, sheet)
                    rasterizer.snapLargeDabs = true
                    if (drift < 0) drift = driftOf(scene, builder, sheet, w, h)
                    out.append(
                        "%7d %7d %7d %8.1f %8.1f %7.2f %5d %4d %9.1f %10.1f\n".format(
                            n, VectorStress.samplesOf(scene), cold.second,
                            cold.first, warm.first, warm.first / n,
                            raw / 1024, patchCount, patch.first, loose.first,
                        ),
                    )
                }
                out.append("redraw drift ").append(drift).append(" px in a million\n\n")
                // Logged per nib rather than only at the end. The first run of
                // this took a quarter of an hour and printed nothing until it
                // was done, so there was no way to tell a slow pencil from a
                // wedged render thread — which is the one question you have
                // while waiting.
                android.util.Log.i("artiest.ik0", out.substring(blockStart))
                blockStart = out.length
            }
        } finally {
            sheet.close()
            BrushCodec.decode(held)?.let { adoptBrush(it, pen) }
            armRasterizer()
        }
        return out.toString()
    }

    /**
     * The strokes of [scene] whose ink meets a [PATCH_DOC] by [PATCH_DOC] square at the
     * middle of the page.
     *
     * **This is the number the plan's first stop condition should have asked
     * for.** A full-sheet rebuild is what an app resume or a zoom settle costs,
     * and it happens once; what happens two hundred times an hour is an *edit*
     * — rub out one line, move three, recolour one — and an edit only has to
     * redraw the strokes that overlap the rectangle it dirtied. Ik4 calls that
     * a damage rectangle. If the full rebuild is unaffordable and the patch is
     * not, then damage rectangles stop being an optimisation and become a
     * requirement, which is a different conclusion from "vector sheets are for
     * opaque nibs only".
     *
     * Half a page across is deliberately generous: a stroke's own bounds are
     * usually far smaller, and being generous here fails towards the honest
     * answer rather than the flattering one.
     */
    private fun touching(
        scene: List<VectorStress.Record>,
        builder: StrokeBuilder,
        w: Int,
        h: Int,
    ): List<VectorStress.Record> {
        val left = (w - PATCH_DOC) * 0.5f
        val top = (h - PATCH_DOC) * 0.5f
        val right = left + PATCH_DOC
        val bottom = top + PATCH_DOC
        val out = ArrayList<VectorStress.Record>()
        for ((i, record) in scene.withIndex()) {
            // Built rather than estimated, because the question is whether the
            // stroke's *ink* meets the patch and only the dab loop knows how
            // wide the ink is. The building is setup and is not timed. The seed
            // is the one `renderScene` will use, so the bounds measured here is
            // the bounds of the stroke that will actually be drawn.
            val bounds = build(record, builder, IK0_SEED_BASE + i).bounds
            if (bounds.right >= left && bounds.left <= right &&
                bounds.bottom >= top && bounds.top <= bottom
            ) {
                out.add(record)
            }
        }
        patchCount = out.size
        return out
    }

    private var patchCount: Int = 0

    /** Wall milliseconds, and the dabs laid, for one pass over [scene]. */
    private fun renderScene(
        scene: List<VectorStress.Record>,
        builder: StrokeBuilder,
        into: Layer,
    ): Pair<Double, Int> {
        var dabs = 0
        val t0 = System.nanoTime()
        for ((i, record) in scene.withIndex()) {
            // The seed is the stroke's index, which is what makes drawing the
            // scene twice the same drawing twice. Before Ik2 the builder took
            // its seed from a counter, and `driftOf` measured the pencil at
            // 105 052 pixels per million because of it.
            val stroke = build(record, builder, IK0_SEED_BASE + i)
            dabs += stroke.dabCount
            stampStroke(stroke, into)
        }
        return (System.nanoTime() - t0) / 1e6 to dabs
    }

    /**
     * How many pixels in a million come out different when the same records are
     * drawn twice.
     *
     * **Ik2's case, measured before Ik2 is written.** `docs/inker-plan.md` says
     * determinism is the load-bearing property and that today's brush does not
     * have it: `StrokeBuilder.begin` derives its random state from a counter,
     * so a re-render gets a different roll. The plan asserts it; this counts it.
     *
     * Zero for a nib with no scatter and no size jitter — most of them — and a
     * real number for the pencil, whose `scatter` is 1.5 document pixels driven
     * by pressure. A drawing that changes every time it is rebuilt is the thing
     * that makes undo, move and zoom all quietly untrustworthy, and a nib at
     * zero here is one that would survive a vector sheet today.
     */
    private fun driftOf(
        scene: List<VectorStress.Record>,
        builder: StrokeBuilder,
        sheet: Layer,
        w: Int,
        h: Int,
    ): Long {
        val mirror = Layer(w, h)
        try {
            sheet.blank()
            renderScene(scene, builder, sheet)
            renderScene(scene, builder, mirror)
            val a = IntArray(w)
            val b = IntArray(w)
            var differ = 0L
            var total = 0L
            sheet.read { first ->
                mirror.read { second ->
                    // Row by row rather than the whole page at once: two
                    // 7.1-megapixel int arrays is 57 MiB, and this runs on a
                    // device whose memory budget is the subject of three other
                    // notes in this file.
                    for (y in 0 until h) {
                        first.getPixels(a, 0, w, 0, y, w, 1)
                        second.getPixels(b, 0, w, 0, y, w, 1)
                        for (x in 0 until w) {
                            total++
                            if (a[x] != b[x]) differ++
                        }
                    }
                }
            }
            return if (total == 0L) 0L else differ * 1_000_000L / total
        } finally {
            mirror.close()
        }
    }

    /** One record through the real builder. The heart of what Ik0 prices. */
    private fun build(record: VectorStress.Record, builder: StrokeBuilder, seed: Int): Stroke {
        builder.begin(inkColorArgb, seed)
        for (i in 0 until record.count) {
            val nanos = downNanos + (record.timeMillis(i) * 1_000_000f).toLong()
            builder.addTilt(record.tilt(i), record.orientation(i), nanos)
            builder.add(record.x(i), record.y(i), record.pressure(i), nanos)
        }
        return builder.end()
    }

    /** An arbitrary but fixed pen-down instant; only the deltas are read. */
    private val downNanos: Long = 1_000_000_000L

    private fun fmt2(v: Float): String = "%.2f".format(v)

    /**
     * Append [record] to the active sheet's stroke list, if that sheet keeps
     * one. **Render thread**, from inside the commit.
     *
     * After the pixels and not before, and the order is not cosmetic: if
     * stamping throws — an out-of-memory opening the scratch buffer is the real
     * case — the record must not be left describing ink that is not on the
     * page. A sheet whose strokes say more than its pixels do is a sheet that
     * repaints itself into something the user did not draw the next time
     * anything touches it.
     *
     * The clip is resolved here rather than on the UI thread because
     * `Selection` is the render thread's, like the stack. A stroke drawn into a
     * selection is clipped pixels, and a record that forgot that would
     * re-render outside the stencil the first time it was touched.
     *
     * A record whose stroke laid no dabs is dropped. `Document.commitStroke`
     * already refuses those, so this is the second line of the same defence:
     * an empty record would be a stroke in the list that the rebuild draws
     * nothing for, which is harmless until Ik7 lets somebody select it.
     */
    private fun keepRecord(record: PendingStroke?, stroke: Stroke): StrokeRecord? {
        if (record == null || stroke.dabCount == 0) return null
        val sheet = document.layers.active.vector ?: return null
        return sheet.append(record, document.selection.snapshot.path)
    }

    /**
     * Put [stroke] into [into], by whichever path the brush in the hand needs.
     *
     * **The one place a stroke becomes pixels**, and that is the point of it
     * being a method rather than the body of [commitSink]'s `onStroke`.
     * `docs/inker-plan.md`'s third stop condition is *"if re-rendering cannot
     * go through `ScratchLayer` and `DabRasterizer` as the commit path does —
     * if it needs its own loop — stop"*, because Phase 3 bought one compositor
     * deliberately and two that drift is the defect a user finds months later
     * in a file they have already sent somewhere. A re-render calls this.
     *
     * [into] is a parameter for the same reason: Ik0 measures the rebuild
     * against an offscreen sheet of the same size, so that measuring it does
     * not paint over the drawing. Every caller that is not a measurement passes
     * `document.layer`.
     *
     * Does not snapshot for undo and does not touch thumbnails. Both belong to
     * the *event* — a stroke was drawn, a sheet changed — and neither belongs
     * to the act of rasterising, which is why they stayed with the caller.
     */
    private fun stampStroke(stroke: Stroke, into: Layer, confine: Confinement? = null) {
        if (confine != null) {
            stampConfined(stroke, into, confine)
            return
        }
        if (!indirectNeeded()) {
            armRasterizer()
            into.write { rasterizer.drawDry(it, stroke) }
            return
        }
        val stencil = document.selection.maskBitmap()
        scratch.begin(stroke.bounds)
        val sc = scratch.canvasInDocSpace()
        if (sc == null) {
            // The buffer could not be opened. Falling back to the direct
            // path draws a beaded stroke, which is wrong but visible;
            // dropping the stroke silently is wrong and invisible.
            //
            // The stencil still has to hold, and here a clip is the only
            // tool left. `Layer`'s canvas is a software one, where Skia
            // antialiases a path clip, so this costs nothing visible
            // against the masked path -- which is exactly why it is safe
            // here and wrong on the frame's `RenderNode` canvas.
            armRasterizer()
            into.write {
                val save = it.save()
                document.selection.clipInto(it)
                rasterizer.drawDry(it, stroke)
                it.restoreToCount(save)
            }
            return
        }
        armRasterizer()
        // No `flowOverride`. Flow lives on the dab now -- `StrokeBuilder`
        // writes `flowOption.valueFor(context)` into every one -- and
        // passing the brush's flow here as well multiplied it in a second
        // time. That was correct when a dab had no flow of its own and was
        // never revisited when it got one, so the pencil painted at flow
        // squared and the darkest press it could reach was 0.72 of what
        // the preset asked for.
        rasterizer.drawDry(sc, stroke)
        if (stencil != null) scratch.maskBy(stencil)
        into.write {
            scratch.compositeInto(
                it, compositeAlpha(), compositeGrain(), pen.erase, pen.burnish,
            )
        }
    }

    /**
     * Where a re-rendered stroke is allowed to land.
     *
     * Two things at once, because a rebuild needs both and neither is the live
     * selection. [damage] is the rectangle being repainted: a stroke that
     * overlaps it usually pokes outside it too, and ink outside the rectangle
     * would land on top of pixels nobody cleared — a second coat, which on a
     * translucent nib is darker and on an opaque one is invisible until the
     * edges disagree. [clip] is the selection that was live when the stroke was
     * *drawn*, out of the sheet's clip table, which is the whole reason that
     * table exists.
     */
    private class Confinement(val damage: Rect, val clip: Path?)

    /**
     * [stampStroke] for a rebuild: the same two paths, confined by a canvas
     * clip instead of by the live selection.
     *
     * **A clip and not a mask bitmap**, unlike the commit path, and the
     * existing code already says why it is safe: `Layer`'s canvas is a software
     * one, where Skia antialiases a path clip, *"which is exactly why it is
     * safe here and wrong on the frame's `RenderNode` canvas"*. The commit path
     * masks instead because it also has to confine the **wet** pass, which
     * draws on a hardware canvas; a rebuild has no wet pass.
     *
     * It is also what makes the damage rectangle affordable. Masking would mean
     * an `ALPHA_8` page per clip-table entry; clipping a rectangle is free.
     */
    private fun stampConfined(stroke: Stroke, into: Layer, confine: Confinement) {
        armRasterizer()
        if (!indirectNeededFor(pen, clipped = false)) {
            into.write {
                val save = it.save()
                it.clipRect(confine.damage)
                confine.clip?.let { path -> it.clipPath(path) }
                rasterizer.drawDry(it, stroke)
                it.restoreToCount(save)
            }
            return
        }
        scratch.begin(stroke.bounds)
        val sc = scratch.canvasInDocSpace()
        if (sc == null) {
            // Same fallback as the commit path, and the same reasoning: a
            // beaded stroke is wrong and visible, a dropped one is wrong and
            // invisible.
            into.write {
                val save = it.save()
                it.clipRect(confine.damage)
                confine.clip?.let { path -> it.clipPath(path) }
                rasterizer.drawDry(it, stroke)
                it.restoreToCount(save)
            }
            return
        }
        rasterizer.drawDry(sc, stroke)
        into.write {
            val save = it.save()
            it.clipRect(confine.damage)
            confine.clip?.let { path -> it.clipPath(path) }
            scratch.compositeInto(
                it, compositeAlpha(), compositeGrain(), pen.erase, pen.burnish,
            )
            it.restoreToCount(save)
        }
    }

    /**
     * What a rebuild did, or why it did nothing.
     *
     * An enum and not a boolean, because "nothing happened" has four causes
     * here and three of them are ordinary. A caller that cannot tell
     * `NOT_VECTOR` from `SPOILED` cannot tell a raster sheet from a drawing
     * whose records no longer describe it.
     */
    enum class Rebuild { DONE, NOTHING_THERE, NOT_VECTOR, SPOILED }

    /** The last rebuild's cost and reach, for the readout. Render thread. */
    var lastRebuildMs: Double = 0.0
        private set

    /** Strokes redrawn by the last rebuild. See [lastRebuildMs]. */
    var lastRebuildStrokes: Int = 0
        private set

    /** Why the last rebuild stopped, if it did. See [lastRebuildMs]. */
    var lastRebuild: Rebuild = Rebuild.NOTHING_THERE
        private set

    /**
     * Repaint [damage] of [entry] from the strokes that made it. **Render
     * thread**, and never while a stroke is open.
     *
     * ## The damage rectangle is the design, not an optimisation
     *
     * Ik0 measured a full-sheet rebuild at 1.09 s for the pen and 7.82 s for
     * the tipped chalk, against a stop condition written at one second. The
     * fallback that condition named — vector sheets for opaque nibs only —
     * failed its own test, because the *pen* is over the bar too. So what makes
     * the feature affordable is that an edit only repaints what it dirtied, and
     * the rectangle has to be **tight**: half a page of pencil is still 1.7 s.
     *
     * ## One compositor, which is a stop condition
     *
     * Every stroke goes through [stampStroke], the same method the commit path
     * uses, with the same `ScratchLayer` and the same `DabRasterizer`. That is
     * `docs/inker-plan.md`'s third stop condition and it is why the brush is
     * *adopted into the pen* for the duration rather than handed to a second
     * code path: two compositors that drift is the defect a user finds months
     * later in a file they have already sent somewhere.
     *
     * Swapping the pen on the render thread is the trick `rerenderReport`
     * already uses, and it is safe for the same reason: a rebuild is one render
     * thread task, no commit is drained during it, and the pen is restored from
     * its own encoded text before the method returns.
     *
     * ## Clearing first
     *
     * The rectangle is blanked before anything is redrawn, because a rebuild
     * *replaces* what is there. Painting over without clearing is a second coat
     * — invisible on an opaque nib until the antialiased edges disagree, and
     * plainly darker on a translucent one.
     */
    fun rebuild(entry: LayerStack.Entry, damage: Bounds): Rebuild {
        val sheet = entry.vector ?: return finishRebuild(Rebuild.NOT_VECTOR, 0, 0.0)
        return rebuild(sheet, entry.layer, damage)
    }

    /**
     * [rebuild] against a sheet and pixels that need not be a stack entry.
     *
     * The bench measures against an offscreen `Layer` of its own so that
     * measuring does not paint over the drawing — the same reason
     * [stampStroke] takes its destination as a parameter.
     */
    fun rebuild(sheet: VectorSheet, into: Layer, damage: Bounds): Rebuild {
        if (!sheet.intact) return finishRebuild(Rebuild.SPOILED, 0, 0.0)
        if (damage.isEmpty) return finishRebuild(Rebuild.NOTHING_THERE, 0, 0.0)
        if (!damage.toPixelRect(rebuildRect, document.widthPx, document.heightPx)) {
            return finishRebuild(Rebuild.NOTHING_THERE, 0, 0.0)
        }
        val rect = Rect(rebuildRect[0], rebuildRect[1], rebuildRect[2], rebuildRect[3])
        val records = sheet.overlapping(damage)
        val started = System.nanoTime()
        into.blank(rect.left, rect.top, rect.right, rect.bottom)
        val held = BrushCodec.encode(pen)
        val heldRubber = borrowedRubber
        try {
            for (record in records) {
                adoptBrush(sheet.brushAt(record.brush), pen)
                // A drawing brush used with the barrel button erases without
                // being an eraser, and the record is the only thing that
                // remembers it did. See `compositeAlpha`.
                borrowedRubber = record.erase && !pen.erase
                // Ik13. The ruler the stroke was drawn against, out of the
                // sheet's own table and **not** out of the document: the guides
                // on the page now are not the ones this stroke was inked
                // against, and using them would make an undo three strokes
                // later slide every earlier line onto wherever the ruler has
                // got to. See `VectorSheet.snapAt`.
                val stroke = replay(record, sheet.snapAt(record.guide)) ?: continue
                stampStroke(stroke, into, Confinement(rect, sheet.clipAt(record.clip)))
            }
        } finally {
            BrushCodec.decode(held)?.let { adoptBrush(it, pen) }
            borrowedRubber = heldRubber
            armRasterizer()
        }
        return finishRebuild(
            Rebuild.DONE, records.size, (System.nanoTime() - started) / 1e6,
        )
    }

    private fun finishRebuild(what: Rebuild, strokes: Int, ms: Double): Rebuild {
        lastRebuild = what
        lastRebuildStrokes = strokes
        lastRebuildMs = ms
        return what
    }

    /** Reused by [rebuild]; render thread only. */
    private val rebuildRect = IntArray(4)

    /**
     * The rectangle waiting to be repainted, and the sheet it belongs to.
     *
     * **The throttle, and it is a coalescer rather than a timer.** A rate limit
     * would be the wrong shape: a rebuild that is *skipped* leaves the sheet
     * showing something the records do not say, which is the one state this
     * whole design exists to prevent. So nothing is ever skipped — repeated
     * requests are unioned into one rectangle and paid for once, at the top of
     * the next dry frame.
     *
     * That is also the shape the callers above Ik4 need. Ik9 drags a stroke and
     * dirties a rectangle eight times a second; Ik10 recolours and dirties one
     * per keystroke of a slider. Both want the last word rather than every
     * word, and both want it before the next frame is drawn rather than a fixed
     * number of milliseconds later.
     *
     * Before the dry clock starts, for the reason the measurement task above it
     * is: a rebuild inside the timed body would show up as one long dry frame
     * in the percentiles it exists to protect.
     */
    private val rebuildDamage = MutableBounds()

    private var rebuildTarget: LayerStack.Entry? = null

    /**
     * Ask for [damage] of [entry] to be repainted from its records, at the top
     * of the next dry frame. **Render thread.**
     *
     * A request for a second sheet before the first has been served repaints
     * the first immediately rather than mixing two rectangles into one — two
     * sheets' damage is two rebuilds, and unioning them would repaint each
     * sheet over the other's rectangle.
     */
    fun requestRebuild(entry: LayerStack.Entry, damage: Bounds) {
        if (damage.isEmpty) return
        val held = rebuildTarget
        if (held != null && held !== entry) drainRebuild()
        rebuildTarget = entry
        rebuildDamage.addBounds(damage)
        redrawDry()
    }

    /**
     * Told on the UI thread when the picked set has changed, so the chrome can
     * redraw its highlight. `onMarqueeChanged`'s counterpart.
     *
     * Invoked from the render thread, so the callback posts.
     */
    var onPickChanged: (() -> Unit)? = null

    /**
     * Whether a select gesture picks strokes rather than pixels. UI thread.
     *
     * **Set by the panel, not inferred from the sheet**, and that is the
     * decision. Inferring it would make the same tool do a different thing
     * depending on which sheet is active with nothing on screen to say so,
     * which is the objection `docs/inker-plan.md` raises against exactly this
     * shape of shortcut. The panel shows the choice, and shows it only where
     * there is a choice to make.
     */
    @Volatile
    var pickingStrokes: Boolean = false

    /**
     * How much of a stroke the eraser takes on a sheet that keeps strokes. UI
     * thread; read on the render thread at pen-up, so it is volatile.
     */
    @Volatile
    var eraseMode: EraseMode = EraseMode.WHOLE

    /**
     * One per view, reused: the eraser's own scratch lists are the point of it
     * being an object rather than a function, and an erase drag asks it the
     * same question of fifty strokes.
     */
    private val eraser = StrokeEraser()

    /**
     * Whether the sheet the pen is on keeps its strokes. UI thread, set by the
     * chrome; read at pen-up, so it is volatile.
     *
     * Mirrored rather than read from the stack, for the reason `pickingStrokes`
     * is: the stack belongs to the render thread, and the decision has to be
     * made on the thread the gesture ends on.
     */
    @Volatile
    var vectorSheetActive: Boolean = false

    /**
     * A finished stroke's centreline, as a document-space path.
     *
     * The dab centres, which is where the nib actually went — close enough to
     * the input to erase by and already in hand, where the samples are not: by
     * pen-up the gesture is a `Stroke` and nothing keeps its samples unless the
     * sheet asked for a record.
     */
    private fun pathOf(stroke: Stroke): Path {
        val out = Path()
        if (stroke.dabCount == 0) return out
        out.moveTo(stroke.x(0), stroke.y(0))
        for (i in 1 until stroke.dabCount) out.lineTo(stroke.x(i), stroke.y(i))
        return out
    }

    /** The widest the eraser's nib got, which is how far its rub reached. */
    private fun eraseReach(stroke: Stroke): Float {
        var r = 0f
        for (i in 0 until stroke.dabCount) if (stroke.radius(i) > r) r = stroke.radius(i)
        return r
    }

    /**
     * The one implementation of [SheetRebuilder], handed to the document so
     * that a `VectorStep` can repaint without knowing about the rasterizer or
     * the scratch buffer.
     *
     * An object rather than a lambda so it can be installed once in [init]
     * rather than captured; it holds nothing but the view.
     */
    private val sheetRebuilder = object : SheetRebuilder {
        override fun rebuild(entry: LayerStack.Entry, damage: Bounds) {
            this@InkSurfaceView.rebuild(entry, damage)
        }
    }

    init {
        document.rebuilder = sheetRebuilder
    }

    /**
     * Repaint the whole active sheet from its records. **UI thread**, from the
     * debug row.
     *
     * The only way to *see* Ik4, because a rebuild that works is a rebuild that
     * changes nothing: what it is checked against is the drawing already on the
     * glass. A small rectangle would prove it for a corner; the whole page
     * proves it for the page, and Ik0 has already priced that at 1 to 8 seconds
     * depending on the nib, which is a price a debug button may pay.
     */
    fun rebuildWholeActiveSheet() {
        val r = renderer ?: return
        if (!surfaceAlive || renderThreadTask != null) return
        renderThreadTask = {
            val entry = document.layers.active
            rebuild(
                entry,
                Bounds.of(0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat()),
            )
            document.layers.touchActive()
        }
        r.commit()
        redrawDry()
    }

    /** Serve the coalesced request, if there is one. **Render thread.** */
    private fun drainRebuild() {
        val entry = rebuildTarget ?: return
        val damage = rebuildDamage.snapshot()
        rebuildTarget = null
        rebuildDamage.reset()
        if (rebuild(entry, damage) == Rebuild.DONE) document.layers.touchActive()
    }

    /**
     * Decoded samples, grown as needed and never shrunk — the same discipline
     * `StrokeBuilder`'s dab buffer keeps, for the same reason: a rebuild walks
     * fifty records and a fresh array per record is fifty allocations inside
     * one render callback.
     */
    private var replayFloats = FloatArray(0)

    /**
     * One record back through the builder that drew it.
     *
     * The seed and the `dabBase` come off the record, which is the whole of
     * Ik2: without them this would draw a *similar* stroke, and the pencil's
     * scatter would land somewhere else every time anything was touched. Ik13
     * adds [snap] to that list for the same reason, and the caller takes it
     * from the *sheet's* table rather than from the document.
     *
     * Null for a record with no samples, which a cancelled gesture can leave.
     */
    private fun replay(record: StrokeRecord, snap: Snap? = null): Stroke? {
        if (record.sampleCount == 0) return null
        if (replayFloats.size < record.floatCount) replayFloats = FloatArray(record.floatCount)
        val n = record.decodeInto(replayFloats)
        val b = rebuildBuilder
        // Before `begin`, like everything else this stroke is a function of.
        // A record stores the raw samples and the guide is applied on the way
        // to the dabs, so this is not decoration — leave it null for a stroke
        // that had one and the line comes back off the ruler.
        b.snap = snap
        b.begin(record.colorArgb, record.seed, record.dabBase)
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            val nanos = REPLAY_EPOCH_NANOS + (replayFloats[o + 5] * 1_000_000f).toLong()
            b.addTilt(replayFloats[o + 3], replayFloats[o + 4], nanos)
            b.add(replayFloats[o], replayFloats[o + 1], replayFloats[o + 2], nanos)
        }
        return b.end()
    }

    /**
     * A second builder over the *same* `pen` object, so that adopting a
     * record's brush into the pen changes what this one draws with.
     *
     * Separate from the live [commitSink]'s builder because that one can be
     * open — a stroke in flight — and a rebuild must not reset it. Lazily
     * built, because a session that never makes an ink layer never pays for it.
     */
    private val rebuildBuilder: StrokeBuilder by lazy { StrokeBuilder(pen) }

    private val commitSink = object : CommitQueue.Sink {
        override fun onStroke(stroke: Stroke, record: PendingStroke?) {
            // **Which kind of undo step this stroke gets**, decided here
            // because here is the only place that knows both the sheet and the
            // stroke. On a sheet that keeps its strokes the answer is a
            // `VectorStep` of a few kilobytes, recorded *after* the ink lands
            // because the record does not exist until then. On any other sheet
            // it is a `PixelPatch` of up to 28 MB, captured *before*, because
            // this is the last moment the region exists in its pre-stroke
            // state -- taken afterwards it would record the stroke as its own
            // undo.
            val keeping = record != null && document.layers.active.vector?.intact == true
            if (!keeping) document.snapshotBeforeStroke(stroke.bounds)
            // W6's indirect path. The wet pass has usually already accumulated
            // this stroke onto the scratch. Reuse it when it has: re-laying
            // every dab would double the paint on a translucent brush, which is
            // the very bug the buffer exists to prevent, wearing a different
            // hat.
            //
            // This is the one thing [stampStroke] cannot do, and the reason it
            // stayed here rather than moving with the rest: it is about *this*
            // stroke having just been drawn, and nothing that re-renders an
            // old record is ever in that position.
            if (indirectNeeded() && scratch.isOpen && scratchEpoch == strokeEpoch &&
                scratch.ensureCovers(stroke.bounds)
            ) {
                // Already masked by the wet pass, and `DST_IN` is idempotent,
                // so this is belt to that braces -- and it is what covers the
                // case where `ensureCovers` has just grown the buffer into
                // ground the wet pass never masked.
                document.selection.maskBitmap()?.let { scratch.maskBy(it) }
                document.layer.write {
                    scratch.compositeInto(
                        it, compositeAlpha(), compositeGrain(), pen.erase, pen.burnish,
                    )
                }
            } else {
                stampStroke(stroke, document.layer)
            }
            val kept = keepRecord(record, stroke)
            if (kept != null) {
                document.recordVectorEdit(
                    VectorStep(document.layers.active.id, added = listOf(kept), removed = emptyList()),
                )
            }
            document.layers.touchActive()
        }

        /**
         * Clear, which means *clear the stencil* when there is one.
         *
         * What every editor does, and what the request implies: a selection is
         * for confining what you do to the page, and the most destructive thing
         * on the bar is the last one that should ignore it.
         */
        override fun onClear() {
            document.snapshotBeforeClear()
            val stencil = document.selection.maskBitmap()
            if (stencil != null) document.layer.blank(stencil) else document.layer.blank()
            // A whole clear empties the record list too, and the two halves
            // have to move together or the sheet's strokes describe ink that is
            // no longer in it -- the same invariant `Document.clearHistory`
            // keeps for the stroke bounds. A clear *inside a selection* is a
            // partial erase, which a record list cannot express until Ik8
            // splits strokes, so it spoils instead. See `VectorSheet.intact`.
            document.layers.active.vector?.let {
                if (stencil != null) it.spoil("a clear inside a selection") else it.clear()
            }
            document.layers.touchActive()
        }

        /**
         * Undo, redo and a float drop all move pixels the record list has no
         * way to follow. See `VectorSheet.intact`: they spoil every vector
         * sheet rather than leaving the two halves quietly disagreeing.
         */
        private fun spoilVectors(reason: String) {
            val stack = document.layers
            for (i in 0 until stack.size) stack.entryAt(i).vector?.spoil(reason)
        }

        // No `spoilVectors` here any more. Ik5 made the answer precise: a
        // `VectorStep` moves the record list and repaints, so nothing
        // disagrees; a `PixelPatch` that lands on a sheet which keeps strokes
        // spoils *that* sheet, from inside `PixelPatch.exchange`, where the
        // layer id is known.
        override fun onUndo() {
            document.applyUndo()
            prunePick()
        }

        override fun onRedo() {
            document.applyRedo()
            prunePick()
        }

        /**
         * Drop picked ids the sheet no longer has.
         *
         * Without it, undoing a stroke that was picked leaves it picked, and
         * the next operation on the set is a no-op nobody can explain. The
         * republish is unconditional on a *redo*, because a record that came
         * back has the same id and the highlight has to find it again.
         */
        private fun prunePick() {
            val sheet = document.layers.active.vector ?: return
            if (document.picked.layerId != document.layers.active.id) return
            if (document.picked.prune(sheet)) {
                post { onPickChanged?.invoke() }
            } else if (document.picked.active) {
                document.picked.republish(sheet)
                post { onPickChanged?.invoke() }
            }
        }

        /**
         * A change to the stack, in its place in the queue. See
         * `CommitQueue.Commit.Layers`.
         *
         * Every thumbnail is marked stale rather than only the one that moved,
         * because most of these operations change what the *panel* shows about
         * several sheets at once — a delete renumbers nothing but shifts every
         * row, a reorder moves two, a duplicate inserts one — and the cost of
         * being wrong here is a picture that does not match its label. The
         * refresh is one sheet per frame and only while the panel is open, so
         * marking eight costs at most eight frames of a panel that is being
         * looked at.
         */
        override fun onLayers(op: be.thalos.artiest.doc.LayerOp) {
            if (!document.layers.apply(op)) return
            document.layers.touchAll()
            // A project has just replaced the stack. Every undo step describes
            // sheets that were closed a line ago. See `Document.resetHistory`.
            if (op is be.thalos.artiest.doc.LayerOp.Open) document.resetHistory()
            // A stroke id is unique *within a sheet*, so a picked set held
            // across a sheet change would name other strokes — and the first
            // thing done to it would happen to them. See `StrokePick`.
            if (document.picked.layerId != document.layers.active.id) {
                if (document.picked.clear()) post { onPickChanged?.invoke() }
            }
        }

        /**
         * A change to the stencil, in its place in the queue. See
         * `CommitQueue.Commit.Select`.
         *
         * Nothing is marked stale: a selection does not change a pixel of any
         * sheet, so the thumbnails are still pictures of the drawing. What it
         * changes is where the *next* stroke may land, and the marching ants,
         * which are the chrome's and read the published snapshot.
         */
        override fun onSelect(op: SelectOp) {
            document.selection.apply(op)
        }

        /**
         * A lift, a move, a drop or a cancel, in its place in the queue. See
         * `CommitQueue.Commit.Float`.
         *
         * The thumbnail is marked stale by `Document.applyFloat` itself, at the
         * one place a float actually writes pixels.
         */
        /**
         * Pick strokes. **Render thread**, in its turn in the queue.
         *
         * Against the *active* sheet, which is what the gesture was aimed at. A
         * sheet that keeps no strokes answers nothing rather than refusing: the
         * gesture that produced this only happens when the panel says strokes,
         * and the panel only says strokes on an ink sheet — but the pen can be
         * moved between the gesture and the drain, and dropping the pick is a
         * better answer to that than picking on the wrong sheet.
         */
        override fun onStrokeOp(op: StrokeOp) {
            val entry = document.layers.active
            val sheet = entry.vector
            // Posted, not called: this runs on the render thread and the
            // listener touches Compose state.
            if (sheet == null) {
                if (document.picked.clear()) post { onPickChanged?.invoke() }
                return
            }
            if (!sheet.intact) return
            when (op) {
                is StrokeOp.Erase -> edit(eraser.plan(op, sheet, entry.id), sheet, entry)
                StrokeOp.DeletePicked ->
                    edit(eraser.planDelete(document.picked.toArray(), sheet, entry.id), sheet, entry)
                is StrokeOp.Transform -> {
                    val step = StrokeMove.plan(op, document.picked.toArray(), sheet, entry.id)
                    if (step != null) {
                        StrokeMove.apply(step, sheet)
                        replaced(step, sheet, entry)
                    }
                }
                is StrokeOp.Restyle -> {
                    val was = document.picked.toArray()
                    val step = StrokeRestyle.plan(op, was, sheet, entry.id)
                    if (step != null) {
                        StrokeRestyle.apply(step, sheet)
                        replaced(step, sheet, entry)
                    }
                }
                else ->
                    if (document.picked.apply(op, sheet, entry.id)) post { onPickChanged?.invoke() }
            }
        }

        /**
         * Apply a planned edit: change the list, record the undo step, repaint
         * what it touched, and drop any picked id that is now gone.
         *
         * In that order, and the order is the invariant. The step is recorded
         * **after** the list moves so that it describes what happened rather
         * than what was about to; the repaint is asked for after both, so it
         * paints the list as it now is; and the pruning is last because it
         * reads the list.
         */
        /**
         * Record an edit that *replaced* records, repaint it, and move the
         * picked set onto the replacements.
         *
         * The last part is what makes a restyle or a drag feel like one action
         * rather than two: the replacements are new records with new ids — they
         * have to be, because an undo step whose two halves name the same thing
         * removes its own replacement — so without this the user would watch
         * their selection vanish for having changed its colour or nudged it a
         * pixel.
         */
        private fun replaced(
            step: VectorStep,
            sheet: be.thalos.artiest.doc.VectorSheet,
            entry: LayerStack.Entry,
        ) {
            document.recordVectorEdit(step)
            rebuild(entry, step.damage())
            document.layers.touchActive()
            redrawDry()
            document.picked.setTo(
                LongArray(step.added.size) { step.added[it].id }, sheet, entry.id,
            )
            post { onPickChanged?.invoke() }
        }

        private fun edit(
            step: VectorStep?,
            sheet: be.thalos.artiest.doc.VectorSheet,
            entry: LayerStack.Entry,
        ) {
            if (step == null) return
            // The edit, in the log, because it is the one thing about Ik8 that
            // a screenshot cannot show: which records went, which took their
            // place, and over what rectangle. `docs/inker-plan.md`'s fourth
            // stop condition is about whether the junction it picked is the one
            // the hand meant, and that is a question about the records.
            android.util.Log.i(
                "artiest.ik8",
                "erase ${eraseMode.name}: -${step.removed.size} +${step.added.size}  " +
                    "damage ${step.damage()}  " +
                    "removed ${step.removed.map { "${it.id}:${it.sampleCount}" }}  " +
                    "added ${step.added.map { "${it.id}:${it.sampleCount}@${it.dabBase}" }}",
            )
            eraser.apply(step, sheet)
            document.recordVectorEdit(step)
            rebuild(entry, step.damage())
            document.layers.touchActive()
            // **And ask for a frame.** This runs inside the commit drain, which
            // is itself inside a render pass, so the pixels it just changed are
            // behind the compositor rather than in front of it: without this
            // the sheet is correct and the screen goes on showing the stroke
            // that was rubbed out until something else asks for a redraw.
            // Found on the tablet, where the records were right and the ink
            // stayed put.
            redrawDry()
            if (document.picked.prune(sheet)) post { onPickChanged?.invoke() }
        }

        override fun onFloat(op: FloatOp) {
            // A lift reads pixels and a drop writes them, and neither is a
            // stroke. See `VectorSheet.intact`. Spoiled on every float op
            // rather than only on the drop, because a lift has already taken
            // ink off the sheet by the time the drop lands.
            spoilVectors("moving pixels")
            document.applyFloat(op)
        }
    }

    /**
     * [DabBatchPool.issuedCount] as of the last commit, handed to the render
     * thread so it can release every batch the commit flushed.
     *
     * Needed because `commit()` drains the library's param queue into
     * [onDrawMultiBufferedLayer], which ignores the params — so those batches
     * are finished with but would never be reported drawn by the front-buffer
     * callback, and the pool would spill forever after the first stroke.
     */
    @Volatile
    private var commitWatermark: Long = 0L

    /** Nanoseconds spent submitting during the event being handled. */
    private var submitNanos: Long = 0L

    /** The open stroke's frozen paint settings, stamped onto every batch. */
    private var strokeColorArgb: Int = Color.BLACK
    private var strokeAntiAlias: Boolean = true

    private var renderer: CanvasFrontBufferedRenderer<DabBatch>? = null

    /** True between `surfaceCreated` and `surfaceDestroyed`. UI thread. */
    private var surfaceAlive = false

    // --- render thread only, below this line -------------------------------

    /**
     * The stamp cache, owned here because the rasterizer it feeds is owned
     * here and both are render-thread-only. See [StampCache].
     */
    private val stamps = StampCache()

    private val rasterizer = DabRasterizer(document.widthPx, document.heightPx, stamps)

    /**
     * Which dab path the renderer uses. W5's A/B switch, surfaced so the two
     * can be compared on the device at identical settings rather than across
     * two builds.
     *
     * Written from the UI thread and read on the render thread. A plain `var`
     * would be a data race that in practice resolves within a frame, which is
     * the kind of race that works until it does not; `@Volatile` costs a
     * fence on a field read once per batch, not once per dab.
     */
    @Volatile
    var stampMode: Boolean = false
        set(value) {
            field = value
            rasterizer.mode = if (value) DabRasterizer.Mode.STAMP else DabRasterizer.Mode.CIRCLE
        }

    /** How many masks the stamp path has uploaded, for the instruments. */
    val stampUploads: Long get() = stamps.uploads

    /** The stamp cache's hit rate, or NaN before it has been asked anything. */
    val stampHitRate: Double get() = stamps.masks.hitRate

    /** Masks currently held, and their bytes, for the instruments. */
    val stampCount: Int get() = stamps.masks.size
    val stampBytes: Long get() = stamps.masks.byteCount

    /**
     * W6's scratch buffer. See [ScratchLayer]; used only when the brush is
     * translucent, because for a fully opaque nib the direct path produces
     * identical pixels for less work.
     */
    /** W8's paper tooth. See [GrainTexture]. */
    private val grain = GrainTexture()

    /** Grain tiles generated, for the instruments. */
    val grainBuilds: Long get() = grain.builds

    private val scratch = ScratchLayer(
        maxWidth = document.widthPx + 2 * ScratchLayer.PAD,
        maxHeight = document.heightPx + 2 * ScratchLayer.PAD,
    )

    /** Bitmap format for the scratch buffer. The F16 question, switchable on device. */
    var scratchF16: Boolean = false
        set(value) {
            field = value
            scratch.release()
            scratch.config =
                if (value) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
        }

    /** Scratch allocations and growths, for the instruments. */
    val scratchAllocations: Long get() = scratch.allocations
    val scratchGrowths: Long get() = scratch.growths
    val scratchExtent: String
        get() = "${scratch.usedWidth}x${scratch.usedHeight} of ${scratch.width}x${scratch.height}"

    /**
     * Whether this stroke has to go through the scratch buffer.
     *
     * Opaque nibs do not: overlapping opaque dabs composite to the same colour
     * as one dab, so the direct path is not an approximation for them, it is
     * the same picture for less work. That is the whole of Phase 1's defence
     * for shipping without a scratch buffer, and it stays true.
     *
     * Hardness counts because a soft edge *is* a translucent rim, which is the
     * beading case wearing a different hat.
     *
     * **A live selection forces it, whatever the nib is.** The stencil is
     * applied by masking the scratch buffer — see `ScratchLayer.maskBy` for why
     * it is a mask on the pixels and not a clip on a canvas — so an opaque nib
     * taking the direct path would be the one stroke in the app that could
     * paint outside the selection. The cost is the scratch path, which is
     * already shipped and already measured; the alternative is a second way of
     * confining ink, which is a second way of getting it wrong.
     */
    private fun indirectNeeded(): Boolean =
        indirectNeededFor(pen, document.selection.active)

    /**
     * The same question for a brush that is not necessarily the one in the
     * hand, which is what a rebuild asks: every stroke it redraws names its own
     * nib out of the sheet's table.
     *
     * [clipped] is separate from the brush because on the commit path a
     * selection forces the indirect route whatever the nib is — there is
     * exactly one way ink is confined — while a rebuild confines with a canvas
     * clip and does not need the buffer for it.
     */
    private fun indirectNeededFor(brush: Brush, clipped: Boolean): Boolean =
        brush.opacity < 1f || brush.flow < 1f || brush.hardness < 1f ||
            brush.tip != null || brush.grain.isActive || brush.erase || clipped

    /**
     * Bumped on the UI thread whenever a stroke starts or is abandoned, and
     * read on the render thread to tell one stroke's wet accumulation from the
     * next's.
     *
     * Needed because an abandoned stroke never reaches the commit sink, so
     * nothing closes the scratch: without this the ink from a stroke cancelled
     * by a palm would still be sitting on the buffer when the next stroke
     * opened, and would composite into the layer as part of it. `isOpen` alone
     * cannot see that, because from the buffer's point of view nothing
     * happened.
     */
    @Volatile
    private var strokeEpoch: Int = 0

    /** The epoch the scratch currently holds. Render thread only. */
    private var scratchEpoch: Int = -1

    /** Reused by [drawWetIndirect]; render thread only, so one array is enough. */
    private val wetRect = FloatArray(4)

    /**
     * The wet pass for a translucent brush: accumulate, then repaint the
     * region from scratch.
     *
     * **Why the front buffer cannot simply be drawn on for these brushes.** It
     * accumulates — that is what makes it fast — so blitting a scratch that has
     * itself grown darker means compositing the stroke over an older copy of
     * the same stroke, which beads exactly as drawing the dabs directly would.
     * The only correct answer is to *replace* the affected pixels, and to
     * replace them we have to be able to reproduce what is underneath: the
     * desk, the paper, the committed layer, and then the stroke so far.
     *
     * That is affordable because the region is small. The clip is the batch's
     * own bounds — a few hundred document pixels across at most, since a batch
     * is one frame's worth of dabs — so the layer blit inside it is a small
     * copy and not a page repaint. Phase 1's W2 measured the full-redraw loop
     * and it passed with headroom; this is a fraction of that per batch.
     *
     * This is also what "the wet stroke is re-renderable" means in W13's entry
     * condition: with this path, the pixels under the pen are a function of the
     * stroke so far rather than a history of what has been drawn on them.
     */
    private fun drawWetIndirect(canvas: Canvas, docToView: Matrix, batch: DabBatch) {
        if (batch.size == 0) return
        val t0 = System.nanoTime()
        try {
            drawWetIndirectTimed(canvas, docToView, batch)
        } finally {
            wetNanos += System.nanoTime() - t0
            wetCalls++
        }
    }

    /**
     * How long the indirect wet pass is taking, per batch.
     *
     * Added because the input-side instruments could not see the problem at
     * all: `onTouchEvent` measured 0.88 ms while the ink was arriving half a
     * second after the pen. Everything this path does happens on the render
     * thread, and until now nothing timed it — `submit` measures the handoff,
     * not the work.
     */
    var wetNanos: Long = 0L
        private set
    var wetCalls: Long = 0L
        private set

    /** Mean milliseconds in the indirect wet pass, or 0 before it has run. */
    val wetMeanMs: Float
        get() = if (wetCalls == 0L) 0f else wetNanos / 1e6f / wetCalls

    /**
     * How long a dry frame is taking, and on what kind of canvas.
     *
     * Phase 3's S0. The phase's plan rests on a bench that ran on this
     * machine's CPU, and half of what it measured — the sheet-by-sheet
     * composite — does not happen there in the app: the renderer holds an
     * `android.graphics.RenderNode` (read out of the 1.0.4 aar with `javap`),
     * so [dryHardware] should read true and those blits should be the GPU's.
     * *Should*. Nothing in the repo has ever checked it, the plan says so
     * plainly, and the whole question of whether the layer stack needs a cached
     * compositor turns on the answer.
     *
     * Everything inside `onDrawMultiBufferedLayer` is counted: the desk, the
     * paper, the commit drain and every visible sheet. That is the frame, which
     * is the thing with a budget — 11.1 ms at 90 Hz — rather than any one part
     * of it.
     *
     * **The statistics reset when the sheet count changes**, which is what makes
     * them usable by hand. The question S0 asks is "what does a dry frame cost
     * at one sheet, and at eight", and a mean pooled across a session that
     * added seven sheets answers neither. Adding a sheet starts the count again.
     */
    var dryNanos: Long = 0L
        private set
    var dryCalls: Long = 0L
        private set
    var dryLastNanos: Long = 0L
        private set
    var dryMaxNanos: Long = 0L
        private set

    /** Sheets composited in the last dry frame — visible ones, not the stack's size. */
    var drySheets: Int = 0
        private set

    /** What the statistics above are counting. See [dryNanos]. */
    private var dryStatsFor: Int = -1

    /**
     * Whether the multi-buffered canvas is hardware accelerated.
     *
     * Read from the canvas itself on the first dry frame rather than assumed
     * from the library's field names. `@Volatile` because the render thread
     * writes it and the readout reads it.
     */
    @Volatile
    var dryHardware: Boolean = false
        private set

    /**
     * The sheet-by-sheet composite alone, out of the dry frame.
     *
     * Two clocks and not one, because the frame contains two very different
     * things and S0 only asks about one of them. A frame that also drained a
     * commit has stamped a stroke into a `Layer` — CPU work, under the layer
     * lock, proportional to the stroke — and a frame that also built a
     * thumbnail has rescaled a 7.1 Mpx page five times. Both are real costs and
     * both belong in [dryNanos]; neither says anything about what a stack of
     * eight sheets costs to blit, which is the question the cached compositor
     * is gated on.
     */
    var compositeNanos: Long = 0L
        private set
    var compositeCalls: Long = 0L
        private set
    var compositeLastNanos: Long = 0L
        private set
    var compositeMaxNanos: Long = 0L
        private set

    /** Mean milliseconds in the dry composite, or 0 before one has run. */
    val compositeMeanMs: Float
        get() = if (compositeCalls == 0L) 0f else compositeNanos / 1e6f / compositeCalls

    /** The last dry composite, in milliseconds. */
    val compositeLastMs: Float get() = compositeLastNanos / 1e6f

    /** The worst dry composite since the sheet count last changed. */
    val compositeMaxMs: Float get() = compositeMaxNanos / 1e6f

    /**
     * [redrawDry] to `onMultiBufferedLayerRenderComplete`: the whole round
     * trip, and the only clock here that can see the GPU.
     *
     * **The other two clocks do not measure drawing, and finding that out is
     * half of what S0 was for.** The `Canvas` handed to
     * `onDrawMultiBufferedLayer` belongs to an `android.graphics.RenderNode`,
     * so `drawBitmap` on it *records a draw op* and returns; the rasterizing
     * happens afterwards, on the GPU, inside the library's own render pass.
     * [compositeNanos] therefore measures how long it takes to write down
     * "blit these eight bitmaps", which is close to free however many there
     * are, and quoting it as the cost of a stack would be a confident wrong
     * answer of exactly the kind Phase 1's W2 produced.
     *
     * This one spans the request, the recording, the GPU pass and the buffer
     * handoff. It is coarser than a GPU trace and it is the honest instrument
     * available from inside the app.
     *
     * Not reset with the sheet count, because a round trip can span a change:
     * the request goes out, a layer op lands in the drain, and the completion
     * arrives against a different stack. [roundTripCalls] counts them all and
     * the reader is expected to let it settle.
     */
    var roundTripNanos: Long = 0L
        private set
    var roundTripCalls: Long = 0L
        private set
    var roundTripLastNanos: Long = 0L
        private set
    var roundTripMaxNanos: Long = 0L
        private set

    @Volatile
    private var roundTripStartNanos: Long = 0L

    /** Mean milliseconds from asking for a dry frame to being told it landed. */
    val roundTripMeanMs: Float
        get() = if (roundTripCalls == 0L) 0f else roundTripNanos / 1e6f / roundTripCalls

    /** The last dry round trip, in milliseconds. */
    val roundTripLastMs: Float get() = roundTripLastNanos / 1e6f

    /** The worst dry round trip since the view was created. */
    val roundTripMaxMs: Float get() = roundTripMaxNanos / 1e6f

    /** See [roundTripNanos]. Render thread, from the completion callback. */
    private fun recordRoundTrip() {
        val start = roundTripStartNanos
        if (start == 0L) return
        roundTripStartNanos = 0L
        val elapsed = System.nanoTime() - start
        roundTripNanos += elapsed
        roundTripCalls++
        roundTripLastNanos = elapsed
        if (elapsed > roundTripMaxNanos) roundTripMaxNanos = elapsed
    }

    /** Mean milliseconds in a dry frame, or 0 before one has run. */
    val dryMeanMs: Float
        get() = if (dryCalls == 0L) 0f else dryNanos / 1e6f / dryCalls

    /** The last dry frame, in milliseconds. */
    val dryLastMs: Float get() = dryLastNanos / 1e6f

    /** The worst dry frame since the sheet count last changed. */
    val dryMaxMs: Float get() = dryMaxNanos / 1e6f

    private fun drawWetIndirectTimed(canvas: Canvas, docToView: Matrix, batch: DabBatch) {
        rasterizer.boundsOf(batch, wetRect)
        val bounds = Bounds.of(wetRect[0], wetRect[1], wetRect[2], wetRect[3])
        if (!scratch.isOpen || scratchEpoch != strokeEpoch) {
            scratch.begin(bounds)
            scratchEpoch = strokeEpoch
        } else {
            scratch.ensureCovers(bounds)
        }
        val sc = scratch.canvasInDocSpace() ?: return
        armRasterizer()
        // 1f for the reason the commit path gives: the batch's dabs carry
        // their own flow. Anything else here and the wet stroke would not
        // match the committed one, which is the one thing this path may not do.
        rasterizer.drawInto(sc, batch, 1f)
        // Every batch, because `ensureCovers` may have grown the buffer into
        // ground that has never been masked, and because `DST_IN` against a
        // fixed mask is idempotent so re-masking what was already masked costs
        // a blit and changes nothing.
        document.selection.maskBitmap()?.let { scratch.maskBy(it) }

        val save = canvas.save()
        canvas.concat(docToView)
        canvas.clipRect(wetRect[0], wetRect[1], wetRect[2], wetRect[3])
        canvas.clipRect(0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat())
        // The whole stack and not just the active sheet, clipped to the dirty
        // rectangle two lines above. Painting only the active layer here would
        // make every sheet above the pen disappear inside the wet stroke's
        // rectangle for as long as the pen was down -- a moving hole in the
        // drawing that closed again at pen-up.
        compositeStack(canvas, wetRect[0], wetRect[1], wetRect[2], wetRect[3])
        canvas.restoreToCount(save)
    }

    /**
     * Paper and every sheet, bottom to top, with the wet stroke in its place on
     * the active one. Document space; the caller has already concatenated the
     * matrix and clipped.
     *
     * The loop itself is [StackCompositor]'s, and it is there rather than here
     * because `PngExporter` had a second copy of it that already disagreed
     * about where the paper goes. What is left in this file is the part that is
     * genuinely the renderer's: what the wet stroke *is*.
     *
     * **The wet stroke belongs *inside* the stack, not on top of it.** Ink
     * going onto the third of five sheets must be hidden by the two above it
     * while it is still wet, or the stroke jumps behind them at pen-up.
     */
    private fun compositeStack(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        compositor.compose(
            canvas,
            document.layers,
            document.paperColor,
            document.widthPx,
            document.heightPx,
            wetInk,
            l, t, r, b,
            document.floating,
        )
        drySheets = compositor.sheetsPainted
    }

    /**
     * One per view. See [StackCompositor]'s threading note: its `Paint` is not
     * shareable, and the export builds its own.
     */
    private val compositor = StackCompositor()

    /**
     * The open stroke, as the compositor sees it.
     *
     * An object rather than a lambda because it is read on the render thread
     * every frame and a capturing lambda per frame is an allocation on the one
     * path with a measured budget — the same reason `CommitQueue.Sink` is an
     * interface.
     */
    private val wetInk = object : StackCompositor.Wet {
        override val position: Int get() = document.layers.activePosition

        /**
         * Open **and belonging to the stroke in hand**.
         *
         * The epoch check is not decoration. `ScratchLayer.isOpen` stays true
         * after a stroke ends — nothing closes it, because keeping the buffer
         * is the point — so on its own it says "there is a buffer", not "there
         * is wet ink". For an ordinary stroke the difference is invisible: the
         * buffer holds the stroke that was just committed, so compositing it
         * again paints the same pixels twice in the same place.
         *
         * It stops being invisible the moment a stroke is *abandoned* rather
         * than committed, which is what Ik8's eraser does on a sheet that keeps
         * its strokes: the sheet repaints itself correctly and the stale buffer
         * goes on being drawn over the top, so the screen shows the stroke that
         * was just rubbed out. Found on the tablet, and only by restarting the
         * app and seeing the drawing come back right.
         *
         * `strokeEpoch` is bumped at every pen-down and at every abandon, so
         * this is exactly the same guard the commit path and the wet pass
         * already use — it was the one reader that had been left out.
         */
        override val isOpen: Boolean get() = scratch.isOpen && scratchEpoch == strokeEpoch
        override val erases: Boolean get() = pen.erase
        override fun draw(canvas: Canvas) {
            if (pen.erase) {
                scratch.drawOnto(canvas, compositeAlpha(), compositeGrain(), erase = true)
            } else {
                scratch.drawOnto(canvas, compositeAlpha(), compositeGrain(), burnish = pen.burnish)
            }
        }
    }

    /**
     * Whether the pen draws or selects.
     *
     * A mode on the view rather than a second `InkInputSink`, and that is a
     * deliberately small change. Every rule about *who* may draw —
     * `StrokeExclusivity`'s palm rejection, its two-finger gesture, the
     * cancel-on-focus-loss — is about pointers and applies to a marquee word
     * for word. A second sink would have had to inherit all of it or lose it,
     * and the tap recogniser and the gesture controller both keep state that
     * would then need a home. So the stroke callbacks fork inside
     * [StrokeDriver] and everything else is untouched.
     *
     * The fork is decided at pen-down and held for the gesture's life, the same
     * shape [eraseDecided] has: a mode switch with the pen already on the glass
     * must not turn half a lasso into half a pencil line.
     */
    @Volatile
    var selecting: Boolean = false

    /**
     * Whether the pen picks a colour instead of drawing one.
     *
     * Lr1, and it is [selecting]'s third case rather than a third mechanism:
     * the fork is inside [StrokeDriver], decided at pen-down and held for the
     * gesture, so palm rejection, the two-finger gesture and cancel-on-focus
     * apply to a pick word for word without any of them being restated.
     *
     * **It wins over [selecting]** when both are somehow on. A picker is a
     * momentary tool the user has just reached for and a marquee is a mode they
     * have been in for a while, and the one the hand meant is the one it
     * touched last.
     */
    @Volatile
    var picking: Boolean = false

    /**
     * Answer with the active sheet's own pixel rather than with what is on the
     * screen. UI thread. See `ColourProbe.at`.
     */
    var pickFromActiveOnly: Boolean = false

    /**
     * Whether the sheet the pen is on refuses ink. Lr4.
     *
     * Pushed by the chrome rather than read off the stack, because the stack
     * belongs to the render thread and this is read at pen-down on the UI one.
     * It is the same arrangement [pickingStrokes] has and for the same reason.
     *
     * **Checked where the stroke begins**, so a pen on a locked sheet does
     * nothing at all — no wet ink, no queued commit, no undo step. Checking at
     * the commit instead would draw the stroke, show it, and then throw it
     * away, which reads as the app losing work.
     *
     * A pick and a marquee still work: neither puts anything on the sheet, and
     * taking a colour off a locked photograph is the main thing anybody wants
     * to do with one.
     */
    @Volatile
    var activeLocked: Boolean = false

    /**
     * The colour under the pen while a pick is in the hand, or 0 when none is.
     *
     * Read by the chrome inside a draw lambda, like [liveMarquee], and for its
     * reason: this changes at pointer rate and must not recompose anything.
     * Zero rather than null because it is read on every frame of the ring and a
     * boxed Int per frame is a boxed Int per frame.
     */
    var pickPreview: Int = 0
        private set

    /** Where the pick is, in **view** pixels. Meaningless unless [pickPreview] is set. */
    var pickAtX: Float = 0f
        private set

    /** See [pickAtX]. */
    var pickAtY: Float = 0f
        private set

    /** The colour that was in the hand when this pick began. See the split ring. */
    var pickWas: Int = 0
        private set

    /** Bumped whenever the four fields above move, so the ring can redraw. */
    var onPickPreview: (() -> Unit)? = null

    /**
     * A colour was picked and the pen has left the glass.
     *
     * Fires **on lift and not on touch**, which is the whole of why the nib can
     * be slid to the right pixel while the ring updates. Not called at all if
     * the pick found nothing — off the page, or a document closed underneath.
     */
    var onColourPicked: ((Int) -> Unit)? = null

    /**
     * Flip [event] about the middle of this view, in place. Lr5.
     *
     * In place, and the event is flipped **back** after the router has had it:
     * the framework owns this instance and may hand it on. That is exactly what
     * a `ViewGroup` does when it dispatches into a transformed child, so it is
     * the platform's own pattern rather than a liberty taken with a shared
     * object.
     *
     * One preallocated `Matrix`, because this runs on every event at 321.75 Hz
     * and a matrix per event is an allocation per event on the one path this
     * project refuses them on. It is its own inverse, so the same matrix does
     * both directions.
     */
    private fun mirrorEvent(event: MotionEvent) {
        mirrorMatrix.setScale(-1f, 1f, width * 0.5f, 0f)
        event.transform(mirrorMatrix)
    }

    /** See [mirrorEvent]. Written and read on the UI thread only. */
    private val mirrorMatrix = Matrix()

    /**
     * Put the mirror on a document-to-view matrix, if it is on. Lr5.
     *
     * **Post**, not pre: the mirror is about the *view*, so it is applied after
     * everything `CanvasTransform` describes. Pre-concatenating it would mirror
     * the document about its own origin, which at the identity transform is the
     * same picture and is wrong at every pan — the shape of mistake
     * `Matrices.kt` has a page of prose about.
     */
    private fun mirror(m: Matrix) {
        if (mirrored) m.postScale(-1f, 1f, width * 0.5f, 0f)
    }

    /**
     * The document-to-view mapping the chrome should draw through. Lr5.
     *
     * The overlays — the ants, the guides, the transform boxes — each built
     * their own from `transform`, and every one of them would have to know
     * about the mirror. They ask for it here instead, which is the same
     * argument `Matrices.kt` makes for there being one place a
     * `CanvasTransform` becomes a `Matrix`.
     */
    /**
     * The matrix a stroke is drawn through, mirror included. Lr5.
     *
     * A fresh instance per pen-down, which is `docToViewMatrix`'s whole point
     * and its KDoc's warning: this one crosses to the render thread and nobody
     * may write it again.
     */
    private fun strokeMatrix(t: CanvasTransform): Matrix =
        docToViewMatrix(t).also { mirror(it) }

    fun fillDocToView(out: Matrix) {
        out.setDocToView(transform)
        mirror(out)
    }


    /**
     * Reads what the eye sees. One per view; see `ColourProbe`.
     *
     * **Render thread only.** `Layer.read` refuses the main thread — the sheets
     * belong to the renderer and the check is there rather than the comment —
     * so the pick is a request the UI thread posts and the next dry frame
     * answers. Found by picking a colour on the tablet, which is where a
     * threading contract that is only written down gets tested.
     */
    private val colourProbe = ColourProbe()

    /** The document pixel a pick wants read, or [NO_PICK] when none does. */
    @Volatile
    private var pickWantX = NO_PICK

    /** See [pickWantX]. */
    @Volatile
    private var pickWantY = 0

    /**
     * Ask the next dry frame for the colour at a document pixel. UI thread.
     *
     * Coalescing by overwriting: a drag asks two hundred times a second and
     * only the newest point has an answer anybody wants. The frame is asked for
     * here rather than waited on, so a pick costs one repaint and no lock on
     * the thread the pen is on.
     */
    private fun requestPick(xDoc: Int, yDoc: Int) {
        pickWantX = xDoc
        pickWantY = yDoc
        redrawDry()
    }

    /**
     * Answer a waiting pick, if there is one. **Render thread.**
     *
     * Posts even when the probe found nothing, and that is not tidiness: pen-up
     * arms the last read and waits for it, so a request that answered only on
     * success would leave the picker on with the ring stuck on the glass the
     * first time somebody lifted off the edge of the paper.
     */
    private fun servicePick() {
        val x = pickWantX
        if (x == NO_PICK) return
        val y = pickWantY
        pickWantX = NO_PICK
        val argb = colourProbe.at(document, x, y, pickFromActiveOnly) ?: 0
        post { driver.deliverPick(argb) }
    }

    /**
     * Whether the canvas is shown mirrored left-to-right. Lr5.
     *
     * **Every beginner guide there is says the same thing**: flip the drawing
     * and the crooked jaw, the lopsided eyes and the leaning pose jump out in
     * under a second, because a mirrored picture is an unfamiliar one and the
     * eye stops correcting for what it expected. Professionals flip constantly;
     * for a beginner it is the difference between *something is wrong* and
     * seeing what.
     *
     * ## It is a view mode and not a transform
     *
     * `CanvasTransform` is a scale, a rotation and a translation, and a mirror
     * has a negative determinant that none of those three can express. Adding
     * one there would change a type that is serialized positionally into every
     * trace file, compared against `Matrices` by a test whose whole job is to
     * catch exactly this sort of drift, and frozen at pen-down by two threads.
     *
     * So the mirror is applied **after** that mapping, in the one place each
     * matrix is built, and **before** it on the way in — [mirrorEvent] flips
     * the pointer at the door, so everything downstream works in a space where
     * there is no mirror at all. One flip out, one flip back.
     *
     * ## You can draw while it is on
     *
     * That is the part worth being careful about and the part that makes it
     * useful rather than a novelty: the classic use is to flip, see that the
     * jaw is crooked, and fix it *while flipped*. A view that refused the pen
     * could not do that.
     */
    @Volatile
    var mirrored: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // The dry matrix is cached against the transform it was built from
            // and this is not part of that transform, so the cache has to be
            // told separately or the first frame after a flip is the old one.
            dryMatrixSource = null
            onTransformChanged?.invoke()
            redrawDry()
        }

    /**
     * Whether the drawing is shown with its colour taken out. Lr5.
     *
     * The squint, as a button. Every beginner guide pairs it with the flip for
     * the same reason: it tunes out the hue and leaves the values, which is
     * where the mistake usually is.
     *
     * A **view** mode. No layer is touched, nothing enters the undo history,
     * and what is exported is in colour — this is a way of looking, and the
     * sheet's own [LayerStack.Entry.desaturate] is the other thing, which is
     * about the drawing rather than about the eye.
     */
    @Volatile
    var greyView: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // The paints are **not** touched here. They stamp the committed
            // stroke into the sheet as well as drawing the wet one, so a filter
            // set on them for the length of the mode would make the mode
            // permanent. `onDrawFrontBufferedLayer` sets and clears it around
            // the one callback that is only ever the wet pass.
            redrawDry()
        }

    /** What a marquee gesture draws. UI thread. */
    var marqueeShape: MarqueeShape = MarqueeShape.RECTANGLE

    /**
     * What a finished marquee does to what is already selected. UI thread.
     *
     * The barrel button overrides it to [SelectMode.SUBTRACT] for one gesture,
     * which is the same momentary shape the eraser already has on the brush:
     * hold the button, take some away, let go, and the panel still says what it
     * said. Chosen because there is no keyboard on this tablet and the hand is
     * already on the barrel.
     */
    var marqueeMode: be.thalos.artiest.doc.SelectMode = be.thalos.artiest.doc.SelectMode.NEW

    /**
     * The marquee under the pen, or null when there is none.
     *
     * Read by the chrome inside a draw lambda. Both live on the UI thread, so
     * there is no publication here and no copy: see [Marquee].
     */
    var liveMarquee: Marquee? = null
        private set

    /**
     * Bumped whenever [liveMarquee] changes shape, so the overlay can redraw.
     *
     * A callback rather than Compose state on this class, for the reason
     * [onHover] gives: the view is the render path and knows nothing about
     * Compose, and a `MutableState` field here would put a recomposition scope
     * on a class whose whole design is that the UI thread does not touch it
     * while a stroke is live.
     */
    var onMarqueeChanged: (() -> Unit)? = null

    /**
     * Called after the canvas transform moves, so chrome drawn in document
     * coordinates can follow it.
     *
     * The marching ants are stroked in view space from a document-space path,
     * so a pan or a zoom moves them and nothing else tells Compose that. The
     * guides are the same shape of thing and a louder one: a ruler that does
     * not follow a pinch is a ruler the ink is no longer on.
     *
     * Fired from [setTransform], which is the only writer of [transform] —
     * see there for what happened while nothing called this.
     */
    var onTransformChanged: (() -> Unit)? = null

    private var eraseDecided = false

    /**
     * Fix this stroke's erase mode from the first sample's buttons.
     *
     * Once per stroke, and never revisited: a button released mid-stroke must
     * not turn the second half of an erase into ink, because the two composite
     * differently and the stroke is a single composite.
     *
     * `buttonState` is stamped onto every sample in a batch from the event's
     * *current* state — `MotionEvents` explains why the framework leaves no
     * choice — so it can be backdated by up to about four samples. That is
     * harmless here: it is read once, at the start, from the batch that opened
     * the stroke.
     */
    private fun applyEraseFor(sample: PenSample) {
        if (eraseDecided) return
        eraseDecided = true
        val barrel = (sample.buttonState and BARREL_BUTTONS) != 0
        // Re-chosen only when the guess at pen-down was wrong, which is only
        // ever the barrel: the brush in [ink] cannot change between pen-down
        // and here, and `barrelHeld` is a field the same events update a moment
        // later. No dab has been emitted yet -- this runs before the sample
        // loop -- so the builder can simply be begun again.
        if (barrel != assumedBarrel) {
            chooseBrush(barrel)
            driver.rebegin()
        }
    }

    /**
     * Copy [ink] or [rubber] into [pen] for the stroke that is starting.
     *
     * The one place [ink] or [rubber] becomes the brush the engine draws with.
     * *Which* of them, and whether this stroke takes ink away, is [PenChoice]'s
     * rule and is stated there — it is the part worth a test, and everything
     * here is the part that needs a render thread.
     */
    private fun chooseBrush(barrel: Boolean) {
        assumedBarrel = barrel
        val choice = PenChoice.of(ink, rubber, barrel)
        adoptBrush(choice.from, pen)
        pen.erase = choice.erase
        borrowedRubber = choice.borrowed
    }

    /** What [chooseBrush] was last told, so [applyEraseFor] can tell if it was wrong. */
    private var assumedBarrel = false

    /**
     * The grain shader, or null when the brush has none.
     *
     * Anchored to the document, not to the scratch buffer — see
     * [GrainTexture.shaderFor]. That is what makes a stroke keep the texture it
     * was drawn with when the pen lifts.
     */
    private fun grainShader() = grain.shaderFor(pen.grain)

    /**
     * The alpha the scratch is put down at: the brush's opacity, or **1 for a
     * borrowed rubber**.
     *
     * ## Why a borrowed one is forced to 1
     *
     * A drawing brush turned into an eraser is not a pale brush. Everything
     * that makes graphite look like graphite — a 0.90 ceiling, a flow that
     * starts at 0.02, a grain mask that skips the pits — is a reason for the
     * pencil to leave *less* ink, and inheriting all three made a
     * full-pressure wipe remove roughly a quarter of what was under it. That is
     * the "eraser is too soft" report, and it is arithmetic rather than taste.
     *
     * ## Why a real eraser is not
     *
     * Because its translucency is *its own*, and half of it is the tool. The
     * soft eraser's whole reason to exist is that one sweep fades a passage and
     * two fade it further — `BrushPreset.SOFT_ERASER` — and forcing this to 1
     * would delete the difference between the two erasers, leaving a pair of
     * tools that differ only in rim softness. See [borrowedRubber], which is
     * the one bit that tells the two cases apart.
     */
    private fun compositeAlpha(): Float = if (borrowedRubber) 1f else pen.opacity

    /**
     * The grain, or none while erasing — *any* erasing, borrowed or not.
     *
     * Unlike [compositeAlpha] this stays absolute. A grain mask is paper tooth,
     * which is a thing ink catches on; an eraser that skipped the pits would
     * leave a speckle of the old stroke behind and read as a failure to rub
     * out. Neither eraser preset sets grain, so this only ever matters for a
     * borrowed rubber and for a saved eraser somebody tuned by hand.
     */
    private fun compositeGrain(): Shader? = if (pen.erase) null else grainShader()

    /**
     * Push the brush's per-dab settings onto the rasterizer, before every draw.
     *
     * `hardness` had never been pushed at all — the field's own KDoc claimed
     * this call site existed and it did not — so the pencil's soft rim was a
     * number in a preset that no pixel ever saw. `solid` is the erase side of
     * [compositeAlpha]: per-dab flow has to go as well as the composite alpha,
     * or the eraser is still scaled by however hard the pencil was leaning.
     *
     * Called immediately before each rasterizer entry rather than at stroke
     * start, because the erase decision and the brush can both change between
     * one and the next, and two assignments are cheaper than the bug.
     */
    private fun armRasterizer() {
        rasterizer.hardness = pen.hardness
        rasterizer.solid = borrowedRubber
        // Resolved here and not per dab: this runs once a stroke and the dab
        // loop runs two hundred times an event. A name nothing answers to
        // leaves null, and the brush draws with the procedural nib — see
        // `Brush.tip`.
        rasterizer.tip = be.thalos.artiest.ink.Tips.find(pen.tip)
    }

    /**
     * Whether this stroke is erasing with a brush that is not an eraser.
     *
     * True only for the barrel button held over a drawing brush, which is the
     * case [compositeAlpha] forces to full strength. False for the two eraser
     * presets and for any brush saved from one, whose flow and opacity are
     * theirs to keep.
     *
     * Written once per stroke, by [chooseBrush], on the render thread.
     */
    private var borrowedRubber = false

    /** The scratch's composite paint for the wet pass. See [drawWetIndirect]. */
    private val wetPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    /** Rebuilt from [transform] when it changes; never published. */
    private val dryMatrix = Matrix()
    private var dryMatrixSource: CanvasTransform? = null

    /**
     * `isFilterBitmap = true`, `isAntiAlias = false`, and both halves matter.
     *
     * **A null Paint means point sampling.** Passing one would
     * nearest-neighbour-resample the whole document at every zoom that is not
     * exactly 1:1, and fine ink crawls when it does. Antialiasing is off
     * because this is a rectangular bitmap blit with no edges to smooth, and
     * `:spike` measured a half-pixel translate through the bilinear filter
     * making one A/B arm look softer than another for no reason at all.
     */
    /**
     * What the paper sits on. View state, not document state: it is never
     * exported, never composited into the layer, and a document has no opinion
     * about the colour of the desk it is lying on.
     */
    var deskColorArgb: Int = DEFAULT_DESK_COLOR

    /** Lr5's grey view, as the paint its offscreen layer is composited with. */
    private val greyPaint = Paint().apply { colorFilter = GREY }

    private val blitPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<DabBatch> {

        /**
         * Wet ink, once per submitted batch.
         *
         * The library invokes this **N times on one `RecordingCanvas` with no
         * save/restore of its own between invocations**, which is why the
         * matrix discipline lives in [DabRasterizer.drawWet] and not here.
         *
         * [DabBatchPool.markDrawn] is the completion signal that makes the ring
         * safe: by the time this returns, the batch has been read and its slot
         * can be handed out again.
         */
        override fun onDrawFrontBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            param: DabBatch,
        ) {
            val m = frozenDocToView
            if (m == null) return
            // Lr5, and **only here**. Both of these paints stamp the committed
            // stroke into the sheet as well as drawing the wet one, so a filter
            // left on them turns the grey *view* into a grey drawing -- which
            // is exactly what it did on the tablet: a red stroke drawn while
            // grey was on was still grey when grey was turned off. Set for this
            // callback, cleared at the end of it, and never seen by the commit
            // or by the dry frame, which has its own layer.
            val grey = if (greyView) GREY else null
            rasterizer.colorFilter = grey
            scratch.colorFilter = grey
            try {
                if (!indirectNeeded()) {
                    armRasterizer()
                    rasterizer.drawWet(canvas, m, param)
                    return
                }
                drawWetIndirect(canvas, m, param)
                batches.markDrawn(param.sequence)
            } finally {
                rasterizer.colorFilter = null
                scratch.colorFilter = null
            }
        }

        /**
         * The dry scene, reproduced from app-held state.
         *
         * `params` is ignored entirely, and that is the design rather than an
         * oversight: the library hands back every param since the last commit,
         * including ones already drawn into the front buffer, so consuming them
         * here would redraw a stroke twice at the one moment both layers are
         * visible. The layer bitmap already holds every committed stroke, and
         * the pending one arrives through [pendingStroke].
         *
         * Never posts to and never blocks on the main thread. The framework's
         * synchronous `surfaceRedrawNeeded` path awaits this callback on an
         * untimed latch, so anything here that waited on the UI thread would
         * deadlock the moment the UI thread was itself waiting on the surface.
         */
        /**
         * The library's own completion signal for a multi-buffered render, and
         * the thing that makes "at most one in flight" a fact rather than a
         * hope.
         *
         * Without it, coalescing to one render per `Choreographer` frame still
         * lets a slow render be followed immediately by another: the frame
         * clock does not know what the render thread is doing. With it,
         * `GestureController` skips a frame rather than queueing behind one.
         */
        override fun onMultiBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            multiBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            dryRenderInFlight = false
            recordRoundTrip()
        }

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<DabBatch>,
        ) {
            // Before the clock starts, on purpose: a measurement that ran
            // inside the timed body would show up as one two-second dry frame
            // in the very percentiles it exists to protect.
            renderThreadTask?.let {
                renderThreadTask = null
                it()
            }
            drainRebuild()
            val dryStart = System.nanoTime()
            try {
                drawDryFrame(canvas)
            } finally {
                recordDryFrame(System.nanoTime() - dryStart)
            }
        }
    }

    /**
     * One dry frame: the desk, the paper, every visible sheet, and whatever the
     * commit queue had waiting.
     *
     * Split out of the callback so [recordDryFrame] can time all of it without
     * a `return` inside the body escaping the clock. See [dryNanos].
     */
    private fun drawDryFrame(canvas: Canvas) {
        // Asked of the canvas rather than inferred from the library's field
        // names. See [dryHardware].
        dryHardware = canvas.isHardwareAccelerated
        document.drainCommits(commitSink)
        batches.markDrawn(commitWatermark)

        // The desk, then the paper on it. Through W14 this line was
        // `drawColor(document.paperColor)` over the whole surface, which
        // draws a white page on a white background: the paper is exactly
        // where it always was and there is no way to see where it ends,
        // so a stroke that runs off the sheet simply stops for no visible
        // reason and pan, zoom and fit all move something invisible. The
        // paper is still not painted *into* the layer — that invariant is
        // untouched — it is painted into the frame, in the document's own
        // coordinates, which is where the plan always said it belonged.
        canvas.drawColor(deskColorArgb)

        val t = transform
        if (t !== dryMatrixSource) {
            dryMatrix.setDocToView(t)
            // Lr5, after the mapping and not inside it. The cache above is keyed
            // on the transform alone, so `mirrored`'s setter clears it.
            mirror(dryMatrix)
            dryMatrixSource = t
        }
        val save = canvas.save()
        canvas.concat(dryMatrix)
        // The paper is the compositor's now: it is the bottom of the stack, not
        // a backdrop, and the export has to agree with this frame about that.
        //
        // A stroke still in flight lives on the scratch, not in the layer,
        // so a redraw that ignored it would blank the wet ink for a frame
        // every time the transform changed. Pinching mid-stroke is not a
        // gesture anyone makes on purpose, but the same redraw is what
        // `redrawDry` schedules after a zoom button.
        //
        // Erasing has to happen inside an offscreen layer, or DST_OUT cuts
        // through the paper as well and the wet stroke reads as a window
        // onto the desk. That is also why the layer blit is inside the
        // branch: it has to be the thing being subtracted from.
        val compositeStart = System.nanoTime()
        // Lr5. The whole drawing through one filter, which is a full-page
        // offscreen and is why it only happens while the mode is on. The wet
        // stroke is filtered separately, on its own paints, because it is on
        // the front buffer and never passes through here.
        val grey = if (greyView) canvas.saveLayer(null, greyPaint) else -1
        compositeStack(
            canvas, 0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat(),
        )
        if (grey >= 0) canvas.restoreToCount(grey)
        recordComposite(System.nanoTime() - compositeStart)
        canvas.restoreToCount(save)
        // After the commits, for the thumbnail's reason one paragraph down: a
        // colour read before the stroke was stamped is the colour of the
        // drawing as it was a moment ago, and here that would be the colour the
        // user is about to be handed.
        servicePick()

        // One stale thumbnail per frame, and only while the panel is open.
        // Here rather than in the sink because it has to happen after the
        // commits have landed -- a thumbnail built before the stroke was
        // stamped is a picture of the drawing as it was a moment ago, which
        // is exactly the complaint a thumbnail is supposed to answer.
        // One more frame if there is another stale thumbnail behind it.
        // `post` and not a direct call: `redrawDry` asks the library for a
        // render, and asking for one from inside the render callback is a
        // re-entrant call into the renderer. This is a UI-thread hop that
        // happens at most eight times, only while the panel is open.
        if (document.layers.refreshThumbnails()) post { redrawDry() }
    }

    /**
     * Fold one frame into the statistics, resetting them if the stack changed
     * shape underneath. **Render thread.** See [dryNanos].
     */
    private fun recordDryFrame(elapsedNanos: Long) {
        val sheets = document.layers.size
        if (sheets != dryStatsFor) {
            dryStatsFor = sheets
            dryNanos = 0L
            dryCalls = 0L
            dryMaxNanos = 0L
        }
        dryNanos += elapsedNanos
        dryCalls++
        dryLastNanos = elapsedNanos
        if (elapsedNanos > dryMaxNanos) dryMaxNanos = elapsedNanos
    }

    /** See [compositeNanos]. Reset alongside [recordDryFrame]'s statistics. */
    private fun recordComposite(elapsedNanos: Long) {
        if (document.layers.size != dryStatsFor) {
            compositeNanos = 0L
            compositeCalls = 0L
            compositeMaxNanos = 0L
        }
        compositeNanos += elapsedNanos
        compositeCalls++
        compositeLastNanos = elapsedNanos
        if (elapsedNanos > compositeMaxNanos) compositeMaxNanos = elapsedNanos
    }

    /**
     * The app's own surface callback, and the reason [redrawDry] exists.
     *
     * **Registered after the renderer is constructed, and the order is
     * load-bearing.** `SurfaceHolder` invokes callbacks in registration order,
     * the renderer registers its own in its constructor, and its
     * `surfaceChanged` does nothing but rebuild its SurfaceControls. Register
     * first and this would ask for a redraw through a renderer whose buffers
     * have not been rebuilt yet; register second and the redraw lands on a
     * renderer that is ready.
     */
    private val holderCallback = object : SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceAlive = true
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceAlive = true
            if (fitOnResize) {
                requestTransform(
                    CanvasTransform.fitTo(width, height, document.widthPx, document.heightPx),
                )
            }
            redrawDry()
        }

        /**
         * The surface is gone and the library has already torn its buffers down
         * from its own callback. Nothing may be submitted after this: the
         * renderer's thread would reach a destroyed surface, and
         * `lockHardwareCanvas` on one throws.
         *
         * An open stroke does not survive it either. The pen may well still be
         * down — backgrounding mid-stroke is the ordinary way to get here — but
         * its wet ink is gone with the buffer, so the builder is dropped rather
         * than committed into a layer at a transform that no longer applies.
         */
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAlive = false
            // Nothing will complete a render against a surface that is gone,
            // and a flag stuck true would make GestureController skip every
            // frame it is ever asked for.
            dryRenderInFlight = false
            router.abandon()
        }
    }

    /** Pan, zoom and rotate. See [GestureController]. */
    val gestures = GestureController(this)

    private val driver = StrokeDriver()

    private val router = InputRouter(driver)

    // Deliberately no setBackgroundColor. See the class header.

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = CanvasFrontBufferedRenderer(this, callback)
        // Built whether or not prediction is on. Construction is once per
        // attach and answers a question the plan left open — which
        // implementation this device resolves to — and everything that costs
        // anything per event stays behind `predictionEnabled`.
        predictor = Predictor(this)
        holder.addCallback(holderCallback)
    }

    override fun onDetachedFromWindow() {
        predictor = null
        router.abandon()
        holder.removeCallback(holderCallback)
        release()
        super.onDetachedFromWindow()
    }

    // --- InkSurface --------------------------------------------------------

    override fun beginStroke(docToView: Matrix, colorArgb: Int, antiAlias: Boolean) {
        strokeColorArgb = colorArgb
        strokeAntiAlias = antiAlias
        frozenDocToView = docToView
    }

    override fun acquireBatch(): DabBatch {
        val batch = batches.acquire()
        batch.colorArgb = strokeColorArgb
        batch.antiAlias = strokeAntiAlias
        return batch
    }

    override fun drawWet(batch: DabBatch) {
        if (batch.size == 0) {
            // Never submitted, so the front-buffer callback will never report
            // it drawn, and the pool would count it in flight forever.
            batches.markDrawn(batch.sequence)
            return
        }
        val r = renderer
        if (r == null || !surfaceAlive) {
            batches.markDrawn(batch.sequence)
            return
        }
        // Timed separately from the rest of the event: this call is where the
        // library can make the UI thread wait, and app work and compositor
        // back-pressure have nothing in common but the clock. See
        // [InputStats.submitMs].
        val t = System.nanoTime()
        r.renderFrontBufferedLayer(batch)
        submitNanos += System.nanoTime() - t
    }

    /**
     * Three steps, and the order is the contract.
     *
     * The stroke is published *before* `commit()` so the render thread cannot
     * reach [onDrawMultiBufferedLayer] with an empty [pendingStroke] and blit a
     * layer that is missing the stroke just finished — one frame of the ink
     * disappearing between the front buffer being released and the layer
     * catching up.
     *
     * `commit()` rather than `renderMultiBufferedLayer`: only `commit()`
     * increments the counter that defers concurrent front-buffer renders until
     * the transaction lands, and it is the only call that releases the front
     * buffer's accumulated pixels.
     */
    /**
     * Two steps, and the order is the contract.
     *
     * The stroke is queued *before* `commit()` so the render thread cannot
     * reach [onDrawMultiBufferedLayer] with an empty queue and blit a layer
     * that is missing the stroke just finished — one frame of the ink
     * disappearing between the front buffer being released and the layer
     * catching up.
     *
     * With no surface the stroke stays queued rather than being dropped. It was
     * dropped through W8, which lost it while `Document` had already counted
     * it; now the queue belongs to the document, so a stroke finished as the
     * app goes to the background is stamped by the first render after it comes
     * back.
     *
     * `commit()` rather than `renderMultiBufferedLayer`: only `commit()`
     * increments the counter that defers concurrent front-buffer renders until
     * the transaction lands, and it is the only call that releases the front
     * buffer's accumulated pixels.
     */
    override fun commitStroke(stroke: Stroke, record: PendingStroke?) {
        commitWatermark = batches.issuedCount
        document.commitStroke(stroke, record)
        val r = renderer
        if (r == null || !surfaceAlive) return
        r.commit()
    }

    /**
     * Throw the wet stroke away, and — the half that was missing — give the
     * batches it was drawn from somewhere to go.
     *
     * `cancel()` hides the front buffer and clears the library's param queue.
     * It invokes **neither** draw callback (bytecode: `ParamQueue.clear`,
     * `cancelPending`, a runnable that sets the front-buffer SurfaceControl
     * invisible, a buffer clear, and no dispatch to either `onDraw*Layer`), and
     * [commitWatermark] is only ever applied inside [onDrawMultiBufferedLayer].
     * So through W12 a cancel set a watermark that nothing read.
     *
     * The consequence is smaller than it first looks and worth stating
     * exactly. `markDrawn` is a watermark, so the *next* batch that is drawn
     * releases the abandoned ones with it — the leak is one cancel deep and it
     * self-heals. What it costs before it does: the stroke after a cancel
     * begins with a ring short by that many slots and can spill on its own
     * first events, and `peakInFlight` reads high from the cancel onwards,
     * which is the number W9 sized `DabBatchPool.DEFAULT_SLOTS` with. A cancel
     * that is the last thing to happen leaves batches in flight for good.
     *
     * The redraw is the fix and it is not a repaint: the dry layer already
     * holds the right pixels, because a cancelled stroke was never committed to
     * it. It is there to make the render thread visit
     * [onDrawMultiBufferedLayer] once, which is where the watermark is applied
     * — the same handoff, on the same thread, in the same order as a commit.
     * One render per cancel, and a cancel happens at most once per stroke.
     *
     * With no surface there is no render thread to hand off to, so the pool is
     * released directly. See [DabBatchPool.releaseAll] for why that is safe
     * there and not in general.
     */
    override fun cancelStroke() {
        strokesCanceled++
        val r = renderer
        if (r == null || !surfaceAlive) {
            batches.releaseAll()
            return
        }
        commitWatermark = batches.issuedCount
        r.cancel()
        redrawDry()
    }

    /**
     * Strokes discarded rather than committed, since the view was created.
     *
     * The count is here and the *causes* are in
     * `InputRouter.rejections`: this side knows a stroke's ink was thrown
     * away, and only the router knows whether that was ACTION_CANCEL, the
     * framework's own cancel flag, a lost pointer or a lifecycle abandon. The
     * two are checked against each other by [rejections] in the readout, which
     * is how a cancel that reaches the router but not the ink — or the reverse
     * — becomes visible instead of silent.
     */
    var strokesCanceled: Long = 0L
        private set

    /** The router's rejection tally. See [RejectionCounters]. */
    val rejections: RejectionCounters get() = router.rejections

    /**
     * True from a [redrawDry] until the library reports that render complete.
     *
     * Written on both threads and read by [GestureController], which is why it
     * is volatile and why nothing branches on it inside the render callback.
     */
    @Volatile
    var dryRenderInFlight: Boolean = false
        private set

    override fun redrawDry() {
        val r = renderer ?: return
        if (!surfaceAlive) return
        dryRenderInFlight = true
        // Before the call and not inside it: what this clock measures is the
        // wait the *caller* sees. See [roundTripNanos].
        roundTripStartNanos = System.nanoTime()
        r.renderMultiBufferedLayer(emptyList())
    }

    /**
     * Drop the render thread and its buffers.
     *
     * Queued commits are **not** dropped: they belong to the document, which
     * outlives this view. `Document.close` is where they go for good, along
     * with the pixels they were going to be drawn into.
     *
     * The case that actually matters is a rung below this one. Rotation does
     * not reach here at all — the manifest handles `orientation|screenSize`, so
     * the view stays attached and only the *surface* is rebuilt — and what
     * happens then is that [commitStroke] finds `surfaceAlive` false and leaves
     * the stroke queued for the first render against the new surface. Measured:
     * a pen-up followed immediately by HOME, then resume, comes back with the
     * ink. Through W8 that stroke was dropped, after `Document` had counted it.
     */
    override fun release() {
        renderer?.release(true)
        renderer = null
        frozenDocToView = null
        // The scratch can be several megabytes at RGBA_F16 and is native
        // memory the GC does not account for; a view torn down mid-stroke
        // would otherwise hold it until the next allocation happens to reuse
        // the field.
        scratch.release()
        grain.release()
    }

    /**
     * Blank the document and redraw.
     *
     * The blank goes through the same queue the strokes do, so it lands after
     * everything already committed and cannot race a stroke finished a
     * millisecond earlier. A separate flag beside a separate stroke slot — what
     * W8 shipped — cannot express that ordering at all: whichever branch the
     * render thread reads first wins.
     */
    fun clear() {
        router.abandon()
        document.requestClear()
        redrawDry()
    }

    /**
     * Step the drawing back one edit, and redraw.
     *
     * Same three lines as [clear] and for the same three reasons. The open
     * stroke is abandoned because an undo landing mid-stroke would restore
     * pixels under ink that is still being laid, and the wet stroke would
     * commit on top of the restored region at pen-up — the undo would appear
     * to work and then be silently reversed. The request is queued so it lands
     * behind everything already committed. The redraw is the one the queue
     * cannot ask for itself.
     */
    fun undo() {
        router.abandon()
        document.requestUndo()
        redrawDry()
    }

    /** Step forward again. See [undo]. */
    fun redo() {
        router.abandon()
        document.requestRedo()
        redrawDry()
    }

    /**
     * Called after a two-finger tap has undone something, so the chrome can
     * catch up. See [undoFromTap].
     *
     * A callback for [onTransformChanged]'s reason: this class is the render
     * path and knows nothing about Compose. The undo *button* bumps the same
     * counter in the activity, and an undo that arrived from the glass has to
     * look exactly like one that arrived from the bar.
     */
    var onCanvasUndo: (() -> Unit)? = null

    /**
     * Undo, because two fingers tapped the paper.
     *
     * **Not [undo]**, and the missing line is `router.abandon()`. There is no
     * stroke to abandon by construction — a gesture and a pen stroke cannot
     * overlap, that is `StrokeExclusivity`'s whole job — and calling it from
     * here would be re-entrant: this runs inside `onTouchEvent`, one frame
     * deep in the router's own dispatch. Abandoning from inside a dispatch
     * would send a second decision through the sink while the first is still
     * being handled.
     *
     * The transform goes back to where the gesture found it. A tap is two
     * fingers landing and leaving, and the solver has been folding their
     * wobble into a pan the whole time — a few pixels each tap, always in the
     * direction the hand happens to roll. Undo three strokes and the drawing
     * has walked across the screen. Putting it back is one assignment and it is
     * what makes the gesture feel like a button rather than a nudge.
     */
    private fun undoFromTap() {
        applyTransform(gestures.transformAtBegin)
        document.requestUndo()
        redrawDry()
        onCanvasUndo?.invoke()
    }

    /**
     * Change what is selected, and redraw.
     *
     * The same three lines as [clear] and [undo], and the open stroke is
     * abandoned for a reason of this feature's own. The wet pass masks the
     * scratch buffer with the stencil as it stands, and the commit masks it
     * again at pen-up; a selection that landed between those two would make the
     * ink visibly change shape as the pen lifted, which is precisely the defect
     * the masked-buffer design exists to prevent. It is not a gesture anybody
     * makes on purpose — the marquee is not the brush — but a Select All
     * pressed with the other hand mid-stroke is reachable, and abandoning is
     * cheaper than explaining.
     */
    fun select(op: be.thalos.artiest.doc.SelectOp) {
        router.abandon()
        document.requestSelect(op)
        redrawDry()
    }

    /**
     * Lift, move, drop or cancel the floating pixels, and redraw.
     *
     * The open stroke is abandoned for lifts and drops, which write pixels, and
     * for the same reason [undo] abandons one. A [FloatOp.Move] does not: it
     * arrives eight times a second while the transform box is being dragged,
     * and there is no stroke open then anyway — the box owns the pen.
     */
    fun float(op: FloatOp) {
        if (op !is FloatOp.Move) router.abandon()
        document.requestFloat(op)
        redrawDry()
    }

    // --- input -------------------------------------------------------------

    /**
     * Drop the open stroke without committing it. For the host activity's
     * `onPause`: the framework does not reliably send an ACTION_CANCEL when the
     * activity leaves the foreground, so the router would otherwise hold a
     * pointer id that never lifts.
     */
    fun abandonStroke() {
        strokeEpoch++
        router.abandon()
    }

    /**
     * The measured window, and it is drawn where the plan draws it: `from
     * onTouchEvent to renderFrontBufferedLayer`.
     *
     * The clock stops before the heap is read, so the two `Runtime` calls that
     * take the drag watermark are not counted against the 0.31 ms budget they
     * are there to police. `MotionEvent` expansion, routing, the transform, the
     * stabilizer, the spline, the dab emit and the front-buffer submission are
     * all inside it.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val samplesBefore = driver.totalSamples
        val dabsBefore = driver.totalDabs
        val batchesBefore = batches.issuedCount
        val heapBefore = heapUsed()
        submitNanos = 0L
        val t0 = System.nanoTime()
        // Read from the same `nanoTime` that starts the measured window, so the
        // two halves of the app-visible latency meet exactly at this line with
        // no gap and no overlap between them.
        val ageNs = event.eventAgeNanos(t0)
        // Inside the measured window on purpose. Prediction is not free, and
        // when it is on the readout should say what it costs rather than
        // reporting the cost of the path without it.
        if (predictionEnabled) predictor?.record(event)
        // Lr5. The whole of the mirror's input half, and it is here rather than
        // in six places downstream because every one of those -- the router,
        // the gesture solver, the driver, the hover, the marquee -- would
        // otherwise need to know about a mode none of them is about. Flipped
        // in, flipped back out, which is exactly what a `ViewGroup` does when
        // it dispatches into a scaled child.
        if (mirrored) mirrorEvent(event)
        val handled = router.onTouchEvent(event, this)
        if (mirrored) mirrorEvent(event)
        val elapsed = System.nanoTime() - t0
        driver.addDragBytes(heapUsed() - heapBefore)
        stats.recordEvent(
            elapsed,
            submitNanos,
            ageNs,
            (driver.totalSamples - samplesBefore).toInt(),
            (batches.issuedCount - batchesBefore).toInt(),
            (driver.totalDabs - dabsBefore).toInt(),
        )
        // After the measured window closes, on purpose: this is chrome, and
        // the number above is what the ink path costs.
        notePen(event)
        return handled
    }

    /**
     * Move the hover ring to where the pen actually is, and say whether a
     * barrel button is down.
     *
     * **Called from the touch path as well as the hover path**, which is the
     * fix for a defect found on the tablet: `onHoverEvent` stops firing the
     * moment the pen touches the glass, so the ring stayed where the pen had
     * last hovered and sat there while a stroke was drawn somewhere else. A
     * ring that is right until you use it is worse than no ring, because it is
     * a measurement of the wrong place.
     *
     * Only a stylus moves it. A finger dragging the canvas is a pan, and a ring
     * following a pan would be showing what the eraser covers at a place the
     * eraser is not.
     */
    private fun notePen(event: MotionEvent) {
        val barrel = (event.buttonState and BARREL_BUTTONS) != 0
        if (barrel != barrelHeld) {
            barrelHeld = barrel
            onBarrel?.invoke(barrel)
        }
        val index = stylusIndex(event)
        if (index < 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            -> onHover?.invoke(true, event.getX(index), event.getY(index))
            // Nothing on up: the pen lifts back into hover range and the very
            // next hover sample says where it is. Clearing here would blink
            // the ring off and on again at the end of every stroke.
            else -> {}
        }
    }

    /** The first stylus pointer in [event], or -1. See [notePen]. */
    private fun stylusIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            val tool = event.getToolType(i)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                return i
            }
        }
        return -1
    }

    /**
     * Whether a barrel button is down **right now**.
     *
     * Distinct from `pen.erase`, which is the erase decision for the stroke in
     * progress and is deliberately latched at the first sample — see
     * [applyEraseFor]. This one tracks the button itself, so the chrome can
     * light the eraser while the button is held and unlight it when it is
     * released, including while the pen is only hovering.
     */
    @Volatile
    var barrelHeld: Boolean = false
        private set

    /**
     * Called when [barrelHeld] changes, so the toolbar can show which tool the
     * pen would use if it came down now.
     *
     * A callback for the reason [onHover] is one: this class is the render path
     * and holds no Compose state.
     */
    var onBarrel: ((Boolean) -> Unit)? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // ACTION_BUTTON_PRESS and ACTION_BUTTON_RELEASE arrive here while the
        // pen is out of contact, and they are the only samples that carry a
        // button change with no movement -- press the barrel while holding the
        // pen still and there is no hover sample to notice it in.
        notePen(event)
        return super.onGenericMotionEvent(event)
    }

    /**
     * Where the pen is hovering, and whether it is in range at all.
     *
     * A callback and not Compose state on this class, deliberately: the view is
     * the render path and knows nothing about Compose, and a `MutableState`
     * field here would put a recomposition scope on a class whose whole design
     * is that the UI thread does not touch it while a stroke is live. The
     * chrome installs a listener, keeps the state on its own side, and reads it
     * in a draw lambda — so a hover at 200 Hz redraws a ring and recomposes
     * nothing.
     *
     * View pixels, because that is what the ring is drawn in. Converting to
     * document space here and back in the overlay would be two conversions to
     * arrive at the number the event already carried.
     */
    var onHover: ((inRange: Boolean, x: Float, y: Float) -> Unit)? = null

    override fun onHoverEvent(event: MotionEvent): Boolean {
        router.onHoverEvent(event)
        // Exit is the one action whose coordinates are meaningless -- the pen
        // has left, and the last position it reports is where it left from,
        // which would leave a ring stranded at the edge of the screen.
        val inRange = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        onHover?.invoke(inRange, event.x, event.y)
        notePen(event)
        // The framework's own hover handling still runs: the router took a
        // presence reading, it did not consume the event.
        return super.onHoverEvent(event)
    }

    /**
     * The widest mark the brush in the hand can make, in **document** pixels.
     *
     * The full-drive diameter and not the diameter at the pressure of the
     * moment, because the ring is a reach indicator: the question it answers is
     * "if I press, what does this cover", and an outline that shrank as the pen
     * approached the page would answer a question nobody asked. For the eraser
     * that is exactly the rubbed-out area at a full press.
     */
    val cursorDiameterDocPx: Float
        get() = when {
            // Mid-stroke the live brush is the truth; before one it is stale,
            // because [pen] is only chosen at pen-down. Hovering is exactly the
            // "before one" case, and a ring that showed the last stroke's width
            // would be a ring that lies about the tool you have just picked.
            strokeOpen -> if (pen.erase) pen.eraseSizeMax else pen.sizeMax
            // An eraser *brush* is a brush, so its ring is its own `sizeMax`
            // like anything else's. `eraseSizeMax` is only the barrel's answer
            // — the width a drawing brush momentarily rubs out at — and reading
            // it here would show a rubber-sized ring for a pencil-sized nib.
            ink.erase -> ink.sizeMax
            erasingNow -> (rubber ?: ink).eraseSizeMax
            else -> ink.sizeMax
        }

    /**
     * Whether the pen would take ink out if it came down now — or is doing so,
     * if it is already down.
     *
     * Two answers and not one, because the barrel button is a momentary
     * override and the stroke's own decision is latched: mid-stroke the honest
     * answer is what [applyEraseFor] decided, and a barrel released halfway
     * through must not shrink the ring to the pencil's width while the eraser
     * is still erasing.
     */
    val erasingNow: Boolean
        get() = if (strokeOpen) pen.erase else ink.erase || barrelHeld

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) router.abandon()
    }

    /**
     * Turns routed samples into wet ink and, at pen-up, into a `Stroke`.
     *
     * It holds one [StrokeBuilder] for the life of the view rather than one per
     * stroke: the builder's dab buffer grows to whatever the longest stroke
     * needed and is then reused, which is the difference between one array per
     * stroke and one per session.
     *
     * [emitted] is how incremental drawing works without the engine knowing
     * about batches. `StrokeBuilder` appends dabs to its own buffer and never
     * removes any, so everything from [emitted] to `dabCount` is exactly what
     * this event produced.
     */
    private inner class StrokeDriver : InkInputSink {

        private val builder = StrokeBuilder(pen)
        private var emitted = 0

        /**
         * Start this stroke over with the brush that has just been chosen.
         *
         * Called only from [applyEraseFor], and only when the barrel turned out
         * to disagree with what pen-down assumed. Safe because no dab exists
         * yet: `StrokeBuilder.begin` resets a count rather than a buffer, and
         * the front buffer has nothing on it either.
         */
        fun rebegin() {
            if (tailSmoothing.strength != pen.stabilization) {
                tailSmoothing = Stabilizer(pen.stabilization)
            }
            // The barrel turned out to disagree with what pen-down assumed, so
            // the guide decision is made again from the brush that actually
            // won. See `onStrokeStart`.
            val guides = document.guides
            builder.snap = if (pen.erase) null else guides.snap
            strokeGuideText = if (pen.erase) null else guides.snapText
            builder.begin(inkColorArgb)
            beginStroke(strokeMatrix(frozen), inkColorArgb, pen.antiAlias)
        }

        private var seen = 0
        private var strokeTiltMaxRad = 0f
        private var strokePressureMin = Float.MAX_VALUE
        private var strokePressureMax = 0f

        /**
         * Samples since the view was created, so [onTouchEvent] can attribute
         * an event's samples to it without racing [seen], which resets at
         * pen-down and would go backwards across an ACTION_DOWN.
         */
        var totalSamples: Long = 0L
            private set

        /** Dabs since the view was created. See [totalSamples]. */
        var totalDabs: Long = 0L
            private set

        private var dragBytes = 0L
        private var gcAtBegin = 0L
        private var strokeOpen = false

        /**
         * Accumulate one event's allocation, from [onTouchEvent], which has
         * already stopped its clock.
         *
         * Summed per event rather than taken as one watermark across the drag,
         * so nothing the caller allocated *between* events is charged to the
         * input path. See [InputStats.dragBytes].
         */
        fun addDragBytes(bytes: Long) {
            if (strokeOpen) dragBytes += bytes
        }

        /**
         * The transform this stroke is being drawn at, captured at pen-down.
         *
         * **The same snapshot converts the input and renders the output**, and
         * that is the point rather than a convenience. If the incoming samples
         * were mapped through the live transform while the front buffer
         * concatenated the frozen one, a zoom mid-stroke would put the ink
         * somewhere neither transform describes — and the already-drawn half
         * cannot be re-transformed, because the renderer never re-records
         * pixels it has drawn. One snapshot, both directions.
         */
        private var frozen: CanvasTransform = CanvasTransform.IDENTITY

        /** Scratch for [CanvasTransform.viewToDoc]. Reused; never escapes. */
        private val docPoint = FloatArray(2)

        /** The pointer this stroke belongs to, for asking the predictor. */
        private var strokePointerId = -1

        /** Stroke start, for the tail's elapsed time. See `Brush.sizeFor`. */
        private var downTimeNanos = 0L

        /**
         * The stroke's input, kept in case the sheet it lands on wants it.
         *
         * One instance for the life of the view, reset at pen-down: at 321.75
         * Hz a per-stroke allocation here would be an allocation in the window
         * `StrokeStats` measures, and the buffer a long stroke grew is the
         * buffer every later stroke uses.
         *
         * Named `sampleLog` and not `samples`, because `onStrokeSamples` takes
         * a parameter of that name and the shadowing compiles into a call that
         * is looking at the wrong thing.
         *
         * **Filled unconditionally, even when the sheet is an ordinary raster
         * one.** The UI thread does not know which sheet the stroke will land
         * on — the stack belongs to the render thread — so the choice is
         * between six float writes a sample that are sometimes wasted and a
         * boolean mirrored across a thread boundary that is sometimes stale.
         * The first is cheap and cannot be wrong.
         */
        private val sampleLog = SampleLog()

        /** `BrushCodec.encode(pen)` as of pen-down. See [samples]. */
        private var strokeBrushText: String = ""

        /**
         * The guides that were live at pen-down, written down, or null.
         *
         * Read in the same statement the snap itself is, and kept for the same
         * reason `strokeBrushText` is kept: the record names what the stroke was
         * drawn with, and the hand can move a ruler before the next one.
         */
        private var strokeGuideText: String? = null

        /** The last real sample in document space, for the lead measurement. */
        private var lastRealDocX = 0f
        private var lastRealDocY = 0f

        private val gate = PredictionGate()

        /** Two fingers down and straight up takes the last stroke back. */
        private val tap = TwoFingerTap()

        /** When the current gesture opened, for [tap]'s duration. */
        private var gestureBeganNanos = 0L

        /** Whether [tap] has been given this gesture's first centroid. */
        private var tapPrimed = false

        /**
         * The fork the speculative tail is smoothed through.
         *
         * Rebuilt only when the brush's stabilization strength moves, because
         * `Stabilizer.copyStateTo` refuses a fork of a different strength —
         * forking into a differently tuned filter is silent, and produces a
         * plausible tail that simply does not match the ink it extends.
         */
        private var tailSmoothing = Stabilizer(pen.stabilization)

        private val tailEmitter = TailEmitter()
        private val tail = PredictedTail(tailEmitter)

        /**
         * The marquee this gesture is building, or null when it is drawing.
         *
         * Fixed at pen-down and never revisited, the same rule [eraseDecided]
         * follows: switching tools with the pen already on the glass must not
         * turn half a lasso into half a pencil line.
         */
        private var marquee: Marquee? = null

        /**
         * Whether this gesture is a colour pick, fixed at pen-down.
         *
         * [marquee]'s rule and for [marquee]'s reason: reaching for the picker
         * with the pen already on the glass must not turn half a stroke into a
         * pick, and putting the picker away mid-gesture must not turn half a
         * pick into a stroke laid down from wherever the nib happens to be.
         */
        private var pickingThis = false

        /**
         * Whether this gesture is being ignored because the sheet is locked.
         *
         * [pickingThis]'s rule: fixed at pen-down and held for the gesture, so
         * unlocking the sheet with the pen already down does not start a stroke
         * from wherever the nib happens to be.
         */
        private var refusing = false

        private val marqueeBuilder = Marquee()

        private var marqueeMode = SelectMode.NEW
        private var marqueeModeDecided = false

        override fun onStrokeBegin(pointerId: Int) {
            if (picking) {
                beginPick(pointerId)
                return
            }
            pickingThis = false
            // Lr4. A locked sheet takes nothing, and the gesture is dropped
            // here rather than at the commit: see [activeLocked]. The marquee
            // is above this line on purpose -- selecting is not putting
            // anything on the sheet.
            refusing = !selecting && activeLocked
            if (refusing) return
            if (selecting) {
                beginMarquee(pointerId)
                return
            }
            marquee = null
            emitted = 0
            seen = 0
            strokeTiltMaxRad = 0f
            strokePressureMin = Float.MAX_VALUE
            strokePressureMax = 0f
            strokePointerId = pointerId
            downTimeNanos = 0L
            gate.reset()
            // The brush first, and everything below reads it: the stabilizer's
            // time constant, the front buffer's antialias flag and the
            // builder's own filter all come off `pen`, so choosing it after any
            // of them would start the stroke with the previous one's numbers.
            // `barrelHeld` is the guess; `applyEraseFor` corrects it from the
            // first sample's own buttons if it was wrong.
            chooseBrush(barrelHeld)
            if (tailSmoothing.strength != pen.stabilization) {
                tailSmoothing = Stabilizer(pen.stabilization)
            }
            frozen = transform
            strokeEpoch++
            // The barrel decision is deferred to the first sample: the router
            // announces a stroke by pointer id and nothing in that signature
            // carries a button. Erasing is a property of the whole stroke, so
            // it must be fixed before any dab is laid, which is what
            // [applyEraseFor] does on the first sample and never again.
            eraseDecided = false
            // Ik13. Read **once, here**, and kept for the stroke, which is the
            // discipline `ink` follows and for the same reason: a ruler dragged
            // while the pen is down must not bend the half of the line that is
            // already on the page. `GuideSet.snap` is a volatile reference to
            // an immutable Snap over immutable guides, so one read is enough.
            //
            // Not while erasing. A rubber that only rubs along a ruler is a
            // rubber nobody can take a mistake out with, and taking the mistake
            // out is what the ruler made likely.
            val guides = document.guides
            builder.snap = if (pen.erase) null else guides.snap
            strokeGuideText = if (pen.erase) null else guides.snapText
            builder.begin(inkColorArgb)
            // Reset, never reallocated: this is filled one sample at a time at
            // 321.75 Hz, and it is the only new per-sample work Ik3 adds.
            sampleLog.reset()
            // The brush as it is *now*, because a record names the parameters
            // the stroke was drawn with and the user can move a slider before
            // the next one. Encoding costs a string per pen-up; the sheet then
            // interns it, so twenty strokes with one nib hold one copy.
            strokeBrushText = BrushCodec.encode(pen)
            // A fresh Matrix per pen-down, never reused: see beginStroke.
            beginStroke(strokeMatrix(frozen), inkColorArgb, pen.antiAlias)
            // GC count first: reading it allocates a String, and taking it
            // before the heap watermark keeps that allocation out of the very
            // window it is there to validate.
            gcAtBegin = gcCount()
            dragBytes = 0L
            strokeOpen = true
            this@InkSurfaceView.strokeOpen = true
        }

        /**
         * The pipeline's `toDoc` step, and it is not optional.
         *
         * A `PenSample` carries what `MotionEvent` reported, which is **view**
         * coordinates. Everything downstream — the stabilizer's time constant,
         * the resampler's arc-length spacing, `Brush`'s radii, the layer
         * bitmap, `Bounds` — is document space. Feeding view coordinates
         * straight to the builder is not a coordinate mix-up that shows up as a
         * crash: it draws a stroke scaled by the zoom factor and offset by the
         * pan, which at 1:1 with no pan is invisible, and this is exactly how
         * it was found on the tablet rather than in a test.
         */
        override fun onStrokeSamples(samples: ArrayList<PenSample>) {
            if (refusing) return
            if (pickingThis) {
                extendPick(samples)
                return
            }
            val marquee = this.marquee
            if (marquee != null) {
                extendMarquee(marquee, samples)
                return
            }
            // Indexed, not `for (s in samples)`: an ArrayList iterator is an
            // allocation per event on the path with a per-sample budget.
            val n = samples.size
            if (n > 0) applyEraseFor(samples[0])
            seen += n
            totalSamples += n
            val dabsBefore = builder.dabCount
            var i = 0
            while (i < n) {
                val s = samples[i]
                if (downTimeNanos == 0L) downTimeNanos = s.eventTimeNanos
                frozen.viewToDoc(s.x, s.y, docPoint)
                lastRealDocX = docPoint[0]
                lastRealDocY = docPoint[1]
                // Tilt first, so the filter has this sample's attitude before
                // the dab that uses it is emitted. It is a separate call
                // because the coordinates are converted in place and handed
                // over as floats, while tilt needs no conversion at all -- it
                // is an angle of the pen against the glass and has nothing to
                // do with where the document is.
                //
                // **Missing this line is why tilt did nothing on the device.**
                // StrokeBuilder.add(PenSample) feeds the filter, but the app
                // uses the by-parts overload for the reason above, so every dab
                // the app ever laid was drawn at a tilt of zero. The engine
                // tests passed throughout, because they use the PenSample form.
                if (s.tilt > strokeTiltMaxRad) strokeTiltMaxRad = s.tilt
                if (s.pressure < strokePressureMin) strokePressureMin = s.pressure
                if (s.pressure > strokePressureMax) strokePressureMax = s.pressure
                builder.addTilt(s.tilt, s.orientation, s.eventTimeNanos)
                builder.add(docPoint[0], docPoint[1], s.pressure, s.eventTimeNanos)
                // The same six numbers the builder just saw, and that is the
                // whole requirement: a record is replayed by feeding these back
                // through this builder, so anything logged that the builder did
                // not see, or seen that was not logged, is a re-render that
                // draws something else.
                sampleLog.add(
                    docPoint[0],
                    docPoint[1],
                    s.pressure,
                    s.tilt,
                    s.orientation,
                    (s.eventTimeNanos - downTimeNanos) / 1_000_000f,
                )
                // Stabilized and in document space, which is what the gate has
                // to see: raw samples carry the digitizer's jitter, and jitter
                // read as curvature suppresses prediction on exactly the slow
                // straight line it helps most.
                gate.push(builder.smoothedX, builder.smoothedY, s.eventTimeNanos)
                i++
            }
            flushWet()
            totalDabs += builder.dabCount - dabsBefore
            if (predictionEnabled) drawPredictedTail()
        }

        /**
         * Draw one frame's worth of speculative ink, front buffer only.
         *
         * Every guard here is a way of drawing nothing, and drawing nothing is
         * always the safe answer: the ink this produces cannot be taken back.
         *
         * There is one thing it deliberately does *not* do, because it cannot:
         * erase the previous frame's tail. Front-buffer ink is unretractable,
         * so each frame's speculation is still on screen when the next frame's
         * real samples arrive. The real ink overdraws most of it in the same
         * colour and what survives is the overshoot — the part the pen never
         * reached. That is the failure mode, it is visible rather than
         * transient, and it is why the toggle defaults off.
         */
        private fun drawPredictedTail() {
            // W13, and the answer is no for these brushes.
            //
            // The front buffer accumulates, which is what makes it fast, and
            // Phase 1's prediction relied on that: a wrong guess is overdrawn
            // in the same colour by the real ink and only the overshoot
            // survives. The indirect path breaks the arrangement in a way that
            // cannot be patched around it. Speculative dabs would land on the
            // *scratch*, which is not a frame buffer but the stroke itself, and
            // the stroke is composited once at pen-up — so a guess written
            // there is permanent, and a wrong one is baked into the committed
            // pixels rather than overdrawn a frame later.
            //
            // Removing it again is not possible either: the dabs are
            // translucent and have already composited with their neighbours, so
            // there is nothing to subtract. The honest answer is that these two
            // features are incompatible as built, and prediction is the one
            // that yields — it is an optimisation, and the scratch buffer is a
            // correctness fix.
            if (indirectNeeded()) {
                predictSuppressedIndirect++
                return
            }
            if (!gate.allows) {
                gateSuppressed++
                return
            }
            gateAllowed++
            if (builder.dabCount == 0) return
            val p = predictor ?: return
            val sample = p.predict(strokePointerId) ?: return
            if (!sample.x.isFinite() || !sample.y.isFinite()) return

            frozen.viewToDoc(sample.x, sample.y, docPoint)
            recordLead(docPoint[0] - lastRealDocX, docPoint[1] - lastRealDocY)
            builder.forkSmoothing(tailSmoothing)
            tailSmoothing.push(docPoint[0], docPoint[1], sample.pressure, sample.eventTimeNanos)
            // **Through the same guide the real ink goes through**, and after
            // the same smoothing. `docs/inker-plan.md`'s tripwire is "a wet
            // tail that wanders off the guide and jumps back", and the tail is
            // the part of the stroke the eye is on — so it is the most visible
            // thing a guide can get wrong. See `StrokeBuilder.snapPredicted`.
            var tailX = tailSmoothing.x
            var tailY = tailSmoothing.y
            if (builder.snapPredicted(tailX, tailY, tailPoint)) {
                tailX = tailPoint[0]
                tailY = tailPoint[1]
            }

            val last = builder.dabCount - 1
            val fromRadius = builder.radius(last)
            val batch = acquireBatch()
            tailEmitter.batch = batch
            val elapsedMillis = (sample.eventTimeNanos - downTimeNanos) / 1_000_000f
            val emittedDabs = tail.emit(
                fromX = builder.x(last),
                fromY = builder.y(last),
                toX = tailX,
                toY = tailY,
                fromPressure = builder.smoothedPressure,
                toPressure = tailSmoothing.pressure,
                elapsedMillis = elapsedMillis,
                firstSpacing = pen.spacingFor(fromRadius),
                // The cap and the batch capacity are the same number on
                // purpose: the tail cannot overflow the batch it was given, so
                // there is no partial-batch case to get wrong.
                maxDabs = batch.capacity,
            )
            tailEmitter.batch = null
            // drawWet reports an empty batch drawn, so the pool does not leak a
            // slot when every guard above passed and the tail was still zero
            // dabs long — a pen barely moving does that on most frames.
            drawWet(batch)
            predictedDabs += emittedDabs
        }

        /**
         * Ship every dab the builder has produced but not yet drawn.
         *
         * Batches are filled to capacity and submitted as they fill, so a
         * single event that produced more dabs than one batch holds becomes
         * several front-buffer renders rather than a dropped tail.
         */
        private fun flushWet() {
            var batch: DabBatch? = null
            while (emitted < builder.dabCount) {
                val b = batch ?: acquireBatch().also { batch = it }
                b.add(
                    builder.x(emitted), builder.y(emitted), builder.radius(emitted),
                    builder.aspect(emitted), builder.rotation(emitted), builder.flow(emitted),
                )
                emitted++
                if (b.isFull) {
                    drawWet(b)
                    batch = null
                }
            }
            batch?.let { drawWet(it) }
        }

        /**
         * `end()` first, then the wet flush, then the commit.
         *
         * `StrokeBuilder.end()` emits the segment the Catmull-Rom fit was
         * holding back — the fit needs the point after a segment's endpoint, so
         * the last one is always one sample behind — and those dabs have never
         * been drawn. Committing without flushing them leaves the very tip of
         * every stroke appearing only when the commit transaction lands, which
         * reads as the stroke snapping forward at pen-up.
         */
        override fun onStrokeEnd() {
            if (refusing) {
                refusing = false
                return
            }
            if (pickingThis) {
                endPick()
                return
            }
            val marquee = this.marquee
            if (marquee != null) {
                endMarquee(marquee)
                return
            }
            // The drag watermark is taken before end() runs, because end()
            // flushes the held-back segment and then allocates the Stroke, its
            // dab copy and its Bounds. Those are the commit's, on purpose, and
            // rolling them into the drag figure would hide a per-sample leak
            // behind an 8 KB copy that is supposed to be there.
            val commitStart = heapUsed()
            val dabsBefore = builder.dabCount
            val stroke = builder.end()
            flushWet()
            totalDabs += builder.dabCount - dabsBefore
            lastStrokeSamples = seen
            lastStrokeDabs = stroke.dabCount
            lastStrokeTiltDeg = strokeTiltMaxRad * DEG_PER_RAD
            lastStrokePressureMin =
                if (strokePressureMin == Float.MAX_VALUE) 0f else strokePressureMin
            lastStrokePressureMax = strokePressureMax
            var wMin = Float.MAX_VALUE
            var wMax = 0f
            for (i in 0 until stroke.dabCount) {
                val w = stroke.radius(i) * 2f
                if (w < wMin) wMin = w
                if (w > wMax) wMax = w
            }
            lastStrokeWidthMin = if (wMin == Float.MAX_VALUE) 0f else wMin
            lastStrokeWidthMax = wMax
            // **An erase on a sheet that keeps its strokes is an edit, not
            // ink.** The wet pass has already shown the grey going down, which
            // is the right feedback; what must not happen is the pixels being
            // committed, because on this sheet the truth is the record list and
            // a rubbed-out stroke has to leave it. The stroke is thrown away
            // and the sheet repaints itself from what is left.
            if (vectorSheetActive && (pen.erase || this@InkSurfaceView.erasingNow)) {
                document.requestStrokeOp(
                    StrokeOp.Erase(pathOf(stroke), eraseReach(stroke), eraseMode),
                )
                strokeOpen = false
                this@InkSurfaceView.strokeOpen = false
                releaseTransform()
                // **Cancel the wet stroke rather than abandoning it**, because
                // only `cancel()` hides the front buffer. Abandoning drops the
                // ink but leaves the front buffer showing the last thing drawn
                // into it, which sits on top of the sheet — so the rebuild was
                // correct in the layer and the screen went on showing the
                // stroke that had just been rubbed out until the app was
                // restarted. Found on the tablet, and only by restarting it.
                val r = this@InkSurfaceView.renderer
                if (r == null || !surfaceAlive) {
                    batches.releaseAll()
                } else {
                    commitWatermark = batches.issuedCount
                    r.cancel()
                }
                // The epoch, so the scratch buffer's contents are not mistaken
                // for the next stroke's. `abandonStroke` did this as well.
                strokeEpoch++
                redrawDry()
                return
            }
            commitStroke(stroke, pendingRecord(stroke))
            val commitEnd = heapUsed()
            strokeOpen = false
            releaseTransform()
            stats.recordStroke(
                dragBytes = dragBytes,
                dragSamples = seen,
                commitBytes = commitEnd - commitStart,
                gcs = gcCount() - gcAtBegin,
            )
        }

        /**
         * The stroke's input, packed, for whichever sheet wants it.
         *
         * The seed comes off the builder rather than being generated here,
         * because the builder is what drew the stroke: `begin(colorArgb)` hands
         * itself the next counter value and `strokeSeed` reads back the one it
         * used. Generating a second number here would produce a record that
         * replays as a *different* stroke, which is the exact defect Ik2 exists
         * to remove, reintroduced one layer up.
         *
         * Null for a stroke with no samples, which is what a cancelled or
         * zero-length gesture leaves behind.
         */
        private fun pendingRecord(stroke: Stroke): PendingStroke? {
            if (sampleLog.count == 0) return null
            return PendingStroke(
                samples = sampleLog.pack(),
                sampleCount = sampleLog.count,
                seed = builder.strokeSeed,
                colorArgb = inkColorArgb,
                erase = pen.erase,
                brushText = strokeBrushText,
                guideText = strokeGuideText,
                bounds = stroke.bounds,
            )
        }

        override fun onStrokeCancel() {
            if (refusing) {
                refusing = false
                return
            }
            if (pickingThis) {
                cancelPick()
                return
            }
            val marquee = this.marquee
            if (marquee != null) {
                // A palm arriving, a second finger taking the gesture away, the
                // window losing focus. Nothing is selected and nothing was:
                // the shape never reached the queue.
                marquee.cancel()
                this.marquee = null
                liveMarquee = null
                strokeOpen = false
                this@InkSurfaceView.strokeOpen = false
                releaseTransform()
                onMarqueeChanged?.invoke()
                return
            }
            gate.reset()
            builder.cancel()
            emitted = 0
            strokeOpen = false
            cancelStroke()
            releaseTransform()
        }

        // --- the colour picker ----------------------------------------------

        /**
         * A pick, rather than a stroke.
         *
         * The transform is frozen for the marquee's reason: every sample is
         * turned into document coordinates through it, and a pinch landing
         * mid-gesture would read the colour of a pixel the nib is not over.
         *
         * Nothing is queued, nothing is snapshotted and no ink is begun — a
         * pick is not an edit, so there is no undo step for it and no history
         * to protect. That is also why it holds the transform: the gesture has
         * to survive to pen-up, and `releaseTransform` is what lets go.
         */
        private fun beginPick(pointerId: Int) {
            pickingThis = true
            pickClosing = false
            marquee = null
            strokePointerId = pointerId
            frozen = transform
            pickWas = inkColorArgb
            pickPreview = 0
            strokeOpen = true
            this@InkSurfaceView.strokeOpen = true
        }

        /**
         * Whether the pen has lifted and the last read is still on its way.
         *
         * The pick cannot be finished at pen-up, because the colour comes from
         * the render thread and the newest request may not have been answered
         * — for a **tap** there has been no frame at all. So pen-up arms this,
         * asks for one more read, and the answer is what ends the gesture.
         */
        private var pickClosing = false

        /**
         * Read the colour under the newest sample and publish it.
         *
         * **The last sample of the batch, not all of them.** A pick has one
         * answer and it is wherever the nib is now; probing every sample of a
         * 321.75 Hz event would compose the stack five times to throw four of
         * the results away.
         *
         * A probe that comes back null — off the page — leaves the previous
         * answer standing rather than blanking the ring, so dragging off the
         * edge of the paper and back does not flicker.
         */
        private fun extendPick(samples: ArrayList<PenSample>) {
            val n = samples.size
            if (n == 0) return
            val s = samples[n - 1]
            // The ring's *position* is known here and now, and it moves with
            // the nib even while the colour it is showing is one frame old.
            // Splitting the two is what keeps the marker under the pen.
            pickAtX = s.x
            pickAtY = s.y
            onPickPreview?.invoke()
            frozen.viewToDoc(s.x, s.y, docPoint)
            requestPick(floor(docPoint[0]).toInt(), floor(docPoint[1]).toInt())
        }

        /**
         * The render thread's answer. **UI thread**, through `post`.
         *
         * A zero means the probe found nothing — off the page, or a document
         * closed underneath — and it leaves the previous answer standing rather
         * than blanking the ring, so dragging off the edge of the paper and
         * back does not flicker.
         */
        fun deliverPick(argb: Int) {
            if (!pickingThis) return
            if (argb != 0) {
                pickPreview = argb
                onPickPreview?.invoke()
            }
            if (pickClosing) finishPick()
        }

        /**
         * The pen has left the glass: the colour under it is now in the hand.
         *
         * On **lift** and not on touch, which is what lets the nib be slid onto
         * the right pixel while the ring updates. A pick that never found a
         * colour — one tap off the page — ends silently and changes nothing.
         */
        private fun endPick() {
            // Nothing will answer if there is no surface to render into, and a
            // picker left on with a ring stuck to the glass is worse than a
            // pick that did not happen.
            if (!surfaceAlive) {
                clearPick()
                return
            }
            pickClosing = true
            frozen.viewToDoc(pickAtX, pickAtY, docPoint)
            requestPick(floor(docPoint[0]).toInt(), floor(docPoint[1]).toInt())
            // And a deadline, because the last read is the one thing in this
            // gesture that another thread has to do. A frame that never comes
            // would otherwise leave the picker on with its ring stuck to the
            // glass and no gesture that clears it — which is what a held pick
            // did on the tablet once, and once is enough to design against.
            // Finishing early costs the freshest reading and nothing else: the
            // colour under the nib a frame ago is the colour under the nib.
            postDelayed({ if (pickClosing) finishPick() }, PICK_DEADLINE_MS)
        }

        /** The last read has landed: the colour under the pen is now in the hand. */
        private fun finishPick() {
            val argb = pickPreview
            clearPick()
            if (argb != 0) onColourPicked?.invoke(argb)
        }

        /** A palm, a second finger, a lost window. The colour stays as it was. */
        private fun cancelPick() {
            clearPick()
        }

        private fun clearPick() {
            pickingThis = false
            pickClosing = false
            pickPreview = 0
            strokeOpen = false
            this@InkSurfaceView.strokeOpen = false
            releaseTransform()
            onPickPreview?.invoke()
        }

        // --- the marquee ----------------------------------------------------

        /**
         * A selection gesture, rather than a stroke.
         *
         * The transform is frozen here for the same reason a stroke freezes it:
         * every sample is turned into document coordinates through it, and a
         * pinch landing mid-gesture would put the second half of a lasso
         * somewhere the first half is not. Unlike a stroke there is no wet ink
         * to go wrong — the shape is rebuilt from its points at any zoom — but
         * one mapping for one gesture is the rule the whole input path is built
         * on and there is no reason to have a second answer here.
         */
        private fun beginMarquee(pointerId: Int) {
            strokePointerId = pointerId
            frozen = transform
            marqueeModeDecided = false
            marqueeMode = this@InkSurfaceView.marqueeMode
            frozen.viewToDoc(0f, 0f, docPoint)
            marquee = marqueeBuilder
            liveMarquee = marqueeBuilder
            marqueeBuilder.cancel()
            strokeOpen = true
            this@InkSurfaceView.strokeOpen = true
            beganAtDown = false
            onMarqueeChanged?.invoke()
        }

        /**
         * Whether the marquee has had its first point yet.
         *
         * `onStrokeBegin` carries a pointer id and no coordinates — the
         * decision that opens a stroke and the event that carries its position
         * are separate bits in the same mask — so the shape cannot start until
         * the first sample arrives. Without this the rectangle would be dragged
         * from wherever the previous one ended.
         */
        private var beganAtDown = false

        /**
         * Where the select gesture went down, in document space.
         *
         * Needed because a **tap** is a gesture whose shape is empty: the
         * marquee records nothing below `Marquee.MIN_STEP_DOC_PX`, so
         * `computeBounds` on it gives a rectangle at the origin and a tap on a
         * stroke would be tested at (0, 0). The gesture's first point is the
         * only thing that knows where the pen actually landed.
         */
        /** Reused by [drawPredictedTail]'s snap; one per view, never escapes. */
        private val tailPoint = FloatArray(2)

        private var marqueeDownX = 0f
        private var marqueeDownY = 0f

        private fun extendMarquee(marquee: Marquee, samples: ArrayList<PenSample>) {
            val n = samples.size
            if (n == 0) return
            if (!marqueeModeDecided) {
                marqueeModeDecided = true
                // The barrel button, as a momentary subtract. Same shape as the
                // eraser's override on the brush: hold it, take some away, let
                // go, and the panel still says what it said. There is no
                // keyboard on this tablet and the hand is already on the barrel.
                if ((samples[0].buttonState and BARREL_BUTTONS) != 0) {
                    marqueeMode = SelectMode.SUBTRACT
                }
            }
            var changed = false
            var i = 0
            while (i < n) {
                val sample = samples[i]
                frozen.viewToDoc(sample.x, sample.y, docPoint)
                if (!beganAtDown) {
                    beganAtDown = true
                    marqueeDownX = docPoint[0]
                    marqueeDownY = docPoint[1]
                    marquee.begin(marqueeShape, docPoint[0], docPoint[1])
                    changed = true
                } else if (marquee.extend(docPoint[0], docPoint[1])) {
                    changed = true
                }
                i++
            }
            if (changed) onMarqueeChanged?.invoke()
        }

        private fun endMarquee(marquee: Marquee) {
            val shape = marquee.end()
            this.marquee = null
            liveMarquee = null
            strokeOpen = false
            this@InkSurfaceView.strokeOpen = false
            releaseTransform()
            onMarqueeChanged?.invoke()
            // Copied into the op, which is what makes it safe to go on using
            // this builder for the next gesture. See `SelectOp.Shape`.
            if (pickingStrokes) {
                // A gesture that went nowhere is a tap, and a tap on a stroke
                // has to pick that stroke rather than lassoing the three
                // document pixels under the nib. The threshold is the marquee's
                // own step, doubled: below it the shape has no inside to speak
                // of, so testing against it would answer nothing on any nib.
                val box = android.graphics.RectF()
                shape.computeBounds(box, true)
                // Empty as well as small: a gesture that never moved records no
                // points at all, and its bounds is a rectangle at the origin
                // rather than a rectangle where the pen was. Both are taps, and
                // both are answered at the point the gesture went **down**,
                // which is the only thing that knows where that was.
                val tap = shape.isEmpty ||
                    (box.width() <= TAP_SLOP_DOC && box.height() <= TAP_SLOP_DOC)
                if (tap) {
                    document.requestStrokeOp(
                        StrokeOp.Tap(marqueeDownX, marqueeDownY, TAP_SLOP_DOC, marqueeMode),
                    )
                } else {
                    document.requestStrokeOp(StrokeOp.Lasso(shape, marqueeMode))
                }
                renderer?.commit()
                return
            }
            select(SelectOp.Shape(shape, marqueeMode))
        }

        override fun onGestureBegin() {
            gestures.begin()
            // The recogniser is fed from here rather than from `onTouchEvent`
            // so that it sees exactly the gestures the exclusivity rules
            // allowed: a palm and a finger never open one, and a gesture the
            // pen took away arrives as a cancel. Nothing about "two fingers,
            // and not the pen" has to be restated.
            gestureBeganNanos = System.nanoTime()
            tapPrimed = false
        }

        override fun onGesturePointers(
            ids: IntArray,
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
        ) {
            gestures.pointers(ids, xs, ys, count)
            var cx = 0f
            var cy = 0f
            for (i in 0 until count) {
                cx += xs[i]
                cy += ys[i]
            }
            cx /= count
            cy /= count
            // The first pointer report is the tap's position, not a movement.
            // `onGestureBegin` carries no coordinates — the decision that opens
            // a gesture and the event that carries its pointers are separate
            // bits in the same mask — so without this the travel would be
            // measured from (0, 0) and every tap would look like a fling across
            // the whole screen.
            if (tapPrimed) {
                tap.move(count, cx, cy)
            } else {
                tap.begin(gestureBeganNanos, count, cx, cy)
                tapPrimed = true
            }
        }

        override fun onGestureEnd() {
            gestures.end()
            if (tap.end(System.nanoTime())) undoFromTap()
        }

        override fun onGestureCancel() {
            gestures.cancel()
            tap.cancel()
        }

        override fun onPenPresence(inRange: Boolean) = Unit
    }

    /**
     * Pen-up: unfreeze the transform and apply whatever was held.
     *
     * A held transform redraws immediately rather than waiting for the next
     * thing to ask for a frame, because the reason it was held is usually that
     * the surface changed size underneath the stroke — and a canvas fitted to
     * the previous window is the visible symptom.
     */
    private fun releaseTransform() {
        strokeOpen = false
        val held = pendingTransform ?: return
        pendingTransform = null
        setTransform(held)
        redrawDry()
    }

    private fun recordLead(dx: Float, dy: Float) {
        val lead = sqrt(dx * dx + dy * dy)
        if (!lead.isFinite()) return
        leadSumDoc += lead.toDouble()
        leadCount++
        predictLeadMeanDoc = (leadSumDoc / leadCount).toFloat()
        if (lead > predictLeadMaxDoc) predictLeadMaxDoc = lead
    }

    /**
     * Where the speculative tail's dabs go: straight into a [DabBatch], never
     * into `StrokeBuilder`.
     *
     * That is the whole of "predicted dabs go to the front buffer only". The
     * committed `Stroke` is built from real samples, so the layer never sees a
     * guess, and the `DabEmitter` seam is what makes the two paths able to
     * share `Brush`'s sizing and spacing without sharing a destination.
     */
    private inner class TailEmitter : DabEmitter {
        var batch: DabBatch? = null

        override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float {
            val radius = pen.sizeFor(pressure, elapsedMillis) * 0.5f
            // No shape dynamics on the speculative tail, deliberately. Scatter
            // and jitter are random per dab, so a predicted dab and the real
            // dab that replaces it would land in different places and the tail
            // would visibly disagree with the ink it is guessing at. A round
            // guess that is slightly the wrong shape is a smaller error than a
            // correctly shaped one in the wrong place.
            batch?.let { if (!it.isFull) it.add(x, y, radius) }
            return pen.spacingFor(radius)
        }
    }

    /** Frames where prediction was skipped because the brush is indirect. See [drawPredictedTail]. */
    var predictSuppressedIndirect: Long = 0L
        private set

    /** Speculative dabs drawn since the view was created. Diagnostics. */
    var predictedDabs: Long = 0L
        private set

    /**
     * How far ahead of the last real sample the predictor actually put the pen,
     * in document pixels: mean and worst.
     *
     * The question this answers is whether there is any prediction happening at
     * all. `MotionEventPredictor` resolves to `SystemMotionEventPredictor` on
     * this device, which delegates to the platform's `MotionPredictor` — and
     * the platform reports `isPredictionAvailable` **false** for this pen. A
     * predictor with nothing to say can still hand back an event, and if that
     * event is the current position echoed, a speculative tail still gets
     * drawn: it bridges the gap between the last *dab* and the last *sample*,
     * which the Catmull-Rom fit's one-sample lag opens on every frame. That is
     * a real and useful thing to draw, and it is not prediction.
     *
     * Measured against the raw document-space sample rather than the smoothed
     * one, because the stabilizer damps the lead and would understate it.
     */
    var predictLeadMeanDoc: Float = 0f
        private set

    /** See [predictLeadMeanDoc]. */
    var predictLeadMaxDoc: Float = 0f
        private set

    private var leadSumDoc = 0.0
    private var leadCount = 0L

    /** Frames where the curvature gate let prediction through, and blocked it. */
    var gateAllowed: Long = 0L
        private set

    /** See [gateAllowed]. */
    var gateSuppressed: Long = 0L
        private set

    /**
     * Bytes live on the Java heap right now.
     *
     * A level, not a counter, which is why [InputStats.strokeGcs] travels with
     * every figure derived from it: a collection inside the measured window
     * subtracts freed bytes from allocated ones, and the delta then reads low,
     * zero or negative. ART has no per-thread allocation counter that survived
     * the Dalvik era — `Debug.startAllocCounting` is a no-op — so this pair of
     * calls plus a GC guard is the honest instrument available.
     */
    private fun heapUsed(): Long {
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }

    /** Collections so far, or 0 if the runtime does not report the stat. */
    private fun gcCount(): Long =
        Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

    companion object {

        /**
         * No pick is waiting to be read. Not a coordinate, so it matches
         * nothing a pen can land on.
         */
        private const val NO_PICK = Int.MIN_VALUE

        /**
         * Saturation zero, for Lr5's grey view.
         *
         * One instance for the process. A `ColorMatrixColorFilter` is immutable
         * and this one is a constant, so unlike every `Paint` in this file it
         * is safe to share between threads and between views.
         */
        private val GREY = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(0f) },
        )

        /**
         * How long pen-up waits for the render thread's last colour.
         *
         * Long enough that a dry frame lands inside it even on a page with
         * eight sheets — Ik0 measured the round trip at 3–4 ms — and short
         * enough that a hand does not notice the wait when it does not.
         */
        private const val PICK_DEADLINE_MS = 250L

        /**
         * The damage square Ik0 prices an edit against, in document pixels.
         *
         * A thousand is about half the page's short side and is deliberately
         * generous: a stroke's own bounds are usually far smaller, so erring
         * this way fails towards the honest answer rather than the flattering
         * one.
         */
        private const val PATCH_DOC: Float = 1000f

        /**
         * Where the re-render bench's stroke seeds start. Arbitrary and fixed:
         * what matters is that stroke *i* of one run gets the same seed as
         * stroke *i* of the run it is compared against, which is what makes
         * `driftOf` a measurement of the engine rather than of a counter.
         */
        /**
         * How far a select gesture may travel and still be a tap, in document
         * pixels.
         *
         * Four: twice `Marquee.MIN_STEP_DOC_PX`, which is the smallest step
         * that gesture records at all. Below it the shape has no inside worth
         * testing, so a lasso would answer nothing whatever the nib — and the
         * user, who put the pen on a stroke, would see the app ignore them.
         *
         * It is also the slop the tap is given, which is the same number for a
         * different reason: a hand aiming at a two-pixel fineliner line misses
         * it by about that much.
         */
        const val TAP_SLOP_DOC: Float = 4f

        private const val IK0_SEED_BASE: Int = 700_000

        /**
         * The pen-down instant a replay pretends to. Arbitrary and fixed: only
         * the deltas from it are read, by `Brush.onsetMillis` and by the speed
         * sensor, and both want the time *within* the stroke.
         */
        private const val REPLAY_EPOCH_NANOS: Long = 1_000_000_000L


        /**
         * The stylus barrel buttons, as `MotionEvent` reports them:
         * `BUTTON_SECONDARY` (4), `BUTTON_STYLUS_PRIMARY` (32) and
         * `BUTTON_STYLUS_SECONDARY` (64). The Pro Pen 3 addresses all three
         * individually; any of them erases, because a user who has pressed *a*
         * button while drawing has not asked for a menu.
         */
        const val BARREL_BUTTONS = 4 or 32 or 64

        /** For the HUD's tilt readout only. */
        const val DEG_PER_RAD = 57.29578f

        /**
         * A dark neutral, so that white paper reads as a sheet rather than as
         * the whole screen.
         *
         * Dark rather than light on purpose: the ink is black, and a light desk
         * would make the page edge the lowest-contrast line on a screen whose
         * whole job is showing where a line is. It is not `Color.BLACK` either
         * — a pure black surround under an antialiased black stroke makes the
         * paper's edge look like part of the drawing.
         */
        const val DEFAULT_DESK_COLOR: Int = 0xFF303134.toInt()
    }
}
