package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush

/**
 * Cutting a record into records, which is what erasing part of a stroke is.
 *
 * ## The whole of it is `dabBase`
 *
 * A piece cut out of the middle of a stroke must go on drawing what it drew as
 * part of the stroke. Its scatter, its size jitter and its grain are a hash of
 * (seed, **dab index**, channel) since Ik2, so the only thing a piece needs in
 * order to be indistinguishable from its parent is to know which dab index its
 * first dab carries — and that number is not the sample index, because dabs are
 * laid by arc length and a slow stretch of a stroke lays many more of them than
 * a fast one.
 *
 * So the parent is **re-run** to find it. The dab loop is replayed sample by
 * sample and `dabCount` is read at each cut, which is exact by construction:
 * it is the same loop, the same brush and the same input that produced the
 * number in the first place. `docs/inker-plan.md` prices a re-render at a few
 * milliseconds a stroke, and a cut happens once per gesture.
 *
 * ## What a piece is not
 *
 * **Not pixel-identical to the parent's stretch.** The Catmull-Rom fit sees
 * different neighbours at a cut — the piece has no samples before its first —
 * so the spline's end conditions differ for a sample or two either side. The
 * mark moves by a fraction of a pixel there and nowhere else. What is exactly
 * preserved is the thing an eye would catch: the grain does not crawl, because
 * the random draws follow the dab index rather than the run.
 *
 * **Not a re-timed stroke.** A piece keeps the clock it had, through
 * `StrokeRecord.startMillis` and `StrokeBuilder.begin`'s `startMillis`. Without
 * it the onset ramp would restart and the pen would appear to lift and land at
 * every cut.
 *
 * Pure, JVM, no pixels. `:engine`.
 */
object StrokeSplitter {

    /**
     * The pieces of [record] covering [ranges], in order.
     *
     * Each range is a pair of sample indices in [ranges] — start, end
     * inclusive — and they must be ascending and non-overlapping, which is what
     * the callers below produce. [ids] supplies one id per range, and a caller
     * gets them from `VectorSheet.nextIds` so that they sort between the record
     * being replaced and the one above it.
     *
     * A range shorter than [MIN_SAMPLES] is dropped rather than kept: a record
     * of one or two samples draws a dot or a tick that nobody asked for, and
     * the commonest way to produce one is an erase that clipped the very end of
     * a stroke.
     *
     * [pen] must be the brush the record names. It is needed only to re-run the
     * dab loop for the `dabBase` of each piece.
     */
    fun pieces(
        record: StrokeRecord,
        ranges: IntArray,
        ids: LongArray,
        pen: Brush,
    ): List<StrokeRecord> {
        require(ranges.size % 2 == 0) { "ranges has ${ranges.size} numbers; it wants pairs" }
        val pairs = ranges.size / 2
        require(ids.size >= pairs) { "${ids.size} ids for $pairs pieces" }
        if (pairs == 0 || record.sampleCount == 0) return emptyList()

        val samples = FloatArray(record.floatCount)
        val n = record.decodeInto(samples)
        val bases = dabBasesAt(record, samples, n, ranges, pen)

        val out = ArrayList<StrokeRecord>(pairs)
        val log = SampleLog()
        for (p in 0 until pairs) {
            val from = ranges[p * 2].coerceIn(0, n - 1)
            val to = ranges[p * 2 + 1].coerceIn(from, n - 1)
            if (to - from + 1 < MIN_SAMPLES) continue
            log.reset()
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in from..to) {
                val o = i * StrokeRecord.STRIDE
                log.add(
                    samples[o], samples[o + 1], samples[o + 2],
                    samples[o + 3], samples[o + 4], samples[o + 5],
                )
                // The piece's own painted extent, from the size response rather
                // than from the parent's bounds: a piece that inherited the
                // whole stroke's rectangle would make every damage rectangle
                // the size of the stroke it was cut from, and Ik4's whole
                // argument is that the rectangle has to be tight.
                val half = pen.sizeFor(samples[o + 2], samples[o + 5]) * 0.5f + pen.scatter.max
                l = minOf(l, samples[o] - half)
                t = minOf(t, samples[o + 1] - half)
                r = maxOf(r, samples[o] + half)
                b = maxOf(b, samples[o + 1] + half)
            }
            out.add(
                StrokeRecord(
                    id = ids[out.size],
                    brush = record.brush,
                    colorArgb = record.colorArgb,
                    erase = record.erase,
                    seed = record.seed,
                    dabBase = bases[p],
                    clip = record.clip,
                    // Both halves of a cut stroke were drawn against the same
                    // guide, for `dabBase`'s reason one line up: a piece is the
                    // same hand movement, and it has to re-render as one.
                    guide = record.guide,
                    bounds = if (l > r) Bounds.EMPTY else Bounds.of(l, t, r, b),
                    packed = log.pack(),
                    sampleCount = log.count,
                )
            )
        }
        return out
    }

    /**
     * The pieces of [record] with the samples from [from] to [to] removed.
     *
     * The shape every eraser mode ends in. Answers an empty list when nothing
     * is left, which is what erasing the middle of a two-sample stroke does and
     * is a perfectly good answer: the caller removes the parent and adds
     * nothing.
     */
    fun without(
        record: StrokeRecord,
        from: Int,
        to: Int,
        ids: LongArray,
        pen: Brush,
    ): List<StrokeRecord> {
        val last = record.sampleCount - 1
        if (last < 0) return emptyList()
        val a = from.coerceIn(0, last)
        val b = to.coerceIn(a, last)
        val ranges = ArrayList<Int>(4)
        if (a > 0) {
            ranges.add(0)
            ranges.add(a - 1)
        }
        if (b < last) {
            ranges.add(b + 1)
            ranges.add(last)
        }
        if (ranges.isEmpty()) return emptyList()
        return pieces(record, ranges.toIntArray(), ids, pen)
    }

    /**
     * How many pieces [without] would produce. For allocating ids before the
     * cut is made, which is what `VectorSheet.replace` needs.
     *
     * Answers the count of *ranges*, not of surviving pieces: a range shorter
     * than [MIN_SAMPLES] is dropped later, so this is an upper bound and the
     * caller may be handed one id it does not use. Spending an id is free —
     * they only ever go up — and under-counting would be a crash.
     */
    fun pieceCount(record: StrokeRecord, from: Int, to: Int): Int {
        val last = record.sampleCount - 1
        if (last < 0) return 0
        val a = from.coerceIn(0, last)
        val b = to.coerceIn(a, last)
        var n = 0
        if (a > 0) n++
        if (b < last) n++
        return n
    }

    /**
     * The dab index each range's first sample falls on, by re-running the
     * parent's own dab loop.
     *
     * Exact rather than estimated, and the estimate is the trap: dabs are laid
     * by arc length, so "half the samples" is not "half the dabs" on any stroke
     * whose speed varied — which is every stroke a hand draws.
     *
     * One re-run for all the ranges, walking them in order. The dab count is
     * read *before* the sample at the range's start is fed, because that sample
     * is the first one the piece will be built from.
     */
    private fun dabBasesAt(
        record: StrokeRecord,
        samples: FloatArray,
        count: Int,
        ranges: IntArray,
        pen: Brush,
    ): IntArray {
        val pairs = ranges.size / 2
        val out = IntArray(pairs)
        val builder = StrokeBuilder(pen)
        builder.begin(record.colorArgb, record.seed, record.dabBase, record.startMillis)
        var next = 0
        for (i in 0 until count) {
            while (next < pairs && ranges[next * 2] <= i) {
                out[next] = record.dabBase + builder.dabCount
                next++
            }
            val o = i * StrokeRecord.STRIDE
            val nanos = EPOCH_NANOS + (samples[o + 5] * 1_000_000f).toLong()
            builder.addTilt(samples[o + 3], samples[o + 4], nanos)
            builder.add(samples[o], samples[o + 1], samples[o + 2], nanos)
        }
        while (next < pairs) {
            out[next] = record.dabBase + builder.dabCount
            next++
        }
        builder.cancel()
        return out
    }

    /**
     * Fewer samples than this and a piece is not worth keeping.
     *
     * Four. Below it the Catmull-Rom fit has no segment to interpolate and the
     * result is a dot or a tick — and the commonest way to produce one is an
     * erase that clipped the very end of a stroke, where the user meant to take
     * the end off rather than to leave a speck behind.
     */
    const val MIN_SAMPLES: Int = 4

    /** See `InkSurfaceView`'s replay epoch: arbitrary, fixed, only deltas read. */
    private const val EPOCH_NANOS: Long = 1_000_000_000L
}
