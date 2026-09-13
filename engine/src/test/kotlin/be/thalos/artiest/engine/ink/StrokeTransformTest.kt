package be.thalos.artiest.engine.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic behind Ik9, without a device.
 *
 * A moved stroke is the same hand movement somewhere else. The channel that
 * makes that more than a slogan is **orientation**: it is the pen's azimuth on
 * the page, so turning a stroke has to turn it, and a chisel nib that did not
 * would draw a different mark in its new place.
 */
class StrokeTransformTest {

    /** `android.graphics.Matrix.getValues` order, built by hand. */
    private fun affine(
        scaleX: Float = 1f,
        skewX: Float = 0f,
        transX: Float = 0f,
        skewY: Float = 0f,
        scaleY: Float = 1f,
        transY: Float = 0f,
    ) = floatArrayOf(scaleX, skewX, transX, skewY, scaleY, transY, 0f, 0f, 1f)

    private fun rotation(radians: Float, cx: Float = 0f, cy: Float = 0f): FloatArray {
        val c = cos(radians)
        val s = sin(radians)
        return floatArrayOf(
            c, -s, cx - c * cx + s * cy,
            s, c, cy - s * cx - c * cy,
            0f, 0f, 1f,
        )
    }

    private fun record(n: Int = 40, orientation: Float = 0.4f): StrokeRecord {
        val log = SampleLog()
        for (i in 0 until n) {
            log.add(100f + i * 5f, 300f, 0.8f, 0.3f, orientation, i * 3.1f)
        }
        return StrokeRecord(
            id = 1L, brush = 2, colorArgb = 0xFF102030.toInt(), erase = true,
            seed = 99, dabBase = 17, clip = 3, bounds = Bounds.of(0f, 0f, 10f, 10f),
            packed = log.pack(), sampleCount = n,
        )
    }

    private fun samplesOf(r: StrokeRecord) = FloatArray(r.floatCount).also { r.decodeInto(it) }

    @Test
    fun `a translation moves the ink and nothing else`() {
        val a = record()
        val before = samplesOf(a)
        val b = StrokeTransform.mapped(a, affine(transX = 40f, transY = -12f), 9L, 5, Bounds.EMPTY)
        val after = samplesOf(b)
        for (i in 0 until a.sampleCount) {
            val o = i * StrokeRecord.STRIDE
            assertEquals(before[o] + 40f, after[o], 0.1f)
            assertEquals(before[o + 1] - 12f, after[o + 1], 0.1f)
            assertEquals(before[o + 2], after[o + 2], 0.01f)
            assertEquals(before[o + 3], after[o + 3], 0.01f)
            assertEquals(before[o + 4], after[o + 4], 0.02f)
            assertEquals(before[o + 5], after[o + 5], 0.2f)
        }
    }

    @Test
    fun `every field but the samples, the id, the brush and the bounds is the parent's`() {
        val a = record()
        val b = StrokeTransform.mapped(a, affine(transX = 1f), 9L, 5, Bounds.of(1f, 2f, 3f, 4f))
        assertEquals(9L, b.id)
        assertEquals(5, b.brush)
        assertEquals(Bounds.of(1f, 2f, 3f, 4f), b.bounds)
        assertEquals(a.colorArgb, b.colorArgb)
        assertEquals(a.erase, b.erase)
        assertEquals(a.seed, b.seed)
        assertEquals(a.dabBase, b.dabBase)
        assertEquals(a.clip, b.clip)
        assertEquals(a.sampleCount, b.sampleCount)
    }

    @Test
    fun `a turn turns the pen's azimuth and leaves the tilt alone`() {
        val a = record(orientation = 0.4f)
        val quarter = (PI / 2).toFloat()
        val b = StrokeTransform.mapped(a, rotation(quarter, 200f, 300f), 2L, 0, Bounds.EMPTY)
        val after = samplesOf(b)
        for (i in 0 until a.sampleCount) {
            val o = i * StrokeRecord.STRIDE
            assertEquals(0.4f + quarter, after[o + 4], 0.03f)
            assertEquals(0.3f, after[o + 3], 0.01f, "tilt is not a page angle")
        }
    }

    /**
     * Orientation is stored in -PI..PI, so a turn that carries it past the end
     * has to come back round rather than being clamped — a chisel nib clamped
     * at PI would stop turning half way through a rotation.
     */
    @Test
    fun `an azimuth past the half turn wraps rather than clamping`() {
        val a = record(orientation = 2.8f)
        val b = StrokeTransform.mapped(a, rotation(1.0f), 2L, 0, Bounds.EMPTY)
        val got = samplesOf(b)[4]
        val want = 2.8f + 1.0f - (2 * PI).toFloat()
        assertEquals(want, got, 0.03f, "orientation was clamped at PI")
        assertTrue(got >= -PI.toFloat() && got <= PI.toFloat())
    }

    @Test
    fun `the scale is the geometric mean of the two axes`() {
        assertEquals(1f, StrokeTransform.scaleOf(affine()), 1e-4f)
        assertEquals(2f, StrokeTransform.scaleOf(affine(scaleX = 2f, scaleY = 2f)), 1e-4f)
        assertEquals(2f, StrokeTransform.scaleOf(affine(scaleX = 4f, scaleY = 1f)), 1e-3f)
        assertEquals(1f, StrokeTransform.scaleOf(rotation(0.7f)), 1e-3f)
        // A degenerate matrix answers 1 rather than 0: a nib scaled to nothing
        // is a stroke that vanishes, which is worse than one that does not
        // resize.
        assertEquals(1f, StrokeTransform.scaleOf(affine(scaleX = 0f, scaleY = 0f)))
    }

    @Test
    fun `the rotation is the angle the matrix turns by`() {
        assertEquals(0f, StrokeTransform.rotationOf(affine()), 1e-4f)
        assertEquals(0.7f, StrokeTransform.rotationOf(rotation(0.7f)), 1e-3f)
        assertEquals(0f, StrokeTransform.rotationOf(affine(scaleX = 3f, scaleY = 3f)), 1e-4f)
    }

    @Test
    fun `identity is decided at a tenth of a pixel`() {
        assertTrue(StrokeTransform.isIdentity(affine()))
        assertTrue(StrokeTransform.isIdentity(affine(transX = 0.05f)))
        assertFalse(StrokeTransform.isIdentity(affine(transX = 0.5f)))
        assertFalse(StrokeTransform.isIdentity(affine(scaleX = 1.01f)))
        assertFalse(StrokeTransform.isIdentity(rotation(0.01f)))
    }

    @Test
    fun `a matrix that is not nine floats is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StrokeTransform.mapped(record(), FloatArray(6), 1L, 0, Bounds.EMPTY)
        }
        assertFailsWith<IllegalArgumentException> { StrokeTransform.scaleOf(FloatArray(4)) }
    }

    @Test
    fun `a stroke moved and moved back lands where it started`() {
        val a = record()
        val before = samplesOf(a)
        val out = StrokeTransform.mapped(a, affine(transX = 250f, transY = 130f), 2L, 0, Bounds.EMPTY)
        val back = StrokeTransform.mapped(out, affine(transX = -250f, transY = -130f), 3L, 0, Bounds.EMPTY)
        val after = samplesOf(back)
        var worst = 0f
        for (i in before.indices step StrokeRecord.STRIDE) {
            worst = maxOf(worst, abs(before[i] - after[i]), abs(before[i + 1] - after[i + 1]))
        }
        // One quantum, not two: the packing quantises the *absolute*
        // coordinate, so a round trip through it does not accumulate.
        assertTrue(worst <= StrokeCodec.QUANTUM_DOC, "drifted $worst px there and back")
    }
}
