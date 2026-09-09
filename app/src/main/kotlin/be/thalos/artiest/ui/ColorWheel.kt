package be.thalos.artiest.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import be.thalos.artiest.engine.color.ColorDisc
import be.thalos.artiest.engine.color.Hsv

/**
 * The largest wheel we ever rasterize, in pixels.
 *
 * A 400dp wheel on this tablet is a little over 1000px square, which is a
 * megapixel of `Hsv.pack` on the composition thread for a picture that is then
 * bilinear-filtered anyway. Capping at 512 costs nothing visible on a smooth
 * hue ramp — there is no edge in a hue wheel for the upscale to soften except
 * the rim, and the rim's antialiasing is baked into the alpha channel — and it
 * bounds the one-off cost at a quarter of the pixels.
 */
private const val MAX_RASTER_PX = 512

/** Tall enough to hit with a thumb without the bar competing with the wheel. */
private val VALUE_BAR_HEIGHT = 28.dp

/** The puck, which shows the chosen colour rather than merely pointing at it. */
private val PUCK_RADIUS = 9.dp

/**
 * An HSV colour wheel: hue and saturation on a disc, value on a bar beneath it.
 *
 * **State is [Hsv] and the caller holds it.** The overload below takes a packed
 * ARGB `Int` for callers whose colour already is one, but the picker's own
 * state can never be that, and the reason is worth stating once: ARGB does not
 * remember where you were pointing. Drag the value bar to zero and every hue is
 * `0xFF000000`; convert back to place the puck and it lands on red. Same at the
 * centre of the disc, where every hue is white. Hold [Hsv] and the puck stays
 * under the finger through black, through white, and back out again.
 *
 * **What this deliberately does not have.** No alpha slider — the app's ink is
 * opaque by construction and `Brush` (Phase 1's `RoundPen`) says why that is load-bearing rather
 * than incidental. No hex field, no eyedropper, no recent-colours row. This is
 * the wheel; the panel that will hold it is Phase 4's job.
 *
 * The geometry and the conversion are in `:engine`
 * ([ColorDisc], [Hsv]) and tested there, on the JVM, without a device. What is
 * left here is a `drawImage`, two `drawCircle`s and a pointer handler.
 */
@Composable
fun ColorWheel(
    hsv: Hsv,
    onHsvChange: (Hsv) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Disc(hsv, onHsvChange, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        ValueBar(hsv, onHsvChange, Modifier.fillMaxWidth())
    }
}

/**
 * The same wheel for a caller that stores its colour as packed ARGB — which is
 * what the ink is, all the way down to `InkSurfaceView.inkColorArgb`.
 *
 * The [Hsv] behind the puck is seeded from [argb] once and then owned here, for
 * the reason in the other overload's KDoc. It is re-seeded only when [argb]
 * arrives disagreeing with what this wheel last emitted — a palette swatch
 * tapped elsewhere, a colour restored at startup — so an external change moves
 * the puck, and the wheel's own emissions never bounce back and flatten the
 * hue.
 */
@Composable
fun ColorWheel(
    argb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hsv by remember { mutableStateOf(Hsv.fromArgb(argb)) }
    LaunchedEffect(argb) {
        if (hsv.toArgb() != argb) hsv = Hsv.fromArgb(argb)
    }
    ColorWheel(
        hsv = hsv,
        onHsvChange = {
            hsv = it
            onColorChange(it.toArgb())
        },
        modifier = modifier,
    )
}

/**
 * The hue/saturation disc.
 *
 * **It is drawn at full value, whatever value is selected**, and the bar
 * beneath it carries the value on its own. That is a correction, and the bug it
 * fixes is the first thing anyone would have hit: the app's default ink is
 * black, black is value zero, and a disc multiplied by zero is a black circle
 * with an invisible puck in it. The wheel's first impression was a hole.
 *
 * The version it replaces multiplied the raster by `(v, v, v, 1)` through
 * `BlendMode.Modulate`, which is exactly what value means in HSV — `HsvTest`
 * pins that identity — and which had a real argument behind it: the disc was
 * then a preview of the ink rather than a chart of hues. The argument is sound
 * and the result is unusable at the bottom of the range, which is where a
 * drawing app starts. Every picker that people already know how to use makes
 * the same trade: the disc is the hue and saturation chart, the bar is the
 * brightness, and the puck is what says which colour you actually have.
 *
 * The alpha channel is why there is still no scrim anywhere in this file. The
 * raster's edge is antialiased in its alpha, so a hard-edged circle drawn over
 * it at the same radius leaves a dark halo one pixel wide.
 */
@Composable
private fun Disc(hsv: Hsv, onHsvChange: (Hsv) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val sidePx = with(density) { maxWidth.roundToPx() }
        val rasterPx = sidePx.coerceIn(1, MAX_RASTER_PX)

        // Synchronous, on the composition thread, and kept until the wheel
        // changes size. Roughly a quarter of a million calls to Hsv.pack once
        // per size — cheaper than one frame of the drag it saves, and a wheel
        // that fades in a frame late reads as a bug.
        val wheel = remember(rasterPx) { discBitmap(rasterPx) }

        Canvas(
            Modifier
                .fillMaxSize()
                .trackTouch { position, viewport ->
                    val radius = minOf(viewport.width, viewport.height) / 2f
                    if (radius <= 0f) return@trackTouch
                    onHsvChange(
                        ColorDisc.sample(
                            dx = (position.x - viewport.width / 2f) / radius,
                            dy = (position.y - viewport.height / 2f) / radius,
                            // Aiming at green on a wheel whose value is zero
                            // means green, not black. Without this the disc is a
                            // dead end from the app's own default ink: every tap
                            // moves the puck and every tap returns black, and
                            // the only way out is a bar the user has not looked
                            // at yet. Black stays one tap away on the palette
                            // and at the left end of that bar.
                            value = if (hsv.value <= 0f) 1f else hsv.value,
                            fallbackHue = hsv.hue,
                        )
                    )
                }
        ) {
            val side = size.minDimension
            if (side <= 0f) return@Canvas
            val radius = side / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)

            drawImage(
                image = wheel,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(wheel.width, wheel.height),
                dstOffset = IntOffset(
                    ((size.width - side) / 2f).toInt(),
                    ((size.height - side) / 2f).toInt(),
                ),
                dstSize = IntSize(side.toInt(), side.toInt()),
                filterQuality = FilterQuality.Medium,
            )

            val point = ColorDisc.positionOf(hsv)
            drawPuck(
                centre = Offset(centre.x + point.dx * radius, centre.y + point.dy * radius),
                colour = Color(hsv.toArgb()),
                radius = PUCK_RADIUS.toPx(),
            )
        }
    }
}

/**
 * Value, from black to the hue the disc is pointing at.
 *
 * It is a bar and not a second ring around the wheel because a ring puts the
 * two things you adjust together at opposite ends of a gesture, and because the
 * gradient here can show the answer: the track *is* the range, so you aim
 * rather than hunt.
 */
@Composable
private fun ValueBar(hsv: Hsv, onHsvChange: (Hsv) -> Unit, modifier: Modifier) {
    // Keyed on the two fields that change it. Recomputing this per frame of a
    // value drag would rebuild the brush for a gradient whose endpoints did not
    // move.
    val track = remember(hsv.hue, hsv.saturation) {
        listOf(
            Color(Hsv(hsv.hue, hsv.saturation, 0f).toArgb()),
            Color(Hsv(hsv.hue, hsv.saturation, 1f).toArgb()),
        )
    }

    Canvas(
        modifier
            .height(VALUE_BAR_HEIGHT)
            .trackTouch { position, viewport ->
                if (viewport.width <= 0) return@trackTouch
                onHsvChange(hsv.copy(value = (position.x / viewport.width).coerceIn(0f, 1f)))
            }
    ) {
        if (size.width <= 0f) return@Canvas
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(brush = Brush.horizontalGradient(track), cornerRadius = corner)
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.25f),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )

        val puckRadius = size.height / 2f - 2.dp.toPx()
        // Inset so the puck cannot hang off either end of the track at 0 and 1.
        val x = (hsv.value * size.width).coerceIn(puckRadius, size.width - puckRadius)
        drawPuck(
            centre = Offset(x, size.height / 2f),
            colour = Color(hsv.toArgb()),
            radius = puckRadius,
        )
    }
}

/**
 * A filled dot in the colour it selects, ringed white inside and dark outside.
 *
 * Two rings rather than one because there is no single ring colour that stays
 * visible against a whole hue wheel: white vanishes on the pale centre, black
 * vanishes on a dark value. A white ring with a dark halo is legible against
 * both, and the fill means the puck answers "what did I just pick" without the
 * eye leaving it.
 */
private fun DrawScope.drawPuck(
    centre: Offset,
    colour: Color,
    radius: Float,
) {
    drawCircle(color = colour, radius = radius, center = centre)
    drawCircle(
        color = Color.White,
        radius = radius,
        center = centre,
        style = Stroke(width = 2.dp.toPx()),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.5f),
        radius = radius + 1.dp.toPx(),
        center = centre,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * Press-and-drag as one gesture, reporting every position in local pixels along
 * with the size of the thing that was touched.
 *
 * Not `detectDragGestures`: that one only reports once a drag has been
 * recognised, so a plain tap on the far side of the wheel would do nothing, and
 * a tap is how anyone picks a colour they can already see. Taking the pointer
 * from `awaitFirstDown` means the touch that starts the gesture is also the
 * first sample, and the drag continues from there.
 *
 * The callback is held through [rememberUpdatedState] because the
 * `pointerInput` block is keyed on `Unit` — it must be, or every recomposition
 * during a drag would cancel the gesture that is producing the recompositions —
 * and a lambda captured once would keep reading the colour the wheel had when
 * the finger went down.
 */
@Composable
private fun Modifier.trackTouch(onTouch: (Offset, IntSize) -> Unit): Modifier {
    val handler by rememberUpdatedState(onTouch)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            handler(down.position, size)
            down.consume()
            drag(down.id) { change ->
                handler(change.position, size)
                change.consume()
            }
        }
    }
}

/** The wheel as an [ImageBitmap]. See [ColorDisc.raster] for what is in it. */
private fun discBitmap(sizePx: Int): ImageBitmap =
    Bitmap.createBitmap(
        ColorDisc.raster(sizePx),
        sizePx,
        sizePx,
        Bitmap.Config.ARGB_8888,
    ).asImageBitmap()
