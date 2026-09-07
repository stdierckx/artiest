package be.thalos.artiest.spike

/**
 * Frame timings for W2, the question W8's render path rests on: can a full
 * redraw of the document hold the frame budget on a Mali-G57 MC2?
 *
 * Two series, because they answer different questions and a loop can pass one
 * while failing the other. **Draw cost** is the wall time inside
 * lock -> draw -> unlockAndPost, and it is the headroom number. **Frame gap**
 * is how far apart presented frames actually land, and it is the honest one: a
 * body that draws in 2 ms but only gets scheduled every 33 ms is a loop that
 * misses every vsync, and averaging alone would hide it.
 *
 * Percentiles rather than a mean. Dropped frames are the tail by definition, so
 * a mean that looks fine is exactly what a stutter produces — p95 and p99 are
 * where a dropped frame shows up at all.
 *
 * Fixed arrays, no allocation past construction, nothing but stores on the
 * recording path. A recorder that perturbs the thing it measures is worse than
 * no recorder, because it produces a number people believe.
 */
class FrameStats(private val capacity: Int = 600) {

    private val drawNs = LongArray(capacity)
    private val gapNs = LongArray(capacity)

    /** Sorted in place by [percentile]; never touched on the recording path. */
    private val scratch = LongArray(capacity)

    private var count = 0
    private var cursor = 0
    private var lastPresentNs = 0L

    val samples: Int get() = count

    fun record(drawDurationNs: Long, presentNs: Long) {
        drawNs[cursor] = drawDurationNs
        // The first frame after a reset has no predecessor and no gap. Store a
        // sentinel rather than a bogus multi-second interval from whenever the
        // view was last active, which would poison p99 for the whole run.
        gapNs[cursor] = if (lastPresentNs == 0L) -1L else presentNs - lastPresentNs
        lastPresentNs = presentNs
        cursor = (cursor + 1) % capacity
        if (count < capacity) count++
    }

    fun reset() {
        count = 0
        cursor = 0
        lastPresentNs = 0L
    }

    fun drawMs(p: Float): Float = percentile(drawNs, p)

    fun gapMs(p: Float): Float = percentile(gapNs, p)

    /**
     * Frames that took more than half again the typical gap — dropped frames,
     * as a fraction of those measured.
     *
     * Deliberately relative to the run's own p50 rather than to a fixed budget.
     * A hardcoded 11.1 ms reports 100% the moment the panel drops to 60 Hz,
     * which says nothing about the loop and everything about the refresh
     * override having lapsed; the first run of this probe did exactly that.
     * Against p50, a loop pinned to vsync reads ~0% at any refresh rate, and
     * only genuine hitches show up.
     */
    fun droppedRate(): Float = missRate(gapMs(0.5f) * 1.5f)

    /** Frames whose gap exceeded [budgetMs], as a fraction of those measured. */
    fun missRate(budgetMs: Float): Float {
        val budget = (budgetMs * 1e6f).toLong()
        var seen = 0
        var missed = 0
        for (i in 0 until count) {
            val g = gapNs[i]
            if (g < 0L) continue
            seen++
            if (g > budget) missed++
        }
        return if (seen == 0) 0f else missed.toFloat() / seen
    }

    private fun percentile(src: LongArray, p: Float): Float {
        var n = 0
        for (i in 0 until count) {
            val v = src[i]
            if (v < 0L) continue
            scratch[n++] = v
        }
        if (n == 0) return 0f
        java.util.Arrays.sort(scratch, 0, n)
        val idx = ((n - 1) * p).toInt().coerceIn(0, n - 1)
        return scratch[idx] / 1e6f
    }
}
