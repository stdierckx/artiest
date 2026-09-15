package be.thalos.artiest.ref

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lr12. The 3D half of the same library.
 *
 * The thing under test that is not obvious is that **a model and a picture
 * share a directory and a naming scheme**, and that `<id>.jpg` means two
 * different things depending on the kind — the reference itself for a picture,
 * a disposable poster for a model. Most of what is here is about that sharing
 * not going wrong: a picture written by an older build must still read, a model
 * with no poster must still list, and a delete must take all three files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RefModelFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File

    private fun files(): RefFiles {
        dir = temp.newFolder()
        return RefFiles(dir)
    }

    /** Not a real mesh. Nothing in `RefFiles` parses one; that is the importer's job. */
    private fun glb(size: Int = 64) = ByteArray(size) { (it % 251).toByte() }

    private fun poster(colour: Int = Color.GREEN): Bitmap =
        Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(colour) }

    @Test
    fun `a model goes in and comes back as a model`() {
        val files = files()
        val added = assertNotNull(files.addModel(glb(), "marble bust", listOf("heads")))
        assertEquals(RefKind.MODEL, added.kind)
        assertEquals("marble bust", added.label)
        assertEquals(listOf("heads"), added.tags)

        val listed = assertNotNull(files.list().firstOrNull())
        assertEquals(added.id, listed.id)
        assertEquals(RefKind.MODEL, listed.kind)
        assertContentEquals(glb(), files.loadModel(added.id))
    }

    @Test
    fun `a model with no poster still lists`() {
        val files = files()
        val added = assertNotNull(files.addModel(glb()))
        assertFalse(files.hasPoster(added.id))
        assertEquals(1, files.list().size)
    }

    @Test
    fun `a poster is written beside the model and does not become the reference`() {
        val files = files()
        val added = assertNotNull(files.addModel(glb()))
        assertTrue(files.setPoster(added.id, poster()))
        assertTrue(files.hasPoster(added.id))

        val listed = assertNotNull(files.list().firstOrNull())
        // Still a model, and the strip can still read the poster the ordinary way.
        assertEquals(RefKind.MODEL, listed.kind)
        assertNotNull(files.loadSmall(added.id, 32))
        assertContentEquals(glb(), files.loadModel(added.id))
    }

    @Test
    fun `deleting a model takes the poster with it`() {
        val files = files()
        val added = assertNotNull(files.addModel(glb()))
        files.setPoster(added.id, poster())
        assertTrue(files.delete(added.id))
        assertEquals(emptyList(), files.list())
        assertNull(files.loadModel(added.id))
        assertFalse(files.hasPoster(added.id))
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `a model bigger than the cap is refused and leaves nothing behind`() {
        val files = files()
        assertNull(files.addModel(ByteArray((RefFiles.MAX_MODEL_BYTES + 1).toInt())))
        assertEquals(emptyList(), files.list())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `an empty file is refused`() {
        val files = files()
        assertNull(files.addModel(ByteArray(0)))
        assertEquals(emptyList(), files.list())
    }

    /**
     * The compatibility claim, made as a test rather than as a comment: a
     * picture's meta file is byte-for-byte what a build that had never heard of
     * models would have written.
     */
    @Test
    fun `a picture's meta file gains nothing`() {
        val files = files()
        val added = assertNotNull(
            files.add(
                Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) },
                "a hand",
            ),
        )
        val text = File(dir, added.id + RefFiles.META).readText()
        assertFalse(text.contains("kind"), text)
        assertTrue(text.contains("size 8 4"), text)
    }

    /** A meta file from an older build has no kind line and is a picture. */
    @Test
    fun `a file written before models reads as a picture`() {
        val files = files()
        File(dir, "r99.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "r99.txt").writeText("added 5\nsize 10 20\nlabel old\n")
        val listed = assertNotNull(files.list().firstOrNull())
        assertEquals(RefKind.PICTURE, listed.kind)
        assertEquals(10, listed.widthPx)
        assertEquals("old", listed.label)
    }

    /** A kind this build has never heard of is not a crash and not a model. */
    @Test
    fun `an unknown kind reads as a picture`() {
        val files = files()
        File(dir, "r98.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "r98.txt").writeText("added 5\nkind hologram\n")
        assertEquals(RefKind.PICTURE, files.list().firstOrNull()?.kind)
    }

    /** A meta file naming a model whose mesh is gone is skipped, not half-shown. */
    @Test
    fun `a model with no mesh is skipped`() {
        val files = files()
        File(dir, "r97.txt").writeText("added 5\nkind model\n")
        assertEquals(emptyList(), files.list())
    }

    /** Pictures and models sort together, newest first, and neither hides the other. */
    @Test
    fun `both kinds share one list`() {
        val files = files()
        val picture = assertNotNull(
            files.add(
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) },
            ),
        )
        Thread.sleep(2)
        val model = assertNotNull(files.addModel(glb()))
        val ids = files.list().map { it.id }
        assertEquals(listOf(model.id, picture.id), ids)
    }
}
