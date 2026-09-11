package be.thalos.artiest.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workspaces as files, against a real directory.
 *
 * This is the half of `WorkspaceStore` with the decisions in it — naming,
 * clashes, what a corrupt file does — and it is split out precisely so it can
 * be tested here rather than only on a tablet.
 *
 * The one that matters most is the last: **an import never overwrites.** A file
 * from a stranger that could silently replace a month of arranging is the one
 * unrecoverable thing this feature could do, and "are you sure" is not a
 * defence, because people say yes.
 */
class WorkspaceFilesTest {

    private val root: File = Files.createTempDirectory("artiest-workspaces").toFile()
    private val files = WorkspaceFiles(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun workspace(id: String, name: String = id) =
        Workspace(id = id, name = name, layout = DockLayout.STARTER)

    @Test
    fun `a saved workspace comes back`() {
        val ws = workspace("sketcher", "Sketcher")
        assertTrue(files.save(ws))
        assertEquals(ws, files.load("sketcher")?.workspace)
        assertTrue(files.exists("sketcher"))
    }

    @Test
    fun `the list is by name, and skips anything unreadable`() {
        files.save(workspace("zebra", "Zebra"))
        files.save(workspace("apple", "Apple"))
        File(root, "junk.json").writeText("this is not JSON at all")
        File(root, "notes.txt").writeText("nor is this a workspace")

        assertEquals(listOf("Apple", "Zebra"), files.list().map { it.name })
    }

    @Test
    fun `nothing throws on a directory that does not exist`() {
        val missing = WorkspaceFiles(File(root, "nowhere"))
        assertEquals(emptyList(), missing.list())
        assertNull(missing.load("sketcher"))
        assertFalse(missing.exists("sketcher"))
        assertNull(missing.duplicate("sketcher", "Copy"))
    }

    @Test
    fun `a duplicate gets its own name and starts at revision one`() {
        files.save(workspace("sketcher", "Sketcher").copy(revision = 7))
        val copy = assertNotNull(files.duplicate("sketcher", "Sketcher two"))
        assertEquals("sketcher-two", copy.id)
        assertEquals("Sketcher two", copy.name)
        assertEquals(1, copy.revision)
        assertEquals(2, files.list().size, "and the original is untouched")
    }

    @Test
    fun `saving twice under one name gives two workspaces, not one`() {
        // What *Save as* does when somebody calls both of them "Inking",
        // which is what somebody naming things with a pen in their hand does.
        files.save(workspace("sketcher", "Sketcher"))
        val first = assertNotNull(files.duplicate("sketcher", "Inking"))
        val second = assertNotNull(files.duplicate("sketcher", "Inking"))
        assertEquals("inking", first.id)
        assertEquals("inking-2", second.id, "the second one gets out of the first one's way")
        assertEquals(3, files.list().size)
    }

    @Test
    fun `a rename keeps the file, the id and the arrangement`() {
        files.save(workspace("inking", "Inking").copy(revision = 4))
        val renamed = assertNotNull(files.load("inking")).workspace!!.renamed("Inking, fine")
        files.save(renamed)
        assertEquals("inking", renamed.id, "a name is not a file name")
        assertEquals(5, renamed.revision)
        assertEquals(listOf("Inking, fine"), files.list().map { it.name })
        assertEquals(DockLayout.STARTER, assertNotNull(files.load("inking")).workspace?.layout)
    }

    @Test
    fun `an import never overwrites what is already here`() {
        val mine = workspace("sketcher", "Sketcher").copy(
            description = "the one I have spent a month on",
        )
        files.save(mine)

        val theirs = WorkspaceJson.encode(
            workspace("sketcher", "Sketcher").copy(description = "a file from a stranger"),
        )
        val imported = assertNotNull(files.import(theirs).workspace)

        assertEquals("sketcher-2", imported.id, "it got the next free name")
        assertEquals(mine, files.load("sketcher")?.workspace, "and mine is exactly as it was")
        assertEquals(2, files.list().size)
    }

    @Test
    fun `importing the same file three times gives three workspaces`() {
        val text = WorkspaceJson.encode(workspace("inker", "Inker"))
        val ids = List(3) { assertNotNull(files.import(text).workspace).id }
        assertEquals(listOf("inker", "inker-2", "inker-3"), ids)
    }

    @Test
    fun `importing something that is not a workspace saves nothing`() {
        val decoded = files.import("{\"hello\": \"world\"}")
        assertNull(decoded.workspace)
        assertTrue(decoded.dropped.isNotEmpty())
        assertEquals(emptyList(), files.list())
    }

    @Test
    fun `an import keeps the report so the user can read it`() {
        val decoded = files.import(
            """{"artiest_workspace": 1, "name": "Inker", "surfaces": [
                {"dock": "left", "rects": [[0,0,1,12]],
                 "tools": [{"id": "pen"}, {"id": "sharpener"}]}]}"""
        )
        assertNotNull(decoded.workspace)
        assertTrue(decoded.dropped.any { "sharpener" in it })
    }

    @Test
    fun `an id that tries to leave the directory does not`() {
        // Every id in the app is already a slug; this is the one place a string
        // becomes a path, so it is a slug again on the way through.
        val escaping = workspace("../../../etc/passwd", "Escape")
        assertTrue(files.save(escaping))
        val written = assertNotNull(root.listFiles()).single()
        assertTrue(written.parentFile == root, "it stayed in the directory")
        assertFalse("/" in written.name.removeSuffix(".json"))
    }

    @Test
    fun `a deleted workspace is gone and deleting a missing one is not an error`() {
        files.save(workspace("sketcher"))
        assertTrue(files.delete("sketcher"))
        assertNull(files.load("sketcher"))
        assertFalse(files.delete("sketcher"), "and it says so rather than throwing")
    }

    @Test
    fun `a corrupt file is not in the list and does not stop the others`() {
        files.save(workspace("good", "Good"))
        File(root, "broken.json").writeText("{\"artiest_workspace\": 1, \"name\": ")
        assertEquals(listOf("Good"), files.list().map { it.name })
        assertNull(files.load("broken"))
    }
}
