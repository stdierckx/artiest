package be.thalos.artiest.ui

import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The selection's outline, and the marquee under the pen.
 *
 * **A Compose overlay and not something the renderer draws**, for the reason
 * `BrushCursor` gives at length and for one more of its own: the outline
 * animates. Every frame of that animation drawn through the canvas would be a
 * full `SurfaceControl` transaction — `GestureController` exists entirely to
 * stop that happening for gestures — and it would have to be erased again from
 * a front buffer that is deliberately never cleared.
 *
 * **Stroked in view space from a document-space path.** The selection is a
 * region of the *page*, so it has to move with a pan and scale with a zoom; but
 * the outline itself must stay one screen pixel wide with a fixed dash length
 * at every zoom, or it vanishes when you zoom out and turns into a row of slabs
 * when you zoom in. Transforming the path and stroking the result is what gives
 * both: geometry in document units, weight in screen units.
 *
 * **Everything is read through lambdas**, so a pan at 90 Hz or a marquee at 200
 * Hz invalidates the draw phase and nothing else.
 *
 * The `android.graphics.Path` goes through `nativeCanvas` rather than being
 * converted to a Compose `Path`. The conversion would be a copy of every
 * segment on every frame, and the object on the other side is an
 * `android.graphics.Path` again anyway.
 */
@Composable
fun SelectionOverlay(
    /** The committed selection, in document coordinates, or null. */
    selection: () -> Path?,
    /** The marquee being dragged, in document coordinates, or null. */
    marquee: () -> Path?,
    /** Document to view, rebuilt by the caller when the canvas moves. */
    docToView: () -> Matrix,
    modifier: Modifier = Modifier,
) {
    // One path and two paints for the overlay's life. This draws on every frame
    // of an animation that runs for as long as something is selected, and a
    // Path per frame is a native allocation per frame.
    val transformed = remember { Path() }
    val dark = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = DARK
        }
    }
    val light = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = LIGHT
        }
    }

    Canvas(modifier) {
        val matrix = docToView()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            selection()?.let { outline(native, it, matrix, transformed, dark, light) }
            marquee()?.let { outline(native, it, matrix, transformed, dark, light) }
        }
    }
}

/**
 * One outline, twice: a dark line and a light one over it.
 *
 * The same trick `BrushCursor` uses and for the same reason — there is no
 * single colour that stays visible against white paper, black ink and the dark
 * desk — except that here the two strokes are the *same* line rather than
 * neighbours, so what the eye sees is a light line with a dark halo.
 */
private fun outline(
    canvas: android.graphics.Canvas,
    path: Path,
    matrix: Matrix,
    scratch: Path,
    dark: android.graphics.Paint,
    light: android.graphics.Paint,
) {
    if (path.isEmpty) return
    path.transform(matrix, scratch)
    canvas.drawPath(scratch, dark)
    canvas.drawPath(scratch, light)
}

/** See `BrushCursor.HAIR`: on a 230 dpi panel a 1dp stroke already reads heavy. */
private const val HAIR = 1.4f

private const val DARK = 0xCC000000.toInt()
private const val LIGHT = 0xE6FFFFFF.toInt()
