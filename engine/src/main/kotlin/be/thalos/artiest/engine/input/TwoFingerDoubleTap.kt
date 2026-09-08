package be.thalos.artiest.engine.input

/**
 * Two fingers, down and up twice, quickly, in the same place — the gesture that
 * puts the canvas back.
 *
 * **Why two fingers and not one.** A single-finger tap never reaches the app at
 * all: `StrokeExclusivity` drops lone fingers entirely, and that rule is what
 * keeps a palm landing before the pen from locking the pen out. Reopening it
 * for a shortcut would trade the phase's central input property for a
 * convenience. Two fingers is what already opens a gesture, so a two-finger
 * double tap costs nothing and breaks nothing — this class watches gestures
 * that were going to happen anyway and notices when one of them did not move.
 *
 * It is a recogniser and not a policy: it says "that was a double tap" and the
 * caller decides that this means `fitToView`. Pure float math, no allocation,
 * and no clock of its own — every time comes in as a parameter, which is what
 * makes the whole of it testable without a device or a sleep.
 *
 * **The centroid, not a pointer.** Fingers land and lift a few milliseconds
 * apart and their ids shuffle between taps, so anything tracking an individual
 * pointer would be measuring which finger the framework happened to number
 * first. The centroid of whatever the gesture owns is stable across both.
 */
class TwoFingerDoubleTap(
    /** Longer than this and it is a hold, not a tap. */
    private val maxTapNanos: Long = MAX_TAP_NANOS,
    /** Travel further than this and it is a pinch or a pan, not a tap. */
    private val maxTravelPx: Float = MAX_TRAVEL_PX,
    /** Between the first lift and the second landing. */
    private val maxGapNanos: Long = MAX_GAP_NANOS,
    /** How far the second tap may be from the first. */
    private val maxSeparationPx: Float = MAX_SEPARATION_PX,
) {

    private var open = false
    private var beginNanos = 0L
    private var beginCount = 0
    private var beginX = 0f
    private var beginY = 0f
    private var travelSq = 0f

    private var haveFirst = false
    private var firstEndNanos = 0L
    private var firstX = 0f
    private var firstY = 0f

    /**
     * A gesture opened, with [pointerCount] fingers on the glass at
     * ([centroidX], [centroidY]).
     */
    fun begin(timeNanos: Long, pointerCount: Int, centroidX: Float, centroidY: Float) {
        open = true
        beginNanos = timeNanos
        beginCount = pointerCount
        beginX = centroidX
        beginY = centroidY
        travelSq = 0f
    }

    /**
     * The gesture's pointers were reported again.
     *
     * Travel is measured from where the gesture *started*, not summed along the
     * path. A pinch whose centroid barely moves is still not a tap, but it is
     * the gesture's duration and its fingers' spread that say so, and this is
     * the cheap half: what disqualifies a tap here is having gone somewhere.
     * Path length would also disqualify a perfectly still finger with a jittery
     * digitizer under it, which is the common case rather than the adversarial
     * one.
     *
     * **Reports with a different [pointerCount] are ignored, and that is not a
     * refinement — without it this class never fires at all.** The router
     * reports the gesture's pointers again when one of them *lifts*, and the
     * centroid of one finger is half a hand-span from the centroid of two: a
     * pair of fingers 240 px apart moves its centroid 120 px at the instant the
     * first one leaves the glass, which is five times the travel bound, on
     * every tap, without anything having moved. A centroid over a changing set
     * of pointers is not a position, and comparing two of them is not a
     * distance. Found on the tablet, where the gesture simply did nothing;
     * every unit test passed, because a test that models `move` as motion never
     * changes the count.
     */
    fun move(pointerCount: Int, centroidX: Float, centroidY: Float) {
        if (!open || pointerCount != beginCount) return
        val dx = centroidX - beginX
        val dy = centroidY - beginY
        val d = dx * dx + dy * dy
        if (d > travelSq) travelSq = d
    }

    /**
     * The gesture ended. Returns true if this completed a double tap, and
     * forgets everything when it does.
     *
     * The reset on a hit is what stops a triple tap firing twice: the third tap
     * begins a new pair rather than pairing with the second. Anything else
     * makes a jittery double tap — three contacts where the user meant two —
     * fit the canvas twice, and the second one lands after the user has already
     * started drawing again.
     */
    fun end(timeNanos: Long): Boolean {
        if (!open) return false
        open = false
        val wasTap = timeNanos - beginNanos <= maxTapNanos &&
            travelSq <= maxTravelPx * maxTravelPx
        if (!wasTap) {
            haveFirst = false
            return false
        }
        val paired = haveFirst &&
            beginNanos - firstEndNanos <= maxGapNanos &&
            near(beginX, beginY, firstX, firstY)
        if (paired) {
            haveFirst = false
            return true
        }
        haveFirst = true
        firstEndNanos = timeNanos
        firstX = beginX
        firstY = beginY
        return false
    }

    /**
     * The gesture was cancelled, or the pen took over.
     *
     * Drops the pending first tap as well as the open gesture. A cancelled
     * gesture is one the framework or the exclusivity rules took away, and
     * counting the half of a double tap that was interrupted by a palm would
     * fit the canvas in the middle of a stroke.
     */
    fun cancel() {
        open = false
        haveFirst = false
    }

    private fun near(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy <= maxSeparationPx * maxSeparationPx
    }

    companion object {
        /** 250 ms. Android's own `ViewConfiguration` tap timeout is 180. */
        const val MAX_TAP_NANOS = 250_000_000L

        /**
         * 24 view px. `ViewConfiguration`'s touch slop is 8 dp — about 24 px at
         * this panel's density — and this is a two-finger centroid, which is
         * steadier than either finger.
         */
        const val MAX_TRAVEL_PX = 24f

        /** 300 ms between the taps, matching the platform's double-tap timeout. */
        const val MAX_GAP_NANOS = 300_000_000L

        /**
         * 120 view px between the two taps. Wider than the travel bound on
         * purpose: a user repositions their hand between taps far more than
         * either tap wobbles, and being strict here reads as the gesture simply
         * not working.
         */
        const val MAX_SEPARATION_PX = 120f
    }
}
