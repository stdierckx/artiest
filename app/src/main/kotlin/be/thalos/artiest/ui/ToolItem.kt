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
 * **3. [cellsWide] is a width, and widths are why the layout has real logic.** A
 * button is one slot; a slider needs four or it is not a slider. Because items
 * differ in width, "can this go here" is a genuine question with an answer that
 * has edges — the end of the bar, the item next door — and that question is
 * what [SurfaceLayout] exists to answer.
 *
 * The two numbers are the item's size on screen, and which of them is spent on
 * slots is the *shape's* business rather than this enum's — see
 * [RegionLayout.naturalSize]. Four cells is 176dp across the bottom and 176dp
 * down the left, and in an L it is both, in different places. That is why the
 * buttons shed a slot each when the docks arrived — a two-slot Undo was two
 * slots of a word, and a one-slot Undo is an icon, which is what makes a
 * vertical dock possible. Text does not turn sideways; a glyph does not need to.
 *
 * **4. [kind] is how the item behaves, not how it is drawn.** A renderer needs
 * to know whether it is placing something that swallows drags — see
 * `DockHost`'s note on why filled cells are edited through Arrange — and the
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
enum class ToolKind {
    BUTTON,
    TOGGLE,
    SLIDER,
    SWATCH,

    /**
     * Bigger than a slot in both directions, and drawn as a card that overhangs
     * the bar it is anchored to. The bar's own ground does not grow — see
     * `docs/panels-plan.md` for why the panel hangs off it instead.
     */
    PANEL,
}

enum class ToolItem(
    /** Stable across renames and releases. See rule 2 above. */
    val id: String,
    /** What the chooser calls it. */
    val label: String,
    /** What fits on the slot itself, which for a one-slot button is 44dp. */
    val short: String,
    /** Width on screen, in 44dp cells, as it would sit on a horizontal bar. */
    val cellsWide: Int,
    val group: ToolGroup,
    /** How it behaves. See [ToolKind]. */
    val kind: ToolKind = ToolKind.BUTTON,
    /** Height on screen, in cells. One for everything that lives inside a bar. */
    val cellsTall: Int = 1,
    /**
     * Whether the footprint turns to suit the shape it lands in.
     *
     * True for a slider, which is a one-dimensional control and wants its long
     * axis to follow the bar: four cells across the bottom, four cells down the
     * left. False for a panel, which has a shape of its own and must keep it —
     * a colour wheel is the same wheel on every edge, and only which of its two
     * dimensions is spent on slots changes.
     *
     * Meaningless for a one-by-one button, which is why the default is free.
     */
    val turns: Boolean = false,
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
    SIZE("size", "Size", "Size", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),
    SMOOTHING("smoothing", "Stabilisation", "Smooth", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),

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
    OPACITY("opacity", "Opacity", "Opac", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),
    FLOW("flow", "Flow", "Flow", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),

    /**
     * W8. The paper's tooth, as one slider from smooth to full depth.
     *
     * One control and not four: scale, cutoffs and seed are brush parameters
     * the format carries, but a toolbar with four grain sliders is a
     * synthesiser, not a pencil. W10's preset sets the other three.
     */
    GRAIN("grain", "Grain", "Grain", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),

    /**
     * W10's two tools, and the third that joined them later. The original
     * catalogue asked for "pen, marker, pencil"; the marker was held back until
     * it was wanted for its own sake, which is the rule [BrushPreset] states
     * and the reason it is here now rather than then.
     */
    PEN("pen", "Pen", "Pen", 1, ToolGroup.DRAW, ToolKind.TOGGLE),
    PENCIL("pencil", "Pencil", "Pencil", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * The third tool, and the one the original catalogue asked for and did not
     * get. See [BrushPreset.MARKER] for what makes it a tool rather than a wide
     * pencil.
     */
    MARKER("marker", "Marker", "Marker", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * W11. A toggle, not a third tool: it changes how the brush in the hand
     * composites, so the pencil erases with the pencil's shape and the pen with
     * the pen's. The barrel button does the same thing momentarily and does not
     * move this toggle.
     */
    ERASER("eraser", "Eraser", "Erase", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * The eraser's own width, in document pixels, separate from [SIZE].
     *
     * A second size slider rather than a reuse of the first, for the reason
     * `Brush.eraseSizeMax` gives: a pencil point and a rubber are different
     * widths, and tying them together means the eraser resizes itself every
     * time the pencil does. It sits next to [ERASER] rather than in a settings
     * panel because "the eraser is too small for this" is a thought you have
     * mid-rub, with the pen already on the glass.
     */
    ERASER_SIZE("eraser_size", "Eraser size", "Erase", 4, ToolGroup.DRAW, ToolKind.SLIDER, turns = true),

    /**
     * The layers panel, as a button that opens it.
     *
     * One slot and a panel, for the reason [COLOUR] is one slot and a panel:
     * the list is as long as the drawing has sheets, it needs thumbnails, names
     * and a slider, and none of that fits in a bar. What a toolbar can usefully
     * show about layers at a glance is nothing, so it shows a way in.
     */
    LAYERS("layers", "Layers", "Layers", 1, ToolGroup.CANVAS),

    /**
     * The marquee: the pen selects instead of drawing.
     *
     * A toggle beside the brushes rather than a fourth brush, for the reason
     * [ERASER] is a toggle: it does not change what the nib is, it changes what
     * the pen is *for*. Everything about who may draw — palm rejection, the
     * two-finger gesture, the cancel on focus loss — is about pointers and
     * applies to a marquee word for word, so the pen's own path is the one that
     * forks.
     */
    MARQUEE("marquee", "Select", "Select", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * The selection panel: shape, what a gesture does to what is already
     * selected, and select all / none / invert.
     *
     * One slot and a panel, the same shape [LAYERS] and [COLOUR] have. Three
     * shapes, four combine modes and three commands is ten controls; a toolbar
     * that carried them would be a toolbar with nothing else on it.
     */
    SELECTION("selection", "Selection", "Select", 1, ToolGroup.CANVAS),

    /**
     * The same panel, kept. Six cells by eight, which is 264 by 352dp.
     *
     * A separate entry from [SELECTION] for the reason [COLOUR_PANEL] is
     * separate from [COLOUR]: fixate leaves the button where it was. It is here
     * because a selection is the one thing in this app you keep *adjusting* —
     * pick a shape, drag, change the combine mode, drag again, lift, turn,
     * drop — and every one of those was a tap to reopen a panel that closes
     * itself the moment the pen touches the page.
     */
    SELECTION_PANEL(
        "selection_panel", "Selection panel", "Select", 6, ToolGroup.CANVAS,
        ToolKind.PANEL, cellsTall = 8,
    ),

    /**
     * The colour wheel as a control you can keep, rather than a popup you
     * re-open. Six cells by eleven, which is 264 by 484dp.
     *
     * Eleven and not ten because ten clipped the recents row off the bottom,
     * which is the row that is only there once you have mixed something and so
     * the one nobody would have noticed missing until they wanted it.
     *
     * It is a **separate entry from [COLOUR]**, not a bigger version of it, and
     * that follows from how it gets on screen: fixate builds a new floating bar
     * and puts this in it, so the swatch stays where it was and keeps working.
     * Two things on screen at once cannot be one entry under the rule that an
     * item lives in exactly one place. It also means the chooser can offer it
     * like anything else, which is what makes "put it wherever you like" true
     * rather than a special case of one button.
     */
    COLOUR_PANEL(
        "colour_panel", "Colour panel", "Colour", 6, ToolGroup.DRAW,
        ToolKind.PANEL, cellsTall = 11,
    ),

    /**
     * The layers panel, kept. Seven cells by eleven, which is 308 by 484dp.
     *
     * Wider than the colour panel because a layer row is a thumbnail, a name
     * and three buttons side by side, and taller for no reason except that the
     * list is the part worth having more of — the docked card spends every
     * extra cell on rows rather than on card.
     *
     * A separate entry from [LAYERS], for the reason [COLOUR_PANEL] is separate
     * from [COLOUR]: fixate leaves the button where it was.
     */
    LAYERS_PANEL(
        "layers_panel", "Layers panel", "Layers", 7, ToolGroup.CANVAS,
        ToolKind.PANEL, cellsTall = 11,
    ),

    ZOOM_IN("zoom_in", "Zoom in", "Zoom+", 1, ToolGroup.CANVAS),
    ZOOM_OUT("zoom_out", "Zoom out", "Zoom-", 1, ToolGroup.CANVAS),
    FIT("fit", "Fit to screen", "Fit", 1, ToolGroup.CANVAS),
    CLEAR("clear", "Clear canvas", "Clear", 1, ToolGroup.CANVAS),

    EXPORT("export", "Export PNG", "Export", 1, ToolGroup.FILE),

    /**
     * The drawings you have, as pictures of themselves.
     *
     * A button and not a panel, and that is not laziness: a gallery is the one
     * piece of chrome that wants the *whole* screen — a list of drawings you
     * cannot recognise at a glance is a list you have to read, and reading file
     * names is what this replaces. It opens over everything and closes again.
     * See `docs/projects-plan.md`.
     */
    PROJECTS("projects", "Drawings", "Files", 1, ToolGroup.FILE),

    /**
     * A picture from the tablet, brought in as a layer of its own.
     *
     * In [ToolGroup.FILE] beside Export and not in DRAW, because it is the same
     * kind of act: something crosses the boundary between this drawing and the
     * rest of the device. See `PictureImporter` for why it lands on a new sheet
     * rather than on the one being worked on.
     */
    IMPORT("import", "Import picture", "Import", 1, ToolGroup.FILE),

    STATS("stats", "Instruments", "Stats", 1, ToolGroup.DEBUG, ToolKind.TOGGLE),
    ;

    init {
        require(cellsWide >= 1 && cellsTall >= 1) { "$id is ${cellsWide}x$cellsTall cells" }
    }

    /**
     * How big this is on screen, lying flat: [cellsWide] by [cellsTall].
     *
     * There used to be a `slotsIn(axis)` and a `depthIn(axis)` here, which
     * answered "how much of a bar does this take" and "how far does it stick
     * out". They are gone, and their answer moved to
     * [RegionLayout.naturalSize] — because a *shape* knows which way it runs at
     * every one of its cells, and a dock only ever had one answer for the whole
     * bar. Keeping both would be two answers to one question, and one of them
     * would go stale in an L.
     *
     * What is left here is the catalogue's own statement: a slider is four
     * cells by one, a colour wheel is six by eleven, and [turns] says
     * whether those two numbers may be swapped to suit where it lands.
     */
    val cells: Pair<Int, Int> get() = cellsWide to cellsTall

    /**
     * Whether this overhangs the shape it is anchored to instead of fitting in
     * it.
     *
     * True for a panel and false for everything else, which is the whole of the
     * rule [CellRegion.accepts] states: a seven-by-eleven card needs the cell it
     * is put in, not a seven-by-eleven toolbar. It is derived rather than
     * declared because a panel that did not overhang would be a panel nobody
     * could place, and a button that did would cover the drawing.
     */
    val hangs: Boolean get() = kind == ToolKind.PANEL

    /**
     * Whether a workspace is allowed to take this away.
     *
     * **One entry, and the list should stay that short.** [PROJECTS] is not a
     * tool, it is the way back to your drawings, and a workspace that filtered
     * it out would be a workspace you cannot leave — the same shape of mistake
     * as hiding Undo, with the work of a month behind it instead of a stroke.
     * `CatalogueFilter` offers it whatever its groups say, which also means a
     * release that adds it finds room for it on an install that has been
     * arranged for months.
     *
     * It is *offered* everywhere, not pinned anywhere: it can still be taken
     * off a bar like anything else, and the chooser will still have it.
     */
    val essential: Boolean get() = this == PROJECTS

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
