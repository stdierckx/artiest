package be.thalos.artiest.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three workspaces that ship, and the promise each of their names makes.
 *
 * `docs/master-plan.md` is blunt about the risk: **a workspace with a name and
 * nothing behind it is the one way this feature can make the app feel worse
 * instead of better.** So the tests here are not about serialisation. They are
 * about whether the names are honest — whether *Sketcher* actually has what a
 * sketcher needs, whether *Clean* is actually clean, whether *Everything*
 * actually offers everything.
 */
class ShippedWorkspacesTest {

    private fun asset(id: String): String {
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/workspaces/$id.json") }
            .firstOrNull { it.isFile }
        return assertNotNull(file, "the $id asset is missing").readText()
    }

    // ---- the files cannot drift from the definitions ------------------------

    @Test
    fun `each shipped file is what the app would write`() {
        for (workspace in ShippedWorkspaces.all()) {
            assertEquals(
                WorkspaceJson.encode(workspace),
                asset(workspace.id),
                "${workspace.id}.json is out of date — run ./gradlew :app:catalogueJson",
            )
        }
    }

    @Test
    fun `each shipped file reads back through the importer with nothing dropped`() {
        // This is why they are shipped as files rather than only compiled in:
        // the three of them are a permanent test of the decoder an import uses,
        // so a break shows up on the first run rather than on the day somebody
        // is sent a file.
        for (workspace in ShippedWorkspaces.all()) {
            val decoded = WorkspaceJson.decode(asset(workspace.id))
            assertEquals(emptyList(), decoded.dropped, workspace.id)
            assertEquals(workspace, decoded.workspace, workspace.id)
        }
    }

    // ---- the names have to be honest ---------------------------------------

    @Test
    fun `Sketcher has what a sketcher needs`() {
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.SKETCHER))
        for (item in listOf(
            ToolItem.PEN, ToolItem.PENCIL, ToolItem.ERASER, ToolItem.COLOUR,
            ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.UNDO, ToolItem.REDO,
        )) {
            assertTrue(item in ws.layout, "${item.id} is not on a bar")
            assertTrue(item in ws.filter, "${item.id} is not even offered")
        }
        // And layers, which are outside the groups it filters on and are put
        // back by name — a sketch has a rough under an ink.
        assertTrue(ToolItem.LAYERS in ws.filter)
        assertTrue(ToolItem.LAYERS in ws.layout)
    }

    @Test
    fun `no shipped shape has a cell with nothing standing on it`() {
        // A bar trims itself to its last item; a shape does not, because a
        // shape is what you drew. So a shipped shape with a spare cell is a
        // stub of grey hanging off it, which is what Clean looked like on the
        // tablet before its L was drawn to fit.
        for (ws in ShippedWorkspaces.all()) {
            for (surface in ws.layout.surfaces) {
                if (surface.region.isStrip || surface.isEmpty) continue
                assertEquals(
                    surface.region.cellCount,
                    surface.slots.usedCells,
                    "${ws.id}/${surface.id} has ground nothing stands on",
                )
            }
        }
    }

    @Test
    fun `Clean is clean, and is the one that demonstrates the shape`() {
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.CLEAN))
        assertEquals(4, ws.layout.all().size, "four controls, no more")
        assertFalse(ws.layout.edge(Dock.LEFT).region.isStrip, "and it is an L")
        assertEquals(4, ws.layout.edge(Dock.LEFT).region.cellCount, "drawn to fit them")
        for (dock in listOf(Dock.TOP, Dock.RIGHT, Dock.BOTTOM)) {
            assertTrue(ws.layout.edge(dock).isEmpty, "${dock.id} is paper")
        }
        // Something to draw with, something to undo with, and a colour.
        assertTrue(ToolItem.PEN in ws.layout)
        assertTrue(ToolItem.ERASER in ws.layout)
        assertTrue(ToolItem.COLOUR in ws.layout)
        assertTrue(ToolItem.UNDO in ws.layout)
    }

    @Test
    fun `Everything offers everything, because it is the way out`() {
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.EVERYTHING))
        assertTrue(ws.filter.isEverything)
        for (item in ToolItem.entries) assertTrue(item in ws.filter, item.id)
        assertEquals(DockLayout.STARTER, ws.layout, "it is the interface as it starts")
    }

    @Test
    fun `nothing a shipped workspace places is something it does not offer`() {
        // The filter never takes a control off a bar, so a shipped workspace
        // *could* place something it hides — and it would be a lie: the user
        // would see a button that the chooser insists does not exist.
        for (ws in ShippedWorkspaces.all()) {
            for (docked in ws.layout.all()) {
                assertTrue(
                    docked.item in ws.filter,
                    "${ws.id} puts ${docked.item.id} on a bar and does not offer it",
                )
            }
        }
    }

    @Test
    fun `every shipped workspace fits on a phone-sized grid`() {
        // Twelve by eight cells is 528 by 352dp, which is a small phone in
        // landscape. Nothing that ships may need more than that to be whole.
        for (ws in ShippedWorkspaces.all()) {
            for (surface in ws.layout.surfaces) {
                if (surface.isEmpty) continue
                val bounds = surface.region.bounds
                assertTrue(
                    bounds.w <= 24 && bounds.h <= 12,
                    "${ws.id}/${surface.id} is ${bounds.w}x${bounds.h} cells",
                )
            }
        }
    }

    @Test
    fun `the ids are slugs and the names are short enough to read`() {
        for (ws in ShippedWorkspaces.all()) {
            assertEquals(ws.id, Workspace.slug(ws.id))
            assertTrue(ws.name.length <= WorkspaceJson.MAX_NAME, ws.name)
            assertTrue(ws.description.isNotEmpty(), "${ws.id} says nothing about itself")
            assertTrue(ws.description.length <= WorkspaceJson.MAX_TEXT)
        }
        assertEquals(ShippedWorkspaces.ids, ShippedWorkspaces.all().map { it.id })
    }

    @Test
    fun `Everything is the one the app falls back to`() {
        assertEquals(WorkspaceStore.DEFAULT_ID, ShippedWorkspaces.EVERYTHING)
    }

    // ---- copies ------------------------------------------------------------

    @Test
    fun `a copy is numbered rather than called copy`() {
        assertEquals("Sketcher 2", Workspace.copyName("Sketcher", emptyList()))
        assertEquals("Sketcher 3", Workspace.copyName("Sketcher", listOf("Sketcher 2")))
        assertEquals(
            "Sketcher 3",
            Workspace.copyName("Sketcher 2", listOf("Sketcher 2")),
            "and not Sketcher 2 2",
        )
    }
}
