package be.thalos.artiest.engine.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recogniser, driven the way the router drives it.
 *
 * Every time is a parameter, so the whole of this runs in microseconds with no
 * sleep and no clock — which is the reason the class takes times instead of
 * reading one. A recogniser with `System.nanoTime()` inside it is testable only
 * by waiting, and a test that waits 300 ms to prove a gap is too long is a test
 * that gets deleted the first time CI is slow.
 *
 * The negative cases are the substance here. A double-tap recogniser that fires
 * on everything passes the one positive test in the file, so each rejection is
 * paired with the accepted case it differs from by exactly one thing.
 */
class TwoFingerDoubleTapTest {

    private val ms = 1_000_000L

    @Test
    fun `two quick taps in the same place fire`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f), "the first tap alone is not a double tap")
        assertTrue(tap(d, at = 200 * ms, x = 706f, y = 494f))
    }

    @Test
    fun `one tap does not fire, however long you wait`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f))
        // The control for every negative below: if a single tap fired, none of
        // them would be measuring what they claim to.
    }

    @Test
    fun `a hold is not a tap`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f, heldMs = 400))
        assertFalse(
            tap(d, at = 500 * ms, x = 700f, y = 500f),
            "a hold must not count as the first half either",
        )
    }

    @Test
    fun `a tap that travelled is a pan, not a tap`() {
        val d = TwoFingerDoubleTap()

        d.begin(0L, 2, 700f, 500f)
        d.move(2, 760f, 500f)
        d.move(2, 700f, 500f) // and back again: travel is from the start, not summed
        assertFalse(d.end(100 * ms))

        assertFalse(tap(d, at = 200 * ms, x = 700f, y = 500f), "and it primed nothing")
    }

    @Test
    fun `a still finger on a jittery digitizer is still a tap`() {
        // The other side of the rule above. Travel measured as summed path
        // length would disqualify this — twenty samples of jitter add up — and
        // a double tap that only works if you are perfectly still is a gesture
        // nobody discovers.
        val d = TwoFingerDoubleTap()

        assertFalse(jitteryTap(d, at = 0L, x = 700f, y = 500f))
        assertTrue(jitteryTap(d, at = 200 * ms, x = 700f, y = 500f))
    }

    @Test
    fun `two taps too far apart in time do not fire`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f))
        assertFalse(tap(d, at = 900 * ms, x = 700f, y = 500f))
    }

    @Test
    fun `two taps too far apart on the glass do not fire`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 200f, y = 500f))
        assertFalse(tap(d, at = 200 * ms, x = 1200f, y = 500f))
    }

    @Test
    fun `a third tap does not fit the canvas a second time`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f))
        assertTrue(tap(d, at = 200 * ms, x = 700f, y = 500f))
        // Without the reset on a hit, a jittery double tap — three contacts
        // where the user meant two — fits the canvas twice, and the second one
        // lands after they have started drawing again.
        assertFalse(tap(d, at = 400 * ms, x = 700f, y = 500f))
        // And a fourth pairs with the third, which is a new double tap.
        assertTrue(tap(d, at = 600 * ms, x = 700f, y = 500f))
    }

    @Test
    fun `a cancelled gesture forgets the tap before it`() {
        val d = TwoFingerDoubleTap()

        assertFalse(tap(d, at = 0L, x = 700f, y = 500f))
        d.begin(150 * ms, 2, 700f, 500f)
        d.cancel()

        // A cancel is the framework or the exclusivity rules taking the gesture
        // away — a palm, an ACTION_CANCEL, the pen arriving. Pairing across one
        // would fit the canvas in the middle of a stroke.
        assertFalse(tap(d, at = 300 * ms, x = 700f, y = 500f))
    }

    @Test
    fun `the centroid jumping as a finger lifts is not travel`() {
        // The defect this class shipped with, and it made the gesture do
        // nothing at all on the tablet while every test here passed: the router
        // reports the gesture's pointers again when one lifts, and the centroid
        // of one finger is 120 px from the centroid of two held a hand-span
        // apart. Five times the travel bound, on every tap, with nothing having
        // moved.
        val d = TwoFingerDoubleTap()

        d.begin(0L, 2, 700f, 500f)
        d.move(2, 700f, 500f)
        d.move(1, 580f, 500f)
        assertFalse(d.end(60 * ms))

        d.begin(200 * ms, 2, 700f, 500f)
        d.move(2, 700f, 500f)
        d.move(1, 580f, 500f)
        assertTrue(d.end(260 * ms), "a lift is bookkeeping, not motion")

        // The control: the same distance travelled by the same two fingers is
        // still a pan, so the rule is about the pointer set and not about
        // forgiving 120 px.
        val e = TwoFingerDoubleTap()
        e.begin(0L, 2, 700f, 500f)
        e.move(2, 580f, 500f)
        assertFalse(e.end(60 * ms))
        e.begin(200 * ms, 2, 700f, 500f)
        e.move(2, 580f, 500f)
        assertFalse(e.end(260 * ms))
    }

    @Test
    fun `an end with no begin is not a tap`() {
        // Reachable: `onGestureEnd` fires for a gesture whose pointers were
        // never reported, and the recogniser is only primed by the first
        // pointer report.
        assertFalse(TwoFingerDoubleTap().end(10 * ms))
    }

    /** One tap: begin, one centroid report, end [heldMs] later. */
    private fun tap(
        d: TwoFingerDoubleTap,
        at: Long,
        x: Float,
        y: Float,
        heldMs: Long = 60,
    ): Boolean {
        d.begin(at, 2, x, y)
        d.move(2, x, y)
        // The lift: the router reports the gesture's pointers once more as the
        // first finger leaves, and the surviving finger's position is nowhere
        // near the pair's centroid. Every tap ends this way, so every tap in
        // this file drives it.
        d.move(1, x - 120f, y)
        return d.end(at + heldMs * ms)
    }

    /** The same, with the centroid wandering a pixel at a time and coming back. */
    private fun jitteryTap(d: TwoFingerDoubleTap, at: Long, x: Float, y: Float): Boolean {
        d.begin(at, 2, x, y)
        for (i in 0 until 20) {
            d.move(2, x + (i % 3) - 1f, y + ((i + 1) % 3) - 1f)
        }
        d.move(1, x - 120f, y)
        return d.end(at + 60 * ms)
    }
}
