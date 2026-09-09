package be.thalos.artiest.engine.ink

/**
 * One finished stroke, frozen at pen-up: everything the commit step needs to
 * stamp it into the layer bitmap, and nothing else.
 *
 * This is the payload of `commitStroke(Stroke)`, and the whole reason that
 * signature can be specified with no library type in it. Dabs are floats,
 * colour is a packed Int, and the bounds is four more floats, so nothing here
 * needs `android.graphics` and the type lives in the module a JVM test can
 * reach. `:app` turns it into `Canvas` calls; `:engine` never sees one.
 *
 * **It crosses a thread boundary, and immutability is not decoration here.**
 * The UI thread builds it at pen-up and hands it to the render thread through
 * `AtomicReference.getAndSet(null)`, which consumes it exactly once so a
 * dropped frame cannot double-stamp. Two separate things make that safe, and
 * both are needed:
 *
 * - The `getAndSet` pair is a volatile write followed by a volatile read, so
 *   everything the UI thread wrote before the set — including every element of
 *   the dab array — is visible to the render thread after the get. That edge is
 *   unconditional. It is the guarantee this type actually rests on, and W8/W10
 *   must not weaken it into a plain field or a "the render thread will see it
 *   eventually".
 * - Every field is `val` and the constructor does not leak `this`, so a
 *   half-built stroke is not representable even if the handoff is ever
 *   refactored. [copyOf] is what makes that claim true rather than nominal:
 *   W7's `StrokeBuilder` emits into a *reused* buffer, and a stroke aliasing
 *   that buffer would go on changing under the render thread while the JLS
 *   17.5 freeze said nothing about it, because the freeze covers the array's
 *   contents only as of the end of the constructor.
 *
 * The dab payload is a flat `FloatArray` of x, y, radius triples rather than a
 * list of dab objects. That is partly scope — `Dab` and `DabList` are W7's, and
 * defining this over them would drag W7's allocation design into W6 — and
 * partly the render path: the rasterizer reads the three floats of a dab
 * together and in order, and one array is one allocation per commit instead of
 * one per dab.
 *
 * Retained only until the commit finishes. `Document` keeps the [bounds] of
 * each committed stroke and nothing more; keeping the dabs would rebuild the
 * ever-growing scene list the layer bitmap exists to replace.
 */
class Stroke private constructor(
    // Private, and exposed only through the indexed accessors below, so no
    // caller can obtain the array and alias it back into a builder.
    private val dabs: FloatArray,
    val dabCount: Int,
    /** Packed ARGB, the form `Paint.setColor` takes. */
    val colorArgb: Int,
    /**
     * Whether the dabs were painted with antialiasing.
     *
     * Carried on the stroke rather than read from a global at commit time
     * because the wet pass and the dry commit have to paint the same stroke the
     * same way. Rebuild the paint from a setting that has changed since
     * ACTION_DOWN and the stroke visibly shifts at pen-up, which reads as a
     * rendering glitch rather than as a settings bug. Nothing sets it false
     * today; `:spike`'s dab paint is `Paint(Paint.ANTI_ALIAS_FLAG)`.
     */
    val antiAlias: Boolean,
    /**
     * What this stroke painted, in document space. Phase 3's undo snapshots
     * exactly this rectangle, so it must be neither smaller nor larger than the
     * ink — see [Bounds] and [MutableBounds.add].
     */
    val bounds: Bounds,
) {

    /**
     * Dab [i]'s centre x, in document space.
     *
     * No range check: the backing array is exactly [dabCount] triples long, so
     * an out-of-range index throws `ArrayIndexOutOfBoundsException` from the
     * array itself. A `require` here would say the same thing at three times the
     * cost, on a loop the rasterizer runs once per dab per commit.
     */
    fun x(i: Int): Float = dabs[i * STRIDE]

    /** Dab [i]'s centre y. See [x]. */
    fun y(i: Int): Float = dabs[i * STRIDE + 1]

    /** Dab [i]'s painted radius — the **major** semi-axis. See [x]. */
    fun radius(i: Int): Float = dabs[i * STRIDE + 2]

    /**
     * Dab [i]'s minor axis over its major, 0..1. 1 is a circle, which is what
     * every dab the pen lays is.
     */
    fun aspect(i: Int): Float = dabs[i * STRIDE + 3]

    /** Dab [i]'s major-axis angle in radians. Meaningless when [aspect] is 1. */
    fun rotation(i: Int): Float = dabs[i * STRIDE + 4]

    /**
     * Dab [i]'s own paint, 0..1 — how much of the stroke's colour it lays.
     *
     * Per dab and not per stroke because pressure varies within a stroke and
     * graphite gets darker where you lean on it. 1 for every brush that does
     * not drive flow, which is every brush before W15.
     */
    fun flow(i: Int): Float = dabs[i * STRIDE + 5]

    override fun toString(): String =
        "Stroke($dabCount dabs, color 0x${colorArgb.toUInt().toString(16)}, $bounds)"

    companion object {

        /**
         * Floats per dab: x, y, radius, aspect, rotation, flow.
         *
         * **Widened from 3 at W9, and the dab goldens did not move**, which is
         * the check that matters: the golden files serialize x, y and radius,
         * and a round dab has an aspect of exactly 1 and a rotation of exactly
         * 0. Two more floats a dab is 8 bytes on a payload that is copied once
         * per stroke, against the alternative of a parallel array or a second
         * stroke type for elliptical brushes.
         */
        const val STRIDE = 6

        /**
         * Copy [dabCount] dabs out of a builder's buffer and freeze them.
         *
         * It copies, and that is the point. W7's `StrokeBuilder` resamples into
         * a reusable array and W8's dab batches come from a preallocated ring;
         * a stroke that aliased either would be rewritten while the render
         * thread was reading it, and no lock covers that read. One allocation
         * per pen-up is the price, on the path that already allocates a
         * `Bounds` and is nowhere near the per-sample budget.
         *
         * [bounds] must agree with [dabCount] about whether anything was drawn.
         * That single check catches both halves of the accumulator bug: dabs
         * emitted without ever calling [MutableBounds.add] — the stale-rim
         * failure — and a builder reused without [MutableBounds.reset], whose
         * first stroke would otherwise arrive carrying the previous one's
         * rectangle. The dab floats themselves are not re-validated; they
         * reached [MutableBounds.add] to produce this bounds, and that is where
         * a non-finite one is refused.
         */
        fun copyOf(
            dabs: FloatArray,
            dabCount: Int,
            colorArgb: Int,
            antiAlias: Boolean,
            bounds: Bounds,
        ): Stroke {
            require(dabCount >= 0) { "dabCount was $dabCount" }
            require(dabs.size >= dabCount * STRIDE) {
                "$dabCount dabs need ${dabCount * STRIDE} floats, buffer holds ${dabs.size}"
            }
            require(bounds.isEmpty == (dabCount == 0)) {
                if (dabCount == 0) {
                    "empty stroke carries $bounds; the accumulator was not reset"
                } else {
                    "$dabCount dabs carry an empty bounds; nothing accumulated them"
                }
            }
            // Filled before the constructor runs, so the final-field freeze on
            // `dabs` covers these values and not just the reference.
            val copy = FloatArray(dabCount * STRIDE)
            dabs.copyInto(copy, 0, 0, dabCount * STRIDE)
            return Stroke(copy, dabCount, colorArgb, antiAlias, bounds)
        }
    }
}
