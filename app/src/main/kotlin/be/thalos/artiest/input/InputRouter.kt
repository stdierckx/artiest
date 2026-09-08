package be.thalos.artiest.input

import android.view.MotionEvent
import android.view.View
import be.thalos.artiest.engine.input.Decision
import be.thalos.artiest.engine.input.ExclusivityState
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.RejectionCounters
import be.thalos.artiest.engine.input.StrokeExclusivity
import be.thalos.artiest.engine.input.StrokeExclusivity.Companion.NO_POINTER
import be.thalos.artiest.engine.input.ToolClass
import be.thalos.artiest.engine.input.toolClassOf
import be.thalos.artiest.engine.trace.TraceRecorder

/**
 * Where the input path delivers its results.
 *
 * A callback interface rather than a sealed event class: this is called at
 * 250-320 Hz, an event object per call is an allocation, and it would have to
 * own the sample list, which kills the single reusable scratch buffer the whole
 * path is built around.
 *
 * No default implementations, on purpose. Every method here corresponds to
 * something a consumer must decide about — the difference between
 * [onStrokeEnd] and [onStrokeCancel] is whether a stroke is committed to the
 * layer or discarded — and a silently inherited no-op is how a cancelled stroke
 * ends up committed anyway.
 */
interface InkInputSink {

    /** A new stroke owns [pointerId] until an end or a cancel. */
    fun onStrokeBegin(pointerId: Int)

    /**
     * Samples for the live stroke, oldest first.
     *
     * [samples] is the router's scratch list, valid for the duration of this
     * call only: the next event refills it. Consume it synchronously and do not
     * retain the list. The [PenSample]s inside it are immutable and may be
     * retained — `TraceRecorder` does exactly that.
     *
     * Iterate it by index. `for (s in samples)` over an `ArrayList` compiles to
     * an `iterator()` call, which is one heap allocation per event on this
     * path; the spike does it inside a loop whose own comment claims the hot
     * path allocates nothing.
     */
    fun onStrokeSamples(samples: ArrayList<PenSample>)

    /** The stroke finished and should be committed. Its final samples came first. */
    fun onStrokeEnd()

    /** The stroke must be discarded. No final sample arrives with it. */
    fun onStrokeCancel()

    fun onGestureBegin()

    /**
     * Current positions of the pointers the gesture owns, as three parallel
     * arrays valid for the duration of the call. Structure-of-arrays for the
     * same reason [onStrokeSamples] hands over a scratch list: no per-event
     * object.
     */
    fun onGesturePointers(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int)

    fun onGestureEnd()

    fun onGestureCancel()

    /**
     * Pen entered or left range, from hover. Presence only — there is no height
     * in it, because `AXIS_DISTANCE` reads zero on this digitizer.
     */
    fun onPenPresence(inRange: Boolean)
}

/**
 * The MotionEvent-facing half of the input path: extraction, and nothing else.
 *
 * Every rule about who is allowed to draw lives in [StrokeExclusivity], which
 * is pure and exhaustively tested on the JVM. What is left here is the part
 * that cannot be — `findPointerIndex`, `getToolType`, the history accessors,
 * `requestUnbufferedDispatch` — and it must stay that way: a branch on router
 * state added to this class is a rule that has escaped the tests, in the module
 * whose test source set did not exist until this work item.
 *
 * This is also where the MotionEvent dies. Nothing downstream of the sink sees
 * one, no reference to one is retained past the call that delivered it, and the
 * event is expanded **exactly once** per pointer into [scratch]. The spike
 * expanded every event twice, in `PenCapture` and again in the view.
 *
 * UI thread only, and unsynchronized, like the machine it drives.
 */
class InputRouter(private val sink: InkInputSink) {

    private val machine = StrokeExclusivity()

    /** The one expansion buffer. 64 slots is ~16 events of history at 4 samples each. */
    private val scratch = ArrayList<PenSample>(64)

    private val gestureIds = IntArray(StrokeExclusivity.MAX_POINTERS)
    private val gestureXs = FloatArray(StrokeExclusivity.MAX_POINTERS)
    private val gestureYs = FloatArray(StrokeExclusivity.MAX_POINTERS)

    /**
     * Off, which is the opposite of the spike's default.
     *
     * `requestUnbufferedDispatch` stops the framework batching events to the
     * frame boundary. Phase 0 measured a regression from it, but measured it
     * against a render path that no longer exists, so the number says nothing
     * about the front-buffered path W8 ships and the honest state is unverified
     * rather than bad. It stays in the code behind this toggle so W9 can A/B it
     * against a real render loop; until then, nothing turns it on.
     *
     * It is bound to [Decision.STROKE_BEGIN] and not to
     * ACTION_DOWN. A stroke that starts from ACTION_POINTER_DOWN — pen touching
     * down with a palm already resting — is a real case on this hardware, and
     * keying off the action the way the spike does skips it silently.
     */
    var unbufferedDispatch: Boolean = false

    /**
     * Attach a recorder to capture the raw input stream. Null, always, unless
     * someone deliberately turns recording on.
     *
     * Recording taps **before** the routing decision, so a trace carries the
     * palm contacts and the pen-during-gesture sequences the router rejected.
     * Recording what the router emitted instead would put the exclusion rules
     * permanently outside the regression net and let the router agree with its
     * own conclusions on replay; the post-router stream is derivable from a raw
     * trace by running the machine over it, and not the other way round.
     *
     * It costs a second expansion of every event, including pointers the router
     * will drop, and it appends to whatever the caller is buffering into on the
     * input thread. That is why it is off: the input path's allocation budget is
     * only assertable with recording off.
     */
    var recorder: TraceRecorder? = null

    /** Per-MotionEvent counter for the trace. Two pointers from one event share it. */
    private var seq = 0

    /**
     * What was rejected, and why strokes ended the way they did.
     *
     * Always on, unlike [recorder]. It is nine `Long`s and a `LongArray`
     * indexed by an enum, incremented once per event with no allocation and no
     * branch on router state — the cost is nothing and the questions it answers
     * cannot be answered any other way. Three of them have no JVM answer at
     * all: whether this digitizer sets `FLAG_CANCELED`, whether the framework
     * ever cancels this app's gesture, and whether a pointer is ever lost
     * mid-stroke. See [RejectionCounters].
     */
    val rejections = RejectionCounters()

    val state: ExclusivityState get() = machine.state

    val penStrokeId: Int get() = machine.penStrokeId

    val penInRange: Boolean get() = machine.penInRange

    /**
     * Feed a touch event. Always returns true.
     *
     * Unconditionally, including for an event the router drops entirely.
     * Returning false hands the gesture to an ancestor view, which can hand it
     * back as ACTION_CANCEL and chop a live stroke in half — the exact failure
     * the exclusivity rule exists to prevent.
     */
    fun onTouchEvent(event: MotionEvent, view: View): Boolean {
        val action = pointerActionOf(event.actionMasked) ?: return true
        val pointerId = event.actionPointerId(action)
        val tool = event.actionToolClass(action)
        val canceled = event.isPointerCanceled()

        record(event, action, pointerId, canceled)

        val decision = machine.route(action, pointerId, tool, canceled)
        // Counted before dispatch, so the tally describes what the machine
        // decided even if a sink throws on its way through.
        rejections.record(action, tool, canceled, decision)
        dispatch(decision, event, view, pointerId)
        return true
    }

    /**
     * Feed a hover event, and return whether it was a pen hover this acted on.
     *
     * Kept because the framework sends ACTION_HOVER_* to `View.onHoverEvent`
     * and never to `onTouchEvent` — a routing detail that makes hardware which
     * hovers perfectly well look like hardware that cannot, if you only
     * override the latter. It survives as a presence signal and nothing more:
     * the distance axis is dead, so there is no height here to reject a palm
     * with, and the event is not expanded into [scratch] because no stage in
     * Phase 1 reads a hover sample.
     *
     * The caller still returns `super.onHoverEvent(event)`, so the framework's
     * own hover handling is untouched.
     */
    fun onHoverEvent(event: MotionEvent): Boolean {
        val action = hoverActionOf(event.actionMasked) ?: return false
        // Recorded on the shared seq counter, so a file preserves how hover and
        // touch interleaved. Presence is a real router output with a sink
        // callback of its own, and without these lines a replay would flip it
        // only at DOWN and UP — a different signal from the session the trace
        // claims to reproduce, on precisely the behaviour no JVM test reaches.
        // Zero samples, because nothing in Phase 1 reads a hover sample.
        recorder?.record(seq++, action, event.getPointerId(0), emptyList())
        // Hover carries exactly one pointer, so index 0 is the pen or nothing.
        val decision = machine.hover(action, toolClassOf(event.getToolType(0)))
        emitPresence(decision)
        return true
    }

    /**
     * Drop whatever was live, with no event to drop it with.
     *
     * ACTION_CANCEL covers most interruptions but not all of them: an activity
     * stopped, a view detached or a dialog taking focus can leave ACTION_MOVE
     * as the last event ever delivered, and the machine then holds a stale
     * pointer id forever. The view calls this from `onWindowFocusChanged(false)`
     * and `onDetachedFromWindow`, and the activity from `onPause`.
     */
    fun abandon() {
        val decision = machine.abandon()
        rejections.recordAbandon(decision)
        dispatch(decision, event = null, view = null, actionPointerId = NO_POINTER)
    }

    /**
     * Order is the content of this function.
     *
     * Cancels go first: an ACTION_DOWN arriving in a stale state discards the
     * old stroke and begins a new one in the same decision, and a begin
     * delivered before that cancel leaves the consumer holding two live
     * strokes. Presence-on precedes the begin because the pen is in range
     * before it draws, presence-off trails the end for the same reason, and
     * samples always precede the end that commits them.
     */
    private fun dispatch(decision: Int, event: MotionEvent?, view: View?, actionPointerId: Int) {
        if (decision has Decision.STROKE_CANCEL) sink.onStrokeCancel()
        if (decision has Decision.GESTURE_CANCEL) sink.onGestureCancel()

        if (decision has Decision.STROKE_END) {
            // The lifting event's own history batch belongs to the stroke. No
            // spike view expanded it — two ignore ACTION_UP's coordinates
            // entirely and the third reads only event.x/y — so the last few
            // hundred microseconds of every stroke never reached the line. The
            // machine cannot fix this for us: it has no event, and it
            // deliberately sets no samples bit on a lift.
            val e = checkNotNull(event) { "STROKE_END without an event to end on" }
            expandInto(e, actionPointerId)
            sink.onStrokeEnd()
        }
        if (decision has Decision.GESTURE_END) sink.onGestureEnd()

        if (decision has Decision.PEN_PRESENCE_ON) sink.onPenPresence(true)

        if (decision has Decision.STROKE_BEGIN) {
            val e = checkNotNull(event) { "STROKE_BEGIN without an event" }
            val v = checkNotNull(view) { "STROKE_BEGIN without a view" }
            if (unbufferedDispatch) {
                // The MotionEvent overload, which lasts for this gesture, not
                // the API-33 source overload, which is persistent: a permanent
                // request would also unbuffer the gestures the router drops.
                v.requestUnbufferedDispatch(e)
            }
            sink.onStrokeBegin(machine.penStrokeId)
        }

        if (decision has Decision.STROKE_SAMPLES) {
            val e = checkNotNull(event) { "STROKE_SAMPLES without an event" }
            val id = machine.penStrokeId
            if (!expandInto(e, id)) {
                // The stroke's pointer is not in this event: it left without a
                // lift. Nothing was appended, so nothing goes out; the machine
                // turns the loss into a cancel, and that cancel is the only
                // decision this call can produce, so the recursion is one deep.
                val lost = machine.route(PointerAction.POINTER_LOST, id, ToolClass.PEN, true)
                rejections.record(PointerAction.POINTER_LOST, ToolClass.PEN, true, lost)
                dispatch(lost, e, view, id)
            }
        }

        if (decision has Decision.GESTURE_BEGIN) sink.onGestureBegin()
        if (decision has Decision.GESTURE_POINTERS) {
            val e = checkNotNull(event) { "GESTURE_POINTERS without an event" }
            emitGesturePointers(e)
        }

        if (decision has Decision.PEN_PRESENCE_OFF) sink.onPenPresence(false)
    }

    /**
     * Expands [pointerId]'s samples out of [event] and hands them to the sink.
     * False, with nothing emitted, when that pointer is not in this event.
     */
    private fun expandInto(event: MotionEvent, pointerId: Int): Boolean {
        scratch.clear()
        if (!event.collectSamples(scratch, pointerId)) return false
        sink.onStrokeSamples(scratch)
        return true
    }

    private fun emitGesturePointers(event: MotionEvent) {
        var n = 0
        for (i in 0 until machine.gesturePointerCount) {
            val id = machine.gesturePointerAt(i)
            val index = event.findPointerIndex(id)
            // A gesture pointer missing from this event is normal on the lift
            // that removed it. Skip it rather than reporting a stale position;
            // the machine's own bookkeeping is what says the gesture is over.
            if (index < 0) continue
            gestureIds[n] = id
            gestureXs[n] = event.getX(index)
            gestureYs[n] = event.getY(index)
            n++
        }
        if (n > 0) sink.onGesturePointers(gestureIds, gestureXs, gestureYs, n)
    }

    private fun emitPresence(decision: Int) {
        if (decision has Decision.PEN_PRESENCE_ON) sink.onPenPresence(true)
        if (decision has Decision.PEN_PRESENCE_OFF) sink.onPenPresence(false)
    }

    /**
     * Writes the raw event to the trace, before anything decides what it means.
     *
     * A MOVE is recorded for every pointer it carries, because the exclusion
     * rules are exactly what a replay needs to exercise and a trace holding
     * only the pen's pointer cannot show a palm arriving. Every other action
     * concerns one pointer and is recorded for that one; a CANCEL concerns none
     * and records no samples at all, since its coordinates are stale.
     *
     * Reuses [scratch], which is safe only because this runs to completion
     * before the routing decision refills it. Nothing else may interleave.
     */
    private fun record(
        event: MotionEvent,
        action: PointerAction,
        actionPointerId: Int,
        canceled: Boolean,
    ) {
        val rec = recorder ?: return
        if (action == PointerAction.MOVE) {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                scratch.clear()
                event.collectSamples(scratch, id)
                rec.record(seq, action, id, scratch, canceled)
            }
        } else {
            scratch.clear()
            if (actionPointerId != NO_POINTER) event.collectSamples(scratch, actionPointerId)
            rec.record(seq, action, actionPointerId, scratch, canceled)
        }
        seq++
    }

    private infix fun Int.has(bit: Int): Boolean = this and bit != 0
}
