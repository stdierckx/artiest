package be.thalos.artiest.spike

import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Phase 0 harness. Three screens, one job each:
 *
 *  - Pen      what the digitizer actually reports
 *  - Latency  A/B across the three render paths
 *  - Device   SoC, GPU and display modes, then export it all
 *
 * See README.md for how to run the measurement.
 */
class MainActivity : ComponentActivity() {

    private val capture = PenCapture()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        keepNavGesturesOutOfTheWay()

        val report = DeviceProbe.run(this, activityDisplay())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Harness(capture, report, { activityDisplay()?.refreshRate ?: 0f }) {
                        val uri = SessionExporter.writeToDownloads(
                            this@MainActivity,
                            SessionExporter.buildReport(
                                // Re-read the refresh rate here rather than
                                // trusting the probe: DeviceProbe.run() happens
                                // in onCreate, before preferredDisplayModeId
                                // has been applied, so it records the boot
                                // default and not the mode actually measured.
                                report.copy(
                                    currentRefreshHz = activityDisplay()?.refreshRate
                                        ?: report.currentRefreshHz,
                                ),
                                capture.stats,
                            ),
                        )
                        Toast.makeText(
                            this@MainActivity,
                            uri?.let { "Saved to Downloads" } ?: "Export failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    /** 90 Hz over 60 removes a third of every frame-bound stage in the chain. */
    private fun requestHighestRefreshRate() {
        val display = activityDisplay() ?: return
        val best = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    /**
     * Edge swipes for system navigation land on the canvas as stray touches and
     * ACTION_CANCELs. Transient bars keep them out of the way while drawing.
     */
    private fun keepNavGesturesOutOfTheWay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Suppress("DEPRECATION")
    private fun activityDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
}

@Composable
private fun Harness(
    capture: PenCapture,
    report: DeviceReport,
    refreshHzNow: () -> Float,
    onExport: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Pen", "Latency", "Device")

    Column(Modifier.fillMaxSize()) {
        // keepNavGesturesOutOfTheWay() takes the window edge-to-edge, so without
        // this the tab row draws underneath the status bar and its labels are
        // hidden behind the clock. Only the row is inset — the ink views below
        // keep the full surface they are being measured on.
        TabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> PenScreen(capture)
            1 -> LatencyScreen(capture, refreshHzNow)
            else -> DeviceScreen(report, capture, refreshHzNow, onExport)
        }
    }
}

/**
 * Draw slowly, light to heavy, and tilt the pen right over. The numbers that
 * matter are sample rate, samples/event and estimated pressure levels.
 */
@Composable
private fun PenScreen(capture: PenCapture) {
    var readout by remember { mutableStateOf("") }

    // Polled at 5 Hz rather than pushed per sample: recomposing on every sample
    // would slow down the very thing being measured.
    LaunchedEffect(Unit) {
        while (true) {
            readout = formatStats(capture.stats)
            delay(200)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = readout,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { capture.stats.reset() }) { Text("Reset stats") }
        }
        AndroidView(
            factory = { ctx ->
                BaselineInkView(ctx).apply {
                    this.capture = capture
                    // The cursor is genuinely useful here — this tab is about
                    // watching what the digitizer reports — so this screen keeps
                    // the instruments the Latency tab now judges without.
                    this.videoInstruments = true
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
        )
    }
}

/**
 * The A/B. Three arms, one chip each, and the chips that follow them change
 * what an arm does rather than which one is mounted.
 *
 * The protocol matters as much as the code: clear before every judged stroke,
 * alternate A-B-A rather than judging each arm once in the order you toggled
 * them, draw the same short stroke each time, and check on the Device tab that
 * the refresh rate is still 90 Hz — the peak_refresh_rate override is external
 * state that lapses silently, and one arm judged at 60 Hz voids the comparison.
 * Judge with Marks off and Predict off, then film at 240 fps with it on.
 */
@Composable
private fun LatencyScreen(capture: PenCapture, refreshHzNow: () -> Float) {
    var arm by remember { mutableStateOf(Arm.GRAPHICS_CORE) }
    // Off by default. The three arms implement prediction incompatibly — the
    // baseline has no predictor at all — so leaving it on would make the first
    // thing a judge sees a comparison of three prediction behaviours rather
    // than of three render paths. Prediction is W11's question, and it already
    // lost once on this hardware.
    var prediction by remember { mutableStateOf(false) }
    // Read from the capture rather than repeating its default: two independent
    // literals drift, and a chip that displays the opposite of what dispatch is
    // actually doing would quietly invalidate the toggle it exists to measure.
    var unbuffered by remember { mutableStateOf(capture.unbufferedDispatch) }
    var frameRateVote by remember { mutableStateOf(true) }
    var fit by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf<View?>(null) }

    // Shown on this tab, not just the Device tab, because the 90 Hz override is
    // external state that lapses on its own and `settings get` keeps reporting
    // 90.0 after the display has already dropped back to 60. A judge who cannot
    // see the live rate can compare two arms measured at different refresh rates
    // and never know. Polled, not cached, for the same reason.
    var liveHz by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            liveHz = refreshHzNow()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Scrollable because the row now carries nine controls and a clipped
        // chip is one nobody remembers to check.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Leading, so it is the first thing read on the row the judging
            // happens on. Red below 90 because a lapsed override does not
            // announce itself and a run at 60 Hz is not comparable with one at 90.
            Text(
                text = "%.0f Hz".format(liveHz),
                color = if (liveHz >= 89f) Color.Unspecified else Color.Red,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            for (a in Arm.values()) {
                FilterChip(arm == a, { arm = a }, { Text(a.label) })
            }
            FilterChip(prediction, { prediction = !prediction }, { Text("Predict") })
            FilterChip(unbuffered, {
                unbuffered = !unbuffered
                capture.unbufferedDispatch = unbuffered
            }, { Text("Unbuffered") })
            FilterChip(frameRateVote, { frameRateVote = !frameRateVote }, { Text("1000 fps") })
            FilterChip(fit, { fit = !fit }, { Text("Fit") })
            FilterChip(marks, { marks = !marks }, { Text("Marks") })
            AssistChip({ clearInk(mounted) }, { Text("Clear") })
            AssistChip({ capture.stats.reset() }, { Text("Reset stats") })
        }
        // AndroidView's factory runs once, so the selector needs an explicit key
        // to force a fresh surface instead of silently keeping the old one.
        key(arm) {
            AndroidView(
                factory = { ctx ->
                    when (arm) {
                        Arm.BASELINE -> BaselineInkView(ctx).apply { this.capture = capture }
                        Arm.GRAPHICS_CORE -> LowLatencyInkView(ctx).apply {
                            this.capture = capture
                            this.predictionEnabled = prediction
                        }
                        Arm.DIRECT_SURFACE -> DirectSurfaceInkView(ctx).apply {
                            this.capture = capture
                            this.predictionEnabled = prediction
                            this.frameRateVote = frameRateVote
                        }
                    }
                },
                update = { view ->
                    mounted = view
                    when (view) {
                        is BaselineInkView -> view.videoInstruments = marks
                        is LowLatencyInkView -> {
                            view.predictionEnabled = prediction
                            view.videoInstruments = marks
                        }
                        is DirectSurfaceInkView -> {
                            view.predictionEnabled = prediction
                            view.videoInstruments = marks
                            view.frameRateVote = frameRateVote
                            view.fitToView = fit
                        }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** The three render paths under test, in the order they were built. */
private enum class Arm(val label: String) {
    BASELINE("Baseline"),
    GRAPHICS_CORE("Graphics-core"),
    DIRECT_SURFACE("DirectSurface"),
}

/**
 * Every arm has had a clear() since Phase 0 and nothing ever called one. It is
 * needed now: the baseline redraws all the ink it holds on every frame, so an
 * arm judged on a canvas that already has a minute of scribble on it is judged
 * on a handicap that has nothing to do with its render path.
 */
private fun clearInk(view: View?) {
    when (view) {
        is BaselineInkView -> view.clear()
        is LowLatencyInkView -> view.clear()
        is DirectSurfaceInkView -> view.clear()
    }
}

@Composable
private fun DeviceScreen(
    report: DeviceReport,
    capture: PenCapture,
    refreshHzNow: () -> Float,
    onExport: () -> Unit,
) {
    // The probe's own refresh reading is taken before the 90 Hz request lands,
    // so it is stale by construction. Poll the live one: running the latency
    // A/B at 60 Hz when you believe it is 90 measures the wrong device.
    var liveRefreshHz by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            liveRefreshHz = refreshHzNow()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            text = formatDevice(report, liveRefreshHz),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Button(onClick = onExport, modifier = Modifier.padding(top = 16.dp)) {
            Text("Export report to Downloads")
        }
        Text(
            text = "\n" + formatStats(capture.stats),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

private fun formatStats(s: PenStats): String = buildString {
    appendLine("events            ${s.events}")
    appendLine("samples           ${s.samples}  (historical ${s.historicalSamples})")
    appendLine("samples/event     ${"%.2f".format(s.samplesPerEvent())}")
    appendLine("sample rate       ${"%.1f".format(s.sampleRateHz())} Hz")
    appendLine("pressure range    ${fmt(s.pressureMin)} .. ${fmt(s.pressureMax)}")
    appendLine("distinct pressure ${s.distinctPressureValues()}")
    appendLine("est. levels       ${s.estimatedPressureLevels()}")
    appendLine("tilt range (rad)  ${fmt(s.tiltMin)} .. ${fmt(s.tiltMax)}")
    appendLine("orientation (rad) ${fmt(s.orientationMin)} .. ${fmt(s.orientationMax)}")
    appendLine("hover samples     ${s.hoverSamples}")
    appendLine("hover max         ${fmt(s.distanceMax)}")
    appendLine("tool types        ${s.toolTypesSeen.joinToString { toolTypeName(it) }}")
    appendLine("button states     ${s.buttonStatesSeen.joinToString()}")
    appendLine("canceled events   ${s.canceledEvents}  (flagged ${s.flaggedCanceledPointers})")
    appendLine("history batches   ${s.historyBatchSizes.toSortedMap()}")
}

private fun formatDevice(d: DeviceReport, liveRefreshHz: Float): String = buildString {
    appendLine("${d.manufacturer} ${d.model}  (${d.device})")
    // First, above everything: this is the one line the phase turns on, and a
    // refusal is invisible everywhere else — the layer keeps its name and the
    // ink still draws.
    appendLine("FRONT BUFFER      ${frontBufferText(d.frontBufferSupported)}")
    appendLine("  usage probed    ${d.frontBufferUsageFlags} (0x${d.frontBufferUsageFlags.toString(16)})")
    appendLine("  bit alone       ${frontBufferBitText(d.frontBufferBitSupported)}")
    appendLine()
    appendLine("SoC               ${d.soc}")
    appendLine("Android           ${d.androidRelease} (API ${d.sdkInt})")
    appendLine("memory class      ${d.memoryClassMb} MB / large ${d.largeMemoryClassMb} MB")
    appendLine("total RAM         ${gib(d.totalMemBytes)} GiB  (free ${gib(d.availMemBytes)} GiB)")
    appendLine("low-mem threshold ${gib(d.lowMemoryThresholdBytes)} GiB  (low now ${d.lowMemory})")
    appendLine("refresh now       ${"%.1f".format(liveRefreshHz)} Hz  (at probe ${"%.1f".format(d.currentRefreshHz)} Hz)")
    appendLine("display modes     ${d.displayModes.joinToString("\n                  ")}")
    appendLine()
    appendLine("GL vendor         ${d.glVendor}")
    appendLine("GL renderer       ${d.glRenderer}")
    appendLine("GL version        ${d.glVersion}")
    appendLine("max texture       ${d.glMaxTextureSize}")
    appendLine("4096 canvas ok    ${d.supportsFullCanvas}")
}

/**
 * Spelled out rather than printed as a bare boolean: the whole point of the
 * probe is that a refusal is silent, so the one place a human reads it must not
 * require them to remember what `false` implies.
 */
private fun frontBufferText(supported: Boolean?): String = when (supported) {
    true -> "granted"
    false -> "REFUSED -> stop condition: DirectSurfaceInkSurface"
    null -> "n/a below API 33 (graphics-core never asks)"
}

/**
 * The same flag asked for on its own. It carries no verdict of its own —
 * graphics-core never asks this narrower question — but it separates "this
 * device has no front buffer at all" from "refused in combination with
 * COMPOSER_OVERLAY", which are different problems.
 */
private fun frontBufferBitText(supported: Boolean?): String = when (supported) {
    true -> "granted (so it is the combination that was refused)"
    false -> "refused (no front buffer on this device at all)"
    null -> "n/a below API 33"
}

private fun gib(bytes: Long): String = "%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))

private fun fmt(v: Float): String =
    if (v == Float.MAX_VALUE || v == -Float.MAX_VALUE) "-" else "%.5f".format(v)
