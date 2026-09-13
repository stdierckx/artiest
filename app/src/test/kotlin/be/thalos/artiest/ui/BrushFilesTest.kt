package be.thalos.artiest.ui

import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushPreset
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The directory of saved brushes.
 *
 * A plain JVM test, no Robolectric: nothing here touches a pixel or a
 * `Context`. What it is really about is the three ways a directory goes wrong —
 * a file that will not parse, a name that is not a name, and two brushes that
 * want to be called the same thing — because a shelf that throws on any of them
 * is a shelf that takes the app down on startup.
 */
class BrushFilesTest {

    private val dir: File = Files.createTempDirectory("artiest-brushes").toFile()
    private val files = BrushFiles(dir)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, label: String = id, size: Float = 20f): BrushEntry =
        BrushEntry.fromText(
            id, label,
            BrushCodec.encode(BrushPreset.PENCIL.create().also { it.sizeMax = size }),
        )

    @Test
    fun `a saved brush comes back after a restart`() {
        assertTrue(files.save(entry("soft-2b", "Soft 2B", size = 33f)))
        val back = assertNotNull(BrushFiles(dir).list().firstOrNull())
        assertEquals("soft-2b", back.id)
        assertEquals("Soft 2B", back.label)
        assertEquals(33f, back.create().sizeMax)
    }

    @Test
    fun `the shelf is what is shipped plus what is on disk`() {
        files.save(entry("mine", "Mine"))
        val library = files.library()
        assertEquals(
            listOf("pen", "pencil", "marker", "hard_eraser", "soft_eraser", "mine"),
            library.entries.map { it.id },
        )
        assertTrue(library.hasSaved)
    }

    @Test
    fun `brushes are listed by name and not by when they were written`() {
        files.save(entry("zulu", "Zulu"))
        files.save(entry("alpha", "alpha"))
        files.save(entry("mike", "Mike"))
        assertEquals(listOf("alpha", "Mike", "Zulu"), files.list().map { it.label })
    }

    @Test
    fun `a file that is not a brush is skipped and the rest still load`() {
        files.save(entry("good", "Good"))
        File(dir, "rubbish.brush").writeText("this is not a brush")
        File(dir, "notes.txt").writeText("nor is this")
        assertEquals(listOf("Good"), files.list().map { it.label })
    }

    @Test
    fun `a brush can be replaced and deleted`() {
        files.save(entry("mine", "Mine", size = 10f))
        files.save(entry("mine", "Mine", size = 40f))
        assertEquals(1, files.list().size, "the same id is the same brush")
        assertEquals(40f, files.list().first().create().sizeMax)

        assertTrue(files.delete("mine"))
        assertEquals(0, files.list().size)
        assertFalse(files.exists("mine"))
    }

    @Test
    fun `two brushes may share a name`() {
        files.save(entry(files.freeId("2B"), "2B"))
        val second = files.freeId("2B")
        assertEquals("2b-2", second)
        files.save(entry(second, "2B"))
        assertEquals(listOf("2B", "2B"), files.list().map { it.label })
        assertEquals(setOf("2b", "2b-2"), files.list().map { it.id }.toSet())
    }

    @Test
    fun `a name that is not a file name still becomes one`() {
        val id = files.freeId("../../etc/passwd")
        assertTrue(files.save(entry(id, "Nasty")))
        assertEquals(
            listOf("nasty"), files.list().map { it.label.lowercase() },
            "it saved, under a name that is only letters",
        )
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
    }

    @Test
    fun `a directory that is not there yet is not an error`() {
        val missing = BrushFiles(File(dir, "never-made"))
        assertEquals(emptyList(), missing.list())
        assertEquals(
            BrushPreset.entries.size,
            missing.library().entries.size,
            "the shipped ones, and no crash",
        )
        assertTrue(missing.save(entry("first", "First")), "and saving makes it")
        assertEquals(1, missing.list().size)
    }

    @Test
    fun `a half written file cannot replace a good one`() {
        files.save(entry("mine", "Mine", size = 12f))
        // What an interrupted save leaves behind: the temporary, never renamed.
        File(dir, "mine.brush.tmp").writeText("truncated")
        assertEquals(12f, assertNotNull(files.list().firstOrNull()).create().sizeMax)
        assertEquals(1, files.list().size, "and the leftover is not a brush")
    }
}
