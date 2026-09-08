package be.thalos.artiest.engine.input

/**
 * Why a stroke was discarded instead of committed.
 *
 * Six causes, and they partition every path in [StrokeExclusivity] that can
 * produce [Decision.STROKE_CANCEL] — `RejectionCountersTest` drives all six and
 * asserts the sum. The partition is the point: when a line vanishes on the
 * tablet, "a stroke was cancelled" is not a diagnosis, and these six have
 * completely different fixes.
 */
enum class CancelCause {

    /**
     * The framework flagged the lift itself as unintentional: `FLAG_CANCELED`
     * on an ACTION_UP or ACTION_POINTER_UP. This is the platform's *own* palm
     * rejection reaching us, and it is the one cause here that means the
     * hardware and the OS did something, rather than this app deciding
     * something.
     *
     * Whether the DTH-A116's driver ever sets it is unmeasured — Phase 0
     * counted the flag but exported no session with a palm on the glass — which
     * is exactly why it is counted separately rather than folded in.
     */
    FLAG,

    /** ACTION_CANCEL: the window, a parent view or the system took the gesture. */
    CANCEL_ACTION,

    /**
     * `findPointerIndex` stopped resolving the stroke's pointer: the contact
     * left without a lift. Synthesized by `:app`, never delivered.
     */
    LOST_POINTER,

    /**
     * An ACTION_DOWN arrived while a stroke was still live, which means an end
     * we never saw. The DOWN is honoured and the orphan is discarded, so this
     * cause is a *self-heal* rather than a loss — but a non-zero count means
     * some lift is not reaching us and the next cause along is hiding behind
     * it.
     */
    STALE_DOWN,

    /** An ACTION_UP for a pointer that was not the one holding the stroke. */
    MISMATCHED_LIFT,

    /**
     * `abandon()`: no event at all. The activity paused, the window lost focus
     * or the surface went away with the pen still down.
     */
    ABANDONED,
}

/**
 * What the router rejected, as counters — the field diagnostic for W13.
 *
 * Every rule these count is `StrokeExclusivity`'s and is proved by its tests.
 * What tests cannot say is which of those rules ever *fires on this hardware*,
 * and three of the questions in this file have no answer that a JVM can give:
 * whether this digitizer's driver sets `FLAG_CANCELED` at all, whether the
 * framework ever sends this app an ACTION_CANCEL, and whether a pointer is ever
 * actually lost mid-stroke. All three were reasoned about in the plan and none
 * was measured, so they are counted instead of assumed.
 *
 * [fingerStrokeBegins] is a different kind of counter from the rest. It is not
 * a measurement, it is an assertion left running: "fingers and the pen's back
 * never draw" is W13's requirement, the tool split is what enforces it, and
 * this is that requirement in a form the device readout can show. **Any value
 * but zero is a bug**, and one that would otherwise present as ink appearing
 * under a resting hand — occasionally, in real use, and never in a test.
 *
 * Pure, allocation-free and UI-thread only, like the machine that feeds it. The
 * enum-indexed `LongArray` is why: this runs once per `MotionEvent` at
 * 250-320 Hz, on the path with a per-sample allocation budget.
 */
class RejectionCounters {

    /** Contacts that touched down at all: DOWN and POINTER_DOWN. */
    var contacts: Long = 0L
        private set

    /** Of [contacts], the ones the digitizer classed as a stylus. */
    var penContacts: Long = 0L
        private set

    /**
     * Contacts that produced no decision whatsoever.
     *
     * The palm resting under a live stroke, the pen arriving during a gesture,
     * and the first finger of a would-be pan all land here. It is the volume of
     * the exclusivity rule doing its job, and on this hardware with a hand on
     * the glass it should be *large*.
     */
    var contactsDropped: Long = 0L
        private set

    /**
     * Of [contactsDropped], the ones that were a pen.
     *
     * The only rejection in this class that can be felt as a fault: it means a
     * gesture was live and the pen was locked out for the lifetime of that
     * contact. Rare and intended; a large count means the two-finger rule is
     * catching hands it should not.
     */
    var penContactsDropped: Long = 0L
        private set

    var strokesBegun: Long = 0L
        private set

    var strokesEnded: Long = 0L
        private set

    var strokesCanceled: Long = 0L
        private set

    /**
     * Strokes begun by something that is not a stylus. See the class header:
     * this is an invariant, and its only correct value is zero.
     */
    var fingerStrokeBegins: Long = 0L
        private set

    /**
     * Events that arrived carrying `FLAG_CANCELED`, whatever the router then
     * did with them.
     *
     * Deliberately counted for every event and not only for the ones that
     * cancelled a stroke, because the open question is whether this device sets
     * the flag *ever*. A flag set on a finger lift changes no decision and
     * still answers it. Equally deliberately, [PointerAction.POINTER_LOST] is
     * excluded: it is synthesized by `:app` with the flag set, and counting it
     * would answer the question with this app's own signal.
     */
    var canceledFlagEvents: Long = 0L
        private set

    private val cancels = LongArray(CancelCause.entries.size)

    /** How many cancelled strokes had [cause]. See [CancelCause]. */
    fun cancelsBy(cause: CancelCause): Long = cancels[cause.ordinal]

    /**
     * Record one routed event: what came in, and what [StrokeExclusivity.route]
     * decided about it.
     *
     * Takes the decision rather than reading the machine, so it cannot disagree
     * with the mask the router actually dispatched — and so this class stays
     * drivable from a test with no machine at all.
     */
    fun record(action: PointerAction, tool: ToolClass, canceled: Boolean, decision: Int) {
        // POINTER_LOST is excluded because it is not an event: `:app`
        // synthesizes it, with the flag set, when the stroke's pointer stops
        // resolving. Counting it here would pollute the single question this
        // counter exists to answer — whether anything *outside* this app ever
        // sets the flag — with a value this app wrote itself.
        if (canceled && action != PointerAction.POINTER_LOST) canceledFlagEvents++
        if (action == PointerAction.DOWN || action == PointerAction.POINTER_DOWN) {
            contacts++
            if (tool == ToolClass.PEN) penContacts++
            if (decision == Decision.NONE) {
                contactsDropped++
                if (tool == ToolClass.PEN) penContactsDropped++
            }
        }
        if (decision and Decision.STROKE_BEGIN != 0) {
            strokesBegun++
            // A begin can only come from a DOWN or a POINTER_DOWN, so `tool`
            // is the tool that began it and this comparison is meaningful.
            if (tool != ToolClass.PEN) fingerStrokeBegins++
        }
        if (decision and Decision.STROKE_END != 0) strokesEnded++
        if (decision and Decision.STROKE_CANCEL != 0) {
            strokesCanceled++
            cancels[causeOf(action, canceled).ordinal]++
        }
    }

    /** Record an [StrokeExclusivity.abandon]: a cancel with no event behind it. */
    fun recordAbandon(decision: Int) {
        if (decision and Decision.STROKE_CANCEL != 0) {
            strokesCanceled++
            cancels[CancelCause.ABANDONED.ordinal]++
        }
    }

    fun reset() {
        contacts = 0L
        penContacts = 0L
        contactsDropped = 0L
        penContactsDropped = 0L
        strokesBegun = 0L
        strokesEnded = 0L
        strokesCanceled = 0L
        fingerStrokeBegins = 0L
        canceledFlagEvents = 0L
        cancels.fill(0L)
    }

    override fun toString(): String =
        "RejectionCounters(contacts=$contacts dropped=$contactsDropped " +
            "strokes=$strokesBegun/$strokesEnded/$strokesCanceled " +
            "flagged=$canceledFlagEvents fingerBegins=$fingerStrokeBegins)"

    companion object {

        /**
         * Which cause a cancelling decision had, from the event that carried
         * it.
         *
         * Order is the content. `LOST_POINTER` is checked before the flag
         * because `:app` synthesizes it *with* the flag set — the contact is
         * gone, so the lift is unintentional by definition — and reading that
         * as the platform's own palm rejection would answer this file's one
         * genuinely open question with a signal this app generated itself.
         */
        fun causeOf(action: PointerAction, canceled: Boolean): CancelCause = when {
            action == PointerAction.CANCEL -> CancelCause.CANCEL_ACTION
            action == PointerAction.POINTER_LOST -> CancelCause.LOST_POINTER
            action == PointerAction.DOWN -> CancelCause.STALE_DOWN
            canceled -> CancelCause.FLAG
            else -> CancelCause.MISMATCHED_LIFT
        }
    }
}
