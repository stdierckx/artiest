package eu.torqa.artiest.spike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Path B — front-buffered rendering, the optimisation under test.
 *
 * ---------------------------------------------------------------------------
 * THIS IS THE ONE FILE IN THE SPIKE WRITTEN AGAINST AN API I COULD NOT COMPILE
 * AGAINST. If `CanvasFrontBufferedRenderer.Callback`'s method signatures or
 * `MotionEventPredictor.predict()`'s arity have shifted, fix them here — check
 * the javadoc that Android Studio shows on the symbol. Nothing else in the
 * spike depends on this file: telemetry, the device probe and the baseline ink
 * path all build and measure without it.
 * ---------------------------------------------------------------------------
 *
 * Canvas rather than GL on purpose. Phase 0 is isolating the *latency* question
 * from the *GL correctness* question; the real stamp engine arrives in Phase 2.
 */
class LowLatencyInkView(context: Context) : SurfaceView(context) {

    /** One line segment. Kept tiny — the renderer streams hundreds of these. */
    private class Segment(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val pressure: Float,
        val predicted: Boolean,
    )

    var capture: PenCapture? = null

    /** Toggle to measure what prediction is actually worth on this hardware. */
    var predictionEnabled: Boolean = true

    private val committed = ArrayList<Segment>(4096)
    private val strokePaint = InkPaints.stroke()
    private val counterPaint = InkPaints.counter()

    /** Reused per event: allocating on the hot path would perturb the measurement. */
    private val scratch = ArrayList<PenSample>(32)

    private var lastX = 0f
    private var lastY = 0f
    private var frames = 0L

    private var predictor: MotionEventPredictor? = null

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Segment> {

        /**
         * Wet ink. Runs per sample and draws only the new segment straight into
         * the front buffer, skipping the compositor. Keep this cheap — every
         * millisecond here lands directly in the number being measured.
         */
        override fun onDrawFrontBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            param: Segment,
        ) {
            drawSegment(canvas, param)
        }

        /**
         * Dry ink. Runs on commit (pen up) and redraws the whole scene into the
         * double-buffered layer. In the real engine this is where the stroke
         * gets composited into the layer texture exactly once.
         */
        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<Segment>,
        ) {
            canvas.drawColor(Color.WHITE)
            // Predicted segments are speculative — drop them on commit and keep
            // only what the pen actually reported.
            params.filterNotTo(committed) { it.predicted }
            for (s in committed) drawSegment(canvas, s)
            frames++
            InkPaints.drawFrameCounter(canvas, frames, counterPaint)
        }
    }

    private var renderer: CanvasFrontBufferedRenderer<Segment>? = null

    init {
        setBackgroundColor(Color.WHITE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = CanvasFrontBufferedRenderer(this, callback)
        predictor = MotionEventPredictor.newInstance(this)
    }

    override fun onDetachedFromWindow() {
        renderer?.release(true)
        renderer = null
        predictor = null
        super.onDetachedFromWindow()
    }

    private fun drawSegment(canvas: Canvas, s: Segment) {
        strokePaint.strokeWidth = InkPaints.widthFor(s.pressure)
        strokePaint.color = if (s.predicted) Color.argb(90, 0, 0, 0) else Color.BLACK
        canvas.drawLine(s.x0, s.y0, s.x1, s.y1, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        capture?.onMotionEvent(event, this)
        predictor?.record(event)

        val r = renderer ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                scratch.clear()
                event.collectSamples(scratch)
                for (s in scratch) {
                    r.renderFrontBufferedLayer(
                        Segment(lastX, lastY, s.x, s.y, s.pressure, predicted = false),
                    )
                    lastX = s.x
                    lastY = s.y
                }

                if (predictionEnabled) {
                    // Predicted points are rendered as temporary ink only. They
                    // are never committed, and over-prediction shows up as
                    // overshoot on direction changes — worth watching for.
                    // Not recycled deliberately: ownership of the event returned
                    // by predict() has varied across library versions, and a double
                    // recycle crashes where a missed one merely churns.
                    predictor?.predict()?.let { predicted ->
                        r.renderFrontBufferedLayer(
                            Segment(
                                lastX, lastY, predicted.x, predicted.y,
                                predicted.pressure, predicted = true,
                            ),
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> r.commit()
            MotionEvent.ACTION_CANCEL -> r.cancel()
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        capture?.onHoverEvent(event, this)
        return super.onHoverEvent(event)
    }

    fun clear() {
        committed.clear()
        renderer?.commit()
    }
}
