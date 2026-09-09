package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.input.PenSample
import java.lang.management.ManagementFactory

/**
 * W0's before-picture: what the Phase 1 dab loop costs, per sample and per dab.
 *
 * Phase 2 changes this loop four times — W3 recomputes spacing per dab, W4 adds
 * a mask cache, W5 replaces `drawCircle` with a mask stamp, W9 makes the dab an
 * ellipse — and every one of those is supposed to be justified by a number. This
 * is the number they are differences against, taken before anything moved.
 *
 * **A `main`, not a test, and that is the whole design.** Phase 1's W2 reported
 * "100% of frames over budget" because a refresh override had lapsed, and its
 * standing lesson is that a measurement harness needs its own review pass. A
 * timing assertion inside `:engine:test` would be the same mistake in a new
 * place: it fails on a loaded laptop, gets a tolerance widened until it cannot
 * fail, and then reports nothing. So **counts are asserted by the goldens, times
 * are only reported here.** Nothing in this file can turn CI red, and nothing in
 * CI can silently redefine what "fast" means.
 *
 *     ./gradlew :engine:benchDabLoop
 *
 * **The split between `add` and `begin+end` is the correction that makes this
 * instrument trustworthy, and it is worth stating because the first version got
 * it wrong.** Timing `begin(); adds; end()` as one block and dividing by the
 * sample count reported 501 B/sample against W9's measured 54.6 on the tablet —
 * a nine-fold disagreement that was entirely the harness. A fresh
 * [StrokeBuilder] per iteration was charging its 3 KB dab array to the samples,
 * which the single-sample `tap` case exposed at 3616 B/sample. The app allocates
 * a builder once and calls `add` per event, so **the steady-state `add` path is
 * the thing that scales with the dab count** and the thing W3 is about to
 * change; `begin+end` is a per-stroke constant and is reported apart from it.
 *
 * **What it does not measure.** Everything downstream of [StrokeBuilder] — the
 * actual rasterisation — is in `:app` behind a `Canvas`, and W5 measures that on
 * the tablet where it runs. This is the engine half: stabilizer, resampler,
 * pressure curve, dab emission. On the W16 zigzag the app spent 0.31 ms an event
 * against 230 dabs, so this half is where a per-dab cost multiplies.
 *
 * `java.lang.management` is test-source-only. It does not exist on Android and
 * must never migrate to `src/main` — see this module's build script for the
 * wider version of that rule.
 */
object DabLoopBench {

    /** Discarded iterations. C2 needs a few thousand passes over this loop. */
    private const val WARMUP = 3_000

    /** Timed iterations. The report is their median; see [report]. */
    private const val ITERATIONS = 2_000

    private val threads =
        ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Result(
        val name: String,
        val samples: Int,
        val dabs: Int,
        addNanos: LongArray,
        val strokeNanos: LongArray,
        val addBytes: Long,
    ) {
        private val sortedAdd = addNanos.sorted()
        fun addMedian(): Long = sortedAdd[sortedAdd.size / 2]
        fun addP95(): Long = sortedAdd[(sortedAdd.size * 95) / 100]
        fun strokeMedian(): Long = strokeNanos.sorted()[strokeNanos.size / 2]
    }

    /**
     * Consumed so the JIT cannot delete the work. Reading one float out of the
     * result keeps the whole stroke alive; summing every dab would add a second
     * loop to what is being timed.
     */
    private var sink = 0f

    private fun measure(name: String, samples: List<PenSample>): Result {
        // One builder for the whole measurement, as the app has one per stroke
        // in flight. After the first iteration its dab array is grown and stays
        // grown, which is exactly the steady state being measured.
        val b = StrokeBuilder(RoundPen())

        repeat(WARMUP) {
            b.begin(BLACK)
            for (s in samples) b.add(s)
            sink += b.end().let { it.x(it.dabCount - 1) }
        }

        val id = Thread.currentThread().threadId()
        val addNanos = LongArray(ITERATIONS)
        val strokeNanos = LongArray(ITERATIONS)
        var addBytes = 0L
        var dabs = 0

        for (i in 0 until ITERATIONS) {
            val strokeStart = System.nanoTime()
            b.begin(BLACK)

            // Allocation is read around the add loop but outside the timing
            // window: getThreadAllocatedBytes allocates nothing itself, so it
            // cannot inflate the byte count, but it is a JNI-ish call and has no
            // business inside a nanosecond measurement.
            val bytesBefore = threads.getThreadAllocatedBytes(id)
            val t0 = System.nanoTime()
            for (s in samples) b.add(s)
            addNanos[i] = System.nanoTime() - t0
            addBytes += threads.getThreadAllocatedBytes(id) - bytesBefore

            val stroke = b.end()
            strokeNanos[i] = System.nanoTime() - strokeStart
            dabs = stroke.dabCount
            sink += stroke.x(stroke.dabCount - 1)
        }
        return Result(name, samples.size, dabs, addNanos, strokeNanos, addBytes / ITERATIONS)
    }

    private fun report(results: List<Result>) {
        println("artiest dab-loop bench — W0 before-picture")
        println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.arch")}")
        println("warmup $WARMUP, timed $ITERATIONS, per-stroke medians")
        println("`add` is the steady-state path; `stroke` adds begin+end. See the KDoc.\n")
        val head = "%-10s %8s %7s %9s %10s %9s %9s %8s %10s"
        val row = "%-10s %8d %7d %9.2f %10.2f %9.2f %9.1f %8.1f %10.2f"
        println(
            head.format(
                "stroke", "samples", "dabs", "dabs/smp", "add us", "add p95",
                "ns/dab", "B/smp", "stroke us",
            )
        )
        for (r in results) {
            println(
                row.format(
                    r.name, r.samples, r.dabs, r.dabs.toFloat() / r.samples,
                    r.addMedian() / 1000.0, r.addP95() / 1000.0,
                    r.addMedian().toDouble() / r.dabs,
                    r.addBytes.toDouble() / r.samples,
                    r.strokeMedian() / 1000.0,
                )
            )
        }
        val samples = results.sumOf { it.samples }
        val dabs = results.sumOf { it.dabs }
        val add = results.sumOf { it.addMedian() }
        val bytes = results.sumOf { it.addBytes }
        println(
            "\n" + row.format(
                "TOTAL", samples, dabs, dabs.toFloat() / samples,
                add / 1000.0, 0.0, add.toDouble() / dabs,
                bytes.toDouble() / samples,
                results.sumOf { it.strokeMedian() } / 1000.0,
            ).replace(Regex("(?<= ) 0\\.00(?= )"), "     -")
        )
        println(
            """

            Read this against two Phase 1 numbers, both measured on the tablet:
              W9   0.119 ms and 54.6 B per input event, wet path, release build
              W16  230 dabs an input event on a real fast stroke

            The number W3 moves is dabs/smp. It should go DOWN on the strokes
            that swell under pressure (taper, onset) and stay put on the ones at
            constant width (straight, arc). If it rises anywhere the spacing
            model is backwards, which is Phase 2's stop condition 2.

            B/smp is the one to watch in W4 and W9: the dab array is reused, so
            anything above about zero here is a per-sample allocation that did
            not exist in Phase 1.
            """.trimIndent()
        )
    }

    private const val BLACK = 0xFF000000.toInt()

    @JvmStatic
    fun main(args: Array<String>) {
        val results = StrokeCorpus.all().map { (name, samples) -> measure(name, samples) }
        report(results)
        if (sink.isNaN()) println("sink went NaN — a dab position is not finite")
    }
}
