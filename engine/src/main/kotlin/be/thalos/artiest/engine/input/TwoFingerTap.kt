package be.thalos.artiest.engine.input

/**
 * Two fingers down and straight back up, in one place — the gesture that takes
 * the last stroke back.
 *
 * It is the one shortcut almost every drawing app agrees on, and the reason is
 * muscle memory rather than discoverability: a line goes wrong, and the hand
 * that is already resting on the glass taps twice-fingered without the eye ever
 * leaving the paper. An undo you have to look at a toolbar for is an undo that
 * breaks the drawing.
 *
 * **Why two fingers and not one.** A single-finger tap never reaches the app at
 * all: `StrokeExclusivity` drops lone fingers entirely, and that rule is what
 * keeps a palm landing before the pen from locking the pen out. Reopening it
 * for a shortcut would trade the phase's central input property for a
 * convenience. Two fingers is what already opens a gesture, so this costs
 * nothing and breaks nothing — it watches gestures that were going to happen
 * anyway and notices when one of them did not move.
 *
 * ## What happened to the double tap
 *
 * This class replaces `TwoFingerDoubleTap`, which fitted the canvas to the
 * view, and the two cannot both exist: a double tap *starts* with a tap, so
 * every fit would undo a stroke on the way in. The choice between them is not
 * close.
 *
 * - Undoing is the thing a hand does mid-drawing, several times a minute.
 *   Fitting the canvas is a thing done between drawings, and it has a button.
 * - **Taps come in runs.** Three strokes wrong is three taps, as fast as the
 *   hand can make them, and that is precisely the input a double-tap policy
 *   reads as "fit the canvas". Any scheme that keeps both — waiting out the
 *   pairing window before undoing, say — breaks exactly the case the gesture
 *   exists for.
 *
 * ## It is a recogniser, not a policy
 *
 * It says "that was a tap" and the caller decides that this means undo. Pure
 * float math, no allocation, and no clock of its own — every time comes in as
 * a parameter, which is what makes the whole of it testable without a device
 * and without a sleep.
 *
 * **The centroid, not a pointer.** Fingers land and lift a few milliseconds
 * apart and their ids shuffle, so anything tracking an individual pointer would
 * be measuring which finger the framework happened to number first.
 */
class TwoFingerTap(
    /** Longer than this and it is a hold, not a tap. */
    private val maxTapNanos: Long = MAX_TAP_NANOS,
    /** Travel further than this and it is a pinch or a pan, not a tap. */
    private val maxTravelPx: Float = MAX_TRAVEL_PX,
) {

    private var open = false
    private var beginNanos = 0L
    private var beginCount = 0
    private var beginX = 0f
    private var beginY = 0f
    private var travelSq = 0f
    private var joined = false

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
        joined = false
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
     * **Reports with a different [pointerCount] are not motion, and that is not
     * a refinement — without it this never fires at all.** The router reports
     * the gesture's pointers again when one of them *lifts*, and the centroid
     * of one finger is half a hand-span from the centroid of two: a pair 240 px
     * apart moves its centroid 120 px at the instant the first one leaves the
     * glass, which is five times the travel bound, on every tap, without
     * anything having moved. A centroid over a changing set of pointers is not
     * a position, and comparing two of them is not a distance. Found on the
     * tablet, where the gesture simply did nothing; every unit test passed,
     * because a test that models `move` as motion never changes the count.
     *
     * A count that *grows* is different from one that shrinks, and it is the
     * one thing here that is a judgement rather than arithmetic: a third finger
     * joining means this was never a two-finger tap, so it is disqualified
     * outright. That keeps three fingers free to mean something else later
     * without a build where they quietly meant undo.
     */
    fun move(pointerCount: Int, centroidX: Float, centroidY: Float) {
        if (!open) return
        if (pointerCount > beginCount) {
            joined = true
            return
        }
        if (pointerCount != beginCount) return
        val dx = centroidX - beginX
        val dy = centroidY - beginY
        val d = dx * dx + dy * dy
        if (d > travelSq) travelSq = d
    }

    /**
     * The gesture ended. True when what just happened was a tap.
     *
     * [FINGERS] exactly: the machine promotes a gesture at the second finger,
     * so this is all but always two, and a report that arrived with fewer —
     * a pointer missing from the event that opened the gesture — is a gesture
     * this cannot speak for.
     */
    fun end(timeNanos: Long): Boolean {
        if (!open) return false
        open = false
        return !joined &&
            beginCount == FINGERS &&
            timeNanos - beginNanos <= maxTapNanos &&
            travelSq <= maxTravelPx * maxTravelPx
    }

    /**
     * The gesture was cancelled, or the pen took over.
     *
     * A cancelled gesture is one the framework or the exclusivity rules took
     * away — a palm, an `ACTION_CANCEL`, the pen arriving — and undoing a
     * stroke because a palm brushed the glass mid-drawing is the worst thing
     * this gesture could do.
     */
    fun cancel() {
        open = false
    }

    companion object {
        /** How many fingers a tap is made of. */
        const val FINGERS = 2

        /** 250 ms. Android's own `ViewConfiguration` tap timeout is 180. */
        const val MAX_TAP_NANOS = 250_000_000L

        /**
         * 24 view px. `ViewConfiguration`'s touch slop is 8 dp — about 24 px at
         * this panel's density — and this is a two-finger centroid, which is
         * steadier than either finger.
         */
        const val MAX_TRAVEL_PX = 24f
    }
}
