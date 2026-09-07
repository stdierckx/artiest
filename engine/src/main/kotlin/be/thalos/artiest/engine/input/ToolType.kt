package be.thalos.artiest.engine.input

/**
 * `MotionEvent.TOOL_TYPE_*` mirrored as plain Ints, which is the whole reason
 * `:engine` can name a tool without seeing `android.*`.
 *
 * The numbers are transcribed from
 * `javap -constants -cp $ANDROID_HOME/platforms/android-35/android.jar
 * android.view.MotionEvent`, not from memory, because [MOUSE] sits at 3
 * *between* [STYLUS] at 2 and [ERASER] at 4. A mirror typed out in the order
 * the names suggest swaps those two, compiles, passes every test in this
 * module, and then classifies the pen as a mouse on device — which presents as
 * "the pen draws nothing" with no exception anywhere near the mistake.
 *
 * These constants have been stable since API 14, so the risk is a typo here
 * rather than a platform change. The assertion that actually catches a typo
 * lives in `:app`, where `MotionEvent` is visible: the test that matters is
 * `ToolType.STYLUS == MotionEvent.TOOL_TYPE_STYLUS`, and it cannot be written
 * in this module by construction. The test here only pins the literals so the
 * two halves fail separately and legibly.
 */
object ToolType {
    const val UNKNOWN = 0
    const val FINGER = 1
    const val STYLUS = 2
    const val MOUSE = 3
    const val ERASER = 4
}

/**
 * What the router does with a contact: [PEN] draws, [FINGER] transforms.
 *
 * Two values rather than a passthrough of [ToolType] because every rule in
 * [StrokeExclusivity] branches on exactly this distinction, and a state machine
 * that switches on five tool types has four dead arms and a `when` that no
 * longer reads as the policy it is.
 */
enum class ToolClass { PEN, FINGER }

/**
 * The single point where a tool type becomes a routing class.
 *
 * [ToolType.ERASER] is here for a future device and is **never** reported by
 * the DTH-A116: this pen has no eraser end, and flipping it reports
 * [ToolType.FINGER] (Phase 0, measured). So the pen's back lands in the
 * [ToolClass.FINGER] bucket: it is dropped while a stroke is live, and on empty
 * glass it is a resting contact like any other finger — one of the two a pan
 * takes. It is not rejected outright, and nothing looks for a drop that is not
 * there. That is the intended outcome — it is not an eraser, and nothing in the
 * event stream distinguishes it from a knuckle — and it is recorded here as a
 * decision rather than left to look like an accident of the finger rule
 * elsewhere.
 * Phase 2's eraser is a barrel-button mapping; the three buttons are
 * individually addressable at buttonState 4 / 32 / 64.
 *
 * [ToolType.MOUSE] and [ToolType.UNKNOWN] class as [ToolClass.FINGER] so an
 * unrecognised digitizer pans instead of drawing an unpressured line.
 */
fun toolClassOf(toolType: Int): ToolClass =
    if (toolType == ToolType.STYLUS || toolType == ToolType.ERASER) ToolClass.PEN else ToolClass.FINGER

/** Names for logs and trace metadata. Never parsed. */
fun toolTypeName(toolType: Int): String = when (toolType) {
    ToolType.STYLUS -> "STYLUS"
    ToolType.ERASER -> "ERASER"
    ToolType.FINGER -> "FINGER"
    ToolType.MOUSE -> "MOUSE"
    ToolType.UNKNOWN -> "UNKNOWN"
    else -> "TOOL_$toolType"
}
