package be.thalos.artiest.ui

import android.graphics.Matrix
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import be.thalos.artiest.doc.GuideSet
import be.thalos.artiest.doc.Guideline

/**
 * Dragging a ruler, and **only in arrange mode**.
 *
 * `docs/guides-plan.md` trap 4: *"Dragging a guide competes with panning.
 * Two-finger pan, pinch-zoom and 'drag this vanishing point' all want the same
 * gestures, and deciding it late means rewriting it."* This repo has settled
 * that class of question twice, and `docs/panels-plan.md`'s third stop
 * condition says it plainly — arrange mode is the answer, and a second answer
 * would make the first unreliable.
 *
 * So it costs a mode switch to move a ruler, which is a thing done a few times
 * a drawing, and it buys back every gesture on the canvas for drawing, which is
 * what happens two hundred times a minute.
 *
 * ## It takes a pointer only when there is something under it
 *
 * `awaitEachGesture` rather than `detectDragGestures`, and that is the whole of
 * how this coexists with the dock. Arrange mode is *also* where toolbars are
 * dragged and shapes are painted, so a layer that swallowed every pointer over
 * the canvas would break both. The down event is tested against the guides
 * first and consumed only if it hit one; otherwise the gesture is let go and
 * whatever is under this gets it.
 *
 * This sits **below** the chrome in the composition, so a drag that starts on a
 * toolbar never reaches here at all — later children are on top and are offered
 * the pointer first.
 */
@Composable
fun GuideHandles(
    guides: GuideSet,
    /** Document to view, live. */
    docToView: () -> Matrix,
    /** A guide moved. Redraws the overlay; the set has already changed. */
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One of each for the life of the layer: this runs at pointer rate.
    val inverse = remember { Matrix() }
    val point = remember { FloatArray(2) }

    // A second scratch, because `handleUnder` walks every handle while `at` is
    // still holding the document point the first one was read into.
    val probe = remember { FloatArray(2) }

    Box(
        modifier.pointerInput(guides) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val m = docToView()
                if (!m.invert(inverse)) return@awaitEachGesture
                val scale = scaleOf(m)
                if (scale <= 0f) return@awaitEachGesture
                val slop = GuideSet.GRAB_VIEW_PX / scale

                var at = toDoc(down.position, inverse, point)
                // **The handles are hit-tested in view space** and the bodies
                // in document space, and that is not an inconsistency: a handle
                // may have been pulled in to the edge of the glass, so where it
                // *is* and where it is *drawn* are two different places. See
                // `handleViewPosition`.
                //
                // A handle before a body, always: a handle sits on its own
                // line, so a hit test that took the body first could never grab
                // an end.
                val handle = handleUnder(
                    guides, down.position, m, size.width.toFloat(), size.height.toFloat(), probe,
                )
                var line: Guideline? = handle?.first
                    ?: guides.lineNear(at.first, at.second, slop)
                    ?: return@awaitEachGesture
                val index = handle?.second ?: Guideline.NO_HANDLE
                val grabbedFar = handle?.third == true

                down.consume()
                var lastX = at.first
                var lastY = at.second
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    at = toDoc(change.position, inverse, point)
                    val held = line ?: break
                    val moved = if (index == Guideline.NO_HANDLE) {
                        held.movedBy(at.first - lastX, at.second - lastY)
                    } else if (grabbedFar) {
                        // A pulled-in marker is a proxy for a point somewhere
                        // off the glass, so the drag moves the point **by** the
                        // distance the finger went rather than **to** where the
                        // finger is. Dragging it to the finger would teleport a
                        // vanishing point onto the page the moment you touched
                        // its marker.
                        held.withPoint(
                            index,
                            held.xAt(index) + (at.first - lastX),
                            held.yAt(index) + (at.second - lastY),
                        )
                    } else {
                        held.withPoint(index, at.first, at.second)
                    }
                    lastX = at.first
                    lastY = at.second
                    // The set is the model and it is edited live, not at the
                    // end: a ruler that only moved when the finger came off
                    // would be a ruler nobody could place. It is cheap --
                    // a `Guideline` is a handful of floats and the snap it
                    // rebuilds is one object.
                    guides.put(moved)
                    line = moved
                    onChanged()
                    change.consume()
                }
            }
        },
    )
}

/** The view point in document coordinates, through an already-inverted matrix. */
private fun toDoc(at: Offset, inverse: Matrix, scratch: FloatArray): Pair<Float, Float> {
    scratch[0] = at.x
    scratch[1] = at.y
    inverse.mapPoints(scratch)
    return scratch[0] to scratch[1]
}

/**
 * Where a guide's handle is **drawn**, which is not always where the point is.
 *
 * A vanishing point belongs off the page — that is what makes it a vanishing
 * point — and at a fit-to-screen zoom the page *is* the screen, so a two-point
 * set's handles are both off the glass and there is no gesture that can reach
 * them. The first version shipped that way: the points were draggable and
 * nobody could drag them.
 *
 * So a handle that falls outside the view is drawn at the edge instead, on the
 * line from the middle of the screen toward the real point, and dragging it
 * moves the point by the same document distance the finger moved. The marker
 * is a proxy and not the thing: you steer a point you cannot see, and the rays
 * move under your hand, which is the feedback that makes it work.
 *
 * Writes the view position into [out] and answers **true when it had to be
 * pulled in**, which is what tells the overlay to draw it hollow.
 */
internal fun handleViewPosition(
    xDoc: Float,
    yDoc: Float,
    docToView: Matrix,
    viewW: Float,
    viewH: Float,
    out: FloatArray,
): Boolean {
    out[0] = xDoc
    out[1] = yDoc
    docToView.mapPoints(out)
    val x = out[0]
    val y = out[1]
    val lo = HANDLE_INSET
    val hiX = viewW - HANDLE_INSET
    val hiY = viewH - HANDLE_INSET
    if (x >= lo && x <= hiX && y >= lo && y <= hiY) return false
    // Toward the middle of the glass, so the marker sits on the side the point
    // is on. A plain per-axis clamp would put a point that is far left and
    // slightly high into the top-left corner, which reads as two points in the
    // same place when both of a pair are off to the left.
    val cx = viewW * 0.5f
    val cy = viewH * 0.5f
    val dx = x - cx
    val dy = y - cy
    var t = 1f
    if (dx > 1e-3f) t = minOf(t, (hiX - cx) / dx)
    if (dx < -1e-3f) t = minOf(t, (lo - cx) / dx)
    if (dy > 1e-3f) t = minOf(t, (hiY - cy) / dy)
    if (dy < -1e-3f) t = minOf(t, (lo - cy) / dy)
    out[0] = cx + dx * t.coerceIn(0f, 1f)
    out[1] = cy + dy * t.coerceIn(0f, 1f)
    return true
}

/**
 * The handle drawn under [atView], as `guide to index to pulled-in`, or null.
 *
 * In **view** pixels, because a handle may be drawn somewhere its point is not.
 * Nearest wins, so two markers that have been pulled into the same corner still
 * give one answer.
 */
private fun handleUnder(
    guides: GuideSet,
    atView: Offset,
    docToView: Matrix,
    viewW: Float,
    viewH: Float,
    scratch: FloatArray,
): Triple<Guideline, Int, Boolean>? {
    var best = GuideSet.GRAB_VIEW_PX
    var found: Triple<Guideline, Int, Boolean>? = null
    for (i in 0 until guides.size) {
        val line = guides[i]
        if (!line.kind.handles) continue
        for (k in 0 until line.pointCount) {
            val far = handleViewPosition(
                line.xAt(k), line.yAt(k), docToView, viewW, viewH, scratch,
            )
            val d = kotlin.math.hypot(scratch[0] - atView.x, scratch[1] - atView.y)
            if (d <= best) {
                best = d
                found = Triple(line, k, far)
            }
        }
    }
    return found
}

/**
 * How far inside the glass a pulled-in handle sits, in view pixels.
 *
 * Far enough that the whole marker is on screen and a finger has something to
 * land on, rather than half a ring hanging off the edge.
 *
 * **A marker can land under a toolbar**, and there is no inset that stops it:
 * a bar can be on any edge, and pushing the marker further in to clear one edge
 * pushes it under whatever is on the other. It is a small problem because of
 * where it happens — this only exists in arrange mode, which is exactly the
 * mode in which a bar can be dragged out of the way. And the precise way to
 * place a far vanishing point is the one that got easier at the same time:
 * zoom out until the point itself is on screen, now that the guides zoom with
 * the page.
 */
internal const val HANDLE_INSET = 26f

/**
 * The zoom, as one number, so a grab radius in screen pixels can be turned into
 * one in document pixels.
 *
 * The geometric mean of the two axis scales, which is `StrokeTransform.scaleOf`'s
 * choice and for its reason: the canvas transform is a uniform scale plus a
 * translation, so the two agree, and the mean is the honest answer if anything
 * ever makes them differ.
 */
private fun scaleOf(m: Matrix): Float {
    val v = FloatArray(9)
    m.getValues(v)
    val sx = kotlin.math.hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    val sy = kotlin.math.hypot(v[Matrix.MSKEW_X], v[Matrix.MSCALE_Y])
    return kotlin.math.sqrt(sx * sy)
}
