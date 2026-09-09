package be.thalos.artiest.doc

import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The crossing between the thread that finishes strokes and the thread that
 * owns the layer's pixels.
 *
 * Plain JVM, no Robolectric: the queue holds `Stroke`s and hands them back, and
 * `Stroke` is `:engine`'s, so nothing here needs an `android.jar`. That is the
 * point of testing the crossing separately from the thing it crosses to.
 *
 * The two tests that matter are the ones with controls. `a single slot loses a
 * stroke` builds the `AtomicReference` handoff this replaced and drives it
 * through the interleaving that breaks it, so "the queue does not lose strokes"
 * is a comparison rather than an assertion about nothing. And `a clear cannot
 * be overtaken by the stroke before it` does the same for the separate-flag
 * design: two independent fields cannot express an ordering, and the control
 * shows the ordering actually inverting.
 */
class CommitQueueTest {

    private fun stroke(x: Float): Stroke = Stroke.copyOf(
        // x, y, radius, aspect, rotation -- see Stroke.STRIDE, widened at W9.
        dabs = floatArrayOf(x, 0f, 1f, 1f, 0f, 1f),
        dabCount = 1,
        colorArgb = 0xFF000000.toInt(),
        antiAlias = true,
        bounds = Bounds.of(x - 1f, -1f, x + 1f, 1f),
    )

    /** Records what the render thread was asked to do, in order. */
    private class Recorder : CommitQueue.Sink {
        val strokes = ArrayList<Stroke>()
        val log = StringBuilder()

        override fun onStroke(stroke: Stroke) {
            strokes.add(stroke)
            log.append('S')
        }

        override fun onClear() {
            log.append('C')
        }

        override fun onUndo() {
            log.append('U')
        }

        override fun onRedo() {
            log.append('R')
        }
    }

    @Test
    fun `commits are applied in the order they were made`() {
        val q = CommitQueue()
        val a = stroke(1f)
        val b = stroke(2f)
        q.commit(a)
        q.clear()
        q.commit(b)

        val r = Recorder()
        assertEquals(3, q.drain(r))
        assertEquals("SCS", r.log.toString())
        assertSame(a, r.strokes[0])
        assertSame(b, r.strokes[1])
    }

    @Test
    fun `draining twice applies nothing twice`() {
        val q = CommitQueue()
        q.commit(stroke(1f))
        val r = Recorder()
        assertEquals(1, q.drain(r))
        assertEquals(0, q.drain(r), "a second drain re-applied a commit")
        assertEquals(1, r.strokes.size)
        assertEquals(0, q.pending)
    }

    @Test
    fun `a drain empties the queue rather than taking one commit`() {
        val q = CommitQueue()
        repeat(50) { q.commit(stroke(it.toFloat())) }
        assertEquals(50, q.pending)
        val r = Recorder()
        assertEquals(50, q.drain(r))
        assertEquals(0, q.pending, "the layer would be 49 strokes behind the document")
    }

    @Test
    fun `abandon drops commits without applying them`() {
        val q = CommitQueue()
        q.commit(stroke(1f))
        q.clear()
        q.abandon()
        val r = Recorder()
        assertEquals(0, q.drain(r))
        assertEquals("", r.log.toString())
    }

    @Test
    fun `a clear cannot be overtaken by the stroke before it`() {
        // What Clear means: after everything drawn so far. One queue makes that
        // FIFO and there is nothing to get wrong.
        val q = CommitQueue()
        q.commit(stroke(1f))
        q.clear()
        val r = Recorder()
        q.drain(r)
        assertEquals("SC", r.log.toString(), "the clear was applied before the stroke it follows")

        // The control: a stroke slot beside a clear flag, which is what this
        // replaced. The render thread reads them in whatever order it is
        // written to read them, and here that order is the wrong one — the
        // stroke lands after the blank and survives a clear it preceded.
        val slot = AtomicReference<Stroke?>(null)
        var clearFlag = false
        slot.set(stroke(1f))
        clearFlag = true
        val control = StringBuilder()
        if (clearFlag) control.append('C')
        slot.getAndSet(null)?.let { control.append('S') }
        assertEquals(
            "CS",
            control.toString(),
            "the control did not invert the order, so the queue proves nothing",
        )
    }

    @Test
    fun `a single slot loses a stroke and the queue does not`() {
        // Two pen-ups inside one render-thread stall. A quick pair of tick
        // marks does this, and so does any stall long enough to span two
        // strokes; nothing about it is exotic.
        val a = stroke(1f)
        val b = stroke(2f)

        val slot = AtomicReference<Stroke?>(null)
        slot.set(a)
        slot.set(b) // the render thread has not run yet
        val survived = ArrayList<Stroke>()
        slot.getAndSet(null)?.let { survived.add(it) }
        assertEquals(1, survived.size)
        assertSame(b, survived[0])
        assertTrue(
            a !in survived,
            "the control kept both strokes, so it is not the failure this replaced",
        )

        val q = CommitQueue()
        q.commit(a)
        q.commit(b)
        val r = Recorder()
        q.drain(r)
        assertEquals(listOf(a, b), r.strokes)
    }

    @Test
    fun `every stroke crosses exactly once under a real producer and consumer`() {
        // The single-threaded tests establish the ordering; this establishes
        // that the ordering survives the two threads it exists for. The
        // consumer drains in a spin rather than on a signal, because that is
        // what the render thread does: it drains whenever a frame happens to
        // run, not when the UI thread tells it to.
        val q = CommitQueue()
        val total = 5_000
        val seen = ArrayList<Stroke>(total)
        val sink = object : CommitQueue.Sink {
            override fun onStroke(stroke: Stroke) {
                seen.add(stroke)
            }

            override fun onClear() = Unit
            override fun onUndo() = Unit
            override fun onRedo() = Unit
        }
        val sent = ArrayList<Stroke>(total)
        repeat(total) { sent.add(stroke(it.toFloat())) }

        val started = CountDownLatch(1)
        val producer = Thread {
            started.countDown()
            for (s in sent) q.commit(s)
        }
        producer.start()
        started.await()
        while (seen.size < total) {
            q.drain(sink)
            Thread.yield()
        }
        producer.join()
        q.drain(sink)

        assertEquals(total, seen.size, "a stroke was lost or duplicated")
        // Identity, in order: the queue must not reorder, and must not hand the
        // same stroke over twice.
        for (i in 0 until total) assertSame(sent[i], seen[i], "stroke $i arrived out of order")
    }
}
