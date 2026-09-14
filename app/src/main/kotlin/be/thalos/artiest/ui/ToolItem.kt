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
 * | Eraser | **shipped**, then rebuilt — two brushes rather than a mode. See [HARD_ERASER]. |
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
     * The two erasers, which are the same kind of thing as the three above: a
     * named favourite for a brush the app ships.
     *
     * ## What went, and why
     *
     * `ERASER`, a one-slot toggle that put whatever brush was in the hand into
     * erase mode, and `ERASER_SIZE`, the second size slider that mode needed.
     * Both are gone. The user's words:
     *
     * > *"The eraser: Now it is some sort of toggle button on a brush, that is
     * > not how i like it. I want the eraser to be a separate tool. We should
     * > have 2 erasers: a hard one, and a soft one. No toggles, just a separate
     * > "brush" with the name: (Hard/Soft) eraser."*
     *
     * The size slider went with the toggle rather than as an extra: an eraser
     * is a brush now, and a brush's width is [SIZE]. That is a slider's worth
     * of bar handed back, which is `docs/ui-space-plan.md`'s Us5 paid early.
     *
     * ## What did not go
     *
     * The barrel button. Turning the pen over still rubs out momentarily with
     * the drawing brush's own shape — a pencil rubs out with the pencil's tilt
     * — and that is not a toggle, it is a thing the hardware does. See
     * `InkSurfaceView.rubber`.
     *
     * ## Why these are entries and not just shelf rows
     *
     * They are both. `BrushPreset.HARD_ERASER` puts them on the shelf like any
     * other brush and [BRUSH] can put either on a bar; these two exist for the
     * reason [PEN], [PENCIL] and [MARKER] do, which is stated there — a tool
     * you reach for fifty times an hour is one tap on the bar, not two taps
     * through a panel.
     */
    HARD_ERASER("hard_eraser", "Hard eraser", "Hard", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /** See [HARD_ERASER]. Soft-edged, and it fades a passage rather than cutting it. */
    SOFT_ERASER("soft_eraser", "Soft eraser", "Soft", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * Lr1. Take the colour that is already on the page.
     *
     * ## Why it is a tool and not a mode
     *
     * A toggle, and one that **turns itself off after one pick**. The pen goes
     * back to the brush that was in it, because that is what the hand was doing
     * a second ago and the pick was an interruption. Holding the button keeps
     * it on for the rare run of twenty colours; that is the exception and the
     * single pick is the rule.
     *
     * A picker that stayed on until it was pressed again would be the fourth
     * way to be in a mode you did not mean to be in — after the marquee, the
     * eraser and arrange — and it is the one where the failure is silent: you
     * would draw a stroke that instead changed your colour.
     *
     * ## Not the barrel button
     *
     * The obvious place for a picker on a tablet with no keyboard is the pen's
     * barrel, and it is taken: `PenChoice` gives it to the rubber, that is what
     * the pen in this user's hand does today, and a second meaning for the same
     * button would make the first unreliable.
     *
     * ## What it picks
     *
     * What the eye can see — the whole stack over the paper, at the opacities
     * and blend modes the sheets are set to. `ColourProbe` says why, and
     * [PICK_LAYER_ONLY] is the switch for the other answer.
     */
    PICK_COLOUR("pick_colour", "Pick a colour", "Pick", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

    /**
     * Whether [PICK_COLOUR] reads the sheet in hand rather than the picture.
     *
     * Off, and it should stay off for almost everybody: *what you see* is what
     * the eye meant. It is here for one case and it is the learner's case — a
     * photograph on a reference layer with a drawing over the top of it, where
     * the colour wanted is the photograph's and the thing on top is in the way.
     */
    PICK_LAYER_ONLY(
        "pick_layer_only", "Pick from this layer", "Layer", 1, ToolGroup.DRAW, ToolKind.TOGGLE,
    ),

    /**
     * One brush of your own, as a button.
     *
     * **The only entry in this catalogue that carries an argument**, and the
     * only one that can appear twice. `CellPlacement.arg` holds the brush id,
     * so two of these side by side are two different controls that happen to
     * share a catalogue entry — which is rule 3 above still holding, of an
     * *item*, while the thing on the bar is an item plus what it is for.
     *
     * It exists because a shelf is a list and a favourite is a button. The
     * three shipped toggles are exactly that argument made once, in code, for
     * the three brushes we authored; this is the same bargain offered to the
     * brushes the artist makes. Two pencils tuned differently, side by side, is
     * the thing that could not be expressed before.
     *
     * [ToolKind.SWATCH], for [COLOUR]'s reason: its face is the mark it makes,
     * and a brush glyph beside it would be a label for something already
     * visible. The chooser lists these separately from the catalogue, because
     * an entry with no argument is not a control.
     */
    BRUSH("brush", "Brush", "Brush", 1, ToolGroup.DRAW, ToolKind.SWATCH),

    /**
     * The shelf, as a button that opens it.
     *
     * **The three toggles above stay**, and that is the design rather than an
     * oversight. A tool you reach for fifty times an hour should be one tap on
     * the bar and not two taps through a panel, so [PEN], [PENCIL] and [MARKER]
     * remain one-slot toggles: the shelf is the full list and the buttons are
     * the favourites. That also keeps rule 3 above true — an item lives in
     * exactly one place — by making the shelf its own entry rather than a
     * bigger version of a button.
     *
     * See `docs/brush-shelf-plan.md`.
     */
    BRUSHES("brushes", "Brushes", "Brush", 1, ToolGroup.DRAW),

    /**
     * The same shelf, kept. Six cells by eleven, which is 264 by 484dp.
     *
     * One narrower than [LAYERS_PANEL] because a row here is a swatch, a name
     * and a small mark, where a layer row is a thumbnail and three buttons.
     * Eleven tall for the reason that one is: the list is the part worth having
     * more of, so every extra cell goes to rows rather than to card.
     *
     * A separate entry from [BRUSHES], for the reason [COLOUR_PANEL] is
     * separate from [COLOUR]: fixate leaves the button where it was.
     */
    BRUSH_SHELF(
        "brush_shelf", "Brush shelf", "Brush", 6, ToolGroup.DRAW,
        ToolKind.PANEL, cellsTall = 11,
    ),

    /**
     * The layers panel, as a button that opens it.
     *
     * One slot and a panel, for the reason [COLOUR] is one slot and a panel:
     * the list is as long as the drawing has sheets, it needs thumbnails, names
     * and a slider, and none of that fits in a bar. What a toolbar can usefully
     * show about layers at a glance is nothing, so it shows a way in.
     */
    /**
     * Lr2. The reference pane, as a button that opens it.
     *
     * [REFERENCE_PANEL] is the same pane kept on a bar, and the pair is exactly
     * [COLOUR]/[COLOUR_PANEL]'s: fixating leaves the button where it was, and
     * two things on screen at once cannot be one item under the rule that an
     * item lives in exactly one place.
     */
    REFERENCES("references", "Reference", "Ref", 1, ToolGroup.LEARN),

    /**
     * The pane, kept. **This is what "side by side" is** — a panel fixated to
     * an edge — and it is why the feature needed no new kind of window.
     *
     * Four cells wide and five tall by default, which is a picture about 170dp
     * across on this tablet: big enough to judge a colour and a shape from,
     * small enough to leave the drawing most of the glass. It is resizable like
     * every other card, so the number is a starting point and not a limit.
     */
    REFERENCE_PANEL(
        "reference_panel", "Reference pane", "Ref", 4, ToolGroup.LEARN,
        ToolKind.PANEL, cellsTall = 5,
    ),

    /**
     * Lr7. Your deck of cards: the drawings you kept and what you said about
     * them, and the tutorials somebody sent you, which are the same thing.
     */
    DECK("deck", "Deck", "Deck", 1, ToolGroup.LEARN),

    /** The deck, kept on a bar. [DECK]'s pair, as [COLOUR_PANEL] is [COLOUR]'s. */
    DECK_PANEL(
        "deck_panel", "Deck panel", "Deck", 4, ToolGroup.LEARN, ToolKind.PANEL, cellsTall = 6,
    ),

    /**
     * Keep the drawing that is on the paper, as a card.
     *
     * A button of its own as well as a line in the deck panel, because it is
     * the one act in this feature that happens *while you are drawing* — you
     * have just done a thing well and you want it before you forget how. A
     * feature you have to open a panel to reach at that moment is a feature
     * that does not get used.
     */
    KEEP_CARD("keep_card", "Keep this drawing", "Keep", 1, ToolGroup.LEARN),

    LAYERS("layers", "Layers", "Layers", 1, ToolGroup.CANVAS),

    /**
     * The marquee: the pen selects instead of drawing.
     *
     * A toggle beside the brushes rather than another brush, and it is now the
     * only real one in this group: it does not change what the nib is, it
     * changes what the pen is *for*. Everything about who may draw — palm rejection, the
     * two-finger gesture, the cancel on focus loss — is about pointers and
     * applies to a marquee word for word, so the pen's own path is the one that
     * forks.
     */
    MARQUEE("marquee", "Select", "Select", 1, ToolGroup.SELECT, ToolKind.TOGGLE),

    /**
     * The selection panel: shape, what a gesture does to what is already
     * selected, and select all / none / invert.
     *
     * One slot and a panel, the same shape [LAYERS] and [COLOUR] have. Three
     * shapes, four combine modes and three commands is ten controls; a toolbar
     * that carried them would be a toolbar with nothing else on it.
     */
    SELECTION("selection", "Selection", "Select", 1, ToolGroup.SELECT),

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
        "selection_panel", "Selection panel", "Select", 6, ToolGroup.SELECT,
        ToolKind.PANEL, cellsTall = 8,
    ),

    /**
     * ## The selection panel, taken apart
     *
     * The panel above is twenty-odd buttons in one card, and the report that
     * produced these entries said so: *"Some panels, like the selection panel,
     * have lots of buttons. Making it a bit overwhelming. Cool would be if the
     * user could choose to add these buttons separately to a toolbar by
     * himself."*
     *
     * So every button in it is also a catalogue entry. Nothing is removed from
     * the panel — somebody who wants the lot in one place still has it, and the
     * panel is still the only thing that shows the *state* of all of it at once
     * — but anybody who uses three of them can put those three on a bar and
     * never open the card again.
     *
     * The entries below are deliberately dumb one-slot buttons rather than a
     * clever "selection bar" item. A bar the user assembled out of the pieces
     * they use is the thing this app's whole dock exists to make possible, and
     * a pre-built strip would be a second answer to the question the dock
     * already answers.
     *
     * They share the panel's icons on purpose: a button you pulled out of a
     * panel should be the *same picture* as the one you pulled it from, or
     * nobody would recognise it on the bar.
     */
    PICK_STROKES("pick_strokes", "Pick strokes", "Pick", 1, ToolGroup.SELECT, ToolKind.TOGGLE),

    MARQUEE_RECT("marquee_rect", "Rectangle", "Rect", 1, ToolGroup.SELECT, ToolKind.TOGGLE),
    MARQUEE_OVAL("marquee_oval", "Ellipse", "Oval", 1, ToolGroup.SELECT, ToolKind.TOGGLE),
    MARQUEE_LASSO("marquee_lasso", "Free draw", "Lasso", 1, ToolGroup.SELECT, ToolKind.TOGGLE),

    /**
     * The four combine modes. A toggle each, because between them they are one
     * setting — the panel shows that by lighting one of four, and a bar shows
     * it the same way.
     */
    SELECT_NEW("select_new", "New selection", "New", 1, ToolGroup.SELECT, ToolKind.TOGGLE),
    SELECT_ADD("select_add", "Add to selection", "Add", 1, ToolGroup.SELECT, ToolKind.TOGGLE),
    SELECT_SUBTRACT(
        "select_subtract", "Subtract from selection", "Sub", 1, ToolGroup.SELECT, ToolKind.TOGGLE,
    ),
    SELECT_OVERLAP("select_overlap", "Overlap", "Lap", 1, ToolGroup.SELECT, ToolKind.TOGGLE),

    SELECT_ALL("select_all", "Select all", "All", 1, ToolGroup.SELECT),
    SELECT_NONE("select_none", "Select none", "None", 1, ToolGroup.SELECT),
    SELECT_INVERT("select_invert", "Invert selection", "Invert", 1, ToolGroup.SELECT),

    FLOAT_MOVE("float_move", "Move selection", "Move", 1, ToolGroup.SELECT),

    /**
     * Duplicate: the same lift, leaving the original where it is.
     *
     * Asked for from the tablet — *"we got move, but we dont have copy (or
     * duplicate) for a selection"* — and it is one flag in the document. See
     * `FloatingPixels.keepSource`.
     */
    FLOAT_COPY("float_copy", "Copy selection", "Copy", 1, ToolGroup.SELECT),

    /**
     * Mirror what is in the air, or what is selected — the op lifts for itself.
     *
     * Two entries and not one with a mode, because a mirror has no state to
     * remember: you press the one you meant and the pixels turn. A single
     * button with an axis setting would be a thing to set before a thing to
     * press.
     */
    FLIP_ACROSS("flip_across", "Flip", "Flip", 1, ToolGroup.SELECT),
    FLIP_DOWN("flip_down", "Flip down", "Down", 1, ToolGroup.SELECT),

    FLOAT_SHEET("float_sheet", "Move the sheet", "Sheet", 1, ToolGroup.SELECT),
    FLOAT_PASTE("float_paste", "Paste", "Paste", 1, ToolGroup.SELECT),
    FLOAT_CANCEL("float_cancel", "Cancel the move", "Cancel", 1, ToolGroup.SELECT),

    /**
     * The rubber's three modes, in **Draw** and not in Selection.
     *
     * They live in the selection *panel* because that is the panel that talks
     * about strokes, and the same tablet report that asked for these entries
     * said *"I dont get the eraser buttons on the selection"* — which is a fair
     * thing not to get. They are about the eraser. So the catalogue puts them
     * where the erasers are, and anybody who finds them surprising in the panel
     * can put the one they use on a bar beside the rubber and never look at
     * that row again.
     *
     * They do nothing on a sheet that does not keep its strokes, which is why
     * the panel only shows the row on one that does.
     */
    ERASE_WHOLE("erase_whole", "Erase whole stroke", "Whole", 1, ToolGroup.DRAW, ToolKind.TOGGLE),
    ERASE_JUNCTION(
        "erase_junction", "Erase to the junction", "Junct", 1, ToolGroup.DRAW, ToolKind.TOGGLE,
    ),
    ERASE_PART("erase_part", "Erase what it touches", "Part", 1, ToolGroup.DRAW, ToolKind.TOGGLE),

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

    /**
     * The guides panel, as a button that opens it. Ik13.
     *
     * In [ToolGroup.CANVAS] beside the selection and the layers, and not in
     * DRAW, for the reason `docs/inker-plan.md` gives when it says guides are
     * *furniture*: a ruler does not change what the nib is or what the mark
     * looks like. It is a thing lying on the page, like a selection is a region
     * of it, and the pen is helped by it without being changed.
     *
     * One slot and a panel, the shape [LAYERS], [COLOUR] and [SELECTION] have:
     * a list as long as the page has rulers, with two sliders under it, is not
     * something a bar can show.
     */
    GUIDES("guides", "Guides", "Guides", 1, ToolGroup.CANVAS),

    /**
     * The same panel, kept. Six cells by seven, which is 264 by 308dp.
     *
     * The shortest of the four kept panels, because it has the least to say: a
     * button, a handful of rows and two sliders. A card with room for eleven
     * rows of rulers would be a card that is mostly empty on every page anybody
     * draws.
     */
    GUIDES_PANEL(
        "guides_panel", "Guides panel", "Guides", 6, ToolGroup.CANVAS,
        ToolKind.PANEL, cellsTall = 7,
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

        /**
         * Ids that were real once, and what they mean now.
         *
         * **One entry, and it should stay short.** Rule 2 says a persisted id
         * is never renamed, and this is not a rename — `eraser` named a toggle
         * that no longer exists. Without this line, everyone who has arranged a
         * toolbar since W11 opens the app to a hole where their eraser was,
         * because a decoder that does not know an id drops it. The nearest
         * thing to what they had is the hard eraser, so that is what they get.
         *
         * `eraser_size` is deliberately **not** here. Its nearest equivalent is
         * the size slider, which is already on the bar in every arrangement
         * this app has ever shipped, and mapping it there would put a second
         * one beside the first. A four-cell hole is the honest outcome, and it
         * is four cells the user can now put something else in.
         */
        private val WAS: Map<String, ToolItem> = mapOf("eraser" to HARD_ERASER)

        fun byId(id: String): ToolItem? = BY_ID[id] ?: WAS[id]
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

    /**
     * Selecting, and everything done to what is selected.
     *
     * Third because it is what a hand reaches for third, and its own group
     * because it had outgrown Canvas: the panel's twenty-odd buttons are all
     * catalogue entries now, and twenty of them inside Canvas would have buried
     * Layers and the zoom controls.
     */
    SELECT("Selection"),
    CANVAS("Canvas"),

    /**
     * Lr2. The things that are about **learning to draw** rather than about
     * drawing: the reference pane, the library behind it, and what comes after
     * them in `docs/learner-plan.md`.
     *
     * Its own group and not Canvas, for the reason the Selection split gives:
     * a group is what a workspace offers, and a *Clean* workspace that wants
     * nothing but paper should be able to say so in one word. It is also the
     * one group a beginner's workspace is built *around*, which is a thing no
     * other group is.
     */
    LEARN("Learn"),
    FILE("File"),
    DEBUG("Instruments"),
}
