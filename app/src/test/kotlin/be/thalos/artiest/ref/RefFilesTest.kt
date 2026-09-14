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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reference library on disk.
 *
 * The rules here are `BrushFiles`' rules, because this is the same shape of
 * thing: a directory a user's data accumulates in, which is where a truncated
 * file, a file somebody edited by hand and a file from a later build all come
 * from. Nothing may throw and nothing may take the rest of the library with it.
 *
 * NATIVE graphics at SDK 34, because `Bitmap.compress` under the default shadow
 * writes nothing and every one of these would pass against a library that
 * stored no pixels at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RefFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun files() = RefFiles(temp.newFolder())

    private fun picture(colour: Int = Color.RED, w: Int = 8, h: Int = 4): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(colour) }

    @Test
    fun `a picture goes in and comes back`() {
        val f = files()
        val added = assertNotNull(f.add(picture(), "A hand", listOf("hands")))
        assertEquals(8, added.widthPx)
        assertEquals(4, added.heightPx)
        assertTrue(added.bytes > 0)

        val back = f.list().single()
        assertEquals(added.id, back.id)
        assertEquals("A hand", back.label)
        assertEquals(listOf("hands"), back.tags)
        assertEquals(8, back.widthPx)

        val pixels = assertNotNull(f.load(added.id))
        assertEquals(8, pixels.width)
    }

    @Test
    fun `the newest picture is first`() {
        val f = files()
        val first = assertNotNull(f.add(picture()))
        // The id is the clock, so two adds inside one millisecond would sort by
        // nothing at all. The list is what is being checked, not the clock.
        Thread.sleep(3)
        val second = assertNotNull(f.add(picture(Color.BLUE)))
        assertEquals(listOf(second.id, first.id), f.list().map { it.id })
    }

    @Test
    fun `a picture with no meta file is not a picture`() {
        val f = files()
        val added = assertNotNull(f.add(picture()))
        temp.root.walkTopDown().filter { it.name.endsWith(RefFiles.META) }.forEach { it.delete() }
        assertEquals(0, f.list().size)
        // And the pixels are still there, which is the point of two files: the
        // half that was lost is the half that can be described again.
        assertNotNull(f.load(added.id))
    }

    @Test
    fun `a meta file with no picture is not a picture`() {
        val f = files()
        assertNotNull(f.add(picture()))
        temp.root.walkTopDown().filter { it.name.endsWith(RefFiles.IMAGE) }.forEach { it.delete() }
        assertEquals(0, f.list().size)
    }

    @Test
    fun `a meta file full of nonsense does not take the library with it`() {
        val f = files()
        val good = assertNotNull(f.add(picture(), "Keep me"))
        Thread.sleep(3)
        val bad = assertNotNull(f.add(picture(Color.BLUE)))
        temp.root.walkTopDown()
            .first { it.name == bad.id + RefFiles.META }
            .writeText("   nonsense" + NL + "label")
        val list = f.list()
        // Both are still pictures: an unreadable *line* is skipped, not the
        // file. What the nonsense one loses is its label.
        assertEquals(2, list.size)
        assertEquals("Keep me", list.first { it.id == good.id }.label)
        assertEquals("", list.first { it.id == bad.id }.label)
    }

    @Test
    fun `a delete takes both files`() {
        val f = files()
        val added = assertNotNull(f.add(picture()))
        assertTrue(f.delete(added.id))
        assertEquals(0, f.list().size)
        assertNull(f.load(added.id))
        assertEquals(0L, f.bytes())
    }

    @Test
    fun `an id that is not a slug reaches no file`() {
        val f = files()
        assertNotNull(f.add(picture()))
        // The only field in this format that becomes a path. A caller that
        // asks for one of these is a caller that has been handed something it
        // did not get from `list`.
        assertNull(f.load("../../shared_prefs/chrome"))
        assertEquals(false, f.delete("../../shared_prefs/chrome"))
        assertEquals(1, f.list().size)
    }

    @Test
    fun `renaming keeps the pixels`() {
        val f = files()
        val added = assertNotNull(f.add(picture(), "Old"))
        assertTrue(f.update(added.copy(label = "New", tags = listOf("a", "b"))))
        val back = f.list().single()
        assertEquals("New", back.label)
        assertEquals(listOf("a", "b"), back.tags)
        assertNotNull(f.load(added.id))
    }

    @Test
    fun `tags are listed once each`() {
        val f = files()
        assertNotNull(f.add(picture(), tags = listOf("hands", "folds")))
        Thread.sleep(3)
        assertNotNull(f.add(picture(), tags = listOf("hands")))
        assertEquals(listOf("folds", "hands"), f.tags())
    }

    @Test
    fun `a label with a newline in it stays one line`() {
        val f = files()
        // The format is one line per idea, so a label that carried a newline
        // would write a line the decoder reads as a key it does not know --
        // silently losing whatever came after it.
        assertNotNull(f.add(picture(), "two" + NL + "lines"))
        assertEquals("two lines", f.list().single().label)
    }

    @Test
    fun `a library with nothing in it is empty rather than broken`() {
        val f = RefFiles(java.io.File(temp.root, "never-made"))
        assertEquals(emptyList(), f.list())
        assertEquals(emptyList(), f.tags())
        assertEquals(0L, f.bytes())
        assertNull(f.load("r1"))
    }

    private companion object {
        /** Written this way so the source of this file has no literal newline in a string. */
        const val NL = "\n"
    }
}
