package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.ToolType
import be.thalos.artiest.engine.xform.CanvasTransform
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The trace format's one non-negotiable property: what comes back is what went
 * in, to the bit.
 *
 * Approximate equality would defeat the point. A stabilizer that changes the
 * low mantissa bit of one coordinate is a stroke that will diverge somewhere,
 * and a regression test that shrugs at it is a test that says the tuning change
 * was free when it was not. Every assertion here compares raw bits.
 */
class TraceRoundTripTest {

    /**
     * Includes the values a decimal encoding is usually suspected over:
     * negative zero (a different bit pattern from zero), the smallest denormal,
     * the smallest normal, both extremes, and the measured 0.00208 that
     * ACTION_DOWN arrives at.
     */
    @Test
    fun `every float in a sample comes back bit for bit`() {
        val edge = listOf(
            0.0f, -0.0f,
            Float.MIN_VALUE, -Float.MIN_VALUE,
            java.lang.Float.MIN_NORMAL, -java.lang.Float.MIN_NORMAL,
            Float.MAX_VALUE, -Float.MAX_VALUE,
            0.00208f, 1f / 3f, -1f / 3f, 1e-30f, 1e30f,
            512.3125f, -1.5707964f,
        )
        // Full mantissa entropy, which no hand-picked list produces: every one
        // of these is a random bit pattern reinterpreted as a float.
        val rng = Random(20260907)
        val random = generateSequence { Float.fromBits(rng.nextInt()) }
            .filter { it.isFinite() }
            .take(3000)
            .toList()

        val values = edge + random
        val original = values.chunked(6)
            .filter { it.size == 6 }
            .mapIndexed { i, f -> sample(f[0], f[1], f[2], f[3], f[4], f[5], timeNanos = BASE + i) }

        val decoded = roundTrip(original)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            val a = original[i]
            val b = decoded[i]
            assertBits(a.x, b.x, "x", i)
            assertBits(a.y, b.y, "y", i)
            assertBits(a.pressure, b.pressure, "pressure", i)
            assertBits(a.tilt, b.tilt, "tilt", i)
            assertBits(a.orientation, b.orientation, "orientation", i)
            assertBits(a.distance, b.distance, "distance", i)
        }
    }

    @Test
    fun `the integer and enum columns come back unchanged`() {
        val original = listOf(
            sample(1f, 2f, 3f, 4f, 5f, 6f, timeNanos = BASE, tool = ToolType.STYLUS, buttons = 0),
            // The three barrel buttons, individually addressable at 4 / 32 / 64.
            sample(1f, 2f, 3f, 4f, 5f, 6f, timeNanos = BASE + 1, tool = ToolType.STYLUS, buttons = 4),
            sample(1f, 2f, 3f, 4f, 5f, 6f, timeNanos = BASE + 2, tool = ToolType.STYLUS, buttons = 32 or 64),
            sample(
                1f, 2f, 3f, 4f, 5f, 6f, timeNanos = BASE + 3,
                tool = ToolType.FINGER, source = PenSample.Source.HISTORICAL,
            ),
        )
        assertEquals(original, roundTrip(original))
    }

    /**
     * Times are stored as a delta from one base, so the base and the deltas
     * have to reconstruct the absolute value exactly. Long arithmetic, and a
     * sample earlier than the base is allowed: the caller picks t0, and the
     * history buffer of the first event can predate it.
     */
    @Test
    fun `absolute event times survive the delta encoding, in both directions`() {
        val original = listOf(
            sample(1f, 1f, 1f, 1f, 1f, 1f, timeNanos = BASE - 4_051_000L),
            sample(1f, 1f, 1f, 1f, 1f, 1f, timeNanos = BASE),
            sample(1f, 1f, 1f, 1f, 1f, 1f, timeNanos = BASE + 9_000_000_000L),
        )
        assertEquals(
            original.map { it.eventTimeNanos },
            roundTrip(original).map { it.eventTimeNanos },
        )
    }

    /**
     * One event maps to one dab batch and one prediction, so flattening the
     * batches is a one-way loss that falsifies both — and makes the buffered /
     * unbuffered comparison impossible, since batching is the only thing
     * `requestUnbufferedDispatch` changes.
     */
    @Test
    fun `event framing and pointer identity survive the round trip`() {
        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(BASE)
        r.record(0, PointerAction.DOWN, 7, listOf(sample(timeNanos = BASE)))
        // Two pointers extracted from one MotionEvent share a seq.
        r.record(1, PointerAction.MOVE, 7, List(4) { sample(timeNanos = BASE + it) })
        r.record(1, PointerAction.MOVE, 3, List(2) { sample(timeNanos = BASE + it) })
        r.record(2, PointerAction.CANCEL, -1, emptyList())
        r.end()

        val trace = TracePlayer.decode(out.toString())
        assertEquals(listOf(0, 1, 1, 2), trace.events.map { it.seq })
        assertEquals(
            listOf(PointerAction.DOWN, PointerAction.MOVE, PointerAction.MOVE, PointerAction.CANCEL),
            trace.events.map { it.action },
        )
        assertEquals(listOf(7, 7, 3, -1), trace.events.map { it.pointerId })
        assertEquals(listOf(1, 4, 2, 0), trace.events.map { it.samples.size })
        assertEquals(7, trace.sampleCount)
        assertEquals(7, trace.samples.count())
    }

    @Test
    fun `the header carries t0, the transform and the metadata across`() {
        val xform = CanvasTransform(scale = 2.5f, rotationRad = -1.5707964f, txDoc = 12.5f, tyDoc = -7.25f)
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(BASE, xform, mapOf("device" to "DTH-A116", "refreshHz" to "90.0"))
            record(0, PointerAction.DOWN, 0, listOf(sample(timeNanos = BASE)))
            end()
        }
        val h = TracePlayer.decode(out.toString()).header
        assertEquals(TraceFormat.VERSION, h.version)
        assertEquals(BASE, h.t0Nanos)
        assertEquals(xform, h.transform)
        assertEquals(mapOf("device" to "DTH-A116", "refreshHz" to "90.0"), h.meta)
    }

    /**
     * The transform is not input, but `toDoc` consumes it, so a trace recorded
     * at 3x zoom replayed against identity draws a different stroke — and the
     * difference gets blamed on the stabilizer. An absent header line stays
     * absent rather than defaulting to identity, so the two cases stay
     * distinguishable.
     */
    @Test
    fun `an unrecorded transform decodes as absent, not as identity`() {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(BASE)
            record(0, PointerAction.DOWN, 0, listOf(sample(timeNanos = BASE)))
            end()
        }
        assertNull(TracePlayer.decode(out.toString()).header.transform)
    }

    /**
     * `String.format("%f")` emits "0,300000" under this machine's own nl-BE
     * locale, which is an extra space-separated token per float and a file the
     * parser mis-splits. Pinned as a test rather than a comment because the
     * failure only shows up for people whose locale is not en-US.
     */
    @Test
    fun `the encoding does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("nl-BE"))
            val original = listOf(sample(0.3f, 0.7f, 0.00208f, 0.5f, -0.5f, 0f, timeNanos = BASE))
            val out = StringBuilder()
            TraceRecorder(out).apply {
                begin(BASE)
                record(0, PointerAction.DOWN, 0, original)
                end()
            }
            assertTrue(',' !in out, "a decimal comma reached the trace:\n$out")
            assertEquals(original, TracePlayer.decode(out.toString()).samples.toList())
        } finally {
            Locale.setDefault(previous)
        }
    }

    /**
     * `System.lineSeparator()` would make the file bytes, and therefore every
     * git diff of the checked-in corpus, depend on which host wrote it.
     */
    @Test
    fun `lines are terminated with a literal newline`() {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(BASE)
            record(0, PointerAction.DOWN, 0, listOf(sample(timeNanos = BASE)))
            end()
        }
        assertTrue('\r' !in out, "carriage return in the trace")
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(BASE)
            record(0, PointerAction.DOWN, 0, listOf(sample(timeNanos = BASE)))
            end()
        }
        val annotated = out.toString().lines().joinToString("\n") { if (it.isEmpty()) it else "$it\n\n# note" }
        assertEquals(1, TracePlayer.decode(annotated).events.size)
    }

    /**
     * The intervals are the stroke. Rebasing exists because the recorded times
     * are a boot clock from another session, and any stage comparing a sample
     * time to the current clock reads a stale base as an unbounded gap.
     */
    @Test
    fun `rebasing shifts every timestamp by one delta and preserves the intervals`() {
        val original = listOf(
            sample(timeNanos = BASE),
            sample(timeNanos = BASE + 4_051_000L),
            sample(timeNanos = BASE + 8_102_000L),
        )
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(BASE)
            record(0, PointerAction.MOVE, 0, original)
            end()
        }
        val trace = TracePlayer.decode(out.toString())
        assertEquals(8_102_000L, trace.durationNanos)

        val rebased = trace.rebasedTo(1_000L)
        assertEquals(listOf(1_000L, 4_052_000L, 8_103_000L), rebased.samples.map { it.eventTimeNanos }.toList())
        assertEquals(trace.durationNanos, rebased.durationNanos)
        // The header base moves by the same delta, so a later decode of the
        // rebased trace reconstructs the same absolute times.
        assertEquals(1_000L, rebased.header.t0Nanos)
        assertSame(trace, trace.rebasedTo(BASE), "a zero shift should not copy the trace")
    }

    private fun assertBits(expected: Float, actual: Float, field: String, index: Int) {
        assertEquals(
            expected.toRawBits(), actual.toRawBits(),
            "sample $index $field: wrote $expected (0x${expected.toRawBits().toString(16)}), " +
                "read $actual (0x${actual.toRawBits().toString(16)})",
        )
    }

    private fun roundTrip(samples: List<PenSample>): List<PenSample> {
        val out = StringBuilder()
        val r = TraceRecorder(out)
        r.begin(BASE)
        // One sample per event keeps a decode failure pointing at the sample it
        // came from rather than at the batch it was buried in.
        samples.forEachIndexed { i, s -> r.record(i, PointerAction.MOVE, 0, listOf(s)) }
        r.end()
        return TracePlayer.decode(out.toString()).samples.toList()
    }

    private companion object {
        const val BASE = 84_213_770_166_000L
    }
}

internal fun sample(
    x: Float = 1f,
    y: Float = 2f,
    pressure: Float = 0.5f,
    tilt: Float = 0.25f,
    orientation: Float = -1.5f,
    distance: Float = 0f,
    timeNanos: Long = 0L,
    tool: Int = ToolType.STYLUS,
    buttons: Int = 0,
    source: PenSample.Source = PenSample.Source.CURRENT,
) = PenSample(x, y, pressure, tilt, orientation, distance, tool, buttons, timeNanos, source)
