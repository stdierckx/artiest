package be.thalos.artiest.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.input.motionprediction.MotionEventPredictor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Path C — a plain `SurfaceHolder` on a dedicated render thread, drawn with
 * `lockHardwareCanvas`. This is W1's candidate, and Phase 1's render path if it
 * survives the A/B.
 *
 * W0 established that this device grants no front buffer at all, so the arm
 * that won the Phase 0 comparison was never front-buffering — it was a
 * dedicated SurfaceControl on its own render thread plus graphics-core's
 * unconditional 1000 fps frame-rate vote. Both are reproducible without the
 * library, and this view reproduces them: [frameRateVote] is the vote on its
 * own toggle, so W1 can tell the two apart instead of retiring or adopting a
 * design on a fused result.
 *
 * Everything it draws is deliberately identical to [LowLatencyInkView] —
 * [InkPaints.stroke], pressure-driven width, black ink, one `drawLine` per
 * sample — so the two arms differ only in how pixels reach the screen.
 *
 * The model is different from the front-buffered one, and simpler:
 * `lockHardwareCanvas` guarantees nothing about buffer contents between frames,
 * so the whole surface is redrawn every frame from a layer [Bitmap] plus the
 * wet stroke's points. The stroke lands in the layer exactly once, at pen-up, on
 * the render thread — which is what makes ACTION_CANCEL free and prediction
 * retractable.
 */
class DirectSurfaceInkView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var capture: PenCapture? = null

    /** The same toggle [LowLatencyInkView] carries, so the Predict chip means the same thing on both arms. */
    var predictionEnabled: Boolean = true

    /**
     * graphics-core votes `setFrameRate(1000f, …)` on its layer unconditionally,
     * and that vote is the leading hypothesis for the win it was credited with.
     * Separating it from "dedicated surface on its own thread" is the whole
     * reason W1 exists, so it is a toggle rather than something baked in.
     */
    var frameRateVote: Boolean = true
        set(value) {
            field = value
            applyFrameRateVote()
        }

    /**
     * The red cursor and the frame counter exist for the 240 fps camera, not for
     * the eye. They are off by default because the graphics-core arm physically
     * cannot draw a transient cursor into an accumulating layer, so the by-eye
     * comparison has to be run with the instruments the weakest arm can support.
     */
    @Volatile var videoInstruments: Boolean = false

    private val strokePaint = InkPaints.stroke()
    private val counterPaint = InkPaints.counter()
    private val cursorPaint = InkPaints.cursor()

    /**
     * Never a null Paint on the layer blit: null means point sampling, which
     * makes fine ink crawl and shimmer at any scale other than exactly 1.0 —
     * and W1 would then be judging resampling artifacts.
     */
    private val filterPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /**
     * Only ever drawn under Fit, where the portrait document is a narrow band in
     * the middle of a landscape view and everything outside it is dropped at
     * pen-up. Without the outline that reads as ink randomly vanishing, and at
     * 1:1 the judged arm must have nothing on screen the other two lack.
     */
    private val docBoundsPaint = InkPaints.cursor().apply { color = Color.LTGRAY }

    /**
     * Reused per event, and expanded here as well as in [PenCapture] because
     * [LowLatencyInkView] pays exactly the same double expansion. Undercutting
     * the arm under comparison on UI-thread cost would hand this one an
     * advantage that has nothing to do with its render path.
     */
    private val scratch = ArrayList<PenSample>(32)
    private var predictor: MotionEventPredictor? = null

    // Deliberately NO setBackgroundColor here, for the reason spelled out in
    // LowLatencyInkView: the view's background is painted over the transparent
    // hole the SurfaceView shows its surface through, so an opaque one hides
    // every frame this class draws while logcat stays perfectly healthy. The
    // white ground comes from drawColor in the frame body.

    init {
        // SurfaceView asks for RGB_565 by default. graphics-core's layer is
        // RGBA_8888, and 16-bit antialiased black ink looks visibly worse, so
        // without this the eye test compares ink quality rather than latency.
        // PixelFormat.OPAQUE would not do: SurfaceView maps it back to RGB_565.
        holder.setFormat(PixelFormat.RGBA_8888)
        holder.addCallback(this)
    }

    // ---- UI thread -> render thread ---------------------------------------
    //
    // A single-producer/single-consumer ring of primitives. The UI thread only
    // ever appends records it never revisits, and the render thread drains them
    // into a wet list only it touches, so there is no shared mutable point list
    // to tear: the classic failure is ACTION_DOWN resetting a shared count to
    // zero while the render thread is midway through reading the previous
    // stroke's coordinates out of the same slots, which shows up as a spurious
    // line between two strokes maybe once a minute and gets blamed on the pen.

    private val ringX = FloatArray(RING_CAP)
    private val ringY = FloatArray(RING_CAP)
    private val ringP = FloatArray(RING_CAP)
    private val ringOp = IntArray(RING_CAP)

    /** Written only by the UI thread; its volatile write publishes the four array writes. */
    private val head = AtomicInteger(0)

    /** Written only by the render thread. */
    private val tail = AtomicInteger(0)

    private val frameScheduled = AtomicBoolean(false)

    @Volatile private var surfaceValid = false
    @Volatile private var cursorX = -1f
    @Volatile private var cursorY = -1f

    private var surfaceW = 0
    private var surfaceH = 0

    // The transform is three volatile scalars rather than a shared Matrix
    // because the UI thread writes it (surfaceChanged, the Fit chip) while the
    // render thread reads it, and a Matrix mutated under a concat is a race.
    // The render thread rebuilds its own Matrix from these each frame, which
    // allocates nothing.
    @Volatile private var docScale = 1f
    @Volatile private var docTx = 0f
    @Volatile private var docTy = 0f

    /**
     * Scale 1.0 keeps the ink pixel-identical in width and sharpness to the
     * other two arms, so the by-eye verdict is not contaminated by minification
     * softening. Fit is W2's instrument: it is the same frame body through a
     * non-trivial matrix, which is what tells you whether the blit is the cost.
     */
    @Volatile var fitToView: Boolean = false
        set(value) {
            field = value
            recomputeTransform()
            requestFrame()
        }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Obtained on the render thread, because Choreographer.getInstance() is per-thread. */
    @Volatile private var choreo: Choreographer? = null

    // ---- render thread only ------------------------------------------------

    /**
     * The document is Phase 1's real 2160x3300, not the surface size. The blit
     * is destination-bound — its cost scales with the ~2.5 Mpx surface, not with
     * the 7.1 Mpx source — so the honest size costs 27 MiB of residency against
     * 4.4 GiB free and buys the real texture-sampling pattern for free.
     */
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null

    private val wetX = FloatArray(MAX_WET)
    private val wetY = FloatArray(MAX_WET)
    private val wetP = FloatArray(MAX_WET)
    private var wetN = 0

    private var predX = 0f
    private var predY = 0f
    private var predP = 0f
    private var hasPred = false

    private val renderMatrix = Matrix()
    private var frames = 0L
    private var lastFrameNs = 0L
    private var frameMsAvg = 0f

    /** One instance, held in a field: a lambda per post would allocate 90/s and could never be removed. */
    private val frameCallback = Choreographer.FrameCallback { t -> onFrame(t) }

    /** Bypasses the Choreographer so the first pixels land before the first vsync rather than up to 11 ms after it. */
    private val drawOnce = Runnable { onFrame(0L) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Allocated on attach and not in surfaceChanged: document size is
        // independent of surface size, so there is never a window in which the
        // layer is null and rotation changes only the matrix. The one-time
        // zero-fill costs a few milliseconds during the tab switch.
        val bitmap = Bitmap.createBitmap(DOC_W, DOC_H, Bitmap.Config.ARGB_8888)
        layer = bitmap
        layerCanvas = Canvas(bitmap)
        predictor = MotionEventPredictor.newInstance(this)

        val t = HandlerThread("artiest-direct-render", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h

        // The thread owns the Choreographer so that a sample landing costs one
        // wake-up to pixels, and the moment the frame starts is set by
        // SurfaceFlinger rather than by whatever else is queued on the main
        // Looper — Compose recomposition, the next MotionEvent, the harness's
        // own polling loops. Grabbed synchronously so no touch can race it.
        val ready = CountDownLatch(1)
        h.post {
            choreo = Choreographer.getInstance()
            ready.countDown()
        }
        ready.await(1, TimeUnit.SECONDS)
    }

    override fun onDetachedFromWindow() {
        // The thread is torn down here rather than in surfaceDestroyed so that
        // surface create/destroy cycles do not thrash it.
        //
        // surfaceValid goes first, before the join. Otherwise a frame callback
        // already queued runs one more full lock/draw/post during teardown, and
        // if it outlives the bounded join it reads a recycled Bitmap and kills
        // the process from the render thread with no usable stack.
        surfaceValid = false
        thread?.quitSafely()
        thread?.join(1000)
        val stopped = thread?.isAlive != true
        thread = null
        handler = null
        choreo = null
        predictor = null
        layerCanvas = null
        // Only recycle once the render thread is provably gone. A leaked bitmap
        // on a wedged teardown is a far better outcome than a use-after-recycle.
        if (stopped) layer?.recycle()
        layer = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceValid = true
        applyFrameRateVote()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        recomputeTransform()
        // The vote rides the ANativeWindow, so it dies with the layer and has to
        // be re-cast whenever a new one arrives. graphics-core re-casts it on
        // every surfaceChanged for the same reason.
        applyFrameRateVote()

        // Once per buffer in the swap chain: every buffer has to be painted at
        // least once, because each frame is a full redraw only for as long as
        // nobody adds the dirty-region optimisation W2 floats. Posted directly
        // rather than through requestFrame so pixels land before the first vsync.
        val h = handler ?: return
        h.post(drawOnce)
        h.post(drawOnce)
        h.post(drawOnce)
    }

    /**
     * The Surface is destroyed in this same UI-thread call stack the moment this
     * returns, so the obligation is to have stopped touching it by then. An
     * in-flight frame is already covered by SurfaceView's own lock, which the UI
     * thread takes before dispatching this; what is not covered is the queued
     * backlog, which at 320 Hz can be substantial. Dropping it and then waiting
     * on a barrier is what actually discharges the contract.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValid = false
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        val drained = CountDownLatch(1)
        // A false return means the looper is already gone, which happens when
        // the view is detached before the surface goes; waiting on a barrier
        // nobody will run would stall the UI thread for the whole timeout.
        val posted = h.post {
            choreo?.removeFrameCallback(frameCallback)
            frameScheduled.set(false)
            drained.countDown()
        }
        if (!posted) return
        // Bounded, because a render thread wedged for a whole second is a bug
        // worth seeing as a glitch rather than as a permanent ANR.
        drained.await(1, TimeUnit.SECONDS)
    }

    private fun recomputeTransform() {
        val w = surfaceW
        val h = surfaceH
        if (w == 0 || h == 0) return
        val scale = if (fitToView) minOf(w / DOC_W.toFloat(), h / DOC_H.toFloat()) else 1f
        docScale = scale
        // Snapped to whole pixels. At 1:1 the vertical centring lands on .5
        // (surface 1181 vs document 3300), and blitting through a half-pixel
        // translate with isFilterBitmap resamples every dry pixel — this arm
        // would read as softer than graphics-core for a reason that has nothing
        // to do with latency, which is exactly the confound W1 must not have.
        docTx = floor((w - DOC_W * scale) / 2f)
        docTy = floor((h - DOC_H * scale) / 2f)
    }

    /**
     * 1000 fps with FRAME_RATE_COMPATIBILITY_DEFAULT, which is bit-for-bit what
     * graphics-core asks for — the two-argument overload already means
     * CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS. CHANGE_FRAME_RATE_ALWAYS would be a
     * stronger request than the arm this one is being compared against, and
     * authorises a visibly interrupting mode switch. A rate of 0 withdraws the
     * vote, which is what the toggle needs to mean off.
     */
    private fun applyFrameRateVote() {
        if (!surfaceValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            holder.surface.setFrameRate(
                if (frameRateVote) 1000f else 0f,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        } catch (e: IllegalStateException) {
            // The Surface was released between the callback and this call.
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        capture?.onMotionEvent(event, this)
        predictor?.record(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> push(OP_DOWN, event.x, event.y, event.pressure)

            MotionEvent.ACTION_MOVE -> {
                scratch.clear()
                event.collectSamples(scratch)
                for (s in scratch) push(OP_MOVE, s.x, s.y, s.pressure)

                if (predictionEnabled) {
                    // Unlike the front-buffered arm, a full redraw can take a
                    // prediction back: only the newest predicted point is ever
                    // drawn and it never reaches the layer, so overshoot shows
                    // as a tip that leads rather than as accumulated ghosts.
                    // Not recycled, for the reason given in LowLatencyInkView.
                    predictor?.predict()?.let { p -> push(OP_PREDICT, p.x, p.y, p.pressure) }
                }
            }

            // The layer only receives the stroke here, so cancellation is one
            // discarded list and the next frame simply does not draw it.
            MotionEvent.ACTION_UP -> push(OP_UP, event.x, event.y, event.pressure)
            MotionEvent.ACTION_CANCEL -> push(OP_CANCEL, 0f, 0f, 0f)
        }

        cursorX = event.x
        cursorY = event.y
        requestFrame()
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        capture?.onHoverEvent(event, this)
        return super.onHoverEvent(event)
    }

    fun clear() {
        push(OP_CLEAR, 0f, 0f, 0f)
        requestFrame()
    }

    /** Coordinates cross the ring in document space, so wet ink and the layer never disagree about where the stroke is. */
    private fun push(op: Int, viewX: Float, viewY: Float, pressure: Float) {
        val scale = docScale
        val h = head.get()
        if (h - tail.get() >= RING_CAP) return
        val i = h and (RING_CAP - 1)
        ringX[i] = (viewX - docTx) / scale
        ringY[i] = (viewY - docTy) / scale
        ringP[i] = pressure
        ringOp[i] = op
        head.set(h + 1)
    }

    /**
     * One frame per vsync however fast the pen reports. Choreographer does not
     * de-duplicate callbacks, so without this gate 320 Hz of input posts 320
     * callbacks per frame and runs the frame body every one of them.
     */
    private fun requestFrame() {
        // Read the Choreographer first: claiming the gate and then failing to
        // post would latch it closed and stop the arm dead.
        val c = choreo ?: return
        if (frameScheduled.compareAndSet(false, true)) c.postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        // Cleared before draining, so a sample arriving during the frame body
        // schedules the next one. Clearing afterwards costs a stalled stroke
        // that only unsticks when the next sample happens to land.
        frameScheduled.set(false)
        drain()
        if (!surfaceValid) return

        if (frameTimeNanos != 0L) {
            if (lastFrameNs != 0L) {
                val dt = (frameTimeNanos - lastFrameNs) / 1e6f
                frameMsAvg += (dt - frameMsAvg) * 0.05f
            }
            lastFrameNs = frameTimeNanos
        }

        // SurfaceHolder swallows the exception a released Surface raises and
        // returns null, so this is the shape that matters — and each null costs
        // up to 100 ms of sleep inside the call, which is why the queue is
        // emptied in surfaceDestroyed rather than being allowed to spin.
        val canvas = holder.lockHardwareCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            val save = canvas.save()
            renderMatrix.setScale(docScale, docScale)
            renderMatrix.postTranslate(docTx, docTy)
            canvas.concat(renderMatrix)
            layer?.let { canvas.drawBitmap(it, 0f, 0f, filterPaint) }
            // Always, not only under Fit. At 1:1 the 2160-wide document does not
            // reach the edges of a 2200-wide view, and ink drawn in that band is
            // wet-visible but discarded at pen-up. Better to show the judge where
            // the paper actually ends than to let a stroke vanish on this arm and
            // survive on the other two.
            run {
                docBoundsPaint.strokeWidth = 2f / docScale
                canvas.drawRect(0f, 0f, DOC_W.toFloat(), DOC_H.toFloat(), docBoundsPaint)
            }
            drawWet(canvas)
            canvas.restoreToCount(save)

            frames++
            if (videoInstruments) {
                if (cursorX >= 0f) canvas.drawCircle(cursorX, cursorY, 18f, cursorPaint)
                InkPaints.drawFrameCounter(canvas, frames, counterPaint)
                // The one number that says whether this loop is really coalescing:
                // ~11.1 ms at 90 Hz or ~16.7 at 60 is the pass, sub-millisecond
                // means the gate is broken and it is drawing per sample, and a
                // stable multiple means frames are overrunning the budget.
                canvas.drawText("%.2f ms".format(frameMsAvg), 24f, 220f, counterPaint)
            }
        } finally {
            // Not optional: an exception escaping between the lock and this call
            // leaves SurfaceView's internal lock held forever, and the next
            // relayout deadlocks the UI thread with no crash and no stack trace.
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drain() {
        var t = tail.get()
        val h = head.get()
        while (t != h) {
            val i = t and (RING_CAP - 1)
            when (ringOp[i]) {
                OP_DOWN -> {
                    wetN = 0
                    hasPred = false
                    appendWet(ringX[i], ringY[i], ringP[i])
                }
                OP_MOVE -> {
                    hasPred = false
                    appendWet(ringX[i], ringY[i], ringP[i])
                }
                OP_PREDICT -> {
                    predX = ringX[i]
                    predY = ringY[i]
                    predP = ringP[i]
                    hasPred = true
                }
                // Stamping and resetting inside the same frame that consumes the
                // pen-up is what makes the commit flicker-free: no frame can show
                // the stroke twice, and none can show it in neither place.
                OP_UP -> {
                    stampWetIntoLayer()
                    wetN = 0
                    hasPred = false
                }
                OP_CANCEL -> {
                    wetN = 0
                    hasPred = false
                }
                OP_CLEAR -> {
                    layerCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    wetN = 0
                    hasPred = false
                }
            }
            t++
        }
        tail.set(t)
    }

    private fun appendWet(x: Float, y: Float, pressure: Float) {
        // A stroke of 16384 samples is about 50 seconds of continuous drag; past
        // that the tail is dropped rather than growing the array on the render
        // thread mid-frame.
        if (wetN >= MAX_WET) return
        wetX[wetN] = x
        wetY[wetN] = y
        wetP[wetN] = pressure
        wetN++
    }

    private fun drawWet(canvas: Canvas) {
        strokePaint.color = Color.BLACK
        for (i in 1 until wetN) {
            strokePaint.strokeWidth = docWidthFor(wetP[i])
            canvas.drawLine(wetX[i - 1], wetY[i - 1], wetX[i], wetY[i], strokePaint)
        }
        if (hasPred && wetN > 0) {
            strokePaint.color = Color.argb(90, 0, 0, 0)
            strokePaint.strokeWidth = docWidthFor(predP)
            canvas.drawLine(wetX[wetN - 1], wetY[wetN - 1], predX, predY, strokePaint)
        }
    }

    private fun stampWetIntoLayer() {
        val c = layerCanvas ?: return
        strokePaint.color = Color.BLACK
        for (i in 1 until wetN) {
            strokePaint.strokeWidth = docWidthFor(wetP[i])
            c.drawLine(wetX[i - 1], wetY[i - 1], wetX[i], wetY[i], strokePaint)
        }
    }

    /**
     * Document-space width, so the ink lands on screen at exactly the width the
     * other two arms draw. Without the division the Fit transform would draw
     * visibly thinner ink and the eye would start comparing line weight.
     */
    private fun docWidthFor(pressure: Float): Float = InkPaints.widthFor(pressure) / docScale
}

private const val DOC_W = 2160
private const val DOC_H = 3300

/** A power of two so the index masks, and ~13 seconds of backlog at 320 Hz. */
private const val RING_CAP = 4096
private const val MAX_WET = 16384

private const val OP_DOWN = 0
private const val OP_MOVE = 1
private const val OP_PREDICT = 2
private const val OP_UP = 3
private const val OP_CANCEL = 4
private const val OP_CLEAR = 5
