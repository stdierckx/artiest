package be.thalos.artiest.project

import android.graphics.Color
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.LayerBlend
import be.thalos.artiest.doc.LayerOp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sheets out to disk, and the one number that matters: **how many of them were
 * written**.
 *
 * Robolectric NATIVE for `LayerTest`'s reason — the default shadow records draw
 * calls instead of rasterizing them, and a PNG of a canvas that drew nothing is
 * a PNG that passes every test here while being blank on a tablet.
 *
 * A small page on purpose. The saver's arithmetic does not know how big a sheet
 * is, and a 3300 x 2160 one costs 27.19 MiB and a real PNG encode per test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectSaverTest {

    private val root: File = Files.createTempDirectory("artiest-saver").toFile()
    private val files = ProjectFiles(root)
    private val saver = ProjectSaver(files)
    private val document = Document(64, 48, enforceOffMainThread = false)

    @After
    fun cleanUp() {
        document.close()
        root.deleteRecursively()
    }

    private fun project() = assertNotNull(files.create("Sketch", 64, 48, 1_000L))

    private fun paint(index: Int, colour: Int) {
        document.layers.entryAt(index).layer.write { it.drawColor(colour) }
    }

    private fun addSheet(name: String) {
        document.layers.apply(LayerOp.Add(document.newLayer(), name))
    }

    private fun save(p: Project, now: Long = 2_000L): SaveResult.Saved {
        val result = runBlocking { saver.save(p, document, now) }
        return assertIs<SaveResult.Saved>(result, (result as? SaveResult.Failed)?.reason ?: "")
    }

    @Test
    fun `a save writes a sheet, a manifest and a thumbnail`() {
        val p = project()
        paint(0, Color.RED)

        val saved = save(p)
        assertEquals(1, saved.sheetsWritten)
        assertTrue(files.sheetOf(p.id, 0).length() > 0, "the sheet is on disk with bytes in it")
        assertTrue(files.thumbnailOf(p.id).length() > 0)

        val back = assertNotNull(files.load(p.id)).project
        assertEquals(1, back?.sheets?.size)
        assertEquals(Project.fileFor(0), back?.sheets?.first()?.file)
        assertEquals(2, back?.revision, "and the revision moved")
        assertEquals(2_000L, back?.modified)
    }

    @Test
    fun `saving again writes nothing, because nothing changed`() {
        val p = project()
        paint(0, Color.RED)
        val first = save(p)

        val again = save(first.project, now = 3_000L)
        assertEquals(0, again.sheetsWritten, "the pixels are the pixels")
        assertEquals(3, again.project.revision, "but the file still says when it was last looked at")
    }

    @Test
    fun `drawing on one sheet of three rewrites one file`() {
        val p = project()
        addSheet("Ink")
        addSheet("Colour")
        paint(0, Color.RED)
        paint(1, Color.GREEN)
        paint(2, Color.BLUE)
        val first = save(p)
        assertEquals(3, first.sheetsWritten)

        paint(1, Color.BLACK)
        assertEquals(1, save(first.project, now = 3_000L).sheetsWritten)
    }

    @Test
    fun `reordering rewrites the sheets that moved, because the file is the position`() {
        val p = project()
        addSheet("Ink")
        paint(0, Color.RED)
        paint(1, Color.GREEN)
        val first = save(p)

        // Nothing has been drawn on. Every revision is where it was, and the
        // files would still be right if they were named after the sheet -- but
        // they are named after the place, and the places have swapped.
        val top = document.layers.entryAt(1).id
        document.layers.apply(LayerOp.Move(top, 0))

        assertEquals(2, save(first.project, now = 3_000L).sheetsWritten)
    }

    @Test
    fun `a deleted sheet takes its file with it`() {
        val p = project()
        addSheet("Ink")
        paint(0, Color.RED)
        paint(1, Color.GREEN)
        val first = save(p)
        assertTrue(files.sheetOf(p.id, 1).isFile)

        document.layers.apply(LayerOp.Delete(document.layers.entryAt(1).id))
        val after = save(first.project, now = 3_000L)

        assertEquals(1, after.project.sheets.size)
        assertFalse(files.sheetOf(p.id, 1).isFile, "and the orphan is gone rather than left to rot")
    }

    @Test
    fun `the manifest describes the stack and not the defaults`() {
        val p = project()
        addSheet("Ink")
        val ink = document.layers.entryAt(1).id
        document.layers.apply(LayerOp.SetOpacity(ink, 0.4f))
        document.layers.apply(LayerOp.SetBlend(ink, LayerBlend.MULTIPLY))
        document.layers.apply(LayerOp.SetVisible(ink, false))
        document.layers.apply(LayerOp.SetActive(ink))
        paint(0, Color.RED)

        val saved = save(p).project
        assertEquals(listOf("Layer 1", "Ink"), saved.sheets.map { it.name })
        assertEquals(0.4f, saved.sheets[1].opacity)
        assertEquals(LayerBlend.MULTIPLY, saved.sheets[1].blend)
        assertFalse(saved.sheets[1].visible)
        assertEquals(1, saved.active)
    }

    @Test
    fun `dirty is what the autosave asks, and it notices all four kinds of change`() {
        val p = project()
        paint(0, Color.RED)
        assertTrue(saver.dirty(document), "a drawing that has never been saved is dirty")

        val saved = save(p)
        assertFalse(saver.dirty(document), "and clean the moment it is on disk")

        paint(0, Color.BLUE)
        assertTrue(saver.dirty(document), "pixels")
        save(saved.project, now = 3_000L)

        document.layers.apply(LayerOp.SetName(document.layers.entryAt(0).id, "Sketch"))
        assertTrue(saver.dirty(document), "a name, which moves no pixels at all")
        val named = save(saved.project, now = 4_000L)

        addSheet("Ink")
        assertTrue(saver.dirty(document), "a sheet")
        val grown = save(named.project, now = 5_000L)

        document.layers.apply(LayerOp.SetActive(document.layers.entryAt(0).id))
        assertTrue(saver.dirty(document), "and which sheet the pen is on")
        save(grown.project, now = 6_000L)
        assertFalse(saver.dirty(document))
    }

    @Test
    fun `seeded sheets are taken as already written`() {
        val p = project()
        paint(0, Color.RED)
        // What the loader does: it built these layers from the files, so the
        // files are already a picture of them.
        saver.seed(p, listOf(document.layers.entryAt(0).layer))
        // The file has to exist for the claim to be believed -- see `clean`.
        files.sheetOf(p.id, 0).also { it.parentFile?.mkdirs() }.writeText("pretend pixels")

        assertEquals(0, save(p).sheetsWritten)
    }

    @Test
    fun `a sheet the saver has no file for is written even if it looks clean`() {
        val p = project()
        paint(0, Color.RED)
        saver.seed(p, listOf(document.layers.entryAt(0).layer))

        // The claim is that the file is on disk. It is not, and a save that
        // believed the bookkeeping over the filesystem would leave a project
        // whose manifest names a sheet that does not exist.
        assertEquals(1, save(p).sheetsWritten)
    }
}
