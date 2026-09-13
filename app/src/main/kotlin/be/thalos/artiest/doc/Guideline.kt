package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.RectF
import be.thalos.artiest.engine.guide.Guide
import be.thalos.artiest.engine.guide.LineGuide
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What sort of guide a [Guideline] is.
 *
 * One entry today, and the enum exists anyway for the same reason
 * `ToolItem` does: the persisted [id] is not the Kotlin name, so a rename is a
 * refactor rather than a silent loss of somebody's perspective grid, and an
 * unknown id read off disk becomes a dropped guide rather than a crash.
 *
 * [points] is how many document-space points place one. It is the whole of what
 * makes this framework stretch to `docs/guides-plan.md`'s Tier 1 and Tier 2
 * without another format: a parallel set is two points, an ellipse is three, a
 * vanishing point is one, and a French curve is as many as it has. See
 * [Guideline.points].
 */
enum class GuideKind(val id: String, val points: Int, val label: String) {

    /**
     * An infinite straight line through two points.
     *
     * `docs/guides-plan.md` item 8, and the one guide Ik13 ships so that the
     * framework has something standing on it. Two points rather than a point
     * and an angle because that is what the hand edits: drag one end and the
     * ruler pivots about the other, which is how a real ruler is turned and
     * needs no arithmetic in the overlay.
     *
     * Infinite for snapping and for drawing both — see [Guideline.outline] —
     * because a stroke that ran off the end of a ruler and un-snapped would
     * have a kink in it exactly where the hand was going fastest.
     */
    RULER("ruler", 2, "Ruler"),
    ;

    companion object {
        private val BY_ID: Map<String, GuideKind> = entries.associateBy { it.id }

        fun byId(id: String): GuideKind? = BY_ID[id]
    }
}

/**
 * One guide on the page: what it is, where it is, and how hard it pulls.
 *
 * **Immutable, and every edit makes a new one.** A guide is read on the render
 * thread at pen-down and written on the UI thread by a hand dragging a handle,
 * and the whole of the thread safety is that neither of those can see half of
 * an edit. It also makes an undo step a reference rather than a copy, which is
 * the same bargain `StrokeRecord` takes.
 *
 * ## Points, and why geometry is a float array
 *
 * The obvious shape for this class is one subclass per kind with named fields —
 * a ruler with two endpoints, an ellipse with a centre and two radii. It is not
 * what is here, and the reason is the three things that have to be written
 * *once* rather than once per kind: the codec, the overlay's handles, and
 * dragging. All three want "the points of this guide" and none of them wants to
 * know what the points mean. A `when` over kinds in each of them is three
 * places to forget Ik15's vanishing point.
 *
 * What is kind-specific is exactly two methods — [buildGuide] and [outline] —
 * and those are the two that genuinely differ.
 */
class Guideline(
    /** Unique within a [GuideSet], and stable across an edit. */
    val id: Long,
    val kind: GuideKind,
    points: FloatArray,
    /**
     * Whether the ink is drawn against this one.
     *
     * Off is not the same as deleted, and that distinction is the point of the
     * field: a perspective grid you turn off to draw a face and on again for
     * the background is the normal way of working, and rebuilding it each time
     * is not.
     *
     * **How hard a live guide pulls is not here** — it is one number on
     * [GuideSet], for every guide on the page. A per-guide strength is a real
     * thing to want eventually (a perspective grid to lean on, a ruler to obey)
     * and it is not here because nothing today would read it, which is
     * `ToolItem`'s rule 1 applied to a field.
     */
    val on: Boolean = true,
) {

    /**
     * The document-space x, y pairs that place this guide. **Copied in**, and
     * never handed out — [xAt] and [yAt] read it.
     */
    private val points: FloatArray = points.copyOf(kind.points * 2)

    init {
        for (v in this.points) require(v.isFinite()) { "a guide point was $v" }
    }

    val pointCount: Int get() = kind.points

    fun xAt(i: Int): Float = points[i * 2]

    fun yAt(i: Int): Float = points[i * 2 + 1]

    /** A copy of the points, for the codec and for tests. */
    fun pointsCopy(): FloatArray = points.copyOf()

    /**
     * The engine's guide, or null when the points do not describe one.
     *
     * Built once, here, rather than per stroke: this object is immutable, the
     * arithmetic is trigonometric, and a stroke asks for it at pen-down when
     * there is a whole frame of slack — but a *set* of guides asks every one of
     * its members, and a page of forty would be forty `atan2` calls on the
     * frame the pen lands.
     *
     * Null is the honest answer for a ruler whose two ends have been dragged
     * onto each other. It is reachable with a real hand, it is not an error,
     * and the guide simply does not apply until the hand moves one of them.
     */
    val guide: Guide? = buildGuide()

    private fun buildGuide(): Guide? = when (kind) {
        GuideKind.RULER -> {
            val x0 = points[0]
            val y0 = points[1]
            val x1 = points[2]
            val y1 = points[3]
            if (hypot(x1 - x0, y1 - y0) < MIN_SPAN_DOC) null else LineGuide.through(x0, y0, x1, y1)
        }
    }

    /** True when this one is both on and describes a guide. */
    val live: Boolean get() = on && guide != null

    // ---- editing, all of which makes a new one -----------------------------

    /** The same guide, switched on or off. */
    fun with(on: Boolean): Guideline = if (on == this.on) this else Guideline(id, kind, points, on)

    /** The same guide with point [i] moved to ([xDoc], [yDoc]). */
    fun withPoint(i: Int, xDoc: Float, yDoc: Float): Guideline {
        if (i < 0 || i >= pointCount) return this
        val next = points.copyOf()
        next[i * 2] = xDoc
        next[i * 2 + 1] = yDoc
        return Guideline(id, kind, next, on)
    }

    /** The same guide, every point moved. */
    fun movedBy(dxDoc: Float, dyDoc: Float): Guideline {
        if (dxDoc == 0f && dyDoc == 0f) return this
        val next = points.copyOf()
        for (i in next.indices step 2) {
            next[i] += dxDoc
            next[i + 1] += dyDoc
        }
        return Guideline(id, kind, next, on)
    }

    // ---- what the overlay and the hand need --------------------------------

    /**
     * How far ([xDoc], [yDoc]) is from this guide, or [Float.MAX_VALUE] when it
     * does not apply there.
     *
     * Kind-free, because it is the projection question again: the distance to a
     * guide *is* the distance to where the guide would put you. That is the
     * second thing bought by [Guide] having one method — a hit test for every
     * guide there will ever be, written once.
     */
    fun distanceTo(xDoc: Float, yDoc: Float, scratch: FloatArray): Float {
        val g = guide ?: return Float.MAX_VALUE
        if (!g.project(xDoc, yDoc, scratch)) return Float.MAX_VALUE
        return hypot(scratch[0] - xDoc, scratch[1] - yDoc)
    }

    /**
     * Which handle is within [slopDoc] of ([xDoc], [yDoc]), or −1.
     *
     * Nearest wins, so two handles dragged on top of each other still give one
     * answer rather than whichever was written first.
     */
    fun handleNear(xDoc: Float, yDoc: Float, slopDoc: Float): Int {
        var best = slopDoc
        var found = NO_HANDLE
        for (i in 0 until pointCount) {
            val d = hypot(xAt(i) - xDoc, yAt(i) - yDoc)
            if (d <= best) {
                best = d
                found = i
            }
        }
        return found
    }

    /**
     * The line to draw for this guide, in **document** space, appended to
     * [out] and clipped to [clip].
     *
     * Document space and not view space, for `SelectionOverlay`'s reason: the
     * guide is a thing on the page, so it pans and zooms with the page, while
     * the stroke that draws it stays one screen pixel wide at every zoom. The
     * overlay transforms this and strokes the result.
     *
     * The clip is not an optimisation. A ruler is infinite, and an infinite
     * line has to be cut down to something a `Path` can hold before it is
     * transformed — a segment from −10⁹ to +10⁹ through a view matrix is where
     * the coordinates stop being representable and the line vanishes at high
     * zoom.
     */
    fun outline(out: Path, clip: RectF) {
        when (kind) {
            GuideKind.RULER -> rulerOutline(out, clip)
        }
    }

    /**
     * The infinite line through the two points, cut to the visible rectangle.
     *
     * Parametric against each of the four edges, keeping the span of `t` that
     * is inside all of them — the standard slab clip, written out rather than
     * pulled in, because the whole of it is nine lines and a `RectF` has no
     * intersect-a-line.
     */
    private fun rulerOutline(out: Path, clip: RectF) {
        val x0 = points[0]
        val y0 = points[1]
        val dx = points[2] - x0
        val dy = points[3] - y0
        if (hypot(dx, dy) < MIN_SPAN_DOC) return
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE
        // Each edge as p*t <= q. A zero p means the line is parallel to that
        // pair of edges: inside for every t, or outside for every t.
        val ps = floatArrayOf(-dx, dx, -dy, dy)
        val qs = floatArrayOf(x0 - clip.left, clip.right - x0, y0 - clip.top, clip.bottom - y0)
        for (i in 0..3) {
            val p = ps[i]
            val q = qs[i]
            if (abs(p) < PARALLEL) {
                if (q < 0f) return
                continue
            }
            val t = q / p
            if (p < 0f) {
                if (t > tMin) tMin = t
            } else {
                if (t < tMax) tMax = t
            }
        }
        if (tMin > tMax) return
        out.moveTo(x0 + dx * tMin, y0 + dy * tMin)
        out.lineTo(x0 + dx * tMax, y0 + dy * tMax)
    }

    override fun toString(): String = "Guideline($id ${kind.id} on=$on)"

    companion object {

        /** [handleNear]'s answer when nothing is near enough. */
        const val NO_HANDLE = -1

        /**
         * Two points closer together than this do not describe a direction.
         *
         * A sixteenth of a document pixel, which is `StrokeCodec`'s own
         * quantum: below it the two ends are the same point as far as anything
         * that stores a stroke is concerned, and `atan2` of the difference is
         * whatever the rounding left behind.
         */
        const val MIN_SPAN_DOC = 1f / 16f

        /** Below this a slab is parallel to the line and the clip is a test. */
        private const val PARALLEL = 1e-6f
    }
}
