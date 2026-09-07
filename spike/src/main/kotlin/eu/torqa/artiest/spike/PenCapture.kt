package eu.torqa.artiest.spike

import android.view.MotionEvent
import android.view.View

/**
 * The single entry point for pen data. Both ink paths feed this, so telemetry
 * is identical whichever renderer is under test and the two are comparable.
 */
class PenCapture {

    val stats = PenStats()

    /** Reused across events; the hot path allocates nothing. */
    private val scratch = ArrayList<PenSample>(32)

    /** Set false to measure how much batching costs. */
    var unbufferedDispatch: Boolean = true

    fun onMotionEvent(event: MotionEvent, view: View): Boolean {
        stats.onEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN && unbufferedDispatch) {
            // Stops Android batching motion events to the frame boundary, so
            // samples arrive as the digitizer produces them. Must be requested
            // per gesture, on DOWN.
            view.requestUnbufferedDispatch(event)
        }

        scratch.clear()
        event.collectSamples(scratch)
        for (s in scratch) stats.onSample(s)
        return true
    }
}
