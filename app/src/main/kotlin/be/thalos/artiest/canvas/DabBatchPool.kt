package be.thalos.artiest.canvas

/**
 * A preallocated ring of [DabBatch]es, with an exact answer to the question
 * that makes recycling dangerous.
 *
 * `renderFrontBufferedLayer(param)` returns immediately. The library queues the
 * param and draws it later on its own thread, and there is no return value, no
 * future and no completion callback that names the param. So a ring that simply
 * wrapped around would, under render-thread lag, hand back a slot the library
 * has not read yet, and the symptom is not a crash: it is a stroke that draws
 * the wrong dabs, occasionally, under load. `docs/phase1-plan.md` says the slot
 * count must be "validated against observed render-thread lag" rather than
 * assumed, and this class is how that validation is possible.
 *
 * **The completion signal the plan says does not exist is `onDrawFrontBufferedLayer`
 * itself.** By the time that callback returns, the batch has been read and the
 * slot is free. It does not tell you *when* a render lands on screen — nothing
 * does — but "landed on screen" is not the question a pool has to answer. So
 * the render thread reports the sequence it just drew ([markDrawn]) and
 * [acquire] compares it against what has been issued. Two consequences, and
 * both matter more than the slot count itself:
 *
 * - **Reuse is provably safe, not statistically safe.** A slot is handed out
 *   only when its previous occupant has been drawn.
 * - **Undersizing is visible instead of silent.** When the ring is exhausted,
 *   [acquire] allocates a batch outside it and counts a [spills]. That trades a
 *   correctness bug for an allocation, which is the right trade on this path,
 *   and W9's allocation trace reads the counter: a non-zero [spills] on a real
 *   stroke means [slots] is too small, and [peakInFlight] says by how much.
 *   A pool that never spills and a `peakInFlight` well under `slots` is the
 *   evidence the number is right.
 *
 * Threading: [acquire] and [issuedCount] are UI-thread only, [markDrawn] is
 * render-thread only, and the two volatile counters are the whole
 * synchronisation. The UI thread writes a batch's contents *before* the
 * volatile `issued` write in [acquire]; the render thread reads `issued`-
 * ordered state after it. Nothing here locks — a lock on the input path would
 * put the render thread's scheduling delay into the pen's latency.
 */
class DabBatchPool(
    /** Batches in the ring. See [DEFAULT_SLOTS]. */
    val slots: Int = DEFAULT_SLOTS,
    /** Dabs per batch. See [DabBatch.DEFAULT_CAPACITY]. */
    val capacity: Int = DabBatch.DEFAULT_CAPACITY,
) {

    init {
        require(slots > 0) { "slots was $slots" }
        require(capacity > 0) { "capacity was $capacity" }
    }

    private val ring = Array(slots) { DabBatch(capacity) }

    /** Batches handed out since construction. Written by the UI thread only. */
    @Volatile
    private var issued: Long = 0L

    /** Highest sequence the render thread has finished with. */
    @Volatile
    private var drawn: Long = 0L

    /**
     * Batches allocated outside the ring because every slot was still in
     * flight. Zero is the design point; anything else is a measurement, not a
     * failure — see the class header.
     */
    var spills: Long = 0L
        private set

    /** The largest number of batches ever outstanding at once. */
    var peakInFlight: Int = 0
        private set

    /** Batches handed out so far — the watermark [markDrawn] is given at commit. */
    val issuedCount: Long get() = issued

    /** Outstanding batches right now: acquired, not yet reported drawn. */
    val inFlight: Int get() = (issued - drawn).toInt()

    /**
     * Take the next batch. Never blocks, never throws, never returns a batch
     * the render thread might still be reading.
     *
     * The returned batch is empty and carries a fresh [DabBatch.sequence]; its
     * colour and antialias flag are the caller's to set, because they come from
     * the stroke rather than from the pool.
     */
    fun acquire(): DabBatch {
        val outstanding = (issued - drawn).toInt()
        if (outstanding > peakInFlight) peakInFlight = outstanding
        val batch: DabBatch
        if (outstanding >= slots) {
            spills++
            batch = DabBatch(capacity)
            batch.spilled = true
        } else {
            batch = ring[(issued % slots).toInt()]
        }
        batch.count = 0
        val seq = issued + 1
        batch.sequence = seq
        // Volatile write last: everything above is published to the render
        // thread by it, and the render thread's read of `issued` (through
        // markDrawn's compare) is the matching acquire.
        issued = seq
        return batch
    }

    /**
     * Report from the render thread that everything up to and including
     * [sequence] has been read.
     *
     * Monotonic rather than a plain assignment because the two callers race by
     * construction: `onDrawFrontBufferedLayer` reports one batch at a time
     * while the commit path reports a whole stroke's worth at once, and a
     * late-arriving smaller value must not walk the watermark backwards and
     * re-expose a slot.
     */
    fun markDrawn(sequence: Long) {
        if (sequence > drawn) drawn = sequence
    }

    /**
     * Declare every outstanding batch finished with, because nothing is going
     * to report them drawn.
     *
     * There is exactly one situation this is for, and it is W13's: a stroke
     * cancelled with no surface to cancel it on.
     * `CanvasFrontBufferedRenderer.cancel()` clears the library's param queue
     * and hides the front buffer — bytecode-verified: `ParamQueue.clear`,
     * `cancelPending`, a runnable that sets the front-buffer SurfaceControl
     * invisible, and a buffer clear — and **invokes neither draw callback**. So
     * every batch queued when a cancel lands is finished with and is never
     * reported.
     *
     * How bad that is, stated exactly, because the obvious reading overstates
     * it: [markDrawn] is a watermark, so the next batch that *does* get drawn
     * releases the abandoned ones along with itself. The leak is therefore
     * bounded by one cancel's worth of batches and it self-heals — but not
     * before two things have happened. The stroke *after* a cancel starts with
     * a ring that is short by exactly that many slots, and can spill on its
     * first few events for no reason of its own; and [peakInFlight] and
     * [inFlight] read high from the cancel onwards, which quietly invalidates
     * the measurement W9 sized [DEFAULT_SLOTS] with. A cancel that is the last
     * thing to happen leaves [inFlight] non-zero for good.
     *
     * With a live surface the caller does not need this — a
     * `renderMultiBufferedLayer` after the cancel gives the render thread the
     * same watermark the commit path uses, on the right thread, in order. This
     * is the arm for when the surface is already gone, where there is no render
     * thread left to take the handoff. Safe there for the reason it is not safe
     * in general: the library has already run `releaseInternal(cancelPending =
     * true)` from its own `surfaceDestroyed`, so pending renders are cancelled,
     * and any callback still running is drawing into a buffer nothing will
     * show.
     */
    fun releaseAll() {
        markDrawn(issued)
    }

    /**
     * Drop the ring's bookkeeping. For a fresh document or a test, **not** for
     * pen-up: a batch in flight at pen-up is still in flight, and resetting
     * around it is exactly the bug the sequence numbers exist to prevent.
     */
    fun reset() {
        issued = 0L
        drawn = 0L
        spills = 0L
        peakInFlight = 0
        for (b in ring) {
            b.count = 0
            b.sequence = 0L
        }
    }

    override fun toString(): String =
        "DabBatchPool($slots x $capacity, inFlight=$inFlight, peak=$peakInFlight, spills=$spills)"

    companion object {
        /**
         * 8 slots, and W9 measured them rather than reasoned about them.
         *
         * W8 opened at 24, arrived at from the sample interval and W2's frame
         * cost: a render thread a whole 90 Hz frame behind would have about
         * four batches outstanding, and 24 was that with six times the room.
         * W9 then drove strokes through the real path — one event per frame
         * carrying five samples and up to 78 dabs, which is a punishing stroke
         * and not a typical one — and `peakInFlight` never exceeded **3**, with
         * **zero spills**, across every run. A firm-pressure stroke peaked at
         * **1**.
         *
         * So 24 was eight times a number that was itself conservative. 8 keeps
         * a 2.6x margin over the worst observed peak and costs 8 x 64 x 3
         * floats = 6 KB allocated once. The margin can be this thin precisely
         * because exhaustion is no longer a bug: [acquire] allocates outside
         * the ring and counts a [spills], so an undersized pool degrades to one
         * allocation and a number in the readout rather than to a batch drawn
         * from under the library.
         */
        const val DEFAULT_SLOTS = 8
    }
}
