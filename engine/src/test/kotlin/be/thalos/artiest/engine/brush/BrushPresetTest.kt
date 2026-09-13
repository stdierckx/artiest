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
        assertEquals(
            listOf("Pen", "Pencil", "Marker", "Hard eraser", "Soft eraser"),
            BrushPreset.entries.map { it.label },
        )
    }

    /**
     * The two erasers, in the three facts that make them two tools.
     *
     * `erase` on both, because that is the whole of what makes one an eraser
     * now that there is no mode to be in. A hard rim and a full-strength pass
     * on the first, so one stroke takes a line out; a soft rim and a flow
     * ceiling under an opacity ceiling on the second, so one stroke fades a
     * passage and two fade it further. If those converge, one of them is a
     * duplicate of the other and the pair is not worth the two cells.
     */
    @Test
    fun `the hard eraser cuts and the soft one fades`() {
        val hard = BrushPreset.HARD_ERASER.create()
        val soft = BrushPreset.SOFT_ERASER.create()
        assertTrue(hard.erase && soft.erase, "an eraser erases because it is one")

        assertEquals(1f, hard.hardness, "the hard one has a rim")
        assertEquals(1f, hard.opacity)
        assertEquals(1f, hard.flow, "and clears in one pass")

        assertTrue(soft.hardness < 0.5f, "the soft one does not: ${soft.hardness}")
        assertTrue(soft.opacity < 1f, "one sweep cannot quite clear: ${soft.opacity}")
        // Soft, not weak. The floor is what makes a feather touch lift a tone
        // instead of clearing it; the gap between floor and ceiling is what
        // makes pressure mean something. An eraser whose ceiling is low needs
        // three sweeps to take a line out and reads as broken — that was the
        // first draft, and the tablet showed it as a uniform grey swatch.
        assertTrue(soft.flowOption.min < 0.1f, "a feather touch must barely lift")
        assertTrue(soft.flow > 0.8f, "and leaning on it must clear: ${soft.flow}")
        assertTrue(soft.sizeMax > hard.sizeMax, "a soft rubber is swept, not aimed")
    }

    /**
     * An eraser's rubber width is its own width.
     *
     * `Brush.modeScale` divides `eraseSizeMax` by `sizeMax` whenever a brush
     * erases, which is right for the barrel button — a pencil rubbing out at
     * rubber width — and must be exactly 1 for a brush that *is* a rubber, or
     * the size slider would scale it twice.
     */
    @Test
    fun `an eraser preset does not scale itself`() {
        for (preset in listOf(BrushPreset.HARD_ERASER, BrushPreset.SOFT_ERASER)) {
            val b = preset.create()
            assertEquals(b.sizeMax, b.eraseSizeMax, "${preset.id} scales itself")
        }
    }

    /**
     * Switching away from an eraser stops the erasing.
     *
     * `reset` did not clear `erase` before, because nothing ever set it. Now
     * two presets do, and a `reset` that left it behind would mean picking the
     * pencil after the eraser hands you a pencil that rubs out — the single
     * most confusing thing this change could have shipped.
     */
    @Test
    fun `a drawing preset takes erasing back off`() {
        val b = BrushPreset.HARD_ERASER.create()
        assertTrue(b.erase)
        for (preset in listOf(BrushPreset.PEN, BrushPreset.PENCIL, BrushPreset.MARKER)) {
            preset.applyTo(b)
            assertFalse(b.erase, "${preset.id} left the rubber on")
        }
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
