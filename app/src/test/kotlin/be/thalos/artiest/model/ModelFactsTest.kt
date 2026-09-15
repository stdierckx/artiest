package be.thalos.artiest.model

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lr12. Whether a model brings a surface of its own.
 *
 * Worth a test rather than a comment because it is the wrong answer that is
 * invisible: say *bare* about a photographic scan and the artist loses the
 * surface they chose the model for, and the only sign is that a bust they
 * remember as marble is grey. Say *dressed* about a white one and the pane is
 * back to the white-on-white this was written to end.
 *
 * Robolectric for `org.json` alone — the parser is the platform's, and the
 * stubbed one in a plain JVM test throws on everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelFactsTest {

    /** A GLB whose JSON chunk is [json]. Padded to four bytes, as the spec asks. */
    private fun glb(json: String): ByteArray {
        var text = json.toByteArray(Charsets.UTF_8)
        while (text.size % 4 != 0) text += ' '.code.toByte()
        val buffer = ByteBuffer.allocate(20 + text.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67)          // glTF
        buffer.putInt(2)
        buffer.putInt(20 + text.size)
        buffer.putInt(text.size)
        buffer.putInt(0x4E4F534A)          // JSON
        buffer.put(text)
        return buffer.array()
    }

    @Test
    fun `a scan with a picture on it wears its own surface`() {
        val json = """{"asset":{"version":"2.0"},"images":[{"mimeType":"image/jpeg"}],
            "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}]}"""
        assertTrue(wearsItsOwnSurface(glb(json)))
    }

    @Test
    fun `a mesh with no materials at all is bare`() {
        val json = """{"asset":{"version":"2.0"},"meshes":[{"primitives":[{}]}]}"""
        assertFalse(wearsItsOwnSurface(glb(json)))
    }

    /**
     * The case the library is actually full of: `stl-to-glb.py` gives every
     * mesh it converts one flat near-white material, because glTF's own default
     * is worse. On screen that is the same white bust, so it is the same answer.
     */
    @Test
    fun `a flat untextured material is still bare`() {
        val json = """{"asset":{"version":"2.0"},"materials":[{"name":"plaster",
            "pbrMetallicRoughness":{"baseColorFactor":[0.82,0.8,0.77,1.0]}}]}"""
        assertFalse(wearsItsOwnSurface(glb(json)))
    }

    @Test
    fun `an empty images array is bare`() {
        assertFalse(wearsItsOwnSurface(glb("""{"asset":{"version":"2.0"},"images":[]}""")))
    }

    /** Nothing readable is no reason to paint over it. */
    @Test
    fun `rubbish is left dressed`() {
        assertTrue(wearsItsOwnSurface(ByteArray(0)))
        assertTrue(wearsItsOwnSurface(ByteArray(64)))
        assertTrue(wearsItsOwnSurface(glb("{not json at all")))
    }

    /** A chunk that claims more bytes than the file has must not be read. */
    @Test
    fun `a truncated json chunk is left dressed`() {
        val whole = glb("""{"asset":{"version":"2.0"}}""")
        assertTrue(wearsItsOwnSurface(whole.copyOf(whole.size - 8)))
    }
}
