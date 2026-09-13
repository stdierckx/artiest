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
 * by waiting, and a test that waits 300 ms to prove a hold is too long is a
 * test that gets deleted the first time CI is slow.
 *
 * The negative cases are the substance. A tap recogniser that fires on
 * everything passes the one positive test in the file, and this one fires an
 * *undo*: every false positive is a stroke taken off somebody's drawing while
 * they were resting a hand. So each rejection is paired with the accepted case
 * it differs from by exactly one thing.
 */
class TwoFingerTapTest {

    private val ms = 1_000_000L

    @Test
    fun `two fingers down and straight back up is a tap`() {
        assertTrue(tap(TwoFingerTap(), at = 0L, x = 700f, y = 500f))
    }

    @Test
    fun `taps in a run all fire`() {
        // The case the double tap could not serve and the reason it was
        // replaced: three strokes wrong is three taps as fast as the hand can
        // make them, and every one of them has to undo.
        val t = TwoFingerTap()

        assertTrue(tap(t, at = 0L, x = 700f, y = 500f))
        assertTrue(tap(t, at = 200 * ms, x = 706f, y = 494f))
        assertTrue(tap(t, at = 400 * ms, x = 690f, y = 510f))
    }

    @Test
    fun `a hold is not a tap`() {
        assertFalse(tap(TwoFingerTap(), at = 0L, x = 700f, y = 500f, heldMs = 400))
    }

    @Test
    fun `a tap that travelled is a pan, not a tap`() {
        val t = TwoFingerTap()

        t.begin(0L, 2, 700f, 500f)
        t.move(2, 760f, 500f)
        t.move(2, 700f, 500f) // and back again: travel is from the start, not summed
        assertFalse(t.end(100 * ms))
    }

    @Test
    fun `a still finger on a jittery digitizer is still a tap`() {
        // The other side of the rule above. Travel measured as summed path
        // length would disqualify this — twenty samples of jitter add up — and
        // an undo that only works if you are perfectly still is an undo nobody
        // trusts.
        val t = TwoFingerTap()

        t.begin(0L, 2, 700f, 500f)
        for (i in 0 until 20) {
            t.move(2, 700f + (i % 3) - 1f, 500f + ((i + 1) % 3) - 1f)
        }
        t.move(1, 580f, 500f)
        assertTrue(t.end(60 * ms))
    }

    @Test
    fun `the centroid jumping as a finger lifts is not travel`() {
        // The defect the double-tap recogniser shipped with, kept here because
        // the rule is the same and the failure was invisible: the router
        // reports the gesture's pointers again when one lifts, and the centroid
        // of one finger is 120 px from the centroid of two held a hand-span
        // apart. Five times the travel bound, on every tap, with nothing having
        // moved.
        val t = TwoFingerTap()
        t.begin(0L, 2, 700f, 500f)
        t.move(2, 700f, 500f)
        t.move(1, 580f, 500f)
        assertTrue(t.end(60 * ms), "a lift is bookkeeping, not motion")

        // The control: the same distance travelled by the same two fingers is
        // still a pan, so the rule is about the pointer set and not about
        // forgiving 120 px.
        val e = TwoFingerTap()
        e.begin(0L, 2, 700f, 500f)
        e.move(2, 580f, 500f)
        assertFalse(e.end(60 * ms))
    }

    @Test
    fun `a third finger joining is not a two-finger tap`() {
        val t = TwoFingerTap()

        t.begin(0L, 2, 700f, 500f)
        t.move(2, 700f, 500f)
        t.move(3, 700f, 500f)
        t.move(1, 580f, 500f)
        assertFalse(t.end(60 * ms), "three fingers must stay free to mean something else")
    }

    @Test
    fun `a gesture that opened with one reported pointer is not a tap`() {
        // Reachable: `emitGesturePointers` skips a pointer that is not in the
        // event it is reading, so the first report can carry one position for a
        // gesture the machine opened with two.
        val t = TwoFingerTap()

        t.begin(0L, 1, 700f, 500f)
        assertFalse(t.end(60 * ms))
    }

    @Test
    fun `a cancelled gesture is not a tap`() {
        // A cancel is the framework or the exclusivity rules taking the gesture
        // away — a palm, an ACTION_CANCEL, the pen arriving. Undoing on one
        // would take a stroke off the drawing in the middle of making it.
        val t = TwoFingerTap()

        t.begin(0L, 2, 700f, 500f)
        t.move(2, 700f, 500f)
        t.cancel()
        assertFalse(t.end(60 * ms))
    }

    @Test
    fun `an end with no begin is not a tap`() {
        // Reachable: `onGestureEnd` fires for a gesture whose pointers were
        // never reported, and the recogniser is only primed by the first
        // pointer report.
        assertFalse(TwoFingerTap().end(10 * ms))
    }

    /** One tap: begin, one centroid report, the lift report, end. */
    private fun tap(
        t: TwoFingerTap,
        at: Long,
        x: Float,
        y: Float,
        heldMs: Long = 60,
    ): Boolean {
        t.begin(at, 2, x, y)
        t.move(2, x, y)
        // The lift: the router reports the gesture's pointers once more as the
        // first finger leaves, and the surviving finger's position is nowhere
        // near the pair's centroid. Every tap ends this way, so every tap in
        // this file drives it.
        t.move(1, x - 120f, y)
        return t.end(at + heldMs * ms)
    }
}
