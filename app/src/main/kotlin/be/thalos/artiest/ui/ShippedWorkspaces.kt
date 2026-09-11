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
     * Pen, pencil, marker and eraser down the left; what you did across the
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
        layout = DockLayout.EMPTY
            .place("left", ToolItem.PEN, Cell(0, 0))
            .place("left", ToolItem.PENCIL, Cell(0, 1))
            .place("left", ToolItem.MARKER, Cell(0, 2))
            .place("left", ToolItem.ERASER, Cell(0, 4))
            .place("left", ToolItem.COLOUR, Cell(0, 6))
            .place("top", ToolItem.UNDO, Cell(0, 0))
            .place("top", ToolItem.REDO, Cell(1, 0))
            .place("right", ToolItem.LAYERS, Cell(0, 0))
            .place("right", ToolItem.FIT, Cell(0, 2))
            .place("bottom", ToolItem.SIZE, Cell(0, 0))
            .place("bottom", ToolItem.SMOOTHING, Cell(5, 0))
            .place("bottom", ToolItem.ERASER_SIZE, Cell(10, 0)),
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
     * tablet the spare one read as a stub of grey hanging off the foot. A bar
     * trims itself to its last item and a shape does not — a shape is what you
     * drew — so the shipped one has to be drawn to fit.
     */
    private fun clean(): Workspace = Workspace(
        id = CLEAN,
        name = "Clean",
        description = "The paper, and four controls in the corner.",
        author = "artiest",
        layout = DockLayout.EMPTY
            .reshape("left", CellRegion.l(arm = 3, foot = 2))
            .reflow("left", FlowOrder.DOWN_THEN_RIGHT)
            .place("left", ToolItem.PEN, Cell(0, 0))
            .place("left", ToolItem.ERASER, Cell(0, 1))
            .place("left", ToolItem.COLOUR, Cell(0, 2))
            .place("left", ToolItem.UNDO, Cell(1, 2)),
        filter = CatalogueFilter.of(ToolGroup.DRAW, ToolGroup.EDIT),
    )

    /**
     * The interface as it was before any of this, with nothing filtered.
     *
     * It is `DockLayout.STARTER`, which is the arrangement every existing
     * install already has, so switching to it is switching to what you had. It
     * is also the safety net the whole filter idea rests on: whatever a
     * workspace hides, this one offers.
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
