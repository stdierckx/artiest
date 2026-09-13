package be.thalos.artiest.doc
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ik13: a guide as a line of text in `project.json`.
 *
 * The tests that matter are the refusals. A guide table is read on the path
 * that opens somebody's drawing, so every malformed row has to come back as
 * "not this one" rather than as an exception or, worse, as a guide in the wrong
 * place that the next hundred strokes are inked against.
 */
class GuideTextTest {

    private fun ruler(id: Long, on: Boolean = true) =
        Guideline(id, GuideKind.RULER, floatArrayOf(100f, 50f, 900f, 780f), on)

    @Test
    fun `a ruler goes out and comes back`() {
        val line = ruler(3)
        val back = assertNotNull(GuideText.decode(GuideText.encode(line)))
        assertEquals(3L, back.id)
        assertEquals(GuideKind.RULER, back.kind)
        assertTrue(back.on)
        assertEquals(100f, back.xAt(0), 1e-3f)
        assertEquals(50f, back.yAt(0), 1e-3f)
        assertEquals(900f, back.xAt(1), 1e-3f)
        assertEquals(780f, back.yAt(1), 1e-3f)
    }

    @Test
    fun `off comes back off`() {
        val back = assertNotNull(GuideText.decode(GuideText.encode(ruler(1, on = false))))
        assertFalse(back.on)
    }

    @Test
    fun `the line is readable, which is why it is text`() {
        assertEquals("3 ruler 1 100.0,50.0 900.0,780.0", GuideText.encode(ruler(3)))
    }

    @Test
    fun `a coordinate keeps a tenth of a pixel and no more`() {
        val line = Guideline(1, GuideKind.RULER, floatArrayOf(10.04f, 10.06f, 20f, 20f))
        val back = assertNotNull(GuideText.decode(GuideText.encode(line)))
        assertEquals(10.0f, back.xAt(0), 1e-4f)
        assertEquals(10.1f, back.yAt(0), 1e-4f)
    }

    // ---- the refusals ------------------------------------------------------

    @Test
    fun `a kind this build does not know is dropped and not guessed`() {
        // A file written by a later build carrying an ellipse opens here with
        // the ellipse missing and everything else intact. Mapping it to the
        // nearest kind would put a line where an ellipse was and let somebody
        // ink a hundred strokes against it.
        assertNull(GuideText.decode("3 ellipse 1 0.0,0.0 10.0,10.0 20.0,0.0"))
    }

    @Test
    fun `a row with the wrong number of points is dropped`() {
        assertNull(GuideText.decode("3 ruler 1 100.0,50.0"), "too few")
        assertNull(GuideText.decode("3 ruler 1 1.0,2.0 3.0,4.0 5.0,6.0"), "too many")
    }

    @Test
    fun `nonsense in any field is dropped`() {
        for (bad in listOf(
            "",
            "ruler 1 0.0,0.0 1.0,1.0",
            "3 ruler yes 0.0,0.0 1.0,1.0",
            "3 ruler 1 0.0 1.0,1.0",
            "3 ruler 1 x,0.0 1.0,1.0",
            "0 ruler 1 0.0,0.0 1.0,1.0",
            "-2 ruler 1 0.0,0.0 1.0,1.0",
            "3 ruler 1 NaN,0.0 1.0,1.0",
            "3 ruler 1 Infinity,0.0 1.0,1.0",
        )) {
            assertNull(GuideText.decode(bad), "\"$bad\" was read as a guide")
        }
    }

    @Test
    fun `a coordinate off any page there could be is dropped`() {
        assertNull(GuideText.decode("3 ruler 1 1.0e9,0.0 1.0,1.0"))
    }

    // ---- the table ---------------------------------------------------------

    @Test
    fun `a bad row loses one guide and not the drawing`() {
        val rows = listOf(
            GuideText.encode(ruler(1)),
            "2 ellipse 1 0.0,0.0",
            GuideText.encode(ruler(3)),
        )
        val back = GuideText.decodeAll(rows)
        assertEquals(listOf(1L, 3L), back.map { it.id })
    }

    @Test
    fun `a repeated id keeps the first`() {
        // Two guides with one id would be one guide with two handles that move
        // independently and one that cannot be deleted.
        val rows = listOf(
            "5 ruler 1 0.0,0.0 100.0,0.0",
            "5 ruler 1 0.0,400.0 100.0,400.0",
        )
        val back = GuideText.decodeAll(rows)
        assertEquals(1, back.size)
        assertEquals(0f, back[0].yAt(0), 1e-3f)
    }

    @Test
    fun `a file claiming ten thousand rulers is capped`() {
        val rows = (1..10_000).map { "$it ruler 1 0.0,0.0 100.0,0.0" }
        assertEquals(GuideText.MAX_GUIDES, GuideText.decodeAll(rows).size)
        assertEquals(GuideText.MAX_GUIDES, GuideText.encodeAll(GuideText.decodeAll(rows)).size)
    }

    @Test
    fun `the whole table goes out and comes back`() {
        val lines = listOf(ruler(1), ruler(2, on = false), ruler(7))
        val back = GuideText.decodeAll(GuideText.encodeAll(lines))
        assertEquals(lines.map { it.id }, back.map { it.id })
        assertEquals(lines.map { it.on }, back.map { it.on })
    }
}
