package be.thalos.artiest.engine.xform

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Two fingers into a `CanvasTransform`. All of the gesture arithmetic and none
 * of the plumbing, so it can be driven by a test instead of by a hand.
 *
 * **Everything is computed from the anchor, never accumulated.** Each update
 * takes the pointer positions the gesture was anchored at, the positions now,
 * and the transform as of the anchor, and produces the answer in one step. The
 * obvious alternative — apply the delta since the last update to the current
 * transform — drifts: `scale` is clamped, rotation is wrapped, and every
 * update's rounding is input to the next, so a slow pinch held at the 8.0
 * ceiling and released does not come back to where it started. Anchoring makes
 * the transform a pure function of where the fingers are, and the only state
 * that survives an update is the anchor itself.
 *
 * **Re-anchoring is what makes a finger lifting survivable.** A gesture can
 * gain or lose a pointer without ending — a hand shifting grip does it
 * constantly — and the pointer set changing means the anchor no longer
 * describes the same two fingers. Re-anchoring on the new set, against the
 * transform as it stands, means the canvas stays exactly where it is instead of
 * jumping by whatever the new pair's spread happens to be.
 *
 * Not thread-safe. One instance on the UI thread, driven from
 * `InkInputSink.onGesturePointers`.
 */
class GestureSolver(
    /**
     * How far the fingers must twist before rotation starts, in radians.
     *
     * A pinch is never a pure pinch: the fingers rotate a few degrees because
     * hands are hinged, and without a dead zone every zoom tilts the canvas a
     * little. The tilt is small, nobody asks for it, and getting back to level
     * is fiddly — which makes it exactly the kind of thing that reads as the app
     * being loose rather than as a gesture being obeyed.
     *
     * It is a trade, not a free win, and the cost is exact: while the twist is
     * inside the dead zone the canvas does **not** follow the fingers exactly,
     * because two points define a similarity and one of its four degrees of
     * freedom is being held at zero. The error is bounded by the dead zone —
     * up to 7 degrees of rotation the canvas declined to make — and it is
     * invisible without a reference, where a canvas that tilts by itself is
     * not. Past the dead zone the tracking is exact again, offset by the
     * threshold; see [rotationOffsetRad].
     */
    val rotationDeadZoneRad: Float = DEFAULT_ROTATION_DEAD_ZONE_RAD,
    /**
     * The closest the two fingers may be, in view pixels, before their spread
     * and angle stop meaning anything. Below it the update is skipped.
     */
    val minSpanView: Float = DEFAULT_MIN_SPAN_VIEW,
) {

    init {
        require(rotationDeadZoneRad >= 0f && rotationDeadZoneRad.isFinite()) {
            "rotationDeadZoneRad was $rotationDeadZoneRad"
        }
        require(minSpanView > 0f && minSpanView.isFinite()) { "minSpanView was $minSpanView" }
    }

    private var anchorTransform: CanvasTransform = CanvasTransform.IDENTITY
    private var anchorId0 = -1
    private var anchorId1 = -1
    private var ax0 = 0f
    private var ay0 = 0f
    private var ax1 = 0f
    private var ay1 = 0f
    private var anchored = false

    /**
     * The dead zone, once broken, becomes a fixed offset rather than a fixed
     * suppression.
     *
     * Subtracting it from every later angle keeps the canvas continuous at the
     * moment rotation starts — no jump of `rotationDeadZoneRad` — and leaves
     * the user able to rotate back through level and past it, which a
     * suppress-below-threshold rule does not.
     */
    private var rotationOffsetRad = 0f
    private var rotating = false

    /** The transform the last [update] produced. */
    var current: CanvasTransform = CanvasTransform.IDENTITY
        private set

    /** True between [begin] and [end]. */
    var active: Boolean = false
        private set

    /** Whether the twist has passed [rotationDeadZoneRad] this gesture. */
    val isRotating: Boolean get() = rotating

    /** Open a gesture against the transform it starts from. */
    fun begin(transform: CanvasTransform) {
        anchorTransform = transform
        current = transform
        anchored = false
        rotating = false
        rotationOffsetRad = 0f
        active = true
    }

    /** Close it. The transform stays where the last [update] left it. */
    fun end() {
        active = false
        anchored = false
    }

    /**
     * Fold one frame of pointer positions into the transform.
     *
     * Returns [current] unchanged whenever it cannot do better than that:
     * fewer than two pointers, fingers too close together to have a spread, or
     * the first update after a re-anchor. Holding is always right — the canvas
     * is exactly where the user last put it — and it is what keeps a lifted
     * finger from being a jump.
     *
     * Only the first two pointers are read. A gesture takes two fingers by
     * rule (`StrokeExclusivity`), and a third arriving is a hand resting rather
     * than a request; folding it into a centroid would move the canvas because
     * someone put a knuckle down.
     */
    fun update(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int): CanvasTransform {
        if (!active || count < 2) return current

        val id0 = ids[0]
        val id1 = ids[1]
        if (!anchored || id0 != anchorId0 || id1 != anchorId1) {
            reanchor(id0, id1, xs[0], ys[0], xs[1], ys[1])
            return current
        }

        val adx = ax1 - ax0
        val ady = ay1 - ay0
        val bdx = xs[1] - xs[0]
        val bdy = ys[1] - ys[0]
        val aLen = sqrt(adx * adx + ady * ady)
        val bLen = sqrt(bdx * bdx + bdy * bdy)
        if (aLen < minSpanView || bLen < minSpanView) return current

        val factor = bLen / aLen
        if (!factor.isFinite() || factor <= 0f) return current

        val raw = atan2(adx * bdy - ady * bdx, adx * bdx + ady * bdy)
        val turn = if (rotating) {
            raw - rotationOffsetRad
        } else if (abs(raw) >= rotationDeadZoneRad) {
            rotating = true
            rotationOffsetRad = if (raw > 0f) rotationDeadZoneRad else -rotationDeadZoneRad
            raw - rotationOffsetRad
        } else {
            0f
        }

        val acx = (ax0 + ax1) * 0.5f
        val acy = (ay0 + ay1) * 0.5f
        val bcx = (xs[0] + xs[1]) * 0.5f
        val bcy = (ys[0] + ys[1]) * 0.5f

        // Scale and rotate about the anchor centroid, which leaves that view
        // point exactly where it was, then translate it onto the current
        // centroid. The composition is what makes the canvas stay under the
        // fingers rather than merely near them.
        current = anchorTransform
            .zoomedAbout(acx, acy, factor)
            .rotatedAbout(acx, acy, turn)
            .pannedByView(bcx - acx, bcy - acy)
        return current
    }

    /**
     * Adopt a new pointer pair without moving the canvas.
     *
     * The anchor transform becomes whatever is on screen right now, so the next
     * update starts from a zero delta. Rotation stays broken if it was already
     * broken, and the offset is recomputed as zero against the new pair —
     * re-arming the dead zone mid-gesture would freeze rotation for another
     * seven degrees every time a finger slipped.
     */
    private fun reanchor(id0: Int, id1: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        anchorTransform = current
        anchorId0 = id0
        anchorId1 = id1
        ax0 = x0
        ay0 = y0
        ax1 = x1
        ay1 = y1
        anchored = true
        rotationOffsetRad = 0f
    }

    override fun toString(): String =
        "GestureSolver(active=$active, anchored=$anchored, rotating=$rotating)"

    companion object {

        /**
         * 7 degrees. Small enough that a deliberate twist starts almost at
         * once, large enough to swallow the incidental rotation of a two-finger
         * pinch — measured on paper, a pinch from 40 mm to 80 mm with a
         * relaxed hand twists 3 to 5 degrees.
         */
        const val DEFAULT_ROTATION_DEAD_ZONE_RAD: Float = 0.122173f

        /**
         * 32 view pixels between the fingers.
         *
         * Below that the spread ratio and the angle are dominated by touch
         * noise: two contacts 10 px apart that each wobble by 2 px report a 20%
         * scale change and a 20-degree twist while nothing moved. 32 px is
         * about 4 mm on this panel, which is closer than two fingertips get.
         */
        const val DEFAULT_MIN_SPAN_VIEW: Float = 32f
    }
}
