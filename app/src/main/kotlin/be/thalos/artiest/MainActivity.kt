package be.thalos.artiest

import android.os.Bundle
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
import be.thalos.artiest.canvas.InkSurfaceView
import be.thalos.artiest.doc.Document

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

@Composable
private fun CanvasScreen(document: Document, onView: (InkSurfaceView) -> Unit) {
    var generation by remember { mutableStateOf(0) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }

    // Polled twice a second rather than pushed. The counters this reads live on
    // the input and render paths, and making them Compose state would put a
    // recomposition on a path that runs at 321 Hz.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            generation++
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
            Text("artiest — W8 ink surface", style = MaterialTheme.typography.titleSmall)
            Text(
                text = readout(surface, document, generation),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = {
                surface?.clear()
                generation++
            }) { Text("Clear") }
        }
    }
}

/**
 * The three numbers W9 needs and the only ones worth showing yet.
 *
 * `spills` is the one to watch: a non-zero count means the batch ring was
 * exhausted while the render thread was behind, so `DabBatchPool.DEFAULT_SLOTS`
 * is too small. `peak` says how close it came. [generation] is a poll tick and
 * is read only so Compose recomputes the string — see the caller for why the
 * counters are not Compose state.
 */
private fun readout(surface: InkSurfaceView?, document: Document, generation: Int): String {
    if (surface == null) return "surface  -"
    val p = surface.batches
    return "doc      ${document.widthPx}x${document.heightPx}   " +
        "strokes ${document.strokeCount}   t $generation\n" +
        "batches  ${p.slots} slots   peak ${p.peakInFlight}   spills ${p.spills}\n" +
        "last     ${surface.lastStrokeSamples} samples -> ${surface.lastStrokeDabs} dabs"
}
