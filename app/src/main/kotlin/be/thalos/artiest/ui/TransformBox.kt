package be.thalos.artiest.ui

import android.graphics.Matrix
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The box you drag to move, turn and resize the floating pixels.
 *
 * ## It owns the pen, and only while a float is live
 *
 * While pixels are in the air there is nothing to draw, so there is nothing to
 * arbitrate: this takes every pointer over the canvas and the ink path never
 * sees one. That is why it is a Compose `pointerInput` rather than another fork
 * in `InputRouter` — palm rejection and the two-finger gesture exist to decide
 * *between* drawing and panning, and neither question arises here.
 *
 * It is deliberately **not** the two-finger pinch. That gesture belongs to the
 * canvas and must keep belonging to it: `StrokeExclusivity` is the settled
 * answer to that class of problem, and a second answer would make the first one
 * unreliable. Corner handles and a knob are the answer a tablet already knows.
 *
 * ## Two speeds
 *
 * The user's decision, and it settles the phase's most expensive open question.
 * The **box** follows the hand at whatever rate the pen reports, because it is
 * a few lines in an overlay and costs a draw pass. The **pixels** are redrawn
 * at most every [PREVIEW_MS] milliseconds, because drawing them means a dry
 * render — the compositor repaints the stack and resamples the float through
 * its matrix — and at 90 Hz that is a redraw asking for more than it can have.
 *
 * Eight refreshes a second is enough to see what is landing where and correct
 * before letting go, and about an eighth of the duty cycle even in the
 * full-page worst case. The trade it makes is that a fast drag puts the box
 * some way ahead of the pixels under it, and that is the right way round: the
 * box is where the pixels *will* land.
 *
 * The last update is unconditional. A gesture whose final movement fell inside
 * the throttle would otherwise drop the pixels one step behind the box, which
 * is small, permanent, and exactly the kind of thing that reads as the gesture
 * not quite landing — the same defect `GestureController.end` exists to
 * prevent.
 */
@Composable
fun TransformBox(
    /** Where the pixels came from, in document coordinates, or null. */
    sourceBounds: Rect?,
    /** Changes when a different float is lifted, so the transform starts over. */
    token: Int,
    /** Document to view, live. */
    docToView: () -> Matrix,
    /** The user's transform so far, in document space. Throttled. */
    onMatrix: (Matrix) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sourceBounds == null) return

    // The transform under the hand. An `android.graphics.Matrix` and not
    // Compose state, because it is mutated in place at pointer rate; `tick` is
    // what tells the draw phase it moved.
    val user = remember(token) { Matrix() }
    val tick = remember(token) { mutableIntStateOf(0) }
    val session = remember(token) { DragSession() }

    Canvas(
        modifier.pointerInput(token) {
            detectDragGestures(
                onDragStart = { at ->
                    session.begin(at, corners(sourceBounds, user, docToView()))
                },
                onDrag = { change, _ ->
                    change.consume()
                    session.drag(change.position, sourceBounds, user, docToView())
                    tick.intValue++
                    if (session.due(change.uptimeMillis)) onMatrix(Matrix(user))
                },
                onDragEnd = {
                    // Unconditional. See the class header.
                    onMatrix(Matrix(user))
                },
                onDragCancel = { onMatrix(Matrix(user)) },
            )
        },
    ) {
        tick.intValue
        val quad = corners(sourceBounds, user, docToView())
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            drawLine(SHADOW, a, b, strokeWidth = HAIR * 3f)
            drawLine(EDGE, a, b, strokeWidth = HAIR)
        }
        for (point in quad) {
            drawCircle(SHADOW, HANDLE + HAIR, point, style = Stroke(width = HAIR * 2f))
            drawCircle(EDGE, HANDLE, point, style = Stroke(width = HAIR))
        }
        val knob = knobOf(quad)
        drawLine(EDGE, midpoint(quad[0], quad[1]), knob, strokeWidth = HAIR)
        drawCircle(SHADOW, HANDLE + HAIR, knob, style = Stroke(width = HAIR * 2f))
        drawCircle(EDGE, HANDLE, knob, style = Stroke(width = HAIR))
    }
}

/**
 * What the hand is doing, decided once when it lands and held for the drag.
 *
 * Decided once for the reason the erase mode and the marquee mode are: a
 * gesture that changed meaning halfway through is a gesture that cannot be
 * corrected, because the correction would change it again.
 */
private class DragSession {

    private enum class Mode { MOVE, SCALE, ROTATE, NONE }

    private var mode = Mode.NONE
    private var lastPushMillis = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastAngle = 0f
    private var lastRadius = 1f
    private val previous = FloatArray(2)
    private val inverse = Matrix()

    fun begin(at: Offset, quad: Array<Offset>) {
        val knob = knobOf(quad)
        val centre = centreOf(quad)
        mode = when {
            near(at, knob) -> Mode.ROTATE
            quad.any { near(at, it) } -> Mode.SCALE
            inside(at, quad) -> Mode.MOVE
            else -> Mode.NONE
        }
        anchorX = centre.x
        anchorY = centre.y
        lastAngle = angle(centre, at)
        lastRadius = hypot(at.x - centre.x, at.y - centre.y).coerceAtLeast(1f)
        previous[0] = at.x
        previous[1] = at.y
        lastPushMillis = 0L
    }

    /**
     * Fold one pointer position into [user], which is in **document** space.
     *
     * Every mode works from the two points mapped back through the canvas
     * transform rather than from a view-space delta scaled by hand. That is not
     * tidiness: the canvas can be rotated as well as zoomed, and a delta
     * divided by the scale would drag the pixels off at an angle to the finger
     * as soon as it was.
     */
    fun drag(at: Offset, source: Rect, user: Matrix, docToView: Matrix) {
        if (mode == Mode.NONE) return
        if (!docToView.invert(inverse)) return
        val now = floatArrayOf(at.x, at.y)
        val was = floatArrayOf(previous[0], previous[1])
        inverse.mapPoints(now)
        inverse.mapPoints(was)
        when (mode) {
            Mode.MOVE -> user.postTranslate(now[0] - was[0], now[1] - was[1])

            Mode.ROTATE -> {
                val centre = Offset(anchorX, anchorY)
                val turned = angle(centre, at) - lastAngle
                lastAngle = angle(centre, at)
                val pivot = pivotOf(source, user)
                user.postRotate(Math.toDegrees(turned.toDouble()).toFloat(), pivot.x, pivot.y)
            }

            Mode.SCALE -> {
                val radius = hypot(at.x - anchorX, at.y - anchorY).coerceAtLeast(1f)
                val factor = (radius / lastRadius).coerceIn(MIN_STEP, MAX_STEP)
                lastRadius = radius
                val pivot = pivotOf(source, user)
                user.postScale(factor, factor, pivot.x, pivot.y)
            }

            Mode.NONE -> Unit
        }
        previous[0] = at.x
        previous[1] = at.y
    }

    /** Whether the pixels are due a redraw. See the class header. */
    fun due(nowMillis: Long): Boolean {
        if (nowMillis - lastPushMillis < PREVIEW_MS) return false
        lastPushMillis = nowMillis
        return true
    }

    /** The middle of the transformed box, in document coordinates. */
    private fun pivotOf(source: Rect, user: Matrix): Offset {
        val point = floatArrayOf(source.exactCenterX(), source.exactCenterY())
        user.mapPoints(point)
        return Offset(point[0], point[1])
    }

    private companion object {
        /** A gesture cannot scale by more than this in one sample. A NaN guard. */
        const val MAX_STEP = 1.2f
        const val MIN_STEP = 0.8f
    }
}

/** The four corners of the transformed source rectangle, in view coordinates. */
private fun corners(source: Rect, user: Matrix, docToView: Matrix): Array<Offset> {
    val points = floatArrayOf(
        source.left.toFloat(), source.top.toFloat(),
        source.right.toFloat(), source.top.toFloat(),
        source.right.toFloat(), source.bottom.toFloat(),
        source.left.toFloat(), source.bottom.toFloat(),
    )
    user.mapPoints(points)
    docToView.mapPoints(points)
    return Array(4) { Offset(points[it * 2], points[it * 2 + 1]) }
}

/**
 * The rotate knob: out beyond the top edge, along the box's own normal.
 *
 * Placed from the geometry rather than "above", so that it stays off the top
 * edge once the box has been turned — a knob that drifted inside the box would
 * be a knob you cannot tell from the pixels under it.
 */
private fun knobOf(quad: Array<Offset>): Offset {
    val top = midpoint(quad[0], quad[1])
    val bottom = midpoint(quad[3], quad[2])
    val dx = top.x - bottom.x
    val dy = top.y - bottom.y
    val length = hypot(dx, dy).coerceAtLeast(1f)
    return Offset(top.x + dx / length * KNOB_GAP, top.y + dy / length * KNOB_GAP)
}

private fun centreOf(quad: Array<Offset>): Offset {
    var x = 0f
    var y = 0f
    for (point in quad) {
        x += point.x
        y += point.y
    }
    return Offset(x / 4f, y / 4f)
}

private fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun angle(from: Offset, to: Offset): Float = atan2(to.y - from.y, to.x - from.x)

private fun near(at: Offset, target: Offset): Boolean =
    hypot(at.x - target.x, at.y - target.y) <= TOUCH_SLOP

/**
 * Whether [at] is inside the quadrilateral, by the winding of its edges.
 *
 * A point-in-polygon test and not a bounding box, because the box is rotated as
 * often as not and a bounding-box hit would let a drag start on ground the user
 * can see is outside the selection.
 */
private fun inside(at: Offset, quad: Array<Offset>): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val a = quad[i]
        val b = quad[(i + 1) % 4]
        val cross = (b.x - a.x) * (at.y - a.y) - (b.y - a.y) * (at.x - a.x)
        val side = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
        if (side == 0) continue
        if (sign == 0) sign = side else if (sign != side) return false
    }
    return true
}

/** See `SelectionOverlay.HAIR`. */
private const val HAIR = 1.4f

/** Big enough to see, small enough not to hide what is under it. */
private const val HANDLE = 9f

/** How far the rotate knob sits beyond the top edge, in view pixels. */
private const val KNOB_GAP = 46f

/** A finger is about 9 mm; at 230 dpi that is 80 px, and half of it is a fair reach. */
private const val TOUCH_SLOP = 40f

/**
 * The 120 ms the user asked for: *"it should update once every 120ms, so the
 * user knows where it is being dropped."*
 */
const val PREVIEW_MS = 120L

private val EDGE = Color(0xE6FFFFFF)
private val SHADOW = Color(0x99000000)
