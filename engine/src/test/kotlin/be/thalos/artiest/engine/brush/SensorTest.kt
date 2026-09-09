package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensorTest {

    private fun ctx(block: DabContext.() -> Unit) = DabContext().apply(block)

    @Test
    fun `pressure passes straight through`() {
        assertEquals(0.4f, Sensor.PRESSURE.read(ctx { pressure = 0.4f }))
    }

    /** The plan's number: 63 degrees is full scale, and past it saturates. */
    @Test
    fun `tilt is normalised over sixty three degrees and saturates above it`() {
        assertEquals(0f, Sensor.TILT.read(ctx { tiltRad = 0f }))
        assertEquals(1f, Sensor.TILT.read(ctx { tiltRad = Sensor.TILT_MAX_RAD }), 1e-6f)
        assertEquals(0.5f, Sensor.TILT.read(ctx { tiltRad = Sensor.TILT_MAX_RAD / 2f }), 1e-6f)
        assertEquals(1f, Sensor.TILT.read(ctx { tiltRad = (PI / 2).toFloat() }), "flat to the glass")
    }

    @Test
    fun `orientation and direction map minus pi to zero and plus pi to one`() {
        for (s in listOf(Sensor.ORIENTATION, Sensor.DIRECTION)) {
            val lo = ctx { orientationRad = -PI.toFloat(); directionRad = -PI.toFloat() }
            val mid = ctx { orientationRad = 0f; directionRad = 0f }
            val hi = ctx { orientationRad = PI.toFloat(); directionRad = PI.toFloat() }
            assertEquals(0f, s.read(lo), 1e-6f, "$s at -PI")
            assertEquals(0.5f, s.read(mid), 1e-6f, "$s at 0")
            assertEquals(1f, s.read(hi), 1e-6f, "$s at PI")
        }
    }

    @Test
    fun `speed saturates at its reference`() {
        assertEquals(0f, Sensor.SPEED.read(ctx { speedDocPxPerMs = 0f }))
        assertEquals(0.5f, Sensor.SPEED.read(ctx {
            speedDocPxPerMs = Sensor.SPEED_MAX_DOC_PX_PER_MS / 2f
        }), 1e-6f)
        assertEquals(1f, Sensor.SPEED.read(ctx { speedDocPxPerMs = 100f }))
    }

    @Test
    fun `time is normalised over a second`() {
        assertEquals(0.25f, Sensor.TIME.read(ctx { elapsedMillis = 250f }), 1e-6f)
        assertEquals(1f, Sensor.TIME.read(ctx { elapsedMillis = 5000f }))
    }

    /**
     * Every sensor has to answer in 0..1 whatever it is handed, because
     * [CurveOption] multiplies them together without checking.
     */
    @Test
    fun `no sensor can return a value outside zero to one`() {
        val hostile = ctx {
            pressure = 5f
            speedDocPxPerMs = -3f
            tiltRad = 100f
            orientationRad = 50f
            directionRad = -50f
            distance = Float.NaN
            elapsedMillis = -1f
            randomDab = 2f
            randomStroke = Float.NEGATIVE_INFINITY
        }
        for (s in Sensor.entries) {
            val v = s.read(hostile)
            assertTrue(v in 0f..1f, "$s returned $v")
        }
    }

    @Test
    fun `reset clears every field`() {
        val c = ctx { pressure = 1f; tiltRad = 1f; randomStroke = 1f; elapsedMillis = 9f }
        c.reset()
        for (s in Sensor.entries) {
            val expected = if (s == Sensor.ORIENTATION || s == Sensor.DIRECTION) 0.5f else 0f
            assertEquals(expected, s.read(c), 1e-6f, "$s after reset")
        }
    }
}
