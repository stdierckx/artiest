package be.thalos.artiest.canvas

import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import be.thalos.artiest.engine.input.CancelCause
import be.thalos.artiest.engine.xform.CanvasTransform

/**
 * W13 on the device: eight contact sequences that must each end a specific way,
 * driven through the real view.
 *
 * The rules themselves are `StrokeExclusivity`'s and `:engine` proves them
 * exhaustively with no Android at all. What that cannot reach is everything
 * between the machine's decision and the pixels — `InputRouter`'s extraction,
 * `InkSurfaceView`'s wet/dry split, `CanvasFrontBufferedRenderer.cancel()`, the
 * batch pool's bookkeeping and `Document`'s commit queue — and those are
 * exactly where a rejected contact turns back into ink. So this replays the
 * sequences through `onTouchEvent` and then asks the *document* what happened.
 *
 * Two things about the shape of it are deliberate.
 *
 * **The first case draws.** Seven of the eight assert that something did *not*
 * happen, and a harness whose synthetic pen cannot draw at all passes every one
 * of them. So the run opens with the same geometry and the same event shapes
 * committing a real stroke, and if that control fails the whole run is reported
 * failed no matter what the rest said.
 *
 * **Every case checks the pool.** `DabBatchPool.inFlight` must be back to zero
 * and `spills` must not have moved by the end of each case. That is not
 * incidental tidiness: through W12 a cancel released no batches at all —
 * `cancel()` invokes neither draw callback, so the watermark it set was never
 * read — and the symptom was not a visible cancel bug but a ring that lost a
 * slot per cancelled stroke and eventually spilled on every acquire. A
 * rejection harness that did not look at the pool would have missed the only
 * defect W13 actually found.
 *
 * Not a measurement of anything. It allocates freely, dispatches from the UI
 * thread and builds a fresh event per action, so it clears `InputStats` on both
 * ends rather than leaving the readout showing its own cost as the app's.
 */
class RejectionStress(private val view: InkSurfaceView) {

    var running: Boolean = false
        private set

    /** One line per case, in run order. Read by the readout. */
    val results = ArrayList<String>()

    var passed: Int = 0
        private set

    var failed: Int = 0
        private set

    private val choreographer = Choreographer.getInstance()

    private var caseIndex = 0
    private var settle = 0
    private var onDone: (() -> Unit)? = null

    /**
     * The case's events, one per frame.
     *
     * Paced rather than dispatched in a burst, and the difference is not
     * cosmetic. The framework batches touch to one `MotionEvent` per frame, so
     * a stroke delivered as thirteen events inside a single frame submits
     * thirteen wet batches before the render thread has run once — which
     * exhausts an eight-slot ring and spills, with nothing wrong anywhere. The
     * first run of this harness did exactly that and reported six spills
     * against its own control. What is being checked here is what the pool does
     * around a *cancel*, so the delivery either has to look like the framework's
     * or the pool cannot be part of the verdict at all.
     */
    private val steps = ArrayList<() -> Unit>()
    private var stepIndex = 0

    private var downTimeMs = 0L
    private var eventTimeMs = 0L

    // Snapshot taken as a case starts, compared when it settles.
    private var strokesBefore = 0
    private var spillsBefore = 0L
    private var canceledBefore = 0L
    private var penDroppedBefore = 0L
    private var fingerBeginsBefore = 0L
    private val causesBefore = LongArray(CancelCause.entries.size)
    private var transformBefore: CanvasTransform = CanvasTransform.IDENTITY

    private val frame = Choreographer.FrameCallback { onFrame() }

    private val cases = listOf(
        Case("pen draws (control)", ::drivePenAlone, ::verifyDrew),
        Case("palm first, pen draws", ::drivePalmThenPen, ::verifyDrew),
        Case("palm mid-stroke", ::drivePalmMidStroke, ::verifyPalmDidNotDraw),
        Case("ACTION_CANCEL", ::driveCancelAction) { verifyDiscarded(CancelCause.CANCEL_ACTION) },
        Case("FLAG_CANCELED lift", ::driveCanceledLift) { verifyDiscarded(CancelCause.FLAG) },
        Case("lone finger", ::driveLoneFinger, ::verifyNothingHappened),
        Case("pen back (reports FINGER)", ::drivePenBack, ::verifyNothingHappened),
        Case("pen during a gesture", ::drivePenDuringGesture, ::verifyPenWasLockedOut),
    )

    /** Run every case, then call [onDone] on the UI thread. */
    fun start(onDone: () -> Unit = {}) {
        if (running) return
        running = true
        this.onDone = onDone
        results.clear()
        passed = 0
        failed = 0
        caseIndex = 0
        settle = 0
        view.stats.reset()
        beginCase()
        choreographer.postFrameCallback(frame)
    }

    private fun beginCase() {
        val c = cases[caseIndex]
        strokesBefore = view.document.strokeCount
        spillsBefore = view.batches.spills
        canceledBefore = view.strokesCanceled
        penDroppedBefore = view.rejections.penContactsDropped
        fingerBeginsBefore = view.rejections.fingerStrokeBegins
        for (cause in CancelCause.entries) causesBefore[cause.ordinal] = view.rejections.cancelsBy(cause)
        transformBefore = view.transform
        downTimeMs = System.nanoTime() / 1_000_000L
        eventTimeMs = downTimeMs
        steps.clear()
        stepIndex = 0
        c.drive()
    }

    private fun onFrame() {
        if (!running) return
        if (stepIndex < steps.size) {
            steps[stepIndex++].invoke()
            choreographer.postFrameCallback(frame)
            return
        }
        // Three frames between the last event of a case and reading its result.
        // The commit, the cancel and the pool's release all cross to the render
        // thread, so reading the document in the same frame the events were
        // dispatched would test the dispatch and not the outcome.
        if (settle < SETTLE_FRAMES) {
            settle++
            choreographer.postFrameCallback(frame)
            return
        }
        finishCase()
        settle = 0
        caseIndex++
        if (caseIndex >= cases.size) {
            running = false
            view.stats.reset()
            val done = onDone
            onDone = null
            done?.invoke()
            return
        }
        beginCase()
        choreographer.postFrameCallback(frame)
    }

    private fun finishCase() {
        val c = cases[caseIndex]
        val failure = c.verify() ?: verifyPool()
        if (failure == null) {
            passed++
            results += "PASS ${c.name}"
        } else {
            failed++
            results += "FAIL ${c.name}: $failure"
        }
    }

    // --- verdicts ----------------------------------------------------------

    /** Every case: the ring is whole again and never had to allocate. */
    private fun verifyPool(): String? {
        if (view.batches.inFlight != 0) return "${view.batches.inFlight} batches still in flight"
        if (view.batches.spills != spillsBefore) {
            return "${view.batches.spills - spillsBefore} spills"
        }
        return null
    }

    private fun verifyDrew(): String? {
        val committed = view.document.strokeCount - strokesBefore
        if (committed != 1) return "$committed strokes committed, expected 1"
        if (view.lastStrokeDabs <= 0) return "committed a stroke of ${view.lastStrokeDabs} dabs"
        return null
    }

    /**
     * The palm landed mid-stroke and lifted before the pen did, so the pen's
     * pointer index moved under it. The assertion is geometric rather than a
     * count: one stroke committed, and its bounds nowhere near where the palm
     * was resting.
     */
    private fun verifyPalmDidNotDraw(): String? {
        verifyDrew()?.let { return it }
        val bounds = view.document.strokeBoundsAt(view.document.strokeCount - 1)
        view.transform.viewToDoc(palmX(), palmY(), docPoint)
        val px = docPoint[0]
        val py = docPoint[1]
        val touched = px >= bounds.left - PALM_MARGIN_DOC && px <= bounds.right + PALM_MARGIN_DOC &&
            py >= bounds.top - PALM_MARGIN_DOC && py <= bounds.bottom + PALM_MARGIN_DOC
        if (touched) return "stroke bounds reach the palm at $px,$py"
        return null
    }

    /** Cancelled: no ink, one cancel, and it was the cancel we caused. */
    private fun verifyDiscarded(cause: CancelCause): String? {
        val committed = view.document.strokeCount - strokesBefore
        if (committed != 0) return "$committed strokes committed"
        if (view.strokesCanceled - canceledBefore != 1L) {
            return "${view.strokesCanceled - canceledBefore} strokes cancelled, expected 1"
        }
        if (view.rejections.cancelsBy(cause) - causesBefore[cause.ordinal] != 1L) {
            return "cancelled, but not by $cause"
        }
        return null
    }

    /** No ink, from any of the three ways a contact could have produced some. */
    private fun verifyNoInk(): String? {
        val committed = view.document.strokeCount - strokesBefore
        if (committed != 0) return "$committed strokes committed"
        if (view.strokesCanceled != canceledBefore) return "a stroke was cancelled, so one began"
        if (view.rejections.fingerStrokeBegins != fingerBeginsBefore) {
            return "a non-pen contact began a stroke"
        }
        return null
    }

    /** No ink and no pan: a lone contact is neither a stroke nor a gesture. */
    private fun verifyNothingHappened(): String? {
        verifyNoInk()?.let { return it }
        // Referential, not equality: a transform the canvas never moved is the
        // same instance, and a solver that recomputed an identical one would
        // still mean a gesture opened that should not have.
        if (view.transform !== transformBefore) return "the canvas moved"
        return null
    }

    /**
     * The pen drew nothing — and the fingers *did* pan, which is what stops
     * this case passing for the wrong reason. A run where the gesture was
     * dropped too would satisfy "the pen drew nothing" perfectly.
     */
    private fun verifyPenWasLockedOut(): String? {
        verifyNoInk()?.let { return it }
        if (view.rejections.penContactsDropped - penDroppedBefore != 1L) {
            return "the pen contact was not counted as locked out"
        }
        if (view.transform === transformBefore) return "the fingers never panned"
        return null
    }

    // --- sequences ---------------------------------------------------------

    private fun drivePenAlone() {
        dispatch(MotionEvent.ACTION_DOWN, penAt(0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, penAt(i / MOVES.toFloat()))
        dispatch(MotionEvent.ACTION_UP, penAt(1f))
    }

    /**
     * The ordinary way an artist starts: hand down first, then the pen. The pen
     * therefore arrives as ACTION_POINTER_DOWN, which is the case the spike had
     * no branch for at all.
     */
    private fun drivePalmThenPen() {
        dispatch(MotionEvent.ACTION_DOWN, palm())
        dispatch(pointerDown(1), palm(), penAt(0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, palm(), penAt(i / MOVES.toFloat()))
        dispatch(pointerUp(1), palm(), penAt(1f))
        dispatch(MotionEvent.ACTION_UP, palm())
    }

    /**
     * The palm arrives after the pen and leaves before it, so the pen slides
     * from index 0 to index 1 and back. A consumer holding the index it was
     * handed at DOWN draws the palm here; one holding the id does not.
     */
    private fun drivePalmMidStroke() {
        dispatch(MotionEvent.ACTION_DOWN, penAt(0f))
        dispatch(MotionEvent.ACTION_MOVE, penAt(0.2f))
        dispatch(pointerDown(1), penAt(0.2f), palm())
        for (i in 3..MOVES) dispatch(MotionEvent.ACTION_MOVE, penAt(i / MOVES.toFloat()), palm())
        dispatch(pointerUp(1), penAt(1f), palm())
        dispatch(MotionEvent.ACTION_UP, penAt(1f))
    }

    private fun driveCancelAction() {
        dispatch(MotionEvent.ACTION_DOWN, penAt(0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, penAt(i / MOVES.toFloat()))
        dispatch(MotionEvent.ACTION_CANCEL, penAt(1f))
    }

    /**
     * The framework's own palm rejection, as it would arrive: the stroke lifts
     * normally and the *event* carries `FLAG_CANCELED`.
     *
     * Synthesized, and that is the honest status of this case. Phase 0 counted
     * the flag but exported no session with a palm on the glass, so whether
     * this digitizer's driver ever sets it is still unmeasured — the router
     * counts it on real events ([RejectionCounters.canceledFlagEvents]) so the
     * question can be answered by using the tablet rather than by reasoning.
     */
    private fun driveCanceledLift() {
        dispatch(MotionEvent.ACTION_DOWN, penAt(0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, penAt(i / MOVES.toFloat()))
        dispatch(MotionEvent.ACTION_UP, penAt(1f), flags = MotionEvent.FLAG_CANCELED)
    }

    /**
     * One finger, dragging. It must draw nothing *and* pan nothing: a gesture
     * takes two, because a hand lowered onto the glass before the pen would
     * otherwise lock the pen out for as long as it rested.
     */
    private fun driveLoneFinger() {
        dispatch(MotionEvent.ACTION_DOWN, fingerAt(FINGER_ID, 0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, fingerAt(FINGER_ID, i / MOVES.toFloat()))
        dispatch(MotionEvent.ACTION_UP, fingerAt(FINGER_ID, 1f))
    }

    /**
     * The pen's back end, which on this hardware reports `TOOL_TYPE_FINGER` —
     * measured in Phase 0, and the reason flip-to-erase is not merely absent
     * but unbuildable. It classes as a finger everywhere, so alone on the glass
     * it is a disowned contact and draws nothing.
     */
    private fun drivePenBack() {
        dispatch(MotionEvent.ACTION_DOWN, penBackAt(0f))
        for (i in 1..MOVES) dispatch(MotionEvent.ACTION_MOVE, penBackAt(i / MOVES.toFloat()))
        dispatch(MotionEvent.ACTION_UP, penBackAt(1f))
    }

    /**
     * The one rejection that can be felt as a fault rather than as the rule
     * working: two fingers are panning, the pen touches down, and it is locked
     * out for the whole life of that contact — not until the fingers clear.
     * A stroke begun mid-contact would have no DOWN, so no onset ramp and no
     * unbuffered-dispatch request: a line appearing out of nowhere.
     */
    private fun drivePenDuringGesture() {
        dispatch(MotionEvent.ACTION_DOWN, fingerAt(FINGER_ID, 0f))
        dispatch(pointerDown(1), fingerAt(FINGER_ID, 0f), fingerAt(FINGER2_ID, 0f))
        dispatch(pointerDown(2), fingerAt(FINGER_ID, 0f), fingerAt(FINGER2_ID, 0f), penAt(0f))
        for (i in 1..MOVES) {
            dispatch(
                MotionEvent.ACTION_MOVE,
                fingerAt(FINGER_ID, i / MOVES.toFloat()),
                fingerAt(FINGER2_ID, i / MOVES.toFloat()),
                penAt(i / MOVES.toFloat()),
            )
        }
        dispatch(pointerUp(2), fingerAt(FINGER_ID, 1f), fingerAt(FINGER2_ID, 1f), penAt(1f))
        dispatch(pointerUp(1), fingerAt(FINGER_ID, 1f), fingerAt(FINGER2_ID, 1f))
        dispatch(MotionEvent.ACTION_UP, fingerAt(FINGER_ID, 1f))
    }

    // --- pointers ----------------------------------------------------------

    private class Contact(val id: Int, val toolType: Int, val x: Float, val y: Float, val pressure: Float)

    private val docPoint = FloatArray(2)

    private fun penAt(t: Float) = Contact(
        PEN_ID,
        MotionEvent.TOOL_TYPE_STYLUS,
        view.width * 0.3f + t * view.width * 0.4f,
        view.height * 0.45f,
        // Firm and constant. The control has to commit dabs, and RoundPen's
        // onset ramp plus a pressure sweep would make "few dabs" a plausible
        // outcome of a correct run.
        0.8f,
    )

    private fun penBackAt(t: Float) = Contact(
        PEN_ID,
        MotionEvent.TOOL_TYPE_FINGER,
        view.width * 0.3f + t * view.width * 0.4f,
        view.height * 0.45f,
        0.8f,
    )

    private fun fingerAt(id: Int, t: Float) = Contact(
        id,
        MotionEvent.TOOL_TYPE_FINGER,
        view.width * (if (id == FINGER_ID) 0.2f else 0.6f) + t * view.width * 0.15f,
        view.height * 0.7f,
        1f,
    )

    /** The resting hand: far from the pen, and it stays put. */
    private fun palm() = Contact(PALM_ID, MotionEvent.TOOL_TYPE_FINGER, palmX(), palmY(), 1f)

    private fun palmX() = view.width * 0.12f

    private fun palmY() = view.height * 0.85f

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerUp(index: Int) =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /**
     * Queue one event for the next frame. The coordinates are computed now, by
     * the sequence that asked for them; only the delivery is deferred.
     */
    private fun dispatch(action: Int, vararg contacts: Contact, flags: Int = 0) {
        val list = contacts.toList()
        steps += { send(action, list, flags) }
    }

    private fun send(action: Int, contacts: List<Contact>, flags: Int) {
        eventTimeMs += EVENT_INTERVAL_MS
        val props = Array(contacts.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = contacts[i].id
                toolType = contacts[i].toolType
            }
        }
        val coords = Array(contacts.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = contacts[i].x
                y = contacts[i].y
                pressure = contacts[i].pressure
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTimeMs, eventTimeMs, action, contacts.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private class Case(
        val name: String,
        val drive: () -> Unit,
        val verify: () -> String?,
    )

    companion object {
        private const val PEN_ID = 7
        private const val PALM_ID = 3
        private const val FINGER_ID = 4
        private const val FINGER2_ID = 5

        /** Moves per sequence: enough travel to commit real dabs. */
        private const val MOVES = 12

        /**
         * One frame apart at 60 Hz, because that is the pacing the events are
         * actually delivered at — see [steps]. Not the digitizer's 3.1 ms
         * interval: an event *carries* a frame's worth of samples, and these
         * carry one apiece.
         */
        private const val EVENT_INTERVAL_MS = 16L

        private const val SETTLE_FRAMES = 3

        /**
         * How far outside a committed stroke's bounds the palm has to be, in
         * document pixels. Generous on purpose: the claim is "the palm's
         * contact is nowhere in this stroke", and the palm sits most of a page
         * away from the pen, so a tight margin would only make the case
         * sensitive to the brush radius.
         */
        private const val PALM_MARGIN_DOC = 100f
    }
}
