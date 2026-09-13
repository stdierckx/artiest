package be.thalos.artiest.engine.brush

import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.ink.StrokeCorpus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W4's stop condition, as a test rather than as a note in the plan.
 *
 * The plan names the failure mode in advance: *"a fast pressure ramp is exactly
 * the stroke that both misses the cache and produces the most dabs. If W0's
 * bench shows the hit rate collapsing on the ramp strokes in the golden corpus,
 * the fix is a small ring of nearby sizes rather than a finer step, and if that
 * does not work the item stops."*
 *
 * So this drives every golden stroke's real dab list through a real
 * [MaskCache] and reports the hit rate per stroke. `taper` and `onset` are the
 * ramps and are the two that matter; the constant-pressure strokes are the
 * control, and if *they* ever drop below near-total the quantisation is broken
 * in a way that has nothing to do with ramps.
 */
class MaskCacheBenchTest {

    private class Row(
        val name: String,
        val dabs: Int,
        val hitRate: Double,
        val masks: Int,
        val kib: Long,
    )

    private fun run(name: String, cache: MaskCache): Row {
        val samples = StrokeCorpus.all().getValue(name)
        val b = StrokeBuilder(Brush())
        b.begin(0xFF000000.toInt())
        for (s in samples) b.add(s)
        val stroke = b.end()
        cache.resetStats()
        for (i in 0 until stroke.dabCount) {
            cache.get(MaskSpec.round(stroke.radius(i) * 2f))
        }
        return Row(name, stroke.dabCount, cache.hitRate, cache.size, cache.byteCount / 1024)
    }

    @Test
    fun `the cache holds up on the corpus, ramps included`() {
        println("stroke      dabs  cold%   warm%  masks  span   KiB   gen ms")
        val rows = ArrayList<Row>()
        for (name in StrokeCorpus.all().keys) {
            val cache = MaskCache()
            val cold = run(name, cache)
            val warm = run(name, cache)          // same cache, second pass
            val span = bucketSpan(name)
            val ms = generationMillis(name)
            rows.add(cold)
            println(
                "%-10s %5d  %5.1f   %5.1f  %5d %5d %5d   %6.2f".format(
                    name, cold.dabs, cold.hitRate * 100, warm.hitRate * 100,
                    cold.masks, span, cold.kib, ms,
                ),
            )
            // The number that decides viability. A miss costs one mask
            // generation; the question is whether the generations a stroke
            // needs fit in the time it has. A stroke lasts seconds.
            assertTrue(ms < 20.0, "$name spent $ms ms generating masks")
            // And a stroke can never need more masks than it crosses buckets.
            assertTrue(
                cold.masks <= span + 2,
                "$name built ${cold.masks} masks for a $span-bucket span",
            )
            // Second time round, everything it needs is already held.
            assertTrue(warm.hitRate > 0.999, "$name only hit ${warm.hitRate} warm")
            assertTrue(cold.kib < 512, "$name needed ${cold.kib} KiB")
        }
        // The controls: a constant-pressure stroke asks for one size all the way
        // down, so anything but near-total is a broken key, not a ramp problem.
        for (name in listOf("straight", "flick", "dwell")) {
            val r = rows.first { it.name == name }
            assertTrue(
                r.hitRate > 0.99,
                "$name is constant pressure and hit only ${"%.1f".format(r.hitRate * 100)}%",
            )
        }
    }

    /** Distinct size buckets the stroke's radii pass through. */
    private fun bucketSpan(name: String): Int {
        val t = MaskTolerance()
        val samples = StrokeCorpus.all().getValue(name)
        val b = StrokeBuilder(Brush())
        b.begin(0xFF000000.toInt())
        for (s in samples) b.add(s)
        val stroke = b.end()
        val seen = HashSet<Int>()
        for (i in 0 until stroke.dabCount) seen.add(t.sizeBucket(stroke.radius(i) * 2f))
        return seen.size
    }

    /** Wall time to build every mask the stroke needs, from cold. */
    private fun generationMillis(name: String): Double {
        val samples = StrokeCorpus.all().getValue(name)
        val b = StrokeBuilder(Brush())
        b.begin(0xFF000000.toInt())
        for (s in samples) b.add(s)
        val stroke = b.end()
        var best = Double.MAX_VALUE
        repeat(5) {
            val cache = MaskCache()
            val t0 = System.nanoTime()
            for (i in 0 until stroke.dabCount) cache.get(MaskSpec.round(stroke.radius(i) * 2f))
            val ms = (System.nanoTime() - t0) / 1e6
            if (ms < best) best = ms
        }
        return best
    }

    /**
     * Wb5's stop condition: *"if tips push the dab loop past W0's measured
     * budget, tips do not ship. Measure before believing."*
     *
     * A tipped dab is the expensive one to build — a bilinear read out of a mip
     * level, sixteen times a pixel, where the ellipse solves a quadratic — and a
     * pressure ramp is the stroke that builds the most of them. So this is the
     * ramp, with the largest tip the bundle actually contains (454 px) at the
     * largest size anything asks for, which is the worst case that exists rather
     * than a worst case invented for the test.
     *
     * The budget is the same 20 ms one the corpus above is held to, and for the
     * same reason: a stroke lasts seconds, the misses are spread across it, and
     * this is all off the critical path of a single frame because the mask is
     * built before the dab is blitted, once per bucket.
     */
    @Test
    fun `a tipped ramp builds its masks inside the budget`() {
        // The bundle's biggest: `square_rough_lightgrey.png`, 454 x 448.
        val tip = Tip("bench", 454, 448, ByteArray(454 * 448) { (it % 251).toByte() })
        val samples = StrokeCorpus.all().getValue("taper")
        val brush = Brush()
        brush.sizeMax = 128f
        val b = StrokeBuilder(brush)
        b.begin(0xFF000000.toInt())
        for (s in samples) b.add(s)
        val stroke = b.end()

        var best = Double.MAX_VALUE
        var masks = 0
        var kib = 0L
        repeat(3) {
            val cache = MaskCache()
            val t0 = System.nanoTime()
            for (i in 0 until stroke.dabCount) {
                cache.get(MaskSpec(stroke.radius(i) * 2f, 1f, 1f, 0f, tip))
            }
            val ms = (System.nanoTime() - t0) / 1e6
            if (ms < best) {
                best = ms
                masks = cache.size
                kib = cache.byteCount / 1024
            }
        }
        // The number a frame actually feels. A stroke crosses about one size
        // bucket a frame, so what a frame pays is one mask, not the stroke's
        // whole bill — and the biggest single one is the biggest dab.
        var worst = Double.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime()
            MaskGenerator.generate(MaskSpec(128f, 1f, 1f, 0f, tip))
            val ms = (System.nanoTime() - t0) / 1e6
            if (ms < worst) worst = ms
        }
        println(
            "tipped taper: ${stroke.dabCount} dabs, $masks masks, $kib KiB, " +
                "%.2f ms total, %.2f ms for the largest".format(best, worst),
        )
        assertTrue(best < 20.0, "a tipped taper spent $best ms generating masks")
        assertTrue(kib < 512, "a tipped taper needed $kib KiB")
        assertTrue(worst < 4.0, "the largest tipped mask took $worst ms to build")
    }

    /**
     * The counterfactual that shows the geometric step is doing work: a
     * deliberately fine tolerance makes the ramps miss, which is the state the
     * plan warns about. If this ever passes with a high hit rate, the size key
     * has stopped depending on size.
     */
    @Test
    fun `too fine a tolerance is what a collapsed hit rate looks like`() {
        val fine = MaskCache(MaskTolerance(sizeRatio = 1.0005f))
        val r = run("taper", fine)
        assertTrue(
            r.hitRate < 0.90,
            "a 0.05% tolerance still hit ${"%.1f".format(r.hitRate * 100)}%, so size is not in the key",
        )
    }
}
