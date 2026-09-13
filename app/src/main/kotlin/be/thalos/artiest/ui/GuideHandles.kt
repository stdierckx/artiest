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
                // A handle before a body, always: a handle sits on its own
                // line, so a hit test that took the body first could never grab
                // an end.
                val handle = guides.handleNear(at.first, at.second, slop)
                var line: Guideline? = handle?.first
                    ?: guides.lineNear(at.first, at.second, slop)
                    ?: return@awaitEachGesture
                val index = handle?.second ?: Guideline.NO_HANDLE

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
