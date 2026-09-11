package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workspace object, and the two rules that make switching one safe.
 *
 * Rule one: the chooser filters and the layout never does, so a tool already
 * placed but outside the filter keeps working and keeps its cell. Rule two: a
 * newly shipped tool the current workspace hides is still recorded as offered,
 * so switching to *Everything* later does not rain buttons onto bars somebody
 * had already arranged.
 *
 * Both of them look like small print and both of them are the difference
 * between a feature people use and one they try once.
 */
class WorkspaceTest {

    // ---- the filter --------------------------------------------------------

    @Test
    fun `no filter at all offers the whole catalogue`() {
        val all = CatalogueFilter.EVERYTHING
        assertTrue(all.isEverything)
        assertEquals(ToolItem.entries.size, all.offered().size)
        for (item in ToolItem.entries) assertTrue(item in all, item.id)
    }

    @Test
    fun `groups are the broad stroke`() {
        val sketch = CatalogueFilter.of(ToolGroup.DRAW, ToolGroup.EDIT)
        assertTrue(ToolItem.PEN in sketch)
        assertTrue(ToolItem.UNDO in sketch)
        assertFalse(ToolItem.STATS in sketch, "the instruments are not what the app is for")
        assertFalse(ToolItem.LAYERS in sketch)
        assertFalse(sketch.isEverything)
    }

    @Test
    fun `hide takes one tool out of a group you wanted`() {
        val filter = CatalogueFilter.of(ToolGroup.DRAW).hiding(ToolItem.GRAIN)
        assertTrue(ToolItem.PEN in filter)
        assertFalse(ToolItem.GRAIN in filter)
    }

    @Test
    fun `show wins over everything, because it is where the escape hatch writes`() {
        // The mitigation for the whole feature's worst failure -- hiding a tool
        // is a promise it was not needed. A hatch some other field could
        // override would not be one.
        val filter = CatalogueFilter
            .of(ToolGroup.DRAW)
            .hiding(ToolItem.GRAIN)
            .offering(ToolItem.GRAIN)
        assertTrue(ToolItem.GRAIN in filter)

        val fromNowhere = CatalogueFilter.of(ToolGroup.DRAW).offering(ToolItem.LAYERS)
        assertTrue(ToolItem.LAYERS in fromNowhere, "a group it was never in")
    }

    @Test
    fun `offering something already offered changes nothing`() {
        val filter = CatalogueFilter.of(ToolGroup.DRAW)
        assertEquals(filter, filter.offering(ToolItem.PEN))
    }

    @Test
    fun `the offered list is in catalogue order`() {
        val filter = CatalogueFilter.of(ToolGroup.EDIT, ToolGroup.FILE)
        assertEquals(
            listOf(ToolItem.UNDO, ToolItem.REDO, ToolItem.EXPORT, ToolItem.IMPORT),
            filter.offered(),
        )
    }

    @Test
    fun `a filter never takes a control off a bar`() {
        // Stated as a test because it is the rule, and because the code that
        // could break it is somewhere else entirely: nothing in DockLayout
        // takes a CatalogueFilter, and that is on purpose.
        val layout = DockLayout.STARTER
        val narrow = CatalogueFilter.of(ToolGroup.DRAW)
        assertFalse(ToolItem.UNDO in narrow)
        assertTrue(ToolItem.UNDO in layout, "still on the top edge, still working")
        assertEquals(14, layout.all().size)
    }

    // ---- the workspace itself ----------------------------------------------

    @Test
    fun `a save is a revision`() {
        val one = Workspace(id = "sketcher", name = "Sketcher", layout = DockLayout.STARTER)
        assertEquals(1, one.revision)
        assertEquals(2, one.revised().revision)
        assertEquals(2, one.renamed("Sketching").revision)
        assertEquals("Sketching", one.renamed("Sketching").name)
        assertEquals("sketcher", one.renamed("Sketching").id, "the id does not follow the name")
    }

    @Test
    fun `a slug is a file name, not a display name`() {
        assertEquals("sketcher", Workspace.slug("Sketcher"))
        assertEquals("clean-webtoon", Workspace.slug("Clean  Webtoon"))
        assertEquals("stephanes-inker", Workspace.slug("Stéphane's Inker"))
        assertEquals("my-workspace-2", Workspace.slug("  My Workspace 2  "))
        assertEquals(Workspace.FALLBACK_SLUG, Workspace.slug("...."))
        assertEquals(Workspace.FALLBACK_SLUG, Workspace.slug(""))
    }

    @Test
    fun `a slug carries nothing a path or a preference key would mind`() {
        val nasty = Workspace.slug("../../etc/passwd  |:,=@+")
        assertTrue(nasty.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }, nasty)
        assertFalse(nasty.startsWith("-"))
        assertFalse(nasty.endsWith("-"))
        assertTrue(Workspace.slug("x".repeat(200)).length <= Workspace.MAX_SLUG)
    }

    @Test
    fun `defaults are empty until the subsystems that own them exist`() {
        // ToolItem's rule 1, applied to a different list: a workspace that
        // promised a brush this build cannot select would lie on the first tap.
        assertTrue(WorkspaceDefaults().isEmpty)
        assertFalse(WorkspaceDefaults(brush = "pencil-2b").isEmpty)
    }

    @Test
    fun `a workspace is its arrangement plus what it offers plus what it sets`() {
        val ws = Workspace(
            id = "sketcher",
            name = "Sketcher",
            description = "Pencil, paper, and nothing in the way.",
            author = "artiest",
            layout = DockLayout.STARTER,
            filter = CatalogueFilter.of(ToolGroup.DRAW, ToolGroup.EDIT),
            defaults = WorkspaceDefaults(stabilisation = 0.4f),
        )
        assertEquals(14, ws.layout.all().size)
        assertTrue(ToolItem.PEN in ws.filter)
        assertFalse(ToolItem.STATS in ws.filter)
        assertFalse(ws.defaults.isEmpty)
    }
}
