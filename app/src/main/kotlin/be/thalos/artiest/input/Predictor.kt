package be.thalos.artiest.input

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import be.thalos.artiest.engine.input.PenSample

/**
 * `Source.PREDICTED`'s producer, and the only thing in the app that guesses.
 *
 * Wraps `androidx.input:input-motionprediction`, which on API 33 and up
 * delegates to the platform's `MotionPredictor` and otherwise runs a bundled
 * Kalman filter. Which of those is actually running on this tablet was an open
 * question in `docs/phase1-plan.md`; [implementation] and [availability] answer
 * it at runtime rather than leaving it to be assumed.
 *
 * **Off by default, and shipping it off is an acceptable outcome.** W1 judged
 * prediction by eye and it lost — partly on an instrument artifact, since the
 * spike drew predicted ink in translucent grey so a 240 fps camera could see
 * overshoot, and the same behaviour in the real ink colour reads as the line
 * leading rather than as a ghost. W16 re-judges it on tip-lead and reversal
 * spurs. Until then the toggle exists and the default is off, because the cost
 * of being wrong is asymmetric: front-buffer ink is unretractable, so a
 * mispredicted dab is a spur that stays until pen-up, and a stroke that
 * occasionally grows a hair is worse under the pen than a stroke that is 5 ms
 * slower.
 *
 * The caller gates on [enabled] before calling [record] and [predict], so a
 * disabled predictor costs nothing at all on the input path — not even the
 * virtual call. That is deliberate: W9's numbers were measured with no
 * prediction in the loop, and they have to keep meaning what they said.
 */
class Predictor(private val view: View) {

    private val delegate: MotionEventPredictor = MotionEventPredictor.newInstance(view)

    /**
     * The concrete class `newInstance` handed back.
     *
     * `SystemMotionEventPredictor` means the platform predictor;
     * `MultiPointerPredictor` or similar means the bundled Kalman fallback. The
     * two behave differently enough that "prediction was judged and lost" is
     * only a statement about whichever one was running.
     */
    val implementation: String = delegate.javaClass.simpleName

    /**
     * What the platform says about this pen, once an event has been seen.
     *
     * Unknown until then: `MotionPredictor.isPredictionAvailable` is asked about
     * a specific device id and input source, and there is no way to name the pen
     * before it has sent something. API 34 and up only; below that the androidx
     * fallback is the only implementation and the question does not arise.
     */
    var availability: String = "unknown"
        private set

    /**
     * Runtime toggle. False by default; see the class header.
     *
     * Turning it on mid-stroke means the predictor has no history for a sample
     * or two and returns nothing, which is correct rather than a gap — it is
     * exactly as uncertain as its input.
     */
    var enabled: Boolean = false

    /** Feed a real event. Call for every touch event while [enabled]. */
    fun record(event: MotionEvent) {
        if (availability == "unknown") readAvailability(event)
        delegate.record(event)
    }

    /**
     * The pen's predicted position, as a sample stamped [PenSample.Source.PREDICTED].
     *
     * Null when the predictor has nothing to say, or when the pointer the
     * stroke belongs to is not in the synthesized event — which happens: the
     * predictor is tracking whatever pointers it was fed, and a palm that
     * arrived and was rejected upstream is still a pointer it saw.
     *
     * **The returned event is deliberately not recycled.** Ownership of it has
     * varied across library versions, and a double recycle crashes where a
     * missed one merely churns — one `MotionEvent` per frame against a budget
     * that already accepts one `PenSample` per sample. `:spike` records the same
     * decision for the same reason.
     */
    fun predict(pointerId: Int): PenSample? {
        val predicted = delegate.predict() ?: return null
        val pi = predicted.findPointerIndex(pointerId)
        if (pi < 0) return null
        return predicted.sampleAt(pi, CURRENT, PenSample.Source.PREDICTED)
    }

    private fun readAvailability(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 34) {
            availability = "n/a below API 34"
            return
        }
        availability = try {
            val ctx: Context = view.context
            val platform = android.view.MotionPredictor(ctx)
            if (platform.isPredictionAvailable(event.deviceId, event.source)) {
                "platform: yes"
            } else {
                "platform: no"
            }
        } catch (e: Throwable) {
            // Vendors have shipped this returning and throwing in about equal
            // measure. A diagnostic that can crash the ink path is worse than a
            // diagnostic that says it does not know.
            "unavailable (${e.javaClass.simpleName})"
        }
    }

    override fun toString(): String =
        "Predictor($implementation, enabled=$enabled, $availability)"
}
