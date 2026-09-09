package be.thalos.artiest.canvas

/**
 * The unit of wet ink handed to the front buffer: the dabs one input event
 * produced, in **document space**, plus the two paint settings frozen at
 * pen-down.
 *
 * This is `CanvasFrontBufferedRenderer`'s type parameter, and that is what
 * shapes it. The library holds every param handed to `renderFrontBufferedLayer`
 * in an internal queue and invokes `onDrawFrontBufferedLayer` for it later, on
 * its own thread — so a batch is read after the UI thread has moved on, and
 * [DabBatchPool] is the thing that decides when the slot may be written again.
 *
 * Mutable and reused on purpose. One of these per event at 321.75 Hz would be
 * the largest allocation on the input path by an order of magnitude, and the
 * budget (`docs/phase1-plan.md`) is one `PenSample` per digitizer sample and
 * nothing else. Everything mutable here is written by the UI thread before
 * [sequence] is set and read by the render thread after; the pool's volatile
 * counters carry the happens-before edge.
 *
 * Flat `FloatArray` rather than a list of dab objects, matching `Stroke`: the
 * rasterizer reads the three floats of a dab together and in order, and the
 * alternative is an allocation per dab on the path with a per-sample budget.
 *
 * [colorArgb] and [antiAlias] are copied onto every batch rather than read from
 * the view when the callback runs. Same reason `Stroke` carries them: the wet
 * pass and the dry commit have to paint the same stroke the same way, and a
 * setting changed mid-stroke would otherwise make the ink shift at pen-up.
 */
class DabBatch internal constructor(
    /** Dabs this batch can hold. Beyond it the emitter opens another batch. */
    val capacity: Int,
) {

    /** Dabs in document space, [STRIDE] floats each. */
    internal val dabs = FloatArray(capacity * STRIDE)

    /** Dabs actually filled. Reset to 0 by [DabBatchPool.acquire]. */
    internal var count: Int = 0

    internal var colorArgb: Int = 0

    internal var antiAlias: Boolean = true

    /**
     * Monotonic id assigned at [DabBatchPool.acquire], and the whole reason
     * recycling is safe rather than hopeful.
     *
     * The render thread reports it back through [DabBatchPool.markDrawn] once
     * the dabs have been read, so the pool knows exactly how many batches are
     * in flight instead of guessing a slot count that is large enough. Zero
     * only on a batch that has never been acquired.
     */
    internal var sequence: Long = 0L

    /** True for a batch allocated past the ring — see [DabBatchPool.spills]. */
    internal var spilled: Boolean = false

    internal fun add(x: Float, y: Float, radius: Float, aspect: Float = 1f, rotation: Float = 0f) {
        val o = count * STRIDE
        dabs[o] = x
        dabs[o + 1] = y
        dabs[o + 2] = radius
        dabs[o + 3] = aspect
        dabs[o + 4] = rotation
        count++
    }

    internal val isFull: Boolean get() = count >= capacity

    /** Dab [i]'s centre x, in document space. */
    fun x(i: Int): Float = dabs[i * STRIDE]

    /** Dab [i]'s centre y. */
    fun y(i: Int): Float = dabs[i * STRIDE + 1]

    /** Dab [i]'s painted radius. */
    fun radius(i: Int): Float = dabs[i * STRIDE + 2]

    /** Minor over major, 0..1. See `Stroke.aspect`. */
    fun aspect(i: Int): Float = dabs[i * STRIDE + 3]

    /** Major-axis angle in radians. See `Stroke.rotation`. */
    fun rotation(i: Int): Float = dabs[i * STRIDE + 4]

    /** How many dabs a reader should draw. */
    val size: Int get() = count

    override fun toString(): String =
        "DabBatch(seq=$sequence, $count/$capacity dabs${if (spilled) ", spilled" else ""})"

    companion object {
        /**
         * Floats per dab: x, y, radius, aspect, rotation. Matches
         * `Stroke.STRIDE` by contract, and the contract is why they are written
         * as one number rather than two that happen to agree: the wet pass and
         * the dry commit read the same layout, and a batch a stride behind a
         * stroke draws garbage rather than failing.
         */
        const val STRIDE = 5

        /**
         * 64 dabs, which is not a round number chosen for looking like one.
         *
         * At the default pen a full-pressure dab is 12 doc px of radius and
         * spacing is 0.125 of the diameter, so 3.0 doc px between dabs; 64 dabs
         * is 192 doc px of stroke in a single event. The digitizer reports
         * every 3.1-4.1 ms, so filling one batch means the pen crossed most of
         * a 2160 px document inside one sample interval. Overflow is handled
         * — the emitter opens another batch — so this is a size, not a limit.
         */
        const val DEFAULT_CAPACITY = 64
    }
}
