package be.thalos.artiest.canvas

import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * A two-finger pinch, twist and drag, synthesized through the real input path.
 *
 * `adb shell input` is single-touch, so there is no way to drive a gesture from
 * a shell — and a gesture is the one thing in Phase 1 that *cannot* be checked
 * by drawing with an injected stylus. This builds the multi-pointer
 * `MotionEvent` sequence the framework would deliver (DOWN, POINTER_DOWN, a run
 * of MOVEs, POINTER_UP, UP) and dispatches it through `onTouchEvent`, so
 * `InputRouter`, `StrokeExclusivity`, `GestureSolver` and
 * `GestureController`'s coalescing all run for real.
 *
 * Fingers, not a stylus, and that matters: the exclusivity rule is that a
 * gesture takes **two** fingers, so a single-pointer version of this would be
 * correctly ignored and prove nothing.
 *
 * The same two caveats `StrokeStress` carries apply — millisecond event times
 * and dispatch from the UI thread rather than from the input dispatcher — and
 * neither touches what this is for.
 */
class GestureStress(private val view: InkSurfaceView) {

    var running: Boolean = false
        private set

    private val choreographer = Choreographer.getInstance()

    private val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        },
        MotionEvent.PointerProperties().apply {
            id = 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        },
    )
    private val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())

    private var downTimeMs = 0L
    private var step = 0
    private var steps = 0
    private var onDone: (() -> Unit)? = null

    private val frame = Choreographer.FrameCallback { onFrame() }

    /**
     * Run a gesture over [steps] frames: the fingers spread to twice their
     * span, twist 40 degrees and travel 300 view px, all at once.
     *
     * Everything at once on purpose. Pan, zoom and rotation are computed from
     * one similarity and a solver can get any two of them right while the third
     * fights them; separating them into three clean gestures is exactly the
     * test that would not notice.
     */
    fun start(steps: Int = DEFAULT_STEPS, onDone: () -> Unit = {}) {
        if (running) return
        running = true
        this.steps = steps
        this.onDone = onDone
        step = 0
        downTimeMs = System.nanoTime() / 1_000_000L

        fill(0f)
        dispatch(MotionEvent.ACTION_DOWN, 1)
        dispatch(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
        )
        choreographer.postFrameCallback(frame)
    }

    private fun onFrame() {
        if (!running) return
        step++
        // Several moves per frame, which is the case coalescing exists for.
        // The framework batches touch to one event a frame by default, so a
        // one-move-a-frame gesture would exercise nothing;
        // `requestUnbufferedDispatch` unbatches it, and the plan keeps that
        // toggle. At MOVES_PER_FRAME the readout's updates-to-frames ratio is
        // the coalescing, visible.
        for (k in 1..MOVES_PER_FRAME) {
            val sub = (step - 1 + k.toFloat() / MOVES_PER_FRAME) / steps
            fill(sub)
            dispatch(MotionEvent.ACTION_MOVE, 2)
        }
        val t = step.toFloat() / steps
        fill(t)
        if (step >= steps) {
            dispatch(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
            )
            dispatch(MotionEvent.ACTION_UP, 1)
            running = false
            val done = onDone
            onDone = null
            done?.invoke()
            return
        }
        choreographer.postFrameCallback(frame)
    }

    private fun fill(t: Float) {
        val cx = view.width * 0.5f + t * 300f
        val cy = view.height * 0.5f + t * 120f
        val half = 150f + t * 150f
        val angle = t * TWIST_RAD
        val dx = half * cos(angle)
        val dy = half * sin(angle)
        coords[0].clear()
        coords[0].x = cx - dx
        coords[0].y = cy - dy
        coords[0].pressure = 1f
        coords[0].size = 1f
        coords[1].clear()
        coords[1].x = cx + dx
        coords[1].y = cy + dy
        coords[1].pressure = 1f
        coords[1].size = 1f
    }

    private fun dispatch(action: Int, pointerCount: Int) {
        val now = System.nanoTime() / 1_000_000L
        val event = MotionEvent.obtain(
            downTimeMs, now, action, pointerCount, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        /** 90 frames: a second and a half at 60 Hz, which is a real gesture. */
        const val DEFAULT_STEPS = 90

        /** 40 degrees of twist, comfortably past the solver's dead zone. */
        private const val TWIST_RAD = 0.698f

        /** Pointer updates per frame, as unbuffered dispatch would deliver. */
        private const val MOVES_PER_FRAME = 4
    }
}
