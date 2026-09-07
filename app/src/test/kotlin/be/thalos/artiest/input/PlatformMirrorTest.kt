package be.thalos.artiest.input

import android.view.MotionEvent
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.ToolClass
import be.thalos.artiest.engine.input.ToolType
import be.thalos.artiest.engine.input.toolClassOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The half of the tool-type mirror that `:engine` cannot write.
 *
 * `ToolType` exists so the engine can name a tool without seeing `android.*`,
 * and its test there pins the literals. Neither of those catches the failure
 * that matters: a mirror whose values do not match the platform's. That check
 * needs both sides visible at once, so it lives here, and it is the only thing
 * standing between a transposed constant and a pen the app classifies as a
 * mouse — which presents as "the pen draws nothing", with no exception anywhere
 * near the mistake.
 *
 * These are plain JVM tests. Android's int constants are readable without a
 * device, an emulator or Robolectric because the Kotlin compiler folds them
 * against compileSdk's real `android.jar`; calling a MotionEvent *method* here
 * would throw "not mocked", which is why nothing below calls one.
 *
 * What that buys is narrower than it looks: this asserts the mirror matches the
 * SDK the app compiles against, not the SDK on the tablet. The constants have
 * been stable since API 14, so the realistic failure is a typo, and a typo is
 * exactly what this catches.
 */
class PlatformMirrorTest {

    @Test
    fun `every mirrored tool type is the platform's own value`() {
        assertEquals(MotionEvent.TOOL_TYPE_UNKNOWN, ToolType.UNKNOWN)
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, ToolType.FINGER)
        assertEquals(MotionEvent.TOOL_TYPE_STYLUS, ToolType.STYLUS)
        // The one worth writing out. MOUSE sits at 3, between STYLUS at 2 and
        // ERASER at 4, so a mirror typed in the order the names suggest swaps
        // the last two and still compiles.
        assertEquals(MotionEvent.TOOL_TYPE_MOUSE, ToolType.MOUSE)
        assertEquals(MotionEvent.TOOL_TYPE_ERASER, ToolType.ERASER)
    }

    /**
     * The same assertion again, through reflection.
     *
     * The comparisons above are constant-folded on both sides, so they check
     * the source the app was compiled from. This one reads the field out of the
     * `android.jar` the test JVM actually loaded, which is the copy a wrong
     * `compileSdk` or a stale build would show up in.
     */
    @Test
    fun `the mirror still matches when the values are read at runtime`() {
        val platform = MotionEvent::class.java
        for ((name, mirrored) in listOf(
            "TOOL_TYPE_UNKNOWN" to ToolType.UNKNOWN,
            "TOOL_TYPE_FINGER" to ToolType.FINGER,
            "TOOL_TYPE_STYLUS" to ToolType.STYLUS,
            "TOOL_TYPE_MOUSE" to ToolType.MOUSE,
            "TOOL_TYPE_ERASER" to ToolType.ERASER,
        )) {
            assertEquals(platform.getField(name).getInt(null), mirrored, name)
        }
    }

    /**
     * Phase 0 measured the three barrel buttons at buttonState 4, 32 and 64.
     * Those are `BUTTON_TERTIARY`, `BUTTON_STYLUS_PRIMARY` and
     * `BUTTON_STYLUS_SECONDARY`, and Phase 2's eraser is a mapping onto them —
     * pinned here so the measurement stays attached to the constants it means.
     */
    @Test
    fun `the measured barrel-button values are the stylus button constants`() {
        assertEquals(4, MotionEvent.BUTTON_TERTIARY)
        assertEquals(32, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(64, MotionEvent.BUTTON_STYLUS_SECONDARY)
    }

    /**
     * The pen's back reports FINGER, not ERASER, so a flipped pen lands in the
     * rejected bucket. That is the intended outcome — it is not an eraser — and
     * the decision is recorded in `toolClassOf`. This asserts it against the
     * platform constant rather than the mirror, so the two cannot agree with
     * each other while both being wrong.
     */
    @Test
    fun `a flipped pen classes as a gesture contact, because it reports FINGER`() {
        assertEquals(ToolClass.FINGER, toolClassOf(MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(ToolClass.PEN, toolClassOf(MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(ToolClass.PEN, toolClassOf(MotionEvent.TOOL_TYPE_ERASER))
        assertEquals(ToolClass.FINGER, toolClassOf(MotionEvent.TOOL_TYPE_MOUSE))
        assertEquals(ToolClass.FINGER, toolClassOf(MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun `every masked touch action maps to the action the machine names`() {
        assertEquals(PointerAction.DOWN, pointerActionOf(MotionEvent.ACTION_DOWN))
        assertEquals(PointerAction.POINTER_DOWN, pointerActionOf(MotionEvent.ACTION_POINTER_DOWN))
        assertEquals(PointerAction.MOVE, pointerActionOf(MotionEvent.ACTION_MOVE))
        assertEquals(PointerAction.POINTER_UP, pointerActionOf(MotionEvent.ACTION_POINTER_UP))
        assertEquals(PointerAction.UP, pointerActionOf(MotionEvent.ACTION_UP))
        assertEquals(PointerAction.CANCEL, pointerActionOf(MotionEvent.ACTION_CANCEL))
    }

    /**
     * Actions with no rule map to null and are dropped. Button press and
     * release are on this list deliberately: `buttonState` rides on every
     * sample, so a barrel button needs no action of its own, and routing one
     * would begin or end a stroke on a click.
     */
    @Test
    fun `an action the router has no rule for maps to nothing`() {
        assertNull(pointerActionOf(MotionEvent.ACTION_SCROLL))
        assertNull(pointerActionOf(MotionEvent.ACTION_OUTSIDE))
        assertNull(pointerActionOf(MotionEvent.ACTION_BUTTON_PRESS))
        assertNull(pointerActionOf(MotionEvent.ACTION_BUTTON_RELEASE))
    }

    /**
     * The two mappings do not overlap. Hover cannot alter exclusivity state,
     * and the way that is enforced is that neither function can produce the
     * other's members — `StrokeExclusivity.route` throws on a hover action.
     */
    @Test
    fun `hover actions and touch actions never map through each other`() {
        assertEquals(PointerAction.HOVER_ENTER, hoverActionOf(MotionEvent.ACTION_HOVER_ENTER))
        assertEquals(PointerAction.HOVER_MOVE, hoverActionOf(MotionEvent.ACTION_HOVER_MOVE))
        assertEquals(PointerAction.HOVER_EXIT, hoverActionOf(MotionEvent.ACTION_HOVER_EXIT))

        assertNull(pointerActionOf(MotionEvent.ACTION_HOVER_ENTER))
        assertNull(pointerActionOf(MotionEvent.ACTION_HOVER_MOVE))
        assertNull(pointerActionOf(MotionEvent.ACTION_HOVER_EXIT))
        assertNull(hoverActionOf(MotionEvent.ACTION_DOWN))
        assertNull(hoverActionOf(MotionEvent.ACTION_MOVE))
    }

    /**
     * The framework's own palm rejection arrives as this bit. It is API 30 as a
     * constant but only set from API 33, and the app's floor is 29 — so the
     * value is pinned here, and the version guard that goes with it lives in
     * `isPointerCanceled`.
     */
    @Test
    fun `the cancellation flag is the bit the framework sets`() {
        assertEquals(32, MotionEvent.FLAG_CANCELED)
    }
}
