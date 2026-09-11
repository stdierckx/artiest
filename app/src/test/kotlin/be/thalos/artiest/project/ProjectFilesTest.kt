package be.thalos.artiest.project

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
 * Projects as directories, against a real one.
 *
 * The one that matters most is the last: **a write that fails leaves the old
 * file alone.** Everything else here is a convenience; that one is the promise
 * the whole feature rests on, because this is the only store in the app holding
 * something the user cannot make again.
 */
class ProjectFilesTest {

    private val root: File = Files.createTempDirectory("artiest-projects").toFile()
    private val files = ProjectFiles(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun create(name: String, at: Long = 1_000L) = assertNotNull(
        files.create(name, 3300, 2160, at),
        "$name was not created",
    )

    private fun putSheet(id: String, index: Int, bytes: String) {
        val file = files.sheetOf(id, index)
        file.parentFile?.mkdirs()
        file.writeText(bytes)
    }

    @Test
    fun `a new project is a directory with a manifest in it`() {
        val p = create("Morning sketch")
        assertEquals("morning-sketch", p.id)
        assertTrue(files.dirFor(p.id).isDirectory)
        assertTrue(files.manifestOf(p.id).isFile)
        assertEquals(3300, p.widthPx)
        assertEquals(1_000L, p.created)
    }

    @Test
    fun `two projects called the same thing are two directories`() {
        val first = create("Sketch")
        val second = create("Sketch")
        assertEquals("sketch", first.id)
        assertEquals("sketch-2", second.id)
        assertEquals(2, files.list().size)
    }

    @Test
    fun `the list is newest first and skips what it cannot read`() {
        create("Old", at = 1_000L)
        create("New", at = 9_000L)
        File(root, "rubbish").mkdirs()
        File(root, "rubbish/project.json").writeText("{\"artiest_project\": ")
        File(root, "loose.txt").writeText("not a directory")

        assertEquals(listOf("New", "Old"), files.list().map { it.name })
    }

    @Test
    fun `a project comes back with its sheets described`() {
        val p = create("Ink test").let {
            it.revised(2_000L, listOf(ProjectSheet(Project.fileFor(0), "Ink", opacity = 0.5f)))
        }
        assertTrue(files.save(p))
        val back = assertNotNull(files.load(p.id)).project
        assertEquals("Ink", back?.sheets?.first()?.name)
        assertEquals(0.5f, back?.sheets?.first()?.opacity)
        assertEquals(2, back?.revision)
    }

    @Test
    fun `a duplicate takes the pixels with it`() {
        val p = create("Sketch")
        putSheet(p.id, 0, "PNG-ish bytes")
        files.save(p.revised(2_000L, listOf(ProjectSheet(Project.fileFor(0), "Layer 1"))))

        val copy = assertNotNull(files.duplicate(p.id, "Sketch, inked", 3_000L))
        assertEquals("sketch-inked", copy.id)
        assertEquals(1, copy.revision, "a copy starts again at one")
        assertEquals("PNG-ish bytes", files.sheetOf(copy.id, 0).readText())
        assertEquals("Sketch", assertNotNull(files.load(p.id)).project?.name, "and the original is untouched")
    }

    @Test
    fun `deleting takes the whole directory, and a missing one is not an error`() {
        val p = create("Sketch")
        putSheet(p.id, 0, "pixels")
        assertTrue(files.delete(p.id))
        assertFalse(files.dirFor(p.id).exists())
        assertNull(files.load(p.id))
        assertFalse(files.delete(p.id), "it says so rather than throwing")
    }

    @Test
    fun `pruning takes the sheets a shorter stack no longer names`() {
        val p = create("Sketch")
        for (i in 0..3) putSheet(p.id, i, "sheet $i")
        files.pruneSheets(p.id, keep = 2)
        assertTrue(files.sheetOf(p.id, 0).isFile)
        assertTrue(files.sheetOf(p.id, 1).isFile)
        assertFalse(files.sheetOf(p.id, 2).isFile)
        assertFalse(files.sheetOf(p.id, 3).isFile)
    }

    @Test
    fun `an id that tries to leave the directory does not`() {
        val p = assertNotNull(files.create("../../elsewhere", 10, 10, 1_000L))
        val written = files.dirFor(p.id)
        assertEquals(root, written.parentFile)
        assertFalse("/" in p.id || ".." in p.id, "id was ${p.id}")
    }

    @Test
    fun `a write that fails leaves what was there before`() {
        val p = create("Sketch")
        val target = files.manifestOf(p.id)
        val good = target.readText()

        val wrote = files.writeAtomically(target) { tmp ->
            tmp.writeText("half of a file")
            throw java.io.IOException("the tablet filled up")
        }

        assertFalse(wrote, "it reports the failure")
        assertEquals(good, target.readText(), "and the old manifest is still the manifest")
        assertFalse(File(target.parentFile, target.name + ".tmp").exists(), "and nothing is left behind")
    }

    @Test
    fun `nothing throws on a directory that does not exist`() {
        val missing = ProjectFiles(File(root, "nowhere"))
        assertEquals(emptyList(), missing.list())
        assertNull(missing.load("sketch"))
        assertFalse(missing.exists("sketch"))
        assertNull(missing.duplicate("sketch", "Copy", 1_000L))
        missing.pruneSheets("sketch", keep = 0)
    }
}
