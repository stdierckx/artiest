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

    /**
     * The list is short on purpose and grows only when a tool is asked for by
     * name — see [BrushPreset]'s header. The marker was refused twice on the
     * grounds that a tilted pencil already did the job, and arrived when it was
     * wanted for its own sake; this is the line that has to be edited
     * deliberately for a fourth.
     */
    @Test
    fun `the preset list is exactly the tools that were asked for`() {
        assertEquals(listOf("Pen", "Pencil", "Marker"), BrushPreset.entries.map { it.label })
    }

    /**
     * What makes the marker a different tool rather than a wide pencil, in the
     * three numbers that carry it.
     *
     * A constant aspect with no sensor is the wedge; a flow floor at 0.94 is
     * the flat pass; a grain strength of zero is ink rather than graphite. Any
     * one of them drifting turns this back into the fat pencil the preset list
     * spent two drafts refusing.
     */
    @Test
    fun `the marker is a wedge that lays flat colour and has no tooth`() {
        val m = BrushPreset.MARKER.create()
        assertEquals(0, m.aspect.inputCount, "the wedge follows a sensor")
        assertEquals(m.aspect.min, m.aspect.max, "the wedge changes shape")
        assertTrue(m.aspect.max < 0.5f, "the nib is not a wedge at ${m.aspect.max}")
        assertTrue(m.flowOption.min > 0.9f, "one pass is not flat: floor ${m.flowOption.min}")
        assertTrue(m.opacity < 0.85f, "overlapping strokes will not show at ${m.opacity}")
        assertEquals(0f, m.grain.strength, "a marker floods the tooth")
        assertEquals(0f, m.burnish)
        // The barrel turns the wedge, and nothing else does.
        assertEquals(1, m.rotation.inputCount)
        assertEquals(Sensor.ORIENTATION, m.rotation.sensorAt(0))
    }
}
