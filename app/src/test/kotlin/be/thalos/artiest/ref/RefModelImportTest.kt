package be.thalos.artiest.ref

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lr12. What comes in through the picker, and what is turned away at the door.
 *
 * The one thing worth a test rather than a comment: **the two ways this fails
 * are both silent if the check is not made here.** A `.gltf` chosen instead of
 * a `.glb` and a download that stopped halfway both produce a file that sits in
 * the strip looking fine and shows nothing when it is opened, hours later, in
 * front of a drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefModelImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun library() = RefFiles(temp.newFolder())

    /** A GLB header: the magic, version 2, and a length that agrees. */
    private fun glb(payload: Int = 40, version: Int = 2, claimed: Int? = null): ByteArray {
        val total = 12 + payload
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("glTF".toByteArray())
        out.putInt(version)
        out.putInt(claimed ?: total)
        repeat(payload) { out.put((it % 251).toByte()) }
        return out.array()
    }

    private fun uriFor(name: String, bytes: ByteArray): Uri {
        val file = File(temp.newFolder(), name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun add(name: String, bytes: ByteArray, files: RefFiles = library()) =
        runBlocking { RefModelImport.add(context, files, uriFor(name, bytes)) } to files

    @Test
    fun `a glb comes in, and its bytes arrive whole`() {
        val (result, files) = add("marble_bust_01.glb", glb())
        val added = (result as? RefModelImport.Result.Added)?.model
            ?: error("refused: ${(result as RefModelImport.Result.Failed).reason}")
        assertEquals(RefKind.MODEL, added.kind)
        assertContentEquals(glb(), files.loadModel(added.id))
        assertEquals(1, files.list().size)
    }

    @Test
    fun `a gltf is refused with the reason a person can act on`() {
        val json = """{"asset":{"version":"2.0"},"buffers":[{"uri":"x.bin"}]}""".toByteArray()
        val (result, _) = add("bust.gltf", json)
        val failed = result as RefModelImport.Result.Failed
        assertTrue(failed.reason.contains(".glb"), failed.reason)
    }

    @Test
    fun `something that is not a model at all is refused`() {
        val (result, files) = add("holiday.jpg", ByteArray(2000) { 0x7F })
        assertTrue(result is RefModelImport.Result.Failed)
        assertEquals(emptyList(), files.list())
    }

    @Test
    fun `a truncated download is refused`() {
        // The header claims more bytes than arrived, which is what a download
        // that stopped halfway looks like.
        val (result, _) = add("half.glb", glb(payload = 20, claimed = 4096))
        assertTrue(result is RefModelImport.Result.Failed)
    }

    @Test
    fun `a glTF 1 file is refused`() {
        val (result, _) = add("old.glb", glb(version = 1))
        assertTrue(result is RefModelImport.Result.Failed)
    }

    @Test
    fun `a file past the cap is refused before it is written`() {
        val big = glb(payload = (RefFiles.MAX_MODEL_BYTES + 32).toInt())
        val (result, files) = add("huge.glb", big)
        val failed = result as RefModelImport.Result.Failed
        assertTrue(failed.reason.contains("MB"), failed.reason)
        assertEquals(emptyList(), files.list())
    }
}
