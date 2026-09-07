package be.thalos.artiest.engine.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the mirrored constants against literals.
 *
 * This cannot be the whole check and does not pretend to be: `MotionEvent` is
 * invisible here by construction, so the assertion that catches drift between
 * the mirror and the platform lives in `:app`. What this catches is the typo
 * the ordering invites — MOUSE at 3 sits between STYLUS at 2 and ERASER at 4,
 * so a mirror written down in the order the names suggest swaps the last two
 * and then classifies the pen as a mouse on device, with no exception anywhere.
 */
class ToolTypeTest {

    @Test
    fun `the mirrored tool types are the values android jar reports`() {
        assertEquals(0, ToolType.UNKNOWN)
        assertEquals(1, ToolType.FINGER)
        assertEquals(2, ToolType.STYLUS)
        // The two that a sequentially guessed mirror swaps.
        assertEquals(3, ToolType.MOUSE)
        assertEquals(4, ToolType.ERASER)
    }

    @Test
    fun `no two tool types collide`() {
        val all = listOf(ToolType.UNKNOWN, ToolType.FINGER, ToolType.STYLUS, ToolType.MOUSE, ToolType.ERASER)
        assertEquals(all.size, all.toSet().size, "duplicate tool type value in $all")
        assertNotEquals(ToolType.MOUSE, ToolType.ERASER)
    }

    /**
     * The pen's back reports FINGER, measured in Phase 0, so a flipped pen
     * lands in the rejected bucket. That is the correct outcome — this pen has
     * no eraser end — and the test exists so the next person to look for
     * flip-to-erase finds the decision instead of reading the finger rule as a
     * bug and "fixing" it into an eraser path the hardware cannot support.
     */
    @Test
    fun `the pen back reports FINGER and is classed as a gesture contact, not an eraser`() {
        assertEquals(ToolClass.FINGER, toolClassOf(ToolType.FINGER))
    }

    @Test
    fun `only stylus and eraser draw`() {
        assertEquals(ToolClass.PEN, toolClassOf(ToolType.STYLUS))
        assertEquals(ToolClass.PEN, toolClassOf(ToolType.ERASER))
        assertEquals(ToolClass.FINGER, toolClassOf(ToolType.MOUSE))
        assertEquals(ToolClass.FINGER, toolClassOf(ToolType.UNKNOWN))
        // An unrecognised digitizer pans rather than drawing a pressureless line.
        assertEquals(ToolClass.FINGER, toolClassOf(99))
    }

    @Test
    fun `an unknown tool type still names itself`() {
        assertEquals("STYLUS", toolTypeName(ToolType.STYLUS))
        assertEquals("TOOL_99", toolTypeName(99))
    }
}
