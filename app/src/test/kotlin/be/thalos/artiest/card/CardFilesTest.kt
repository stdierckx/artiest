package be.thalos.artiest.card

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
 * The deck on disk.
 *
 * `RefFilesTest` covers the shape these two share — two files, no index,
 * nothing throws, a slug is a safe file name — and is not repeated. What is
 * here is what a *card* is: a picture that is a drawing rather than a
 * photograph, and a note that is one sentence rather than a document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun files() = CardFiles(temp.newFolder())

    private fun drawing(): Bitmap =
        Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }

    @Test
    fun `a card is a drawing, a title, a note and tags`() {
        val f = files()
        val made = assertNotNull(
            f.add(drawing(), "A hand", "box, then sausages", listOf("hands"), "Tuesday"),
        )
        val back = f.list().single()
        assertEquals("A hand", back.title)
        assertEquals("box, then sausages", back.note)
        assertEquals(listOf("hands"), back.tags)
        assertEquals("Tuesday", back.fromProject)
        assertEquals(8, back.widthPx)
        assertNotNull(f.load(made.id))
    }

    @Test
    fun `the picture is lossless`() {
        // PNG and not JPEG, because a card is a drawing: ink on paper is
        // exactly the content JPEG's blocks ruin, and a card is the thing this
        // program is for.
        val f = files()
        val made = assertNotNull(f.add(drawing(), "", "", emptyList(), ""))
        val back = assertNotNull(f.load(made.id))
        assertEquals(Color.BLACK, back.getPixel(4, 2))
    }

    @Test
    fun `a note is one sentence and the type says so`() {
        val f = files()
        val essay = "x".repeat(CardFiles.MAX_NOTE * 3)
        val made = assertNotNull(f.add(drawing(), "t", essay, emptyList(), ""))
        assertEquals(CardFiles.MAX_NOTE, made.note.length)
        assertEquals(CardFiles.MAX_NOTE, f.list().single().note.length)
    }

    @Test
    fun `a card with nothing written on it is still a card`() {
        // The bar button keeps with no title and no note, because a form in the
        // way of the one act that happens mid-drawing is a form that stops the
        // act happening. It has to survive the round trip.
        val f = files()
        val made = assertNotNull(f.add(drawing(), "", "", emptyList(), ""))
        val back = f.list().single()
        assertEquals("", back.title)
        assertEquals("", back.note)
        assertEquals(emptyList(), back.tags)
        assertTrue(back.addedMs > 0)
        assertNotNull(made)
    }

    @Test
    fun `naming it later keeps the drawing`() {
        val f = files()
        val made = assertNotNull(f.add(drawing(), "", "", emptyList(), ""))
        assertTrue(f.update(made.copy(title = "A hand", tags = listOf("hands"))))
        assertEquals("A hand", f.list().single().title)
        assertNotNull(f.load(made.id))
    }

    @Test
    fun `a delete takes both files`() {
        val f = files()
        val made = assertNotNull(f.add(drawing(), "t", "n", listOf("a"), ""))
        assertTrue(f.delete(made.id))
        assertEquals(0, f.list().size)
        assertNull(f.load(made.id))
        assertEquals(0L, f.bytes())
    }

    @Test
    fun `an id that is not a slug reaches no file`() {
        val f = files()
        assertNotNull(f.add(drawing(), "t", "n", emptyList(), ""))
        assertNull(f.load("../../shared_prefs/chrome"))
        assertEquals(false, f.delete("../../shared_prefs/chrome"))
        assertEquals(1, f.list().size)
    }
}
