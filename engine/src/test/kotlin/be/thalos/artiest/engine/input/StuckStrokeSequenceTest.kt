package be.thalos.artiest.engine.input

import be.thalos.artiest.engine.input.StrokeExclusivity.Companion.NO_POINTER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whole hand-sequences, driven end to end, asked one question each: **can the
 * pen still draw when this is over?**
 *
 * [StrokeExclusivityTest] checks the transition table cell by cell. This file
 * checks the thing a cell-by-cell table cannot: that no *sequence* of legal
 * cells parks the machine somewhere the pen never draws again. That failure —
 * "the pen stopped working and I had to lift my hand and put it back" — is
 * invisible in a unit of one transition, needs a palm and a pen in a specific
 * order to reproduce, and is miserable to diagnose on a tablet with no
 * debugger, which is the entire argument for the machine being pure.
 *
 * Every test ends with [assertPenCanDraw] or with its negation, deliberately
 * stated, so the file reads as a list of hand positions and a yes or no.
 */
class StuckStrokeSequenceTest {

    // ---- the sequences that must end with the pen drawing -----------------

    /** (a) pen down, palm down, pen up, palm up, pen down. */
    @Test
    fun `pen, then palm, then both lift in order`() {
        val m = StrokeExclusivity()
        assertDecision(SB or SS or PON, m.down(PEN, ToolClass.PEN))
        assertEquals(ExclusivityState.PEN, m.state)

        // The palm is tracked so its lift balances, and nothing else moves.
        assertDecision(Decision.NONE, m.pointerDown(PALM, ToolClass.FINGER))
        assertEquals(ExclusivityState.PEN, m.state)
        assertEquals(PEN, m.penStrokeId)

        assertDecision(SE or POFF, m.pointerUp(PEN))
        // Not IDLE and not GESTURE: the palm is still on the glass and owned by
        // nobody. This is the state that decides the whole question.
        assertEquals(ExclusivityState.DISOWNED, m.state)

        assertDecision(Decision.NONE, m.up(PALM))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertPenCanDraw(m)
    }

    /**
     * (b) palm down, pen down, and the pen draws — the palm arrives first,
     * which is the ordinary order as a hand is lowered toward a tablet.
     *
     * A lone contact does not open a gesture, so the palm is
     * [ExclusivityState.DISOWNED] and the pen lands in the one cell that starts
     * a stroke from it.
     */
    @Test
    fun `palm first, then pen, and the pen draws anyway`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(PALM, ToolClass.FINGER))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertDecision(Decision.NONE, m.move())

        assertDecision(SB or SS or PON, m.pointerDown(PEN, ToolClass.PEN))
        assertEquals(ExclusivityState.PEN, m.state)
        assertEquals(PEN, m.penStrokeId)
        assertDecision(SS, m.move())

        // ...and the palm still on the glass changes nothing on the way out.
        assertDecision(SE or POFF, m.pointerUp(PEN))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertDecision(Decision.NONE, m.up(PALM))
        assertPenCanDraw(m)
    }

    /** (c) pen down, ACTION_CANCEL, pen down. */
    @Test
    fun `a cancel mid-stroke is a full reset, not a wedge`() {
        val m = StrokeExclusivity()
        m.down(PEN, ToolClass.PEN)
        assertDecision(SC or POFF, m.cancel())
        assertEquals(ExclusivityState.IDLE, m.state)
        assertEquals(0, m.downPointerCount)
        assertPenCanDraw(m)
    }

    /** (d) three fingers, all lift, then the pen. */
    @Test
    fun `three fingers down then all lifted leaves the pen free`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(1, ToolClass.FINGER))
        assertDecision(GB or GP, m.pointerDown(2, ToolClass.FINGER))
        assertDecision(GP, m.pointerDown(3, ToolClass.FINGER))
        assertEquals(3, m.gesturePointerCount)
        assertDecision(GP, m.pointerUp(3))
        assertDecision(GP, m.pointerUp(2))
        assertDecision(GE, m.up(1))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertEquals(0, m.downPointerCount)
        assertEquals(0, m.gesturePointerCount)
        assertPenCanDraw(m)
    }

    /** (e) the trivial case: pen down, pen up, pen down. No lockout anywhere. */
    @Test
    fun `a clean stroke can be followed immediately by another`() {
        val m = StrokeExclusivity()
        assertDecision(SB or SS or PON, m.down(PEN, ToolClass.PEN))
        assertDecision(SE or POFF, m.up(PEN))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertDecision(SB or SS or PON, m.down(PEN, ToolClass.PEN))
        assertEquals(ExclusivityState.PEN, m.state)
    }

    /** (f) hover enter, pen down, pen up, hover exit. */
    @Test
    fun `a full hover-touch-hover cycle leaves presence and state consistent`() {
        val m = StrokeExclusivity()
        assertDecision(PON, m.hover(PointerAction.HOVER_ENTER, ToolClass.PEN))
        assertTrue(m.penInRange)
        // Hover moved nothing about who owns the glass. That is the invariant
        // hover is a bit and not a fifth state for.
        assertEquals(ExclusivityState.IDLE, m.state)

        // Presence is already on, so the begin does not re-announce it.
        assertDecision(SB or SS, m.down(PEN, ToolClass.PEN))
        assertDecision(SE or POFF, m.up(PEN))
        assertFalse(m.penInRange)

        // The trailing EXIT is a no-op rather than a second presence-off.
        assertDecision(Decision.NONE, m.hover(PointerAction.HOVER_EXIT, ToolClass.PEN))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertPenCanDraw(m)
    }

    /**
     * (g) the palm arrives and leaves mid-stroke. The stroke must survive both,
     * because this is a hand settling, and on the palm's lift the pen's pointer
     * index shifts underneath — which is why the id, not the index, is tracked.
     */
    @Test
    fun `a palm that lands and lifts mid-stroke never touches the stroke`() {
        val m = StrokeExclusivity()
        m.down(PEN, ToolClass.PEN)
        assertDecision(Decision.NONE, m.pointerDown(PALM, ToolClass.FINGER))
        assertDecision(SS, m.move())

        assertDecision(Decision.NONE, m.pointerUp(PALM))
        assertEquals(ExclusivityState.PEN, m.state)
        assertEquals(PEN, m.penStrokeId)
        assertDecision(SS, m.move())

        assertDecision(SE or POFF, m.up(PEN))
        assertPenCanDraw(m)
    }

    /** (h) the pen's back, which reports FINGER, used alone and then flipped over. */
    @Test
    fun `the pen's back draws nothing and then the writing end still draws`() {
        val m = StrokeExclusivity()
        // TOOL_TYPE_FINGER, because this pen has no eraser end. It is a finger
        // contact, deliberately — alone it is disowned like any other single
        // contact, and it must not poison what follows.
        assertDecision(Decision.NONE, m.down(PEN, ToolClass.FINGER))
        assertDecision(Decision.NONE, m.move())
        assertDecision(Decision.NONE, m.up(PEN))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertPenCanDraw(m)
    }

    /** (i) a second stylus during a live stroke. First pen wins, and cleanly. */
    @Test
    fun `a second stylus never steals or strands the stroke`() {
        val m = StrokeExclusivity()
        m.down(PEN, ToolClass.PEN)
        assertDecision(Decision.NONE, m.pointerDown(PEN2, ToolClass.PEN))
        assertEquals(PEN, m.penStrokeId)
        assertDecision(SS, m.move())

        assertDecision(SE or POFF, m.pointerUp(PEN))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        // The second stylus was disowned on arrival, so it is free to start a
        // stroke of its own the moment it is re-placed — but not before.
        assertDecision(Decision.NONE, m.move())
        assertDecision(Decision.NONE, m.up(PEN2))
        assertPenCanDraw(m)
    }

    // ---- the attack --------------------------------------------------------

    /**
     * THE FINDING, and the regression test for the fix.
     *
     * When one finger opened a gesture, a palm that landed before the pen
     * locked the pen out for as long as it rested: a live gesture drops every
     * pen contact for its whole lifetime, including contacts that begin and end
     * entirely inside it, so the artist drew nothing until they lifted the
     * *hand* — a recovery nobody performs, because nobody knows they have to.
     *
     * The fix is upstream of the lockout rather than downstream of it: a
     * gesture takes two fingers, so a resting hand is DISOWNED and the pen
     * draws from it. Fixing it in W12's `GestureController` instead ("require
     * >= 2 pointers to pan") would have stopped the canvas moving and left the
     * lockout exactly where it was, because it fires here whether or not
     * anything acts on GESTURE_BEGIN.
     *
     * Stated as three complete pen contacts rather than one, because the
     * cell-by-cell test lifts the palm before it re-places the pen and so
     * cannot see a lockout that outlives a single contact.
     */
    @Test
    fun `a palm that lands first does not lock the pen out`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(PALM, ToolClass.FINGER))
        assertEquals(ExclusivityState.DISOWNED, m.state)

        // Three complete strokes, drawn one after another while the hand rests.
        repeat(3) {
            assertDecision(SB or SS or PON, m.pointerDown(PEN, ToolClass.PEN))
            assertEquals(PEN, m.penStrokeId)
            assertDecision(SS, m.move())          // stroke samples, never gesture pointers
            assertDecision(SE or POFF, m.pointerUp(PEN))
            assertEquals(ExclusivityState.DISOWNED, m.state)
        }

        assertDecision(Decision.NONE, m.up(PALM))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertPenCanDraw(m)
    }

    /**
     * The other half of the trade, stated so nobody re-derives it as a bug: a
     * second finger beside the resting palm *does* open a gesture, and the pen
     * is then locked out for the lifetime of its contact. Two contacts is where
     * a resting hand and a deliberate pan become genuinely indistinguishable,
     * and the lockout still costs nothing permanent — lifting the fingers, or
     * an ACTION_DOWN, returns the glass.
     */
    @Test
    fun `two fingers do open a gesture, and the pen waits for them`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(PALM, ToolClass.FINGER))
        assertDecision(GB or GP, m.pointerDown(PALM2, ToolClass.FINGER))
        assertEquals(ExclusivityState.GESTURE, m.state)

        assertDecision(Decision.NONE, m.pointerDown(PEN, ToolClass.PEN))
        assertEquals(NO_POINTER, m.penStrokeId)

        assertDecision(GP, m.pointerUp(PALM2))
        assertDecision(GE, m.pointerUp(PALM))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertDecision(Decision.NONE, m.up(PEN))
        assertPenCanDraw(m)
    }

    /**
     * The counterweight, and the reason the finding above is a policy problem
     * rather than a wedged machine: nothing survives a lift of everything. From
     * every state reachable here, one ACTION_UP or ACTION_CANCEL — or an
     * ACTION_DOWN, which self-heals — returns an empty, drawable machine.
     */
    @Test
    fun `no reachable state survives a lift of everything`() {
        for ((name, build) in situations()) {
            val viaUp = build()
            viaUp.up(99)
            assertEquals(ExclusivityState.IDLE, viaUp.state, "$name did not clear on UP")
            assertEquals(0, viaUp.downPointerCount, "$name leaked pointers on UP")
            assertPenCanDraw(viaUp, name)

            val viaCancel = build()
            viaCancel.cancel()
            assertEquals(ExclusivityState.IDLE, viaCancel.state, "$name did not clear on CANCEL")
            assertPenCanDraw(viaCancel, name)

            val viaAbandon = build()
            viaAbandon.abandon()
            assertEquals(ExclusivityState.IDLE, viaAbandon.state, "$name did not clear on abandon")
            assertPenCanDraw(viaAbandon, name)

            // The one that does not need a lifecycle callback to arrive: a DOWN
            // proves the framework thinks the glass is empty, so any other state
            // is stale and is discarded before the DOWN is processed.
            val viaDown = build()
            assertTrue(
                viaDown.down(PEN, ToolClass.PEN) has SB,
                "$name did not self-heal on the next ACTION_DOWN",
            )
            assertEquals(PEN, viaDown.penStrokeId, "$name self-healed without owning the stroke")
        }
    }

    /**
     * The general shape of the bug this file hunts: a state whose only exit is
     * an event that the state itself causes to be dropped. Enumerated by brute
     * force over every situation and every single next action, asserting that
     * from every resulting machine the pen is at most one lift away from
     * drawing.
     */
    @Test
    fun `no single action can park the machine more than one lift from drawing`() {
        val actions = PointerAction.entries.filter { !it.isHover }
        for ((name, build) in situations()) {
            for (action in actions) {
                for (tool in ToolClass.entries) {
                    for (canceled in listOf(false, true)) {
                        for (id in intArrayOf(PEN, PALM, PEN2, 99)) {
                            val m = build()
                            val pointerId =
                                if (action == PointerAction.MOVE || action == PointerAction.CANCEL) {
                                    NO_POINTER
                                } else {
                                    id
                                }
                            m.route(action, pointerId, tool, canceled)
                            val where = "$name then $action id=$id tool=$tool canceled=$canceled"
                            if (m.state == ExclusivityState.PEN) continue // already drawing
                            // Everything lifts, the way a hand leaves a tablet.
                            m.route(PointerAction.UP, 99, ToolClass.FINGER, canceled = false)
                            assertEquals(ExclusivityState.IDLE, m.state, "$where: UP left ${m.state}")
                            assertPenCanDraw(m, where)
                        }
                    }
                }
            }
        }
    }

    /**
     * The brute-force version of this whole file, and the check that found the
     * palm-first lockout in the first place: every route() sequence of length
     * one to four over two pointer ids, two tool classes and every non-hover
     * action — half a million of them — each asked whether a *fresh* pen
     * contact can start a stroke from wherever it landed.
     *
     * The invariant is that the only resting state which refuses the pen is
     * GESTURE, and that no single event can reach it. Before the two-finger
     * rule, one event could: `DOWN/finger` alone opened a gesture, and 127316
     * of these sequences ended with the pen locked out behind a resting hand.
     *
     * GESTURE itself is allowed to refuse, and must: a pan is live, the pen has
     * no business starting mid-transform, and every one of those sequences put
     * two fingers on the glass to get there.
     */
    @Test
    fun `the only sequence that stops the pen drawing is a live two-finger gesture`() {
        val actions = PointerAction.entries.filter { !it.isHover }
        val ids = intArrayOf(0, 1)
        var checked = 0
        val blocking = LinkedHashMap<String, String>()

        fun walk(depth: Int, replay: List<Triple<PointerAction, Int, ToolClass>>) {
            if (replay.isNotEmpty()) {
                val m = StrokeExclusivity()
                for ((a, id, tool) in replay) {
                    val pointer =
                        if (a == PointerAction.MOVE || a == PointerAction.CANCEL) NO_POINTER else id
                    m.route(a, pointer, tool, canceled = false)
                }
                checked++
                if (m.state != ExclusivityState.PEN) {
                    val where = m.state
                    // Mutating m is free: it is discarded either way.
                    val drew = m.route(PointerAction.POINTER_DOWN, 9, ToolClass.PEN, false) has SB
                    if (!drew) {
                        val how = replay.joinToString(" ; ") { "${it.first}/${it.second}/${it.third}" }
                        blocking.putIfAbsent("$where after ${replay.size}", how)
                        assertEquals(
                            ExclusivityState.GESTURE, where,
                            "the pen was locked out from $where by: $how",
                        )
                        assertTrue(
                            replay.size >= FINGERS_PER_GESTURE,
                            "one event reached a pen lockout: $how",
                        )
                    }
                }
            }
            if (depth == 0) return
            for (a in actions) for (id in ids) for (tool in ToolClass.entries) {
                walk(depth - 1, replay + Triple(a, id, tool))
            }
        }

        walk(4, emptyList())
        assertTrue(checked > 500_000, "the probe covered only $checked sequences")
        // Not an empty set: a live pan does refuse the pen, deliberately.
        assertTrue(blocking.isNotEmpty(), "nothing refused the pen, so this probe proves nothing")
    }

    // ---- harness ----------------------------------------------------------

    /** Every hand position this file cares about, each rebuilt from scratch. */
    private fun situations(): List<Pair<String, () -> StrokeExclusivity>> = listOf(
        "IDLE" to { StrokeExclusivity() },
        "PEN" to { StrokeExclusivity().also { it.down(PEN, ToolClass.PEN) } },
        "DISOWNED palm alone" to { StrokeExclusivity().also { it.down(PALM, ToolClass.FINGER) } },
        "GESTURE" to {
            StrokeExclusivity().also {
                it.down(PALM, ToolClass.FINGER)
                it.pointerDown(PALM2, ToolClass.FINGER)
            }
        },
        "DISOWNED" to {
            StrokeExclusivity().also {
                it.down(PEN, ToolClass.PEN)
                it.pointerDown(PALM, ToolClass.FINGER)
                it.pointerUp(PEN)
            }
        },
        "PEN with palm" to {
            StrokeExclusivity().also {
                it.down(PEN, ToolClass.PEN)
                it.pointerDown(PALM, ToolClass.FINGER)
            }
        },
        "GESTURE with a disowned pen" to {
            StrokeExclusivity().also {
                it.down(PALM, ToolClass.FINGER)
                it.pointerDown(PALM2, ToolClass.FINGER)
                it.pointerDown(PEN, ToolClass.PEN)
            }
        },
    )

    /** Puts a pen on the glass and insists that ink comes out. */
    private fun assertPenCanDraw(m: StrokeExclusivity, where: String = "") {
        val decision = m.down(PEN, ToolClass.PEN)
        assertTrue(
            decision has SB && decision has SS,
            "$where: the pen came down and got ${Decision.names(decision)} from ${m.state}",
        )
        assertEquals(PEN, m.penStrokeId, "$where: stroke began without owning the pointer")
        assertTrue(m.move() has SS, "$where: the stroke released no samples on the next move")
    }

    private fun assertDecision(expected: Int, actual: Int) =
        assertEquals(
            expected, actual,
            "expected ${Decision.names(expected)}, got ${Decision.names(actual)}",
        )

    private infix fun Int.has(bit: Int) = this and bit != 0

    private companion object {
        const val PEN = 7
        const val PEN2 = 8
        const val PALM = 3
        const val PALM2 = 4
        const val FINGERS_PER_GESTURE = StrokeExclusivity.FINGERS_PER_GESTURE

        const val SB = Decision.STROKE_BEGIN
        const val SS = Decision.STROKE_SAMPLES
        const val SE = Decision.STROKE_END
        const val SC = Decision.STROKE_CANCEL
        const val GB = Decision.GESTURE_BEGIN
        const val GP = Decision.GESTURE_POINTERS
        const val GE = Decision.GESTURE_END
        const val PON = Decision.PEN_PRESENCE_ON
        const val POFF = Decision.PEN_PRESENCE_OFF
    }
}

// Local to this file: the same call-site names the transition-table test uses,
// so a sequence here can be read against a MotionEvent log line by line.
private fun StrokeExclusivity.down(id: Int, tool: ToolClass) =
    route(PointerAction.DOWN, id, tool, canceled = false)

private fun StrokeExclusivity.pointerDown(id: Int, tool: ToolClass) =
    route(PointerAction.POINTER_DOWN, id, tool, canceled = false)

private fun StrokeExclusivity.move() =
    route(PointerAction.MOVE, StrokeExclusivity.NO_POINTER, ToolClass.FINGER, canceled = false)

private fun StrokeExclusivity.pointerUp(id: Int) =
    route(PointerAction.POINTER_UP, id, ToolClass.FINGER, canceled = false)

private fun StrokeExclusivity.up(id: Int) =
    route(PointerAction.UP, id, ToolClass.FINGER, canceled = false)

private fun StrokeExclusivity.cancel() =
    route(PointerAction.CANCEL, StrokeExclusivity.NO_POINTER, ToolClass.FINGER, canceled = false)
