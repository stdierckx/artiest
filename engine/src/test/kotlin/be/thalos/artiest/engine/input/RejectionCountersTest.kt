package be.thalos.artiest.engine.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The counters W13 ships as a field diagnostic, checked against the machine
 * they count rather than against a hand-written mask.
 *
 * Two of the tests below are the ones that matter. `the six causes account for
 * every way a stroke can be discarded` drives [StrokeExclusivity] down all six
 * cancelling paths and asserts the causes partition them — a cause that fell
 * into the wrong bucket would still add up, so each is asserted individually
 * *and* the total is asserted against the sum. And `a finger can never begin a
 * stroke` carries its own negative control: the same sequences with the tool
 * classification inverted do produce finger begins, which is what stops the
 * zero being the zero of a counter nobody increments.
 */
class RejectionCountersTest {

    private val counters = RejectionCounters()
    private val machine = StrokeExclusivity()

    /** Route through the machine and count what it decided, as `:app` does. */
    private fun route(
        action: PointerAction,
        id: Int = StrokeExclusivity.NO_POINTER,
        tool: ToolClass = ToolClass.PEN,
        canceled: Boolean = false,
    ): Int {
        val decision = machine.route(action, id, tool, canceled)
        counters.record(action, tool, canceled, decision)
        return decision
    }

    private fun abandon() {
        counters.recordAbandon(machine.abandon())
    }

    @Test
    fun `a pen stroke that begins and ends is counted once each way`() {
        route(PointerAction.DOWN, PEN)
        route(PointerAction.MOVE)
        route(PointerAction.UP, PEN)

        assertEquals(1L, counters.contacts)
        assertEquals(1L, counters.penContacts)
        assertEquals(1L, counters.strokesBegun)
        assertEquals(1L, counters.strokesEnded)
        assertEquals(0L, counters.strokesCanceled)
        assertEquals(0L, counters.contactsDropped)
        assertEquals(0L, counters.fingerStrokeBegins)
    }

    @Test
    fun `the six causes account for every way a stroke can be discarded`() {
        // FLAG: the framework's own palm rejection on the lift.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.UP, PEN, canceled = true)

        // CANCEL_ACTION: the window or a parent took the gesture.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.CANCEL)

        // LOST_POINTER: findPointerIndex stopped resolving the stroke's pointer.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.POINTER_LOST, PEN, canceled = true)

        // STALE_DOWN: a DOWN with a stroke still live, so an end went missing.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.DOWN, PEN)
        route(PointerAction.UP, PEN)

        // MISMATCHED_LIFT: the last pointer up is not the one holding the stroke.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.UP, OTHER)

        // ABANDONED: no event at all — the activity paused with the pen down.
        route(PointerAction.DOWN, PEN)
        abandon()

        for (cause in CancelCause.entries) {
            assertEquals(1L, counters.cancelsBy(cause), "cause $cause")
        }
        assertEquals(6L, counters.strokesCanceled)
        assertEquals(
            counters.strokesCanceled,
            CancelCause.entries.sumOf { counters.cancelsBy(it) },
            "the causes do not add up to the cancels",
        )
        // Seven begins from six sequences: the stale DOWN cancels the orphan
        // and begins its own stroke in the same decision. Asserted so the
        // partition above cannot be satisfied by a cause that swallowed a
        // begin, and because that one mask carrying both bits is the case a
        // counter written as an `else if` chain gets wrong.
        assertEquals(7L, counters.strokesBegun)
        assertEquals(1L, counters.strokesEnded)
    }

    @Test
    fun `a lost pointer is not read as the platform's cancel flag`() {
        // `:app` synthesizes POINTER_LOST *with* the flag set, because a
        // contact that vanished did not lift on purpose. Counting that as FLAG
        // would answer the one open question — does this digitizer's driver
        // ever set it — with a signal this app generated itself.
        route(PointerAction.DOWN, PEN)
        route(PointerAction.POINTER_LOST, PEN, canceled = true)

        assertEquals(1L, counters.cancelsBy(CancelCause.LOST_POINTER))
        assertEquals(0L, counters.cancelsBy(CancelCause.FLAG))
        // And it does not reach the event-level flag count either, which is
        // the number that answers the question. A POINTER_LOST is not an event
        // — nothing delivered it — so a session that only ever lost pointers
        // must still report zero flagged events.
        assertEquals(0L, counters.canceledFlagEvents)
    }

    @Test
    fun `the cancel flag is counted on events that change nothing`() {
        // A finger lifting under the flag routes exactly like a plain lift —
        // fingers never draw, so there is nothing to discard — and the flag is
        // still evidence about the driver, which is what this counter is for.
        route(PointerAction.DOWN, FINGER_A, ToolClass.FINGER)
        route(PointerAction.UP, FINGER_A, ToolClass.FINGER, canceled = true)

        assertEquals(1L, counters.canceledFlagEvents)
        assertEquals(0L, counters.strokesCanceled)
    }

    @Test
    fun `a palm under a live stroke is counted as a dropped contact`() {
        route(PointerAction.DOWN, PEN)
        route(PointerAction.POINTER_DOWN, FINGER_A, ToolClass.FINGER)

        assertEquals(2L, counters.contacts)
        assertEquals(1L, counters.contactsDropped)
        assertEquals(0L, counters.penContactsDropped)
    }

    @Test
    fun `a pen arriving during a gesture is counted as a dropped pen`() {
        route(PointerAction.DOWN, FINGER_A, ToolClass.FINGER)
        route(PointerAction.POINTER_DOWN, FINGER_B, ToolClass.FINGER)
        assertEquals(ExclusivityState.GESTURE, machine.state)
        route(PointerAction.POINTER_DOWN, PEN)

        assertEquals(1L, counters.penContactsDropped)
        // The lone first finger is a dropped contact too: one finger is never a
        // gesture. So the pen is the second of two, not the only one.
        assertEquals(2L, counters.contactsDropped)
        assertEquals(0L, counters.strokesBegun)
    }

    @Test
    fun `a finger can never begin a stroke`() {
        // Every shape a finger contact can arrive in: alone, beside a palm,
        // during a stroke, during a gesture.
        route(PointerAction.DOWN, FINGER_A, ToolClass.FINGER)
        route(PointerAction.POINTER_DOWN, FINGER_B, ToolClass.FINGER)
        route(PointerAction.UP, FINGER_A, ToolClass.FINGER)
        route(PointerAction.DOWN, PEN)
        route(PointerAction.POINTER_DOWN, FINGER_A, ToolClass.FINGER)
        route(PointerAction.UP, PEN)

        assertEquals(0L, counters.fingerStrokeBegins)
        assertTrue(counters.strokesBegun > 0L, "no stroke began at all, so nothing was tested")

        // The control. The pen's back reports TOOL_TYPE_FINGER on this
        // hardware, so `toolClassOf` is the whole of the rule — and a mirror
        // that classed it as a pen would produce a stroke from a knuckle. Here
        // that mistake is made deliberately, and the counter sees it.
        val broken = RejectionCounters()
        val loose = StrokeExclusivity()
        val decision = loose.route(
            PointerAction.DOWN,
            FINGER_A,
            ToolClass.PEN,
            canceled = false,
        )
        broken.record(PointerAction.DOWN, ToolClass.FINGER, false, decision)
        assertEquals(1L, broken.fingerStrokeBegins, "the control did not trip the invariant")
    }

    @Test
    fun `reset drops every counter including the causes`() {
        route(PointerAction.DOWN, PEN)
        route(PointerAction.CANCEL)
        counters.reset()

        assertEquals(0L, counters.contacts)
        assertEquals(0L, counters.strokesBegun)
        assertEquals(0L, counters.strokesCanceled)
        assertEquals(0L, counters.canceledFlagEvents)
        for (cause in CancelCause.entries) assertEquals(0L, counters.cancelsBy(cause))
    }

    @Test
    fun `the cause of a cancel is derived from the action, not from the flag alone`() {
        // The table, directly. Order is what this pins: a CANCEL action and a
        // lost pointer both arrive flagged, and both have their own cause.
        assertEquals(
            CancelCause.CANCEL_ACTION,
            RejectionCounters.causeOf(PointerAction.CANCEL, canceled = true),
        )
        assertEquals(
            CancelCause.LOST_POINTER,
            RejectionCounters.causeOf(PointerAction.POINTER_LOST, canceled = true),
        )
        assertEquals(
            CancelCause.STALE_DOWN,
            RejectionCounters.causeOf(PointerAction.DOWN, canceled = false),
        )
        assertEquals(
            CancelCause.FLAG,
            RejectionCounters.causeOf(PointerAction.UP, canceled = true),
        )
        assertEquals(
            CancelCause.MISMATCHED_LIFT,
            RejectionCounters.causeOf(PointerAction.UP, canceled = false),
        )
    }

    private companion object {
        const val PEN = 1
        const val OTHER = 2
        const val FINGER_A = 3
        const val FINGER_B = 4
    }
}
