package be.thalos.artiest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import be.thalos.artiest.canvas.GestureStress
import be.thalos.artiest.canvas.InkSurfaceView
import be.thalos.artiest.canvas.InputStats
import be.thalos.artiest.canvas.RejectionStress
import be.thalos.artiest.canvas.StrokeStress
import be.thalos.artiest.doc.Document
import be.thalos.artiest.engine.input.CancelCause

/**
 * Placeholder chrome around the real canvas.
 *
 * W15 replaces the Compose half of this file with the actual toolbars, the
 * refresh-rate toggle and the `DeviceProbe` port. What it is for until then is
 * the on-device check W8 is judged by: draw, and ink appears under the pen;
 * lift, and it survives the commit; rotate, and the canvas is still there.
 *
 * The `Document` is owned here rather than by the view. It outlives every
 * attach/detach cycle, so a rotation rebuilds the `SurfaceView` against the
 * same layer bitmap instead of reallocating 27 MiB and losing the drawing.
 */
class MainActivity : ComponentActivity() {

    private val document = Document()

    private var view: InkSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CanvasScreen(document) { view = it }
                }
            }
        }
    }

    /**
     * The framework does not reliably send an ACTION_CANCEL when the activity
     * leaves the foreground — a dialog taking focus can leave ACTION_MOVE as
     * the last event ever delivered — so the open stroke is abandoned here.
     * `InkSurfaceView.clear` is not what is wanted: the drawing stays.
     */
    override fun onPause() {
        view?.abandonStroke()
        super.onPause()
    }

    /**
     * Released last, and only here. The layer's pixels must outlive the render
     * thread that writes them, and the view's detach is what joins that thread.
     */
    override fun onDestroy() {
        view = null
        document.close()
        super.onDestroy()
    }
}

/** The two stress shapes the readout is meant to be compared across. */
private val STRESS_MODES = listOf(
    Triple("Sweep", null, StrokeStress.Path.SPIRAL),
    Triple("Firm", 1f, StrokeStress.Path.SPIRAL),
    Triple("Zigzag", 1f, StrokeStress.Path.ZIGZAG),
)

@Composable
private fun CanvasScreen(document: Document, onView: (InkSurfaceView) -> Unit) {
    var generation by remember { mutableStateOf(0) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var stress by remember { mutableStateOf<StrokeStress?>(null) }
    var pinch by remember { mutableStateOf<GestureStress?>(null) }
    var reject by remember { mutableStateOf<RejectionStress?>(null) }
    var polling by remember { mutableStateOf(true) }

    // Polled twice a second rather than pushed. The counters this reads live on
    // the input and render paths, and making them Compose state would put a
    // recomposition on a path that runs at 321 Hz.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            if (polling) generation++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                // No transform is set here. The view fits the document to its
                // own surface in surfaceChanged, which is the only place the
                // real size is known — displayMetrics is the display, not the
                // window, and the two differ by the system bars at minimum.
                InkSurfaceView(context, document).also {
                    surface = it
                    onView(it)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.statusBarsPadding().padding(16.dp)) {
            Text("artiest — W13 canvas", style = MaterialTheme.typography.titleSmall)
            Text(
                text = readout(surface, document, reject, generation),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row {
                TextButton(onClick = {
                    surface?.clear()
                    generation++
                }) { Text("Clear") }
                TextButton(onClick = {
                    surface?.fitToView()
                    generation++
                }) { Text("Fit") }
                TextButton(
                    enabled = surface != null && pinch?.running != true,
                    onClick = {
                        val v = surface ?: return@TextButton
                        val g = pinch ?: GestureStress(v).also { pinch = it }
                        polling = false
                        g.start { polling = true; generation++ }
                    },
                ) { Text("Pinch") }
                TextButton(
                    enabled = surface != null && reject?.running != true,
                    onClick = {
                        val v = surface ?: return@TextButton
                        val g = reject ?: RejectionStress(v).also { reject = it }
                        // Cleared first: three of the eight cases commit ink on
                        // purpose, and the seven that must not are counted
                        // against the document's stroke count.
                        v.clear()
                        polling = false
                        g.start { polling = true; generation++ }
                    },
                ) { Text("Reject") }
                TextButton(onClick = {
                    val v = surface ?: return@TextButton
                    v.predictionEnabled = !v.predictionEnabled
                    v.predictor?.enabled = v.predictionEnabled
                    generation++
                }) { Text(if (surface?.predictionEnabled == true) "Predict ON" else "Predict off") }
                // Two runs, not one. See StrokeStress.start's pressure
                // parameter: the sweep is the worst case and the firm press is
                // what most of a real stroke looks like, and the pair is what
                // shows the cost tracking dabs rather than samples.
                for ((label, p, path) in STRESS_MODES) {
                    TextButton(
                        enabled = surface != null && stress?.running != true,
                        onClick = {
                            val v = surface ?: return@TextButton
                            val s = stress ?: StrokeStress(v).also { stress = it }
                            v.clear()
                            // The readout is paused for the duration:
                            // recomposing it allocates, and this run is
                            // measuring allocation.
                            polling = false
                            s.start(pressure = p, path = path) {
                                polling = true
                                generation++
                            }
                        },
                    ) { Text(label) }
                }
            }
        }
    }
}

/**
 * W9's numbers, and only the ones a decision hangs on.
 *
 * `spills` says whether the batch ring was ever exhausted while the render
 * thread was behind, and `peak` says how close it came — together they set
 * `DabBatchPool.DEFAULT_SLOTS`. `event` is the per-event cost against the
 * plan's 0.31 ms ceiling, in percentiles because the budget is missed by a
 * tail. `alloc` is bytes per digitizer sample during the drag, which the plan
 * predicts at about 56 — one `PenSample` and nothing else. It refuses to print
 * a figure that a collection ran through: heap-used is a level, so a GC inside
 * the window makes the delta meaningless rather than merely noisy.
 *
 * [generation] is a poll tick, read only so Compose recomputes the string. The
 * counters themselves are plain fields on the input and render paths and are
 * deliberately not Compose state — a recomposition at 321 Hz would be the most
 * expensive thing in the app, and on this screen it would also be the thing
 * being measured.
 */
private fun readout(
    surface: InkSurfaceView?,
    document: Document,
    reject: RejectionStress?,
    generation: Int,
): String {
    if (surface == null) return "surface  -"
    val p = surface.batches
    val s = surface.stats
    val alloc = if (!s.allocationValid) {
        if (s.dragSamples == 0) "no stroke measured" else "invalidated by ${s.strokeGcs} GC"
    } else {
        "${r(s.bytesPerSample(), 1)} B/sample   " +
            "drag ${s.dragBytes} B over ${s.dragSamples}   commit ${s.commitBytes} B"
    }
    return "doc      ${document.widthPx}x${document.heightPx}   " +
        "strokes ${document.strokeCount}   t $generation\n" +
        "batches  ${p.slots} slots   peak ${p.peakInFlight}   spills ${p.spills}   " +
        // In flight *right now*, which at rest must be zero. Non-zero on an
        // idle canvas means batches were handed out and never reported drawn,
        // and the only path that does that is a cancel.
        "in flight ${p.inFlight}   " +
        "commits pending ${document.pendingCommits}\n" +
        "last     ${surface.lastStrokeSamples} samples -> ${surface.lastStrokeDabs} dabs   " +
        "${r(s.samplesPerEvent(), 2)} samples/event   ${r(s.dabsPerEvent(), 1)} dabs/event\n" +
        "event    p50 ${r(s.eventMs(0.5f), 3)}  " +
        "p95 ${r(s.eventMs(0.95f), 3)}  " +
        "p99 ${r(s.eventMs(0.99f), 3)}  " +
        "max ${r(s.eventMs(1f), 3)} ms   " +
        "over ${r(s.overBudgetRate() * 100f, 1)}% of ${s.events}\n" +
        "submit   p50 ${r(s.submitMs(0.5f), 3)}  " +
        "p95 ${r(s.submitMs(0.95f), 3)}  " +
        "p99 ${r(s.submitMs(0.99f), 3)} ms in renderFrontBufferedLayer\n" +
        "sample   p50 ${r(s.msPerSample(0.5f) * 1000f, 1)}  " +
        "p99 ${r(s.msPerSample(0.99f) * 1000f, 1)} us/sample   " +
        "of a 3108 us interval\n" +
        "alloc    $alloc\n" +
        "predict  ${if (surface.predictionEnabled) "ON" else "off"}   " +
        "${surface.predictor?.implementation ?: "-"}   " +
        "${surface.predictor?.availability ?: "-"}   " +
        "${surface.predictedDabs} dabs   " +
        "lead mean ${r(surface.predictLeadMeanDoc, 2)} max ${r(surface.predictLeadMaxDoc, 2)} doc px\n" +
        "gate     ${surface.gateAllowed} allowed   ${surface.gateSuppressed} suppressed\n" +
        "xform    scale ${r(surface.transform.scale, 3)}   " +
        "rot ${r(surface.transform.rotationRad, 3)} rad   " +
        "t ${r(surface.transform.txDoc, 1)},${r(surface.transform.tyDoc, 1)}   " +
        "fitOnResize ${surface.fitOnResize}   " +
        "deferred ${surface.transformDeferrals}\n" +
        "gesture  ${surface.gestures.updates} updates   " +
        "${surface.gestures.framesDrawn} frames drawn   " +
        "${surface.gestures.framesSkipped} skipped\n" +
        rejectionLines(surface, reject)
}

/**
 * W13's three lines: what was refused, why strokes ended the way they did, and
 * whether the eight sequences still behave.
 *
 * `finger-begins` is the one number here that is an assertion rather than an
 * observation — "fingers and the pen's back never draw" — and its only correct
 * value is 0. `flagged` is the open question the plan could not close: whether
 * this digitizer's driver ever sets `FLAG_CANCELED`. A non-zero count while
 * drawing with a hand on the glass answers it yes; the honest reading of a zero
 * is "not seen yet", not "never".
 */
private fun rejectionLines(surface: InkSurfaceView, reject: RejectionStress?): String {
    val r = surface.rejections
    val causes = buildString {
        for (cause in CancelCause.entries) {
            val n = r.cancelsBy(cause)
            if (n > 0L) append("  ").append(cause.name.lowercase()).append(' ').append(n)
        }
    }
    val verdict = when {
        reject == null -> "not run"
        reject.running -> "running"
        else -> "${reject.passed}/${reject.passed + reject.failed} pass" +
            reject.results.filter { it.startsWith("FAIL") }.joinToString("") { "\n         $it" }
    }
    return "contacts ${r.contacts} (${r.penContacts} pen)   " +
        "dropped ${r.contactsDropped} (${r.penContactsDropped} pen)   " +
        "finger-begins ${r.fingerStrokeBegins}   flagged ${r.canceledFlagEvents}\n" +
        "strokes  ${r.strokesBegun} begun   ${r.strokesEnded} ended   " +
        // Both counts, on purpose. The router counts the decision and the view
        // counts the ink it threw away in response; they are the same number
        // unless a cancel stopped somewhere between them, which is precisely
        // the failure that would otherwise be silent.
        "${r.strokesCanceled} cancelled (${surface.strokesCanceled} dropped)" +
        "${if (causes.isEmpty()) "" else " ->$causes"}\n" +
        "reject   $verdict"
}

/**
 * Round to [places] decimals, without `String.format`.
 *
 * `String.format` and `DecimalFormat` follow the default locale, which on this
 * machine is nl-BE and prints a comma. That is banned outright in serialization
 * — an `:engine` test pins it — and while a debug readout is not serialization,
 * having exactly one rule about number formatting is cheaper than having two.
 * `Float.toString` is locale-independent by specification.
 */
private fun r(v: Float, places: Int): String {
    var m = 1f
    repeat(places) { m *= 10f }
    return (kotlin.math.round(v * m) / m).toString()
}
