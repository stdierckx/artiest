package be.thalos.artiest.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file format, and what it does with a file from a stranger.
 *
 * Half of this is the ordinary case — a workspace goes out and comes back the
 * same. The other half is `app/src/test/resources/workspaces/`, a corpus of
 * files that are wrong in a different way each: truncated, four hundred deep,
 * twenty thousand strings long, rectangles with negative sides, a name with a
 * right-to-left override in it, a version from the future.
 *
 * **Every one of them asserts two things: that nothing threw, and what was
 * dropped.** The second matters as much as the first. A decoder that silently
 * swallows half a file is not safe, it is quiet — the user opens their new
 * workspace, three tools are missing, and there is nothing to read.
 */
class WorkspaceJsonTest {

    private fun corpus(name: String): String {
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/test/resources/workspaces/$name") }
            .firstOrNull { it.isFile }
        return assertNotNull(file, "$name is missing from the corpus").readText()
    }

    private fun sketcher() = Workspace(
        id = "sketcher",
        name = "Sketcher",
        description = "Pencil, paper, and nothing in the way.",
        author = "artiest",
        revision = 3,
        layout = DockLayout.STARTER,
        filter = CatalogueFilter.of(ToolGroup.DRAW, ToolGroup.EDIT).hiding(ToolItem.GRAIN),
        defaults = WorkspaceDefaults(stabilisation = 0.4f),
    )

    // ---- the round trip ----------------------------------------------------

    @Test
    fun `a workspace goes out and comes back the same`() {
        val there = WorkspaceJson.encode(sketcher())
        val back = WorkspaceJson.decode(there)
        assertEquals(emptyList(), back.dropped)
        assertEquals(sketcher(), back.workspace)
    }

    @Test
    fun `an L survives the round trip, where it was drawn and with its flow`() {
        val l = CellRegion.l(arm = 8, foot = 5).translated(2, 4)
        val ws = sketcher().copy(
            layout = DockLayout.of(
                listOf(
                    Surface(
                        "s1",
                        FlowOrder.DOWN_THEN_RIGHT,
                        SurfaceLayout.of(l, listOf(CellPlacement(ToolItem.PEN, 2, 4, 1, 1))),
                    ),
                ),
            ),
        )
        val back = assertNotNull(WorkspaceJson.decode(WorkspaceJson.encode(ws)).workspace)
        assertEquals(l, back.layout.surface("s1")?.region, "shape and corner both")
        assertEquals(FlowOrder.DOWN_THEN_RIGHT, back.layout.surface("s1")?.flow)
        assertEquals(ws, back)
    }

    @Test
    fun `a surface keeps its corner and a resized panel keeps its size`() {
        val (layout, id) = DockLayout.EMPTY.addSurface(ToolItem.COLOUR_PANEL, Cell(9, 7))
        val ws = sketcher().copy(layout = layout.resizePanel(id, Cell(9, 7), 8, 9))
        val back = assertNotNull(WorkspaceJson.decode(WorkspaceJson.encode(ws)).workspace)
        assertEquals(Cell(9, 7), back.layout.surface(id)?.origin)
        val panel = assertNotNull(back.layout.locate(ToolItem.COLOUR_PANEL)?.placement)
        assertEquals(8, panel.w, "a size the user chose is theirs")
        assertEquals(9, panel.h)
    }

    @Test
    fun `an at-less file packs deterministically, twice`() {
        val text = corpus("good.json")
        val once = assertNotNull(WorkspaceJson.decode(text).workspace)
        val twice = assertNotNull(WorkspaceJson.decode(text).workspace)
        assertEquals(once, twice)
        // pen said where it was; pencil and eraser did not and flowed after it.
        assertEquals(Cell(0, 0), once.layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), once.layout.locate(ToolItem.PENCIL)?.cell)
        assertEquals(Cell(0, 2), once.layout.locate(ToolItem.ERASER)?.cell)
    }

    @Test
    fun `the good file is the workspace it says it is`() {
        val decoded = WorkspaceJson.decode(corpus("good.json"))
        val ws = assertNotNull(decoded.workspace)
        assertEquals(emptyList(), decoded.dropped)
        assertEquals("sketcher", ws.id)
        assertEquals("Sketcher", ws.name)
        assertEquals(3, ws.revision)
        assertEquals(setOf(ToolGroup.DRAW, ToolGroup.EDIT), ws.filter.groups)
        assertFalse(ToolItem.GRAIN in ws.filter)
        assertEquals(0.4f, ws.defaults.stabilisation)
    }

    // ---- the published example ---------------------------------------------

    @Test
    fun `the example in the reference is one this build would write`() {
        // docs/workspace-format.md quotes it, and a worked example somebody
        // copies from has to be one the app would actually produce. Run
        // ./gradlew :app:catalogueJson when this fails.
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/workspace-example.json") }
            .firstOrNull { it.isFile }
        val text = assertNotNull(file, "docs/workspace-example.json is missing").readText()
        assertEquals(
            WorkspaceJson.encode(exampleWorkspace()),
            text,
            "docs/workspace-example.json is out of date — run ./gradlew :app:catalogueJson",
        )

        // And it reads back with nothing to complain about, which is the whole
        // point of publishing it.
        val decoded = WorkspaceJson.decode(text)
        assertEquals(emptyList(), decoded.dropped)
        assertEquals(exampleWorkspace(), decoded.workspace)
    }

    @Test
    fun `the example uses every field the reference describes`() {
        val ws = exampleWorkspace()
        assertFalse(assertNotNull(ws.layout.surface("s1")).region.isStrip, "a shape")
        assertEquals(3, ws.layout.surfaces.size, "more than one toolbar")
        assertTrue(
            ws.layout.surfaces.any { it.origin != Cell(0, 0) },
            "at least one of them somewhere other than the corner",
        )
        assertNotNull(ws.filter.groups, "a filter with groups")
        assertTrue(ws.filter.hide.isNotEmpty(), "something hidden")
        assertTrue(ws.filter.show.isNotEmpty(), "something shown back")
        assertFalse(ws.defaults.isEmpty, "and a default")
        val panel = assertNotNull(ws.layout.locate(ToolItem.LAYERS_PANEL)?.placement)
        assertTrue(
            panel.w != ToolItem.LAYERS_PANEL.cellsWide ||
                panel.h != ToolItem.LAYERS_PANEL.cellsTall,
            "a resized panel",
        )
    }

    // ---- the examples ------------------------------------------------------

    @Test
    fun `every example in docs opens with nothing dropped`() {
        // `docs/examples/` is what somebody copies from, and an example that
        // half-works teaches the half that does not. Each one has to open with
        // an empty complaint list — no unknown tool, no rectangle off the grid,
        // nothing with nowhere to go.
        val dir = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/examples") }
            .firstOrNull { it.isDirectory }
        val files = assertNotNull(dir, "docs/examples is missing").listFiles().orEmpty().sorted()
        assertTrue(files.size >= 4, "the examples are thinner than they were")

        for (file in files) {
            val decoded = WorkspaceJson.decode(file.readText())
            val ws = assertNotNull(decoded.workspace, file.name)
            assertEquals(emptyList(), decoded.dropped, file.name)
            assertEquals(file.name.removeSuffix(".json"), ws.id, "the id is the file name")
            assertTrue(ws.description.isNotEmpty(), "${file.name} says nothing about itself")
            assertTrue(ws.layout.all().isNotEmpty(), "${file.name} has no controls on it")

            // An example that does not fit the screen it is shown on is not an
            // example. Twenty-four by twelve cells is a small tablet.
            for (surface in ws.layout.surfaces) {
                val b = surface.region.bounds
                assertTrue(b.w <= 24 && b.h <= 12, "${file.name}/${surface.id} is ${b.w}x${b.h}")
            }

            // And every control it places is one it offers, or the chooser
            // would insist a visible button does not exist.
            for (docked in ws.layout.all()) {
                assertTrue(docked.item in ws.filter, "${file.name} hides its own ${docked.item.id}")
            }
        }
    }

    @Test
    fun `the example written without positions packs the way it reads`() {
        // The property that makes the format writable by hand: a list of names,
        // in order, is a toolbar. If this ever stops being true, the reference
        // is telling people something that is not.
        val text = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/examples/minimal.json") }
            .first { it.isFile }
            .readText()
        val ws = assertNotNull(WorkspaceJson.decode(text).workspace)

        assertEquals(Cell(0, 0), ws.layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), ws.layout.locate(ToolItem.PENCIL)?.cell)
        assertEquals(Cell(0, 4), ws.layout.locate(ToolItem.COLOUR)?.cell)
        assertEquals(Cell(0, 0), ws.layout.locate(ToolItem.UNDO)?.cell)
        assertEquals(Cell(1, 0), ws.layout.locate(ToolItem.REDO)?.cell)
        // Sliders are four cells long, so they land four apart along the bottom.
        assertEquals(Cell(0, 0), ws.layout.locate(ToolItem.SIZE)?.cell)
        assertEquals(Cell(4, 0), ws.layout.locate(ToolItem.SMOOTHING)?.cell)
        assertEquals(Cell(8, 0), ws.layout.locate(ToolItem.ERASER_SIZE)?.cell)
    }

    @Test
    fun `the L example stands its buttons up and lays its sliders flat`() {
        val text = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/examples/corner.json") }
            .first { it.isFile }
            .readText()
        val ws = assertNotNull(WorkspaceJson.decode(text).workspace)
        val l = assertNotNull(ws.layout.surfaces.firstOrNull { !it.region.isStrip }, "it is an L")
        val b = l.region.bounds

        assertEquals(Axis.VERTICAL, l.region.localAxis(b.x, b.y + 2), "up the arm")
        assertEquals(
            Axis.HORIZONTAL,
            l.region.localAxis(b.x + 5, b.bottom - 1),
            "along the foot",
        )

        val size = assertNotNull(ws.layout.locate(ToolItem.SIZE)?.placement)
        assertEquals(4, size.w, "the slider in the foot is four cells wide")
        assertEquals(1, size.h)
    }

    // ---- the corpus --------------------------------------------------------

    @Test
    fun `nothing in the corpus throws`() {
        val dir = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/test/resources/workspaces") }
            .firstOrNull { it.isDirectory }
        val files = assertNotNull(dir, "the corpus is missing").listFiles().orEmpty()
        assertTrue(files.size >= 10, "the corpus is thinner than it was")
        for (file in files) {
            // The assertion is that this line returns.
            WorkspaceJson.decode(file.readText())
        }
    }

    @Test
    fun `a truncated file is not a workspace, and says so`() {
        val decoded = WorkspaceJson.decode(corpus("truncated.json"))
        assertNull(decoded.workspace)
        assertTrue(decoded.dropped.isNotEmpty())
    }

    @Test
    fun `four hundred brackets deep is refused before the tree is built`() {
        val decoded = WorkspaceJson.decode(corpus("deep.json"))
        assertNull(decoded.workspace, "nesting past the ceiling is not a file we read")
    }

    @Test
    fun `twenty thousand strings is refused on node count`() {
        val decoded = WorkspaceJson.decode(corpus("huge-array.json"))
        assertNull(decoded.workspace)
    }

    @Test
    fun `duplicates keep the first and the user is told`() {
        val decoded = WorkspaceJson.decode(corpus("duplicates.json"))
        val ws = assertNotNull(decoded.workspace)
        assertEquals("left", ws.layout.locate(ToolItem.PEN)?.surface?.id)
        assertNull(ws.layout.locate(ToolItem.PENCIL), "the second toolbar named left was dropped")
        assertEquals(1, ws.layout.all().size)
        assertTrue(decoded.dropped.any { "twice" in it }, decoded.dropped.toString())
        assertTrue(decoded.dropped.any { "more than one toolbar" in it }, decoded.dropped.toString())
    }

    @Test
    fun `a rectangle that is not a rectangle is dropped and the shape keeps the rest`() {
        val decoded = WorkspaceJson.decode(corpus("negative-rects.json"))
        val ws = assertNotNull(decoded.workspace)
        assertEquals(CellRegion.strip(6, Axis.VERTICAL), ws.layout.surface("left")?.region)
        assertEquals(3, decoded.dropped.count { "off the screen" in it })
    }

    @Test
    fun `a toolbar entirely off the screen is given a bar long enough for it`() {
        val decoded = WorkspaceJson.decode(corpus("off-screen.json"))
        val ws = assertNotNull(decoded.workspace)
        // 900,900 is past MAX_COORD, so every rectangle went and the fallback
        // is a plain bar sized to what is on it, running the way the side it
        // named suggests. A toolbar with no shape at all would be a toolbar
        // that cannot be reached.
        val left = assertNotNull(ws.layout.surface("left"))
        assertEquals(CellRegion.strip(left.slots.placements.size, Axis.VERTICAL), left.region)
        assertTrue(decoded.dropped.any { "has no shape" in it }, decoded.dropped.toString())
    }

    @Test
    fun `a file from the future opens, dropping what it cannot do`() {
        val decoded = WorkspaceJson.decode(corpus("future.json"))
        val ws = assertNotNull(decoded.workspace, "a newer file is still mostly this file")

        assertEquals("Inker", ws.name)
        assertEquals("somebody else", ws.author)
        assertEquals(setOf(ToolGroup.DRAW), ws.filter.groups)
        assertEquals(0.8f, ws.defaults.stabilisation)
        assertEquals("nib-fine", ws.defaults.brush)
        assertEquals(Cell(0, 0), ws.layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 2), ws.layout.locate(ToolItem.ERASER)?.cell)

        // And every single thing it could not do is named.
        val said = decoded.dropped.joinToString("\n")
        assertTrue("newer version" in said, said)
        assertTrue("perspective" in said, said)
        assertTrue("perspective_ruler" in said, said)
        assertTrue("invert" in said, said)
        assertTrue("paper" in said, said)
        assertTrue("opacity" in said, said)
        assertTrue("colour" in said, said)
    }

    @Test
    fun `JSON that is not a workspace is refused rather than half-read`() {
        val decoded = WorkspaceJson.decode(corpus("not-a-workspace.json"))
        assertNull(decoded.workspace)
        assertTrue(decoded.dropped.single().contains("artiest_workspace"))
    }

    @Test
    fun `a name is cleaned and capped before it reaches a label`() {
        val ws = assertNotNull(WorkspaceJson.decode(corpus("nasty-name.json")).workspace)
        assertEquals("Sketcherreally", ws.name, "no controls, no override")
        assertEquals(WorkspaceJson.MAX_NAME, ws.author.length, "an author is not five hundred long")
        assertTrue(ws.name.none { it < ' ' })
        assertTrue(ws.name.none { it in '‪'..'‮' })
    }

    @Test
    fun `a string longer than the reader will read refuses the file`() {
        // Not a field cap. Nine thousand characters in one string is past what
        // the parser will build at all, and it stops before there is a field to
        // cap — which is the point of the limit being inside the parser.
        assertNull(WorkspaceJson.decode(corpus("giant-string.json")).workspace)
    }

    @Test
    fun `more toolbars than a screen has are cut off, and the user is told`() {
        val decoded = WorkspaceJson.decode(corpus("too-many-surfaces.json"))
        val ws = assertNotNull(decoded.workspace)
        assertTrue(ws.layout.surfaces.size <= WorkspaceJson.MAX_SURFACES)
        assertTrue(decoded.dropped.any { "more than a screen can hold" in it })
    }

    // ---- the small stuff that is easy to get wrong --------------------------

    @Test
    fun `an unknown tool is named rather than silently missing`() {
        val decoded = WorkspaceJson.decode(
            """{"artiest_workspace": 1, "name": "x", "surfaces": [
                {"dock": "left", "rects": [[0,0,1,12]],
                 "tools": [{"id": "pen"}, {"id": "sharpener"}]}]}"""
        )
        val ws = assertNotNull(decoded.workspace)
        assertEquals(1, ws.layout.all().size)
        assertEquals(listOf("\"sharpener\" — this build has no such tool"), decoded.dropped)
    }

    @Test
    fun `a tool with nowhere to go is reported rather than dropped in silence`() {
        val decoded = WorkspaceJson.decode(
            """{"artiest_workspace": 1, "name": "x", "surfaces": [
                {"dock": "left", "rects": [[0,0,1,2]],
                 "tools": [{"id": "pen"}, {"id": "pencil"}, {"id": "eraser"}]}]}"""
        )
        val ws = assertNotNull(decoded.workspace)
        assertEquals(2, ws.layout.all().size)
        assertTrue(decoded.dropped.any { "no room for it" in it }, decoded.dropped.toString())
    }

    @Test
    fun `only a panel may carry a size`() {
        val decoded = WorkspaceJson.decode(
            """{"artiest_workspace": 1, "name": "x", "surfaces": [
                {"dock": "bottom", "rects": [[0,0,24,1]],
                 "tools": [{"id": "size", "at": [0,0], "size": [19, 3]}]}]}"""
        )
        val p = assertNotNull(decoded.workspace?.layout?.locate(ToolItem.SIZE)?.placement)
        assertEquals(4, p.w, "a slider is what the catalogue says it is")
        assertEquals(1, p.h)
    }

    @Test
    fun `a missing id is made from the name`() {
        val ws = assertNotNull(
            WorkspaceJson.decode("""{"artiest_workspace": 1, "name": "My Inker"}""").workspace
        )
        assertEquals("my-inker", ws.id)
        assertTrue(ws.layout.surfaces.isEmpty(), "and a workspace with no toolbars has none")
    }

    @Test
    fun `an id that tries to be a path is a slug by the time it is one`() {
        val ws = assertNotNull(
            WorkspaceJson.decode(
                """{"artiest_workspace": 1, "id": "../../etc/passwd", "name": "x"}"""
            ).workspace
        )
        assertTrue(ws.id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }, ws.id)
    }
}
