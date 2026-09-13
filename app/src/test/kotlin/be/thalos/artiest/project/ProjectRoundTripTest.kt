package be.thalos.artiest.project

import be.thalos.artiest.doc.StrokeOp
import android.graphics.Path
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.SampleLog
import be.thalos.artiest.engine.ink.StrokeRecord
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import be.thalos.artiest.doc.PendingStroke
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
        override fun onStroke(stroke: Stroke, record: PendingStroke?) = Unit
        override fun onClear() = Unit
        override fun onUndo() = Unit
        override fun onRedo() = Unit
        override fun onSelect(op: SelectOp) = Unit
        override fun onStrokeOp(op: StrokeOp) = Unit
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
    // ---- Ik6: the strokes beside the pixels --------------------------------

    private fun pending(y: Float, seed: Int = 1): PendingStroke {
        val log = SampleLog()
        for (i in 0 until 20) log.add(4f + i, y, 0.7f, 0.1f, 0f, i * 3.1f)
        return PendingStroke(
            samples = log.pack(),
            sampleCount = log.count,
            seed = seed,
            colorArgb = Color.BLACK,
            erase = false,
            brushText = BrushCodec.encode(Brush().apply { sizeMax = 9f }),
            bounds = Bounds.of(0f, y - 5f, 30f, y + 5f),
        )
    }

    /**
     * The whole of Ik6 in one test: an ink sheet's strokes, its brush table and
     * its clip table survive a save, a close and an open, and the sheet comes
     * back editable.
     *
     * The clip is the half that is easy to leave out and expensive to leave
     * out. A stroke drawn into a selection is *clipped pixels*; if the clip
     * table did not survive, the first edit after reopening would re-render
     * that stroke outside its stencil — silently, and nowhere near the thing
     * that caused it.
     */
    @Test
    fun `an ink sheet keeps its strokes, its brushes and its clips across a save`() {
        val made = document()
        made.layers.apply(LayerOp.AddVector(made.newLayer(), "Ink"))
        val sheet = made.layers.active.vector!!
        val clip = Path().apply { addRect(2f, 2f, 40f, 40f, Path.Direction.CW) }
        val a = sheet.append(pending(10f), null)
        val b = sheet.append(pending(20f, seed = 9), clip)
        paint(made, 0, Color.RED)

        val project = assertNotNull(files.create("Inked", 64, 48, 1_000L))
        val saver = ProjectSaver(files)
        val saved = assertIs<SaveResult.Saved>(runBlocking { saver.save(project, made, 2_000L) })
        assertTrue(File(files.dirFor(saved.project.id), "strokes/1.ink").isFile)
        assertFalse(
            File(files.dirFor(saved.project.id), "strokes/0.ink").isFile,
            "an ordinary sheet should not get a stroke file",
        )

        val opened = document()
        val stored = assertNotNull(files.load(saved.project.id)).project!!
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, stored, opened, ProjectSaver(files)) }
        )
        opened.render()
        assertEquals(emptyList(), result.notes)

        assertNull(opened.layers.entryAt(0).vector, "the raster sheet stayed raster")
        val back = assertNotNull(opened.layers.entryAt(1).vector)
        assertTrue(back.intact)
        assertEquals(2, back.size)
        assertEquals(listOf(a.id, b.id), back.strokes.map { it.id })
        assertEquals(listOf(a.seed, b.seed), back.strokes.map { it.seed })
        assertEquals(1, back.brushes.size, "one nib, one table entry")
        assertEquals(9f, back.brushAt(back.strokes[0].brush).sizeMax)
        assertEquals(StrokeRecord.NO_CLIP, back.strokes[0].clip)
        assertEquals(0, back.strokes[1].clip)
        assertNotNull(back.clipAt(0))

        // And the samples themselves, byte for byte.
        assertContentEquals(a.copyPackedBytes(), back.strokes[0].copyPackedBytes())

        // The sheet is live: a tap finds the stroke it drew.
        assertEquals(b.id, back.hit(14f, 20f, 2f))
    }

    /**
     * The PNG is what the drawing looks like; the strokes are what it can be
     * edited from. Losing the second must not cost the first — which is
     * `docs/inker-plan.md`'s stated reason for writing both.
     */
    @Test
    fun `a stroke file that cannot be read costs editability and not the drawing`() {
        val made = document()
        made.layers.apply(LayerOp.AddVector(made.newLayer(), "Ink"))
        made.layers.active.vector!!.append(pending(10f), null)
        paint(made, 1, Color.BLUE)

        val project = assertNotNull(files.create("Inked", 64, 48, 1_000L))
        val saved = assertIs<SaveResult.Saved>(
            runBlocking { ProjectSaver(files).save(project, made, 2_000L) }
        )
        File(files.dirFor(saved.project.id), "strokes/1.ink").writeText("not a stroke file")

        val opened = document()
        val stored = assertNotNull(files.load(saved.project.id)).project!!
        val result = assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, stored, opened, ProjectSaver(files)) }
        )
        opened.render()

        assertEquals(Color.BLUE, pixel(opened, 1, 10, 10), "the pixels are still there")
        val back = assertNotNull(opened.layers.entryAt(1).vector)
        assertFalse(back.intact, "and the sheet knows it cannot be rebuilt")
        assertEquals(0, back.size)
        assertTrue(result.notes.any { it.contains("cannot be edited") }, "${result.notes}")
    }

    @Test
    fun `a sheet that stops keeping strokes loses its stroke file`() {
        val made = document()
        made.layers.apply(LayerOp.AddVector(made.newLayer(), "Ink"))
        val inkId = made.layers.active.id
        made.layers.active.vector!!.append(pending(10f), null)

        val project = assertNotNull(files.create("Inked", 64, 48, 1_000L))
        val saver = ProjectSaver(files)
        val first = assertIs<SaveResult.Saved>(runBlocking { saver.save(project, made, 2_000L) })
        val ink = File(files.dirFor(first.project.id), "strokes/1.ink")
        assertTrue(ink.isFile)

        made.layers.apply(LayerOp.Delete(inkId))
        val second = assertIs<SaveResult.Saved>(
            runBlocking { saver.save(first.project, made, 3_000L) }
        )
        assertEquals(1, second.project.sheets.size)
        assertFalse(ink.isFile, "the deleted sheet's strokes were left behind")
    }

    /**
     * The autosave is a poll, so it has to notice a change that moves no
     * pixels. Undoing a vector edit repaints the sheet and so moves both — but
     * a change to the record list that happens to repaint identically would be
     * missed by a saver that only watched `Layer.revision`.
     */
    @Test
    fun `the saver notices a change to the stroke list alone`() {
        val made = document()
        made.layers.apply(LayerOp.AddVector(made.newLayer(), "Ink"))
        val sheet = made.layers.active.vector!!
        val project = assertNotNull(files.create("Inked", 64, 48, 1_000L))
        val saver = ProjectSaver(files)
        val saved = assertIs<SaveResult.Saved>(runBlocking { saver.save(project, made, 2_000L) })
        assertFalse(saver.dirty(made))

        sheet.append(pending(10f), null)
        assertTrue(saver.dirty(made), "a new record left the saver thinking it was clean")
        runBlocking { saver.save(saved.project, made, 3_000L) }
        assertFalse(saver.dirty(made))
    }

    /**
     * A version 1 file has no `strokes` field and is a drawing of ordinary
     * sheets, which is what it is. Nothing about opening one changes.
     */
    @Test
    fun `a file from before ink layers opens as ordinary sheets`() {
        val made = document()
        paint(made, 0, Color.RED)
        val project = assertNotNull(files.create("Old", 64, 48, 1_000L))
        val saved = assertIs<SaveResult.Saved>(
            runBlocking { ProjectSaver(files).save(project, made, 2_000L) }
        )
        val manifest = File(files.dirFor(saved.project.id), "project.json")
        manifest.writeText(manifest.readText().replace("\"artiest_project\": 2", "\"artiest_project\": 1"))

        val opened = document()
        val stored = assertNotNull(files.load(saved.project.id)).project!!
        assertIs<OpenResult.Opened>(
            runBlocking { ProjectLoader.open(files, stored, opened, ProjectSaver(files)) }
        )
        opened.render()
        assertNull(opened.layers.entryAt(0).vector)
        assertEquals(Color.RED, pixel(opened, 0, 10, 10))
    }
}