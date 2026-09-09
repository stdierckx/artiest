package be.thalos.artiest.doc

/**
 * One reversible edit, and what keeping it costs.
 *
 * Deliberately says nothing about pixels. The history below is the *policy* —
 * how deep, how much memory, what a new edit does to the redo chain — and it is
 * decidable without a device, which is why it is a separate type with its own
 * tests. [PixelPatch] is the one implementation that knows what a bitmap is.
 */
interface UndoStep {

    /** What this step occupies. Used for the memory budget, so it must be real. */
    val bytes: Long

    /** Release whatever this holds. Called exactly once, by the history. */
    fun recycle()
}

/**
 * The undo and redo chains, with a memory budget.
 *
 * ## Why this is region snapshots and not a stroke list
 *
 * The obvious undo for a drawing app is to keep the strokes and re-rasterise
 * without the last one. It is rejected here for two reasons, both of which get
 * worse rather than better as Phase 2 lands:
 *
 * - **Replay has to be deterministic, and Phase 2's brush will not be.** W9 adds
 *   scatter, size jitter and spin. Re-running a stroke through a randomised
 *   brush draws something *similar*, not something identical, so undo followed
 *   by redo would silently alter the drawing. Restoring pixels cannot do that.
 * - **Replay costs the whole document.** Undoing the last of three hundred
 *   strokes means rasterising two hundred and ninety-nine.
 *
 * Snapshotting the region an edit is about to touch has neither problem, and it
 * is the interface a tiled copy-on-write manager implements later — "save what
 * is under this rectangle before writing it" is exactly what a tile manager
 * does, one tile at a time. So this is a step toward Phase 3's tiling rather
 * than a detour around it. What it is *not* is tiling: a stroke across the page
 * still snapshots the page's width, and the budget below is what stops that
 * being unbounded.
 *
 * ## The budget, and why there are two of them
 *
 * A depth cap alone does not bound memory, because one sweeping stroke can
 * touch the whole document — 3300 x 2160 x 4 is 28.5 MB. A byte cap alone does
 * not bound depth in a useful way either, because a page of small strokes would
 * keep hundreds of steps nobody will ever walk back through. So both, and
 * whichever bites first wins.
 *
 * Eviction is from the **oldest** end, which is the correct end: the step you
 * are most likely to want is the one you just made. Losing the far end of a long
 * history is the cost of not running out of memory, and it is a cost every
 * raster editor pays.
 *
 * ## Threading
 *
 * None. This class is not synchronised and must not be shared across threads.
 * In this app it lives on the render thread, reached only from inside
 * `CommitQueue.drain`, which is single-threaded by construction — the same
 * ordering guarantee that lets a Clear queue up behind a stroke.
 */
class UndoHistory<T : UndoStep>(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {

    init {
        require(maxDepth >= 1) { "a history $maxDepth deep cannot undo anything" }
        require(maxBytes >= 1L) { "maxBytes was $maxBytes" }
    }

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    /** What both chains occupy together. */
    var bytes: Long = 0L
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoDepth: Int get() = past.size
    val redoDepth: Int get() = future.size

    /**
     * A new edit happened. [step] is what the layer looked like before it.
     *
     * Discards the redo chain, because there is no longer anything to redo
     * *to*: the branch the user was on has been left. This is the behaviour
     * every editor has and the only one that is not surprising.
     */
    fun record(step: T) {
        while (future.isNotEmpty()) drop(future.removeLast())
        past.addLast(step)
        bytes += step.bytes
        evict()
    }

    /**
     * Walk one step back.
     *
     * [exchange] is handed the step to restore and must do two things in one
     * go: capture what is *currently* in that region, put the step back, and
     * return the capture. It is one call rather than a peek and a push because
     * a half-applied undo — restored pixels with no inverse recorded — is a
     * redo that draws the wrong thing, and the shape of this API is what makes
     * that unwritable.
     *
     * The consumed step is recycled unless [exchange] hands it straight back as
     * the inverse, which is how a caller reuses one buffer for both directions.
     *
     * Returns false if there was nothing to undo.
     */
    fun undo(exchange: (T) -> T): Boolean = step(past, future, exchange)

    /** Walk one step forward. See [undo]; this is the mirror of it. */
    fun redo(exchange: (T) -> T): Boolean = step(future, past, exchange)

    private fun step(from: ArrayDeque<T>, to: ArrayDeque<T>, exchange: (T) -> T): Boolean {
        val consumed = from.removeLastOrNull() ?: return false
        bytes -= consumed.bytes
        val inverse = exchange(consumed)
        if (inverse !== consumed) consumed.recycle()
        to.addLast(inverse)
        bytes += inverse.bytes
        evict()
        return true
    }

    /** Forget everything, releasing it. The Clear button's other half. */
    fun clear() {
        while (past.isNotEmpty()) drop(past.removeLast())
        while (future.isNotEmpty()) drop(future.removeLast())
    }

    /**
     * Enforce both caps.
     *
     * Depth is checked against the undo chain only — it is what "how many times
     * can I press undo" means, and a redo chain is bounded by the undos that
     * built it. Bytes are checked across both, because both are memory.
     *
     * At least one step always survives. A single edit larger than the whole
     * budget — clearing a full page is 28.5 MB and could be most of it — is
     * still worth being able to undo, and dropping it would make the budget's
     * behaviour depend on the size of the last thing you did.
     */
    private fun evict() {
        while (past.size > maxDepth) drop(past.removeFirst())
        while (bytes > maxBytes && past.size + future.size > 1) {
            if (past.size > 1) {
                drop(past.removeFirst())
            } else if (future.isNotEmpty()) {
                // The front of the redo chain is the *deepest* redo — the one
                // undone longest ago. Losing it costs the far end of the
                // forward walk and keeps the near end, which is the right way
                // round for the same reason eviction is oldest-first.
                drop(future.removeFirst())
            } else {
                break
            }
        }
    }

    private fun drop(step: T) {
        bytes -= step.bytes
        step.recycle()
    }

    companion object {

        /**
         * 48 MB. Bitmap pixels have been native rather than Java-heap since
         * API 26, so this is not spent against the 512 MB heap the readout
         * shows — it is device memory, of which the tablet reported 4.65 GiB
         * free. Room for one full-page Clear and a normal working history, or
         * for a great many ordinary strokes.
         */
        const val DEFAULT_MAX_BYTES = 48L * 1024L * 1024L

        /**
         * Deep enough to walk out of a mistake, short enough that the far end
         * is genuinely worthless. Nothing measured this; it is the number to
         * change first if the answer is ever "I could not undo far enough".
         */
        const val DEFAULT_MAX_DEPTH = 32
    }
}
