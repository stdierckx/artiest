package be.thalos.artiest.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The workspaces that ship, and the promise each of their names makes.
 *
 * `docs/master-plan.md` is blunt about the risk: **a workspace with a name and
 * nothing behind it is the one way this feature can make the app feel worse
 * instead of better.** So the tests here are not about serialisation. They are
 * about whether the names are honest — whether *Sketcher* actually has what a
 * sketcher needs, whether *Inker* has what Ik1–Ik12 built, whether *Clean* is
 * actually clean, whether *Everything* actually offers everything.
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
        // they are a permanent test of the decoder an import uses,
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
            ToolItem.PEN, ToolItem.PENCIL, ToolItem.HARD_ERASER, ToolItem.COLOUR,
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
    fun `Inker has what an inker needs, and leaves out what an inker does not`() {
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.INKER))
        for (item in listOf(
            ToolItem.PEN, ToolItem.MARKER, ToolItem.HARD_ERASER, ToolItem.COLOUR,
            ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.UNDO, ToolItem.REDO,
            // The two that make it an inker rather than a sketcher: on an ink
            // sheet the marquee picks strokes, and the panel is where rubbing
            // one back to its junction lives. See docs/inker-plan.md.
            ToolItem.MARQUEE, ToolItem.SELECTION,
            // A rough under an ink, and the zoom that says whether the ink was
            // good.
            ToolItem.LAYERS, ToolItem.ZOOM_IN, ToolItem.ZOOM_OUT, ToolItem.FIT,
            // Ik13, and on the tool side rather than the view side — this is
            // the workspace that lays a ruler down and inks along it.
            ToolItem.GUIDES,
        )) {
            assertTrue(item in ws.layout, "${item.id} is not on a bar")
            assertTrue(item in ws.filter, "${item.id} is not even offered")
        }
        // Ink is a hard edge. Both of these are still offered -- the filter is
        // Draw -- and both are one tap away on the shelf; what is bought by
        // leaving them off the bar is the cells the marquee sits in.
        assertFalse(ToolItem.PENCIL in ws.layout, "an inker does not ink in pencil")
        assertFalse(ToolItem.SOFT_ERASER in ws.layout, "nor fade the end of a line")
        assertTrue(ToolItem.PENCIL in ws.filter)
        assertTrue(ToolItem.SOFT_ERASER in ws.filter)
    }

    @Test
    fun `Inker offers the selection panel's buttons one at a time`() {
        // The panel is twenty-odd buttons in one card and the tablet said so.
        // Every one of them is a catalogue entry now, and this is the workspace
        // that has to offer them: an inker picks a line they are not happy
        // with, and copies, flips and rubs one back to a junction.
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.INKER))
        for (item in listOf(
            ToolItem.PICK_STROKES, ToolItem.MARQUEE_LASSO, ToolItem.SELECT_ADD,
            ToolItem.SELECT_ALL, ToolItem.FLOAT_MOVE, ToolItem.FLOAT_COPY,
            ToolItem.FLIP_ACROSS, ToolItem.FLIP_DOWN, ToolItem.FLOAT_PASTE,
            ToolItem.ERASE_JUNCTION,
        )) {
            assertTrue(item in ws.filter, "${item.id} is not offered")
        }
        // And none of them is *placed*: a workspace that put twenty new buttons
        // on the bars would have answered the report with a worse version of
        // the thing being complained about.
        assertFalse(ToolItem.FLOAT_COPY in ws.layout)
        assertFalse(ToolItem.SELECT_ALL in ws.layout)
    }

    @Test
    fun `Clean keeps the marquee it always offered`() {
        // Selecting lived in Draw until the panel was taken apart. Clean
        // filters to Draw and Edit, so without the name it would have lost a
        // tool to a refactor, which `CatalogueFilter`'s header forbids.
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.CLEAN))
        assertTrue(ToolItem.MARQUEE in ws.filter)
        assertFalse(ToolItem.SELECT_ALL in ws.filter, "and none of the rest of the group")
    }

    @Test
    fun `Learner is defined by what it leaves out`() {
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.LEARNER))
        // The promise this workspace makes is subtraction, so the test is about
        // what is *not* offered. A beginner who cannot yet tell a good
        // interface from a bad one will assume anything confusing is their own
        // fault, which is why this one is checked rather than eyeballed.
        assertFalse(ToolItem.MARQUEE in ws.filter, "Learner offers the marquee")
        assertFalse(ToolItem.SELECTION_PANEL in ws.filter, "Learner offers the selection panel")
        assertFalse(ToolItem.GUIDES in ws.filter, "Learner offers the guides")
        // And the two things it adds, which are the reason it exists.
        assertTrue(ToolItem.REFERENCE_PANEL in ws.filter)
        assertTrue(ToolItem.DECK in ws.filter)
        // The pane is on the glass rather than behind a button: a feature a
        // beginner has to discover is a feature they do not have.
        val placed = ws.layout.surfaces.flatMap { it.slots.placements }.map { it.item }
        assertTrue(ToolItem.REFERENCE_PANEL in placed, "the pane is not on screen")
        assertTrue(ToolItem.KEEP_CARD in placed, "there is no way to keep a drawing")
        assertTrue(ToolItem.PICK_COLOUR in placed, "there is no picker on the bar")
    }

    @Test
    fun `Inker leans the pen before the line is drawn`() {
        // The one shipped workspace with anything in its defaults, and the one
        // number that separates inking from sketching. WorkspaceDefaults' KDoc
        // says a field lands in the commit that makes it do something; this is
        // that commit, so the test is that the field is not empty and that what
        // is in it is an inking value rather than the app's own.
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.INKER))
        assertFalse(ws.defaults.isEmpty, "Inker has nothing to say on arrival")
        assertEquals("pen", ws.defaults.brush, "and the pen is what it says first")
        val steady = assertNotNull(ws.defaults.stabilisation)
        assertTrue(steady > 0.3f, "an inker's hand is steadier than that: $steady")
        assertTrue(steady < 0.7f, "past here the wet tail trails the nib: $steady")

        // And only *Learner* does the same, so switching to Sketcher or Clean
        // does not quietly put a different pen in your hand.
        val speaks = setOf(ShippedWorkspaces.INKER, ShippedWorkspaces.LEARNER)
        for (other in ShippedWorkspaces.all().filter { it.id !in speaks }) {
            assertTrue(other.defaults.isEmpty, "${other.id} moves a tool on arrival")
        }
    }

    @Test
    fun `the Inker stabilisation survives the round trip it is written through`() {
        // A float in a JSON file that came back as 0.55000001 would make `each
        // shipped file is what the app would write` fail on a day nobody had
        // touched the workspace, which is the worst kind of failing test.
        val ws = assertNotNull(ShippedWorkspaces.byId(ShippedWorkspaces.INKER))
        val read = assertNotNull(WorkspaceJson.decode(WorkspaceJson.encode(ws)).workspace)
        assertEquals(ws.defaults, read.defaults)
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
        val only = assertNotNull(ws.layout.surfaces.singleOrNull(), "one toolbar, and no more")
        assertFalse(only.region.isStrip, "and it is an L")
        assertEquals(4, only.region.cellCount, "drawn to fit them")
        assertEquals(
            Cell(0, 7),
            ws.layout.settled(20, 10).surfaces.single().origin,
            "in the corner a thumb reaches, which a side anchor could not have said",
        )
        // Something to draw with, something to undo with, and a colour.
        assertTrue(ToolItem.PEN in ws.layout)
        assertTrue(ToolItem.HARD_ERASER in ws.layout)
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
    fun `every shipped workspace fits on a tablet, and says so on a phone`() {
        // Nothing that ships may need more than a tablet to be whole, and
        // whatever a phone cannot show has to reach the overflow chevron rather
        // than vanish. Everything's slider bar is the only thing that does.
        for (ws in ShippedWorkspaces.all()) {
            val tablet = ws.layout.settled(24, 12).fittedTo(24, 12)
            assertTrue(tablet.isWhole, "${ws.id} does not fit a tablet: ${tablet.overflow}")

            val phone = ws.layout.settled(12, 8).fittedTo(12, 8)
            for ((id, lost) in phone.overflow) {
                assertEquals(
                    listOf(ToolItem.FLOW),
                    lost.map { it.item },
                    "${ws.id}/$id loses more on a phone than the last slider",
                )
            }
        }
    }

    @Test
    fun `no two toolbars of a shipped workspace claim the same cell`() {
        for (ws in ShippedWorkspaces.all()) {
            for ((w, h) in listOf(12 to 8, 24 to 12, 28 to 18, 18 to 28)) {
                val settled = ws.layout.settled(w, h).fittedTo(w, h).layout
                val claimed = HashSet<Cell>()
                for (surface in settled.surfaces) {
                    for (cell in surface.region.cells(FlowOrder.RIGHT_THEN_DOWN)) {
                        assertTrue(claimed.add(cell), "${ws.id} on ${w}x$h: two claim $cell")
                    }
                }
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
