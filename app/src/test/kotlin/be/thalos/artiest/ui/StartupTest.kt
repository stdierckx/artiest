package be.thalos.artiest.ui

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the app puts on screen on the first launch after the workspace system.
 *
 * **This exists because a tablet disagreed with the model.** The bars that came
 * up were not the bars in `SharedPreferences`, and the layout decoder was
 * innocent — a plain JVM test of the exact stored string returned exactly the
 * right thing. So the fault had to be in the *startup order*, which is the one
 * part of this that nothing covered: two stores, one preference file, and a
 * seed step that runs before either of them is read.
 *
 * The rule being pinned is the one an existing install depends on:
 *
 * > **The bars you had are the bars you get.** A release that adds workspaces
 * > must not hand somebody the shipped *Sketcher* in place of the arrangement
 * > they built. The workspace decides what the chooser offers; the preference
 * > decides what is on the bars, and it wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A real arrangement from a real tablet, in the format that build wrote. */
    private val stored =
        "v3|left:12:0=pen,1=pencil,2=eraser,3=marker,4=colour,5=marquee|" +
            "top:24:0=undo,1=redo,2=import,3=export,4=stats|" +
            "right:12:0=zoom_in,1=zoom_out,2=fit,3=layers,4=selection,6=clear|" +
            "bottom:24:0=size,4=smoothing,8=grain,12=eraser_size,16=opacity,20=flow|" +
            "f4@0.909,0.45:7:0=layers_panel@7x11"

    private fun seedPrefs() {
        context.getSharedPreferences("chrome", Context.MODE_PRIVATE)
            .edit()
            .putString("toolbar.layout", stored)
            // Everything in NEW_ITEMS has already been offered on this install,
            // so `introduce` has nothing to do and cannot be what moves a bar.
            .putString("toolbar.offered", "layers,marker,import,eraser_size,marquee,selection")
            .apply()
    }

    /** The order MainActivity does it in, which is the thing under test. */
    private fun startUp(): Pair<Workspace, DockLayout> {
        val store = DockStore(context)
        val workspaces = WorkspaceStore(context)
        val loaded = workspaces.current()
        val bars = store.load(loaded.filter)
        return (workspaces.adoptOnce(bars) ?: loaded) to bars
    }

    @Test
    fun `an existing install keeps the bars it had`() {
        seedPrefs()
        val (workspace, docks) = startUp()

        assertEquals("everything", workspace.id, "nobody has chosen a workspace yet")
        assertEquals(
            listOf("pen", "pencil", "eraser", "marker", "colour", "marquee"),
            docks.surface("left")!!.slots.placements.map { it.item.id },
            "the left edge is the one the user built, not the one that ships",
        )
        assertEquals(
            listOf("undo", "redo", "import", "export", "stats"),
            docks.surface("top")!!.slots.placements.map { it.item.id },
        )
        // Seven, not six: this build's DockStore has a control the install has
        // never been offered -- the gallery -- and `introduce` puts it in the
        // first cell that will hold it. That is the other half of "the bars you
        // had are the bars you get": nothing moves, and what is new appears.
        assertEquals(7, docks.surface("right")!!.slots.placements.size)
        assertEquals(
            listOf("zoom_in", "zoom_out", "fit", "layers", "selection", "projects", "clear"),
            docks.surface("right")!!.slots.placements.map { it.item.id },
            "and it lands in the gap that was there, moving nothing",
        )
        assertEquals(6, docks.surface("bottom")!!.slots.placements.size)
        assertNotNull(docks.surface("f4"), "and the panel they fixated")
    }

    @Test
    fun `a fresh install gets the starter bars, not a shipped workspace's`() {
        val (workspace, docks) = startUp()
        assertEquals("everything", workspace.id)

        // The starter set, plus whatever `DockStore.introduce` has never
        // offered before — which on a fresh install is all of it. That is the
        // existing rule and not a new one; what is being pinned here is that
        // the *starter* is where it begins, rather than a shipped workspace.
        for (item in DockLayout.STARTER.all()) {
            assertEquals(
                item.cell,
                docks.locate(item.item)?.cell,
                "${item.item.id} moved",
            )
        }
        assertTrue(ToolItem.LAYERS in docks, "and the newer controls were introduced")
    }

    @Test
    fun `the shipped workspaces are seeded on the first run, through the importer`() {
        WorkspaceStore(context)
        val store = WorkspaceStore(context)
        assertEquals(
            listOf("Clean", "Everything", "Sketcher"),
            store.list().map { it.name },
            "three files, read back by name",
        )
        for (id in ShippedWorkspaces.ids) {
            val loaded = assertNotNull(store.load(id), id)
            assertEquals(ShippedWorkspaces.byId(id), loaded, "$id came back changed")
        }
    }

    @Test
    fun `switching away and back gives you your own bars, not the factory ones`() {
        // The bug a tablet found, as a test. Before this, Everything meant the
        // *shipped* starter, so the first switch to Sketcher replaced an
        // arrangement somebody had built and switching back handed them the
        // factory layout. Nothing asked, and nothing could undo it.
        seedPrefs()
        val (workspace, docks) = startUp()

        val mine = listOf("pen", "pencil", "eraser", "marker", "colour", "marquee")
        assertEquals(mine, docks.surface("left")!!.slots.placements.map { it.item.id })
        assertEquals(
            mine,
            workspace.layout.surface("left")!!.slots.placements.map { it.item.id },
            "Everything now means what this install actually had",
        )

        // Go to Sketcher, which really does replace the bars -- that is what
        // switching is for -- and come home.
        val workspaces = WorkspaceStore(context)
        workspaces.switchTo(ShippedWorkspaces.SKETCHER)
        assertEquals(
            listOf("pen", "pencil", "marker", "eraser", "colour"),
            workspaces.current().layout.surface("s1")!!.slots.placements.map { it.item.id },
        )

        workspaces.switchTo(ShippedWorkspaces.EVERYTHING)
        assertEquals(
            mine,
            // "left" and not "s1": what Everything holds now is the arrangement
            // this install already had, surface names and all.
            workspaces.current().layout.surface("left")!!.slots.placements.map { it.item.id },
            "and they are still there",
        )
    }

    @Test
    fun `adopting happens once, so a later drag is not undone by a restart`() {
        seedPrefs()
        startUp()

        // A drag moves a control and writes the fast path. The file catches up
        // later, on purpose -- so a second adoption would be a stale layout
        // overwriting a fresh one.
        val store = DockStore(context)
        val moved = store.load(CatalogueFilter.EVERYTHING).remove("left", Cell(0, 5))
        store.save(moved)

        val workspaces = WorkspaceStore(context)
        assertEquals(null, workspaces.adoptOnce(moved), "once, ever")
    }

    @Test
    fun `a fresh install has nothing to adopt`() {
        val workspaces = WorkspaceStore(context)
        assertEquals(
            null,
            workspaces.adoptOnce(DockLayout.STARTER),
            "the starter is already what Everything says",
        )
    }

    @Test
    fun `switching to a workspace takes its arrangement, and only then`() {
        seedPrefs()
        val store = DockStore(context)
        val workspaces = WorkspaceStore(context)

        // Before: the user's own bars.
        assertTrue(ToolItem.MARQUEE in store.load(workspaces.current().filter))

        // Switching is the one thing that replaces them, and it is a thing the
        // user did rather than something a release did to them.
        workspaces.switchTo(ShippedWorkspaces.SKETCHER)
        val sketcher = assertNotNull(workspaces.current())
        assertEquals("sketcher", sketcher.id)
        assertEquals(
            listOf("pen", "pencil", "marker", "eraser", "colour"),
            sketcher.layout.surface("s1")!!.slots.placements.map { it.item.id },
        )
    }

    @Test
    fun `the current workspace survives a restart`() {
        WorkspaceStore(context).switchTo(ShippedWorkspaces.CLEAN)
        assertEquals("clean", WorkspaceStore(context).currentId())
        assertEquals("Clean", WorkspaceStore(context).current().name)
    }
}
