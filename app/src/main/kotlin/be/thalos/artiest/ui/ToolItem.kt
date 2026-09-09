package be.thalos.artiest.ui

/**
 * The catalogue: everything that can be dropped into a toolbar slot.
 *
 * Three rules govern this enum, and they are the whole design.
 *
 * **1. Only controls that work appear here.** A chooser full of buttons that do
 * nothing is worse than a short chooser, because the user cannot tell a control
 * they have not understood from one that was never wired up. Phase 2's opacity
 * and flow sliders (W7) and its brush presets (W10) get their entry *in the
 * commit that makes them do something*, not before. That is the point of having
 * a catalogue at all: adding a control becomes one line here plus one branch in
 * the renderer, instead of an edit to a hand-built `Row`.
 *
 * **2. [id] is the persisted name, and it is not [name].** A saved layout
 * outlives the source. Renaming a Kotlin constant is a refactor; renaming a
 * persisted identifier silently empties somebody's toolbar. Keeping them
 * separate means the compiler can be used freely on one and the other is left
 * alone deliberately.
 *
 * **3. [slots] is a width, and widths are why the layout has real logic.** A
 * button is one slot; a slider needs four or it is not a slider. Because items
 * differ in width, "can this go here" is a genuine question with an answer that
 * has edges — the end of the bar, the item next door — and that question is
 * what [ToolbarLayout] exists to answer.
 *
 * Since the docks arrived, [slots] is a length along the dock's own axis rather
 * than a width: four slots is 176dp across the bottom and 176dp down the left.
 * That is the entire cost of turning a bar into five bars, and it is why the
 * buttons shed a slot each in the same change — a two-slot Undo was two slots
 * of a word, and a one-slot Undo is an icon, which is also what makes a vertical
 * dock possible. Text does not turn sideways; a glyph does not need to.
 *
 * **4. [kind] is how the item behaves, not how it is drawn.** A renderer needs
 * to know whether it is placing something that swallows drags — see
 * [SlotToolbar]'s note on why filled slots are edited through Arrange — and the
 * dock layer needs it before it has drawn anything, to decide whether an item
 * can go on a vertical edge at all. What the glyph looks like is `ToolIcons`'
 * business and is deliberately not here, so that this enum stays free of
 * Compose and keeps running in a plain JVM test.
 *
 * ## The catalogue this is growing into
 *
 * The target list is the user's, given while this was being built, and it is
 * recorded here rather than in a document because rule 1 makes it a to-do list
 * with an address: each line lights up in the commit that makes it work, and
 * the work item that unlocks it is named so nobody has to guess whether it was
 * forgotten or is merely early.
 *
 * | Wanted | Unlocked by |
 * |---|---|
 * | Undo, redo | **shipped** — region snapshots, see `UndoHistory` |
 * | Zoom in, zoom out | **shipped** |
 * | Pen, pencil, marker | W10's presets. Marker is a real question — the graphite reference turned out to be one pencil at two tilts, and no marker was cut from the plan on that evidence. It comes back only if it is wanted for its own sake. |
 * | Eraser | W11 |
 * | Tool settings, stabilisation settings | Partly shipped as the size and smoothing sliders; becomes a panel when W7 adds opacity and flow |
 * | Colour wheel | Phase 4. The five-swatch palette is the placeholder and says so. |
 * | Document history (a list you can jump around in) | Phase 3, with the tiled copy-on-write layer. Undo and redo did not need it; a visual history of every state does. |
 * | Layers: add, clear, lock | Phase 3, same reason |
 * | Export | **shipped** |
 */
/**
 * What sort of control an item is.
 *
 * Three of these matter to the layout rather than to the painter.
 *
 * [SLIDER] is the kind that eats gestures, which is why Arrange mode exists,
 * and it is also the kind that has a long axis of its own. A slider laid down
 * a vertical dock has to be a vertical slider, and one that is four slots long
 * is four slots tall — both true, both handled, and both the reason this
 * distinction is data rather than a `when` in the renderer.
 *
 * [SWATCH] is a button whose face is the thing it sets. It has no glyph because
 * showing the current colour *is* the icon, and a palette symbol sitting next to
 * a colour would be a label for something already visible.
 */
enum class ToolKind { BUTTON, TOGGLE, SLIDER, SWATCH }

enum class ToolItem(
    /** Stable across renames and releases. See rule 2 above. */
    val id: String,
    /** What the chooser calls it. */
    val label: String,
    /** What fits on the slot itself, which for a one-slot button is 44dp. */
    val short: String,
    /** Length along the dock's axis, in slots. */
    val slots: Int,
    val group: ToolGroup,
    /** How it behaves. See [ToolKind]. */
    val kind: ToolKind = ToolKind.BUTTON,
) {
    UNDO("undo", "Undo", "Undo", 1, ToolGroup.EDIT),
    REDO("redo", "Redo", "Redo", 1, ToolGroup.EDIT),

    /**
     * One slot, showing the ink it will set, opening the wheel when tapped.
     *
     * It was four slots of five fixed swatches, and the swatches were a
     * placeholder that said so. The wheel is real now, so the bar gets the
     * button and the panel gets the palette — which is the right way round:
     * the swatches were taking four slots of a bar to show five of sixteen
     * million colours, and the one thing a toolbar must show about the ink is
     * what colour it is right now.
     */
    COLOUR("colour", "Colour", "Colour", 1, ToolGroup.DRAW, ToolKind.SWATCH),
    SIZE("size", "Size", "Size", 4, ToolGroup.DRAW, ToolKind.SLIDER),
    SMOOTHING("smoothing", "Stabilisation", "Smooth", 4, ToolGroup.DRAW, ToolKind.SLIDER),

    /**
     * W7. The tripwire is paid: these two exist because the scratch buffer
     * does, and they are what make the reference's median 0.27 alpha
     * reachable at all.
     *
     * Two sliders and not one, because they are genuinely different things.
     * [FLOW] is paint per dab and builds up along a stroke; [OPACITY] is the
     * ceiling the whole stroke composites at and cannot be exceeded however
     * often the stroke crosses itself. Collapsing them into one control is the
     * shortcut that makes a pencil impossible: graphite is low flow under a
     * high ceiling.
     */
    OPACITY("opacity", "Opacity", "Opac", 4, ToolGroup.DRAW, ToolKind.SLIDER),
    FLOW("flow", "Flow", "Flow", 4, ToolGroup.DRAW, ToolKind.SLIDER),

    /**
     * W8. The paper's tooth, as one slider from smooth to full depth.
     *
     * One control and not four: scale, cutoffs and seed are brush parameters
     * the format carries, but a toolbar with four grain sliders is a
     * synthesiser, not a pencil. W10's preset sets the other three.
     */
    GRAIN("grain", "Grain", "Grain", 4, ToolGroup.DRAW, ToolKind.SLIDER),

    /**
     * W10's two tools. The user's original catalogue asked for "pen, marker,
     * pencil"; the marker is deliberately absent — see [BrushPreset] for why
     * one tilted pencil does what a second tool was invented to do.
     */
    PEN("pen", "Pen", "Pen", 1, ToolGroup.DRAW, ToolKind.TOGGLE),
    PENCIL("pencil", "Pencil", "Pencil", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * W11. A toggle, not a third tool: it changes how the brush in the hand
     * composites, so the pencil erases with the pencil's shape and the pen with
     * the pen's. The barrel button does the same thing momentarily and does not
     * move this toggle.
     */
    ERASER("eraser", "Eraser", "Erase", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    ZOOM_IN("zoom_in", "Zoom in", "Zoom+", 1, ToolGroup.CANVAS),
    ZOOM_OUT("zoom_out", "Zoom out", "Zoom-", 1, ToolGroup.CANVAS),
    FIT("fit", "Fit to screen", "Fit", 1, ToolGroup.CANVAS),
    CLEAR("clear", "Clear canvas", "Clear", 1, ToolGroup.CANVAS),

    EXPORT("export", "Export PNG", "Export", 1, ToolGroup.FILE),

    STATS("stats", "Instruments", "Stats", 1, ToolGroup.DEBUG, ToolKind.TOGGLE),
    ;

    init {
        require(slots >= 1) { "$id occupies $slots slots" }
    }

    companion object {
        /** The catalogue keyed by [id], for the codec. Unknown ids decode to null. */
        private val BY_ID: Map<String, ToolItem> = entries.associateBy { it.id }

        fun byId(id: String): ToolItem? = BY_ID[id]
    }
}

/**
 * How the chooser is grouped.
 *
 * Ordering is the display order, so it is a design decision rather than
 * bookkeeping: what the pen touches most often is listed first, and the
 * instruments are listed last because they are not what the app is for.
 */
enum class ToolGroup(val label: String) {
    EDIT("Edit"),
    DRAW("Draw"),
    CANVAS("Canvas"),
    FILE("File"),
    DEBUG("Instruments"),
}
