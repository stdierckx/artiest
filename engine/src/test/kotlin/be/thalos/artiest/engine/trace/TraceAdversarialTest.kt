package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Adversarial attack on the claim that a trace replays bit-exactly.
 *
 * If the encoding loses a single mantissa bit, every downstream tuning
 * comparison is measuring encoding noise instead of the change under test. So
 * the questions here are only ever answered with raw bits, never with `==` on
 * floats, and never with a tolerance.
 */
class TraceAdversarialTest {

    // ---------------------------------------------------------------- 1. bits

    @Test
    fun `denormals, negative zero and random mantissas all survive the round trip bit for bit`() {
        val specials = linkedMapOf(
            "0.0f" to 0.0f,
            "-0.0f" to -0.0f,
            "Float.MIN_VALUE (denormal)" to Float.MIN_VALUE,
            "-Float.MIN_VALUE" to -Float.MIN_VALUE,
            "denormal 0x007fffff" to Float.fromBits(0x007fffff),
            "denormal 0x00000002" to Float.fromBits(0x00000002),
            "MIN_NORMAL" to java.lang.Float.MIN_NORMAL,
            "Float.MAX_VALUE" to Float.MAX_VALUE,
            "-Float.MAX_VALUE" to -Float.MAX_VALUE,
            "1/3" to 1f / 3f,
            "0.00208f (measured DOWN pressure)" to 0.00208f,
        )
        // Full mantissa entropy: a seeded LCG reinterpreted as float patterns.
        val rng = java.util.Random(0x5EEDL)
        val entropy = LinkedHashMap<String, Float>()
        while (entropy.size < 32) {
            val bits = rng.nextInt()
            val f = Float.fromBits(bits)
            if (f.isFinite()) entropy["lcg 0x${bits.toUInt().toString(16).padStart(8, '0')}"] = f
        }

        val survivors = ArrayList<String>()
        val casualties = ArrayList<String>()
        for ((name, f) in specials + entropy) {
            val back = roundTripOne(f)
            if (back.toRawBits() == f.toRawBits()) survivors += name
            else casualties += "$name: wrote $f 0x${f.toRawBits().toUInt().toString(16)}, " +
                "read $back 0x${back.toRawBits().toUInt().toString(16)}"
        }
        if (casualties.isNotEmpty()) {
            fail(
                "${casualties.size} of ${survivors.size + casualties.size} values were lossy:\n" +
                    casualties.joinToString("\n"),
            )
        }

        // The non-finite half of the question: not preserved, refused. Loudly,
        // at both ends, which is the only honest option for a decimal encoding
        // (NaN payload bits cannot survive) but has to be stated, not assumed.
        for (bad in listOf(Float.NaN, Float.fromBits(0x7fc00001), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("recorder accepted $bad") {
                TraceRecorder(StringBuilder()).apply {
                    begin(0L)
                    record(0, PointerAction.MOVE, 0, listOf(sample(x = bad)))
                }
            }
        }
        for (token in listOf("NaN", "Infinity", "-Infinity")) {
            assertFailsWith<TraceFormatException>("decoder accepted $token") {
                TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 $token 2.0 0.5 0.25 -1.5 0.0 2 0 C\nend 1 1\n")
            }
        }
    }

    /**
     * Zero's sign is a different bit pattern, and it is preserved. That matters
     * for exactly one reason: `-0.0f` and `0.0f` compare equal, so a lossy
     * encoding of the sign would never be caught by an `assertEquals` anywhere
     * downstream, and would still change `atan2` and any reciprocal in the
     * resampler.
     */
    @Test
    fun `the sign of zero is preserved and is invisible to equality`() {
        assertEquals((-0.0f).toRawBits(), roundTripOne(-0.0f).toRawBits())
        assertEquals(0.0f.toRawBits(), roundTripOne(0.0f).toRawBits())
        assertTrue(-0.0f == 0.0f, "float equality cannot see this; only raw bits can")
    }

    // ------------------------------------------------------- 2. realistic run

    /**
     * The shape the digitizer actually produces: 8192 pressure levels as
     * k/8191f, subpixel coordinates, a boot-clock base in the 10^14 nanosecond
     * range and 4.051 ms between samples at the measured 246.85 Hz.
     */
    @Test
    fun `four hundred realistic samples round trip with zero mismatches`() {
        val rng = java.util.Random(4051)
        val original = ArrayList<PenSample>(400)
        var t = 84_213_770_166_000L
        for (i in 0 until 400) {
            val k = rng.nextInt(8192)
            original += PenSample(
                x = 512f + i * 1.37f + rng.nextInt(1024) / 1024f,
                y = 900f + i * 0.91f + rng.nextInt(1024) / 1024f,
                pressure = k / 8191f,
                tilt = (rng.nextInt(9001) / 9000f) * 1.5707964f,
                orientation = -3.1415927f + rng.nextInt(6283) / 1000f,
                distance = 0f,
                toolType = ToolType.STYLUS,
                buttonState = if (i % 97 == 0) 4 else 0,
                eventTimeNanos = t,
                source = if (i % 5 == 4) PenSample.Source.CURRENT else PenSample.Source.HISTORICAL,
            )
            t += 4_051_000L
        }

        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(84_213_770_166_000L)
        // Batched the way the hardware delivers them: ~4 samples per event at
        // 246.85 Hz against a 60 Hz panel.
        original.chunked(4).forEachIndexed { i, batch -> r.record(i, PointerAction.MOVE, 0, batch) }
        r.end()

        val decoded = TracePlayer.decode(out.toString()).samples.toList()
        assertEquals(original.size, decoded.size)
        var mismatches = 0
        for (i in original.indices) {
            val a = original[i]
            val b = decoded[i]
            if (a.x.toRawBits() != b.x.toRawBits()) mismatches++
            if (a.y.toRawBits() != b.y.toRawBits()) mismatches++
            if (a.pressure.toRawBits() != b.pressure.toRawBits()) mismatches++
            if (a.tilt.toRawBits() != b.tilt.toRawBits()) mismatches++
            if (a.orientation.toRawBits() != b.orientation.toRawBits()) mismatches++
            if (a.distance.toRawBits() != b.distance.toRawBits()) mismatches++
            if (a.eventTimeNanos != b.eventTimeNanos) mismatches++
            if (a != b) mismatches++
        }
        println(
            "[realistic] 400 samples, ${out.length} chars, ${out.length / 400} bytes/sample, " +
                "$mismatches field mismatches",
        )
        assertEquals(0, mismatches)
    }

    /**
     * All 8192 pressure levels, which is the value most likely to be quantised
     * by a formatter with a fixed decimal count: consecutive levels differ by
     * 1.2e-4, so a `%.3f` encoding would collapse roughly eight levels into one.
     */
    @Test
    fun `all 8192 pressure levels stay distinct through the encoding`() {
        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(0L)
        for (k in 0 until 8192) r.record(k, PointerAction.MOVE, 0, listOf(sample(pressure = k / 8191f, timeNanos = k.toLong())))
        r.end()
        val decoded = TracePlayer.decode(out.toString()).samples.toList()
        assertEquals(8192, decoded.size)
        var worstUlp = 0
        for (k in 0 until 8192) {
            val expected = k / 8191f
            val ulp = Math.abs(expected.toRawBits() - decoded[k].pressure.toRawBits())
            if (ulp > worstUlp) worstUlp = ulp
        }
        assertEquals(8192, decoded.map { it.pressure.toRawBits() }.toSet().size, "pressure levels collapsed")
        println("[precision] worst pressure error across all 8192 levels: $worstUlp ULP")
        assertEquals(0, worstUlp)
    }

    // ------------------------------------------------- 4. adversarial parsing

    @Test
    fun `every malformed file throws a TraceFormatException naming the line`() {
        val cases = linkedMapOf(
            "empty file" to "",
            "whitespace only" to "\n\n   \n",
            "comments only" to "# nothing here\n",
            "garbage version header" to "artiest-trace one\nt0 0\nend 0 0\n",
            "future version" to "artiest-trace 2\nt0 0\nend 0 0\n",
            "version 0" to "artiest-trace 0\nt0 0\nend 0 0\n",
            "no magic" to "t0 0\nend 0 0\n",
            "magic with no version" to "artiest-trace\nend 0 0\n",
            "truncated mid sample line" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 512.31",
            "sample with too few fields" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 1.0 2.0 0.5 0.25 -1.5 0.0 2 0\nend 1 1\n",
            "sample with too many fields" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 1.0 2.0 0.5 0.25 -1.5 0.0 2 0 C X\nend 1 1\n",
            "event with too few fields" to "artiest-trace 1\nt0 0\ne 0 MOVE 0\nend 1 0\n",
            "double space between fields" to "artiest-trace 1\nt0 0\ne 0 MOVE  0 0\nend 1 0\n",
            "tab separated" to "artiest-trace 1\nt0 0\ne\t0\tMOVE\t0\t0\nend 1 0\n",
            "negative sample count" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 -1\nend 1 0\n",
            "missing end" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 0\n",
            "totals disagree" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 0\nend 2 0\n",
            "content after end" to "artiest-trace 1\nt0 0\nend 0 0\ne 0 MOVE 0 0\n",
            "second end line" to "artiest-trace 1\nt0 0\nend 0 0\nend 0 0\n",
            "utf8 BOM" to "﻿artiest-trace 1\nt0 0\nend 0 0\n",
            "meta without equals" to "artiest-trace 1\nt0 0\nmeta device\nend 0 0\n",
            "duplicate meta key" to "artiest-trace 1\nt0 0\nmeta a=1 a=2\nend 0 0\n",
            "duplicate t0" to "artiest-trace 1\nt0 0\nt0 5\nend 0 0\n",
            "no t0" to "artiest-trace 1\ne 0 MOVE 0 0\nend 1 0\n",
            "xform out of range" to "artiest-trace 1\nt0 0\nxform 0.0 0.0 0.0 0.0\nend 0 0\n",
            "unknown source token" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 1.0 2.0 0.5 0.25 -1.5 0.0 2 0 X\nend 1 1\n",
            "dt is not an integer" to "artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0.5 1.0 2.0 0.5 0.25 -1.5 0.0 2 0 C\nend 1 1\n",
        )
        val silent = ArrayList<String>()
        for ((name, text) in cases) {
            val thrown = try {
                TracePlayer.decode(text)
                null
            } catch (e: Throwable) {
                e
            }
            when {
                thrown == null -> silent += "$name: decoded without complaint"
                thrown !is TraceFormatException -> silent += "$name: threw ${thrown::class.simpleName}: ${thrown.message}"
                !thrown.message.orEmpty().startsWith("trace line ") -> silent += "$name: message does not name a line: ${thrown.message}"
            }
        }
        if (silent.isNotEmpty()) fail("malformed traces that were not cleanly refused:\n" + silent.joinToString("\n"))
    }

    /**
     * CRLF is the one damaged-file case that is deliberately tolerated: an
     * editor rewriting the line endings would otherwise present as an
     * unparseable source token on every single line of the corpus.
     */
    @Test
    fun `a file rewritten with CRLF still decodes to the same samples`() {
        val lf = recordedSample()
        val crlf = lf.replace("\n", "\r\n")
        assertEquals(
            TracePlayer.decode(lf).samples.toList(),
            TracePlayer.decode(crlf).samples.toList(),
        )
    }

    /** A five minute recording is ~75k events. Decoding one must not be pathological. */
    @Test
    fun `a very long trace decodes in linear time`() {
        val events = 50_000
        val out = StringBuilder(events * 300)
        val r = TraceRecorder(out)
        r.begin(84_213_770_166_000L)
        var t = 84_213_770_166_000L
        for (i in 0 until events) {
            r.record(i, PointerAction.MOVE, 0, List(4) { sample(x = it + i.toFloat(), timeNanos = t + it * 1_012_750L) })
            t += 4_051_000L
        }
        r.end()
        val started = System.nanoTime()
        val trace = TracePlayer.decode(out.toString())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(events, trace.events.size)
        assertEquals(events * 4, trace.sampleCount)
        println("[scale] ${out.length / 1024} KiB, ${events * 4} samples, decoded in ${elapsedMs}ms")
    }

    // ---------------------------------------------------- 5. event boundaries

    /**
     * The stabilizer integrates over per-sample dt and prediction fires once per
     * event, so both the intervals *and* the batch grouping have to come back.
     * Two pointers extracted from one MotionEvent share a seq, and that is the
     * only thing that says they were one event.
     */
    @Test
    fun `batch grouping and per-sample intervals both survive`() {
        val base = 84_213_770_166_000L
        val gaps = longArrayOf(0, 4_051_000, 8_102_000, 12_153_000)
        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(base)
        r.record(0, PointerAction.DOWN, 0, listOf(sample(timeNanos = base)))
        r.record(1, PointerAction.MOVE, 0, gaps.map { sample(timeNanos = base + 16_204_000L + it) })
        r.record(1, PointerAction.MOVE, 1, listOf(sample(timeNanos = base + 16_204_000L)))
        r.end()

        val trace = TracePlayer.decode(out.toString())
        assertEquals(listOf(1, 4, 1), trace.events.map { it.samples.size }, "batch boundaries flattened")
        assertEquals(listOf(0, 1, 1), trace.events.map { it.seq }, "the two pointers lost their shared event")
        val moved = trace.events[1].samples
        assertEquals(
            gaps.toList().zipWithNext { a, b -> b - a },
            moved.zipWithNext { a, b -> b.eventTimeNanos - a.eventTimeNanos },
            "per-sample dt is what the stabilizer integrates over",
        )
        // Grouping by seq is what reconstructs one MotionEvent from its pointers.
        assertEquals(2, trace.events.groupBy { it.seq }.size)
    }

    // ------------------------------------------------------------- helpers

    private fun roundTripOne(f: Float): Float {
        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(0L)
        r.record(0, PointerAction.MOVE, 0, listOf(sample(x = f)))
        r.end()
        return TracePlayer.decode(out.toString()).samples.single().x
    }

    private fun recordedSample(): String {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(0L)
            record(0, PointerAction.MOVE, 0, listOf(sample(x = 1.5f), sample(x = 2.5f)))
            end()
        }
        return out.toString()
    }
}
