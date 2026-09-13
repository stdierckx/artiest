package be.thalos.artiest.engine.guide

/**
 * Several guides as one: whichever of them the point is nearest to wins.
 *
 * A page can have a horizon, two vanishing points and a ruler on it at once,
 * and `StrokeBuilder` takes **one** [Snap]. Something has to answer "which of
 * these did the hand mean", and this is the cheapest answer that is not wrong:
 * the guide whose projection moves the point least.
 *
 * ## Why nearest, and where it stops being enough
 *
 * Nearest-projection is right for the guides that are *objects on the page* —
 * two rulers crossing, a ruler and an ellipse — because the one you are next to
 * is the one you laid down there to draw against.
 *
 * It is **not** the whole answer for perspective, and `docs/guides-plan.md`
 * item 18 says why: with three vanishing points live, every ray of every point
 * passes through the pen sooner or later, so "nearest ray" is nearly always the
 * ray you are standing on rather than the one you are drawing along. The rule
 * there is *whichever ray is closest to the stroke's own direction*, which
 * needs something this interface deliberately does not have — where the stroke
 * came from. That is Ik15's, it belongs to the perspective guide itself, and
 * this class is what it will be one of rather than what it will replace.
 *
 * ## Threading and allocation
 *
 * Built on the UI thread when a guide changes and read on the render thread at
 * 321.75 Hz, so it is immutable and holds its own array. [project] allocates
 * nothing: the second point goes in a field-held scratch, which is safe for the
 * same reason [Snap.apply]'s scratch is — one thread, one stroke, and nothing
 * kept.
 */
class NearestGuide(guides: List<Guide>) : Guide {

    private val guides: Array<Guide> = guides.toTypedArray()

    /** Where a candidate lands while it is being compared. Never escapes. */
    private val trial = FloatArray(2)

    val size: Int get() = guides.size

    override fun project(xDoc: Float, yDoc: Float, out: FloatArray): Boolean {
        var best = Float.MAX_VALUE
        var found = false
        for (guide in guides) {
            if (!guide.project(xDoc, yDoc, trial)) continue
            val dx = trial[0] - xDoc
            val dy = trial[1] - yDoc
            val d2 = dx * dx + dy * dy
            // Strictly nearer, so that when two guides cross and the pen is on
            // the crossing the earlier one wins rather than the later. It is an
            // arbitrary tie-break, but an *unchanging* one: a stroke drawn
            // along the intersection must not flicker between the two.
            if (d2 < best) {
                best = d2
                out[0] = trial[0]
                out[1] = trial[1]
                found = true
            }
        }
        return found
    }

    override fun begin(xDoc: Float, yDoc: Float) {
        // Forwarded to every member, and not only to the one that turned out to
        // be nearest — because which one is nearest is a question about a
        // sample and this is a question about a stroke. A parallel ruler that
        // was only told about strokes which happened to start beside it would
        // be a ruler that works some of the time.
        for (guide in guides) guide.begin(xDoc, yDoc)
    }

    override fun advance(xDoc: Float, yDoc: Float) {
        for (guide in guides) guide.advance(xDoc, yDoc)
    }

    override fun toString(): String = "NearestGuide(${guides.size})"

    companion object {
        /**
         * One guide, that guide; several, a [NearestGuide]; none, null.
         *
         * The empty and single cases are worth the branch: a page with one
         * ruler on it is the common one, and wrapping it would put an array
         * walk and a scratch write on every sample to choose between one thing.
         */
        fun of(guides: List<Guide>): Guide? = when (guides.size) {
            0 -> null
            1 -> guides[0]
            else -> NearestGuide(guides)
        }
    }
}
