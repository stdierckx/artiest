package be.thalos.artiest.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * The wheel's pixels, built once per process instead of once per opening.
 *
 * **Why this had to change.** The panel is a `Popup`, so it leaves the
 * composition when it closes and every `remember` inside it goes with it. The
 * raster is a quarter of a million `atan2`, `sqrt` and `Hsv.pack` calls plus a
 * megabyte of `Bitmap`, and it was being paid **every time the colour button
 * was pressed**, synchronously, on the thread that is trying to show the panel.
 * That is the delay: not the drawing of the wheel, the building of it.
 *
 * One size and no key, rather than a map keyed on the measured width. The disc
 * is drawn scaled to whatever box it lands in and always was — a hue ramp has
 * no edge for the upscale to soften — so rasterising at the cap and letting
 * `drawImage` resize it costs one bilinear fetch per pixel and removes the one
 * way a cache like this goes wrong, which is holding an entry nobody asks for
 * twice.
 *
 * [warm] exists because a cache still charges someone for the first miss, and
 * the first miss is the first time the user opens the panel — the one moment
 * this is meant to fix. Called at startup off the main thread, the entry is
 * there before anything asks.
 */
private object DiscRaster {

    @Volatile
    private var cached: ImageBitmap? = null

    private val lock = Any()

    fun get(): ImageBitmap {
        cached?.let { return it }
        // Synchronized so a warm-up in flight and a panel opening cannot both
        // pay for it. The loser of the race waits for the winner's bitmap
        // rather than building a second one.
        synchronized(lock) {
            cached?.let { return it }
            val started = System.nanoTime()
            val built = discBitmap(MAX_RASTER_PX)
            android.util.Log.i(
                "artiest",
                "colour wheel raster ${MAX_RASTER_PX}px in " +
                    "${(System.nanoTime() - started) / 1_000_000f} ms",
            )
            cached = built
            return built
        }
    }

    fun warm() {
        if (cached != null) return
        Thread({ get() }, "wheel-raster").also { it.isDaemon = true }.start()
    }
}

/**
 * Build the colour wheel's pixels now, off the main thread, so the first press
 * of the colour button does not have to.
 *
 * Safe to call more than once and safe to call never — the panel builds its own
 * if this has not run.
 */
fun warmColourWheel() = DiscRaster.warm()

/**
 * One slider row: tall enough to hit with a thumb, thin enough that three of
 * them cost less than the wheel.
 *
 * It was 28 for a single value bar. Three rows at 28 plus their gaps is 94dp
 * of a panel the user has asked to get smaller, and a 20dp track with a 24dp
 * row around it is still twice the height of a fingertip's precision.
 */
private val TRACK_HEIGHT = 20.dp

/** The row a track sits in, which is what the layout actually spends. */
private val SLIDER_ROW = 24.dp

/** The puck, which shows the chosen colour rather than merely pointing at it. */
private val PUCK_RADIUS = 9.dp

/**
 * An HSV colour wheel: hue and saturation on a disc, and all three on sliders
 * beneath it.
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
 * opaque by construction and `Brush` (Phase 1's `RoundPen`) says why that is
 * load-bearing rather than incidental. No eyedropper. The hex field and the
 * recent-colours row live in [ColourPanel], which is where a colour stops being
 * a coordinate and starts being one you have used.
 *
 * The single value bar that used to sit under the disc is now the third of
 * [HsvSliders]' three rows, which is Us4 of `docs/ui-space-plan.md` and the
 * user's own sentence: *"The palette with some standard colors can go instead
 * for a hue, saturation, brightness slider."*
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
    discSize: Dp? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Disc(hsv, onHsvChange, if (discSize == null) Modifier.fillMaxWidth() else Modifier.width(discSize))
        Spacer(Modifier.height(10.dp))
        HsvSliders(hsv, onHsvChange, Modifier.fillMaxWidth())
    }
}

/**
 * Hue, saturation and value, one gradient track each, with the number.
 *
 * ## Why three sliders under a wheel that already has two of them
 *
 * Because the user asked for them by name, and in the same breath as the thing
 * they replaced:
 *
 * > *"The palette with some standard colors can go instead for a hue,
 * > saturation, brightness slider."*
 *
 * They are not redundant with the disc, they are the *other* half of the same
 * job. A disc is the fast instrument — a flick lands you in the right region of
 * colour and you can see where you are going. A slider is the precise one: it
 * has one degree of freedom, so a small movement changes one thing by a known
 * amount, and it is the only way to say "the same red, a little darker" without
 * disturbing the hue. Every picker people already know has both.
 *
 * ## Each track is its own answer
 *
 * The hue track is the whole hue circle; the saturation track runs from grey to
 * the full chroma of the hue you are on; the value track runs from black to the
 * colour you have. So the track *is* the range at the setting you are at — you
 * aim rather than hunt — and all three are rebuilt whenever the fields they
 * depend on move, which is what makes the saturation track go grey when the
 * value does.
 *
 * The value track replaced a standalone bar of the same construction. Nothing
 * about it changed except that it now has two siblings and is 8dp shorter.
 */
@Composable
fun HsvSliders(hsv: Hsv, onHsvChange: (Hsv) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Six stops and not seven: the circle closes, so red is the first and
        // the last, and a gradient that ends on magenta puts a seam in the one
        // place the eye is most likely to be aiming.
        SliderRow(
            label = "H",
            value = hsv.hue / 360f,
            reading = (hsv.hue + 0.5f).toInt().toString(),
            track = remember(hsv.saturation, hsv.value) {
                List(HUE_STOPS + 1) { i ->
                    Color(Hsv(i * 360f / HUE_STOPS, 1f, 1f).toArgb())
                }
            },
            puck = Color(hsv.copy(saturation = 1f, value = 1f).toArgb()),
            onFraction = { onHsvChange(hsv.copy(hue = it * 360f)) },
        )
        SliderRow(
            label = "S",
            value = hsv.saturation,
            reading = percent(hsv.saturation),
            track = remember(hsv.hue, hsv.value) {
                listOf(
                    Color(Hsv(hsv.hue, 0f, hsv.value).toArgb()),
                    Color(Hsv(hsv.hue, 1f, hsv.value).toArgb()),
                )
            },
            puck = Color(hsv.toArgb()),
            onFraction = { onHsvChange(hsv.copy(saturation = it)) },
        )
        SliderRow(
            label = "V",
            value = hsv.value,
            reading = percent(hsv.value),
            track = remember(hsv.hue, hsv.saturation) {
                listOf(
                    Color(Hsv(hsv.hue, hsv.saturation, 0f).toArgb()),
                    Color(Hsv(hsv.hue, hsv.saturation, 1f).toArgb()),
                )
            },
            puck = Color(hsv.toArgb()),
            onFraction = { onHsvChange(hsv.copy(value = it)) },
        )
    }
}

/**
 * Enough stops that a hue ramp has no visible facets across a 240dp track.
 *
 * Twelve is one every thirty degrees, which is 20dp of track per segment —
 * `horizontalGradient` interpolates in linear RGB between them, and thirty
 * degrees of hue is close enough to a straight line in RGB that the difference
 * is under a level.
 */
private const val HUE_STOPS = 12

private fun percent(v: Float): String = "${(v * 100f + 0.5f).toInt()}"

/**
 * A label, a gradient track you can aim at, and the number it is reading.
 *
 * The number is there because it is half of what the user asked for: *"You cant
 * input a color code"* is about the hex field, and a picker that cannot tell you
 * what hue you are on cannot be written down either. It is read-only — the hex
 * field is where typing happens, because one editable field is a thing people
 * find and four are a form.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    reading: String,
    track: List<Color>,
    puck: Color,
    onFraction: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(SLIDER_ROW),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(13.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(TRACK_HEIGHT)
                .trackTouch { position, viewport ->
                    if (viewport.width <= 0) return@trackTouch
                    onFraction((position.x / viewport.width).coerceIn(0f, 1f))
                },
        ) {
            if (size.width <= 0f) return@Canvas
            val corner = CornerRadius(size.height / 2f)
            drawRoundRect(brush = Brush.horizontalGradient(track), cornerRadius = corner)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.25f),
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx()),
            )
            val r = size.height / 2f - 2.dp.toPx()
            // Inset so the puck cannot hang off either end of the track at 0
            // and 1.
            val x = (value * size.width).coerceIn(r, size.width - r)
            drawPuck(Offset(x, size.height / 2f), puck, r)
        }
        Text(
            reading,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
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
    discSize: Dp? = null,
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
        discSize = discSize,
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
    // A plain Box since the raster left this composable. It was a
    // `BoxWithConstraints` because the disc used to rasterise itself at
    // whatever size it was handed, so it had to know that size before it could
    // build anything; the cache builds one raster at a fixed resolution and the
    // Canvas scales it, so the constraints are not read any more. Lint catches
    // exactly this -- an unused BoxWithConstraints scope is a subcomposition
    // being paid for and thrown away.
    Box(modifier.aspectRatio(1f)) {
        // From the process-wide cache, which is almost always already warm --
        // see [DiscRaster]. Building it here, per opening, is what made the
        // panel slow to appear.
        val wheel = DiscRaster.get()

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
