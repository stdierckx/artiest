package be.thalos.artiest.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Debug
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import be.thalos.artiest.doc.CommitQueue
import be.thalos.artiest.doc.Document
import be.thalos.artiest.engine.ink.DabEmitter
import be.thalos.artiest.engine.ink.PredictedTail
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PredictionGate
import be.thalos.artiest.engine.input.RejectionCounters
import be.thalos.artiest.engine.input.Stabilizer
import be.thalos.artiest.engine.input.TwoFingerDoubleTap
import be.thalos.artiest.engine.xform.CanvasTransform
import be.thalos.artiest.ink.DabRasterizer
import be.thalos.artiest.ink.GrainTexture
import be.thalos.artiest.ink.ScratchLayer
import be.thalos.artiest.ink.StampCache
import be.thalos.artiest.input.InkInputSink
import be.thalos.artiest.input.InputRouter
import be.thalos.artiest.input.eventAgeNanos
import be.thalos.artiest.input.Predictor
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
     * The brush.
     *
     * A `val` holding a mutable `Brush` rather than a reassignable field:
     * `StrokeBuilder` captures the instance at construction, so replacing it
     * here would leave the builder drawing with the old one and nothing would
     * fail. `Brush`'s own settings are `var`, so W15's sliders move those.
     */
    val pen: Brush = Brush()

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
        transform = t
        return true
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
    private val commitSink = object : CommitQueue.Sink {
        override fun onStroke(stroke: Stroke) {
            // The snapshot first, and it is not merely ordering: this is the
            // last moment the region exists in its pre-stroke state. Taken
            // after the rasterise it would record the stroke as its own undo.
            document.snapshotBeforeStroke(stroke.bounds)
            if (!indirectNeeded()) {
                document.layer.write { rasterizer.drawDry(it, stroke) }
                return
            }
            // W6's indirect path. The dabs land on the scratch, which starts
            // empty, so the stroke's own overlaps composite against nothing;
            // the single composite at the end is what carries the opacity.
            // The wet pass has usually already accumulated this stroke. Reuse
            // it when it has: re-laying every dab would double the paint on a
            // translucent brush, which is the very bug the buffer exists to
            // prevent, wearing a different hat.
            if (scratch.isOpen && scratchEpoch == strokeEpoch &&
                scratch.ensureCovers(stroke.bounds)
            ) {
                document.layer.write {
                    scratch.compositeInto(it, pen.opacity, grainShader())
                }
                return
            }
            scratch.begin(stroke.bounds)
            val sc = scratch.canvasInDocSpace()
            if (sc == null) {
                // The buffer could not be opened. Falling back to the direct
                // path draws a beaded stroke, which is wrong but visible;
                // dropping the stroke silently is wrong and invisible.
                document.layer.write { rasterizer.drawDry(it, stroke) }
                return
            }
            rasterizer.drawDry(sc, stroke, flowOverride = pen.flow)
            document.layer.write { scratch.compositeInto(it, pen.opacity, grainShader()) }
        }

        override fun onClear() {
            document.snapshotBeforeClear()
            document.layer.blank()
        }

        override fun onUndo() {
            document.applyUndo()
        }

        override fun onRedo() {
            document.applyRedo()
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
    val scratchExtent: String get() = "${scratch.width}x${scratch.height}"

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
     */
    private fun indirectNeeded(): Boolean =
        pen.opacity < 1f || pen.flow < 1f || pen.hardness < 1f || pen.grain.isActive

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
        rasterizer.boundsOf(batch, wetRect)
        val bounds = Bounds.of(wetRect[0], wetRect[1], wetRect[2], wetRect[3])
        if (!scratch.isOpen || scratchEpoch != strokeEpoch) {
            scratch.begin(bounds)
            scratchEpoch = strokeEpoch
        } else {
            scratch.ensureCovers(bounds)
        }
        val sc = scratch.canvasInDocSpace() ?: return
        rasterizer.drawInto(sc, batch, pen.flow)

        val save = canvas.save()
        canvas.concat(docToView)
        canvas.clipRect(wetRect[0], wetRect[1], wetRect[2], wetRect[3])
        canvas.clipRect(0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat())
        paperPaint.color = document.paperColor
        canvas.drawRect(0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat(), paperPaint)
        document.layer.read { canvas.drawBitmap(it, 0f, 0f, blitPaint) }
        scratch.drawOnto(canvas, pen.opacity, grainShader())
        canvas.restoreToCount(save)
    }

    /** The grain shader anchored to the scratch's current origin, or null. */
    private fun grainShader() =
        grain.shaderFor(pen.grain, scratch.originX, scratch.originY)

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

    /** The page rectangle's fill. One `Paint` for the view's life; see [blitPaint]. */
    private val paperPaint = Paint()

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
            if (!indirectNeeded()) {
                rasterizer.drawWet(canvas, m, param)
                return
            }
            drawWetIndirect(canvas, m, param)
            batches.markDrawn(param.sequence)
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
        }

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<DabBatch>,
        ) {
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
                dryMatrixSource = t
            }
            val save = canvas.save()
            canvas.concat(dryMatrix)
            paperPaint.color = document.paperColor
            canvas.drawRect(0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat(), paperPaint)
            document.layer.read { canvas.drawBitmap(it, 0f, 0f, blitPaint) }
            // A stroke still in flight lives on the scratch, not in the layer,
            // so a redraw that ignored it would blank the wet ink for a frame
            // every time the transform changed. Pinching mid-stroke is not a
            // gesture anyone makes on purpose, but the same redraw is what
            // `redrawDry` schedules after a zoom button.
            if (scratch.isOpen) scratch.drawOnto(canvas, pen.opacity, grainShader())
            canvas.restoreToCount(save)
        }
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
    override fun commitStroke(stroke: Stroke) {
        commitWatermark = batches.issuedCount
        document.commitStroke(stroke)
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
        val handled = router.onTouchEvent(event, this)
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
        return handled
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        router.onHoverEvent(event)
        // The framework's own hover handling still runs: the router took a
        // presence reading, it did not consume the event.
        return super.onHoverEvent(event)
    }

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
        private var seen = 0

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

        /** The last real sample in document space, for the lead measurement. */
        private var lastRealDocX = 0f
        private var lastRealDocY = 0f

        private val gate = PredictionGate()

        /** Two fingers down and up twice puts the canvas back. */
        private val doubleTap = TwoFingerDoubleTap()

        /** When the current gesture opened, for [doubleTap]'s tap duration. */
        private var gestureBeganNanos = 0L

        /** Whether [doubleTap] has been given this gesture's first centroid. */
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

        override fun onStrokeBegin(pointerId: Int) {
            emitted = 0
            seen = 0
            strokePointerId = pointerId
            downTimeNanos = 0L
            gate.reset()
            if (tailSmoothing.strength != pen.stabilization) {
                tailSmoothing = Stabilizer(pen.stabilization)
            }
            frozen = transform
            strokeEpoch++
            builder.begin(inkColorArgb)
            // A fresh Matrix per pen-down, never reused: see beginStroke.
            beginStroke(docToViewMatrix(frozen), inkColorArgb, pen.antiAlias)
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
            // Indexed, not `for (s in samples)`: an ArrayList iterator is an
            // allocation per event on the path with a per-sample budget.
            val n = samples.size
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
                builder.add(docPoint[0], docPoint[1], s.pressure, s.eventTimeNanos)
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

            val last = builder.dabCount - 1
            val fromRadius = builder.radius(last)
            val batch = acquireBatch()
            tailEmitter.batch = batch
            val elapsedMillis = (sample.eventTimeNanos - downTimeNanos) / 1_000_000f
            val emittedDabs = tail.emit(
                fromX = builder.x(last),
                fromY = builder.y(last),
                toX = tailSmoothing.x,
                toY = tailSmoothing.y,
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
                    builder.aspect(emitted), builder.rotation(emitted),
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
            commitStroke(stroke)
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

        override fun onStrokeCancel() {
            gate.reset()
            builder.cancel()
            emitted = 0
            strokeOpen = false
            cancelStroke()
            releaseTransform()
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
                doubleTap.move(count, cx, cy)
            } else {
                doubleTap.begin(gestureBeganNanos, count, cx, cy)
                tapPrimed = true
            }
        }

        override fun onGestureEnd() {
            gestures.end()
            if (doubleTap.end(System.nanoTime())) fitToView()
        }

        override fun onGestureCancel() {
            gestures.cancel()
            doubleTap.cancel()
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
        transform = held
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
