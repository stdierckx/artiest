package be.thalos.artiest.engine.guide

import kotlin.math.abs
import kotlin.math.hypot

/**
 * One, two or three vanishing points, and the ray the hand meant.
 *
 * `docs/guides-plan.md` items 16, 17 and 18, and the third of those is the one
 * that matters: *"With three points live, the app must decide which ray you
 * meant. **The part that decides whether perspective feels usable**, and the
 * part every program solves differently."*
 *
 * ## The ray is decided once, from where the hand actually went
 *
 * Every ray of every vanishing point passes through the pen-down point — that
 * is what a ray *is* — so "the nearest ray" is a tie at the moment the pen
 * lands and a coin-flip a millisecond later. `NearestGuide`'s own note says so.
 * The question can only be answered by the stroke's **direction**, and a
 * direction does not exist until the hand has moved.
 *
 * So: nothing snaps for the first [DECIDE_DOC] document pixels. Then the ray
 * whose line lies closest to the direction travelled is chosen, and it is kept
 * for the rest of the stroke. Deciding once and keeping it is the whole of what
 * makes this feel like a ruler rather than like a fight: a stroke that
 * re-decided per sample would swap rays in the middle and put a corner in the
 * line.
 *
 * ## What the first eight pixels cost
 *
 * They are drawn where the hand put them, and the ninth is on the ray. The
 * chosen ray passes through the pen-down point, so the step at the join is at
 * most [DECIDE_DOC] times the sine of the angle between the hand and the ray —
 * and that angle is the *smallest* of the candidates by construction, so with
 * three points 60° apart it is at most 30° and the step is at most four
 * pixels. A nib is wider than that.
 *
 * Eight and not two, because at 321.75 Hz two pixels of a slow, careful inking
 * stroke is three samples of hand tremor and the answer would be a coin flip
 * again. Eight and not thirty, because thirty pixels of unguided line at the
 * start of every stroke is a thing you would see.
 *
 * ## A point pushed far away becomes a parallel set, by itself
 *
 * `docs/guides-plan.md` item 20 is *"infinitise a vanishing point, and its rays
 * become parallel"*, and there is no code here for it: the rays from any two
 * points on a page to a vanishing point a hundred pages away differ by an angle
 * too small to see, so the arithmetic degenerates to parallel on its own. What
 * item 20 is really asking for is a vanishing point you can still *reach*, and
 * that is `ParallelGuide`, which already exists — an angle with no point at all.
 */
class PerspectiveGuide(
    points: FloatArray,
    /** How many vanishing points are in [points]; the array may be longer. */
    val count: Int,
    /**
     * Which ray to use whatever the hand does, or [NO_LOCK] to choose.
     *
     * `docs/guides-plan.md` item 18's *"with a manual override"*. It is not a
     * fallback for a rule that does not work — the rule works — it is for the
     * run of twenty lines that all go to the left-hand point, where being asked
     * twenty times is twenty chances to be told no.
     */
    val lockedRay: Int = NO_LOCK,
) : Guide {

    /** x, y pairs. Copied in; the caller reuses. */
    private val points: FloatArray = points.copyOf(count * 2)

    init {
        require(count in 1..MAX_POINTS) { "$count vanishing points" }
        require(points.size >= count * 2) { "${points.size} floats for $count points" }
        for (i in 0 until count * 2) {
            require(this.points[i].isFinite()) { "a vanishing point was ${this.points[i]}" }
        }
    }

    fun xAt(i: Int): Float = points[i * 2]

    fun yAt(i: Int): Float = points[i * 2 + 1]

    /**
     * Where the stroke started, or NaN before it has, and which ray it is on.
     *
     * Three fields of per-stroke state and no lock, which is safe for the same
     * reason [ParallelGuide]'s two are: the live guide is built on the UI
     * thread and written by the samples of one stroke on that thread, and a
     * rebuild uses a **different** instance — `VectorSheet.snapAt` decodes its
     * own from the sheet's guide table, on the render thread. The two never
     * meet, and that is not a happy accident: it is the same separation that
     * makes a stroke re-render against the ruler it was drawn on rather than
     * the one on the page now.
     */
    private var originX: Float = Float.NaN
    private var originY: Float = Float.NaN

    /** The ray this stroke is on, or [NO_RAY] before it has been decided. */
    private var chosen: Int = NO_RAY

    override fun begin(xDoc: Float, yDoc: Float) {
        originX = xDoc
        originY = yDoc
        // A locked set needs no direction, so it snaps from the very first
        // sample and there is no unguided head at all. That is the other half
        // of what the override buys.
        chosen = if (lockedRay in 0 until count) lockedRay else NO_RAY
    }

    override fun advance(xDoc: Float, yDoc: Float) {
        if (chosen != NO_RAY) return
        val ox = originX
        val oy = originY
        if (ox.isNaN()) return
        val tx = xDoc - ox
        val ty = yDoc - oy
        val travel = hypot(tx, ty)
        if (travel < DECIDE_DOC) return
        chosen = nearestRayTo(tx / travel, ty / travel)
    }

    /**
     * Which vanishing point's ray from the origin lies closest to the unit
     * direction ([ux], [uy]).
     *
     * The score is the **absolute** cosine, because a ray is a line and not an
     * arrow: drawing away from a vanishing point is as much along its ray as
     * drawing toward it, and a signed test would send every receding line to
     * the wrong point — which is most of the lines in a perspective drawing.
     *
     * ## The tie, which is not exotic — it is the textbook case
     *
     * Two vanishing points placed symmetrically on a horizon, and a stroke
     * drawn horizontally from a point midway between them, score **identically**
     * against both. That is not a corner case dreamt up for a test: it is the
     * canonical two-point setup and the most ordinary stroke in it.
     *
     * So a tie is broken by the **signed** cosine: of two rays equally aligned
     * with the hand, the one the hand is heading *toward* wins. That is what a
     * person means by "this line goes to the right-hand point", and it is
     * stable — the same stroke breaks the tie the same way every time, which a
     * first-wins rule would also do but in the direction nobody expects.
     *
     * A vanishing point the stroke started exactly on has no direction from
     * here and is skipped. If they all are — a pen-down on the only vanishing
     * point there is — nothing is chosen and nothing snaps, which is the right
     * answer to a question with no answer.
     */
    private fun nearestRayTo(ux: Float, uy: Float): Int {
        var best = -1f
        var bestSigned = 0f
        var found = NO_RAY
        for (i in 0 until count) {
            val rx = points[i * 2] - originX
            val ry = points[i * 2 + 1] - originY
            val len = hypot(rx, ry)
            if (len < ON_POINT) continue
            val signed = (ux * rx + uy * ry) / len
            val score = abs(signed)
            val take = when {
                score > best + TIE -> true
                score < best - TIE -> false
                else -> signed > bestSigned
            }
            if (take) {
                best = score
                bestSigned = signed
                found = i
            }
        }
        return found
    }

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        val at = chosen
        if (at == NO_RAY) return false
        val ox = originX
        val oy = originY
        val rx = points[at * 2] - ox
        val ry = points[at * 2 + 1] - oy
        val len2 = rx * rx + ry * ry
        if (len2 < ON_POINT * ON_POINT) return false
        // Onto the infinite line through the origin and the vanishing point.
        // Infinite rather than the segment between them, because a ray carries
        // on past the point it converges to and a line stopping dead at a
        // vanishing point would be a line with a full stop in the middle of the
        // page.
        val t = ((xDoc - ox) * rx + (yDoc - oy) * ry) / len2
        out[0] = ox + rx * t
        out[1] = oy + ry * t
        return true
    }

    override fun toString(): String =
        "PerspectiveGuide($count points, locked $lockedRay)"

    companion object {

        /** [lockedRay]'s value when the guide chooses for itself. */
        const val NO_LOCK: Int = -1

        /** [chosen]'s value before the stroke has said which way it is going. */
        private const val NO_RAY = -1

        /**
         * How far the hand has to travel before the ray is chosen, in document
         * pixels. See the class note for why it is this and not two or thirty.
         */
        const val DECIDE_DOC: Float = 8f

        /** Nearer than this to a vanishing point and there is no ray from here. */
        private const val ON_POINT = 1f / 16f

        /**
         * How close two rays' alignment has to be before the tie-break decides.
         *
         * A thousandth of a cosine, which at the angles this compares is about
         * a twentieth of a degree. Wide enough that a symmetric pair placed by
         * a finger counts as symmetric, narrow enough that it never overrides a
         * difference anybody drew on purpose.
         */
        private const val TIE = 1e-3f

        /**
         * Three, which is every perspective an artist draws in.
         *
         * Not a limit anybody has to work around: four-point and curvilinear
         * perspective are `docs/guides-plan.md` item 21, which scores 1 for use
         * and 4 for difficulty and is a different guide rather than a fourth
         * point on this one.
         */
        const val MAX_POINTS: Int = 3
    }
}
