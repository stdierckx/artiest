package be.thalos.artiest.engine.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The packing, and the two things that would make it quietly wrong.
 *
 * The first is **drift**: a delta code that rounds each difference on its own
 * random-walks, and the end of a long stroke lands somewhere the hand did not
 * put it. It is invisible on a short stroke, which is every test stroke anyone
 * writes by hand, so it is pinned here at a thousand samples.
 *
 * The second is **a channel quantised against the wrong thing**. A byte of
 * orientation is only defensible because the mask cache's own rotation bucket
 * is coarser than it, and that is an arithmetic claim about two constants in
 * two files, so it is asserted rather than believed.
 */
class StrokeRecordTest {

    private val quantum = StrokeCodec.QUANTUM_DOC

    // --------------------------------------------------------------- shape

    @Test
    fun `a packed sample is nine bytes after an eight byte origin`() {
        val log = SampleLog()
        repeat(100) { i -> log.add(10f + i, 20f, 0.5f, 0.1f, 0f, i * 3.1f) }
        val packed = log.pack()
        assertEquals(8 + 100 * 9, packed.size)
        assertEquals(StrokeCodec.ORIGIN_BYTES, 8)
        assertEquals(StrokeCodec.SAMPLE_BYTES, 9)
    }

    @Test
    fun `an empty stroke packs to the origin alone`() {
        val packed = SampleLog().pack()
        assertEquals(8, packed.size)
        val record = recordOf(packed, 0)
        assertEquals(0, record.sampleCount)
        assertEquals(0f, record.durationMillis)
        assertEquals(0, record.decodeInto(FloatArray(0)))
    }

    @Test
    fun `the record copies the buffer it was handed`() {
        val log = SampleLog()
        log.add(10f, 20f, 1f, 0f, 0f, 0f)
        val packed = log.pack()
        val record = recordOf(packed, 1)
        packed.fill(0)
        val out = FloatArray(StrokeRecord.STRIDE)
        record.decodeInto(out)
        assertEquals(10f, out[0])
        assertEquals(20f, out[1])
        assertNotSame(packed, record.copyPackedBytes())
    }

    @Test
    fun `a mismatched sample count is refused at construction`() {
        val log = SampleLog()
        log.add(1f, 2f, 1f, 0f, 0f, 0f)
        assertFailsWith<IllegalArgumentException> { recordOf(log.pack(), 2) }
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `an arc round trips inside one quantum`() {
        val log = SampleLog()
        val n = 400
        for (i in 0 until n) {
            val t = i / (n - 1f)
            log.add(
                xDoc = 300f + 800f * t + 40f * sin(t * 6f),
                yDoc = 900f - 500f * t * t,
                pressure = 0.15f + 0.8f * sin(t * PI.toFloat()),
                tiltRad = 0.3f + 0.2f * t,
                orientationRad = -2f + 3f * t,
                timeMillis = i * (1000f / 321.75f),
            )
        }
        val raw = log.raw().copyOf(n * StrokeRecord.STRIDE)
        val record = recordOf(log.pack(), n)
        val out = FloatArray(n * StrokeRecord.STRIDE)
        assertEquals(n, record.decodeInto(out))

        var worstPos = 0f
        var worstPressure = 0f
        var worstTilt = 0f
        var worstOrientation = 0f
        var worstTime = 0f
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            worstPos = maxOf(worstPos, abs(out[o] - raw[o]), abs(out[o + 1] - raw[o + 1]))
            worstPressure = maxOf(worstPressure, abs(out[o + 2] - raw[o + 2]))
            worstTilt = maxOf(worstTilt, abs(out[o + 3] - raw[o + 3]))
            worstOrientation = maxOf(worstOrientation, abs(out[o + 4] - raw[o + 4]))
            worstTime = maxOf(worstTime, abs(out[o + 5] - raw[o + 5]))
        }
        assertTrue(worstPos <= quantum * 0.5f + 1e-4f, "position off by $worstPos px")
        assertTrue(worstPressure <= 1f / 510f + 1e-6f, "pressure off by $worstPressure")
        assertTrue(worstTilt <= (PI / 2 / 510).toFloat() + 1e-5f, "tilt off by $worstTilt rad")
        assertTrue(
            worstOrientation <= (2 * PI / 510).toFloat() + 1e-5f,
            "orientation off by $worstOrientation rad",
        )
        assertTrue(
            worstTime <= StrokeCodec.TIME_QUANTUM_MS * 0.5f + 1e-4f,
            "time off by $worstTime ms",
        )
    }

    /**
     * The drift test, and the reason the encoder quantises the absolute before
     * it takes the difference.
     *
     * A thousand samples along a wandering path, each step a fraction of a
     * quantum so that a naive implementation rounds in a biased direction and
     * accumulates. Rounding each raw difference gives an end point tens of
     * pixels away; quantising the absolutes gives half a quantum at every
     * sample including the last.
     */
    @Test
    fun `a thousand samples do not accumulate error`() {
        val log = SampleLog()
        val n = 1000
        var x = 500.0
        var y = 500.0
        for (i in 0 until n) {
            // A step of 0.6 of a quantum: too small to survive rounding on its
            // own, which is exactly the case that drifts.
            x += cos(i * 0.037) * quantum * 0.6
            y += sin(i * 0.041) * quantum * 0.6
            log.add(x.toFloat(), y.toFloat(), 0.5f, 0f, 0f, i * 3.1f)
        }
        val raw = log.raw().copyOf(n * StrokeRecord.STRIDE)
        val out = FloatArray(n * StrokeRecord.STRIDE)
        recordOf(log.pack(), n).decodeInto(out)
        var worst = 0f
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            worst = maxOf(worst, abs(out[o] - raw[o]), abs(out[o + 1] - raw[o + 1]))
        }
        assertTrue(worst <= quantum * 0.5f + 1e-4f, "drifted $worst px over $n samples")
    }

    @Test
    fun `time is milliseconds from pen-down and accumulates exactly`() {
        val log = SampleLog()
        val n = 600
        val dt = 1000f / 321.75f
        for (i in 0 until n) log.add(10f, 10f, 1f, 0f, 0f, i * dt)
        val record = recordOf(log.pack(), n)
        val out = FloatArray(n * StrokeRecord.STRIDE)
        record.decodeInto(out)
        assertEquals(0f, out[5])
        val last = out[(n - 1) * StrokeRecord.STRIDE + 5]
        assertTrue(abs(last - (n - 1) * dt) <= StrokeCodec.TIME_QUANTUM_MS, "last was $last ms")
        assertEquals(last, record.durationMillis)
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `a coordinate step past the int16 reach is refused rather than clamped`() {
        val log = SampleLog()
        log.add(0f, 0f, 1f, 0f, 0f, 0f)
        log.add(StrokeCodec.MAX_STEP_DOC + 10f, 0f, 1f, 0f, 0f, 3f)
        val thrown = assertFailsWith<IllegalArgumentException> { log.pack() }
        assertTrue(thrown.message!!.contains("jumped"), thrown.message!!)
    }

    @Test
    fun `a step just inside the reach packs`() {
        val log = SampleLog()
        log.add(0f, 0f, 1f, 0f, 0f, 0f)
        log.add(StrokeCodec.MAX_STEP_DOC - 1f, 0f, 1f, 0f, 0f, 3f)
        val record = recordOf(log.pack(), 2)
        val out = FloatArray(2 * StrokeRecord.STRIDE)
        record.decodeInto(out)
        assertTrue(abs(out[StrokeRecord.STRIDE] - (StrokeCodec.MAX_STEP_DOC - 1f)) <= quantum)
    }

    /**
     * A backwards timestamp is **held, not refused**, and the asymmetry with
     * the refusals above is the point: a NaN coordinate is a bug upstream that
     * has to be found, while a glitched clock is something to absorb rather
     * than a reason to lose the stroke the user just drew. Holding the previous
     * time is also what makes `pack()` unable to throw on the commit path.
     */
    @Test
    fun `time that goes backwards is held rather than costing the stroke`() {
        val log = SampleLog()
        log.add(0f, 0f, 1f, 0f, 0f, 10f)
        log.add(1f, 0f, 1f, 0f, 0f, 5f)
        log.add(2f, 0f, 1f, 0f, 0f, 12f)
        val out = FloatArray(3 * StrokeRecord.STRIDE)
        recordOf(log.pack(), 3).decodeInto(out)
        assertEquals(0f, out[5])
        assertEquals(0f, out[StrokeRecord.STRIDE + 5])
        assertEquals(2f, out[2 * StrokeRecord.STRIDE + 5], 0.2f)
    }

    @Test
    fun `a negative sample time is still refused`() {
        assertFailsWith<IllegalArgumentException> {
            SampleLog().add(0f, 0f, 1f, 0f, 0f, -1f)
        }
    }

    @Test
    fun `a non-finite sample is refused where it enters`() {
        val log = SampleLog()
        assertFailsWith<IllegalArgumentException> { log.add(Float.NaN, 0f, 1f, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> {
            log.add(0f, Float.POSITIVE_INFINITY, 1f, 0f, 0f, 0f)
        }
        assertFailsWith<IllegalArgumentException> { log.add(0f, 0f, Float.NaN, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { log.add(0f, 0f, 1f, 0f, 0f, -1f) }
        assertEquals(0, log.count)
    }

    @Test
    fun `out of range pressure tilt and orientation are clamped to their own range`() {
        val log = SampleLog()
        log.add(0f, 0f, -0.5f, -1f, -9f, 0f)
        log.add(0f, 0f, 2f, 9f, 9f, 3f)
        val out = FloatArray(2 * StrokeRecord.STRIDE)
        recordOf(log.pack(), 2).decodeInto(out)
        assertEquals(0f, out[2])
        assertEquals(0f, out[3])
        assertEquals(-PI.toFloat(), out[4], 1e-5f)
        assertEquals(1f, out[StrokeRecord.STRIDE + 2])
        assertEquals((PI / 2).toFloat(), out[StrokeRecord.STRIDE + 3], 1e-2f)
        assertEquals(PI.toFloat(), out[StrokeRecord.STRIDE + 4], 1e-5f)
    }

    // ----------------------------------------------------------- the budget

    /**
     * `docs/inker-plan.md` reserves 8.7 KB for a three-second stroke and
     * 661 KiB for Ik0's 300-stroke page. Both are this arithmetic, so if the
     * packing ever widens, the number in the plan is wrong that day and not
     * three months later.
     */
    @Test
    fun `a three second stroke costs what the plan reserved`() {
        val samples = (3f * 321.75f).toInt()
        val log = SampleLog()
        for (i in 0 until samples) {
            log.add(10f + i * 0.3f, 20f, 0.5f, 0.2f, 0f, i * (1000f / 321.75f))
        }
        val record = recordOf(log.pack(), samples)
        assertEquals(965, samples)
        assertTrue(record.byteCount in 8600..8800, "${record.byteCount} bytes")

        // Ik0's measured page: 74 000 samples over 300 strokes.
        val page = 74_000 * StrokeCodec.SAMPLE_BYTES + 300 * StrokeCodec.ORIGIN_BYTES
        assertTrue(page < 680 * 1024, "$page bytes for a 300-stroke page")
    }

    @Test
    fun `the sample log grows without losing what it held`() {
        val log = SampleLog()
        val n = SampleLog.INITIAL_SAMPLES * 3 + 7
        for (i in 0 until n) log.add(i.toFloat(), 0f, 1f, 0f, 0f, i * 4f)
        assertEquals(n, log.count)
        val out = FloatArray(n * StrokeRecord.STRIDE)
        recordOf(log.pack(), n).decodeInto(out)
        assertEquals(0f, out[0])
        assertEquals((n - 1).toFloat(), out[(n - 1) * StrokeRecord.STRIDE], quantum)
        log.reset()
        assertEquals(0, log.count)
    }

    private fun recordOf(packed: ByteArray, count: Int) = StrokeRecord(
        id = 1L,
        brush = 0,
        colorArgb = 0xFF000000.toInt(),
        erase = false,
        seed = 7,
        dabBase = 0,
        clip = StrokeRecord.NO_CLIP,
        bounds = Bounds.of(0f, 0f, 1f, 1f),
        packed = packed,
        sampleCount = count,
    )
}
