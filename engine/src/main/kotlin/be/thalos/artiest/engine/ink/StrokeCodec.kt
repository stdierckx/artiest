package be.thalos.artiest.engine.ink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * A stroke file said something the decoder cannot act on.
 *
 * Every malformed input throws rather than being skipped or best-guessed, for
 * the reason `TraceFormatException` gives about traces and with more at stake:
 * a lenient decoder turns a corrupt file into a *different drawing*, which then
 * opens, looks plausible, and gets saved back over the original.
 */
class StrokeFormatException(message: String) : RuntimeException(message)

/**
 * The wire format for stroke records, in one place so the writer and the reader
 * cannot disagree about it.
 *
 * Binary, and that is the opposite of what `TraceFormat` chose. The reasoning
 * that made text right there makes it wrong here: a trace is a debugging
 * artifact that is checked into git, diffed, pasted into a bug report and
 * occasionally hand-authored, and it is one recording. A stroke file is
 * **a drawing's contents**, written on `ProjectSaver`'s debounce every few
 * seconds for as long as the app is open, and `TraceRecorder`'s 76 bytes a
 * sample would make a 300-stroke page 5.6 MB rewritten on every save. Nine
 * bytes makes it 661 KB.
 *
 * Two layers, and they are separable on purpose:
 *
 * - [packSamples] and [unpackSamples] are the per-stroke sample buffer, which
 *   is what a [StrokeRecord] holds in memory. Nothing about it is on-disk-only;
 *   the in-memory form and the stored form are the same bytes, so saving a
 *   sheet copies rather than converts.
 * - [encode] and [decode] wrap a list of records in a header. That is Ik6's
 *   `strokes/<n>.ink`.
 *
 * ## The version byte, and what it refuses
 *
 * A file whose version is newer than [VERSION] is **refused, not guessed at**.
 * The failure mode being prevented is specific: a later build widens the fixed
 * point or adds a channel, the user opens the file on an older build, and every
 * stroke decodes into a plausible-looking but wrong position. A drawing that
 * will not open can be opened by updating; a drawing that opens wrong is
 * discovered after it has been saved over.
 *
 * The same file also carries the *pixels* — see `docs/inker-plan.md`, "What Ik6
 * writes, and why the PNG stays" — so refusing the stroke file costs the
 * editability of the strokes, not the drawing.
 */
object StrokeCodec {

    /** Six ASCII bytes at the head of every file. */
    const val MAGIC: String = "ARTINK"

    /**
     * 1. Bumped by any change to the sample packing, the channel set or the
     * record header. Never bumped for a change that only adds an optional
     * trailing field — there are none, and the format has no room for one by
     * design, because "optional" is how a format becomes ambiguous.
     */
    const val VERSION: Int = 1

    /** Bytes before the first sample: two int32 origin coordinates. */
    const val ORIGIN_BYTES: Int = 8

    /** Bytes per sample. See [StrokeRecord]'s layout note. */
    const val SAMPLE_BYTES: Int = 9

    /**
     * The document-space grid positions are snapped to, in pixels.
     *
     * 1/16. With an int16 delta that is a reach of +-2047.9 px between
     * consecutive samples, and the worst case has a sixteen-fold margin on it.
     * The arithmetic, because "surely that is enough" is how a format grows a
     * failure nobody can reproduce:
     *
     * - The gap between two consecutive samples of one stroke is the
     *   digitizer's own period, 3.1 ms at the measured 321.75 Hz. **A stall
     *   does not widen it**: `MotionEvent` carries its history, so a frame lost
     *   to a hitch delivers every sample that happened during it rather than
     *   skipping to the newest.
     * - A pen on glass tops out around 2 m/s, which is about 10 000 view pixels
     *   a second on this panel: 31 view pixels in 3.1 ms.
     * - `CanvasTransform.MIN_SCALE` is 0.25, so one view pixel is at most four
     *   document pixels. 124 document pixels a sample, against 2047.
     *
     * See [packSamples]'s refusal for what happens if that reasoning is ever
     * wrong — it throws rather than clamping, because a clamped step is ink
     * moved somewhere the hand did not put it and nothing downstream would say
     * so.
     */
    const val QUANTUM_DOC: Float = 1f / 16f

    /** Milliseconds per unit of the uint16 time delta. Max gap 8191.875 ms. */
    const val TIME_QUANTUM_MS: Float = 1f / 8f

    /** The largest delta an int16 can carry, in document pixels. */
    const val MAX_STEP_DOC: Float = 32767f / 16f

    /** Tilt's full range, 0 (perpendicular) to flat against the glass. */
    private const val TILT_MAX = (PI / 2.0).toFloat()

    private const val TWO_PI = (PI * 2.0).toFloat()

    private const val PI_F = PI.toFloat()

    // ---------------------------------------------------------------- samples

    /**
     * Pack [count] samples from [samples] — six floats each, in
     * [StrokeRecord.STRIDE] order — into the nine-bytes-a-sample buffer a
     * record holds.
     *
     * The absolute coordinate is quantised first and the *difference of the
     * quantised values* is what is stored, so the decoder recovers the
     * quantised absolute exactly and no error accumulates along the stroke.
     * Rounding each raw difference instead is the obvious implementation and it
     * random-walks; see [StrokeRecord]'s note.
     *
     * Out-of-range inputs are clamped where the range is the channel's own
     * definition — pressure outside 0..1, tilt outside 0..PI/2, orientation
     * outside -PI..PI are all values the physical quantity cannot take, and a
     * clamp there loses nothing. A **position** step beyond [MAX_STEP_DOC] is
     * refused instead, because clamping it would move the ink somewhere the
     * hand did not put it and nothing downstream would ever say so.
     */
    fun packSamples(samples: FloatArray, count: Int, offset: Int = 0): ByteArray {
        require(count >= 0) { "count was $count" }
        require(offset >= 0 && samples.size >= offset + count * StrokeRecord.STRIDE) {
            "need ${count * StrokeRecord.STRIDE} floats at $offset, array holds ${samples.size}"
        }
        val out = ByteArray(ORIGIN_BYTES + count * SAMPLE_BYTES)
        if (count == 0) return out

        var prevX = quantise(samples[offset])
        var prevY = quantise(samples[offset + 1])
        var prevT = quantiseTime(samples[offset + 5])
        putInt(out, 0, prevX)
        putInt(out, 4, prevY)

        for (i in 0 until count) {
            val s = offset + i * StrokeRecord.STRIDE
            val qx = quantise(samples[s])
            val qy = quantise(samples[s + 1])
            val qt = quantiseTime(samples[s + 5])
            val dx = qx - prevX
            val dy = qy - prevY
            val dt = qt - prevT
            require(dx >= -32768 && dx <= 32767 && dy >= -32768 && dy <= 32767) {
                "sample $i jumped ${dx * QUANTUM_DOC}, ${dy * QUANTUM_DOC} document pixels " +
                    "from the one before; the limit is $MAX_STEP_DOC"
            }
            require(dt >= 0 && dt <= 65535) {
                "sample $i is ${dt * TIME_QUANTUM_MS} ms after the one before; " +
                    "time must not go backwards and the gap limit is ${65535 * TIME_QUANTUM_MS} ms"
            }
            val o = ORIGIN_BYTES + i * SAMPLE_BYTES
            putShort(out, o, dx)
            putShort(out, o + 2, dy)
            out[o + 4] = unitByte(samples[s + 2])
            out[o + 5] = rangeByte(samples[s + 3], 0f, TILT_MAX)
            out[o + 6] = rangeByte(samples[s + 4], -PI_F, PI_F)
            out[o + 7] = ((dt ushr 8) and 0xFF).toByte()
            out[o + 8] = (dt and 0xFF).toByte()
            prevX = qx
            prevY = qy
            prevT = qt
        }
        return out
    }

    /**
     * The inverse of [packSamples]: [count] samples out of [packed] into [out]
     * at [offset], six floats each. Answers how many were written.
     */
    fun unpackSamples(packed: ByteArray, count: Int, out: FloatArray, offset: Int = 0): Int {
        require(count >= 0) { "count was $count" }
        require(packed.size >= ORIGIN_BYTES + count * SAMPLE_BYTES) {
            "packed buffer is ${packed.size} bytes, too short for $count samples"
        }
        require(offset >= 0 && out.size >= offset + count * StrokeRecord.STRIDE) {
            "need ${count * StrokeRecord.STRIDE} floats at $offset, array holds ${out.size}"
        }
        if (count == 0) return 0
        var x = getInt(packed, 0)
        var y = getInt(packed, 4)
        var t = 0
        for (i in 0 until count) {
            val o = ORIGIN_BYTES + i * SAMPLE_BYTES
            x += getShort(packed, o)
            y += getShort(packed, o + 2)
            t += ((packed[o + 7].toInt() and 0xFF) shl 8) or (packed[o + 8].toInt() and 0xFF)
            val d = offset + i * StrokeRecord.STRIDE
            out[d] = x * QUANTUM_DOC
            out[d + 1] = y * QUANTUM_DOC
            out[d + 2] = (packed[o + 4].toInt() and 0xFF) / 255f
            out[d + 3] = (packed[o + 5].toInt() and 0xFF) / 255f * TILT_MAX
            out[d + 4] = (packed[o + 6].toInt() and 0xFF) / 255f * TWO_PI - PI_F
            out[d + 5] = t * TIME_QUANTUM_MS
        }
        return count
    }

    /** First sample to last, in milliseconds. Walks the time column only. */
    internal fun durationMillisOf(packed: ByteArray, count: Int): Float {
        if (count < 2) return 0f
        var t = 0
        for (i in 0 until count) {
            val o = ORIGIN_BYTES + i * SAMPLE_BYTES
            t += ((packed[o + 7].toInt() and 0xFF) shl 8) or (packed[o + 8].toInt() and 0xFF)
        }
        return t * TIME_QUANTUM_MS
    }

    // ------------------------------------------------------------------- file

    /**
     * Every record of one sheet, bottom-most first, as the bytes Ik6 writes to
     * `strokes/<n>.ink`.
     *
     * The brush table and the clip table are **not** here. They are the sheet's,
     * shared across its records, and they go in `project.json` beside the rest
     * of the sheet's description — a `BrushCodec` line is text and a clip is a
     * path, and neither belongs in a buffer whose whole argument is that it is
     * nine bytes a sample.
     */
    fun encode(records: List<StrokeRecord>): ByteArray {
        val bytes = ByteArrayOutputStream(1 shl 16)
        val out = DataOutputStream(bytes)
        out.writeBytes(MAGIC)
        out.writeByte(VERSION)
        // Reserved, and checked on read. A zero byte here is the cheapest place
        // to put a flag that has to be understood rather than ignored.
        out.writeByte(0)
        out.writeInt(records.size)
        for (r in records) {
            out.writeLong(r.id)
            out.writeInt(r.brush)
            out.writeInt(r.colorArgb)
            out.writeInt(r.seed)
            out.writeInt(r.dabBase)
            out.writeInt(r.clip)
            out.writeByte(if (r.erase) 1 else 0)
            if (r.bounds.isEmpty) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeFloat(r.bounds.left)
                out.writeFloat(r.bounds.top)
                out.writeFloat(r.bounds.right)
                out.writeFloat(r.bounds.bottom)
            }
            out.writeInt(r.sampleCount)
            out.write(r.bytes())
        }
        out.flush()
        return bytes.toByteArray()
    }

    /**
     * The inverse of [encode]. Throws [StrokeFormatException] for anything it
     * cannot read, including a file that ends early.
     *
     * [maxSamples] bounds what a single record may claim, so a corrupt length
     * field allocates a message rather than a gigabyte. It is [SampleLog]'s own
     * ceiling, which is thirteen minutes of unbroken pen-down.
     */
    fun decode(bytes: ByteArray, maxSamples: Int = SampleLog.MAX_SAMPLES): List<StrokeRecord> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val magic = ByteArray(MAGIC.length)
            input.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) {
                throw StrokeFormatException("not a stroke file: no $MAGIC header")
            }
            val version = input.readUnsignedByte()
            if (version > VERSION) {
                throw StrokeFormatException(
                    "stroke file is version $version; this build reads up to $VERSION"
                )
            }
            if (version < 1) throw StrokeFormatException("stroke file version $version")
            val reserved = input.readUnsignedByte()
            if (reserved != 0) {
                throw StrokeFormatException("stroke file sets reserved byte to $reserved")
            }
            val count = input.readInt()
            if (count < 0) throw StrokeFormatException("stroke file claims $count records")
            val out = ArrayList<StrokeRecord>(minOf(count, 4096))
            for (i in 0 until count) {
                val id = input.readLong()
                val brush = input.readInt()
                val colorArgb = input.readInt()
                val seed = input.readInt()
                val dabBase = input.readInt()
                val clip = input.readInt()
                val erase = input.readUnsignedByte() != 0
                val bounds = when (val tag = input.readUnsignedByte()) {
                    0 -> Bounds.EMPTY
                    1 -> {
                        val l = input.readFloat()
                        val t = input.readFloat()
                        val r = input.readFloat()
                        val b = input.readFloat()
                        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite() ||
                            l > r || t > b
                        ) {
                            throw StrokeFormatException("record $i has bounds ($l, $t, $r, $b)")
                        }
                        Bounds.of(l, t, r, b)
                    }
                    else -> throw StrokeFormatException("record $i has bounds tag $tag")
                }
                val samples = input.readInt()
                if (samples < 0 || samples > maxSamples) {
                    throw StrokeFormatException("record $i claims $samples samples")
                }
                val packed = ByteArray(ORIGIN_BYTES + samples * SAMPLE_BYTES)
                input.readFully(packed)
                out.add(
                    StrokeRecord(
                        id = id,
                        brush = brush,
                        colorArgb = colorArgb,
                        erase = erase,
                        seed = seed,
                        dabBase = dabBase,
                        clip = clip,
                        bounds = bounds,
                        packed = packed,
                        sampleCount = samples,
                    )
                )
            }
            return out
        } catch (e: EOFException) {
            throw StrokeFormatException("stroke file ends early: ${e.message ?: "truncated"}")
        }
    }

    // ------------------------------------------------------------------ bytes

    private fun quantise(v: Float): Int {
        require(v.isFinite()) { "coordinate was $v" }
        val q = Math.round(v.toDouble() / QUANTUM_DOC)
        require(q >= Int.MIN_VALUE && q <= Int.MAX_VALUE) { "coordinate $v is off any page" }
        return q.toInt()
    }

    private fun quantiseTime(millis: Float): Int {
        require(millis.isFinite() && millis >= 0f) { "sample time was $millis" }
        val q = Math.round(millis.toDouble() / TIME_QUANTUM_MS)
        require(q <= Int.MAX_VALUE) { "sample time $millis ms is beyond any stroke" }
        return q.toInt()
    }

    private fun unitByte(v: Float): Byte {
        if (!v.isFinite()) return 0
        return (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
    }

    private fun rangeByte(v: Float, lo: Float, hi: Float): Byte {
        if (!v.isFinite()) return 0
        return ((v.coerceIn(lo, hi) - lo) / (hi - lo) * 255f).roundToInt().toByte()
    }

    private fun putInt(a: ByteArray, at: Int, v: Int) {
        a[at] = (v ushr 24).toByte()
        a[at + 1] = (v ushr 16).toByte()
        a[at + 2] = (v ushr 8).toByte()
        a[at + 3] = v.toByte()
    }

    private fun getInt(a: ByteArray, at: Int): Int =
        ((a[at].toInt() and 0xFF) shl 24) or
            ((a[at + 1].toInt() and 0xFF) shl 16) or
            ((a[at + 2].toInt() and 0xFF) shl 8) or
            (a[at + 3].toInt() and 0xFF)

    private fun putShort(a: ByteArray, at: Int, v: Int) {
        a[at] = (v ushr 8).toByte()
        a[at + 1] = v.toByte()
    }

    /** Sign-extended: the deltas are signed and a zero-extended read drifts. */
    private fun getShort(a: ByteArray, at: Int): Int =
        (((a[at].toInt() and 0xFF) shl 8) or (a[at + 1].toInt() and 0xFF)).toShort().toInt()
}
