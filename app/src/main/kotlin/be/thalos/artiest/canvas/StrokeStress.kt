package be.thalos.artiest.canvas

import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives a stroke through the real input path at the rate the real digitizer
 * uses, so W9's numbers describe the device rather than the harness.
 *
 * The reason this exists is a measurement that was not wrong so much as
 * unearned. W8 read a batch-pool peak of 1 and zero spills off strokes injected
 * with `adb shell input stylus swipe`, which delivers events at roughly 120 Hz
 * with one sample each. The DTH-A116's pen reports at **321.75 Hz against a
 * 90 Hz panel**, batched into one `MotionEvent` per frame — so the real path
 * sees a third as many events carrying three and a half samples apiece, and
 * every dab of a frame's worth of movement is submitted in one go. A pool
 * validated at the injected rate has not been validated.
 *
 * So the events are synthesized here instead: `MotionEvent.obtain` for the
 * frame's first sample, `addBatch` for the rest, dispatched through
 * `onTouchEvent` exactly as the framework would. Nothing downstream can tell
 * the difference — the router, `collectSamples`, the stabilizer, the resampler
 * and the pool all run.
 *
 * **Two things it cannot reproduce, stated so the numbers are not over-read.**
 * `MotionEvent.obtain` takes milliseconds, so `getEventTimeNanos` comes back at
 * millisecond granularity and a 3.108 ms sample interval arrives as alternating
 * 3 ms and 4 ms; that jitter is real input to the stabilizer, which integrates
 * over dt and absorbs it, but it is not what the hardware does. And injected
 * events are dispatched from the UI thread's `Choreographer` callback rather
 * than from the input dispatcher, so they miss whatever the framework's own
 * delivery costs — this measures the app's work per event, not end-to-end
 * latency. W16 films that at 240 fps.
 */
class StrokeStress(private val view: InkSurfaceView) {

    /** See [start]'s `path` parameter. */
    enum class Path { SPIRAL, ZIGZAG }

    var running: Boolean = false
        private set

    private val choreographer = Choreographer.getInstance()

    private val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            // MotionEvent's own constant, not :engine's mirror of it. The two
            // are equal and PlatformMirrorTest pins that, but the mirror exists
            // so :engine can route without an android.jar — using it here where
            // the real one is in scope buys nothing and lint refuses it.
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        },
    )

    // One reused PointerCoords for the current sample and one for each batched
    // sample. Reused because this class is instrumentation on the path it is
    // measuring: a PointerCoords per sample would be the largest allocation in
    // the run and would be attributed to the app.
    private val coords = arrayOf(MotionEvent.PointerCoords())

    private var downTimeMs = 0L
    private var sampleIndex = 0
    private var totalSamples = 0
    private var startNanos = 0L
    private var nextSampleNanos = 0L
    private var onDone: (() -> Unit)? = null
    private var constantPressure: Float? = null
    private var path: Path = Path.SPIRAL

    /** Scratch for the doc-to-view mapping. Reused; never escapes. */
    private val viewPoint = FloatArray(2)

    private val frame = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    /**
     * Draw [samples] samples' worth of stroke, then call [onDone] on the UI
     * thread.
     *
     * 1200 samples is about 3.7 seconds of drawing at the measured rate, which
     * is a long stroke by hand and enough events for a p99 to mean something.
     */
    fun start(
        samples: Int = DEFAULT_SAMPLES,
        /**
         * Constant pressure, or null to sweep the full range.
         *
         * The two are different measurements and neither alone is the answer.
         * Sweeping is the worst case by a wide margin, because `Brush`
         * spaces dabs at a fraction of the *diameter* and clamps the result at
         * `MIN_SPACING_DOC`: a feather-light dab is 0.5 doc px from its
         * neighbour where a full-press one is 3.0, so the same movement emits
         * six times the dabs. A constant firm press is what most of a real
         * stroke looks like, and the gap between the two runs is the evidence
         * that per-event cost tracks dabs rather than samples.
         */
        pressure: Float? = null,
        /**
         * The path shape.
         *
         * [Path.SPIRAL] turns continuously and gently — its tightest sweep is
         * about 6 rad/s, twenty times under `PredictionGate`'s threshold — so it
         * exercises the resampler and the pool but never the gate.
         * [Path.ZIGZAG] reverses direction outright, which is the case the gate
         * exists for and the case a predictor is worst at. Having only the
         * first would leave the gate measured by its unit tests alone.
         */
        path: Path = Path.SPIRAL,
        onDone: () -> Unit = {},
    ) {
        if (running) return
        running = true
        constantPressure = pressure
        this.path = path
        this.onDone = onDone
        this.totalSamples = samples
        sampleIndex = 0
        view.stats.reset()
        startNanos = System.nanoTime()
        nextSampleNanos = startNanos
        downTimeMs = startNanos / 1_000_000L
        dispatch(MotionEvent.ACTION_DOWN, startNanos, 1)
        sampleIndex = 1
        choreographer.postFrameCallback(frame)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return

        // How many samples the digitizer would have reported since the last
        // frame. Derived from the frame clock rather than from a fixed count,
        // so a run on a 60 Hz panel produces 4 samples per event and one on a
        // 90 Hz panel produces 3 or 4 — which is the difference the whole
        // rate-invariance argument is about.
        var due = 0
        while (nextSampleNanos <= frameTimeNanos && sampleIndex + due < totalSamples) {
            nextSampleNanos += SAMPLE_INTERVAL_NANOS
            due++
        }

        if (due > 0) {
            dispatch(MotionEvent.ACTION_MOVE, frameTimeNanos, due)
            sampleIndex += due
        }

        if (sampleIndex >= totalSamples) {
            dispatch(MotionEvent.ACTION_UP, frameTimeNanos, 1)
            running = false
            val done = onDone
            onDone = null
            done?.invoke()
            return
        }
        choreographer.postFrameCallback(frame)
    }

    /**
     * One event carrying [count] samples: `obtain` for the first, `addBatch`
     * for the rest, which is the shape the framework delivers and the shape
     * `collectSamples` is written against.
     *
     * The event is recycled here rather than left to the pool. Ownership is
     * unambiguous — nothing downstream retains a `MotionEvent`, that boundary
     * is the whole point of `PenSample` — and an un-recycled event per frame
     * would show up in the allocation figures this class exists to produce.
     */
    private fun dispatch(action: Int, frameTimeNanos: Long, count: Int) {
        val firstIndex = if (action == MotionEvent.ACTION_DOWN) 0 else sampleIndex
        fillCoords(firstIndex)
        val firstMs = downTimeMs + (elapsedNanosAt(firstIndex) / 1_000_000L)
        val event = MotionEvent.obtain(
            downTimeMs, firstMs, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
        for (k in 1 until count) {
            val i = firstIndex + k
            fillCoords(i)
            event.addBatch(downTimeMs + (elapsedNanosAt(i) / 1_000_000L), coords, 0)
        }
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun elapsedNanosAt(index: Int): Long = index * SAMPLE_INTERVAL_NANOS

    /**
     * The path: a spiral inside the page.
     *
     * Not a straight drag, and not because a curve looks better. A straight
     * line is the one shape where the Catmull-Rom fit does its least work and
     * where dab spacing never changes, so it flatters both the per-event timing
     * and the batch count. A spiral turns continuously, sweeps pressure across
     * its whole range, and therefore exercises the resampler's subdivision and
     * the pool's worst case — a fast, wide, heavily-dabbed stretch — in the
     * same run.
     */
    private fun fillCoords(index: Int) {
        val t = index.toFloat() / totalSamples
        val cx = view.document.widthPx * 0.5f
        val cy = view.document.heightPx * 0.5f
        val xDoc: Float
        val yDoc: Float
        if (path == Path.SPIRAL) {
            val angle = t * TURNS * TWO_PI
            val radius = 200f + t * 800f
            xDoc = cx + radius * cos(angle)
            yDoc = cy + radius * sin(angle)
        } else {
            // A triangle wave: constant speed along each leg and an
            // instantaneous reversal at every vertex, which is a turn rate
            // bounded only by the sample interval. Twelve legs over the run, so
            // a reversal lands every few frames rather than once.
            val leg = t * ZIGZAG_LEGS
            val phase = leg - leg.toInt()
            val up = leg.toInt() % 2 == 0
            xDoc = cx - 900f + t * 1800f
            yDoc = cy + (if (up) -700f + phase * 1400f else 700f - phase * 1400f)
        }
        view.transform.docToView(xDoc, yDoc, viewPoint)
        val c = coords[0]
        c.clear()
        c.x = viewPoint[0]
        c.y = viewPoint[1]
        // Pressure sweeps the full range and back, so Brush's curve, the
        // onset ramp and the spacing that follows from radius are all covered.
        c.pressure = constantPressure ?: (0.05f + 0.95f * (0.5f - 0.5f * cos(t * TWO_PI * 2f)))
        c.size = 1f
        // Tilt and orientation, added at W15 because their absence hid a
        // problem completely. Every stress run before this fed a tilt of
        // exactly zero, so every dab came out round -- and a round dab takes
        // the same path a pen takes. The pencil's defining input was the one
        // input the instrument could not produce, so the instrument said the
        // pencil was fast while the pen in a hand said it was unusable.
        //
        // The pen tilts and rolls as it travels, at rates a wrist actually
        // manages: the tilt sweeps most of its range twice over the run and the
        // barrel turns through a full circle.
        c.setAxisValue(MotionEvent.AXIS_TILT, 0.55f + 0.45f * sin(t * TWO_PI * 2f))
        c.setAxisValue(MotionEvent.AXIS_ORIENTATION, (t * 2f - 1f) * PI_F)
    }

    companion object {
        /** 321.75 Hz, the measured digitizer rate against a 90 Hz panel. */
        const val SAMPLE_INTERVAL_NANOS = 3_107_855L

        const val DEFAULT_SAMPLES = 1200

        private const val ZIGZAG_LEGS = 12f

        private const val TURNS = 3.5f
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        private const val PI_F = Math.PI.toFloat()
    }
}
