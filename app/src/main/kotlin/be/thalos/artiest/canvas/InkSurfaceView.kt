package be.thalos.artiest.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Debug
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import be.thalos.artiest.doc.CommitQueue
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp
import be.thalos.artiest.doc.StackCompositor
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
    private val commitSink = object : CommitQueue.Sink {
        override fun onStroke(stroke: Stroke) {
            // The snapshot first, and it is not merely ordering: this is the
            // last moment the region exists in its pre-stroke state. Taken
            // after the rasterise it would record the stroke as its own undo.
            document.snapshotBeforeStroke(stroke.bounds)
            if (!indirectNeeded()) {
                armRasterizer()
                document.layer.write { rasterizer.drawDry(it, stroke) }
                document.layers.touchActive()
                return
            }
            val stencil = document.selection.maskBitmap()
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
                // Already masked by the wet pass, and `DST_IN` is idempotent,
                // so this is belt to that braces -- and it is what covers the
                // case where `ensureCovers` has just grown the buffer into
                // ground the wet pass never masked.
                if (stencil != null) scratch.maskBy(stencil)
                document.layer.write {
                    scratch.compositeInto(
                        it, compositeAlpha(), compositeGrain(), pen.erase, pen.burnish,
                    )
                }
                document.layers.touchActive()
                return
            }
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
                document.layer.write {
                    val save = it.save()
                    document.selection.clipInto(it)
                    rasterizer.drawDry(it, stroke)
                    it.restoreToCount(save)
                }
                document.layers.touchActive()
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
            document.layer.write {
                scratch.compositeInto(
                    it, compositeAlpha(), compositeGrain(), pen.erase, pen.burnish,
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
            document.layers.touchActive()
        }

        override fun onUndo() {
            document.applyUndo()
        }

        override fun onRedo() {
            document.applyRedo()
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
            if (document.layers.apply(op)) document.layers.touchAll()
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
        override fun onSelect(op: be.thalos.artiest.doc.SelectOp) {
            document.selection.apply(op)
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
        pen.opacity < 1f || pen.flow < 1f || pen.hardness < 1f ||
            pen.grain.isActive || pen.erase || document.selection.active

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
        override val isOpen: Boolean get() = scratch.isOpen
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
     * so a pan or a zoom moves them and nothing else tells Compose that.
     */
    var onTransformChanged: (() -> Unit)? = null

    /**
     * Whether the toolbar's eraser is selected. Written from the UI thread.
     *
     * The barrel button is the *other* way in, and the two are an `or`: holding
     * a barrel button erases for that stroke whatever the toolbar says, and
     * releasing it does not switch the tool back. That is the behaviour a
     * pencil with an eraser end has, and it is the reason the button is a
     * momentary override rather than a toggle.
     */
    @Volatile
    var eraserTool: Boolean = false

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
        pen.erase = eraserTool || barrel
    }

    /**
     * The grain shader, or null when the brush has none.
     *
     * Anchored to the document, not to the scratch buffer — see
     * [GrainTexture.shaderFor]. That is what makes a stroke keep the texture it
     * was drawn with when the pen lifts.
     */
    private fun grainShader() = grain.shaderFor(pen.grain)

    /**
     * The alpha the scratch is put down at: the brush's opacity, or **1 while
     * erasing**.
     *
     * An eraser is not a pale brush. Everything that makes graphite look like
     * graphite — a 0.90 ceiling, a flow that starts at 0.02, a grain mask that
     * skips the pits — is a reason for the pencil to leave *less* ink, and
     * inheriting all three made a full-pressure wipe remove roughly a quarter
     * of what was under it. That is the "eraser is too soft" report. Erasing
     * takes the brush's shape and its size and none of its translucency.
     */
    private fun compositeAlpha(): Float = if (pen.erase) 1f else pen.opacity

    /** The grain, or none while erasing. See [compositeAlpha]. */
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
        rasterizer.solid = pen.erase
    }

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
                armRasterizer()
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
            recordRoundTrip()
        }

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<DabBatch>,
        ) {
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
        compositeStack(
            canvas, 0f, 0f, document.widthPx.toFloat(), document.heightPx.toFloat(),
        )
        recordComposite(System.nanoTime() - compositeStart)
        canvas.restoreToCount(save)
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
        get() = if (pen.erase) pen.eraseSizeMax else pen.sizeMax

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

        /**
         * The marquee this gesture is building, or null when it is drawing.
         *
         * Fixed at pen-down and never revisited, the same rule [eraseDecided]
         * follows: switching tools with the pen already on the glass must not
         * turn half a lasso into half a pencil line.
         */
        private var marquee: Marquee? = null

        private val marqueeBuilder = Marquee()

        private var marqueeMode = SelectMode.NEW
        private var marqueeModeDecided = false

        override fun onStrokeBegin(pointerId: Int) {
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
