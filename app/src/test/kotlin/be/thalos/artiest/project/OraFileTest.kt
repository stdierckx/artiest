package be.thalos.artiest.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A drawing out to an `.ora` and back in as another one.
 *
 * Robolectric NATIVE, because the whole question is whether the pixels arrive:
 * the default shadow would hand back a zip full of PNGs of nothing and every
 * assertion here would pass.
 *
 * The last test is the one that is not about our own files at all. Krita trims
 * a layer to what is drawn on it and records where it goes, so a foreign file
 * is normally a set of small images at offsets — and a reader that ignored them
 * would put every layer in the top-left corner, which looks like a bug in the
 * drawing rather than in the reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OraFileTest {

    private val root: File = Files.createTempDirectory("artiest-ora").toFile()
    private val files = ProjectFiles(File(root, "projects"))
    private val document = Document(64, 48, enforceOffMainThread = false)

    @After
    fun cleanUp() {
        document.close()
        root.deleteRecursively()
    }

    private fun paint(index: Int, colour: Int) {
        document.layers.entryAt(index).layer.write { it.drawColor(colour) }
    }

    private fun savedProject(): Project {
        val made = assertNotNull(files.create("Sketch", 64, 48, 1_000L))
        val saved = runBlocking { ProjectSaver(files).save(made, document, 2_000L) }
        return assertIs<SaveResult.Saved>(saved).project
    }

    private fun writeOra(project: Project): File {
        val out = File(root, "out.ora")
        val result = runBlocking {
            FileOutputStream(out).use { OraFile.write(files, project, document, it) }
        }
        assertIs<OraResult.Written>(result, (result as? OraResult.Failed)?.reason ?: "")
        return out
    }

    @Test
    fun `what is written is an ora that anything can open`() {
        document.layers.apply(LayerOp.Add(document.newLayer(), "Ink"))
        paint(0, Color.RED)
        paint(1, Color.GREEN)
        val project = savedProject()

        val file = writeOra(project)
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("mimetype", names.first(), "the format says this one is first")
            assertEquals(
                ZipEntry.STORED,
                assertNotNull(zip.getEntry("mimetype")).method,
                "and stored, so a reader can find it without unzipping",
            )
            assertEquals(
                Ora.MIMETYPE,
                zip.getInputStream(zip.getEntry("mimetype")).readBytes().toString(Charsets.US_ASCII),
            )
            assertTrue(Ora.STACK in names)
            assertTrue(Ora.MERGED in names, "the flattened picture the format requires")
            assertTrue(Ora.dataFor(0) in names && Ora.dataFor(1) in names)

            // The sheets went across as they were, not re-encoded.
            assertEquals(
                files.sheetOf(project.id, 1).readBytes().toList(),
                zip.getInputStream(zip.getEntry(Ora.dataFor(1))).readBytes().toList(),
            )

            val bytes = zip.getInputStream(zip.getEntry(Ora.MERGED)).readBytes()
            val merged = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(Color.GREEN, merged.getPixel(10, 10), "the top sheet is on top")
        }
    }

    @Test
    fun `and it comes back as a new drawing with its sheets in order`() {
        document.layers.apply(LayerOp.Add(document.newLayer(), "Ink"))
        val ink = document.layers.entryAt(1).id
        document.layers.apply(LayerOp.SetOpacity(ink, 0.25f))
        document.layers.apply(LayerOp.SetBlend(ink, LayerBlend.SCREEN))
        paint(0, Color.RED)
        paint(1, Color.GREEN)
        val project = savedProject()
        val file = writeOra(project)

        val read = runBlocking { OraFile.read(files, file, "Sketch from a file", 3_000L) }
        val opened = assertIs<OraResult.Read>(read, (read as? OraResult.Failed)?.reason ?: "").project

        assertEquals("sketch-from-a-file", opened.id, "a new project, never over the old one")
        assertEquals(listOf("Layer 1", "Ink"), opened.sheets.map { it.name })
        assertEquals(0.25f, opened.sheets[1].opacity)
        assertEquals(LayerBlend.SCREEN, opened.sheets[1].blend)
        assertEquals(64, opened.widthPx)

        val bottom = BitmapFactory.decodeFile(files.sheetOf(opened.id, 0).path)
        val top = BitmapFactory.decodeFile(files.sheetOf(opened.id, 1).path)
        assertEquals(Color.RED, bottom.getPixel(5, 5))
        assertEquals(Color.GREEN, top.getPixel(5, 5), "and the right way up")
    }

    @Test
    fun `a layer that is smaller than the page goes where the file says`() {
        // What Krita writes: a trimmed layer with an offset. Built by hand,
        // because nothing in this app produces one.
        val patch = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        patch.eraseColor(Color.BLUE)
        val png = java.io.ByteArrayOutputStream()
        patch.compress(Bitmap.CompressFormat.PNG, 100, png)
        patch.recycle()

        val foreign = File(root, "foreign.ora")
        ZipOutputStream(FileOutputStream(foreign)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write(Ora.MIMETYPE.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(Ora.STACK))
            zip.write(
                """
                <image version="0.0.3" w="64" h="48">
                  <stack><layer name="Patch" src="data/p.png" x="20" y="10"/></stack>
                </image>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data/p.png"))
            zip.write(png.toByteArray())
            zip.closeEntry()
        }

        val read = runBlocking { OraFile.read(files, foreign, "Foreign", 4_000L) }
        val opened = assertIs<OraResult.Read>(read, (read as? OraResult.Failed)?.reason ?: "").project

        val sheet = BitmapFactory.decodeFile(files.sheetOf(opened.id, 0).path)
        assertEquals(64, sheet.width, "it is the page now, not the patch")
        assertEquals(Color.BLUE, sheet.getPixel(22, 12), "and the patch is where it was put")
        assertEquals(0, sheet.getPixel(2, 2), "with nothing where it was not")
    }

    @Test
    fun `something that is not an ora is refused rather than half read`() {
        val rubbish = File(root, "rubbish.ora")
        rubbish.writeText("this is not a zip")
        val read = runBlocking { OraFile.read(files, rubbish, "Rubbish", 5_000L) }
        assertIs<OraResult.Failed>(read)
        assertEquals(0, files.list().size, "and it left no project behind")
    }
}
