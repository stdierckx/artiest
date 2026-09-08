package be.thalos.artiest.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recorder W9's numbers come out of, checked against distributions whose
 * answers are known by hand.
 *
 * The failure mode worth guarding is not an arithmetic slip in a percentile —
 * it is a measurement that looks reasonable and is not. Two of those are real
 * here: a ring that has wrapped and silently reports the wrong window, and an
 * allocation figure taken across a garbage collection. The second is the
 * dangerous one, because it fails in the flattering direction: heap-used is a
 * level, so a collection inside the window subtracts freed bytes from allocated
 * ones and the path looks *cheaper* than it is. `no allocation figure survives
 * a collection` is the test that this class refuses rather than rounds.
 */
class InputStatsTest {

    private fun ms(v: Double): Long = (v * 1e6).toLong()

    @Test
    fun `percentiles come off the recorded events, not off a mean`() {
        val stats = InputStats(capacity = 100)
        // 1..100 microseconds, recorded out of order so a sort is required.
        for (i in 100 downTo 1) stats.recordEvent(i * 1_000L, 0L, 0L, 1, 1, 1 * 3)
        assertEquals(100, stats.events)
        // Index ((n-1) * p), so p50 of 100 samples is index 49 -> 50 us.
        assertEquals(0.050f, stats.eventMs(0.5f), 1e-6f)
        assertEquals(0.095f, stats.eventMs(0.95f), 1e-6f)
        assertEquals(0.100f, stats.eventMs(1f), 1e-6f)
    }

    @Test
    fun `a mean would hide the tail these percentiles exist to show`() {
        // The negative control for the choice of statistic: 95 fast events and
        // five 20x stalls. The mean stays comfortably under budget and the p99
        // does not, and it is the p99 that describes what the pen felt.
        val stats = InputStats(capacity = 100)
        repeat(95) { stats.recordEvent(ms(0.05), 0L, 0L, 3, 1, 3 * 3) }
        repeat(5) { stats.recordEvent(ms(1.0), 0L, 0L, 3, 1, 3 * 3) }

        val mean = (95 * 0.05f + 5 * 1.0f) / 100f
        assertTrue(mean < InputStats.BUDGET_MS, "the control's mean is not under budget")
        assertTrue(
            stats.eventMs(0.99f) > InputStats.BUDGET_MS,
            "the p99 missed a stall that a mean would have hidden",
        )
        assertEquals(0.05f, stats.overBudgetRate(), 1e-6f)

        // Nearest-rank-below, the same convention :spike's FrameStats uses, so
        // W2's frame numbers and W9's event numbers mean the same thing by the
        // same rule. It is mildly optimistic — with 100 samples the "p99" is
        // index 98, so the single worst event is never the answer — and a
        // single outlier is therefore visible in max, not in p99.
        val single = InputStats(capacity = 100)
        repeat(99) { single.recordEvent(ms(0.05), 0L, 0L, 3, 1, 3 * 3) }
        single.recordEvent(ms(1.0), 0L, 0L, 3, 1, 3 * 3)
        assertEquals(0.05f, single.eventMs(0.99f), 1e-6f)
        assertEquals(1.0f, single.eventMs(1f), 1e-6f)
    }

    @Test
    fun `the ring keeps the most recent events and reports only those`() {
        val stats = InputStats(capacity = 8)
        // 20 events: the first 12 must fall out, so nothing under 13 us
        // survives and the p50 is drawn from 13..20.
        for (i in 1..20) stats.recordEvent(i * 1_000L, 0L, 0L, 1, 1, 1 * 3)
        assertEquals(8, stats.events)
        assertEquals(0.020f, stats.eventMs(1f), 1e-6f)
        assertEquals(0.013f, stats.eventMs(0f), 1e-6f)
        // Totals are lifetime counters and deliberately not windowed: they
        // answer "how much did this run do", not "how fast was it lately".
        assertEquals(20L, stats.samples)
    }

    @Test
    fun `an empty recorder reports zero rather than dividing by it`() {
        val stats = InputStats(capacity = 4)
        assertEquals(0, stats.events)
        assertEquals(0f, stats.eventMs(0.5f))
        assertEquals(0f, stats.overBudgetRate())
        assertEquals(0f, stats.samplesPerEvent())
        assertFalse(stats.allocationValid)
        assertEquals(-1f, stats.bytesPerSample())
    }

    @Test
    fun `samples per event is the digitizer rate over the frame rate`() {
        val stats = InputStats(capacity = 16)
        // 321.75 Hz into 90 Hz frames is 3.575 samples an event, which arrives
        // as an alternating 3, 4, 4, 3 rather than as a fraction.
        for (n in intArrayOf(4, 3, 4, 4, 3, 4, 3, 4)) stats.recordEvent(ms(0.1), 0L, 0L, n, 1, n * 3)
        assertEquals(3.625f, stats.samplesPerEvent(), 1e-6f)
        assertEquals(29L, stats.samples)
        assertEquals(10.875f, stats.dabsPerEvent(), 1e-6f)
        // The rate-independent figure: cost per event divided by the samples
        // in it, which is what stays comparable when the panel changes rate.
        assertEquals(0.1f / 3.625f, stats.msPerSample(0.5f), 1e-6f)
    }

    @Test
    fun `bytes per sample is the number the plan predicts at about 56`() {
        val stats = InputStats(capacity = 4)
        stats.recordStroke(dragBytes = 56L * 900L, dragSamples = 900, commitBytes = 8_192L, gcs = 0L)
        assertTrue(stats.allocationValid)
        assertEquals(56f, stats.bytesPerSample(), 1e-3f)
        // The commit is reported apart from the drag on purpose: one Stroke,
        // one dab copy and one Bounds are allocated there by design, and
        // folding them in would put 8 KB across the drag's sample count and
        // turn a per-sample leak into a rounding difference.
        assertEquals(8_192L, stats.commitBytes)
    }

    @Test
    fun `no allocation figure survives a collection`() {
        val stats = InputStats(capacity = 4)
        // What a GC inside the window actually looks like: freed bytes exceed
        // allocated ones, so the delta comes out near zero — an input path that
        // appears to allocate nothing at all, which is precisely the answer
        // someone would like to see and must not be given.
        stats.recordStroke(dragBytes = 128L, dragSamples = 900, commitBytes = 0L, gcs = 1L)
        assertFalse(stats.allocationValid, "a figure taken across a GC was accepted")
        assertEquals(-1f, stats.bytesPerSample())
        assertEquals(1L, stats.strokeGcs)

        // And without the guard the same numbers read as 0.1 B/sample, which is
        // why the guard is not decoration.
        assertTrue(128f / 900f < 1f)
    }

    @Test
    fun `reset clears the window and the stroke figures`() {
        val stats = InputStats(capacity = 4)
        stats.recordEvent(ms(0.2), 0L, 0L, 3, 1, 3 * 3)
        stats.recordStroke(1000L, 10, 500L, 0L)
        stats.reset()
        assertEquals(0, stats.events)
        assertEquals(0L, stats.samples)
        assertEquals(0L, stats.batches)
        assertFalse(stats.allocationValid)
    }

    @Test
    fun `a nonsense capacity is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { InputStats(capacity = 0) }
    }

    @Test
    fun `input age is its own distribution, not a share of the event cost`() {
        val stats = InputStats()

        // The shape this exists to catch: cheap events that arrived late. An
        // implementation that derived age from the event duration — or reused
        // one ring for both — would report a fast p99 here, and the whole point
        // of the number is that these two are independent.
        repeat(99) { stats.recordEvent(ms(0.05), 0L, ms(6.0), 2, 1, 6) }
        stats.recordEvent(ms(0.05), 0L, ms(20.0), 2, 1, 6)

        assertEquals(0.05f, stats.eventMs(0.5f), 0.001f)
        assertEquals(6.0f, stats.inputAgeMs(0.5f), 0.001f)
        assertEquals(20.0f, stats.inputAgeMs(1f), 0.001f)
    }

    @Test
    fun `a reset clears the ages with everything else`() {
        val stats = InputStats()
        stats.recordEvent(ms(0.05), 0L, ms(6.0), 2, 1, 6)

        stats.reset()

        // Not a formality: `reset` zeroes `count`, and a percentile that read
        // the array without consulting `count` would keep returning the old
        // 6 ms forever after a reset.
        assertEquals(0f, stats.inputAgeMs(0.5f))
    }
}
