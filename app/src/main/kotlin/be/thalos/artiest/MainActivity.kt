package be.thalos.artiest

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import be.thalos.artiest.engine.input.ExclusivityState
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.toolTypeName
import be.thalos.artiest.input.InkInputSink
import be.thalos.artiest.input.InputRouter

/**
 * Placeholder chrome, and a smoke test for the input path.
 *
 * W15 replaces the body of this file with the real toolbars and the
 * `DeviceProbe` port; nothing here is meant to survive that. What it does until
 * then is run real pen input through the whole W4 path — `MotionEvent` into
 * [InputRouter], `PenSample` out — and print what came back. There is no ink:
 * the stroke pipeline and the render path start at W6, and an ink surface built
 * here would be thrown away twice.
 *
 * It is not decoration. The numbers below are the only on-device check that
 * exists for the two things no JVM test can reach — whether the framework
 * routes ACTION_HOVER_* to `onHoverEvent` on this tablet, and whether the
 * router's pointer bookkeeping survives a real palm — and reading them takes a
 * pen, a hand and thirty seconds.
 */
class MainActivity : ComponentActivity() {

    private val probe = InputProbe()
    private val router = InputRouter(probe)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InputProbeScreen(probe, router)
                }
            }
        }
    }

    /**
     * A stroke does not survive the activity leaving the foreground, and the
     * framework does not always send an ACTION_CANCEL to say so — a dialog
     * taking focus can leave ACTION_MOVE as the last event ever delivered.
     * Without this the router holds a pointer id that will never lift.
     */
    override fun onPause() {
        router.abandon()
        super.onPause()
    }
}

/**
 * The input target: a `View` that draws nothing and exists to have events
 * dispatched to it.
 *
 * Hover comes through `onHoverEvent` and touch through `onTouchEvent`, which is
 * the framework's split and not a choice made here — a view that overrides only
 * the second sees no hover at all and looks like hardware that cannot hover.
 */
private class InputProbeView(
    context: Context,
    private val router: InputRouter,
    private val probe: InputProbe,
) : View(context) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = router.onTouchEvent(event, this)
        probe.onRouted(router)
        return handled
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        router.onHoverEvent(event)
        probe.onRouted(router)
        // The framework's own hover handling still runs. The router took a
        // presence reading; it did not consume the event.
        return super.onHoverEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) router.abandon()
    }

    override fun onDetachedFromWindow() {
        router.abandon()
        super.onDetachedFromWindow()
    }
}

/**
 * Counts what the router emitted, and holds the last sample.
 *
 * Compose state is written once per event rather than once per sample: the
 * digitizer runs to 321.75 Hz and one event carries several samples, so
 * recomposing per sample would make the readout the most expensive thing in the
 * app. Even per event this is a debug surface and nothing else — the real
 * consumer of these callbacks is W6's stroke builder.
 */
private class InputProbe : InkInputSink {

    var last by mutableStateOf<PenSample?>(null)
        private set
    var state by mutableStateOf(ExclusivityState.IDLE)
        private set
    var penInRange by mutableStateOf(false)
        private set
    var strokePointerId by mutableStateOf(-1)
        private set

    var samples by mutableStateOf(0L)
        private set
    var events by mutableStateOf(0L)
        private set
    var strokes by mutableStateOf(0)
        private set
    var cancelled by mutableStateOf(0)
        private set
    var gestures by mutableStateOf(0)
        private set

    fun onRouted(router: InputRouter) {
        events++
        state = router.state
        penInRange = router.penInRange
        strokePointerId = router.penStrokeId
    }

    override fun onStrokeBegin(pointerId: Int) {
        strokes++
    }

    override fun onStrokeSamples(samples: ArrayList<PenSample>) {
        // Read by size and index. `for (s in samples)` over an ArrayList
        // allocates an iterator per event, which is the allocation the spike's
        // own "the hot path allocates nothing" comment sits directly above.
        this.samples += samples.size
        last = samples[samples.size - 1]
    }

    override fun onStrokeEnd() = Unit

    override fun onStrokeCancel() {
        cancelled++
    }

    override fun onGestureBegin() {
        gestures++
    }

    override fun onGesturePointers(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) = Unit

    override fun onGestureEnd() = Unit

    override fun onGestureCancel() = Unit

    override fun onPenPresence(inRange: Boolean) {
        penInRange = inRange
    }
}

@Composable
private fun InputProbeScreen(probe: InputProbe, router: InputRouter) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> InputProbeView(context, router, probe) },
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.statusBarsPadding().padding(24.dp)) {
            Text("artiest — W4 input path", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Draw with the pen. Rest a hand on the glass mid-stroke: the " +
                    "state stays PEN, the sample count keeps climbing, and no gesture " +
                    "is counted. Flip the pen and it reports FINGER, which is why it " +
                    "pans instead of erasing.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = readout(probe),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private fun readout(probe: InputProbe): String {
    val s = probe.last
    return buildString {
        append("state     ${probe.state}")
        append("   pen in range ${probe.penInRange}")
        append("   stroke id ${probe.strokePointerId}\n")
        append("events    ${probe.events}   samples ${probe.samples}\n")
        append("strokes   ${probe.strokes} begun, ${probe.cancelled} cancelled")
        append("   gestures ${probe.gestures}\n")
        if (s == null) {
            append("last      -")
        } else {
            append("last      ${toolTypeName(s.toolType)}  ${s.source}\n")
            append("          x ${s.x}  y ${s.y}\n")
            append("          pressure ${s.pressure}  tilt ${s.tilt}\n")
            append("          orientation ${s.orientation}  buttons ${s.buttonState}\n")
            // Printed because it is measured dead — 400 hover samples, all
            // zero — and a reading that is anything but 0.0 on a future device
            // is worth seeing rather than assuming.
            append("          distance ${s.distance}  t ${s.eventTimeNanos}")
        }
    }
}
