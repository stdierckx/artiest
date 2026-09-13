package be.thalos.artiest.ui

import be.thalos.artiest.engine.brush.BrushPreset

/**
 * The four workspaces that come with the app.
 *
 * `docs/master-plan.md` chose them, and the choosing is the interesting part:
 * **a workspace with a name and nothing behind it is the one way this feature
 * can make the app feel worse instead of better.** *Painter*, *Webtoon* and
 * *Animator* ship when the tools behind them do, and not before. These four
 * are honest today.
 *
 * - **Sketcher** — what is in your hand and what you just did, and nothing
 *   else. The reason it is honest now is that a sketcher's whole list is
 *   already built: brushes, an eraser, size, stabilisation, colour, undo.
 * - **Inker** — the same paper with a different set of habits on it: a hard
 *   edge, a steady hand, and a pen that picks a line as readily as it draws
 *   one. It became honest with `docs/inker-plan.md`'s Ik1–Ik12 — before those,
 *   *Select* meant pixels and there was nothing to lean a line against.
 * - **Clean** — the paper, and four controls in an L in the corner. It is the
 *   one that demonstrates the shape, and it is the one to open when the
 *   interface is in the way.
 * - **Everything** — the interface as it has always been, unfiltered. Nothing
 *   is ever lost, and it is where the search box sends you if you get stuck.
 *
 * ## Anchored, not positioned
 *
 * Every surface here is drawn from its own corner and carries an [Anchor], so
 * that one definition lands sensibly on a phone, on a tablet and either way up.
 * The anchor is resolved the first time the workspace is on a screen and then
 * it is cells like anything else — see [DockLayout.settled]. It is the one
 * remaining use for the word *left*, and it is a starting position rather than
 * an attachment.
 *
 * ## Compiled in *and* shipped as files
 *
 * These definitions are the source of truth, and
 * the files under `app/src/main/assets/workspaces/` are generated from
 * them by `./gradlew :app:catalogueJson`. The app reads the **assets**, through
 * the same decoder an import uses, which makes those files a permanent
 * test of the importer: if the decoder breaks, the app opens wrong on the first
 * run rather than on the day somebody is sent a file.
 *
 * A test asserts the two cannot drift.
 */
object ShippedWorkspaces {

    const val SKETCHER = "sketcher"
    const val INKER = "inker"
    const val CLEAN = "clean"
    const val EVERYTHING = "everything"

    /** Where the generated files live, in `assets`. */
    const val DIRECTORY = "workspaces"

    /** In the order the chooser lists them: the one you want most, first. */
    val ids: List<String> = listOf(SKETCHER, INKER, CLEAN, EVERYTHING)

    fun all(): List<Workspace> = listOf(sketcher(), inker(), clean(), everything())

    fun byId(id: String): Workspace? = all().firstOrNull { it.id == id }

    /**
     * Pen, pencil, marker and eraser down one side; what you did across the
     * top; the two sliders that shape a mark along the bottom.
     *
     * The filter is Draw and Edit, plus the layers panel put back by name. A
     * sketch has layers — a rough under an ink — and leaving them out to keep
     * the group list tidy would be tidiness at the user's expense.
     */
    private fun sketcher(): Workspace = Workspace(
        id = SKETCHER,
        name = "Sketcher",
        description = "Pencil, paper, and nothing in the way.",
        author = "artiest",
        layout = DockLayout.of(
            listOf(
                DockLayout.anchored(
                    "s1", Side.LEFT, 1, 7,
                    ToolItem.PEN to Cell(0, 0),
                    ToolItem.PENCIL to Cell(0, 1),
                    ToolItem.MARKER to Cell(0, 2),
                    // The shelf, in the free cell under the three favourites.
                    // A sketcher tunes a pencil more than anybody, so this is
                    // the workspace that most wants somewhere to keep one.
                    ToolItem.BRUSHES to Cell(0, 3),
                    ToolItem.HARD_ERASER to Cell(0, 4),
                    ToolItem.SOFT_ERASER to Cell(0, 5),
                    ToolItem.COLOUR to Cell(0, 6),
                ),
                DockLayout.anchored(
                    "s2", Side.TOP, 2, 1,
                    ToolItem.UNDO to Cell(0, 0),
                    ToolItem.REDO to Cell(1, 0),
                ),
                DockLayout.anchored(
                    "s3", Side.RIGHT, 1, 3,
                    ToolItem.LAYERS to Cell(0, 0),
                    ToolItem.FIT to Cell(0, 2),
                ),
                DockLayout.anchored(
                    // Nine, not fourteen: the eraser's own size slider went
                    // when the eraser became a brush, and a sketching
                    // workspace is the one that can least afford a bar with a
                    // gap in it.
                    "s4", Side.BOTTOM, 9, 1,
                    ToolItem.SIZE to Cell(0, 0),
                    ToolItem.SMOOTHING to Cell(5, 0),
                ),
            ),
        ),
        filter = CatalogueFilter
            .of(ToolGroup.DRAW, ToolGroup.EDIT)
            .offering(ToolItem.LAYERS)
            .offering(ToolItem.LAYERS_PANEL)
            .offering(ToolItem.FIT),
    )


    /**
     * The same paper as *Sketcher*, with a different set of habits on it.
     *
     * An inker does four things over and over: lay a confident line, zoom in to
     * see whether it was confident, pick a line that was not, and fix it. This
     * arrangement is those four and very little else.
     *
     * ## What is missing, and that is the design
     *
     * **No pencil and no soft eraser.** Ink is a hard edge — a soft eraser
     * fades a passage, which is exactly what an ink line must not do at its
     * end. Both are one tap away on the shelf, and the shelf is on the bar; what
     * is bought by leaving them off is the two cells that
     * [ToolItem.MARQUEE] and [ToolItem.SELECTION] now sit in, on the side the
     * free hand rests on.
     *
     * **The marquee is in the tool column, not with the view controls.** This
     * is the one place this workspace disagrees with [DockLayout.STARTER], and
     * `docs/inker-plan.md` is the reason: on an ink sheet the marquee picks
     * *strokes*, so rubbing a line back to its junction or giving six lines
     * another weight is done with it. That is not a view control. It is a tool,
     * and it belongs where the hand looks for tools.
     *
     * **Stabilisation is the first slider, and it arrives turned up.** A
     * sketcher wants the wobble; an inker is trying to get rid of it. That is
     * the whole difference between the two workspaces in one number, and it is
     * the reason [WorkspaceDefaults] finally has a field filled in — see
     * [INKING_STABILISATION].
     *
     * **Zoom and fit are on the far side from the tools**, for the reason
     * [DockLayout.STARTER] gives and an inker feels hardest: putting them on
     * the tool side is how you zoom when you meant to erase, and an inker
     * reaches for the eraser more than anybody.
     */
    private fun inker(): Workspace = Workspace(
        id = INKER,
        name = "Inker",
        description = "A hard edge, a steady hand, and the pen picks lines too.",
        author = "artiest",
        layout = DockLayout.of(
            listOf(
                DockLayout.anchored(
                    // Seven, and full. Five things that make a mark and two
                    // that choose one, with no gap between them — on an ink
                    // sheet choosing a line is the same kind of act as drawing
                    // one, so they read as a single run on purpose.
                    "s1", Side.LEFT, 1, 7,
                    ToolItem.PEN to Cell(0, 0),
                    ToolItem.MARKER to Cell(0, 1),
                    ToolItem.BRUSHES to Cell(0, 2),
                    ToolItem.HARD_ERASER to Cell(0, 3),
                    ToolItem.COLOUR to Cell(0, 4),
                    ToolItem.MARQUEE to Cell(0, 5),
                    ToolItem.SELECTION to Cell(0, 6),
                ),
                DockLayout.anchored(
                    // What you did, and the way back to your drawings. The gap
                    // is deliberate: undoing a line and leaving the drawing are
                    // not the same kind of act and should not be adjacent.
                    "s2", Side.TOP, 4, 1,
                    ToolItem.UNDO to Cell(0, 0),
                    ToolItem.REDO to Cell(1, 0),
                    ToolItem.PROJECTS to Cell(3, 0),
                ),
                DockLayout.anchored(
                    // Where you are looking, and what you are looking at.
                    "s3", Side.RIGHT, 1, 4,
                    ToolItem.ZOOM_IN to Cell(0, 0),
                    ToolItem.ZOOM_OUT to Cell(0, 1),
                    ToolItem.FIT to Cell(0, 2),
                    ToolItem.LAYERS to Cell(0, 3),
                ),
                DockLayout.anchored(
                    // Stabilisation first and size second, which is the other
                    // way round from Sketcher. The order of two sliders is not
                    // usually worth a comment; here it is the statement.
                    "s4", Side.BOTTOM, 9, 1,
                    ToolItem.SMOOTHING to Cell(0, 0),
                    ToolItem.SIZE to Cell(5, 0),
                ),
            ),
        ),
        // Draw, Edit and Canvas: an inker works on the drawing, and the one
        // thing outside those three that inking ends in is a finished picture
        // leaving the app. Drawings is offered whatever this says — see
        // [ToolItem.essential].
        filter = CatalogueFilter
            .of(ToolGroup.DRAW, ToolGroup.EDIT, ToolGroup.CANVAS)
            .offering(ToolItem.EXPORT),
        defaults = WorkspaceDefaults(
            brush = BrushPreset.PEN.id,
            stabilisation = INKING_STABILISATION,
        ),
    )

    /**
     * What *Inker* sets stabilisation to on arrival.
     *
     * Nearly four times the app's own default of 0.15, and that is the point:
     * 0.15 is enough to take the tremor out of a sketch line without the pen
     * feeling like it is dragging, and an inker is after something else
     * entirely — a curve that arrives where the eye said it would. Past about
     * 0.7 the lag is visible as the wet tail trailing the nib, which reads as
     * the tablet being slow rather than the line being smooth, so this stops
     * short of there.
     *
     * It is a starting position and not a setting: the slider is on the bar,
     * first, precisely so it can be moved.
     */
    const val INKING_STABILISATION: Float = 0.55f

    /**
     * One L in the bottom-left corner, four controls on it, and the rest of the
     * glass is paper.
     *
     * This is the one that demonstrates the shape, and it is deliberately the
     * smallest thing that is still a drawing program: something to draw with,
     * something to undo with, and a colour. An L rather than a bar because the
     * corner is the part of a tablet a thumb reaches without moving the hand
     * that is drawing.
     *
     * **The shape is exactly four cells, because it holds exactly four
     * controls.** It was a three-by-three L, which is five cells, and on the
     * tablet the spare one read as a stub of grey hanging off the foot. Nothing
     * trims itself to its last item any more — a shape is what you drew — so a
     * shipped shape has to be drawn to fit.
     *
     * Its anchor is a corner rather than a side, which is what [Anchor.Spot] is
     * for: an L whose corner does not point into the corner it is sitting in is
     * not what anybody means by an L there.
     */
    private fun clean(): Workspace = Workspace(
        id = CLEAN,
        name = "Clean",
        description = "The paper, and four controls in the corner.",
        author = "artiest",
        layout = DockLayout.of(
            listOf(
                Surface(
                    id = "s1",
                    flow = FlowOrder.DOWN_THEN_RIGHT,
                    slots = SurfaceLayout.of(
                        CellRegion.l(arm = 3, foot = 2),
                        listOf(
                            CellPlacement(ToolItem.PEN, 0, 0, 1, 1),
                            CellPlacement(ToolItem.HARD_ERASER, 0, 1, 1, 1),
                            CellPlacement(ToolItem.COLOUR, 0, 2, 1, 1),
                            CellPlacement(ToolItem.UNDO, 1, 2, 1, 1),
                        ),
                    ),
                    anchor = Anchor.Spot(0f, 1f),
                ),
            ),
        ),
        filter = CatalogueFilter.of(ToolGroup.DRAW, ToolGroup.EDIT),
    )

    /**
     * The interface as it was before any of this, with nothing filtered.
     *
     * It is [DockLayout.STARTER], which is the arrangement every fresh install
     * gets, so switching to it is switching to what the app opens with. It is
     * also the safety net the whole filter idea rests on: whatever a workspace
     * hides, this one offers.
     */
    private fun everything(): Workspace = Workspace(
        id = EVERYTHING,
        name = "Everything",
        description = "Every control there is, arranged the way it starts.",
        author = "artiest",
        layout = DockLayout.STARTER,
        filter = CatalogueFilter.EVERYTHING,
    )
}
