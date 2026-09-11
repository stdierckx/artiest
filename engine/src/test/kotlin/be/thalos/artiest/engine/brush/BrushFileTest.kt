package be.thalos.artiest.engine.brush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec's header: version 2's whole change.
 *
 * The version bump exists to be *survived*, so the test that matters is the one
 * that reads a version 1 file. Everything the app has ever saved is one.
 */
class BrushFileTest {

    @Test
    fun `a saved brush round trips with its name`() {
        val brush = BrushPreset.PENCIL.create()
        brush.sizeMax = 21f
        val entry = BrushEntry.fromText("soft-2b", "Soft 2B", BrushCodec.encode(brush))

        val back = assertNotNull(BrushCodec.decodeFile(BrushCodec.encodeFile(entry)))
        assertEquals("soft-2b", back.id)
        assertEquals("Soft 2B", back.label)
        assertEquals(BrushOrigin.SAVED, back.origin)
        assertEquals(21f, back.create().sizeMax)
        assertTrue(back.create().aspect.inputCount > 0, "and the tilt came with it")
    }

    @Test
    fun `tags come back even though nothing reads them yet`() {
        val entry = BrushEntry.fromText(
            "inky", "Inky", BrushCodec.encode(Brush()), tags = listOf("ink", "liner"),
        )
        val back = assertNotNull(BrushCodec.decodeFile(BrushCodec.encodeFile(entry)))
        assertEquals(listOf("ink", "liner"), back.tags)
    }

    @Test
    fun `a version 1 file still loads`() {
        // Hand-written as the old build wrote it: no header at all.
        val old = """
            artiest-brush 1
            size 1.5 48.0
            hardness 0.72
            opacity 0.97
            flow 1.0
        """.trimIndent()
        val brush = assertNotNull(BrushCodec.decode(old), "the brush itself")
        assertEquals(48f, brush.sizeMax)

        val entry = assertNotNull(BrushCodec.decodeFile(old, fallbackId = "found"))
        assertEquals("found", entry.id, "a file with no id gets one")
        assertEquals("found", entry.label)
        assertEquals(BrushOrigin.SAVED, entry.origin)
        assertEquals(48f, entry.create().sizeMax)
    }

    @Test
    fun `a line this build does not understand is skipped rather than fatal`() {
        val fromLater = BrushCodec.encodeFile(
            BrushEntry.fromText("x", "X", BrushCodec.encode(Brush())),
        ) + "\nwetness 0.4 0.9\n"
        val back = assertNotNull(BrushCodec.decodeFile(fromLater))
        assertEquals("X", back.label)
    }

    @Test
    fun `a file cannot claim to be shipped`() {
        val pretending = BrushCodec.encodeFile(
            BrushEntry.fromText("mine", "Mine", BrushCodec.encode(Brush())),
        ).replace("origin SAVED", "origin BUILT_IN")
        assertEquals(BrushOrigin.SAVED, assertNotNull(BrushCodec.decodeFile(pretending)).origin)
    }

    @Test
    fun `a name with spaces and newlines cannot break the format`() {
        val entry = BrushEntry.fromText(
            "odd", "two\nlines and  spaces", BrushCodec.encode(Brush()),
        )
        val text = BrushCodec.encodeFile(entry)
        assertEquals(
            1, text.split('\n').count { it.startsWith("label ") },
            "one label line, whatever was typed",
        )
        assertEquals("two lines and  spaces", assertNotNull(BrushCodec.decodeFile(text)).label)
    }

    @Test
    fun `something that is not a brush is refused`() {
        assertNull(BrushCodec.decodeFile("hello"))
        assertNull(BrushCodec.decodeFile(null))
        assertNull(BrushCodec.decodeFile(""))
    }
}
