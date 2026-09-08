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
         * 24 slots, sized from the only two numbers that bound the answer.
         *
         * The pen reports every 3.1 ms at the measured 321.75 Hz, and W2 put
         * the render thread's whole frame at p99 4.0 ms against an 11.1 ms
         * budget. One batch per event means a render thread that fell a full
         * 90 Hz frame behind — 11.1 ms, far past anything W2 saw — would have
         * about 4 batches outstanding. 24 is that with six times the room,
         * costing 24 x 64 x 3 floats = 18 KB allocated once.
         *
         * The number is still a starting point rather than a result: W9
         * measures [peakInFlight] on a real stroke and this constant moves to
         * whatever that says, which is the point of counting at all.
         */
        const val DEFAULT_SLOTS = 24
    }
}
