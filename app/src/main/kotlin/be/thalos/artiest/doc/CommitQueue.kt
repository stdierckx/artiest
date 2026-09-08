package be.thalos.artiest.doc

import be.thalos.artiest.engine.ink.Stroke
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything that changes the layer bitmap, in the order the UI thread asked
 * for it.
 *
 * The UI thread finishes strokes and presses Clear; the render thread is the
 * only thread allowed to touch the layer's pixels. This is the whole of the
 * crossing between them, and it exists because the obvious version of that
 * crossing — one `AtomicReference<Stroke>` consumed with `getAndSet(null)` —
 * has two holes that only open under timing nobody reproduces by hand.
 *
 * **A single slot drops strokes.** `getAndSet` guarantees a stroke is consumed
 * *at most* once, which is the property the plan asked for: a dropped frame
 * cannot double-stamp. It does not guarantee *at least* once. Two pen-ups
 * inside one render-thread stall — a quick pair of tick marks, a signature, a
 * hatch — and the second `set` overwrites the first, whose ink is then missing
 * from the layer while `Document.recordStroke` has already counted it. Nothing
 * fails; a stroke is simply not there. `CommitQueueTest` carries the single-slot
 * version longhand and shows it losing one.
 *
 * **A separate clear flag races the slot.** Blanking the layer and stamping a
 * stroke are ordered with respect to each other by the user — Clear means
 * "after everything I have drawn" — and two independent fields cannot express
 * that. Whichever branch the render thread happens to read first wins, so a
 * stroke committed just before Clear survives it about as often as not. Making
 * the clear an *item in the same queue* deletes the question: FIFO is the
 * ordering, and there is no second field to disagree with.
 *
 * Allocation: one queue node and one [Commit.Draw] per pen-up, which is order
 * 1 Hz and on the path that already allocates a `Stroke`, its dab copy and its
 * `Bounds`. Nothing here is on the per-sample path.
 *
 * Lock-free on both ends. The render thread must never block on the UI thread —
 * the framework's synchronous `surfaceRedrawNeeded` awaits the render callback
 * on an untimed latch, so a lock the UI thread could be holding is a deadlock
 * waiting for the right frame.
 */
class CommitQueue {

    /** One layer mutation. */
    sealed interface Commit {
        /** Stamp [stroke] into the layer, in document space. */
        class Draw(val stroke: Stroke) : Commit

        /** Blank the layer. A singleton: it carries nothing. */
        object Clear : Commit
    }

    /**
     * What [drain] calls back into.
     *
     * An interface rather than two lambda parameters so the render thread's
     * drain allocates nothing: a capturing lambda per frame is small, but this
     * is the one place in the app where "small per frame" has already been
     * measured and budgeted, and there is no reason to spend it here.
     */
    interface Sink {
        fun onStroke(stroke: Stroke)
        fun onClear()
    }

    private val queue = ConcurrentLinkedQueue<Commit>()

    /** Commits waiting for the render thread. Diagnostic only. */
    val pending: Int get() = queue.size

    /** UI thread, at pen-up. */
    fun commit(stroke: Stroke) {
        queue.add(Commit.Draw(stroke))
    }

    /**
     * UI thread, from the Clear button.
     *
     * Queued rather than applied, and **not** paired with draining what is
     * already in the queue. Draining would look like an optimisation — those
     * strokes are about to be erased anyway — and would be wrong the moment a
     * stroke is committed between the drain and the enqueue, which then lands
     * *behind* the clear and survives it. Let the order be the order.
     */
    fun clear() {
        queue.add(Commit.Clear)
    }

    /**
     * Render thread. Applies every commit queued so far, in order, and returns
     * how many.
     *
     * Drains to empty rather than taking one per frame. A frame that applied
     * one commit and left the rest would show a layer that is behind the
     * document by an unbounded amount under any burst, and the cost of the
     * whole queue is the cost of the strokes in it either way.
     */
    fun drain(sink: Sink): Int {
        var applied = 0
        while (true) {
            val commit = queue.poll() ?: return applied
            when (commit) {
                is Commit.Draw -> sink.onStroke(commit.stroke)
                Commit.Clear -> sink.onClear()
            }
            applied++
        }
    }

    /**
     * Drop everything queued without applying it.
     *
     * For teardown only — the view is gone and so is the surface that would
     * have drawn it. Not for Clear: see [clear].
     */
    fun abandon() {
        queue.clear()
    }
}
