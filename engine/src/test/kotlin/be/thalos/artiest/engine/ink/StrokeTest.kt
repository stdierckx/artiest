package be.thalos.artiest.engine.ink

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The properties a `Stroke` has to hold to survive being handed to another
 * thread, checked on the JVM because that is where a race is reproducible at
 * all. On the tablet this failure is a stroke that lands with a few dabs from
 * the *next* stroke mixed into it, at a rate of maybe one stroke in a thousand.
 */
class StrokeTest {

    private val bounds = Bounds.of(0f, 0f, 10f, 10f)

    private fun dabs(vararg values: Float) = values

    /** One dab's five floats: x, y, radius, aspect, rotation. See [Stroke.STRIDE]. */
    private fun dab(
        x: Float,
        y: Float,
        r: Float,
        aspect: Float = 1f,
        rot: Float = 0f,
        flow: Float = 1f,
    ) = floatArrayOf(x, y, r, aspect, rot, flow)

    @Test
    fun `the accessors read back the dabs they were given`() {
        val s = Stroke.copyOf(
            dab(1f, 2f, 3f) + dab(4f, 5f, 6f, aspect = 0.5f, rot = 1.2f),
            dabCount = 2,
            colorArgb = 0xFF000000.toInt(),
            antiAlias = true,
            bounds = bounds,
        )
        assertEquals(2, s.dabCount)
        assertEquals(1f, s.x(0)); assertEquals(2f, s.y(0)); assertEquals(3f, s.radius(0))
        assertEquals(1f, s.aspect(0), "a dab with no shape dynamics is a circle")
        assertEquals(0f, s.rotation(0))
        assertEquals(4f, s.x(1)); assertEquals(5f, s.y(1)); assertEquals(6f, s.radius(1))
        assertEquals(0.5f, s.aspect(1)); assertEquals(1.2f, s.rotation(1))
        assertEquals(0xFF000000.toInt(), s.colorArgb)
        assertTrue(s.antiAlias)
        assertEquals(bounds, s.bounds)
    }

    /** Only the first [Stroke.dabCount] triples are taken; the rest of the builder's buffer is not a dab. */
    @Test
    fun `dabs past the count are not copied and not reachable`() {
        val s = Stroke.copyOf(
            dab(1f, 2f, 3f) + dab(99f, 99f, 99f),
            dabCount = 1,
            colorArgb = 0,
            antiAlias = true,
            bounds = bounds,
        )
        assertEquals(1, s.dabCount)
        assertFailsWith<IndexOutOfBoundsException> { s.x(1) }
        assertFailsWith<IndexOutOfBoundsException> { s.radius(-1) }
    }

    /**
     * The reason [Stroke.copyOf] copies: W7's `StrokeBuilder` resamples into a
     * reusable array and W8's batches come from a preallocated ring, so a stroke
     * that aliased either would be rewritten under the render thread with no
     * lock covering the read.
     */
    @Test
    fun `a committed stroke does not change when the builder reuses its buffer`() {
        val buffer = dab(1f, 2f, 3f, aspect = 0.5f, rot = 1.2f)
        val s = Stroke.copyOf(buffer, 1, colorArgb = 0, antiAlias = true, bounds = bounds)
        for (i in buffer.indices) buffer[i] = -1f
        assertEquals(1f, s.x(0))
        assertEquals(2f, s.y(0))
        assertEquals(3f, s.radius(0))
        assertEquals(0.5f, s.aspect(0))
        assertEquals(1.2f, s.rotation(0))
    }

    /**
     * Structural, not stylistic: the copy above is worthless if a caller can
     * fetch the array back out and alias it into a builder. This fails the
     * moment someone adds a `val dabs: FloatArray` for convenience.
     */
    @Test
    fun `the dab payload is not reachable from outside the stroke`() {
        val java = Stroke::class.java
        val leaks = java.methods.filter { it.returnType == FloatArray::class.java }.map { it.name } +
            java.fields.filter { it.type == FloatArray::class.java }.map { it.name }
        assertEquals(emptyList(), leaks)
    }

    /**
     * A stroke whose dabs never reached an accumulator carries an empty bounds,
     * which is the stale-rim bug at its source; a builder that was not reset
     * carries the previous stroke's rectangle. One check refuses both.
     */
    @Test
    fun `a stroke and its bounds must agree about whether anything was drawn`() {
        assertFailsWith<IllegalArgumentException> {
            Stroke.copyOf(dabs(1f, 2f, 3f), 1, 0, true, Bounds.EMPTY)
        }
        assertFailsWith<IllegalArgumentException> {
            Stroke.copyOf(FloatArray(0), 0, 0, true, bounds)
        }
    }

    @Test
    fun `a stroke with no dabs is representable`() {
        val s = Stroke.copyOf(FloatArray(0), 0, 0, true, Bounds.EMPTY)
        assertEquals(0, s.dabCount)
        assertTrue(s.bounds.isEmpty)
    }

    @Test
    fun `a count the buffer cannot supply is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Stroke.copyOf(dabs(1f, 2f, 3f), 2, 0, true, bounds)
        }
        assertFailsWith<IllegalArgumentException> {
            Stroke.copyOf(dabs(1f, 2f, 3f), -1, 0, true, bounds)
        }
    }

    /**
     * The handoff as W8 and W10 will run it, with the producer doing the one
     * thing that would break a stroke that aliased its buffer: overwriting the
     * buffer the instant after publishing.
     *
     * What is assertable is that the consumer never observes a stroke
     * disagreeing with itself — every dab carries its own generation number,
     * and the poison written between rounds carries a value no generation uses.
     * A green run is not a proof of the memory model; it is evidence against
     * the aliasing bug, which is the half of it that is this type's job.
     * Exactly-once consumption belongs to the `AtomicReference` and is W8's.
     */
    @Test
    fun `a stroke handed to another thread never shows the buffer it was copied from`() {
        val rounds = 5000
        val perStroke = 16
        val handoff = AtomicReference<Stroke?>(null)
        val torn = AtomicReference<String?>(null)
        val done = AtomicBoolean(false)
        var observed = 0

        val consumer = Thread {
            while (true) {
                val s = handoff.getAndSet(null)
                if (s == null) {
                    if (done.get()) return@Thread
                    continue
                }
                observed++
                val g = s.colorArgb.toFloat()
                for (i in 0 until s.dabCount) {
                    if (s.x(i) != g || s.y(i) != g + 1f || s.radius(i) != 1f) {
                        torn.compareAndSet(
                            null,
                            "stroke ${s.colorArgb} dab $i was (${s.x(i)}, ${s.y(i)}, ${s.radius(i)})",
                        )
                        return@Thread
                    }
                }
                if (s.bounds != Bounds.of(g - 1f, g, g + 1f, g + 2f)) {
                    torn.compareAndSet(null, "stroke ${s.colorArgb} carried ${s.bounds}")
                    return@Thread
                }
            }
        }
        consumer.start()

        val buffer = FloatArray(perStroke * Stroke.STRIDE)
        val acc = MutableBounds()
        for (g in 1..rounds) {
            acc.reset()
            for (i in 0 until perStroke) {
                buffer[i * Stroke.STRIDE] = g.toFloat()
                buffer[i * Stroke.STRIDE + 1] = g + 1f
                buffer[i * Stroke.STRIDE + 2] = 1f
                acc.add(g.toFloat(), g + 1f, 1f)
            }
            handoff.set(Stroke.copyOf(buffer, perStroke, g, true, acc.snapshot()))
            // The overwrite that makes the test mean something. A stroke holding
            // the builder's array would be reading this by the time the consumer
            // gets to it.
            buffer.fill(Float.MIN_VALUE)
        }
        done.set(true)
        consumer.join()

        assertNull(torn.get())
        // Values are dropped under contention — getAndSet guarantees at most
        // once, never at least once — so this asserts the consumer ran at all,
        // not that it saw every stroke.
        assertTrue(observed > 0, "consumer observed no strokes")
    }
}
