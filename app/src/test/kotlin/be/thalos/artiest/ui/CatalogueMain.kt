package be.thalos.artiest.ui

import java.io.File

/**
 * Writes the two generated documents. Run by `./gradlew :app:catalogueJson`.
 *
 * `docs/catalogue.json` is the vocabulary — every tool, group, kind, fill
 * order and anchor. `docs/workspace-example.json` is one real workspace written
 * by the real encoder, which is what `docs/workspace-format.md` quotes: a
 * worked example a person copies from has to be one the app would actually
 * produce, and the only way to be sure of that is to produce it.
 *
 * It lives in the test source set rather than in `main` for one reason: it must
 * not be in the APK. A `main` function that writes a file to a path given on
 * the command line is not something to ship on a tablet, and the test classpath
 * is the one place in this module that already runs on a plain JVM.
 */
fun main(args: Array<String>) {
    val docs = File(args.firstOrNull() ?: "docs")
    docs.mkdirs()

    val catalogue = File(docs, "catalogue.json")
    catalogue.writeText(ToolCatalogue.json())
    println("wrote ${catalogue.path} (${ToolItem.entries.size} tools, v${ToolCatalogue.VERSION})")

    val example = File(docs, "workspace-example.json")
    example.writeText(WorkspaceJson.encode(exampleWorkspace()))
    println("wrote ${example.path}")

    val assets = File(args.getOrNull(1) ?: "app/src/main/assets/${ShippedWorkspaces.DIRECTORY}")
    assets.mkdirs()
    for (workspace in ShippedWorkspaces.all()) {
        val file = File(assets, "${workspace.id}.json")
        file.writeText(WorkspaceJson.encode(workspace))
        println("wrote ${file.path}")
    }
}

/**
 * The workspace `docs/workspace-format.md` walks through.
 *
 * Deliberately not one of the shipped ones: it uses an L drawn where it sits, a
 * second surface out in the middle of the paper, a resized panel and a filter,
 * so that every field in the reference appears in the example rather than being
 * described in prose nobody can copy from.
 */
internal fun exampleWorkspace(): Workspace {
    val arm = Surface(
        id = "s1",
        flow = FlowOrder.DOWN_THEN_RIGHT,
        slots = SurfaceLayout.of(
            CellRegion.l(arm = 10, foot = 5).translated(0, 6),
            listOf(
                CellPlacement(ToolItem.PEN, 0, 6, 1, 1),
                CellPlacement(ToolItem.PENCIL, 0, 7, 1, 1),
                CellPlacement(ToolItem.ERASER, 0, 8, 1, 1),
                CellPlacement(ToolItem.SIZE, 0, 10, 1, 4),
                CellPlacement(ToolItem.COLOUR, 0, 15, 1, 1),
            ),
        ),
    )
    val top = Surface(
        id = "s2",
        flow = FlowOrder.RIGHT_THEN_DOWN,
        slots = SurfaceLayout.of(
            CellRegion.strip(2, Axis.HORIZONTAL).translated(2, 0),
            listOf(
                CellPlacement(ToolItem.UNDO, 2, 0, 1, 1),
                CellPlacement(ToolItem.REDO, 3, 0, 1, 1),
            ),
        ),
    )
    val panel = Surface(
        id = "s3",
        flow = FlowOrder.RIGHT_THEN_DOWN,
        slots = SurfaceLayout.of(
            CellRegion.strip(3, Axis.HORIZONTAL).translated(18, 3),
            // A panel hangs off the cell it is anchored to. Eight by nine is a
            // size the user dragged out, which is the one thing a file records
            // that the catalogue could have told it.
            listOf(CellPlacement(ToolItem.LAYERS_PANEL, 18, 3, 8, 9)),
        ),
    )

    return Workspace(
        id = "example",
        name = "Example",
        description = "Every field in the reference, in one file.",
        author = "artiest",
        revision = 1,
        layout = DockLayout.of(listOf(arm, top, panel)),
        filter = CatalogueFilter
            .of(ToolGroup.DRAW, ToolGroup.EDIT)
            .hiding(ToolItem.GRAIN)
            .offering(ToolItem.LAYERS_PANEL),
        defaults = WorkspaceDefaults(stabilisation = 0.35f),
    )
}
