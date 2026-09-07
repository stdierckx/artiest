package be.thalos.artiest.input

import android.os.Build
import android.view.MotionEvent
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.StrokeExclusivity.Companion.NO_POINTER
import be.thalos.artiest.engine.input.ToolClass
import be.thalos.artiest.engine.input.toolClassOf

/**
 * Everything that reads a `MotionEvent`, and the only place in the app that
 * does apart from [InputRouter] driving these functions.
 *
 * The boundary is the point: a MotionEvent enters at
 * `View.onTouchEvent`/`onHoverEvent`, becomes [PenSample]s and
 * [PointerAction]s here, and dies at the router. Nothing downstream can reach
 * back for a field nobody normalised, which is what makes the pipeline
 * replayable from a trace file with no device attached.
 */

/**
 * History position for the event's *current* sample, which the historical
 * accessors have no index for.
 *
 * Named and internal rather than private because [sampleAt] takes it, and W11's
 * `Predictor` is the second caller: a predicted point is the current state of a
 * synthesized event.
 */
internal const val CURRENT = -1

/**
 * Appends every sample [pointerId] carries in this event, oldest first.
 *
 * **Appends. The caller clears.** The whole input path shares one scratch list,
 * so a missing `clear()` is not the visible duplicate-sample bug it would be
 * with a list per caller — it is unbounded growth on a 250-320 Hz path. The
 * spike's version documented neither half of this contract.
 *
 * The pointer is named by **id**, and the index is resolved here, on every
 * event. Indices shuffle the moment a pointer lifts: with a palm and a pen
 * down, the palm lifting first slides the pen from index 1 to index 0, and a
 * caller holding the index it was handed at DOWN then draws the palm. Ids are
 * stable for the life of a contact and this is the only conversion.
 *
 * Returns false, having appended nothing, when [pointerId] is not in this
 * event. That is not an error — a pointer can leave mid-gesture — but it is the
 * one case where indexing anyway reads whatever pointer took over the slot, so
 * the router turns it into [PointerAction.POINTER_LOST] rather than a sample.
 *
 * The digitizer runs at 246.85 Hz against a 60 Hz panel, so one ACTION_MOVE
 * routinely carries several samples in its history buffer. Even with
 * unbuffered dispatch requested, 4-6% of events still carry 2-4 historical
 * samples, so this loop is load-bearing rather than a legacy path.
 */
fun MotionEvent.collectSamples(into: MutableList<PenSample>, pointerId: Int): Boolean {
    val pi = findPointerIndex(pointerId)
    if (pi < 0) return false
    for (h in 0 until historySize) into += sampleAt(pi, h, PenSample.Source.HISTORICAL)
    into += sampleAt(pi, CURRENT, PenSample.Source.CURRENT)
    return true
}

/**
 * One sample, from the history buffer at [historyPos] or from the event's
 * current state when that is [CURRENT].
 *
 * [source] is a parameter rather than something derived from [historyPos],
 * which is what left `Source.PREDICTED` with no producer in the spike. The two
 * stop being the same question here: W11's `Predictor` reads the current state
 * of a synthesized event and stamps it PREDICTED, and it needs this function to
 * do it rather than a second copy of the accessor table.
 *
 * `internal` for that reason — W11's producer is in this package — and not
 * public, because a source argument that disagrees with the position it reads
 * is a lie the trace format then records as truth.
 */
internal fun MotionEvent.sampleAt(
    pointerIndex: Int,
    historyPos: Int,
    source: PenSample.Source,
): PenSample {
    // Short locals only so the accessor table below fits a line each.
    val pi = pointerIndex
    val h = historyPos
    val historical = h != CURRENT
    return PenSample(
        x = if (historical) getHistoricalX(pi, h) else getX(pi),
        y = if (historical) getHistoricalY(pi, h) else getY(pi),
        pressure = if (historical) getHistoricalPressure(pi, h) else getPressure(pi),
        tilt = if (historical) getHistoricalAxisValue(MotionEvent.AXIS_TILT, pi, h)
               else getAxisValue(MotionEvent.AXIS_TILT, pi),
        orientation = if (historical) getHistoricalOrientation(pi, h)
                      else getOrientation(pi),
        // Read despite being measured dead — 400 hover samples, all 0.0 — because
        // it is a column of the trace format and dropping it would change the
        // format for a value a future digitizer might actually report. Nothing
        // branches on it; two axis reads per sample is the whole cost.
        distance = if (historical) getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, pi, h)
                   else getAxisValue(MotionEvent.AXIS_DISTANCE, pi),
        // Both read from the event's current state and stamped onto every
        // historical sample in the batch, because Android exposes no historical
        // variant of either. Unfixable here, and it matters downstream:
        // TraceRecorder writes them as per-sample truth, so a barrel button
        // pressed mid-batch replays backdated across up to ~4 samples.
        toolType = getToolType(pi),
        buttonState = buttonState,
        eventTimeNanos = if (historical) historicalTimeNanos(h) else timeNanos(),
        source = source,
    )
}

/**
 * Nanosecond event times landed in API 34. The MovinkPad runs 14, so it takes
 * the precise path; the millisecond fallback exists because minSdk is 29 and a
 * comparison device would otherwise crash rather than lose resolution.
 */
private fun MotionEvent.timeNanos(): Long =
    if (Build.VERSION.SDK_INT >= 34) eventTimeNanos else eventTime * 1_000_000L

private fun MotionEvent.historicalTimeNanos(h: Int): Long =
    if (Build.VERSION.SDK_INT >= 34) getHistoricalEventTimeNanos(h)
    else getHistoricalEventTime(h) * 1_000_000L

/**
 * The masked action as the exclusivity machine names it, or null for an action
 * the router has no rule for.
 *
 * Null rather than a catch-all member: ACTION_SCROLL, ACTION_OUTSIDE and the
 * button press/release pair all arrive at `onTouchEvent` and none of them
 * changes who owns the glass. Barrel buttons in particular need no action of
 * their own — `buttonState` rides on every sample already.
 *
 * Hover is deliberately absent. It comes through a different `View` callback
 * and cannot alter exclusivity state, so [hoverActionOf] is a separate
 * function and neither will return the other's members.
 */
fun pointerActionOf(actionMasked: Int): PointerAction? = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> PointerAction.DOWN
    MotionEvent.ACTION_POINTER_DOWN -> PointerAction.POINTER_DOWN
    MotionEvent.ACTION_MOVE -> PointerAction.MOVE
    MotionEvent.ACTION_POINTER_UP -> PointerAction.POINTER_UP
    MotionEvent.ACTION_UP -> PointerAction.UP
    MotionEvent.ACTION_CANCEL -> PointerAction.CANCEL
    else -> null
}

/** The hover half of [pointerActionOf]. Null for anything else. */
fun hoverActionOf(actionMasked: Int): PointerAction? = when (actionMasked) {
    MotionEvent.ACTION_HOVER_ENTER -> PointerAction.HOVER_ENTER
    MotionEvent.ACTION_HOVER_MOVE -> PointerAction.HOVER_MOVE
    MotionEvent.ACTION_HOVER_EXIT -> PointerAction.HOVER_EXIT
    else -> null
}

/**
 * The id of the pointer [action] concerns, or `NO_POINTER` for the two actions
 * that concern the whole event.
 *
 * `getPointerId(actionIndex)`, never `getPointerId(0)`: on an
 * ACTION_POINTER_DOWN or ACTION_POINTER_UP the index is packed into the action
 * word, and index 0 is some other contact entirely.
 */
fun MotionEvent.actionPointerId(action: PointerAction): Int =
    if (action == PointerAction.MOVE || action == PointerAction.CANCEL) NO_POINTER
    else getPointerId(actionIndex)

/** The routing class of the pointer [action] concerns. Meaningless for MOVE and CANCEL. */
fun MotionEvent.actionToolClass(action: PointerAction): ToolClass =
    if (action == PointerAction.MOVE || action == PointerAction.CANCEL) ToolClass.FINGER
    else toolClassOf(getToolType(actionIndex))

/**
 * Whether the framework flagged this lift as unintentional — its own palm
 * rejection, which lands as FLAG_CANCELED on ACTION_POINTER_UP and
 * ACTION_CANCEL.
 *
 * Guarded on API 33 because that is when the platform started *setting* it; the
 * constant itself is API 30 and inlined at compile time, so the read is safe
 * back to minSdk 29 and simply never true there. Whether this particular
 * digitizer's driver ever fires it is unmeasured — Phase 0 counted the flag but
 * no session with a palm on the glass was ever exported — so nothing here
 * depends on it firing.
 */
fun MotionEvent.isPointerCanceled(): Boolean =
    Build.VERSION.SDK_INT >= 33 && (flags and MotionEvent.FLAG_CANCELED) != 0
