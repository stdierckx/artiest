package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.RectF
import be.thalos.artiest.engine.guide.Guide
import be.thalos.artiest.engine.guide.CurveGuide
import be.thalos.artiest.engine.guide.EllipseGuide
import be.thalos.artiest.engine.guide.FisheyeGuide
import be.thalos.artiest.engine.guide.LineGuide
import be.thalos.artiest.engine.guide.ParallelGuide
import be.thalos.artiest.engine.guide.PerspectiveGuide
import kotlin.math.abs
import kotlin.math.atan2
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
/**
 * [GuideKind.ANY], as a top-level constant.
 *
 * An enum entry is constructed before its own companion object, so `CURVE`
 * cannot read `GuideKind.ANY` in its own argument list. The companion re-exposes
 * this one, because every *other* reader should see it as the enum's.
 */
private const val ANY_POINTS = 0

enum class GuideKind(
    val id: String,
    /**
     * How many document-space points place one, or [ANY] for a kind whose
     * point count is the shape itself.
     */
    val points: Int,
    val label: String,
    /**
     * Whether each point is a handle a finger can grab.
     *
     * False for the curve, and that is not a shortcut. A curve traced from a
     * stroke has hundreds of points; drawing a knob on each would bury the
     * curve under its own handles and make every one of them too small to hit.
     * A curve is dragged whole, which is what a French curve is for — you slide
     * it, you do not reshape it.
     */
    val handles: Boolean = true,
    /**
     * The fewest points a kind with [ANY] of them can be made of. Ignored by a
     * kind that names a fixed count, which is its own answer.
     */
    private val leastAny: Int = 2,
) {

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

    /**
     * An angle. Every stroke comes out parallel to it, through wherever the pen
     * landed.
     *
     * `docs/guides-plan.md` item 10, and the cheapest useful thing in Tier 1:
     * hatching and speed lines are *many lines at one angle in different
     * places*, which is a ruler you would otherwise drag between every stroke.
     *
     * Two points like a ruler, and they mean something different: they set the
     * **angle** and nothing else. Where they are on the page is only where the
     * handles are, which is why one is drawn and dragged exactly as a ruler's
     * are and the line through them is not what the ink follows. See
     * [outline], which draws the family rather than the pair.
     */
    PARALLEL("parallel", 2, "Parallel"),

    /**
     * An ellipse to draw around.
     *
     * `docs/guides-plan.md` item 13, and the entry that justifies Tier 1 to an
     * inker: a circle seen at an angle is an ellipse, freehand ellipses are the
     * thing hands are worst at, and the correction is exactly the one a guide
     * can make — the hand supplies the sweep and the guide supplies the shape.
     *
     * Three points: the centre, the end of one radius, and the end of the
     * other. The third is **kept perpendicular to the second** — see
     * [normalised] — because that is what the arithmetic means by two radii,
     * and a handle drawn where it is not is a handle that lies about what
     * dragging it will do.
     */
    ELLIPSE("ellipse", 3, "Ellipse"),

    /**
     * A shape you lay on the page and draw along: a French curve.
     *
     * `docs/guides-plan.md` items 12 and 15, which are the same item twice. The
     * interesting half is not projecting onto a polyline, it is **getting the
     * polyline**, and the honest answer is *draw one* — so this kind is made
     * from a stroke you have already drawn and picked. See `GuideAct.FromPicked`.
     *
     * [ANY] points, which is why that constant exists: a curve's point count is
     * its shape, not a property of its kind.
     */
    CURVE("curve", ANY_POINTS, "Curve", handles = false, leastAny = 2),

    /**
     * One, two or three vanishing points, and the rays that converge on them.
     *
     * `docs/guides-plan.md` items 16, 17 and 18 in one kind, because they are
     * one thing: a perspective grid *is* a set of vanishing points, and 1-, 2-
     * and 3-point perspective differ only in how many there are. [ANY] points,
     * capped at three by `PerspectiveGuide`.
     *
     * The **horizon is not stored**. It is the line through the first two
     * points, or a horizontal through the only one — so it moves when a point
     * is dragged, which is what a horizon does. A stored one would be a second
     * thing to keep in step with the first.
     *
     * Items 19 and 20 are *not* here and need no code: an isometric grid is
     * three [PARALLEL] sets at fixed angles, and a vanishing point pushed to
     * infinity is a [PARALLEL] set with no point at all. See
     * `PerspectiveGuide`.
     */
    PERSPECTIVE("perspective", ANY_POINTS, "Perspective", leastAny = 1),

    /**
     * Five-point curvilinear perspective: a fisheye.
     *
     * `docs/guides-plan.md` item 21. Two points and not five: the centre and
     * one of the four on the circle, which between them give the radius and the
     * turn — and the other three follow, because a fisheye's four are at the
     * compass positions of one circle by definition. Storing five would be
     * storing three numbers that can disagree with the other two.
     */
    FISHEYE("fisheye", 2, "Fisheye"),
    ;

    /**
     * The fewest points one of these can be made of.
     *
     * One for a perspective set, because one-point perspective is a perspective
     * and not a broken two-point one; two for everything else, because a guide
     * made of one point is a guide with no direction.
     */
    val least: Int get() = if (points == ANY) leastAny else points

    companion object {

        /**
         * A kind whose point count is its shape rather than a property of the
         * kind. One kind uses it, and the codec and the model both branch on it
         * rather than on the kind, so the second one costs nothing.
         */
        const val ANY: Int = ANY_POINTS

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
    /**
     * Which ray a perspective set uses whatever the hand does, or
     * [PerspectiveGuide.NO_LOCK] to let it choose. Meaningless for every other
     * kind.
     *
     * **Per-guide, where the strength deliberately is not**, and the difference
     * is the rule rather than an exception to it: nothing reads a per-guide
     * strength, and this is read on every sample of every stroke drawn against
     * the set. It is also *geometry* — it changes where the ink goes — so it
     * has to survive a save and be named by the record, which is why it is on
     * the guideline and in the row rather than a piece of panel state.
     */
    val lockedRay: Int = PerspectiveGuide.NO_LOCK,
) {

    /**
     * The document-space x, y pairs that place this guide. **Copied in**, and
     * never handed out — [xAt] and [yAt] read it.
     */
    private val points: FloatArray = normalised(
        kind,
        if (kind.points == GuideKind.ANY) points.copyOf() else points.copyOf(kind.points * 2),
    )

    init {
        require(points.size % 2 == 0 && points.size >= kind.least * 2) {
            "${points.size} floats is not a ${kind.id}"
        }
        for (v in this.points) require(v.isFinite()) { "a guide point was $v" }
    }

    val pointCount: Int
        get() = if (kind.points == GuideKind.ANY) points.size / 2 else kind.points

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

        GuideKind.PARALLEL -> {
            val dx = points[2] - points[0]
            val dy = points[3] - points[1]
            if (hypot(dx, dy) < MIN_SPAN_DOC) null else ParallelGuide(atan2(dy, dx))
        }

        GuideKind.CURVE -> {
            val n = pointCount
            if (n < 2) null else CurveGuide(points, minOf(n, CurveGuide.MAX_POINTS))
        }

        GuideKind.PERSPECTIVE -> {
            val n = minOf(pointCount, PerspectiveGuide.MAX_POINTS)
            if (n < 1) null else PerspectiveGuide(points, n, lockedRay)
        }

        GuideKind.FISHEYE -> {
            val cx = points[0]
            val cy = points[1]
            val rx = points[2] - cx
            val ry = points[3] - cy
            val r = hypot(rx, ry)
            if (r < MIN_SPAN_DOC) null else FisheyeGuide(cx, cy, r, atan2(ry, rx))
        }

        GuideKind.ELLIPSE -> {
            val cx = points[0]
            val cy = points[1]
            val ax = points[2] - cx
            val ay = points[3] - cy
            val a = hypot(ax, ay)
            val b = hypot(points[4] - cx, points[5] - cy)
            if (a < MIN_SPAN_DOC || b < MIN_SPAN_DOC) null else {
                EllipseGuide(cx, cy, a, b, atan2(ay, ax))
            }
        }
    }

    /** True when this one is both on and describes a guide. */
    val live: Boolean get() = on && guide != null

    // ---- editing, all of which makes a new one -----------------------------

    /** The same guide, switched on or off, or locked to another ray. */
    fun with(
        on: Boolean = this.on,
        lockedRay: Int = this.lockedRay,
    ): Guideline =
        if (on == this.on && lockedRay == this.lockedRay) this
        else Guideline(id, kind, points, on, lockedRay)

    /**
     * The same perspective set with one more vanishing point, or back to one.
     *
     * A cycle — one, two, three, one — for [nextLock]'s reason: there are three
     * states and a menu for three states is a menu nobody opens. Answers itself
     * unchanged for every other kind.
     *
     * **Growing places the new point and shrinking forgets the old ones**, and
     * that is the trade a cycle makes. It is the right way round here: a
     * vanishing point takes one drag to place and the set takes one tap to get
     * back to, while the alternative — a plus and a minus — is two more
     * controls on a row that already carries four.
     *
     * The new point goes **above** the page, which is where the third one of a
     * three-point set belongs for the drawing everybody makes with it: a
     * building seen from the street, with its verticals converging upward.
     */
    fun nextPointCount(pageW: Float, pageH: Float): Guideline {
        if (kind != GuideKind.PERSPECTIVE) return this
        val now = pointCount
        val next = if (now >= PerspectiveGuide.MAX_POINTS) 1 else now + 1
        val out = FloatArray(next * 2)
        for (i in 0 until minOf(now, next)) {
            out[i * 2] = xAt(i)
            out[i * 2 + 1] = yAt(i)
        }
        if (next > now) {
            // Above the page and centred between the two that are already
            // there, so a fresh third point is where a hand would have put it.
            val cx = if (now >= 2) (xAt(0) + xAt(1)) * 0.5f else pageW * 0.5f
            out[(next - 1) * 2] = cx
            out[(next - 1) * 2 + 1] = -pageH * 1.2f
        }
        // A lock that named a point which has just gone has to go with it.
        val lock = if (lockedRay < next) lockedRay else PerspectiveGuide.NO_LOCK
        return Guideline(id, kind, out, on, lock)
    }

    /**
     * The next lock in the cycle: choosing, then each point in turn, then
     * choosing again.
     *
     * A cycle and not a menu, because there are at most four states and a menu
     * for four states is a menu nobody opens. Answers itself unchanged for a
     * kind that has no rays.
     */
    fun nextLock(): Guideline {
        if (kind != GuideKind.PERSPECTIVE) return this
        val next = lockedRay + 1
        return with(lockedRay = if (next >= pointCount) PerspectiveGuide.NO_LOCK else next)
    }

    /** The same guide with point [i] moved to ([xDoc], [yDoc]). */
    fun withPoint(i: Int, xDoc: Float, yDoc: Float): Guideline {
        if (i < 0 || i >= pointCount) return this
        val next = points.copyOf()
        next[i * 2] = xDoc
        next[i * 2 + 1] = yDoc
        return Guideline(id, kind, next, on, lockedRay)
    }

    /** The same guide, every point moved. */
    fun movedBy(dxDoc: Float, dyDoc: Float): Guideline {
        if (dxDoc == 0f && dyDoc == 0f) return this
        val next = points.copyOf()
        for (i in next.indices step 2) {
            next[i] += dxDoc
            next[i + 1] += dyDoc
        }
        return Guideline(id, kind, next, on, lockedRay)
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
        if (!kind.handles) return NO_HANDLE
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
    fun outline(out: Path, clip: RectF, page: RectF) {
        when (kind) {
            GuideKind.RULER -> rulerOutline(out, clip)
            GuideKind.PARALLEL -> parallelOutline(out, clip, page)
            GuideKind.ELLIPSE -> ellipseOutline(out)
            GuideKind.CURVE -> curveOutline(out)
            GuideKind.PERSPECTIVE -> perspectiveOutline(out, clip, page)
            GuideKind.FISHEYE -> fisheyeOutline(out, clip)
        }
    }

    /**
     * A parallel set draws as **several** lines and not as the one through its
     * handles, and that is the whole of telling it apart from a ruler.
     *
     * A ruler is a line you draw along; this is an angle, and the line the ink
     * follows is wherever the pen lands. Drawing one line would say "draw
     * here", which is the opposite of what it does. Drawing a family says
     * "anywhere, at this angle", which is what it does.
     *
     * The family is spaced across the *clip*, so it fills whatever is on screen
     * at whatever zoom, and the lines are never anything a stroke snaps to —
     * they are a picture of an angle.
     */
    private fun parallelOutline(out: Path, clip: RectF, page: RectF) {
        val dx = points[2] - points[0]
        val dy = points[3] - points[1]
        val len = hypot(dx, dy)
        if (len < MIN_SPAN_DOC) return
        // Perpendicular, normalised: the axis the family is spaced along.
        val nx = -dy / len
        val ny = dx / len
        // **Spaced on the page and not on the screen.** The gap is a fraction
        // of the page's diagonal, so it is the same distance on the paper at
        // every zoom, and the phase is measured from the page's centre, so a
        // given line of the family is always in the same place on the drawing.
        val cx = page.centerX()
        val cy = page.centerY()
        val gap = hypot(page.width(), page.height()) / PARALLEL_LINES
        if (gap < MIN_SPAN_DOC) return
        // Which of them cross what is on screen: project the clip's four
        // corners onto the spacing axis and walk the range between.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0..3) {
            val px = (if (i == 1 || i == 2) clip.right else clip.left) - cx
            val py = (if (i >= 2) clip.bottom else clip.top) - cy
            val at = px * nx + py * ny
            lo = minOf(lo, at)
            hi = maxOf(hi, at)
        }
        var k = kotlin.math.floor(lo / gap).toInt()
        val last = kotlin.math.ceil(hi / gap).toInt()
        var drawn = 0
        while (k <= last && drawn < MAX_FAMILY) {
            val at = k * gap
            lineThrough(out, clip, cx + nx * at, cy + ny * at, dx, dy)
            k++
            drawn++
        }
    }

    /**
     * The horizon, and a fan of rays from each vanishing point.
     *
     * **The rays are aimed at the visible rectangle rather than spread at even
     * angles**, and that is the whole trick: a vanishing point is usually a
     * long way off the page, so a fan at even angles would put nearly all of it
     * off screen and the two that were left would look like a mistake. Aiming
     * each ray at a point spaced along the clip's own edge makes the fan fill
     * whatever is in front of you, at any zoom and wherever the point is.
     *
     * The horizon is derived and not stored: the line through the first two
     * points, or a horizontal through the only one. It moves when a point is
     * dragged, which is what a horizon does.
     */
    private fun perspectiveOutline(out: Path, clip: RectF, page: RectF) {
        val n = minOf(pointCount, PerspectiveGuide.MAX_POINTS)
        if (n < 1) return
        for (i in 0 until n) {
            val vx = points[i * 2]
            val vy = points[i * 2 + 1]
            for (k in 0 until RAYS) {
                // **Aimed at the page and not at the glass.** Each ray goes
                // through a fixed point on the paper's own edge, so the fan is
                // nailed to the drawing: zoom in and you see the ones that
                // cross the view, in the places they have always been.
                val edge = onPerimeter(page, (k + 0.5f) / RAYS)
                val dx = edge[0] - vx
                val dy = edge[1] - vy
                if (hypot(dx, dy) < MIN_SPAN_DOC) continue
                lineThrough(out, clip, vx, vy, dx, dy)
            }
        }
        // The horizon last, so it is drawn over its own rays rather than under
        // them -- it is the one line of the set an eye uses to place the
        // others.
        if (n >= 2) {
            val dx = points[2] - points[0]
            val dy = points[3] - points[1]
            if (hypot(dx, dy) >= MIN_SPAN_DOC) lineThrough(out, clip, points[0], points[1], dx, dy)
        } else {
            lineThrough(out, clip, points[0], points[1], 1f, 0f)
        }
    }

    /**
     * The fisheye: its circle, the spokes through the middle, and the two
     * families of arcs.
     *
     * Cut to [clip] only where something is infinite, which is the spokes. The
     * circle and the arcs are bounded by the field of view itself and are the
     * same shape on the page at every zoom — a fisheye is the one guide whose
     * furniture *is* its geometry.
     */
    private fun fisheyeOutline(out: Path, clip: RectF) {
        val cx = points[0]
        val cy = points[1]
        val ax = points[2] - cx
        val ay = points[3] - cy
        val r = hypot(ax, ay)
        if (r < MIN_SPAN_DOC) return
        val a = atan2(ay, ax)
        val ca = kotlin.math.cos(a)
        val sa = kotlin.math.sin(a)

        // The field of view.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        out.addOval(oval, Path.Direction.CW)

        // The spokes: straight lines through the middle, which is the family a
        // line going away from the viewer belongs to. Infinite, so they are cut
        // to what is on screen.
        for (k in 0 until FISHEYE_SPOKES) {
            val t = Math.PI * k / FISHEYE_SPOKES + a
            lineThrough(out, clip, cx, cy, kotlin.math.cos(t).toFloat(), kotlin.math.sin(t).toFloat())
        }

        // The two families of arcs. Each is the circle through a pair of
        // opposite points on the field of view and a third point taken along
        // the diameter between the other pair — which is exactly the family a
        // stroke started at that third point would be snapped to.
        for (family in 0 until 2) {
            val pax = if (family == 0) cx - ca * r else cx + sa * r
            val pay = if (family == 0) cy - sa * r else cy - ca * r
            val pbx = if (family == 0) cx + ca * r else cx - sa * r
            val pby = if (family == 0) cy + sa * r else cy + ca * r
            for (k in 1..FISHEYE_ARCS) {
                val at = (k.toFloat() / (FISHEYE_ARCS + 1) * 2f - 1f) * r * FISHEYE_SPREAD
                val tx = if (family == 0) cx - sa * at else cx + ca * at
                val ty = if (family == 0) cy + ca * at else cy + sa * at
                arcThrough(out, pax, pay, pbx, pby, tx, ty)
            }
        }
    }

    /**
     * The arc from ([ax], [ay]) to ([bx], [by]) that passes through
     * ([tx], [ty]), appended to [out].
     *
     * Three points on a line have no arc and get the chord, which is what they
     * are asking for — see `FisheyeGuide.circleThrough`.
     */
    private fun arcThrough(
        out: Path,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        tx: Float,
        ty: Float,
    ) {
        if (!FisheyeGuide.circleThrough(ax, ay, bx, by, tx, ty, circle)) {
            out.moveTo(ax, ay)
            out.lineTo(bx, by)
            return
        }
        val ox = circle[0]
        val oy = circle[1]
        val r = circle[2]
        oval.set(ox - r, oy - r, ox + r, oy + r)
        val from = degrees(ax - ox, ay - oy)
        val to = degrees(bx - ox, by - oy)
        val through = degrees(tx - ox, ty - oy)
        // Two ways round; take the one the third point is on. `arcTo` sweeps
        // anticlockwise for a negative sweep, which is how the other half is
        // asked for.
        val ccw = wrap(to - from)
        val sweep = if (wrap(through - from) < ccw) ccw else ccw - 360f
        out.arcTo(oval, from, sweep, true)
    }

    private fun degrees(x: Float, y: Float): Float =
        Math.toDegrees(atan2(y, x).toDouble()).toFloat()

    /** An angle difference as 0 up to 360. */
    private fun wrap(d: Float): Float {
        var v = d % 360f
        if (v < 0f) v += 360f
        return v
    }

    /** [fisheyeOutline]'s rectangle and circle. UI thread; never escape a draw. */
    private val oval = RectF()
    private val circle = FloatArray(3)

    /**
     * A point [t] of the way round [rect]'s edge, clockwise from its top-left.
     *
     * Reused scratch, because this is called once per ray per frame of a pan.
     */
    private fun onPerimeter(rect: RectF, t: Float): FloatArray {
        val clip = rect
        val w = clip.width()
        val h = clip.height()
        val half = w + h
        val d = (t.coerceIn(0f, 1f)) * half * 2f
        when {
            d < w -> perimeter.set(clip.left + d, clip.top)
            d < w + h -> perimeter.set(clip.right, clip.top + (d - w))
            d < w + h + w -> perimeter.set(clip.right - (d - w - h), clip.bottom)
            else -> perimeter.set(clip.left, clip.bottom - (d - w - h - w))
        }
        return perimeter
    }

    private fun FloatArray.set(x: Float, y: Float) {
        this[0] = x
        this[1] = y
    }

    /** [onPerimeter]'s own point. UI thread only, and it never escapes a draw. */
    private val perimeter = FloatArray(2)

    /**
     * The curve, as the polyline it is. No clip: it is bounded, and cutting it
     * would mean walking it twice to draw a line nobody can see the end of.
     */
    private fun curveOutline(out: Path) {
        val n = pointCount
        if (n < 2) return
        out.moveTo(points[0], points[1])
        for (i in 1 until n) out.lineTo(points[i * 2], points[i * 2 + 1])
    }

    private fun ellipseOutline(out: Path) {
        val cx = points[0]
        val cy = points[1]
        val ax = points[2] - cx
        val ay = points[3] - cy
        val a = hypot(ax, ay)
        val b = hypot(points[4] - cx, points[5] - cy)
        if (a < MIN_SPAN_DOC || b < MIN_SPAN_DOC) return
        val oval = RectF(cx - a, cy - b, cx + a, cy + b)
        val turn = android.graphics.Matrix()
        turn.setRotate(Math.toDegrees(atan2(ay, ax).toDouble()).toFloat(), cx, cy)
        val shape = Path()
        shape.addOval(oval, Path.Direction.CW)
        shape.transform(turn)
        out.addPath(shape)
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
        val dx = points[2] - points[0]
        val dy = points[3] - points[1]
        if (hypot(dx, dy) < MIN_SPAN_DOC) return
        lineThrough(out, clip, points[0], points[1], dx, dy)
    }

    /** The infinite line through ([x0], [y0]) along ([dx], [dy]), cut to [clip]. */
    private fun lineThrough(out: Path, clip: RectF, x0: Float, y0: Float, dx: Float, dy: Float) {
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

        /**
         * The points a kind insists on, whatever the hand did to them.
         *
         * One kind needs it. An ellipse is a centre and **two perpendicular**
         * radii, because that is what the arithmetic means by two radii — so
         * dragging the second radius handle sets its length and nothing else,
         * and the handle is put back where the shape says it is. Drawing it
         * where the finger left it would be a handle that lies about what
         * dragging it does.
         *
         * Applied in the constructor rather than at the drag, so that a file, a
         * transform and a hand all land in the same place.
         */
        private fun normalised(kind: GuideKind, points: FloatArray): FloatArray {
            if (kind != GuideKind.ELLIPSE) return points
            val cx = points[0]
            val cy = points[1]
            val ax = points[2] - cx
            val ay = points[3] - cy
            val a = hypot(ax, ay)
            if (a < MIN_SPAN_DOC) return points
            val b = hypot(points[4] - cx, points[5] - cy)
            // Perpendicular to the first radius, at the second one's length.
            points[4] = cx - ay / a * b
            points[5] = cy + ax / a * b
            return points
        }

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

        /**
         * How many lines a parallel set draws across whatever is on screen.
         *
         * Fourteen across the page's diagonal, which is a gap of about 280
         * document pixels on the tablet's 3300 by 2160 page.
         *
         * A *spacing* rather than a count, because the family is fixed to the
         * paper: at a fit-to-screen zoom you see about fourteen of them, and
         * zoomed in you see the two or three that cross the view — in the same
         * places on the drawing they were before you zoomed.
         */
        private const val PARALLEL_LINES = 14

        /**
         * Past this many lines of one family the loop has gone wrong.
         *
         * Reachable only by a page whose diagonal is a rounding error or a clip
         * the size of a country, neither of which the app can make — so it is a
         * guard against arithmetic rather than against a user.
         */
        private const val MAX_FAMILY = 256

        /**
         * How many rays a vanishing point draws across whatever is on screen.
         *
         * Twelve per point, aimed at twelve fixed places on the paper's edge.
         *
         * More than the seven this started with, and the reason is that the fan
         * is now nailed to the page: seven rays spread over a whole page is two
         * or three in view once you zoom in to ink one of them, and a grid you
         * cannot see is a grid you are not drawing against. Twelve is a
         * two-point grid of twenty-four lines and a horizon, which is about as
         * much furniture as a page will take.
         */
        private const val RAYS = 12

        /**
         * How many straight spokes a fisheye draws through its middle.
         *
         * Eight, which is one every 22.5 degrees — dense enough to read as
         * "everything radiates from here" and sparse enough that the two
         * families of arcs over them are still separate lines.
         */
        private const val FISHEYE_SPOKES = 8

        /** How many arcs each of a fisheye's two families draws. */
        private const val FISHEYE_ARCS = 5

        /**
         * How far out along the diameter the outermost arc of a family is
         * taken, as a fraction of the radius.
         *
         * Not all the way to 1: an arc through a point *on* the field of view
         * is the field of view, drawn a second time. Nine tenths keeps the
         * outermost one visibly inside it.
         */
        private const val FISHEYE_SPREAD = 0.9f
    }
}
