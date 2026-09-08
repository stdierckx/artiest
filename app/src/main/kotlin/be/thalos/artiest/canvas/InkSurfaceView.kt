package be.thalos.artiest.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import be.thalos.artiest.doc.Document
import be.thalos.artiest.engine.ink.RoundPen
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.xform.CanvasTransform
import be.thalos.artiest.ink.DabRasterizer
import be.thalos.artiest.input.InkInputSink
import be.thalos.artiest.input.InputRouter
import java.util.concurrent.atomic.AtomicReference

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
     * A `val` holding a mutable `RoundPen` rather than a reassignable field:
     * `StrokeBuilder` captures the instance at construction, so replacing it
     * here would leave the builder drawing with the old one and nothing would
     * fail. `RoundPen`'s own settings are `var`, so W15's sliders move those.
     */
    val pen: RoundPen = RoundPen()

    /** Ink colour. W15 gives it a swatch; until then it is black. */
    var inkColorArgb: Int = Color.BLACK

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
     * The finished stroke, waiting for the render thread to stamp it into the
     * layer.
     *
     * `getAndSet(null)` consumes it exactly once, so a dropped or duplicated
     * frame cannot double-stamp — and the volatile write/read pair is also the
     * memory edge that publishes every dab in it.
     */
    private val pendingStroke = AtomicReference<Stroke?>(null)

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

    /** Set by [clear]; consumed on the render thread. */
    @Volatile
    private var clearRequested: Boolean = false

    /** The open stroke's frozen paint settings, stamped onto every batch. */
    private var strokeColorArgb: Int = Color.BLACK
    private var strokeAntiAlias: Boolean = true

    private var renderer: CanvasFrontBufferedRenderer<DabBatch>? = null

    /** True between `surfaceCreated` and `surfaceDestroyed`. UI thread. */
    private var surfaceAlive = false

    // --- render thread only, below this line -------------------------------

    private val rasterizer = DabRasterizer(document.widthPx, document.heightPx)

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
            if (m != null) rasterizer.drawWet(canvas, m, param)
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
        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<DabBatch>,
        ) {
            if (clearRequested) {
                clearRequested = false
                document.layer.blank()
            }

            val stroke = pendingStroke.getAndSet(null)
            if (stroke != null) {
                document.layer.write { rasterizer.drawDry(it, stroke) }
            }
            batches.markDrawn(commitWatermark)

            canvas.drawColor(document.paperColor)

            val t = transform
            if (t !== dryMatrixSource) {
                dryMatrix.setDocToView(t)
                dryMatrixSource = t
            }
            val save = canvas.save()
            canvas.concat(dryMatrix)
            document.layer.read { canvas.drawBitmap(it, 0f, 0f, blitPaint) }
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
                transform = CanvasTransform.fitTo(width, height, document.widthPx, document.heightPx)
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
            router.abandon()
        }
    }

    private val driver = StrokeDriver()

    private val router = InputRouter(driver)

    // Deliberately no setBackgroundColor. See the class header.

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = CanvasFrontBufferedRenderer(this, callback)
        holder.addCallback(holderCallback)
    }

    override fun onDetachedFromWindow() {
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
        r.renderFrontBufferedLayer(batch)
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
    override fun commitStroke(stroke: Stroke) {
        commitWatermark = batches.issuedCount
        pendingStroke.set(stroke)
        val r = renderer
        if (r == null || !surfaceAlive) {
            pendingStroke.set(null)
            return
        }
        r.commit()
    }

    override fun cancelStroke() {
        commitWatermark = batches.issuedCount
        renderer?.cancel()
    }

    override fun redrawDry() {
        val r = renderer ?: return
        if (!surfaceAlive) return
        r.renderMultiBufferedLayer(emptyList())
    }

    override fun release() {
        renderer?.release(true)
        renderer = null
        frozenDocToView = null
        pendingStroke.set(null)
    }

    /**
     * Blank the document and redraw.
     *
     * Two halves on two threads, which is why `Document.forgetStrokes` and
     * `Layer.blank` are not one call: the history is the UI thread's and the
     * pixels are the render thread's. The flag carries the second half across.
     */
    fun clear() {
        router.abandon()
        document.forgetStrokes()
        clearRequested = true
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
        router.abandon()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = router.onTouchEvent(event, this)

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

        override fun onStrokeBegin(pointerId: Int) {
            emitted = 0
            seen = 0
            frozen = transform
            builder.begin(inkColorArgb)
            // A fresh Matrix per pen-down, never reused: see beginStroke.
            beginStroke(docToViewMatrix(frozen), inkColorArgb, pen.antiAlias)
        }

        /**
         * The pipeline's `toDoc` step, and it is not optional.
         *
         * A `PenSample` carries what `MotionEvent` reported, which is **view**
         * coordinates. Everything downstream — the stabilizer's time constant,
         * the resampler's arc-length spacing, `RoundPen`'s radii, the layer
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
            var i = 0
            while (i < n) {
                val s = samples[i]
                frozen.viewToDoc(s.x, s.y, docPoint)
                builder.add(docPoint[0], docPoint[1], s.pressure, s.eventTimeNanos)
                i++
            }
            flushWet()
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
                b.add(builder.x(emitted), builder.y(emitted), builder.radius(emitted))
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
            val stroke = builder.end()
            flushWet()
            lastStrokeSamples = seen
            lastStrokeDabs = stroke.dabCount
            if (stroke.dabCount > 0) document.recordStroke(stroke.bounds)
            commitStroke(stroke)
        }

        override fun onStrokeCancel() {
            builder.cancel()
            emitted = 0
            cancelStroke()
        }

        // W12 owns the gesture path. Until then a gesture is routed, counted by
        // the exclusivity machine — which is what keeps a palm from drawing —
        // and moves nothing.
        override fun onGestureBegin() = Unit

        override fun onGesturePointers(
            ids: IntArray,
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
        ) = Unit

        override fun onGestureEnd() = Unit

        override fun onGestureCancel() = Unit

        override fun onPenPresence(inRange: Boolean) = Unit
    }
}
