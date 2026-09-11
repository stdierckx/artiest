package be.thalos.artiest.project

import android.graphics.Color
import be.thalos.artiest.doc.CommitQueue
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.FloatOp
import be.thalos.artiest.doc.LayerBlend
import be.thalos.artiest.doc.LayerOp
import be.thalos.artiest.doc.SelectOp
import be.thalos.artiest.engine.ink.Stroke
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
 * Save it, close it, open it: the same drawing.
 *
 * This is the test the whole item exists for, and it is deliberately end to end
 * — `ProjectSaver` writing real PNGs through a real rasterizer, and
 * `ProjectLoader` reading them back into a second `Document` that has never
 * seen the first. A test that faked either half would pass on the day the two
 * disagreed about what a sheet is called.
 *
 * The one piece of the app that is written out again here is the render
 * thread's sink, because there is no render thread. [Render] does what
 * `InkSurfaceView.commitSink` does for layer operations and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectRoundTripTest {

    private val root: File = Files.createTempDirectory("artiest-round-trip").toFile()
    private val files = ProjectFiles(root)
    private val documents = ArrayList<Document>()

    @After
    fun cleanUp() {
        for (d in documents) d.close()
        root.deleteRecursively()
    }

    private fun document(w: Int = 64, h: Int = 48): Document =
        Document(w, h, enforceOffMainThread = false).also { documents += it }

    /** The render thread's half, for layer operations. See the class header. */
    private class Render(private val document: Document) : CommitQueue.Sink {
        override fun onStroke(stroke: Stroke) = Unit
        override fun onClear() = Unit
        override fun onUndo() = Unit
        override fun onRedo() = Unit
        override fun onSelect(op: SelectOp) = Unit
        override fun onFloat(op: FloatOp) = Unit
        override fun onLayers(op: LayerOp) {
            if (!document.layers.apply(op)) return
            if (op is LayerOp.Open) document.resetHistory()
        }
    }

    private fun Document.render() {
        drainCommits(Render(this))
    }

    private fun paint(document: Document, index: Int, colour: Int) {
        document.layers.entryAt(index).layer.write { it.drawColor(colour) }
    }

    private fun pixel(document: Document, index: Int, x: Int, y: Int): Int {
        var argb = 0
        document.layers.entryAt(index).layer.read { argb = it.getPixel(x, y) }
        return argb
    }

    @Test
    fun `a drawing saved, closed and opened is the same drawing`() {
        val made = document()
        made.layers.apply(LayerOp.Add(made.newLayer(), "Ink"))
        made.layers.apply(LayerOp.Add(made.newLayer(), "Colour"))
        val ink = made.layers.entryAt(1).id
        made.layers.apply(LayerOp.SetOpacity(ink, 0.4f))
        made.layers.apply(LayerOp.SetBlend(ink, LayerBlend.MULTIPLY))
        made.layers.apply(LayerOp.SetVisible(made.layers.entryAt(2).id, false))
        made.layers.apply(LayerOp.SetActive(ink))
        paint(made, 0, Color.RED)
        paint(made, 1, Color.GREEN)

        val project = assertNotNull(files.create("Sketch", 64, 48, 1_000L))
        val saver = ProjectSaver(files)
        val saved = assertIs<SaveResult.Saved>(runBlocking { saver.save(project, made, 2_000L) })

        // A second document that has never seen the first, which is what the
        // next launch of the app is.
        val opened = document()
        val reader = ProjectSaver(files)
        val stored = assertNotNull(files.load(saved.project.id)).project!!
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, stored, opened, reader) }
        )
        opened.render()

        assertEquals(3, result.sheets)
        assertEquals(emptyList(), result.notes)
        assertEquals(3, opened.layers.size)
        assertEquals(listOf("Layer 1", "Ink", "Colour"), (0 until 3).map { opened.layers.entryAt(it).name })
        assertEquals(0.4f, opened.layers.entryAt(1).opacity)
        assertEquals(LayerBlend.MULTIPLY, opened.layers.entryAt(1).blend)
        assertFalse(opened.layers.entryAt(2).visible)
        assertEquals(1, opened.layers.activePosition, "and the pen is on the sheet it was on")

        assertEquals(Color.RED, pixel(opened, 0, 10, 10))
        assertEquals(Color.GREEN, pixel(opened, 1, 10, 10))
        assertEquals(0, pixel(opened, 2, 10, 10), "and the sheet nothing was drawn on is empty")
    }

    @Test
    fun `the first save after an open writes nothing`() {
        val made = document()
        paint(made, 0, Color.RED)
        val project = assertNotNull(files.create("Sketch", 64, 48, 1_000L))
        val saver = ProjectSaver(files)
        val saved = assertIs<SaveResult.Saved>(runBlocking { saver.save(project, made, 2_000L) })

        val opened = document()
        val reader = ProjectSaver(files)
        runBlocking { ProjectLoader.open(files, saved.project, opened, reader) }
        opened.render()

        val again = assertIs<SaveResult.Saved>(runBlocking { reader.save(saved.project, opened, 3_000L) })
        assertEquals(0, again.sheetsWritten, "the bytes on disk are already this drawing")
    }

    @Test
    fun `a sheet whose file has gone comes back empty, in its place, and says so`() {
        val made = document()
        made.layers.apply(LayerOp.Add(made.newLayer(), "Ink"))
        paint(made, 0, Color.RED)
        paint(made, 1, Color.GREEN)
        val project = assertNotNull(files.create("Sketch", 64, 48, 1_000L))
        val saved = assertIs<SaveResult.Saved>(
            runBlocking { ProjectSaver(files).save(project, made, 2_000L) }
        )
        assertTrue(files.sheetOf(project.id, 0).delete())

        val opened = document()
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, saved.project, opened, ProjectSaver(files)) }
        )
        opened.render()

        assertEquals(2, opened.layers.size, "the stack still matches the file")
        assertEquals(0, pixel(opened, 0, 10, 10))
        assertEquals(Color.GREEN, pixel(opened, 1, 10, 10), "and the sheet above it is where it was")
        assertTrue(result.notes.any { "could not be read" in it })
    }

    @Test
    fun `a project with no sheets opens as one empty sheet`() {
        val project = assertNotNull(files.create("Empty", 64, 48, 1_000L))
        val opened = document()
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, project, opened, ProjectSaver(files)) }
        )
        opened.render()

        assertEquals(1, opened.layers.size)
        assertEquals(1, result.sheets)
        assertEquals(emptyList(), result.notes, "a new project is not a broken one")
    }

    @Test
    fun `a drawing from a bigger page goes in the corner and says so`() {
        val made = document(80, 60)
        paint(made, 0, Color.RED)
        val project = assertNotNull(files.create("Wide", 80, 60, 1_000L))
        val saved = assertIs<SaveResult.Saved>(
            runBlocking { ProjectSaver(files).save(project, made, 2_000L) }
        )

        val small = document(40, 30)
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, saved.project, small, ProjectSaver(files)) }
        )
        small.render()

        assertEquals(Color.RED, pixel(small, 0, 5, 5), "what fits is there")
        assertTrue(result.notes.any { "in the corner" in it })
    }

    @Test
    fun `opening clears the undo history, because it describes sheets that are gone`() {
        val made = document()
        paint(made, 0, Color.RED)
        val project = assertNotNull(files.create("Sketch", 64, 48, 1_000L))
        val saved = assertIs<SaveResult.Saved>(
            runBlocking { ProjectSaver(files).save(project, made, 2_000L) }
        )

        val opened = document()
        // Something to undo, from the drawing that is about to be replaced.
        opened.snapshotBeforeClear()
        assertTrue(opened.canUndo, "there was a step to lose")

        runBlocking { ProjectLoader.open(files, saved.project, opened, ProjectSaver(files)) }
        opened.render()
        assertFalse(opened.canUndo, "and it is gone rather than pointing at closed pixels")
    }
}
