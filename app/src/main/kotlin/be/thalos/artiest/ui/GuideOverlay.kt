package be.thalos.artiest.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The rulers on the page, drawn over it.
 *
 * A second pass beside `SelectionOverlay`, and the reason is that document's
 * word for word: this is a thing drawn over the canvas that is not part of any
 * layer, the canvas is a front buffer that is deliberately never cleared, and
 * every frame of it drawn through the renderer would be a `SurfaceControl`
 * transaction. Here it is a draw pass on a sibling view and the canvas never
 * hears about it.
 *
 * **Geometry in document units, weight in screen units**, for the ants' reason:
 * a guide is a thing on the *page*, so it pans and zooms with the page, and the
 * line drawn for it has to stay one screen pixel wide at every zoom or it
 * disappears when you zoom out and becomes a slab when you zoom in.
 *
 * It does **not** march. Ants move because they are the boundary of something
 * that is being edited and has to be told apart from the drawing; a ruler is
 * furniture, it is on screen for the length of a drawing session, and a moving
 * line in the corner of the eye for an hour is the kind of decoration that
 * makes people turn a feature off. Nothing here animates, so nothing here wakes
 * the frame clock.
 *
 * ## Two greys, and a third state
 *
 * A live guide is a solid line with a pale halo, for `SelectionOverlay`'s
 * reason: there is no single colour that stays visible against white paper,
 * black ink and a grey desk. A guide that is switched off is the same line,
 * dashed and dimmer — visible, because it is still a thing on the page you can
 * pick up, and quiet, because it is not doing anything.
 *
 * A handle whose point is off the glass is drawn at the edge of it, hollow —
 * see [handleViewPosition]. That is not a nicety: a vanishing point belongs off
 * the page, and at a fit-to-screen zoom the page *is* the screen, so without it
 * a two-point set has two handles and no way to reach either.
 *
 * The handles are drawn **only while arranging**. That is Ik13's one interface
 * decision and `docs/guides-plan.md` trap 4 is why: dragging a guide competes
 * with panning, and `docs/panels-plan.md` has already settled that class of
 * question — arrange mode is the answer, and a second answer would make the
 * first unreliable. Outside it a guide is furniture the pen draws against and
 * cannot knock over.
 */
@Composable
fun GuideOverlay(
    /** The guides that are on, in document coordinates. */
    live: () -> Path?,
    /** The guides that are off, in document coordinates. */
    dim: () -> Path?,
    /**
     * The handles, in document coordinates, as x, y pairs, or null.
     *
     * Null outside arrange mode, which is what makes them invisible there
     * rather than a flag inside this function: a caller that has decided not to
     * offer handles should not be building their positions every frame.
     */
    handles: () -> FloatArray?,
    /** Document to view, rebuilt by the caller when the canvas moves. */
    docToView: () -> Matrix,
    modifier: Modifier = Modifier,
) {
    // One path and four paints for the overlay's life. This draws on every pan
    // and zoom frame, and a `Path` per frame is a native allocation per frame.
    val transformed = remember { Path() }
    val halo = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR * 3f
            isAntiAlias = true
            color = HALO
        }
    }
    val core = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = LIVE
        }
    }
    val off = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = OFF
            pathEffect = android.graphics.DashPathEffect(DASH, 0f)
        }
    }
    val knob = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
            color = LIVE
        }
    }
    val knobEdge = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR * 1.5f
            isAntiAlias = true
            color = HALO
        }
    }
    /** A ring for a handle whose point is off the glass. See the draw below. */
    val hollow = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR * 1.6f
            isAntiAlias = true
            color = LIVE
        }
    }
    val point = remember { FloatArray(2) }

    Canvas(modifier) {
        val matrix = docToView()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            dim()?.let { line(native, it, matrix, transformed, halo, off) }
            live()?.let { line(native, it, matrix, transformed, halo, core) }
            val grabs = handles() ?: return@drawIntoCanvas
            val w = size.width
            val h = size.height
            var i = 0
            while (i + 1 < grabs.size) {
                // A handle whose point is off the glass is drawn at the edge,
                // and drawn **hollow** so it reads as a marker for something
                // out there rather than as the thing itself. A vanishing point
                // belongs off the page, so this is the normal case for the one
                // guide that most needs dragging. See `handleViewPosition`.
                val far = handleViewPosition(grabs[i], grabs[i + 1], matrix, w, h, point)
                if (far) {
                    native.drawCircle(point[0], point[1], HANDLE + 2f, knobEdge)
                    native.drawCircle(point[0], point[1], HANDLE - 1f, hollow)
                } else {
                    native.drawCircle(point[0], point[1], HANDLE, knob)
                    native.drawCircle(point[0], point[1], HANDLE, knobEdge)
                }
                i += 2
            }
        }
    }
}

/**
 * One set of guides: a pale wide pass, then the line itself over it.
 *
 * The scratch path is shared between the live guides and the dim ones, which is
 * safe for `SelectionOverlay`'s reason: they are drawn one after the other on
 * one thread and neither keeps it.
 */
private fun line(
    canvas: android.graphics.Canvas,
    path: Path,
    matrix: Matrix,
    scratch: Path,
    halo: android.graphics.Paint,
    ink: android.graphics.Paint,
) {
    if (path.isEmpty) return
    path.transform(matrix, scratch)
    canvas.drawPath(scratch, halo)
    canvas.drawPath(scratch, ink)
}

/** See `SelectionOverlay.HAIR`: on a 230 dpi panel a 1dp stroke already reads heavy. */
private const val HAIR = 1.4f

/** Big enough to put a finger on, small enough not to hide the line under it. */
private const val HANDLE = 8f

/** Longer than the ants', because this is a quiet line and not a moving one. */
private val DASH = floatArrayOf(10f, 8f)

/**
 * Teal, and not the ants' black-and-white or the picked strokes' blue.
 *
 * Three things can be on the glass at once — a selection, a set of picked
 * strokes and the guides — and a hand has to tell them apart without reading
 * anything. They are already two different colours; this is the third, and it
 * is the coolest of the three because it is the one that is *not* about
 * something you have chosen.
 */
private const val LIVE = 0xF224B0A4.toInt()
private const val OFF = 0x8024B0A4.toInt()
private const val HALO = 0x99FFFFFF.toInt()
