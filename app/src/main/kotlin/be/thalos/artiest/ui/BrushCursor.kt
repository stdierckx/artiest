package be.thalos.artiest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * A thin ring under the hovering pen, showing what the tool covers.
 *
 * **Why it exists.** An eraser has no mark to look at until after it has
 * removed something, so without an outline the only way to find out how much it
 * takes is to take it and undo. The user asked for exactly this — "a very thin
 * circle, so the user can predict himself what is going to be erased" — and the
 * same argument applies, more weakly, to the pencil: the size slider is a
 * number in document pixels, and the ring is what turns it into a width you can
 * see before you commit to it.
 *
 * **It is a Compose overlay and not something the view draws.** The canvas is a
 * `SurfaceView` with a front-buffered layer: anything drawn into it goes through
 * the renderer's callbacks, which run on the render thread and exist to put ink
 * down at the lowest latency the device allows. Adding a hover ring there would
 * put a UI concern on the one path with a measured budget, and it would have to
 * be erased again from a front buffer that is deliberately never cleared. A
 * sibling in the `Box` costs one composition and is drawn by the platform above
 * the surface.
 *
 * **Everything is read through lambdas.** [at], [diameterPx] and [erasing] are
 * called inside the draw lambda, so a hover moving at 200 Hz invalidates the
 * draw phase and nothing else: no recomposition, no layout, no allocation of a
 * new modifier chain. Passing the values directly would recompose this
 * composable on every hover sample the digitizer sends.
 *
 * **Two rings and not one**, the same trick the colour puck uses. There is no
 * single ring colour that stays visible against both white paper and the dark
 * desk, so a dark ring is drawn just outside a light one and whichever has
 * contrast is the one you see.
 */
@Composable
fun BrushCursor(
    at: () -> Offset,
    diameterPx: () -> Float,
    erasing: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val centre = at()
        if (centre == Offset.Unspecified) return@Canvas
        val d = diameterPx()
        // Below about four pixels across the two rings are a dot, and a dot
        // under the pen tip is indistinguishable from a speck of ink the user
        // is about to try to rub off.
        if (!(d > MIN_VISIBLE_PX)) return@Canvas
        val r = d / 2f
        val erase = erasing()
        drawCircle(
            color = if (erase) DARK_ERASE else DARK,
            radius = r + HAIR,
            center = centre,
            style = Stroke(width = HAIR),
        )
        drawCircle(
            color = if (erase) LIGHT_ERASE else LIGHT,
            radius = r,
            center = centre,
            style = Stroke(width = HAIR),
        )
    }
}

/**
 * One pixel, near enough. Not a `dp`: the ring's job is to be the thinnest
 * visible line, and on a 230 dpi panel a 1dp stroke is 1.4 px and reads as
 * heavier than the ink it is measuring.
 */
private const val HAIR = 1f

/** See [BrushCursor]. */
private const val MIN_VISIBLE_PX = 4f

private val DARK = Color(0x99000000)
private val LIGHT = Color(0x99FFFFFF)

/**
 * The eraser's ring is the same shape in a different colour, because the two
 * tools are the same nib and the only thing that distinguishes them at a glance
 * is that one is about to remove something.
 */
private val DARK_ERASE = Color(0xAA202020)
private val LIGHT_ERASE = Color(0xAAFFC08A)
