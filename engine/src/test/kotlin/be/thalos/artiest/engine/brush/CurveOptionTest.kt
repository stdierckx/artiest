package be.thalos.artiest.engine.brush

import kotlin.test.Test
import kotlin.test.assertEquals

class CurveOptionTest {

    private fun ctx(block: DabContext.() -> Unit) = DabContext().apply(block)

    /**
     * The choice stated in the class header: no sensors means "the value the
     * slider says", not "off".
     */
    @Test
    fun `an option with no sensors is the constant max`() {
        val o = CurveOption(2f, 20f)
        assertEquals(1f, o.combined(DabContext()))
        assertEquals(20f, o.valueFor(DabContext()))
    }

    @Test
    fun `one sensor maps the range`() {
        val o = CurveOption(2f, 20f).drive(Sensor.PRESSURE)
        assertEquals(2f, o.valueFor(ctx { pressure = 0f }))
        assertEquals(11f, o.valueFor(ctx { pressure = 0.5f }))
        assertEquals(20f, o.valueFor(ctx { pressure = 1f }))
    }

    @Test
    fun `multiply needs every sensor high`() {
        val o = CurveOption(0f, 1f, CurveOption.Combine.MULTIPLY)
            .drive(Sensor.PRESSURE)
            .drive(Sensor.RANDOM_DAB)
        assertEquals(0.25f, o.valueFor(ctx { pressure = 0.5f; randomDab = 0.5f }))
        assertEquals(0f, o.valueFor(ctx { pressure = 0f; randomDab = 1f }), "one zero zeroes it")
    }

    @Test
    fun `maximum needs only one sensor high`() {
        val o = CurveOption(0f, 1f, CurveOption.Combine.MAXIMUM)
            .drive(Sensor.PRESSURE)
            .drive(Sensor.RANDOM_DAB)
        assertEquals(0.9f, o.valueFor(ctx { pressure = 0.9f; randomDab = 0.1f }), 1e-6f)
        assertEquals(1f, o.valueFor(ctx { pressure = 0f; randomDab = 1f }))
    }

    @Test
    fun `a curve is applied to the sensor before combining`() {
        val o = CurveOption(0f, 1f).drive(Sensor.PRESSURE, ResponseCurve.CUBIC)
        assertEquals(0.125f, o.valueFor(ctx { pressure = 0.5f }), 1e-6f)
    }

    @Test
    fun `an inverted range still interpolates, so a sensor can shrink a value`() {
        // max below min is legal on purpose: "faster means thinner" is a real
        // brush, and forbidding it would need a second inverted-curve concept.
        val o = CurveOption(20f, 2f).drive(Sensor.SPEED)
        assertEquals(20f, o.valueFor(ctx { speedDocPxPerMs = 0f }))
        assertEquals(2f, o.valueFor(ctx { speedDocPxPerMs = 99f }), "speed saturates at its reference")
    }

    @Test
    fun `valueForFraction bypasses the sensors and clamps`() {
        val o = CurveOption(2f, 20f).drive(Sensor.PRESSURE)
        assertEquals(11f, o.valueForFraction(0.5f))
        assertEquals(2f, o.valueForFraction(-1f))
        assertEquals(20f, o.valueForFraction(2f))
        assertEquals(2f, o.valueForFraction(Float.NaN))
    }

    @Test
    fun `clearing the inputs returns it to a constant`() {
        val o = CurveOption(2f, 20f).drive(Sensor.PRESSURE)
        assertEquals(2f, o.valueFor(DabContext()))
        o.clearInputs()
        assertEquals(0, o.inputCount)
        assertEquals(20f, o.valueFor(DabContext()))
    }
}
