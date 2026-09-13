package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush

/**
 * One committed stroke, kept as **the input that made it** rather than as the
 * dabs it made or the pixels those dabs painted.
 *
 * `Stroke`'s own KDoc says it is *"retained only until the commit finishes"*,
 * and gives the reason: keeping the dabs would rebuild the ever-growing scene
 * list the layer bitmap exists to replace. That reason is still right and this
 * type does not contradict it — **what is kept here is the input, not the
 * dabs.** A dab list is the expensive, derived, brush-specific thing; the
 * samples are what the hand did, and they are six numbers at 321.75 Hz rather
 * than six floats per dab at eight dabs a pixel.
 *
 * The arithmetic that decides the shape, measured rather than assumed. Ik0's
 * 300-stroke page of inking is 74 000 samples. As `PenSample` objects that is
 * about 4 MiB and as the unpacked `FloatArray` the bench used it is 1.76 MiB;
 * packed as below it is **661 KiB**, and a thousand strokes is under 2.2 MiB
 * against a device with a measured 4.4 GiB free. That is the difference
 * between a feature with a memory question and one without.
 *
 * ## What is in the packed buffer
 *
 * Nine bytes a sample, after an eight-byte origin:
 *
 * ```
 *   0..3   int32   x of sample 0, in 1/16 document pixels
 *   4..7   int32   y of sample 0, likewise
 *   then, per sample, nine bytes:
 *     0..1  int16  dx from the previous sample, in 1/16 document pixels
 *     2..3  int16  dy
 *     4     uint8  pressure, 0..1 over 0..255
 *     5     uint8  tilt, 0..PI/2 over 0..255
 *     6     uint8  orientation, -PI..PI over 0..255
 *     7..8  uint16 dt from the previous sample, in 1/8 milliseconds
 * ```
 *
 * Big-endian throughout, which is what `DataOutputStream` writes and what a
 * hexdump reads left to right. Sample 0's dx, dy and dt are all zero — five
 * bytes spent to keep every sample the same width, because a special case at
 * index 0 is a special case in the decoder, in the encoder, in the splitter
 * Ik8 adds and in every test of all three. On a 250-sample stroke it is 0.2%.
 *
 * ## The quantisation, and the drift that is not here
 *
 * The naive delta code rounds each difference on its own, and the error then
 * performs a random walk: over a thousand samples it can reach a pixel, and the
 * end of a long stroke lands somewhere the hand did not put it. So the encoder
 * quantises the **absolute** coordinate to 1/16 px and stores the difference of
 * the quantised values. Reconstruction is then exact — the decoder recovers the
 * quantised absolute, not an accumulation — and the total error is one quantum,
 * 1/32 px at worst per sample, whatever the length of the stroke.
 * `StrokeRecordTest` pins that at a thousand samples.
 *
 * Whether 1/16 px is *invisible* in a re-render is a different question from
 * whether it is small, and it is **Ik4's measurement, not this type's claim**.
 * The mask cache buckets sizes at 3% and snaps large dabs to whole pixels, so a
 * 1/32 px shift changes antialiased coverage on small dabs by some amount
 * nobody has counted yet. [StrokeCodec.VERSION] is the escape hatch: a finer
 * fixed point is one constant and a version bump, and old files then refuse to
 * load rather than decoding as a different drawing.
 *
 * The other three channels are quantised against what consumes them rather
 * than against what produces them, which is why a byte is enough:
 *
 * - **Pressure** to 1/255. The pen reports 8192 levels; a 24 px nib moves
 *   0.09 px per step at that resolution, which is a quarter of the mask cache's
 *   own 3% size bucket.
 * - **Tilt** to PI/512 radians, 0.35 degrees.
 * - **Orientation** to 2PI/255, **1.41 degrees — finer than the mask cache's
 *   own rotation bucket**, which is `MaskTolerance.rotationSteps` = 64 over PI,
 *   or 2.81 degrees. So a chisel nib replayed from a record asks the cache for
 *   the same mask it asked for live.
 *
 * ## What is not in here
 *
 * The brush is an **index into the sheet's brush table**, not a library id.
 * Retuning a preset must not reach back and change strokes drawn with it last
 * week — that is `docs/inker-plan.md`'s risk table, and an index into a table
 * of frozen `BrushCodec` text is what answers it.
 *
 * [bounds] is stored rather than derived because it is the *painted* extent,
 * which only the dab loop knows: it is accumulated one dab at a time with that
 * dab's radius, exactly as `MutableBounds.add` requires. Recomputing it from
 * the samples would need the brush, the tilt filter and the scatter, which is
 * to say it would need to re-run the stroke.
 *
 * Immutable, and every edit is a new record. Ik5's undo is then the pair of
 * lists rather than a diff, and Ik8's split is three records replacing one
 * rather than a mutation the undo step has to describe.
 */
class StrokeRecord(
    /**
     * Unique within its sheet, and **ascending in draw order**. The sheet
     * allocates them; nothing here checks it, and `StrokeGrid` relies on it to
     * hand back a query result that is already in the order the strokes are
     * painted.
     */
    val id: Long,
    /** Index into the sheet's brush table. See the note above about why. */
    val brush: Int,
    /** Packed ARGB, the form `Paint.setColor` takes. */
    val colorArgb: Int,
    val erase: Boolean,
    /**
     * The stroke's random seed, and the reason a redraw is the same drawing.
     * See Ik2: the per-dab random becomes a hash of this, the dab index and the
     * channel, so a record renders what it rendered however it is split.
     */
    val seed: Int,
    /**
     * The dab index the first dab of this record carries. Zero for a stroke as
     * drawn, non-zero for the tail half of a split — which is what keeps the
     * grain of a cut stroke where the hand left it.
     */
    val dabBase: Int,
    /**
     * Index into the sheet's clip table, or -1 for the usual case of no
     * selection. A stroke drawn into a selection is clipped pixels, and a
     * record that forgot that would re-render outside the stencil the first
     * time it was touched — silently, and long after the fact.
     */
    val clip: Int,
    /**
     * What this stroke painted, in document space, as the dab loop accumulated
     * it. Neither smaller nor larger than the ink; see [Bounds].
     */
    val bounds: Bounds,
    packed: ByteArray,
    val sampleCount: Int,
) {

    /**
     * Private, and copied in, for the reason `Stroke` gives about its dab
     * array: the live path packs into a reused buffer, and a record aliasing
     * that buffer would go on changing after it was handed over. [writeTo] and
     * [StrokeCodec] reach it internally; nothing outside the module can.
     */
    private val packed: ByteArray = packed.copyOf()

    init {
        require(sampleCount >= 0) { "sampleCount was $sampleCount" }
        require(this.packed.size == StrokeCodec.ORIGIN_BYTES + sampleCount * StrokeCodec.SAMPLE_BYTES) {
            "packed buffer is ${this.packed.size} bytes for $sampleCount samples; " +
                "expected ${StrokeCodec.ORIGIN_BYTES + sampleCount * StrokeCodec.SAMPLE_BYTES}"
        }
    }

    /** What the samples cost in memory. The record's other fields are 48 bytes. */
    val byteCount: Int get() = packed.size

    internal fun bytes(): ByteArray = packed

    /** A copy of the packed buffer, for a caller outside this module. */
    fun copyPackedBytes(): ByteArray = packed.copyOf()

    /**
     * Decode every sample into [out] at [offset], six floats each — x, y,
     * pressure, tilt, orientation, milliseconds from pen-down — and answer how
     * many were written.
     *
     * **There is no `xAt(i)` and there must not be one.** The buffer is delta
     * coded, so an indexed accessor walks from the start every time and a loop
     * over the samples is quadratic in the length of the stroke. That is the
     * kind of defect that never shows up on a test stroke and costs a second on
     * a real drawing, so the shape of the API is what prevents it: the only way
     * to read this buffer is to read all of it, forwards, once.
     *
     * Allocates nothing. The caller owns [out] and can reuse it across every
     * record of a sheet.
     */
    fun decodeInto(out: FloatArray, offset: Int = 0): Int =
        StrokeCodec.unpackSamples(packed, sampleCount, out, offset)

    /** Floats [decodeInto] needs. */
    val floatCount: Int get() = sampleCount * STRIDE

    /**
     * The stroke's duration, from the first sample to the last, in
     * milliseconds. Zero for a record with fewer than two samples.
     */
    val durationMillis: Float get() = StrokeCodec.durationMillisOf(packed, sampleCount)

    /**
     * The centreline this record's ink lies along, simplified, for hit testing.
     *
     * Derived and cached, and dropped by [dropDerived] under memory pressure —
     * a few hundred bytes a stroke, against records that are already kilobytes.
     *
     * [pen] must be the brush this record's [brush] index names. The cache is
     * keyed on identity rather than trusted, so handing a different brush
     * rebuilds rather than silently answering with the old shape; that matters
     * from Ik10, where re-brushing a stroke changes how wide it is and
     * therefore what a tap on it hits.
     */
    fun polyline(pen: Brush): StrokePolyline {
        val held = derived
        if (held != null && derivedPen === pen) return held
        val built = StrokePolyline.of(this, pen)
        derived = built
        derivedPen = pen
        return built
    }

    /**
     * Forget the derived centreline. The next [polyline] rebuilds it.
     *
     * `docs/inker-plan.md`'s risk table names this as the first thing to give
     * back when records grow past 20 MiB in a session, ahead of any cap on the
     * records themselves.
     */
    fun dropDerived() {
        derived = null
        derivedPen = null
    }

    // Not volatile: both fields are written together, the value is a pure
    // function of the record and the pen, and the worst a race can do is build
    // the same polyline twice. A lock here would sit on the hit-test path.
    private var derived: StrokePolyline? = null
    private var derivedPen: Brush? = null

    override fun toString(): String =
        "StrokeRecord(#$id, brush $brush, $sampleCount samples, $byteCount B, $bounds)"

    companion object {

        /**
         * Floats per sample in the **unpacked** form: x, y, pressure, tilt,
         * orientation, milliseconds. The same layout `VectorStress.Record`
         * uses, so Ik0's bench scene feeds [SampleLog] without a conversion.
         */
        const val STRIDE: Int = 6

        /** No clip. The usual value; see [clip]. */
        const val NO_CLIP: Int = -1
    }
}

/**
 * The growing buffer a stroke's samples are appended to while the pen is down,
 * and packed from at pen-up.
 *
 * One per builder, reset rather than reallocated, so a session that has drawn
 * one long stroke allocates nothing for every later one — the same discipline
 * `StrokeBuilder`'s dab buffer keeps, and for the same reason: this is a
 * per-sample path at 321.75 Hz.
 *
 * It is here rather than in `:app` so that the packing and the thing that fills
 * it cannot disagree about the order of the six floats, and so that both are
 * testable without a device.
 *
 * Not thread-safe. One instance, UI thread, for the life of the app.
 */
class SampleLog {

    private var buffer = FloatArray(INITIAL_SAMPLES * StrokeRecord.STRIDE)

    /** Samples appended since the last [reset]. */
    var count: Int = 0
        private set

    /** Back to empty, keeping the buffer. Call at pen-down. */
    fun reset() {
        count = 0
    }

    /**
     * Append one sample, in document space, with [timeMillis] measured from
     * pen-down.
     *
     * Refuses a non-finite value rather than absorbing it, for the reason
     * `StrokeBuilder.add` gives: a NaN here is invisible until the record is
     * packed, and by then nothing points back at the sample that carried it.
     */
    fun add(
        xDoc: Float,
        yDoc: Float,
        pressure: Float,
        tiltRad: Float,
        orientationRad: Float,
        timeMillis: Float,
    ) {
        require(xDoc.isFinite() && yDoc.isFinite()) { "sample $count was ($xDoc, $yDoc)" }
        require(pressure.isFinite() && tiltRad.isFinite() && orientationRad.isFinite()) {
            "sample $count carried a non-finite pressure, tilt or orientation"
        }
        require(timeMillis.isFinite() && timeMillis >= 0f) { "sample $count time was $timeMillis" }
        val needed = (count + 1) * StrokeRecord.STRIDE
        if (needed > buffer.size) {
            check(count < MAX_SAMPLES) {
                "stroke passed $MAX_SAMPLES samples; that is ${MAX_SAMPLES / 321.75f} seconds " +
                    "of pen-down at the digitizer's own rate"
            }
            val grown = FloatArray(minOf(buffer.size * 2, MAX_SAMPLES * StrokeRecord.STRIDE))
            buffer.copyInto(grown, 0, 0, count * StrokeRecord.STRIDE)
            buffer = grown
        }
        val o = count * StrokeRecord.STRIDE
        buffer[o] = xDoc
        buffer[o + 1] = yDoc
        buffer[o + 2] = pressure
        buffer[o + 3] = tiltRad
        buffer[o + 4] = orientationRad
        buffer[o + 5] = timeMillis
        count++
    }

    /** The packed nine-bytes-a-sample buffer a [StrokeRecord] holds. */
    fun pack(): ByteArray = StrokeCodec.packSamples(buffer, count)

    /**
     * The unpacked floats, for a caller that wants the samples as they arrived
     * rather than as they will be stored. Read-only by convention; the buffer
     * is reused.
     */
    internal fun raw(): FloatArray = buffer

    override fun toString(): String = "SampleLog($count samples)"

    companion object {

        /**
         * 512 samples, 12 KB. At 321.75 Hz that is 1.6 seconds — most inking
         * strokes, and the ones it does not cover pay for one growth.
         */
        const val INITIAL_SAMPLES: Int = 512

        /**
         * A loud failure, like `StrokeBuilder.MAX_DABS`. 262 144 samples is
         * thirteen and a half minutes of unbroken pen-down at the measured
         * rate; reaching it means a stroke was never ended, not that somebody
         * drew a long line.
         */
        const val MAX_SAMPLES: Int = 1 shl 18
    }
}
