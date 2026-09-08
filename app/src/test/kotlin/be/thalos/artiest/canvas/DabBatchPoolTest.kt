package be.thalos.artiest.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a batch pool has to get right, and it is exactly one thing: never hand
 * back a slot the render thread has not finished reading.
 *
 * The failure it guards is not a crash. It is a stroke that draws a few of the
 * wrong dabs, sometimes, when the render thread happens to be behind — which on
 * the tablet looks like a rendering glitch and is unreproducible by hand. So
 * every test here drives the pool through a *simulated lag pattern* rather than
 * a happy path, and the last one is a negative control: the same pattern
 * against a plain modulo ring, which must alias where this pool does not. A
 * safety test that the unsafe implementation also passes proves nothing.
 */
class DabBatchPoolTest {

    @Test
    fun `steady state reuses the ring and allocates nothing`() {
        val pool = DabBatchPool(slots = 4, capacity = 8)
        val first = pool.acquire()
        pool.markDrawn(first.sequence)
        val seen = HashSet<DabBatch>()
        repeat(200) {
            val b = pool.acquire()
            seen.add(b)
            pool.markDrawn(b.sequence)
        }
        // 201 acquires, at most 4 distinct instances, and not one spill.
        assertTrue(seen.size <= 4, "pool handed out ${seen.size} distinct batches")
        assertEquals(0L, pool.spills)
    }

    @Test
    fun `a slot is not reused until the render thread reports it drawn`() {
        val pool = DabBatchPool(slots = 4, capacity = 8)
        val held = ArrayList<DabBatch>()
        repeat(4) { held.add(pool.acquire()) }
        assertEquals(4, held.toSet().size, "the four slots were not four instances")

        // Nothing drawn yet: the ring is full and the next acquire must not
        // reach for held[0].
        val fifth = pool.acquire()
        assertFalse(held.contains(fifth), "reused a slot the render thread still holds")
        assertEquals(1L, pool.spills)

        // The render thread catches up through sequence 2. Slot 0 is free, and
        // the pool is allowed to reuse exactly it.
        pool.markDrawn(2L)
        val sixth = pool.acquire()
        assertSame(held[1], sixth, "the freed slot was not the one reused")
        assertEquals(1L, pool.spills, "spilled again with a slot free")
    }

    @Test
    fun `a spilled batch is outside the ring and does not disturb it`() {
        val pool = DabBatchPool(slots = 2, capacity = 8)
        val a = pool.acquire()
        val b = pool.acquire()
        val spill = pool.acquire()
        assertTrue(spill.spilled)
        assertFalse(a.spilled)
        assertFalse(b.spilled)
        assertNotSame(a, spill)
        assertNotSame(b, spill)

        // Draining everything returns the ring to service unchanged — though
        // not to the same phase: a spill consumes a sequence number, so the
        // ring index moves on with it and the next acquire is whichever slot
        // that lands on. What matters is that both ring slots come back and no
        // further spill is needed.
        pool.markDrawn(spill.sequence)
        assertEquals(setOf(a, b), setOf(pool.acquire(), pool.acquire()))
        assertEquals(1L, pool.spills)
    }

    @Test
    fun `sequences are consecutive from one and unique`() {
        val pool = DabBatchPool(slots = 3, capacity = 4)
        var expected = 1L
        repeat(50) {
            val batch = pool.acquire()
            assertEquals(expected, batch.sequence)
            expected++
            pool.markDrawn(batch.sequence)
        }
    }

    @Test
    fun `markDrawn never walks the watermark backwards`() {
        val pool = DabBatchPool(slots = 4, capacity = 8)
        val held = ArrayList<DabBatch>()
        repeat(4) { held.add(pool.acquire()) }

        // The commit path reports the whole stroke at once; a front-buffer
        // callback for an earlier batch can land after it. Applying the smaller
        // value would re-expose slots that were just released.
        pool.markDrawn(4L)
        pool.markDrawn(1L)
        assertEquals(0, pool.inFlight, "a late smaller report moved the watermark back")
        assertSame(held[0], pool.acquire())
    }

    @Test
    fun `a batch acquired but never submitted must still be reported drawn`() {
        // The case InkSurfaceView.drawWet handles for an empty batch: acquired,
        // never handed to the renderer, so no callback will ever report it. Not
        // reporting it leaks a slot permanently, which this pins by showing the
        // pool otherwise never recovers.
        val pool = DabBatchPool(slots = 2, capacity = 4)
        val orphan = pool.acquire()
        val used = pool.acquire()
        pool.markDrawn(used.sequence)
        assertEquals(0, pool.inFlight)
        assertSame(orphan, pool.acquire(), "the orphaned slot never came back")
    }

    @Test
    fun `peak in flight is the number W9 reads`() {
        val pool = DabBatchPool(slots = 8, capacity = 4)
        val held = ArrayList<DabBatch>()
        repeat(5) { held.add(pool.acquire()) }
        pool.markDrawn(5L)
        repeat(2) { pool.acquire() }
        // The peak is sampled at acquire, so it is the outstanding count seen
        // by the last acquire that saw the most: 4 before the fifth was handed
        // out.
        assertEquals(4, pool.peakInFlight)
        assertEquals(0L, pool.spills)
    }

    @Test
    fun `reset clears the ring and the counters`() {
        val pool = DabBatchPool(slots = 2, capacity = 4)
        val first = pool.acquire()
        first.add(1f, 2f, 3f)
        pool.acquire()
        pool.acquire() // spills
        pool.reset()
        assertEquals(0L, pool.spills)
        assertEquals(0, pool.peakInFlight)
        assertEquals(0, pool.inFlight)
        val again = pool.acquire()
        assertSame(first, again)
        assertEquals(1L, again.sequence)
        assertEquals(0, again.size)
    }

    @Test
    fun `a batch fills to capacity and reports it`() {
        val pool = DabBatchPool(slots = 1, capacity = 3)
        val b = pool.acquire()
        b.add(1f, 2f, 3f)
        b.add(4f, 5f, 6f)
        assertFalse(b.isFull)
        b.add(7f, 8f, 9f)
        assertTrue(b.isFull)
        assertEquals(3, b.size)
        assertEquals(4f, b.x(1))
        assertEquals(5f, b.y(1))
        assertEquals(6f, b.radius(1))
    }

    @Test
    fun `a nonsense size is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { DabBatchPool(slots = 0) }
        assertFailsWith<IllegalArgumentException> { DabBatchPool(capacity = 0) }
    }

    /**
     * The negative control.
     *
     * Everything above asserts that this pool does not alias. That is only
     * evidence if aliasing is something a plausible implementation would
     * actually do — so here is the plausible implementation, four lines of
     * modulo arithmetic with no completion signal, driven through the same lag
     * pattern. It must hand back a batch that is still in flight. If this test
     * ever fails, the pattern stopped exercising the hazard and the assertions
     * above have gone quiet without going red.
     */
    @Test
    fun `a plain modulo ring aliases under the same lag pattern`() {
        val slots = 4
        val naive = Array(slots) { DabBatch(8) }
        var issued = 0
        fun naiveAcquire(): DabBatch = naive[issued++ % slots]

        val inFlight = ArrayList<DabBatch>()
        repeat(slots) { inFlight.add(naiveAcquire()) }
        val next = naiveAcquire()
        assertTrue(
            inFlight.contains(next),
            "the control did not alias, so the pool's safety assertions prove nothing",
        )

        val pool = DabBatchPool(slots = slots, capacity = 8)
        val safeInFlight = ArrayList<DabBatch>()
        repeat(slots) { safeInFlight.add(pool.acquire()) }
        assertFalse(safeInFlight.contains(pool.acquire()))
    }
    @Test
    fun `the default slot count clears the peak W9 measured with room`() {
        // W9 drove a punishing stroke — 78 dabs an event — through the real
        // path and never saw more than 3 batches outstanding. This pins the
        // margin so a later change to DEFAULT_SLOTS has to argue with a
        // measurement rather than with nothing.
        assertTrue(
            DabBatchPool.DEFAULT_SLOTS >= 3 * 2,
            "the default ring no longer clears the measured peak of 3 with margin",
        )
        val pool = DabBatchPool()
        val held = ArrayList<DabBatch>()
        repeat(3) { held.add(pool.acquire()) }
        assertEquals(0L, pool.spills, "the measured worst case already spills at the default")
        assertEquals(3, held.toSet().size)
    }

}
