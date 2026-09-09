package be.thalos.artiest.engine.brush

import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * W9: the dab that follows the pen's attitude rather than only its pressure.
 *
 * The pencil this phase exists to build is defined by these: laid over, the
 * contact patch flattens and turns to follow the barrel. Everything here goes
 * through W1's [CurveOption] machinery, so the test is as much that the
 * machinery reaches the dab as that the arithmetic is right.
 */
class ShapeDynamicsTest {

    private fun sample(x: Float, y: Float, tilt: Float, orient: Float, i: Int) = PenSample(
        x = x, y = y, pressure = 0.6f,
        tilt = tilt, orientation = orient, distance = 0f,
        toolType = ToolType.STYLUS, buttonState = 0,
        eventTimeNanos = i * 3_000_000L,
        source = PenSample.Source.CURRENT,
    )

    private fun draw(brush: Brush, tilt: Float, orient: Float, n: Int = 60) =
        StrokeBuilder(brush).run {
            begin(0xFF000000.toInt())
            for (i in 0 until n) add(sample(10f + i * 4f, 50f, tilt, orient, i))
            end()
        }

    private fun pencil(): Brush = Brush().also {
        it.aspect.min = 1f
        it.aspect.max = 0.25f
        it.aspect.drive(Sensor.TILT)
        it.rotation.min = 0f
        it.rotation.max = MaskSpec.PI_F
        it.rotation.drive(Sensor.ORIENTATION)
    }

    /** The pen must be untouched, or the dab goldens would have moved. */
    @Test
    fun `a brush with no shape dynamics lays circles`() {
        val brush = Brush()
        assertFalse(brush.hasShapeDynamics)
        val s = draw(brush, tilt = 1.0f, orient = 0.5f)
        for (i in 0 until s.dabCount) {
            assertEquals(1f, s.aspect(i), "dab $i was not round")
            assertEquals(0f, s.rotation(i), "dab $i was rotated")
        }
    }

    @Test
    fun `an upright pencil is round and a laid-over one is flat`() {
        val upright = draw(pencil(), tilt = 0f, orient = 0f)
        val laid = draw(pencil(), tilt = Sensor.TILT_MAX_RAD, orient = 0f)
        assertEquals(1f, upright.aspect(upright.dabCount - 1), 0.02f)
        assertEquals(0.25f, laid.aspect(laid.dabCount - 1), 0.02f)
    }

    /**
     * Compared as shapes, not as raw numbers, because an ellipse at 0 and one
     * at PI are the same ellipse. Orientation is filtered through a unit vector
     * and read back with `atan2`, so an input of -PI legitimately comes back as
     * +PI — the same angle, the opposite end of the range, and a test that
     * asserted on the number alone would fail on a correct result.
     */
    @Test
    fun `the dab turns with the barrel`() {
        fun angleOf(orient: Float): Float {
            val s = draw(pencil(), tilt = Sensor.TILT_MAX_RAD, orient = orient)
            return MaskSpec.foldRotation(s.rotation(s.dabCount - 1))
        }
        assertEquals(MaskSpec.PI_F / 2f, angleOf(0f), 0.05f, "barrel pointing along +x")
        assertEquals(MaskSpec.PI_F * 3f / 4f, angleOf(PI.toFloat() / 2f), 0.05f)
        // Half a turn of the barrel is the same ellipse. Compared with a
        // mod-PI distance rather than by folding both and subtracting: fold has
        // its own discontinuity, and 3.1415925 and 0 are a hair apart as angles
        // while being the two ends of the folded range.
        val gap = kotlin.math.abs(angleOf(-PI.toFloat()) - angleOf(PI.toFloat()))
        assertTrue(
            minOf(gap, MaskSpec.PI_F - gap) < 0.05f,
            "-PI and +PI gave different ellipses, ${angleOf(-PI.toFloat())} against ${angleOf(PI.toFloat())}",
        )
    }

    /**
     * The filter earns its place here. Raw tilt jitters sample to sample, and a
     * dab shape that follows it directly shimmers while the pen is held still.
     */
    @Test
    fun `tilt noise does not reach the dab`() {
        val brush = pencil()
        val b = StrokeBuilder(brush)
        b.begin(0xFF000000.toInt())
        for (i in 0 until 80) {
            // A steady 0.8 rad with a sample-to-sample wobble.
            val wobble = if (i % 2 == 0) 0.12f else -0.12f
            b.add(sample(10f + i * 4f, 50f, 0.8f + wobble, 0f, i))
        }
        val s = b.end()
        var maxStep = 0f
        for (i in s.dabCount / 2 until s.dabCount) {
            val d = kotlin.math.abs(s.aspect(i) - s.aspect(i - 1))
            if (d > maxStep) maxStep = d
        }
        assertTrue(maxStep < 0.02f, "aspect jumped by $maxStep between neighbouring dabs")
    }

    @Test
    fun `scatter throws dabs off the path and is off by default`() {
        val straight = draw(Brush(), 0f, 0f)
        for (i in 0 until straight.dabCount) {
            assertEquals(50f, straight.y(i), 1e-4f, "the default brush scattered dab $i")
        }
        val scattered = Brush().also { it.scatter.min = 4f; it.scatter.max = 4f }
        assertTrue(scattered.hasShapeDynamics)
        val s = draw(scattered, 0f, 0f)
        var off = 0
        for (i in 0 until s.dabCount) if (kotlin.math.abs(s.y(i) - 50f) > 0.5f) off++
        assertTrue(off > s.dabCount / 2, "only $off of ${s.dabCount} dabs moved")
    }

    @Test
    fun `size jitter only ever shrinks a dab`() {
        val plain = draw(Brush(), 0f, 0f)
        val jittery = Brush().also { it.sizeJitter.min = 0.5f; it.sizeJitter.max = 0.5f }
        val s = draw(jittery, 0f, 0f)
        val biggest = (0 until plain.dabCount).maxOf { plain.radius(it) }
        for (i in 0 until s.dabCount) {
            assertTrue(s.radius(i) <= biggest + 1e-4f, "dab $i grew to ${s.radius(i)}")
        }
        assertTrue(
            (0 until s.dabCount).any { s.radius(it) < biggest * 0.9f },
            "nothing shrank",
        )
    }

    /**
     * The goldens depend on it: a stroke replayed from the same samples must
     * produce the same dabs, so the randomness cannot come from a shared global
     * generator whose state depends on what else in the process drew first.
     */
    @Test
    fun `randomness is per stroke and reproducible`() {
        val brush = Brush().also { it.scatter.min = 6f; it.scatter.max = 6f }
        val a = draw(brush, 0f, 0f)
        val b = draw(brush.copy(), 0f, 0f)
        for (i in 0 until a.dabCount) {
            assertEquals(a.y(i), b.y(i), "a replay diverged at dab $i")
        }
        // Two strokes from one builder must differ, or the scatter is a
        // fixed pattern stamped on every stroke.
        val builder = StrokeBuilder(brush)
        fun once(): FloatArray {
            builder.begin(0xFF000000.toInt())
            for (i in 0 until 40) builder.add(sample(10f + i * 4f, 50f, 0f, 0f, i))
            val s = builder.end()
            return FloatArray(s.dabCount) { s.y(it) }
        }
        assertNotEquals(once().toList(), once().toList())
    }

    /** An ellipse squashed to nothing is a line, and its mask would be empty. */
    @Test
    fun `aspect is floored so a dab cannot vanish`() {
        val extreme = Brush().also {
            it.aspect.min = 1f
            it.aspect.max = -5f
            it.aspect.drive(Sensor.TILT)
        }
        val s = draw(extreme, tilt = Sensor.TILT_MAX_RAD, orient = 0f)
        for (i in 0 until s.dabCount) {
            assertTrue(s.aspect(i) >= StrokeBuilder.ASPECT_MIN, "dab $i had aspect ${s.aspect(i)}")
        }
    }

    /** Spacing follows the minor axis, so a flat dab lays more dabs, not fewer. */
    @Test
    fun `a flattened dab is spaced by its narrow axis`() {
        val round = draw(Brush(), 0f, 0f)
        val flat = draw(pencil(), tilt = Sensor.TILT_MAX_RAD, orient = 0f)
        assertTrue(
            flat.dabCount > round.dabCount,
            "flat laid ${flat.dabCount} dabs against round's ${round.dabCount}",
        )
    }
}
