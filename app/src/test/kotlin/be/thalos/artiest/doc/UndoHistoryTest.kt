package be.thalos.artiest.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The undo policy, with no pixels in sight.
 *
 * Every rule that decides what the buttons do — what a new edit does to redo,
 * which end eviction takes, what survives a full budget, who releases what — is
 * checked here on a fake step that only counts bytes. What is left for the
 * device is whether the right rectangle was copied, which is a different kind
 * of bug and not one more tests of this class would find.
 */
class UndoHistoryTest {

    /** A step that holds nothing and remembers whether it was released. */
    private class Fake(val name: String, override val bytes: Long = 1L) : UndoStep {
        var recycled = false
        override fun recycle() {
            check(!recycled) { "$name recycled twice" }
            recycled = true
        }
        override fun toString() = name
    }

    /** Restores in place: hands back a new step standing for the old pixels. */
    private fun swap(tag: String): (Fake) -> Fake = { consumed -> Fake("$tag-of-$consumed", consumed.bytes) }

    @Test
    fun `an empty history cannot go either way`() {
        val h = UndoHistory<Fake>()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertFalse(h.undo(swap("u")))
        assertFalse(h.redo(swap("r")))
        assertEquals(0L, h.bytes)
    }

    @Test
    fun `undo then redo walks back to where it started`() {
        val h = UndoHistory<Fake>()
        h.record(Fake("a"))
        h.record(Fake("b"))
        assertEquals(2, h.undoDepth)

        val restored = ArrayList<String>()
        assertTrue(h.undo { restored += it.name; Fake("was-${it.name}") })
        assertEquals(listOf("b"), restored)
        assertEquals(1, h.undoDepth)
        assertEquals(1, h.redoDepth)

        assertTrue(h.redo { restored += it.name; Fake("was-${it.name}") })
        assertEquals(listOf("b", "was-b"), restored)
        assertEquals(2, h.undoDepth)
        assertEquals(0, h.redoDepth)
    }

    @Test
    fun `undo comes back in the reverse of the order things were recorded`() {
        val h = UndoHistory<Fake>()
        for (n in listOf("a", "b", "c")) h.record(Fake(n))
        val order = ArrayList<String>()
        repeat(3) { h.undo { order += it.name; Fake("x") } }
        assertEquals(listOf("c", "b", "a"), order)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a new edit discards the redo chain and releases it`() {
        val h = UndoHistory<Fake>()
        h.record(Fake("a"))
        val inverse = Fake("inverse-of-a")
        h.undo { inverse }
        assertTrue(h.canRedo)

        h.record(Fake("b"))
        assertFalse(h.canRedo, "drawing after an undo leaves the branch you could redo onto")
        assertTrue(inverse.recycled, "the abandoned redo step was not released")
    }

    @Test
    fun `the consumed step is released, unless the caller reuses it`() {
        val h = UndoHistory<Fake>()
        val a = Fake("a")
        h.record(a)
        h.undo { Fake("fresh") }
        assertTrue(a.recycled)

        val b = Fake("b")
        h.record(b)
        h.undo { it } // reuses the same buffer for both directions
        assertFalse(b.recycled, "a caller that hands the step back still owns it")
    }

    @Test
    fun `the depth cap drops the oldest step, not the newest`() {
        val h = UndoHistory<Fake>(maxDepth = 3)
        val steps = listOf("a", "b", "c", "d").map { Fake(it) }
        steps.forEach { h.record(it) }

        assertEquals(3, h.undoDepth)
        assertTrue(steps[0].recycled, "the oldest step should have gone")
        assertFalse(steps[3].recycled, "the newest step must survive")

        val order = ArrayList<String>()
        repeat(4) { h.undo { order += it.name; Fake("x", 1L) } }
        assertEquals(listOf("d", "c", "b"), order)
    }

    @Test
    fun `the byte budget evicts from the old end and keeps the total under it`() {
        val h = UndoHistory<Fake>(maxBytes = 100L)
        val big = Fake("big", 60L)
        h.record(big)
        h.record(Fake("second", 60L))
        assertEquals(60L, h.bytes)
        assertEquals(1, h.undoDepth)
        assertTrue(big.recycled)
    }

    @Test
    fun `one step survives even when it is larger than the whole budget`() {
        // Clearing a full page is 28.5 MB and could be most of the budget. It
        // is still the single most important thing to be able to undo.
        val h = UndoHistory<Fake>(maxBytes = 10L)
        val huge = Fake("clear", 1_000L)
        h.record(huge)
        assertEquals(1, h.undoDepth)
        assertFalse(huge.recycled)
        assertTrue(h.canUndo)
    }

    @Test
    fun `bytes are counted across both chains and follow an undo across`() {
        val h = UndoHistory<Fake>()
        h.record(Fake("a", 10L))
        h.record(Fake("b", 20L))
        assertEquals(30L, h.bytes)

        h.undo { Fake("inverse", 25L) }
        // b (20) left the undo chain and its 25-byte inverse joined the redo one.
        assertEquals(35L, h.bytes)

        h.clear()
        assertEquals(0L, h.bytes)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun `clear releases everything it was holding`() {
        val h = UndoHistory<Fake>()
        val a = Fake("a")
        h.record(a)
        val inverse = Fake("inv")
        h.undo { inverse }
        h.clear()
        assertTrue(inverse.recycled)
    }

    @Test
    fun `a history that cannot hold anything is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { UndoHistory<Fake>(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { UndoHistory<Fake>(maxBytes = 0L) }
    }
}
