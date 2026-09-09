package be.thalos.artiest.engine.brush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushPresetTest {

    /**
     * The pen has to stay exactly Phase 1's brush, or the phase has taxed the
     * one tool it was not supposed to touch. This is the assertion behind that
     * claim, and it is what the dab goldens would fail on next.
     */
    @Test
    fun `the pen is the default brush, untouched by the phase`() {
        val pen = BrushPreset.PEN.create()
        val default = Brush()
        assertEquals(default.sizeMin, pen.sizeMin)
        assertEquals(default.sizeMax, pen.sizeMax)
        assertEquals(default.spacing, pen.spacing)
        assertEquals(default.sizeCurve, pen.sizeCurve)
        assertEquals(1f, pen.opacity)
        assertEquals(1f, pen.flow)
        assertEquals(1f, pen.hardness)
        assertFalse(pen.grain.isActive)
        assertFalse(pen.hasShapeDynamics)
    }

    /** And therefore stays on the direct path, which is where its speed lives. */
    @Test
    fun `the pen needs nothing the scratch buffer provides`() {
        val pen = BrushPreset.PEN.create()
        val indirect = pen.opacity < 1f || pen.flow < 1f || pen.hardness < 1f || pen.grain.isActive
        assertFalse(indirect, "the pen would be routed through the scratch buffer")
    }

    @Test
    fun `the pencil is translucent, textured and tilt-shaped`() {
        val p = BrushPreset.PENCIL.create()
        assertTrue(p.flow < 1f, "flow is what makes hatching build")
        assertTrue(p.opacity < 1f, "opacity is the ceiling it builds toward")
        assertTrue(p.flow < p.opacity, "low flow under a high ceiling, not the other way round")
        assertTrue(p.grain.isActive)
        assertTrue(p.hasShapeDynamics)
        assertTrue(p.hardness < 1f, "a pencil's mark has no crisp boundary")
    }

    /** The claim the preset is built on: one tool, both of the reference's postures. */
    @Test
    fun `the pencil is round upright and flat laid over`() {
        val p = BrushPreset.PENCIL.create()
        val c = DabContext()
        c.tiltRad = 0f
        assertEquals(1f, p.aspect.valueFor(c), 1e-4f, "upright")
        c.tiltRad = Sensor.TILT_MAX_RAD
        assertTrue(p.aspect.valueFor(c) < 0.4f, "laid over it should flatten")
    }

    /** Switching presets must not leave the previous one's dynamics behind. */
    @Test
    fun `applying a preset clears what the last one set`() {
        val brush = BrushPreset.PENCIL.create()
        assertTrue(brush.hasShapeDynamics)
        BrushPreset.PEN.applyTo(brush)
        assertFalse(brush.hasShapeDynamics, "the pencil's tilt survived a switch to the pen")
        assertFalse(brush.grain.isActive)
        assertEquals(1f, brush.opacity)
        assertEquals(0, brush.aspect.inputCount)
        assertEquals(0, brush.rotation.inputCount)
    }

    /** Applied twice, a preset must not stack its own sensors. */
    @Test
    fun `applying a preset twice is the same as applying it once`() {
        val a = BrushPreset.PENCIL.create()
        val b = BrushPreset.PENCIL.create().also { BrushPreset.PENCIL.applyTo(it) }
        assertEquals(a.aspect.inputCount, b.aspect.inputCount)
        assertEquals(a.rotation.inputCount, b.rotation.inputCount)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun `there are two presets and neither is a marker`() {
        assertEquals(listOf("Pen", "Pencil"), BrushPreset.entries.map { it.label })
    }
}
