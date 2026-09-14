package be.thalos.artiest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The colour under the pen while a pick is in the hand, beside the one it
 * would replace.
 *
 * Lr1. A picker with no readout is a guess: the nib covers the pixel it is
 * reading, a finger's width of glass is a hundred pixels of a zoomed-out page,
 * and "did I get the shadow or the line" is not answerable after the fact. So
 * the answer is on the screen while the pen is down, and **the pick lands on
 * lift** — which is what makes the readout worth having, because there is
 * something to do about it.
 *
 * ## Two halves, not one
 *
 * The ring is split: the colour under the nib on one side, the colour already
 * in the hand on the other. A single swatch answers *what is this*, which the
 * eye can mostly do on its own; the pair answers *is this different from what I
 * have*, which is the question actually being asked when somebody picks a
 * colour out of a photograph. Two near-identical greys read as one swatch and
 * as an obvious seam.
 *
 * ## Where it sits
 *
 * Up and to the left of the nib, far enough that a hand resting below and to
 * the right of the pen is not on top of it. There is no way to be right for
 * both hands — a left-handed user's wrist is below and to the *left* — and this
 * is left as one offset rather than a setting, because the ring is only on the
 * glass while the pen is down and a hand can be lifted for a moment.
 *
 * ## It is a draw-phase read
 *
 * Same rule as the ants and the guides: [at] and [colour] are read **inside**
 * the draw lambda, so a pick moving at pointer rate invalidates a draw and
 * recomposes nothing. See `SelectionOverlay`.
 */
@Composable
fun PickRing(
    /** Where the nib is, in view pixels, or [Offset.Unspecified] for no pick. */
    at: () -> Offset,
    /** The colour under the nib. */
    colour: () -> Int,
    /** The colour it would replace. */
    was: () -> Int,
    modifier: Modifier = Modifier,
) {
    // One of each for the life of the overlay: this draws at pointer rate.
    val fill = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
    }
    val edge = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val box = remember { android.graphics.RectF() }

    Canvas(modifier) {
        val here = at()
        if (here == Offset.Unspecified) return@Canvas
        val argb = colour()
        if (argb == 0) return@Canvas
        val before = was()
        val cx = here.x + OFFSET_X
        val cy = here.y + OFFSET_Y
        box.set(cx - RADIUS, cy - RADIUS, cx + RADIUS, cy + RADIUS)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // The new colour on the left, the old on the right, because a hand
            // reads left to right and the new one is the answer.
            fill.color = argb
            native.drawArc(box, 90f, 180f, true, fill)
            fill.color = before
            native.drawArc(box, 270f, 180f, true, fill)
            // Two rings, white outside and black inside, for `SelectionOverlay`'s
            // reason: there is no single colour that stays visible against white
            // paper, black ink and a grey desk.
            edge.color = HALO
            edge.strokeWidth = EDGE * 2f
            native.drawCircle(cx, cy, RADIUS + EDGE, edge)
            edge.color = INK
            edge.strokeWidth = EDGE
            native.drawCircle(cx, cy, RADIUS, edge)
            // The seam, so two near-identical colours still read as two.
            native.drawLine(cx, cy - RADIUS, cx, cy + RADIUS, edge)
            // And a hair at the nib itself, because the ring is not where the
            // reading is taken and nothing else says where it is.
            native.drawCircle(here.x, here.y, NIB, edge)
        }
    }
}

/** Big enough to judge a colour against another, small enough not to be a panel. */
private const val RADIUS = 34f

/** See the header: clear of a right hand, and on the glass only while picking. */
private const val OFFSET_X = -64f
private const val OFFSET_Y = -64f

private const val EDGE = 1.6f
private const val NIB = 3f
private const val INK = 0xCC000000.toInt()
private const val HALO = 0xCCFFFFFF.toInt()
