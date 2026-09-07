package be.thalos.artiest.engine.input

/**
 * Which of the pointers on the glass, if any, is allowed to draw.
 *
 * Four states, derived rather than stored so they cannot disagree with the
 * bookkeeping they are read from.
 */
enum class ExclusivityState {
    /** Nothing on the glass, nothing owned. */
    IDLE,

    /** A stylus stroke is live and owns exactly one pointer id. */
    PEN,

    /** One or more finger pointers are owned by a transform gesture. */
    GESTURE,

    /**
     * Pointers are physically down and the router owns none of them.
     *
     * The state the requirements do not name and the one that decides whether
     * the app gets a stuck-input bug. Every contact that is not part of a
     * gesture and not the live stroke sits here: a palm resting under a pen
     * that has lifted, and — because a gesture takes two fingers — a lone hand
     * lowered onto empty glass as well. Distinct from [IDLE] only in that
     * pointers are down, and distinct from [GESTURE] because a pen arriving
     * here starts a stroke while a pen arriving during a gesture is dropped.
     *
     * A hand that lands before the pen spends its whole life here, which is the
     * entire point: the pen draws from it, over and over, without the artist
     * having to lift the hand to un-wedge anything.
     */
    DISOWNED,
}

/**
 * What [StrokeExclusivity.route] decided, as a bitmask.
 *
 * A bitmask rather than a sealed class or a list: this is called once per
 * MotionEvent at 250-320 Hz, and an object per event is an allocation that
 * would also have to own the sample list, which kills the single reusable
 * scratch buffer the whole input path is built around.
 *
 * [STROKE_BEGIN] is also the signal for `requestUnbufferedDispatch`. There is
 * deliberately no separate bit for it: binding the call to
 * `actionMasked == ACTION_DOWN` the way the spike does silently skips the case
 * where a stroke starts from ACTION_POINTER_DOWN, and a second bit that means
 * the same thing as the first is a chance for the two to disagree.
 */
object Decision {
    const val NONE = 0

    /** A new stroke owns [StrokeExclusivity.penStrokeId] from this event on. */
    const val STROKE_BEGIN = 1 shl 0

    /** Expand this event for the stroke's pointer and hand the samples down. */
    const val STROKE_SAMPLES = 1 shl 1

    /** The stroke finished normally and should be committed. */
    const val STROKE_END = 1 shl 2

    /**
     * The stroke must be discarded, not committed. Distinct from [STROKE_END]
     * because W10's commit step writes to the layer bitmap: a cancel that
     * commits leaves an undoable stroke drawn by a contact the system already
     * flagged as unintentional.
     */
    const val STROKE_CANCEL = 1 shl 3

    const val GESTURE_BEGIN = 1 shl 4
    const val GESTURE_POINTERS = 1 shl 5
    const val GESTURE_END = 1 shl 6
    const val GESTURE_CANCEL = 1 shl 7

    /** Pen entered range. Never set in the same mask as [PEN_PRESENCE_OFF]. */
    const val PEN_PRESENCE_ON = 1 shl 8

    /** Pen left range. */
    const val PEN_PRESENCE_OFF = 1 shl 9

    private val NAMES = arrayOf(
        "STROKE_BEGIN", "STROKE_SAMPLES", "STROKE_END", "STROKE_CANCEL",
        "GESTURE_BEGIN", "GESTURE_POINTERS", "GESTURE_END", "GESTURE_CANCEL",
        "PEN_PRESENCE_ON", "PEN_PRESENCE_OFF",
    )

    /** For assertion messages and logs. Not on any hot path. */
    fun names(mask: Int): String {
        if (mask == NONE) return "NONE"
        val out = StringBuilder()
        for (i in NAMES.indices) {
            if (mask and (1 shl i) == 0) continue
            if (out.isNotEmpty()) out.append('|')
            out.append(NAMES[i])
        }
        val unknown = mask and ((1 shl NAMES.size) - 1).inv()
        if (unknown != 0) out.append("|0x").append(unknown.toString(16))
        return out.toString()
    }
}

/**
 * The pen/gesture mutual exclusion rule, with no `MotionEvent` in sight.
 *
 * While a pen stroke is live, finger pointers are dropped entirely; while a
 * gesture is live, a pen contact is ignored for its whole lifetime. The reason
 * that survived W1 and W2 is the palm: a palm landing mid-stroke would
 * otherwise chop the line in two and pan the canvas underneath it. (The
 * frozen-transform argument is weaker now that the canvas can move under the
 * pen safely — do not cite it, or the comment will read as obsolete and invite
 * someone to soften the rule, which silently reopens the render decision.)
 *
 * A gesture takes **two** fingers. One finger alone is
 * [ExclusivityState.DISOWNED], never a gesture, because the ordinary way an
 * artist starts is to lower a hand onto the glass and *then* the pen: a lone
 * contact that opened a gesture would lock the pen out for as long as that hand
 * rested, and nothing the pen can do recovers it — lifting and re-placing it
 * lands in the same locked-out cell. The rule cannot live downstream in W12's
 * `GestureController` either, because the lockout is here and fires whether or
 * not anything acts on [Decision.GESTURE_BEGIN]. The price is that a
 * single-finger pan is impossible by construction, which is the intended trade:
 * a resting hand is far more common than a deliberate one-finger drag, and the
 * canvas gesture this project ships is two-finger pan/zoom/rotate anyway.
 *
 * Rejection is by tool type and by stroke exclusivity, never by hover height.
 * `AXIS_DISTANCE` is dead on this digitizer — 400 hover samples, all zero — so
 * there is no distance-based palm rejection to write and no point looking for
 * one.
 *
 * Pure on purpose. Everything decidable without touching an event lives here so
 * it can be driven exhaustively from a JVM test: this is a state machine over
 * multi-pointer sequences whose worst failure — a palm chopping a live stroke —
 * is not reliably reproducible by hand on a tablet. `:app`'s `InputRouter` is
 * left with pure extraction (`findPointerIndex`, `getToolType`, the history
 * accessors, `requestUnbufferedDispatch`) and no branching on state. It is the
 * same split `CanvasTransform` and `Matrices.kt` already use, for the same
 * reason: the Android type is a stub in JVM tests.
 *
 * UI thread only, and not synchronized. W8 puts a render thread next door; it
 * reads the samples this machine's decisions release, never this machine.
 */
class StrokeExclusivity {

    /**
     * Ten because Android's input stack tops out around there. Overflow drops
     * the pointer rather than growing an array, which loses a contact the
     * router would have ignored anyway — an eleventh finger is not a stroke.
     */
    private val down = IntArray(MAX_POINTERS)
    private var downCount = 0

    private val gesture = IntArray(MAX_POINTERS)
    private var gestureCount = 0

    /**
     * Fingers that are down, owned by nobody, and still eligible to become a
     * gesture if a second one joins them.
     *
     * A strict subset of [down]. A finger that arrives while a stroke is live
     * never lands here — it is disowned for the lifetime of the contact, and a
     * palm that has been resting under a stroke must not turn into a pan the
     * moment a second finger touches down beside it.
     */
    private val pending = IntArray(MAX_POINTERS)
    private var pendingCount = 0

    /** The pointer id the live stroke owns, or [NO_POINTER]. */
    var penStrokeId: Int = NO_POINTER
        private set

    private var gestureActive = false

    /**
     * Pen in range, fed only by [hover] and by stroke begin/end.
     *
     * Deliberately a bit alongside the machine rather than a fifth state: no
     * transition below depends on it. A `HOVER_EXIT` that never arrives — the
     * pen yanked away, the window losing focus — would wedge a state machine in
     * a state that blocks drawing, whereas a stale bit can only draw a stale
     * cursor.
     */
    var penInRange: Boolean = false
        private set

    val state: ExclusivityState
        get() = when {
            penStrokeId != NO_POINTER -> ExclusivityState.PEN
            gestureActive -> ExclusivityState.GESTURE
            downCount > 0 -> ExclusivityState.DISOWNED
            else -> ExclusivityState.IDLE
        }

    /** Pointers the framework believes are on the glass, owned or not. */
    val downPointerCount: Int get() = downCount

    fun downPointerAt(i: Int): Int = down[i]

    /** The subset a live gesture owns. Empty unless [state] is GESTURE. */
    val gesturePointerCount: Int get() = gestureCount

    fun gesturePointerAt(i: Int): Int = gesture[i]

    /**
     * Feed one touch action and get back what to emit.
     *
     * [actionPointerId] is the id of the pointer the action concerns, and
     * [NO_POINTER] for MOVE and CANCEL, which concern the whole event.
     * [actionTool] is that pointer's class, ignored for the same two.
     * [canceled] is `event.flags and FLAG_CANCELED != 0` — set by the framework
     * from Android 13 on, and this device runs 14, so it is live. It changes
     * exactly one thing: a pen lifting under the flag ends its stroke with
     * [Decision.STROKE_CANCEL] instead of [Decision.STROKE_END]. For fingers it
     * changes nothing, because fingers never draw and a palm-rejected finger
     * and a lifted finger are the same event downstream.
     *
     * Passing a hover action here is a programming error; hover goes to
     * [hover], which cannot alter exclusivity state.
     */
    fun route(
        action: PointerAction,
        actionPointerId: Int,
        actionTool: ToolClass,
        canceled: Boolean,
    ): Int {
        require(!action.isHover) { "$action is a hover action; call hover() instead" }
        // NO_POINTER is the "no stroke" sentinel, so an action that concerns a
        // pointer must arrive with a real id: a DOWN carrying -1 would set
        // penStrokeId to the sentinel and produce a stroke that emits samples
        // while [state] reads IDLE. Android pointer ids are always >= 0, so
        // this only fires on a caller that forgot to resolve one.
        require(action == PointerAction.MOVE || action == PointerAction.CANCEL || actionPointerId >= 0) {
            "$action concerns a pointer, so actionPointerId must be a real id, not $actionPointerId"
        }
        val presenceBefore = penInRange
        return routeInner(action, actionPointerId, actionTool, canceled) or presenceBits(presenceBefore)
    }

    private fun routeInner(
        action: PointerAction,
        id: Int,
        tool: ToolClass,
        canceled: Boolean,
    ): Int = when (action) {
        // ACTION_DOWN means the framework believes zero pointers were down
        // before this event. Any other state is therefore stale — a stroke we
        // never saw end, usually because the window lost focus without sending
        // a cancel. Discard it and process the DOWN normally, so every
        // reachable stuck state self-heals on the next touch rather than
        // waiting for a lifecycle callback that may not come.
        PointerAction.DOWN -> forceIdle() or claim(id, tool)

        PointerAction.POINTER_DOWN -> when (state) {
            // The framework says another pointer was already down and our set
            // is empty, so the bookkeeping is behind. Trust the framework and
            // treat this like the first contact.
            ExclusivityState.IDLE -> claim(id, tool)

            // The palm case. Add the id so the eventual POINTER_UP balances,
            // emit nothing, and leave the stroke's state completely untouched.
            // The following MOVEs now carry two pointers and the router
            // extracts only findPointerIndex(penStrokeId) — which is exactly
            // why the stroke is tracked by id and not by a cached index: when
            // the palm lifts first, the pen's index shifts under it.
            ExclusivityState.PEN -> { add(id); Decision.NONE }

            ExclusivityState.GESTURE -> {
                add(id)
                if (tool == ToolClass.FINGER) {
                    addGesture(id)
                    Decision.GESTURE_POINTERS
                } else {
                    // A live gesture locks the pen out for the whole lifetime
                    // of this contact, not until the fingers clear. A stroke
                    // that began mid-contact would have no DOWN, so no onset
                    // ramp and no unbuffered-dispatch request: a line that
                    // appears out of nowhere.
                    Decision.NONE
                }
            }

            // The most important cell in the table, and the one the whole
            // two-fingers-to-pan rule exists to keep reachable. Nothing here is
            // owned: these are resting contacts, not a gesture — no
            // GestureBegin was emitted and no transform is moving — so the
            // lockout reason does not apply to them. Blocking here means:
            // artist rests hand, draws, lifts the pen to reposition without
            // lifting the hand, puts it back down, and nothing draws. That bug
            // reproduces only with a palm down, which is to say only in real
            // use.
            ExclusivityState.DISOWNED -> {
                add(id)
                if (tool == ToolClass.PEN) beginPen(id) else arriveFinger(id)
            }
        }

        PointerAction.MOVE -> when (state) {
            ExclusivityState.PEN -> Decision.STROKE_SAMPLES
            ExclusivityState.GESTURE -> Decision.GESTURE_POINTERS
            else -> Decision.NONE
        }

        // findPointerIndex returned -1 for the pointer that owns the stroke.
        // The contact is gone without an UP, so the stroke cannot be committed.
        PointerAction.POINTER_LOST -> release(id, canceled = true)

        PointerAction.POINTER_UP -> release(id, canceled)

        // ACTION_UP is by definition the last pointer, so it clears everything
        // rather than removing one id — belt and braces against bookkeeping
        // drift, which otherwise strands the machine in GESTURE forever.
        PointerAction.UP -> {
            val out = when (state) {
                ExclusivityState.PEN ->
                    // id != penStrokeId means the pen vanished without a lift
                    // and the bookkeeping was wrong. Discard rather than commit.
                    if (canceled || id != penStrokeId) Decision.STROKE_CANCEL else Decision.STROKE_END
                ExclusivityState.GESTURE ->
                    if (canceled) Decision.GESTURE_CANCEL else Decision.GESTURE_END
                else -> Decision.NONE
            }
            clearAll()
            out
        }

        // The universal reset, and the safety valve behind every row above.
        // No final sample goes out with it: the coordinates on a cancel are
        // stale.
        PointerAction.CANCEL -> {
            val out = when (state) {
                ExclusivityState.PEN -> Decision.STROKE_CANCEL
                ExclusivityState.GESTURE -> Decision.GESTURE_CANCEL
                else -> Decision.NONE
            }
            clearAll()
            out
        }

        PointerAction.HOVER_ENTER, PointerAction.HOVER_MOVE, PointerAction.HOVER_EXIT ->
            error("unreachable: guarded by route()")
    }

    /**
     * Pen presence from `View.onHoverEvent`, which is the only thing hover is
     * still good for now that the distance axis is known dead.
     *
     * Never touches exclusivity state — that is the invariant this entry point
     * exists to make checkable. A hover event from anything but a pen is
     * dropped outright rather than classed as a finger: a hovering finger is
     * not a contact and has nothing to be excluded from.
     *
     * Returns only a presence bit, never a samples bit: with the distance axis
     * dead there is nothing in a hover sample anything reads, so `:app` must
     * not expand a hover event into the scratch list. A hover cursor is W15,
     * and it needs a position, not a history batch.
     *
     * `HOVER_EXIT` is ambiguous — the framework sends it both when the pen
     * leaves range and immediately before ACTION_DOWN — so stroke begin forces
     * the bit back on rather than trusting the sequence. The brief flicker at
     * lift is cosmetic and W15 can debounce it; debouncing here would put a
     * timer in a machine that otherwise has no clock.
     */
    fun hover(action: PointerAction, tool: ToolClass): Int {
        require(action.isHover) { "$action is not a hover action" }
        if (tool != ToolClass.PEN) return Decision.NONE
        val before = penInRange
        penInRange = action != PointerAction.HOVER_EXIT
        return presenceBits(before)
    }

    /**
     * Drop everything without a MotionEvent to drop it with.
     *
     * `ACTION_CANCEL` covers most of the ways a stroke can be interrupted, but
     * not all of them: an activity stopped, a view detached, or a dialog taking
     * focus can leave the last event ever delivered being an ACTION_MOVE, and
     * the machine then sits in PEN holding a stale pointer id. `:app` calls
     * this from `onWindowFocusChanged(false)`, `onDetachedFromWindow` and
     * `onPause`.
     */
    fun abandon(): Int {
        val before = penInRange
        val out = forceIdle()
        // Unconditional, unlike a stroke ending: the window is going away, so a
        // remembered hover is a cursor that outlives the surface it sat on.
        penInRange = false
        return out or presenceBits(before)
    }

    private fun claim(id: Int, tool: ToolClass): Int {
        add(id)
        return if (tool == ToolClass.PEN) beginPen(id) else arriveFinger(id)
    }

    private fun beginPen(id: Int): Int {
        penStrokeId = id
        penInRange = true
        // Everything already on the glass stops being a gesture candidate. A
        // hand that was resting when the stroke started must not become a pan
        // later just because another finger joins it: the artist is drawing,
        // and those contacts are disowned for as long as they last.
        pendingCount = 0
        // STROKE_SAMPLES rides along with the begin: the DOWN event's own
        // samples are part of the stroke. All three spike views take only
        // event.x/y on DOWN and none of them expands the lifting event at all —
        // two ignore ACTION_UP's coordinates entirely and the third reads only
        // event.x/y — which is why their strokes look clipped at each end.
        return Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES
    }

    /**
     * A finger arrived with nothing owned.
     *
     * It waits in [pending] — physically down, [ExclusivityState.DISOWNED],
     * emitting nothing — until a second finger joins it, and the two are
     * promoted into the gesture together at that moment so the transform starts
     * from both contacts rather than from a phantom one-finger origin.
     */
    private fun arriveFinger(id: Int): Int {
        addPending(id)
        if (pendingCount < FINGERS_PER_GESTURE) return Decision.NONE
        gestureActive = true
        for (i in 0 until pendingCount) addGesture(pending[i])
        pendingCount = 0
        return Decision.GESTURE_BEGIN or Decision.GESTURE_POINTERS
    }

    private fun release(id: Int, canceled: Boolean): Int {
        var out = Decision.NONE
        if (id != NO_POINTER && id == penStrokeId) {
            penStrokeId = NO_POINTER
            penInRange = false
            out = out or if (canceled) Decision.STROKE_CANCEL else Decision.STROKE_END
        }
        val wasGesturePointer = removeGesture(id)
        remove(id)
        if (gestureActive && gestureCount == 0) {
            gestureActive = false
            // GestureEnd even under the cancel flag: a gesture has nothing to
            // discard, so there is no distinction to make.
            out = out or Decision.GESTURE_END
        } else if (wasGesturePointer) {
            out = out or Decision.GESTURE_POINTERS
        }
        return out
    }

    private fun forceIdle(): Int {
        val out = when {
            penStrokeId != NO_POINTER -> Decision.STROKE_CANCEL
            gestureActive -> Decision.GESTURE_CANCEL
            else -> Decision.NONE
        }
        clearAll()
        return out
    }

    private fun clearAll() {
        downCount = 0
        gestureCount = 0
        pendingCount = 0
        gestureActive = false
        if (penStrokeId != NO_POINTER) {
            penStrokeId = NO_POINTER
            penInRange = false
        }
    }

    // Computed once at the end of an entry point rather than at each mutation,
    // so the two presence bits cannot both be set by an event that cancels one
    // stroke and begins another.
    private fun presenceBits(before: Boolean): Int = when {
        penInRange == before -> Decision.NONE
        penInRange -> Decision.PEN_PRESENCE_ON
        else -> Decision.PEN_PRESENCE_OFF
    }

    private fun add(id: Int) {
        if (id == NO_POINTER || indexOf(down, downCount, id) >= 0) return
        if (downCount < MAX_POINTERS) down[downCount++] = id
    }

    private fun addPending(id: Int) {
        if (id == NO_POINTER || indexOf(pending, pendingCount, id) >= 0) return
        if (pendingCount < MAX_POINTERS) pending[pendingCount++] = id
    }

    private fun addGesture(id: Int) {
        if (id == NO_POINTER || indexOf(gesture, gestureCount, id) >= 0) return
        if (gestureCount < MAX_POINTERS) gesture[gestureCount++] = id
    }

    // Membership by id, never a counter. A POINTER_UP for an id that was never
    // added is reachable after a stale-state reset, and decrementing an int
    // there underflows to -1: the machine is then stuck in GESTURE with no exit
    // short of a cancel.
    private fun remove(id: Int) {
        val p = indexOf(pending, pendingCount, id)
        if (p >= 0) pending[p] = pending[--pendingCount]
        val i = indexOf(down, downCount, id)
        if (i < 0) return
        down[i] = down[--downCount]
    }

    private fun removeGesture(id: Int): Boolean {
        val i = indexOf(gesture, gestureCount, id)
        if (i < 0) return false
        gesture[i] = gesture[--gestureCount]
        return true
    }

    private fun indexOf(a: IntArray, n: Int, id: Int): Int {
        for (i in 0 until n) if (a[i] == id) return i
        return -1
    }

    companion object {
        const val NO_POINTER = -1
        const val MAX_POINTERS = 10

        /**
         * How many fingers it takes to open a gesture. Two, and the reason is
         * in the class doc: at one, a hand that lands before the pen locks the
         * pen out for as long as it rests.
         */
        const val FINGERS_PER_GESTURE = 2
    }
}
