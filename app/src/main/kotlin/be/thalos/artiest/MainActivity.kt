package be.thalos.artiest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.thalos.artiest.engine.xform.CanvasTransform
import kotlin.math.PI

/**
 * Placeholder chrome. W15 replaces the whole body of this file with the real
 * toolbars, the refresh-rate toggle and the `DeviceProbe` port; nothing here is
 * meant to survive that.
 *
 * It is not empty, though, and that is the point: it runs `:engine` code and
 * prints what came back. The module boundary is only worth anything if `:app`
 * actually links against it, and an activity that ignores `:engine` would let a
 * broken dependency edge sit undetected until the first real consumer — by
 * which point the failure looks like the consumer's.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineReadout()
                }
            }
        }
    }
}

/**
 * The same operations a pinch-rotate gesture will drive in W12, run once with
 * fixed inputs. The zoom deliberately overshoots the ceiling and the rotation
 * deliberately exceeds half a turn, so the two clamps this transform exists to
 * enforce are visible on screen rather than merely present in the class.
 */
@Composable
private fun EngineReadout() {
    val t = CanvasTransform.IDENTITY
        .zoomedBy(64f)
        .rotatedBy((1.5 * PI).toFloat())
        .pannedBy(120f, -40f)

    Column(modifier = Modifier.statusBarsPadding().padding(24.dp)) {
        Text("artiest — Phase 1 scaffold", style = MaterialTheme.typography.titleMedium)
        Text(
            text = ":engine linked. CanvasTransform after a 64x zoom, a " +
                "three-quarter turn and a pan:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "scale       ${t.scale}   (clamped to ${CanvasTransform.MAX_SCALE})\n" +
                "rotationRad ${t.rotationRad}   (wrapped into -PI..PI)\n" +
                "txDoc       ${t.txDoc}\n" +
                "tyDoc       ${t.tyDoc}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
