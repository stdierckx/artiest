package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cutting a record into records.
 *
 * `docs/inker-plan.md`'s risk table names the failure this has to avoid:
 * *"splitting a stroke changes its grain"*, answered by *"the (seed, dab index)
 * hash, Ik2"*. The test of that is the one below about a jitter brush: a piece
 * cut out of the middle must lay the dabs the parent laid there, which is only
 * true if its `dabBase` is the dab index the parent's own loop reached — and
 * that number cannot be estimated from the sample index, because dabs are laid
 * by arc length and a slow stretch of a stroke lays far more of them than a
 * fast one.
 */
class StrokeSplitterTest {

    private val dt = 1000f / 321.75f

    /** A brush that jitters its size and nothing else: the grain, recoverable. */
    private fun jittery() = Brush().apply {
        sizeMin = 0f
        sizeMax = 20f
        sizeJitter.min = 0f
        sizeJitter.max = 1f
    }

    /**
     * A stroke that speeds up along its length, so that sample index and dab
     * index are nowhere near proportional — which is the case a splitter that
     * guessed would get wrong.
     */
    private fun accelerating(n: Int = 200): StrokeRecord {
        val log = SampleLog()
        var x = 100.0
        for (i in 0 until n) {
            log.add(x.toFloat(), 300f, 1f, 0f, 0f, i * dt)
            x += 0.4 + 4.0 * (i / (n - 1.0))
        }
        return record(log, n)
    }

    private fun record(log: SampleLog, n: Int, id: Long = 1L, dabBase: Int = 0) = StrokeRecord(
        id = id,
        brush = 0,
        colorArgb = 0xFF000000.toInt(),
        erase = false,
        seed = 4242,
        dabBase = dabBase,
        clip = StrokeRecord.NO_CLIP,
        bounds = Bounds.of(0f, 0f, 2000f, 600f),
        packed = log.pack(),
        sampleCount = n,
    )

    private fun draw(record: StrokeRecord, pen: Brush): Stroke {
        val samples = FloatArray(record.floatCount)
        val n = record.decodeInto(samples)
        val b = StrokeBuilder(pen)
        b.begin(record.colorArgb, record.seed, record.dabBase, record.startMillis)
        for (i in 0 until n) {
            val o = i * StrokeRecord.STRIDE
            val nanos = 1_000_000_000L + (samples[o + 5] * 1_000_000f).toLong()
            b.addTilt(samples[o + 3], samples[o + 4], nanos)
            b.add(samples[o], samples[o + 1], samples[o + 2], nanos)
        }
        return b.end()
    }

    // -------------------------------------------------------------- the cut

    @Test
    fun `erasing the middle leaves the two ends`() {
        val pen = jittery()
        val parent = accelerating()
        val pieces = StrokeSplitter.without(parent, 80, 119, longArrayOf(10L, 11L), pen)
        assertEquals(2, pieces.size)
        assertEquals(80, pieces[0].sampleCount)
        assertEquals(80, pieces[1].sampleCount)
        assertEquals(10L, pieces[0].id)
        assertEquals(11L, pieces[1].id)
        assertEquals(2, StrokeSplitter.pieceCount(parent, 80, 119))
    }

    @Test
    fun `erasing an end leaves one piece`() {
        val pen = jittery()
        val parent = accelerating()
        assertEquals(1, StrokeSplitter.pieceCount(parent, 0, 50))
        val head = StrokeSplitter.without(parent, 0, 50, longArrayOf(10L), pen)
        assertEquals(1, head.size)
        assertEquals(149, head[0].sampleCount)
        assertEquals(1, StrokeSplitter.pieceCount(parent, 150, 199))
        assertEquals(150, StrokeSplitter.without(parent, 150, 199, longArrayOf(10L), pen)[0].sampleCount)
    }

    @Test
    fun `erasing the whole stroke leaves nothing`() {
        val pen = jittery()
        val parent = accelerating()
        assertEquals(0, StrokeSplitter.pieceCount(parent, 0, 199))
        assertEquals(0, StrokeSplitter.without(parent, 0, 500, LongArray(2), pen).size)
    }

    @Test
    fun `a piece too short to draw is dropped rather than kept`() {
        val pen = jittery()
        val parent = accelerating()
        // Two samples would be left at the head.
        val pieces = StrokeSplitter.without(parent, 2, 100, longArrayOf(10L, 11L), pen)
        assertEquals(1, pieces.size, "a two-sample speck survived")
        assertEquals(99, pieces[0].sampleCount)
        assertTrue(StrokeSplitter.MIN_SAMPLES >= 4)
    }

    // ------------------------------------------------------------ the grain

    /**
     * The one `docs/inker-plan.md`'s risk table asks for. A brush that jitters
     * its size and nothing else, at flat pressure: every dab would be the same
     * radius but for the jitter, and the jitter is a hash of the dab index. So
     * a piece cut out of the middle lays the parent's own radii if and only if
     * its `dabBase` is right.
     */
    @Test
    fun `a piece cut from the middle keeps the grain it had`() {
        val pen = jittery()
        val log = SampleLog()
        val n = 300
        for (i in 0 until n) log.add(100f + i * 3f, 400f, 1f, 0f, 0f, i * dt)
        val parent = record(log, n)
        val whole = draw(parent, pen)

        val piece = StrokeSplitter.without(parent, 0, 99, longArrayOf(10L), pen).single()
        assertTrue(piece.dabBase > 0, "the piece did not take a dab index")
        val tail = draw(piece, pen)

        // Line the two up: the piece's first dab is the parent's dab `base`.
        val base = piece.dabBase
        assertTrue(tail.dabCount > 50, "only ${tail.dabCount} dabs")
        var matched = 0
        var compared = 0
        // Skip the first few: the spline's end conditions differ at a cut,
        // which is what the class note says is *not* preserved.
        for (i in 4 until minOf(tail.dabCount, whole.dabCount - base)) {
            compared++
            if (tail.radius(i) == whole.radius(base + i)) matched++
        }
        assertTrue(compared > 40, "only $compared dabs compared")
        assertTrue(
            matched.toDouble() / compared > 0.9,
            "only $matched of $compared dabs carried the parent's jitter",
        )
    }

    /**
     * The counterfactual, so the test above cannot pass for the wrong reason: a
     * piece told it starts at dab 0 draws something else.
     */
    @Test
    fun `a piece with the wrong dab index draws something else`() {
        val pen = jittery()
        val log = SampleLog()
        val n = 300
        for (i in 0 until n) log.add(100f + i * 3f, 400f, 1f, 0f, 0f, i * dt)
        val parent = record(log, n)
        val whole = draw(parent, pen)
        val right = StrokeSplitter.without(parent, 0, 99, longArrayOf(10L), pen).single()
        val wrong = StrokeRecord(
            id = 11L, brush = right.brush, colorArgb = right.colorArgb, erase = false,
            seed = right.seed, dabBase = 0, clip = right.clip, bounds = right.bounds,
            packed = right.copyPackedBytes(), sampleCount = right.sampleCount,
        )
        val tail = draw(wrong, pen)
        val base = right.dabBase
        var matched = 0
        for (i in 4 until minOf(tail.dabCount, whole.dabCount - base)) {
            if (tail.radius(i) == whole.radius(base + i)) matched++
        }
        assertTrue(matched < 10, "$matched dabs matched by accident")
    }

    /**
     * The dab index is not proportional to the sample index, which is why the
     * splitter re-runs the loop instead of estimating. On a stroke that speeds
     * up, half the samples is nowhere near half the dabs.
     */
    @Test
    fun `the dab index is found by re-running rather than by proportion`() {
        val pen = jittery()
        val parent = accelerating(200)
        val piece = StrokeSplitter.without(parent, 0, 99, longArrayOf(10L), pen).single()
        val total = draw(parent, pen).dabCount
        val half = total / 2
        assertTrue(
            abs(piece.dabBase - half) > total / 10,
            "dabBase ${piece.dabBase} is suspiciously close to half of $total",
        )
    }

    // ------------------------------------------------------------ the clock

    /**
     * A tail whose clock restarted would get a fresh onset ramp: the pen would
     * appear to lift and land again at the cut, which is the most visible thing
     * a split could get wrong.
     */
    @Test
    fun `a piece keeps its place in the stroke's clock`() {
        val pen = Brush().apply { sizeMin = 2f; sizeMax = 20f }
        val log = SampleLog()
        val n = 300
        for (i in 0 until n) log.add(100f + i * 3f, 400f, 1f, 0f, 0f, i * dt)
        val parent = record(log, n)
        val piece = StrokeSplitter.without(parent, 0, 149, longArrayOf(10L), pen).single()

        assertTrue(piece.startMillis > 400f, "the piece started at ${piece.startMillis} ms")
        val tail = draw(piece, pen)
        // The onset ramp lifts the first dabs of a stroke; a piece from the
        // middle is past it, so its first dab is full width.
        assertEquals(10f, tail.radius(0), 0.01f)
        assertEquals(10f, tail.radius(tail.dabCount - 1), 0.01f)
    }

    @Test
    fun `the head keeps the clock it always had`() {
        val pen = jittery()
        val parent = accelerating()
        val head = StrokeSplitter.without(parent, 120, 199, longArrayOf(10L), pen).single()
        assertEquals(0f, head.startMillis, 0.2f)
        assertEquals(0, head.dabBase)
    }

    // ----------------------------------------------------------- the bounds

    /**
     * A piece that inherited the parent's rectangle would make every damage
     * rectangle the size of the stroke it was cut from — and Ik4's whole
     * argument is that the rectangle has to be tight.
     */
    @Test
    fun `a piece carries its own painted extent, not its parent's`() {
        val pen = Brush().apply { sizeMin = 2f; sizeMax = 10f }
        val log = SampleLog()
        val n = 200
        for (i in 0 until n) log.add(100f + i * 4f, 400f, 1f, 0f, 0f, i * dt)
        val parent = record(log, n)
        val pieces = StrokeSplitter.without(parent, 100, 199, longArrayOf(10L), pen)
        val head = pieces.single()
        assertTrue(head.bounds.right < 600f, "the head kept the whole stroke's width: ${head.bounds}")
        assertTrue(head.bounds.left < 100f && head.bounds.left > 80f, "${head.bounds}")
        assertTrue(head.bounds.height < 30f, "${head.bounds}")
    }

    @Test
    fun `every field but the samples comes from the parent`() {
        val pen = jittery()
        val parent = accelerating()
        val piece = StrokeSplitter.without(parent, 0, 99, longArrayOf(10L), pen).single()
        assertEquals(parent.brush, piece.brush)
        assertEquals(parent.colorArgb, piece.colorArgb)
        assertEquals(parent.erase, piece.erase)
        assertEquals(parent.seed, piece.seed)
        assertEquals(parent.clip, piece.clip)
    }

    @Test
    fun `an empty record cuts into nothing`() {
        val pen = jittery()
        val empty = record(SampleLog(), 0)
        assertEquals(0, StrokeSplitter.without(empty, 0, 10, LongArray(2), pen).size)
        assertEquals(0, StrokeSplitter.pieceCount(empty, 0, 10))
    }
}
