package be.thalos.artiest.ui

/**
 * The three workspaces that come with the app.
 *
 * `docs/master-plan.md` chose them, and the choosing is the interesting part:
 * **a workspace with a name and nothing behind it is the one way this feature
 * can make the app feel worse instead of better.** *Inker*, *Painter*,
 * *Webtoon* and *Animator* ship when the tools behind them do, and not before.
 * These three are honest today.
 *
 * - **Sketcher** — what is in your hand and what you just did, and nothing
 *   else. The reason it is honest now is that a sketcher's whole list is
 *   already built: brushes, an eraser, size, stabilisation, colour, undo.
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
 * the three files under `app/src/main/assets/workspaces/` are generated from
 * them by `./gradlew :app:catalogueJson`. The app reads the **assets**, through
 * the same decoder an import uses, which makes those files a permanent
 * test of the importer: if the decoder breaks, the app opens wrong on the first
 * run rather than on the day somebody is sent a file.
 *
 * A test asserts the two cannot drift.
 */
object ShippedWorkspaces {

    const val SKETCHER = "sketcher"
    const val CLEAN = "clean"
    const val EVERYTHING = "everything"

    /** Where the generated files live, in `assets`. */
    const val DIRECTORY = "workspaces"

    /** In the order the chooser lists them: the one you want most, first. */
    val ids: List<String> = listOf(SKETCHER, CLEAN, EVERYTHING)

    fun all(): List<Workspace> = listOf(sketcher(), clean(), everything())

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
                    ToolItem.ERASER to Cell(0, 4),
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
                    "s4", Side.BOTTOM, 14, 1,
                    ToolItem.SIZE to Cell(0, 0),
                    ToolItem.SMOOTHING to Cell(5, 0),
                    ToolItem.ERASER_SIZE to Cell(10, 0),
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
                            CellPlacement(ToolItem.ERASER, 0, 1, 1, 1),
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
