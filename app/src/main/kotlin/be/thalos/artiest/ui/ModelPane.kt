package be.thalos.artiest.ui

import android.graphics.Bitmap
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import be.thalos.artiest.model.ModelStage
import be.thalos.artiest.ref.RefFiles
import com.google.android.filament.SwapChain
import com.google.android.filament.android.UiHelper

/**
 * The reference pane, when what is in it is a model rather than a picture.
 *
 * Lr12. It sits in exactly the rectangle `ReferenceBody` gives the picture, it
 * is driven by exactly the same [PaneView], and the buttons under it are the
 * same row. From the artist's side there is one pane and two kinds of thing to
 * put in it, which is the whole point.
 *
 * ## A TextureView and not a SurfaceView
 *
 * A `SurfaceView` has its own window behind the app's, which the app then
 * punches a hole through. That is why it is the right choice for the canvas and
 * the wrong one here: this pane lives inside a rounded card, inside a popup
 * that can sit over the drawing, and a surface behind the window ignores the
 * rounded corners, the shadow, and the fact that it is in a popup at all.
 *
 * A `TextureView` is drawn like any other view, so it clips, it shadows, and it
 * moves with the panel. It costs one extra copy of the pane's pixels per frame,
 * which for a panel a few hundred pixels across is not a cost worth the
 * corruption a SurfaceView would look like.
 *
 * ## It draws when something changes
 *
 * There is no loop. Every source of change — a different model, a drag, the
 * light, clay, grey, a resize, a new surface — ends in [Frames.ask], which
 * posts a single Choreographer callback. A model that is being looked at and
 * not touched costs nothing, and that is deliberate: the canvas next to it
 * draws ink into a front buffer and does not need a neighbour spending the GPU
 * on a picture nobody asked to change.
 */
@Composable
fun ModelPane(
    stage: ModelStage,
    /** The bytes of the model being shown, or null while they are being read. */
    glb: ByteArray?,
    /** Which model this is. A change is what makes the stage load again. */
    modelId: String?,
    pane: PaneView,
    /**
     * Called once with the first frame of a model that has no poster yet, so
     * the strip has a face to show for it. See `RefFiles.setPoster`.
     */
    onPoster: (Bitmap) -> Unit,
    wantPoster: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val backing = scheme.surfaceContainerHighest.toArgb()
    val frames = remember(stage) { Frames(stage) }

    DisposableEffect(frames) {
        onDispose { frames.detach() }
    }

    Box(modifier) {
        AndroidView(
            factory = { context ->
                // Not opaque, because the renderer draws the model onto
                // nothing and the card underneath is the pane's background.
                TextureView(context).apply { isOpaque = false }
                    .also { frames.attach(it) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { frames.detach() },
        )

        // Everything that can change what the frame should look like, in one
        // place. `snapshotFlow` and not a keyed effect: a drag changes these
        // sixty times a second and a `LaunchedEffect` per change would cancel
        // and restart a coroutine sixty times a second to do nothing but ask
        // for a frame.
        LaunchedEffect(frames) {
            snapshotFlow {
                Look(
                    pane.spin, pane.tilt, pane.dolly,
                    pane.lightAzimuth, pane.lightElevation,
                    pane.grey, pane.slices,
                )
            }.collect { look ->
                stage.aim(look.spin, look.tilt, look.dolly)
                stage.relight(look.azimuth, look.elevation)
                stage.grey(look.grey)
                stage.density(look.slices)
                frames.ask()
            }
        }

        // Loading is the one change that is not just a number, so it is its own
        // effect and it is keyed on the two things that mean "load again".
        LaunchedEffect(modelId, glb, pane.clay, pane.contour) {
            if (glb == null) {
                stage.close()
            } else {
                stage.open(glb, look(pane), pane.slices)
                stage.aim(pane.spin, pane.tilt, pane.dolly)
                stage.relight(pane.lightAzimuth, pane.lightElevation)
                stage.grey(pane.grey)
            }
            frames.poster(if (wantPoster) onPoster else null, backing)
            frames.ask()
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pane) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var fingers = 1
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            // A second finger arriving reports a wild first
                            // zoom, because there was nothing to compare it
                            // with. Waiting for the count to settle costs one
                            // event and saves a model jumping to arm's length
                            // every time a pinch starts.
                            if (pressed != fingers) {
                                fingers = pressed
                            } else if (pressed >= 2) {
                                if (zoom != 1f) pane.pinched(zoom)
                            } else if (pan != Offset.Zero) {
                                pane.dragged(pan.x, pan.y)
                            }
                            for (change in event.changes) if (change.pressed) change.consume()
                        }
                    }
                },
        )

        if (pane.lighting) LightBall(pane, Modifier.fillMaxSize())

        if (glb == null || !stage.loaded) {
            Text(
                if (stage.trouble.isNotEmpty()) stage.trouble else "…",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Which of the three ways of dressing the model the two switches mean. */
private fun look(pane: PaneView): ModelStage.Look = when {
    pane.contour -> ModelStage.Look.CONTOUR
    pane.clay -> ModelStage.Look.CLAY
    else -> ModelStage.Look.SCANNED
}

/** Everything a frame depends on that is a number. See the `snapshotFlow`. */
private data class Look(
    val spin: Float,
    val tilt: Float,
    val dolly: Float,
    val azimuth: Float,
    val elevation: Float,
    val grey: Boolean,
    val slices: Float,
)

/**
 * The surface half: a `TextureView`, the swap chain over it, and the one
 * pending frame.
 *
 * Held by `remember` rather than by the stage, because this is the half that
 * genuinely belongs to a view. When the panel is rearranged the `TextureView`
 * is destroyed, [detach] gives the swap chain back, and the model, the
 * materials and the engine are untouched on the other side of the split.
 *
 * ## One frame in flight, never a queue
 *
 * [ask] sets a flag and posts a callback only if one is not already posted.
 * Sixty drag events between two vsyncs therefore draw one frame, not sixty, and
 * a pane nobody is touching posts nothing at all.
 */
private class Frames(private val stage: ModelStage) {

    private val choreographer: Choreographer = Choreographer.getInstance()
    private var uiHelper: UiHelper? = null
    private var swapChain: SwapChain? = null
    private var widthPx = 0
    private var heightPx = 0
    private var posted = false

    private val callback = Choreographer.FrameCallback { nanos ->
        posted = false
        draw(nanos)
    }

    fun attach(view: TextureView) {
        detach()
        // DONT_CHECK: the app already holds a GL context for the canvas, and
        // UiHelper's check is for the case where somebody else made one on this
        // thread by accident. Here it is deliberate and it is a different
        // context on a different surface.
        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        helper.isOpaque = false
        helper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                releaseChain()
                swapChain = stage.engineOrNull()?.createSwapChain(surface)
                ask()
            }

            override fun onDetachedFromSurface() {
                releaseChain()
            }

            override fun onResized(width: Int, height: Int) {
                widthPx = width
                heightPx = height
                ask()
            }
        }
        // The stage has to exist before a swap chain can be made against its
        // engine, and the surface callback can arrive before the first model
        // does, so starting it here rather than at the first load is what stops
        // the first surface being wasted.
        stage.start()
        helper.attachTo(view)
        uiHelper = helper
    }

    fun detach() {
        if (posted) {
            choreographer.removeFrameCallback(callback)
            posted = false
        }
        uiHelper?.detach()
        uiHelper = null
        releaseChain()
        widthPx = 0
        heightPx = 0
    }

    /** Ask for the next frame to be kept as the model's poster, or stop asking. */
    fun poster(take: ((Bitmap) -> Unit)?, backing: Int) {
        if (take == null) return
        stage.captureNext(backing) { shot ->
            val side = RefFiles.POSTER_SIDE
            val scaled = runCatching {
                val long = maxOf(shot.width, shot.height).coerceAtLeast(1)
                Bitmap.createScaledBitmap(
                    shot,
                    (shot.width * side / long).coerceAtLeast(1),
                    (shot.height * side / long).coerceAtLeast(1),
                    true,
                )
            }.getOrDefault(shot)
            take(scaled)
            if (scaled !== shot) shot.recycle()
        }
    }

    fun ask() {
        if (posted) return
        posted = true
        choreographer.postFrameCallback(callback)
    }

    private fun draw(nanos: Long) {
        val chain = swapChain ?: return
        if (!stage.frame(chain, widthPx, heightPx, nanos)) return
        // A read is handed to the GPU during the frame and answered a frame or
        // two later, so the pane has to keep drawing until it has been. This is
        // the only thing in here that asks for a frame nothing has changed for,
        // and it stops the moment the picture has been taken.
        if (stage.capturing) ask()
    }

    private fun releaseChain() {
        val chain = swapChain ?: return
        swapChain = null
        val engine = stage.engineOrNull() ?: return
        engine.destroySwapChain(chain)
        // Filament hands work to its own thread, and destroying the surface
        // underneath work that has not run yet is a crash in the driver rather
        // than an exception here.
        engine.flushAndWait()
    }
}
