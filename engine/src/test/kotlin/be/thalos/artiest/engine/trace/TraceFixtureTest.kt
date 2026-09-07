package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.Decision
import be.thalos.artiest.engine.input.ExclusivityState
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.StrokeExclusivity
import be.thalos.artiest.engine.input.ToolClass
import be.thalos.artiest.engine.input.ToolType
import be.thalos.artiest.engine.input.toolClassOf
import be.thalos.artiest.engine.xform.CanvasTransform
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A checked-in v1 file that this decoder must keep reading forever.
 *
 * The version header on its own only upgrades a silent misread into a crash.
 * What keeps an old corpus replayable is a fixture nobody is allowed to
 * re-record: when the format goes to v2, this test fails until a migration
 * exists, and re-recording is not a migration — a re-recorded stroke is a
 * different stroke, and swapping it in rebaselines every golden that compares
 * against it while looking like a successful upgrade.
 *
 * It is also the shape of the thing this work item exists to produce: one
 * recording off the tablet turns behaviour that is otherwise device-only into a
 * native regression test.
 */
class TraceFixtureTest {

    private val trace: Trace = TracePlayer.decode(
        checkNotNull(javaClass.getResourceAsStream("/trace/v1-baseline.trace")) {
            "the v1 fixture is missing from the test resources"
        }.reader().readText(),
    )

    @Test
    fun `the v1 fixture still decodes with its header intact`() {
        assertEquals(1, trace.header.version)
        assertEquals(84_213_770_166_000L, trace.header.t0Nanos)
        assertEquals(CanvasTransform.IDENTITY, trace.header.transform)
        assertEquals("DTH-A116", trace.header.meta["device"])
        assertEquals("60.0", trace.header.meta["refreshHz"])
        assertEquals(5, trace.events.size)
        assertEquals(7, trace.sampleCount)
        // No event carries the trailing '!', and absent decodes as false rather
        // than as unknown — which is what keeps every v1 file written before
        // the flag had a channel readable without a version bump.
        assertEquals(List(5) { false }, trace.events.map { it.canceled })
    }

    @Test
    fun `the v1 fixture's samples decode to the exact values it spells`() {
        val down = trace.events[0].samples.single()
        assertEquals(512.3125f.toRawBits(), down.x.toRawBits())
        assertEquals(900.75f.toRawBits(), down.y.toRawBits())
        // ACTION_DOWN arrives at effectively zero pressure on this digitizer,
        // measured in Phase 0. Nothing may filter it out as noise: the onset
        // ramp is a later stage's job, and a router that drops it reads as
        // latency.
        assertEquals(0.00208f.toRawBits(), down.pressure.toRawBits())
        assertEquals(ToolType.STYLUS, down.toolType)
        assertEquals(PenSample.Source.CURRENT, down.source)
        assertEquals(trace.header.t0Nanos, down.eventTimeNanos)

        val move = trace.events[1].samples
        assertEquals(4, move.size)
        assertEquals(
            listOf(PenSample.Source.HISTORICAL, PenSample.Source.HISTORICAL, PenSample.Source.HISTORICAL, PenSample.Source.CURRENT),
            move.map { it.source },
            "history is oldest-first with the current sample last",
        )
        // The barrel button appears mid-batch, which is the shape of the known
        // limit: Android exposes no historical buttonState, so the press is
        // stamped across every sample of the event it was read on.
        assertEquals(listOf(0, 0, 4, 4), move.map { it.buttonState })
        assertEquals(trace.header.t0Nanos + 16_204_000L, move.last().eventTimeNanos)

        // The palm. Also what a flipped pen reports: this one has no eraser end.
        assertEquals(ToolType.FINGER, trace.events[2].samples.single().toolType)

        // A cancel carries no sample, because its coordinates are stale.
        assertEquals(PointerAction.CANCEL, trace.events[4].action)
        assertEquals(0, trace.events[4].samples.size)
    }

    /**
     * The payoff, and the reason the trace records raw actions rather than the
     * router's conclusions: the exclusivity machine can be driven from a file.
     * A palm landing mid-stroke and a pen lifting with the hand still down are
     * the two sequences that are least reproducible by hand on a tablet, and
     * here they run in a millisecond with no device attached.
     */
    @Test
    fun `replaying the fixture through the exclusivity machine reproduces the routing`() {
        val m = StrokeExclusivity()
        val decisions = trace.events.map { e ->
            // The tool comes from the samples rather than from a recorded
            // verdict. Recording the router's own classification would let the
            // trace confirm whatever the router does with it.
            val tool = e.samples.firstOrNull()?.let { toolClassOf(it.toolType) } ?: ToolClass.FINGER
            // From the file, not hardcoded: FLAG_CANCELED is what turns the
            // POINTER_UP below into a discard instead of a commit, so a replay
            // that assumes false cannot pin the routing it claims to.
            m.route(e.action, e.pointerId, tool, e.canceled)
        }

        assertEquals(
            listOf(
                Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
                Decision.STROKE_SAMPLES,
                // The palm, dropped without ending the stroke.
                Decision.NONE,
                Decision.STROKE_END or Decision.PEN_PRESENCE_OFF,
                Decision.NONE,
            ),
            decisions,
            decisions.joinToString { Decision.names(it) },
        )
        assertEquals(ExclusivityState.IDLE, m.state)
    }

    /**
     * The state between the pen lifting and the palm lifting, which is where a
     * machine without DISOWNED strands the artist: hand still resting, pen back
     * on the glass, nothing draws.
     */
    @Test
    fun `the fixture's pen lift leaves a disowned palm that the next pen down draws over`() {
        val m = StrokeExclusivity()
        for (e in trace.events.take(4)) {
            val tool = e.samples.firstOrNull()?.let { toolClassOf(it.toolType) } ?: ToolClass.FINGER
            m.route(e.action, e.pointerId, tool, e.canceled)
        }
        assertEquals(ExclusivityState.DISOWNED, m.state)
        assertEquals(1, m.downPointerCount)
        assertEquals(
            Decision.STROKE_BEGIN or Decision.STROKE_SAMPLES or Decision.PEN_PRESENCE_ON,
            m.route(PointerAction.POINTER_DOWN, 0, ToolClass.PEN, canceled = false),
        )
    }
}
