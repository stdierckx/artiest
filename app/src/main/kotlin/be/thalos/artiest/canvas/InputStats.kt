package be.thalos.artiest.canvas

/**
 * What the input path costs, measured rather than asserted.
 *
 * `docs/phase1-plan.md` puts two numbers on the path from `onTouchEvent` to
 * `renderFrontBufferedLayer`: **under 0.31 ms per event**, and **one immutable
 * `PenSample` per digitizer sample and nothing else**. Both are claims about a
 * running app on a specific tablet, so neither can be settled by reading the
 * code — an iterator allocated inside a library call, a boxed `Long` from an
 * inlined lambda, or a `Matrix` rebuilt one layer down are all invisible at the
 * call site and all fatal to the second claim.
 *
 * Two kinds of measurement, because they fail differently:
 *
 * - **Per event, timed.** Percentiles, not a mean: the budget is missed by a
 *   tail, and a mean that looks fine is exactly what an occasional stall
 *   produces. `:spike`'s `FrameStats` made the same argument for frames and it
 *   is the same argument here.
 * - **Per stroke, allocated.** Split into the **drag** — pen-down to the last
 *   sample before pen-up, which is where the per-sample budget lives — and the
 *   **commit**, where one `Stroke`, one `Bounds` and one dab-array copy are
 *   allocated on purpose. Reporting them together would hide a per-sample leak
 *   behind an 8 KB copy that is supposed to be there.
 *
 * **This class does not read the clock or the heap.** It is handed numbers.
 * That keeps it a plain JVM class the unit tests can drive with a known
 * distribution, and it keeps the two Android-only calls — `Runtime`'s heap
 * figures and `Debug.getRuntimeStat` — at the one site that knows when a stroke
 * starts and ends.
 *
 * Fixed arrays, no allocation past construction, nothing but stores on the
 * recording path. A recorder that perturbs an allocation measurement is not
 * merely imprecise, it is measuring itself.
 */
class InputStats(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity was $capacity" }
    }

    private val eventNs = LongArray(capacity)

    /**
     * Of [eventNs], the part spent inside `renderFrontBufferedLayer`.
     *
     * Split out because the two halves have opposite fixes. Time in the app's
     * own work — expansion, routing, the spline, the dab emit — is ours to
     * make cheaper. Time inside the submit call is the library making the UI
     * thread wait on the compositor, and no amount of tightening the resampler
     * touches it. Measuring only the total leaves those indistinguishable, and
     * they were: the first stroke after a process start ran at a third the cost
     * of every later one, at a pinned 2.0 GHz, which is not a shape any amount
     * of app-side work produces.
     */
    private val submitNs = LongArray(capacity)
    /**
     * How old the newest sample already was when `onTouchEvent` received it.
     *
     * The other half of the latency the app can see. [eventNs] is what the app
     * spends; this is what was spent before it was asked, and on this device it
     * is the larger of the two by a wide margin. See
     * `MotionEvent.eventAgeNanos`.
     */
    private val ageNs = LongArray(capacity)

    private val eventSamples = IntArray(capacity)
    private val eventDabs = IntArray(capacity)

    /** Sorted in place by [eventMs]; never touched on the recording path. */
    private val scratch = LongArray(capacity)

    private var count = 0
    private var cursor = 0

    /** Events recorded, capped at [capacity]. */
    val events: Int get() = count

    /** Samples across those events. */
    var samples: Long = 0L
        private set

    /** Front-buffer submissions across those events. */
    var batches: Long = 0L
        private set

    /** Dabs emitted across those events. */
    var dabs: Long = 0L
        private set

    /**
     * Bytes allocated **inside `onTouchEvent`** between pen-down and the last
     * event before pen-up, and the sample count they cover. This is the pair
     * the per-sample budget is read off.
     *
     * Summed per event rather than taken as one watermark across the whole
     * drag, and the difference is not pedantry — it is the difference between
     * measuring the app and measuring the harness. A single begin-to-end
     * watermark also catches everything the *caller* allocated between events:
     * for a hand on glass that is whatever else the UI thread was doing, and
     * for `StrokeStress` it is a `MotionEvent.obtain` plus an `addBatch` per
     * frame, which is not the input path's cost and would be charged to it.
     * The first run of this measurement did exactly that and reported 1023
     * B/sample.
     */
    var dragBytes: Long = 0L
        private set

    /** See [dragBytes]. */
    var dragSamples: Int = 0
        private set

    /** Heap growth across the commit: the `Stroke`, its dab copy, its bounds. */
    var commitBytes: Long = 0L
        private set

    /**
     * Collections that ran during the last measured stroke.
     *
     * Non-zero invalidates [dragBytes] and [commitBytes] rather than merely
     * blurring them: heap-used is a level, not a counter, so a collection
     * inside the window subtracts freed bytes from allocated ones and can make
     * the delta small, zero or negative. A measurement that quietly reports
     * "almost no allocation" because a GC ran is worse than no measurement, so
     * the count travels with the numbers and the readout refuses to show them
     * without it.
     */
    var strokeGcs: Long = 0L
        private set

    /** True when the last stroke's byte figures are usable. See [strokeGcs]. */
    val allocationValid: Boolean get() = strokeGcs == 0L && dragSamples > 0

    /** One event's cost. Called once per `onTouchEvent`, after the work. */
    fun recordEvent(
        durationNs: Long,
        submitDurationNs: Long,
        inputAgeNs: Long,
        sampleCount: Int,
        batchCount: Int,
        dabCount: Int,
    ) {
        eventNs[cursor] = durationNs
        submitNs[cursor] = submitDurationNs
        ageNs[cursor] = inputAgeNs
        eventSamples[cursor] = sampleCount
        eventDabs[cursor] = dabCount
        cursor = (cursor + 1) % capacity
        if (count < capacity) count++
        samples += sampleCount
        batches += batchCount
        dabs += dabCount
    }

    /** One stroke's allocation. Called once at pen-up. */
    fun recordStroke(dragBytes: Long, dragSamples: Int, commitBytes: Long, gcs: Long) {
        this.dragBytes = dragBytes
        this.dragSamples = dragSamples
        this.commitBytes = commitBytes
        this.strokeGcs = gcs
    }

    fun reset() {
        count = 0
        cursor = 0
        samples = 0L
        batches = 0L
        dabs = 0L
        dragBytes = 0L
        dragSamples = 0
        commitBytes = 0L
        strokeGcs = 0L
    }

    /** Event duration at percentile [p] (0..1), in milliseconds. */
    fun eventMs(p: Float): Float = percentileMs(eventNs, p)

    /** Of that, the part spent inside `renderFrontBufferedLayer`. */
    fun submitMs(p: Float): Float = percentileMs(submitNs, p)

    /**
     * Age of the newest sample at `onTouchEvent`, at percentile [p], in
     * milliseconds. See [ageNs].
     */
    fun inputAgeMs(p: Float): Float = percentileMs(ageNs, p)

    private fun percentileMs(src: LongArray, p: Float): Float {
        if (count == 0) return 0f
        System.arraycopy(src, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        val idx = ((count - 1) * p).toInt().coerceIn(0, count - 1)
        return scratch[idx] / 1e6f
    }

    /**
     * Events over [budgetMs], as a fraction of those measured.
     *
     * Against a fixed budget and not against this run's own median, which is
     * the opposite of what `FrameStats.droppedRate` does — and deliberately.
     * A frame gap is pinned to whatever the panel is doing, so a fixed
     * threshold there measures the refresh rate; per-event work is pinned to
     * nothing, and 0.31 ms is a real ceiling that does not move when the panel
     * does.
     */
    fun overBudgetRate(budgetMs: Float = BUDGET_MS): Float {
        if (count == 0) return 0f
        val budget = (budgetMs * 1e6f).toLong()
        var missed = 0
        for (i in 0 until count) if (eventNs[i] > budget) missed++
        return missed.toFloat() / count
    }

    /** Mean samples per event — the digitizer rate divided by the frame rate. */
    fun samplesPerEvent(): Float = meanOf(eventSamples)

    /**
     * Mean dabs per event, and the figure that makes the timing readable.
     *
     * An event's cost is not a constant: it is roughly linear in the dabs the
     * resampler emitted, and that count swings by an order of magnitude with
     * pressure, because `Brush`'s spacing is a fraction of the *diameter*
     * and a feather-light dab is 30x closer to its neighbour than a full-press
     * one. A per-event budget with no dab count beside it is therefore a number
     * about the stroke that was drawn, not about the code.
     */
    fun dabsPerEvent(): Float = meanOf(eventDabs)

    /** Event cost divided by the samples in it — the rate-independent figure. */
    fun msPerSample(p: Float): Float {
        val spe = samplesPerEvent()
        return if (spe <= 0f) 0f else eventMs(p) / spe
    }

    private fun meanOf(src: IntArray): Float {
        if (count == 0) return 0f
        var total = 0L
        for (i in 0 until count) total += src[i]
        return total.toFloat() / count
    }

    /**
     * Bytes allocated per digitizer sample during the drag, or -1 when the
     * measurement is invalid. See [strokeGcs].
     */
    fun bytesPerSample(): Float =
        if (!allocationValid) -1f else dragBytes.toFloat() / dragSamples

    companion object {
        /**
         * 4096 events. At the measured 90 Hz frame rate that is 45 seconds of
         * continuous drawing, which is longer than any stroke and long enough
         * that a run's percentiles are not one unlucky scheduling hiccup.
         */
        const val DEFAULT_CAPACITY = 4096

        /** The plan's per-event ceiling, in milliseconds. */
        const val BUDGET_MS = 0.31f
    }
}
