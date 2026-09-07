package be.thalos.artiest.spike

import android.os.Build
import android.os.Bundle
import android.view.Display
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *  - Latency  A/B between the baseline and front-buffered paths
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
            1 -> LatencyScreen(capture)
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
            factory = { ctx -> BaselineInkView(ctx).apply { this.capture = capture } },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
        )
    }
}

/**
 * Film this screen at 240 fps and count frames between the pen moving and the
 * ink following. Each frame is 4.17 ms. Run all four toggle combinations.
 */
@Composable
private fun LatencyScreen(capture: PenCapture) {
    var frontBuffered by remember { mutableStateOf(true) }
    var prediction by remember { mutableStateOf(true) }
    var unbuffered by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(frontBuffered, { frontBuffered = !frontBuffered }, { Text("Front buffer") })
            FilterChip(prediction, { prediction = !prediction }, { Text("Predict") })
            FilterChip(unbuffered, {
                unbuffered = !unbuffered
                capture.unbufferedDispatch = unbuffered
            }, { Text("Unbuffered") })
        }
        // AndroidView's factory runs once, so the toggle needs an explicit key
        // to force a fresh surface instead of silently keeping the old one.
        key(frontBuffered) {
            AndroidView(
                factory = { ctx ->
                    if (frontBuffered) {
                        LowLatencyInkView(ctx).apply {
                            this.capture = capture
                            this.predictionEnabled = prediction
                        }
                    } else {
                        BaselineInkView(ctx).apply { this.capture = capture }
                    }
                },
                update = { view ->
                    if (view is LowLatencyInkView) view.predictionEnabled = prediction
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
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
    appendLine("SoC               ${d.soc}")
    appendLine("Android           ${d.androidRelease} (API ${d.sdkInt})")
    appendLine("memory class      ${d.memoryClassMb} MB / large ${d.largeMemoryClassMb} MB")
    appendLine("refresh now       ${"%.1f".format(liveRefreshHz)} Hz  (at probe ${"%.1f".format(d.currentRefreshHz)} Hz)")
    appendLine("display modes     ${d.displayModes.joinToString("\n                  ")}")
    appendLine()
    appendLine("GL vendor         ${d.glVendor}")
    appendLine("GL renderer       ${d.glRenderer}")
    appendLine("GL version        ${d.glVersion}")
    appendLine("max texture       ${d.glMaxTextureSize}")
    appendLine("4096 canvas ok    ${d.supportsFullCanvas}")
}

private fun fmt(v: Float): String =
    if (v == Float.MAX_VALUE || v == -Float.MAX_VALUE) "-" else "%.5f".format(v)
