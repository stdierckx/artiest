package be.thalos.artiest.canvas

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ik0's scene: a page of inking, as the samples that produced it.
 *
 * ## What this is measuring, and what it is not
 *
 * `docs/inker-plan.md` rests on one claim — *"a vector sheet is a raster sheet
 * that kept the strokes that made it, and can therefore rebuild itself"* — and
 * that claim has a price nobody has paid yet: **rebuilding a whole sheet from
 * its records.** Drawing is not the question. The commit path already stamps a
 * stroke into the sheet and appending a record beside it is a few hundred bytes
 * and no pixels. What is new is the re-render, which happens on an edit, an
 * undo, or a zoom settle, and which the plan's first stop condition prices at
 * *"more than a second for 300 pencil strokes and vector sheets are for opaque
 * nibs only"*.
 *
 * So the scene is **the input**, and the run is the dab loop, the scratch
 * buffer and the composite — the real ones. This class generates and holds; it
 * draws nothing. `InkSurfaceView.measureRerender` is the half that has the
 * machinery.
 *
 * ## Why the samples are synthesized rather than recorded
 *
 * A recorded corpus would be more faithful and it would also be one hand on one
 * afternoon. What the measurement needs is a scene that can be *dialled* — 100,
 * 300 and 1000 strokes of comparable shape — so that the number is a curve
 * rather than a point, and so that a later run after a change means something
 * against an earlier one. The generator is deterministic from a seed for the
 * same reason.
 *
 * What it does copy from the hardware is the rate. The DTH-A116 reports at
 * **321.75 Hz** — `StrokeStress`'s own figure, measured — so a sample every
 * 3.108 ms is what the stabilizer and the resampler see. Getting that wrong in
 * either direction changes the dab count, which is what the cost actually
 * tracks.
 *
 * ## What a stroke looks like here
 *
 * An arc, 200 to 700 document pixels long, drawn at roughly 600 px a second, so
 * a stroke is a third of a second to a second and a bit — an inker's mark
 * rather than a painter's sweep. Pressure ramps in over 60 ms, holds, and
 * tapers over the last 80 ms, because a taper is the part of a stroke that
 * makes the most dabs per pixel and a scene without one would flatter the
 * result.
 */
object VectorStress {

    /** The rate the tablet's pen reports at. See `StrokeStress`. */
    const val SAMPLE_HZ: Float = 321.75f

    /** Floats per sample: x, y, pressure, tilt, orientation, time in ms. */
    const val STRIDE: Int = 6

    /**
     * One stroke's input, in document space and milliseconds from pen-down.
     *
     * A flat `FloatArray` rather than a list of samples, for the reason the
     * plan gives about `StrokeRecord`: a thousand strokes of objects is tens of
     * megabytes of the thing being measured. This is 24 bytes a sample where
     * Ik1's packed form will be nine, and [packedBytes] reports what that form
     * would cost so the two can be compared before it exists.
     */
    class Record(val samples: FloatArray, val count: Int) {
        fun x(i: Int): Float = samples[i * STRIDE]
        fun y(i: Int): Float = samples[i * STRIDE + 1]
        fun pressure(i: Int): Float = samples[i * STRIDE + 2]
        fun tilt(i: Int): Float = samples[i * STRIDE + 3]
        fun orientation(i: Int): Float = samples[i * STRIDE + 4]
        fun timeMillis(i: Int): Float = samples[i * STRIDE + 5]

        /** Bytes this form costs. */
        val bytes: Int get() = count * STRIDE * 4

        /** Bytes `StrokeCodec`'s nine-byte packed form is predicted to cost. */
        val packedBytes: Int get() = count * 9
    }

    /**
     * [strokes] arcs over a [widthPx] by [heightPx] page, from [seed].
     *
     * Laid out on a loose grid with jitter rather than at random, because a
     * uniform random scatter clumps, and a clump is a worse test than an even
     * spread: the scratch buffer's cost is per stroke bounds, and overlapping
     * bounds is the cheap case rather than the dear one.
     */
    fun scene(
        strokes: Int,
        widthPx: Int,
        heightPx: Int,
        seed: Int = 1,
    ): List<Record> {
        var state = if (seed == 0) 1 else seed
        fun rnd(): Float {
            // xorshift32, so the scene is the same on every device and in every
            // process. `Random` would do, and would also be a second thing to
            // pin when a golden moves.
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return ((state ushr 8) and 0xFFFFFF) / 16_777_216f
        }

        val columns = Math.max(1, Math.round(Math.sqrt(strokes.toDouble())).toInt())
        val rows = (strokes + columns - 1) / columns
        val cellW = widthPx.toFloat() / columns
        val cellH = heightPx.toFloat() / rows
        val out = ArrayList<Record>(strokes)
        for (i in 0 until strokes) {
            val cx = (i % columns + 0.5f) * cellW
            val cy = (i / columns + 0.5f) * cellH
            out.add(arc(cx, cy, ::rnd))
        }
        return out
    }

    /** Total bytes a scene costs in this form, and in the packed one. */
    fun bytesOf(records: List<Record>): Pair<Int, Int> {
        var raw = 0
        var packed = 0
        for (r in records) {
            raw += r.bytes
            packed += r.packedBytes
        }
        return raw to packed
    }

    fun samplesOf(records: List<Record>): Int {
        var n = 0
        for (r in records) n += r.count
        return n
    }

    /** One arc through [cx],[cy], as samples. */
    private fun arc(cx: Float, cy: Float, rnd: () -> Float): Record {
        val length = 200f + rnd() * 500f
        val heading = rnd() * TWO_PI
        // How far the arc bends over its length, in radians. Zero is a ruled
        // line and nothing a hand draws is one.
        val bend = (rnd() - 0.5f) * 2.2f
        val speed = 450f + rnd() * 300f          // document px per second
        val millis = length / speed * 1000f
        val dt = 1000f / SAMPLE_HZ
        val count = Math.max(8, (millis / dt).toInt())
        val samples = FloatArray(count * STRIDE)

        val hold = 0.55f + rnd() * 0.35f
        val tiltBase = 0.20f + rnd() * 0.45f
        var x = cx - cos(heading) * length * 0.5f
        var y = cy - sin(heading) * length * 0.5f
        var angle = heading - bend * 0.5f
        val step = length / count
        var prevX = x
        var prevY = y
        for (i in 0 until count) {
            val t = i / (count - 1f)
            val timeMillis = i * dt
            // Onset and taper in wall-clock milliseconds, which is what
            // `Brush.onsetMillis` is measured in, rather than in stroke
            // fraction — a short stroke is nearly all onset and taper, and
            // that is a real property of a short stroke.
            val onset = (timeMillis / 60f).coerceAtMost(1f)
            val left = millis - timeMillis
            val taper = (left / 80f).coerceAtMost(1f)
            // A slow wobble, so pressure is never flat: a flat stroke shares
            // one size bucket and would make the mask cache look better than
            // it is.
            val wobble = 1f + 0.08f * sin(t * 11f)
            val pressure = (hold * onset * taper * wobble).coerceIn(0.02f, 1f)

            val o = i * STRIDE
            samples[o] = x
            samples[o + 1] = y
            samples[o + 2] = pressure
            samples[o + 3] = tiltBase + 0.12f * sin(t * 3f)
            // Orientation follows the direction of travel, which is what the
            // hand does with a chisel nib and what `Sensor.ORIENTATION` reads.
            samples[o + 4] = atan2(y - prevY, x - prevX)
            samples[o + 5] = timeMillis

            prevX = x
            prevY = y
            angle += bend / count
            x += cos(angle) * step
            y += sin(angle) * step
        }
        return Record(samples, count)
    }

    private const val TWO_PI = (PI * 2).toFloat()
}
