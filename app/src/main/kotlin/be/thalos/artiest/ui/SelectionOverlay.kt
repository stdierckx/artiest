package be.thalos.artiest.ui

import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Marching ants: the selection's outline, and the marquee under the pen.
 *
 * **A Compose overlay and not something the renderer draws**, for the reason
 * `BrushCursor` gives at length and for one more of its own: this animates.
 * Every frame of that animation drawn through the canvas would be a full
 * `SurfaceControl` transaction — `GestureController` exists entirely to stop
 * that happening for gestures — and it would have to be erased again from a
 * front buffer that is deliberately never cleared. Here it is a draw pass on a
 * sibling view and the canvas never hears about it.
 *
 * **Stroked in view space from a document-space path.** The selection is a
 * region of the *page*, so it has to move with a pan and scale with a zoom; but
 * the line itself must stay one screen pixel wide with a fixed dash length at
 * every zoom, or it disappears when you zoom out and turns into a row of slabs
 * when you zoom in. Transforming the path and stroking the result gives both:
 * geometry in document units, weight and dashes in screen units.
 *
 * **A solid light line under a moving dark dash**, which is what makes them
 * ants rather than a dotted line. Two dashed passes offset by half a period
 * would be the textbook version and costs a second `DashPathEffect` per frame
 * for the same picture; a solid line underneath is already the "other colour",
 * and it has the property the two-tone ring has — there is no single colour
 * that stays visible against white paper, black ink and the dark desk, and this
 * way one of the two always has contrast.
 *
 * **Everything is read through lambdas**, so a pan at 90 Hz, a marquee at 200
 * Hz and the animation's own frame clock all invalidate the draw phase and
 * nothing else: no recomposition, no layout, no new modifier chain.
 *
 * The paths go through `nativeCanvas` rather than being converted to Compose
 * `Path`s. The conversion would copy every segment on every frame to arrive at
 * an `android.graphics.Path` again.
 */
@Composable
fun SelectionOverlay(
    /** The committed selection, in document coordinates, or null. */
    selection: () -> Path?,
    /** The marquee being dragged, in document coordinates, or null. */
    marquee: () -> Path?,
    /**
     * The centrelines of the picked strokes, in document coordinates, or null.
     *
     * Drawn solid rather than as ants, and that is the whole of telling the two
     * apart: a pixel selection is a *region* and its boundary is the thing to
     * show, while a stroke selection is a *set of objects* and the thing to
     * show is the objects. Marching ants along fifty spines would read as fifty
     * very thin regions.
     */
    picked: () -> Path? = { null },
    /** Document to view, rebuilt by the caller when the canvas moves. */
    docToView: () -> Matrix,
    /** Whether there is anything to draw. Gates the animation. */
    showing: Boolean,
    modifier: Modifier = Modifier,
) {
    // One path and two paints for the overlay's life. This draws on every frame
    // of an animation that runs for as long as something is selected, and a
    // `Path` per frame is a native allocation per frame.
    val transformed = remember { Path() }
    val light = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = LIGHT
        }
    }
    val dark = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR
            isAntiAlias = true
            color = DARK
        }
    }
    /**
     * Two paints again, and the same argument as [LIGHT] and [DARK]: there is
     * no single colour that stays visible against white paper, black ink and a
     * grey desk. A wide pale stroke under a narrow bright one shows on both.
     */
    val glow = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR * 5f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
            color = PICK_GLOW
        }
    }
    val core = remember {
        Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = HAIR * 1.6f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
            color = PICK_CORE
        }
    }

    val phase = remember { mutableFloatStateOf(0f) }

    // Only while there is an outline. An animation ticking over an empty page
    // is a frame callback a second and a wakeup a frame for a picture of
    // nothing, and `showing` is false for almost all of a drawing session.
    if (showing) {
        LaunchedEffect(Unit) { march(phase) }
    }

    Canvas(modifier) {
        val matrix = docToView()
        // Read inside the draw lambda, which is what makes the animation a
        // draw-phase invalidation rather than a recomposition.
        dark.pathEffect = DashPathEffect(DASH, phase.floatValue)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            selection()?.let { ants(native, it, matrix, transformed, light, dark) }
            marquee()?.let { ants(native, it, matrix, transformed, light, dark) }
            picked()?.let { spine(native, it, matrix, transformed, glow, core) }
        }
    }
}

/**
 * Advance the dash phase forever, on the frame clock.
 *
 * `withInfiniteAnimationFrameNanos` and not a timer: it is driven by the same
 * clock the frame is, it stops when the composition leaves, and — the reason it
 * matters here — it is disabled wholesale when animations are turned off in the
 * system's accessibility settings, which is exactly the right behaviour for a
 * decoration that moves.
 *
 * Negative, so the dashes travel *along* the path in the direction it was
 * drawn. Positive looks like the outline is being unwound.
 */
private suspend fun march(phase: MutableFloatState) {
    var startNanos = 0L
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            if (startNanos == 0L) startNanos = now
            val seconds = (now - startNanos) / 1e9f
            phase.floatValue = -(seconds * SPEED_PX_PER_SECOND) % PERIOD
        }
    }
}

/**
 * One outline: a solid light line, then a moving dark dash over it.
 *
 * The scratch path is shared between the committed selection and the marquee,
 * which is safe because they are drawn one after the other on one thread and
 * neither keeps it.
 */
private fun ants(
    canvas: android.graphics.Canvas,
    path: Path,
    matrix: Matrix,
    scratch: Path,
    light: android.graphics.Paint,
    dark: android.graphics.Paint,
) {
    if (path.isEmpty) return
    path.transform(matrix, scratch)
    canvas.drawPath(scratch, light)
    canvas.drawPath(scratch, dark)
}

/**
 * The picked strokes' spines, wide pale first and narrow bright over it.
 *
 * Shares [ants]' scratch path for the same reason and with the same safety:
 * they are drawn one after another on one thread and neither keeps it.
 */
private fun spine(
    canvas: android.graphics.Canvas,
    path: Path,
    matrix: Matrix,
    scratch: Path,
    glow: android.graphics.Paint,
    core: android.graphics.Paint,
) {
    if (path.isEmpty) return
    path.transform(matrix, scratch)
    canvas.drawPath(scratch, glow)
    canvas.drawPath(scratch, core)
}

/** See `BrushCursor.HAIR`: on a 230 dpi panel a 1dp stroke already reads heavy. */
private const val HAIR = 1.4f

/**
 * Six on, six off, in **view** pixels.
 *
 * Long enough that the gap reads as a gap at 230 dpi and short enough that the
 * dashes follow a lasso's curve rather than chording across it.
 */
private val DASH = floatArrayOf(6f, 6f)

private const val PERIOD = 12f

/** One period a second: visibly moving, and not asking to be watched. */
private const val SPEED_PX_PER_SECOND = 12f

private const val DARK = 0xE6101010.toInt()
private const val LIGHT = 0xE6FFFFFF.toInt()

/**
 * The picked-stroke highlight: a pale halo and a blue core.
 *
 * Blue rather than the ants' black and white, because the two have to be told
 * apart at a glance — one says "these pixels", the other says "these strokes",
 * and they can be on screen together. The halo is what keeps the core visible
 * where a stroke is drawn in the same blue.
 */
private const val PICK_GLOW = 0x99FFFFFF.toInt()
private const val PICK_CORE = 0xF23C7DFF.toInt()
