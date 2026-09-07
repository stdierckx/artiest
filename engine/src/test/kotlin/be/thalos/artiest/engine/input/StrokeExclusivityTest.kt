package be.thalos.artiest.engine.input

import be.thalos.artiest.engine.input.StrokeExclusivity.Companion.NO_POINTER
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The exclusivity machine, driven exhaustively on the JVM.
 *
 * This is the reason the rule lives in `:engine` at all. Its worst failure — a
 * palm landing mid-stroke, chopping the line in two and panning the canvas
 * underneath it — needs a hand, a palm and a pen in a specific order, which is
 * not something anyone reproduces reliably on a tablet, and the spike never ran
 * a second pointer at all: `ACTION_POINTER_DOWN`, `ACTION_POINTER_UP`,
 * `findPointerIndex` and `getPointerId` appear nowhere in it. Every rule below
 * is new code, so every cell of the table gets an assertion.
 */
class StrokeExclusivityTest {

    // ---- IDLE -------------------------------------------------------------

    @Test
    fun `a pen touching an empty glass begins a stroke and claims its samples`() {
        val m = StrokeExclusivity()
        assertDecision(
            Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            m.down(PEN_ID, ToolClass.PEN),
        )
        assertEquals(ExclusivityState.PEN, m.state)
        assertEquals(PEN_ID, m.penStrokeId)
        // The DOWN event's own history batch belongs to the stroke. All three
        // spike views take only event.x/event.y on DOWN and dropped it, which
        // is half of why their strokes look clipped at the ends.
        assertTrue(m.down(PEN_ID, ToolClass.PEN) has Decision.STROKE_SAMPLES)
    }

    /**
     * One finger is not a gesture, and this is the assertion that keeps the
     * pen alive. A hand lowered onto the glass before the pen is a single
     * FINGER contact; if it opened a gesture, every pen contact that followed
     * would be dropped for as long as the hand rested, and lifting and
     * re-placing the pen would not recover it.
     */
    @Test
    fun `one finger on an empty glass is disowned and opens no gesture`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(1, ToolClass.FINGER))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertEquals(NO_POINTER, m.penStrokeId)
        assertEquals(1, m.downPointerCount)
        assertEquals(0, m.gesturePointerCount)
        assertDecision(Decision.NONE, m.move())
    }

    /** The second finger is what a pan actually is, and both are promoted into it. */
    @Test
    fun `a second finger opens the gesture and takes the first one with it`() {
        val m = StrokeExclusivity()
        m.down(1, ToolClass.FINGER)
        assertDecision(
            Decision.GESTURE_BEGIN or Decision.GESTURE_POINTERS,
            m.pointerDown(2, ToolClass.FINGER),
        )
        assertEquals(ExclusivityState.GESTURE, m.state)
        assertEquals(2, m.gesturePointerCount, "the resting finger joined the gesture it started")
    }

    /**
     * A POINTER_DOWN in IDLE means the framework saw a contact this machine
     * never did. Trusting the framework and treating it as the first contact is
     * what keeps a missed event from becoming a permanently dead pointer set.
     */
    @Test
    fun `a POINTER_DOWN with nothing tracked is treated as the first contact`() {
        val m = StrokeExclusivity()
        assertDecision(
            Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            m.pointerDown(PEN_ID, ToolClass.PEN),
        )
        assertEquals(ExclusivityState.PEN, m.state)
    }

    @Test
    fun `nothing is emitted for movement or lifts on an empty glass`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.move())
        assertDecision(Decision.NONE, m.pointerUp(3))
        assertDecision(Decision.NONE, m.up(3))
        assertDecision(Decision.NONE, m.cancel())
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    // ---- PEN --------------------------------------------------------------

    /**
     * The case the whole rule exists for. The palm is tracked so its eventual
     * lift balances, and nothing else about the stroke moves: no gesture
     * begins, no sample is emitted for it, and the stroke does not end.
     */
    @Test
    fun `a palm landing mid-stroke emits nothing and leaves the stroke live`() {
        val m = penStroke()
        assertDecision(Decision.NONE, m.pointerDown(PALM_ID, ToolClass.FINGER))
        assertEquals(ExclusivityState.PEN, m.state)
        assertEquals(PEN_ID, m.penStrokeId)
        assertEquals(0, m.gesturePointerCount, "the palm must not be owned by a gesture")
        assertEquals(2, m.downPointerCount)
        // And the following MOVEs still belong to the pen alone.
        assertDecision(Decision.STROKE_SAMPLES, m.move())
    }

    @Test
    fun `a second pen contact never steals the live stroke`() {
        val m = penStroke()
        assertDecision(Decision.NONE, m.pointerDown(9, ToolClass.PEN))
        assertEquals(PEN_ID, m.penStrokeId)
    }

    @Test
    fun `movement during a stroke releases stroke samples and no gesture pointers`() {
        val m = penStroke()
        assertDecision(Decision.STROKE_SAMPLES, m.move())
    }

    @Test
    fun `only the stroke's own pointer can end it`() {
        val m = penStroke()
        m.pointerDown(PALM_ID, ToolClass.FINGER)
        assertDecision(Decision.NONE, m.pointerUp(PALM_ID))
        assertEquals(ExclusivityState.PEN, m.state)
        assertDecision(Decision.STROKE_END or Decision.PEN_PRESENCE_OFF, m.pointerUp(PEN_ID))
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    /**
     * The one behavioural difference FLAG_CANCELED makes at this stage, and it
     * is load-bearing: W10's commit step writes the stroke to the layer bitmap,
     * so an End here ships an undoable stroke drawn by a contact the system
     * already told us was a palm.
     */
    @Test
    fun `FLAG_CANCELED on the pen's lift discards the stroke instead of committing it`() {
        val m = penStroke()
        val d = m.route(PointerAction.POINTER_UP, PEN_ID, ToolClass.PEN, canceled = true)
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, d)
        assertTrue(!(d has Decision.STROKE_END))
    }

    /**
     * Fingers never draw, so a palm-rejected finger and a lifted finger are the
     * same event downstream. Written down so W13 does not read the absence of a
     * branch as an oversight and add one.
     */
    @Test
    fun `FLAG_CANCELED on a finger lift behaves exactly like a plain lift`() {
        val plain = fingerGesture().also { it.pointerDown(3, ToolClass.FINGER) }
        val flagged = fingerGesture().also { it.pointerDown(3, ToolClass.FINGER) }
        assertEquals(
            plain.route(PointerAction.POINTER_UP, 3, ToolClass.FINGER, canceled = false),
            flagged.route(PointerAction.POINTER_UP, 3, ToolClass.FINGER, canceled = true),
        )
        assertEquals(plain.state, flagged.state)
    }

    @Test
    fun `an UP for a pointer that is not the stroke discards the stroke`() {
        val m = penStroke()
        // ACTION_UP is the last pointer by definition, so the pen went away
        // without a lift and the bookkeeping was wrong. Discard, do not commit.
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, m.up(77))
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    @Test
    fun `CANCEL mid-stroke discards it and clears every pointer`() {
        val m = penStroke()
        m.pointerDown(PALM_ID, ToolClass.FINGER)
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, m.cancel())
        assertEquals(ExclusivityState.IDLE, m.state)
        assertEquals(0, m.downPointerCount)
    }

    /**
     * `findPointerIndex` returning -1 for the tracked id is normal, not
     * exceptional. The contact is gone with no UP, so the stroke can never be
     * completed and must not be committed.
     */
    @Test
    fun `a lost pointer cancels the stroke it owned rather than stranding it`() {
        val m = penStroke()
        m.pointerDown(PALM_ID, ToolClass.FINGER)
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, m.lost(PEN_ID))
        assertEquals(ExclusivityState.DISOWNED, m.state, "the palm is still on the glass")
    }

    // ---- GESTURE ----------------------------------------------------------

    /**
     * A stroke that began mid-contact would have no DOWN, so no onset ramp and
     * no unbuffered-dispatch request: a line that appears out of nowhere. The
     * lockout is therefore for the lifetime of the contact, not until the
     * fingers clear.
     */
    @Test
    fun `a pen arriving during a gesture is locked out for the life of that contact`() {
        val m = fingerGesture()
        assertDecision(Decision.NONE, m.pointerDown(PEN_ID, ToolClass.PEN))
        assertEquals(ExclusivityState.GESTURE, m.state)
        assertDecision(Decision.GESTURE_POINTERS, m.move())

        // The fingers clear, and the pen still does not spontaneously draw.
        assertDecision(Decision.GESTURE_POINTERS, m.pointerUp(2))
        assertDecision(Decision.GESTURE_END, m.pointerUp(1))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertEquals(NO_POINTER, m.penStrokeId)
        assertDecision(Decision.NONE, m.move())
    }

    @Test
    fun `further fingers join the live gesture`() {
        val m = fingerGesture()
        assertDecision(Decision.GESTURE_POINTERS, m.pointerDown(3, ToolClass.FINGER))
        assertEquals(3, m.gesturePointerCount)
    }

    @Test
    fun `three fingers down then all lifted returns to IDLE with empty sets`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.down(1, ToolClass.FINGER))
        assertDecision(Decision.GESTURE_BEGIN or Decision.GESTURE_POINTERS, m.pointerDown(2, ToolClass.FINGER))
        assertDecision(Decision.GESTURE_POINTERS, m.pointerDown(3, ToolClass.FINGER))
        assertEquals(3, m.gesturePointerCount)
        assertDecision(Decision.GESTURE_POINTERS, m.pointerUp(3))
        assertDecision(Decision.GESTURE_POINTERS, m.pointerUp(2))
        // The gesture stays live down to its last pointer rather than falling
        // back below the two-finger threshold: a finger momentarily leaving the
        // glass mid-pan must not abort the pan and re-anchor it.
        assertDecision(Decision.GESTURE_END, m.up(1))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertEquals(0, m.downPointerCount)
        assertEquals(0, m.gesturePointerCount)
    }

    @Test
    fun `an UP under FLAG_CANCELED cancels the gesture instead of ending it`() {
        val m = fingerGesture()
        assertDecision(Decision.GESTURE_CANCEL, m.route(PointerAction.UP, 1, ToolClass.FINGER, canceled = true))
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    // ---- DISOWNED ---------------------------------------------------------

    /**
     * The stuck-input bug, and the reason DISOWNED is a state rather than a
     * synonym for IDLE or GESTURE. The palm was never a gesture: no
     * GestureBegin was emitted and no transform is moving, so the lockout
     * reason does not apply to it. Blocking here means the artist rests a hand,
     * draws, lifts the pen to reposition without lifting the hand, puts it back
     * down, and nothing draws.
     */
    @Test
    fun `the pen can start a new stroke with a palm still resting on the glass`() {
        val m = disownedPalm()
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertDecision(
            Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            m.pointerDown(PEN_ID, ToolClass.PEN),
        )
        assertEquals(ExclusivityState.PEN, m.state)
    }

    /**
     * The corollary. A palm that was resting under a live stroke is not a
     * gesture candidate afterwards, so one deliberate finger beside it is still
     * one finger and pans nothing. Two deliberate fingers do pan, which is the
     * next test: an artist should be able to zoom with a hand still on the
     * glass.
     */
    @Test
    fun `a finger arriving next to a disowned palm does not start a gesture`() {
        val m = disownedPalm()
        assertDecision(Decision.NONE, m.pointerDown(4, ToolClass.FINGER))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertDecision(Decision.NONE, m.move())
    }

    @Test
    fun `two fingers beside a disowned palm pan without recruiting the palm`() {
        val m = disownedPalm()
        assertDecision(Decision.NONE, m.pointerDown(4, ToolClass.FINGER))
        assertDecision(
            Decision.GESTURE_BEGIN or Decision.GESTURE_POINTERS,
            m.pointerDown(5, ToolClass.FINGER),
        )
        assertEquals(2, m.gesturePointerCount, "the resting palm is not one of the pan's anchors")
        assertEquals(3, m.downPointerCount)
    }

    @Test
    fun `lifting the last disowned contact returns to IDLE`() {
        val m = disownedPalm()
        assertDecision(Decision.NONE, m.up(PALM_ID))
        assertEquals(ExclusivityState.IDLE, m.state)
        assertEquals(0, m.downPointerCount)
    }

    // ---- self-heal and bookkeeping ---------------------------------------

    /**
     * ACTION_DOWN means the framework believes zero pointers were down before
     * this event, so any other state is stale — usually a stroke that never
     * ended because the window lost focus without a cancel. Every reachable
     * stuck state therefore clears on the next touch, without waiting for a
     * lifecycle callback that may never arrive.
     */
    @Test
    fun `a DOWN from any stale state cancels it and then starts clean`() {
        val fromPen = penStroke()
        val d = fromPen.down(5, ToolClass.FINGER)
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, d)
        // One finger, so DISOWNED and not GESTURE — the stale stroke is gone
        // either way, which is what this test is about.
        assertEquals(ExclusivityState.DISOWNED, fromPen.state)
        assertEquals(1, fromPen.downPointerCount)

        val fromGesture = fingerGesture()
        assertDecision(
            Decision.GESTURE_CANCEL or Decision.STROKE_BEGIN or
                Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            fromGesture.down(PEN_ID, ToolClass.PEN),
        )
        assertEquals(ExclusivityState.PEN, fromGesture.state)

        // Presence went off when the pen lifted into DISOWNED, so this DOWN
        // reports it coming back on.
        val fromDisowned = disownedPalm()
        assertDecision(
            Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            fromDisowned.down(PEN_ID, ToolClass.PEN),
        )
        assertEquals(1, fromDisowned.downPointerCount, "the stale palm is gone")
    }

    @Test
    fun `abandon reaches IDLE from every state and cancels whatever was live`() {
        assertEquals(Decision.NONE, StrokeExclusivity().abandon())

        val pen = penStroke()
        assertDecision(Decision.STROKE_CANCEL or Decision.PEN_PRESENCE_OFF, pen.abandon())
        assertEquals(ExclusivityState.IDLE, pen.state)

        val gesture = fingerGesture()
        assertDecision(Decision.GESTURE_CANCEL, gesture.abandon())
        assertEquals(ExclusivityState.IDLE, gesture.state)

        val disowned = disownedPalm()
        assertDecision(Decision.NONE, disowned.abandon())
        assertEquals(ExclusivityState.IDLE, disowned.state)
        assertEquals(0, disowned.downPointerCount)

        // Hover survives nothing: the window is going away with the surface the
        // cursor was drawn on.
        val hovering = StrokeExclusivity().also { it.hover(PointerAction.HOVER_ENTER, ToolClass.PEN) }
        assertDecision(Decision.PEN_PRESENCE_OFF, hovering.abandon())
    }

    /**
     * Reachable after a stale-state reset. Membership is by id for exactly this
     * reason: a counter decremented here underflows to -1 and the machine is
     * stuck in GESTURE with no exit short of a cancel.
     */
    @Test
    fun `a lift for a pointer that was never tracked changes nothing`() {
        val m = fingerGesture()
        assertDecision(Decision.NONE, m.pointerUp(42))
        assertEquals(2, m.gesturePointerCount)
        assertEquals(2, m.downPointerCount)
        assertEquals(ExclusivityState.GESTURE, m.state)
    }

    @Test
    fun `the same pointer id going down twice is tracked once`() {
        val m = fingerGesture()
        m.pointerDown(1, ToolClass.FINGER)
        assertEquals(2, m.downPointerCount)
        assertEquals(2, m.gesturePointerCount)
    }

    /**
     * The same id arriving twice while it is only *pending* must not fake up
     * the second finger a gesture needs. A duplicate POINTER_DOWN is reachable
     * after a stale-state reset, and counting it would pan the canvas from one
     * resting palm — the bug the two-finger rule exists to remove.
     */
    @Test
    fun `one finger reported twice is still one finger and opens no gesture`() {
        val m = StrokeExclusivity()
        m.down(1, ToolClass.FINGER)
        assertDecision(Decision.NONE, m.pointerDown(1, ToolClass.FINGER))
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertEquals(1, m.downPointerCount)
    }

    @Test
    fun `an eleventh contact is dropped rather than growing the pointer set`() {
        val m = StrokeExclusivity()
        m.down(0, ToolClass.FINGER)
        // The second finger is what makes this a gesture at all.
        for (i in 1..15) m.pointerDown(i, ToolClass.FINGER)
        assertEquals(StrokeExclusivity.MAX_POINTERS, m.downPointerCount)
        assertEquals(ExclusivityState.GESTURE, m.state)
        // And it still unwinds: the drop cannot strand the machine.
        assertDecision(Decision.GESTURE_END, m.up(0))
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    // ---- hover ------------------------------------------------------------

    /**
     * Hover is a bit beside the machine, not a fifth state. A HOVER_EXIT that
     * never arrives — the pen yanked away, the window losing focus — would wedge
     * a state machine in a state that blocks drawing; a stale bit can only draw
     * a stale cursor.
     */
    @Test
    fun `hover never alters exclusivity state, from any state`() {
        for (build in stateBuilders()) {
            for (action in listOf(PointerAction.HOVER_ENTER, PointerAction.HOVER_MOVE, PointerAction.HOVER_EXIT)) {
                for (tool in ToolClass.entries) {
                    val m = build()
                    val before = m.state
                    val pointers = m.downPointerCount
                    val stroke = m.penStrokeId
                    m.hover(action, tool)
                    assertEquals(before, m.state, "$action/$tool moved the machine out of $before")
                    assertEquals(pointers, m.downPointerCount)
                    assertEquals(stroke, m.penStrokeId)
                }
            }
        }
    }

    @Test
    fun `hover from anything but a pen is dropped`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.NONE, m.hover(PointerAction.HOVER_ENTER, ToolClass.FINGER))
        assertTrue(!m.penInRange)
    }

    /**
     * The framework sends HOVER_EXIT both when the pen leaves range and
     * immediately before ACTION_DOWN, so presence is forced back on at stroke
     * begin rather than inferred from the sequence.
     */
    @Test
    fun `stroke begin forces presence on, undoing the HOVER_EXIT that precedes a DOWN`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.PEN_PRESENCE_ON, m.hover(PointerAction.HOVER_ENTER, ToolClass.PEN))
        assertDecision(Decision.PEN_PRESENCE_OFF, m.hover(PointerAction.HOVER_EXIT, ToolClass.PEN))
        assertTrue(m.down(PEN_ID, ToolClass.PEN) has Decision.PEN_PRESENCE_ON)
        assertTrue(m.penInRange)
    }

    @Test
    fun `presence is reported only when it changes`() {
        val m = StrokeExclusivity()
        assertDecision(Decision.PEN_PRESENCE_ON, m.hover(PointerAction.HOVER_ENTER, ToolClass.PEN))
        assertDecision(Decision.NONE, m.hover(PointerAction.HOVER_MOVE, ToolClass.PEN))
        assertDecision(Decision.NONE, m.hover(PointerAction.HOVER_MOVE, ToolClass.PEN))
        assertDecision(Decision.PEN_PRESENCE_OFF, m.hover(PointerAction.HOVER_EXIT, ToolClass.PEN))
        assertDecision(Decision.NONE, m.hover(PointerAction.HOVER_EXIT, ToolClass.PEN))
    }

    /**
     * A DOWN carrying the sentinel would set penStrokeId to NO_POINTER: a
     * stroke that emits samples while `state` reads IDLE, and nothing
     * downstream would ever see an end for it.
     */
    @Test
    fun `an action that names a pointer is refused without a real id`() {
        assertFailsWith<IllegalArgumentException> {
            StrokeExclusivity().route(PointerAction.DOWN, NO_POINTER, ToolClass.PEN, canceled = false)
        }
        assertFailsWith<IllegalArgumentException> {
            penStroke().route(PointerAction.POINTER_UP, NO_POINTER, ToolClass.PEN, canceled = false)
        }
        // MOVE and CANCEL concern the whole event and take the sentinel.
        StrokeExclusivity().route(PointerAction.MOVE, NO_POINTER, ToolClass.FINGER, canceled = false)
        StrokeExclusivity().route(PointerAction.CANCEL, NO_POINTER, ToolClass.FINGER, canceled = false)
    }

    @Test
    fun `routing a hover action through the touch entry point is refused`() {
        val m = StrokeExclusivity()
        assertFailsWith<IllegalArgumentException> {
            m.route(PointerAction.HOVER_MOVE, 1, ToolClass.PEN, canceled = false)
        }
        assertFailsWith<IllegalArgumentException> { m.hover(PointerAction.MOVE, ToolClass.PEN) }
    }

    // ---- invariants over the whole table ---------------------------------

    /**
     * There is no path into a state that a single lift cannot exit. Enumerated
     * over every state, tool and cancel flag rather than argued, because the
     * whole risk here is a cell nobody thought about.
     */
    @Test
    fun `UP and CANCEL land in IDLE with empty sets from every state`() {
        for (build in stateBuilders()) {
            for (action in listOf(PointerAction.UP, PointerAction.CANCEL)) {
                for (tool in ToolClass.entries) {
                    for (canceled in listOf(false, true)) {
                        for (id in listOf(PEN_ID, PALM_ID, 99)) {
                            val m = build()
                            val was = m.state
                            m.route(action, id, tool, canceled)
                            assertEquals(
                                ExclusivityState.IDLE, m.state,
                                "$was + $action(id=$id, $tool, canceled=$canceled)",
                            )
                            assertEquals(0, m.downPointerCount)
                            assertEquals(0, m.gesturePointerCount)
                            assertEquals(NO_POINTER, m.penStrokeId)
                        }
                    }
                }
            }
        }
    }

    /**
     * The two presence bits describe a transition, so a mask carrying both is
     * self-contradictory. Reachable in principle: a DOWN can cancel one stroke
     * and begin another inside one event.
     */
    @Test
    fun `no single decision reports the pen both entering and leaving range`() {
        forEveryLegalSequence { _, decision ->
            assertTrue(
                !(decision has Decision.PEN_PRESENCE_ON) || !(decision has Decision.PEN_PRESENCE_OFF),
                "both presence bits in ${Decision.names(decision)}",
            )
        }
    }

    /**
     * The rule itself, checked over random sequences rather than only over the
     * cases someone thought to write down: ink and transform are never both
     * live, and a live stroke never has a gesture pointer beside it.
     */
    @Test
    fun `a stroke and a gesture are never live at the same time`() {
        forEveryLegalSequence { m, _ ->
            if (m.state == ExclusivityState.PEN) {
                assertEquals(0, m.gesturePointerCount, "a gesture owns a pointer during a live stroke")
            }
            if (m.state == ExclusivityState.GESTURE) {
                assertEquals(NO_POINTER, m.penStrokeId, "a stroke is live during a gesture")
            }
        }
    }

    @Test
    fun `stroke samples are only ever released while a stroke is live`() {
        forEveryLegalSequence { m, decision ->
            if (decision has Decision.STROKE_SAMPLES) {
                assertEquals(ExclusivityState.PEN, m.state)
            }
            if (decision has Decision.GESTURE_POINTERS) {
                assertEquals(ExclusivityState.GESTURE, m.state)
            }
        }
    }

    @Test
    fun `the tracked pointer count never exceeds the fixed pointer set`() {
        forEveryLegalSequence { m, _ ->
            assertTrue(m.downPointerCount in 0..StrokeExclusivity.MAX_POINTERS)
            assertTrue(m.gesturePointerCount <= m.downPointerCount)
        }
    }

    // ---- harness ----------------------------------------------------------

    private fun stateBuilders(): List<() -> StrokeExclusivity> = listOf(
        { StrokeExclusivity() },
        { penStroke() },
        { fingerGesture() },
        { disownedPalm() },
    )

    /**
     * 20000 random action sequences. The fixed seed means a failure is
     * reproducible; an unseeded generator here would produce a bug report that
     * says "sometimes".
     */
    private fun forEveryLegalSequence(check: (StrokeExclusivity, Int) -> Unit) {
        val rng = Random(20260907)
        val actions = PointerAction.entries.filter { !it.isHover }
        repeat(2000) {
            val m = StrokeExclusivity()
            repeat(10) {
                val action = actions[rng.nextInt(actions.size)]
                val decision = m.route(
                    action,
                    // Real ids only: -1 is the sentinel and route() refuses it
                    // for every action that names a pointer.
                    rng.nextInt(0, 4),
                    if (rng.nextBoolean()) ToolClass.PEN else ToolClass.FINGER,
                    rng.nextBoolean(),
                )
                check(m, decision)
            }
        }
    }

    private fun penStroke(): StrokeExclusivity =
        StrokeExclusivity().also { it.down(PEN_ID, ToolClass.PEN) }

    /** Two fingers, because one is [ExclusivityState.DISOWNED] and pans nothing. */
    private fun fingerGesture(): StrokeExclusivity = StrokeExclusivity().also {
        it.down(1, ToolClass.FINGER)
        it.pointerDown(2, ToolClass.FINGER)
    }

    /** Pen down, palm lands, pen lifts: a resting hand and nothing owned. */
    private fun disownedPalm(): StrokeExclusivity = penStroke().also {
        it.pointerDown(PALM_ID, ToolClass.FINGER)
        it.pointerUp(PEN_ID)
    }

    private fun assertDecision(expected: Int, actual: Int) {
        assertEquals(
            expected, actual,
            "expected ${Decision.names(expected)}, got ${Decision.names(actual)}",
        )
    }

    private infix fun Int.has(bit: Int) = this and bit != 0

    private companion object {
        const val PEN_ID = 7
        const val PALM_ID = 3
    }
}

// Named the way the call sites read on device, so a test sequence can be
// compared against a MotionEvent log line by line. The tool argument is ignored
// for MOVE and CANCEL, which concern the whole event rather than one pointer.
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

private fun StrokeExclusivity.lost(id: Int) =
    route(PointerAction.POINTER_LOST, id, ToolClass.PEN, canceled = false)
