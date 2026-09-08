package be.thalos.artiest.canvas

import android.view.Choreographer
import be.thalos.artiest.engine.xform.GestureSolver

/**
 * Turns the router's gesture callbacks into canvas movement, at a rate the
 * render thread can actually keep up with.
 *
 * The arithmetic is `GestureSolver`'s, in `:engine`, where a test can drive it
 * without a device. What is left here is the part that cannot be tested that
 * way and is the part that goes wrong: **when to redraw**.
 *
 * **One redraw per frame, and at most one in flight.** The pointer callbacks
 * arrive at the digitizer's rate — up to 320 a second — and a
 * `renderMultiBufferedLayer` is a full `SurfaceControl` transaction, so
 * redrawing per callback would commit three to five transactions per frame and
 * spend the rest of the time queued behind itself. Coalescing onto
 * `Choreographer` fixes the first half of that. It does not fix the second: the
 * frame clock does not know what the render thread is doing, so a render that
 * outlasts a frame would still be followed immediately by another. The
 * library's `onMultiBufferedLayerRenderComplete` is the missing half, and
 * `InkSurfaceView.dryRenderInFlight` carries it here.
 *
 * **This is not the coalescing the plan forbids.** Wet ink is submitted
 * straight from `onTouchEvent` with nothing waiting for vsync, because W1
 * measured that as where the feel comes from. A gesture has no wet ink by
 * construction — `StrokeExclusivity` will not let a pen stroke and a gesture
 * overlap — so the only thing moving here is the dry layer, and the dry layer
 * is a frame-rate thing.
 */
class GestureController(private val surface: InkSurfaceView) {

    private val solver = GestureSolver()
    private val choreographer = Choreographer.getInstance()

    private var framePending = false
    private var waitingSinceNanos = 0L

    /** Frames the gesture asked for, and frames skipped for a busy renderer. */
    var framesDrawn: Long = 0L
        private set

    /** See [framesDrawn]. */
    var framesSkipped: Long = 0L
        private set

    /** Pointer callbacks folded into the transform. */
    var updates: Long = 0L
        private set

    private val frame = Choreographer.FrameCallback { onFrame() }

    fun begin() {
        // The user has moved the canvas themselves, so it stops following the
        // window. A rotation must not throw away a pan and zoom someone set up
        // on purpose; until there is a gesture there is nothing to throw away.
        surface.fitOnResize = false
        solver.begin(surface.transform)
    }

    fun pointers(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) {
        if (!solver.active) return
        updates++
        val next = solver.update(ids, xs, ys, count)
        if (next === surface.transform) return
        // Through requestTransform rather than assigned: the frozen-transform
        // rule is enforced there, and exclusivity means this call cannot be
        // refused today. That it cannot be refused is a property of another
        // class, which is exactly the kind of thing that stops being true.
        surface.requestTransform(next)
        requestRedraw()
    }

    fun end() {
        solver.end()
        // One last frame, unconditionally. The gesture's final pointer update
        // may have arrived in a frame that was skipped for a busy renderer, and
        // ending without it leaves the canvas one update behind where the
        // fingers left it — small, permanent, and exactly the kind of thing
        // that reads as the gesture not quite landing.
        requestRedraw()
    }

    fun cancel() {
        solver.end()
        requestRedraw()
    }

    private fun requestRedraw() {
        if (framePending) return
        framePending = true
        waitingSinceNanos = 0L
        choreographer.postFrameCallback(frame)
    }

    private fun onFrame() {
        framePending = false
        if (surface.dryRenderInFlight) {
            framesSkipped++
            // Re-post rather than drop: the transform has already moved, and a
            // dropped frame here is a canvas that stops following the fingers
            // until they move again.
            //
            // The deadline is the safety valve. A render that never completes
            // — a surface torn down at the wrong moment — would otherwise leave
            // this re-posting at vsync forever, awake and drawing nothing.
            // After it, draw anyway: a second render queued behind a stuck one
            // is a worse outcome than the stall it is recovering from is
            // likely.
            val now = System.nanoTime()
            if (waitingSinceNanos == 0L) waitingSinceNanos = now
            if (now - waitingSinceNanos < STUCK_RENDER_NANOS) {
                framePending = true
                choreographer.postFrameCallback(frame)
                return
            }
        }
        waitingSinceNanos = 0L
        framesDrawn++
        surface.redrawDry()
    }

    override fun toString(): String =
        "GestureController($updates updates, $framesDrawn drawn, $framesSkipped skipped)"

    companion object {
        /**
         * 100 ms of waiting on a render before drawing over it anyway.
         *
         * Nine frames at 90 Hz, and twenty-five times W2's measured p99 dry
         * frame of 4.0 ms. Anything that has not completed by then is not slow,
         * it is stuck.
         */
        const val STUCK_RENDER_NANOS: Long = 100_000_000L
    }
}
