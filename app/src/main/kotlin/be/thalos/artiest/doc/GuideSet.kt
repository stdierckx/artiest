package be.thalos.artiest.doc

import android.graphics.Path
import android.graphics.RectF
import be.thalos.artiest.engine.guide.Guide
import be.thalos.artiest.engine.guide.NearestGuide
import be.thalos.artiest.engine.guide.Snap

/**
 * The guides on this drawing: what they are, which are on, and how hard they
 * pull.
 *
 * `docs/inker-plan.md`'s Ik13, and `docs/guides-plan.md`'s item 7 — the
 * framework that items 8 to 22 all stand on and none of Tier 0 needs. It is
 * deliberately small, because the expensive parts of a guide subsystem are the
 * three things it does *not* contain: the arithmetic is a [Guide] in `:engine`,
 * the drawing is `GuideOverlay`, and the dragging is the chrome's.
 *
 * ## One snap, published across a thread
 *
 * A hand edits this on the UI thread; a stroke reads it on the render thread at
 * pen-down. The whole of the thread safety is [snap]: a `@Volatile` reference to
 * an **immutable** [Snap] over immutable guides, rebuilt on every change. A
 * stroke reads it once when the pen lands and keeps what it got, which is the
 * same discipline `InkSurfaceView.ink` follows and for the same reason — a
 * ruler dragged mid-stroke must not bend the line already on the page.
 *
 * ## Why one strength for the page
 *
 * Every live guide goes into one [NearestGuide] and one [Snap] carries the
 * strength and the reach for all of them. A per-guide strength is a real thing
 * to want eventually — a perspective grid you lean on and a ruler you obey, on
 * one page — and it is not here because it would be a field nothing reads,
 * while the *set's* number is on a slider in Ik14. Nearest-projection is what
 * chooses between guides; see [NearestGuide] for where that rule stops being
 * enough and why that is Ik15's problem and not this class's.
 *
 * ## Not undoable, on purpose
 *
 * Moving a ruler is not a change to the drawing, and putting it in the undo
 * history would mean an undo after a misplaced guide throws away the stroke you
 * drew before it. Guides are furniture: they persist, they are not marks, and
 * the way back from a guide you did not want is to move it or switch it off.
 * `docs/panels-plan.md` settles the same question the same way for toolbars.
 */
class GuideSet {

    private val lines = ArrayList<Guideline>()

    /**
     * Bumped on every change, so a `LaunchedEffect` or an overlay can notice
     * one without comparing lists.
     */
    var revision: Int = 0
        private set

    /**
     * How far a live guide moves the point it is given, 0..1.
     *
     * One for the page. See the class note.
     */
    var strength: Float = DEFAULT_STRENGTH
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                changed()
            }
        }

    /**
     * How far from a guide the pull reaches, in document pixels, or 0 for
     * everywhere. See [Snap.reachDoc].
     */
    var reachDoc: Float = 0f
        set(value) {
            val v = if (value.isFinite() && value > 0f) value else 0f
            if (v != field) {
                field = v
                changed()
            }
        }

    /**
     * The pull every stroke is drawn against, or null when there is none.
     *
     * **Read once at pen-down and kept for the stroke.** Volatile because the
     * hand that drags a handle and the thread that lays dabs are not the same
     * one; immutable because that is what makes a single read enough.
     */
    @Volatile
    var snap: Snap? = null
        private set

    /**
     * The same pull as [snap], written down, or null when there is none.
     *
     * A stroke drawn against a guide has to **keep** which guide, or a rebuild
     * re-renders it off the ruler — see `StrokeRecord.guide`. This is what a
     * sheet interns, and it is published in the same assignment [snap] is so
     * that a stroke cannot get one of the two from before an edit and the other
     * from after it.
     */
    @Volatile
    var snapText: String? = null
        private set

    val size: Int get() = lines.size

    val isEmpty: Boolean get() = lines.isEmpty()

    /** True when at least one guide is on and describes a guide. */
    val anyLive: Boolean get() = snap != null

    operator fun get(i: Int): Guideline = lines[i]

    fun all(): List<Guideline> = lines.toList()

    fun byId(id: Long): Guideline? = lines.firstOrNull { it.id == id }

    // ---- editing -----------------------------------------------------------

    /** An id no guide in this set has. */
    fun nextId(): Long = ++lastId

    /**
     * Add [line], or replace the one with its id.
     *
     * One method for both because a drag *is* a replace — the handle moves and
     * a new immutable [Guideline] with the same id takes its place — and having
     * two would mean every caller deciding which, on information the set
     * already has.
     */
    fun put(line: Guideline) {
        val at = lines.indexOfFirst { it.id == line.id }
        if (at < 0) lines += line else lines[at] = line
        if (line.id > lastId) lastId = line.id
        changed()
    }

    fun remove(id: Long): Boolean {
        val removed = lines.removeAll { it.id == id }
        if (removed) changed()
        return removed
    }

    /** Switch one on or off. No-op for an id that is not here. */
    fun setOn(id: Long, on: Boolean) {
        val at = lines.indexOfFirst { it.id == id }
        if (at < 0 || lines[at].on == on) return
        lines[at] = lines[at].with(on)
        changed()
    }

    fun clear() {
        if (lines.isEmpty()) return
        lines.clear()
        changed()
    }

    /**
     * Replace everything, which is what opening a drawing does.
     *
     * The ids come from the file, so [lastId] is taken from the highest one
     * rather than reset: a guide added after a load must not collide with one
     * that was already there.
     */
    fun load(from: List<Guideline>) {
        lines.clear()
        lines += from
        lastId = from.maxOfOrNull { it.id } ?: 0L
        changed()
    }

    // ---- what the hand and the overlay ask ---------------------------------

    /**
     * The guide whose body is within [slopDoc] of ([xDoc], [yDoc]), or null.
     *
     * Nearest wins. Off guides are included: a guide you switched off is still
     * a thing on the page you can pick up and move, and one you could not grab
     * until you had switched it back on would be a guide that hides from you.
     */
    fun lineNear(xDoc: Float, yDoc: Float, slopDoc: Float): Guideline? {
        var best = slopDoc
        var found: Guideline? = null
        for (line in lines) {
            val d = line.distanceTo(xDoc, yDoc, scratch)
            if (d <= best) {
                best = d
                found = line
            }
        }
        return found
    }

    /**
     * The handle within [slopDoc] of ([xDoc], [yDoc]), as `guide to index`, or
     * null.
     *
     * Searched **before** [lineNear] by every caller, because a handle sits on
     * its own line and a hit test that found the body first could never grab an
     * end.
     */
    fun handleNear(xDoc: Float, yDoc: Float, slopDoc: Float): Pair<Guideline, Int>? {
        var best = slopDoc
        var found: Pair<Guideline, Int>? = null
        for (line in lines) {
            val i = line.handleNear(xDoc, yDoc, best)
            if (i == Guideline.NO_HANDLE) continue
            val d = kotlin.math.hypot(line.xAt(i) - xDoc, line.yAt(i) - yDoc)
            if (d <= best) {
                best = d
                found = line to i
            }
        }
        return found
    }

    /**
     * Every guide's line, in document space, into [out], clipped to [clip].
     *
     * [out] is reset first: the overlay owns one path for its life and this is
     * what refills it.
     */
    fun outline(out: Path, clip: RectF, page: RectF) {
        out.reset()
        for (line in lines) line.outline(out, clip, page)
    }

    /** The same, for the guides that are switched off. See `GuideOverlay`. */
    fun outline(out: Path, clip: RectF, page: RectF, on: Boolean) {
        out.reset()
        for (line in lines) if (line.on == on) line.outline(out, clip, page)
    }

    // ---- the one thing that has to happen on every change ------------------

    private fun changed() {
        revision++
        val live: List<Guide> = lines.mapNotNull { if (it.live) it.guide else null }
        val composed = NearestGuide.of(live)
        // The text first and the snap second, and both before anything else can
        // read either: a stroke that got the snap from after an edit and the
        // text from before it would be interned under a description of a ruler
        // it was not drawn against, which is worse than either half being stale.
        snapText = GuideText.encodeSnap(strength, reachDoc, lines)
        snap = if (composed == null || strength <= 0f) {
            null
        } else {
            Snap(composed, strength, reachDoc)
        }
    }

    private var lastId: Long = 0L

    /** [lineNear]'s projection scratch. UI thread only; never escapes. */
    private val scratch = FloatArray(2)

    override fun toString(): String = "GuideSet($size, live=$anyLive, rev=$revision)"

    companion object {

        /**
         * What a fresh guide pulls with.
         *
         * A hard 1: a ruler that is on is on, which is what `Snap.reachDoc`'s
         * own note says a ruler wants. Ik14's slider is for the guides that are
         * furniture rather than instruments.
         */
        const val DEFAULT_STRENGTH = 1f

        /**
         * How near the pen has to be to grab a guide or a handle, in **view**
         * pixels — the caller divides by the zoom.
         *
         * Twice `StrokePick`'s tap slop, because a guide is one hairline wide
         * and a stroke is as wide as its nib: there is nothing else to aim at.
         */
        const val GRAB_VIEW_PX = 22f
    }
}
