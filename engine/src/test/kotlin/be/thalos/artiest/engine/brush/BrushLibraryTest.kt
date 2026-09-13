package be.thalos.artiest.engine.brush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shelf's data layer: three shipped brushes and however many came from
 * files, all reachable the same way.
 *
 * The tests that matter here are the two that are about *not* trusting what is
 * on disk — an id that has gone away and a file that claims to be the pencil —
 * because both of those are how a shelf turns into a crash on the path that
 * draws.
 */
class BrushLibraryTest {

    private fun saved(id: String, label: String = id, brush: Brush = Brush()): BrushEntry =
        BrushEntry.fromText(id, label, BrushCodec.encode(brush))

    @Test
    fun `the five shipped brushes are there and are built in`() {
        val library = BrushLibrary.DEFAULT
        assertEquals(
            listOf("pen", "pencil", "marker", "hard_eraser", "soft_eraser"),
            library.entries.map { it.id },
        )
        assertTrue(library.entries.all { it.origin == BrushOrigin.BUILT_IN })
        assertFalse(library.entries.any { it.removable }, "a shipped brush cannot be deleted")
        assertFalse(library.hasSaved)
    }

    @Test
    fun `a built in makes the brush its preset makes`() {
        for (preset in BrushPreset.entries) {
            val entry = assertNotNull(BrushLibrary.DEFAULT.find(preset.id), preset.id)
            assertEquals(BrushCodec.encode(preset.create()), BrushCodec.encode(entry.create()))
            assertEquals(BrushPreset.TUNING, entry.tuning, "a built-in follows the tuning")
        }
    }

    @Test
    fun `a saved brush is its own truth and no tuning bump can move it`() {
        val mine = Brush().also { BrushPreset.PENCIL.applyTo(it) }
        mine.sizeMax = 12f
        val entry = saved("my-pencil", "My pencil", mine)
        assertEquals(0, entry.tuning, "zero never changes, which is the whole point")
        assertEquals(12f, entry.create().sizeMax)
        assertTrue(entry.removable)
    }

    @Test
    fun `a file cannot shadow a shipped brush`() {
        val fake = saved("pencil", "Not the pencil", Brush().also { it.sizeMax = 3f })
        val library = BrushLibrary(listOf(fake))
        assertEquals(BrushPreset.entries.size, library.entries.size)
        assertEquals(
            BrushPreset.PENCIL.create().sizeMax,
            assertNotNull(library.find("pencil")).create().sizeMax,
        )
    }

    @Test
    fun `an id that has gone away comes back as the pen`() {
        val library = BrushLibrary(listOf(saved("gone")))
        assertNull(library.find("deleted-yesterday"))
        assertSame(library.entries.first(), library.entryFor("deleted-yesterday"))
        assertEquals("pen", library.entryFor(null).id)
    }

    @Test
    fun `the old stored spelling still means the same brush`() {
        assertEquals("pencil", BrushLibrary.idOf("PENCIL"))
        assertEquals("pen", BrushLibrary.idOf("PEN"))
        assertEquals("marker", BrushLibrary.idOf("MARKER"))
        assertEquals("pencil", BrushLibrary.idOf("pencil"), "and the new one is left alone")
        assertEquals("my-pencil", BrushLibrary.idOf("my-pencil"), "as is a saved one")
        assertNull(BrushLibrary.idOf(null))
        assertNull(BrushLibrary.idOf(""))
    }

    @Test
    fun `an entry knows when the brush in the hand has moved`() {
        val entry = assertNotNull(BrushLibrary.DEFAULT.find("pencil"))
        val brush = entry.create()
        assertTrue(entry.matches(brush))
        brush.sizeMax += 1f
        assertFalse(entry.matches(brush), "a dragged slider is a modified brush")
    }

    @Test
    fun `a pencil turned into an eraser is no longer the pencil`() {
        // The reverse of what this test used to say, and the reversal is Us2.
        // While the eraser was a *mode*, a pencil rubbing something out was
        // still the pencil and `matches` had to ignore the erase lines. The
        // eraser is two brushes now — `BrushPreset.HARD_ERASER` — so a brush
        // that erases and one that does not are two different brushes, and the
        // shelf's modified mark should say so.
        val entry = assertNotNull(BrushLibrary.DEFAULT.find("pencil"))
        val brush = entry.create()
        assertTrue(entry.matches(brush), "untouched, it is itself")
        brush.erase = true
        assertFalse(entry.matches(brush), "and now it is a rubber with a pencil's shape")
    }

    @Test
    fun `picking a brush is what turns erasing on and off`() {
        // Also the reverse of what it used to say. There was a toggle then, and
        // a brush that carried `erase` would have been a brush that moved
        // somebody else's switch — so `applyTo` refused to. There is no toggle
        // now, and this line is the whole of how an artist reaches for a
        // rubber.
        val hand = BrushPreset.PENCIL.create()
        BrushLibrary.DEFAULT.entryFor("hard_eraser").applyTo(hand)
        assertTrue(hand.erase, "picking the eraser puts a rubber in the hand")

        BrushLibrary.DEFAULT.entryFor("pencil").applyTo(hand)
        assertFalse(hand.erase, "and picking the pencil takes it back out")

        val fromFile = BrushPreset.PEN.create()
        saved("rubber", brush = Brush().also { it.erase = true }).applyTo(fromFile)
        assertTrue(fromFile.erase, "a saved eraser is an eraser too")
    }

    @Test
    fun `a name becomes an id that can be a file name`() {
        assertEquals("my-soft-pencil", BrushEntry.slug("My Soft Pencil"))
        assertEquals("2b", BrushEntry.slug("  2B  "))
        assertEquals("a-b", BrushEntry.slug("a/../b"), "nothing that is a path")
        assertEquals("brush", BrushEntry.slug("///"), "and never nothing at all")
    }

    @Test
    fun `wiring is put back without the numbers being touched`() {
        val brush = BrushPreset.PENCIL.create()
        val entry = BrushEntry.fromText("mine", "Mine", BrushCodec.encode(brush))
        // What an older build's save looks like: the scalars, none of the
        // sensor wiring.
        val partial = BrushPreset.PENCIL.create()
        partial.sizeMax = 99f
        for (o in listOf(partial.aspect, partial.rotation, partial.scatter, partial.size)) {
            o.clearInputs()
        }
        entry.applyShapeOnlyTo(partial)
        assertEquals(99f, partial.sizeMax, "the user's slider survives")
        assertTrue(partial.aspect.inputCount > 0, "and the tilt comes back")
    }
}
