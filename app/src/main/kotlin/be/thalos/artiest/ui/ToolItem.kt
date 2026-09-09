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
enum class ToolItem(
    /** Stable across renames and releases. See rule 2 above. */
    val id: String,
    /** What the chooser calls it. */
    val label: String,
    /** What fits on the slot itself, which for a one-slot button is 44dp. */
    val short: String,
    /** Width, in slots. */
    val slots: Int,
    val group: ToolGroup,
) {
    UNDO("undo", "Undo", "Undo", 2, ToolGroup.EDIT),
    REDO("redo", "Redo", "Redo", 2, ToolGroup.EDIT),

    /** Five swatches at 32dp each. Four slots is 176dp, so they fit without shrinking. */
    COLOUR("colour", "Colour", "Colour", 4, ToolGroup.DRAW),
    SIZE("size", "Size", "Size", 4, ToolGroup.DRAW),
    SMOOTHING("smoothing", "Stabilisation", "Smooth", 4, ToolGroup.DRAW),

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
    OPACITY("opacity", "Opacity", "Opac", 4, ToolGroup.DRAW),
    FLOW("flow", "Flow", "Flow", 4, ToolGroup.DRAW),

    /**
     * W8. The paper's tooth, as one slider from smooth to full depth.
     *
     * One control and not four: scale, cutoffs and seed are brush parameters
     * the format carries, but a toolbar with four grain sliders is a
     * synthesiser, not a pencil. W10's preset sets the other three.
     */
    GRAIN("grain", "Grain", "Grain", 4, ToolGroup.DRAW),

    /**
     * W10's two tools. The user's original catalogue asked for "pen, marker,
     * pencil"; the marker is deliberately absent — see [BrushPreset] for why
     * one tilted pencil does what a second tool was invented to do.
     */
    PEN("pen", "Pen", "Pen", 2, ToolGroup.DRAW),
    PENCIL("pencil", "Pencil", "Pencil", 2, ToolGroup.DRAW),

    ZOOM_IN("zoom_in", "Zoom in", "+", 1, ToolGroup.CANVAS),
    ZOOM_OUT("zoom_out", "Zoom out", "\u2212", 1, ToolGroup.CANVAS),
    FIT("fit", "Fit to screen", "Fit", 1, ToolGroup.CANVAS),
    CLEAR("clear", "Clear canvas", "Clear", 2, ToolGroup.CANVAS),

    EXPORT("export", "Export PNG", "Export", 2, ToolGroup.FILE),

    STATS("stats", "Instruments", "Stats", 2, ToolGroup.DEBUG),
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
