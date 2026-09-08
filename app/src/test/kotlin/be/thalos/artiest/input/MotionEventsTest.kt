package be.thalos.artiest.input

import android.os.SystemClock
import android.view.MotionEvent
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three mandated edits to the spike's `collectSamples`, exercised against a
 * real `MotionEvent` rather than reviewed.
 *
 * Robolectric earns its place on the pointerId edit above all: index
 * shuffling is the failure that edit exists to prevent, and it cannot be
 * written down without an event carrying two pointers. What Robolectric cannot
 * check is stated where it matters — timestamps here are milliseconds scaled by
 * a million, so nothing below asserts anything about nanosecond resolution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionEventsTest {

    /**
     * Two pointers, the pen deliberately second: index 1 at DOWN, index 0 the
     * moment the palm lifts. A caller that cached the index draws the palm.
     */
    @Test
    fun `samples follow the pointer id when the indices shuffle underneath`() {
        val both = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(
                TestPointer(id = PALM_ID, toolType = ToolType.FINGER, x = 100f, y = 200f),
                TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = 512f, y = 900f),
            ),
        )
        val out = ArrayList<PenSample>()
        assertTrue(both.collectSamples(out, PEN_ID))
        assertEquals(1, out.size)
        assertEquals(512f, out[0].x)
        assertEquals(ToolType.STYLUS, out[0].toolType)

        // The palm lifts; the pen is now at index 0, with the same id.
        val penOnly = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = 513f, y = 901f)),
        )
        out.clear()
        assertTrue(penOnly.collectSamples(out, PEN_ID))
        assertEquals(513f, out[0].x)
        assertEquals(0, penOnly.findPointerIndex(PEN_ID))
    }

    /**
     * A pointer that is not in this event is not an error and must not be
     * indexed anyway — `findPointerIndex` returns -1 and `getX(-1)` reads
     * whatever is next in memory. Nothing is appended, and the caller is told,
     * so the router can turn it into a cancel instead of a sample.
     */
    @Test
    fun `an absent pointer appends nothing and reports itself`() {
        val e = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(TestPointer(id = PALM_ID, toolType = ToolType.FINGER, x = 1f, y = 2f)),
        )
        val out = ArrayList<PenSample>()
        assertFalse(e.collectSamples(out, PEN_ID))
        assertEquals(0, out.size)
    }

    /**
     * History oldest first, then the current sample. The digitizer runs at
     * 246.85 Hz against a 60 Hz panel, so this is where most of the pen's
     * resolution lives; reading only getX throws it away and the curve facets.
     */
    @Test
    fun `history comes out oldest first and the current sample comes last`() {
        val e = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = 10f, y = 10f)),
        )
        e.addBatch(DOWN_TIME_MS + 1, batch(20f, 20f, pressure = 0.25f), 0)
        e.addBatch(DOWN_TIME_MS + 2, batch(30f, 30f, pressure = 0.5f), 0)

        val out = ArrayList<PenSample>()
        e.collectSamples(out, PEN_ID)

        assertEquals(listOf(10f, 20f, 30f), out.map { it.x })
        assertEquals(
            listOf(
                PenSample.Source.HISTORICAL,
                PenSample.Source.HISTORICAL,
                PenSample.Source.CURRENT,
            ),
            out.map { it.source },
        )
        assertEquals(0.5f, out.last().pressure)
    }

    /**
     * Appends, and the caller clears. The whole path shares one scratch list,
     * so a missing clear is unbounded growth at 250-320 Hz rather than the
     * visible duplicate-sample bug it would be with a list per caller.
     */
    @Test
    fun `collectSamples appends to what it is given`() {
        val e = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = 1f, y = 1f)),
        )
        val out = ArrayList<PenSample>()
        e.collectSamples(out, PEN_ID)
        e.collectSamples(out, PEN_ID)
        assertEquals(2, out.size)
    }

    /**
     * The source is a parameter, not a function of where the sample was read
     * from. This is the call W11's `Predictor` makes — the current state of a
     * synthesized event, tagged PREDICTED — and it is the reason the enum value
     * had no producer in the spike.
     */
    @Test
    fun `a sample carries the source it was asked for, not the one its position implies`() {
        val e = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = 7f, y = 8f)),
        )
        val predicted = e.sampleAt(0, CURRENT, PenSample.Source.PREDICTED)
        assertEquals(PenSample.Source.PREDICTED, predicted.source)
        assertEquals(7f, predicted.x)
    }

    @Test
    fun `pressure tilt and the button state come through the boundary`() {
        val e = motionEvent(
            action = MotionEvent.ACTION_DOWN,
            pointers = listOf(
                TestPointer(
                    id = PEN_ID,
                    toolType = ToolType.STYLUS,
                    x = 5f,
                    y = 6f,
                    // The measured near-zero pressure at DOWN. Nothing filters
                    // it out here: the onset ramp is W7's, and a router that
                    // dropped low-pressure samples would read as latency.
                    pressure = 0.00208f,
                    tilt = 0.34906584f,
                ),
            ),
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        val out = ArrayList<PenSample>()
        e.collectSamples(out, PEN_ID)
        assertEquals(0.00208f, out[0].pressure)
        assertEquals(0.34906584f, out[0].tilt)
        assertEquals(32, out[0].buttonState)
        assertEquals(0f, out[0].distance, "AXIS_DISTANCE is dead; the column is still read")
    }

    private fun batch(x: Float, y: Float, pressure: Float): Array<MotionEvent.PointerCoords> =
        arrayOf(pointerCoords(TestPointer(id = PEN_ID, x = x, y = y, pressure = pressure)))

    private companion object {
        const val PEN_ID = 7
        const val PALM_ID = 3
    }

    @Test
    fun `input age is measured against the newest sample`() {
        // Robolectric fabricates eventTimeNanos as milliseconds x 1e6, which is
        // exactly the fallback path this runs on below API 34 — so the number
        // here is the arithmetic, not the platform's nanosecond clock. The
        // nanosecond path is the device's, and the readout is where it is read.
        val event = motionEvent(MotionEvent.ACTION_MOVE, listOf(TestPointer(0)), eventTimeMs = 2_000L)
        try {
            val now = 2_007L * 1_000_000L
            assertEquals(7_000_000L, event.eventAgeNanos(now))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `an event from the future reads as zero, not as a negative age`() {
        // Not defensive tidiness: a negative sample would drag a percentile
        // below zero and make the latency figure read *better* than perfect,
        // which is the one direction a latency number must never be wrong in.
        val event = motionEvent(MotionEvent.ACTION_MOVE, listOf(TestPointer(0)), eventTimeMs = 2_000L)
        try {
            assertEquals(0L, event.eventAgeNanos(1_990L * 1_000_000L))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `the clock check subtracts the two clocks the age depends on`() {
        // **This cannot assert the fact it exists for, and that is the finding.**
        // The question is whether `MotionEvent.eventTimeNanos` (documented in
        // the `SystemClock.uptimeMillis` base) and `System.nanoTime` read the
        // same counter, because if they do not the input-age figure is a
        // plausible number that is simply wrong. Under Robolectric they do not:
        // `uptimeMillis` is a *simulated* clock that starts near zero while
        // `nanoTime` is the host JVM's, and the first version of this test
        // failed with a skew of 74,071 seconds — which says nothing whatsoever
        // about the tablet.
        //
        // So the arithmetic is pinned here and the fact is checked at the point
        // of use: `MainActivity`'s readout refuses to print an age at all when
        // the skew is out of range, which is an assertion left running on the
        // only machine that can answer it.
        val expected = System.nanoTime() - SystemClock.uptimeMillis() * 1_000_000L
        val actual = clockSkewNanos()

        assertTrue(
            abs(actual - expected) < 50_000_000L,
            "clockSkewNanos is not nanoTime minus the uptime base: $actual vs $expected",
        )
    }
}
