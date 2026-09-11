package be.thalos.artiest.doc

import android.graphics.Color
import android.graphics.Rect
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke

/**
 * What is being drawn: a size, one [Layer], and the rectangle each committed
 * stroke painted into.
 *
 * A pure model that happens to hold an Android type. It is in `:app` by
 * transitivity — [Layer] owns a `Bitmap` and cannot leave — not because
 * anything about a document needs a device.
 *
 * **A stack of layers, and [layer] is whichever one the pen is on.** Phase 1
 * shipped a single sheet and said the stack would arrive by replacing the
 * field rather than by generalising it early; that is what has happened. The
 * shape of the stack, the rule about which thread may change it and the
 * ordering against strokes all live in [LayerStack] — this class keeps the
 * queue, the history and the bookkeeping, and hands the stack the operations
 * that reach it through the same queue a stroke does.
 *
 * [layer] stays, meaning "the sheet the pen draws on". Every caller that had it
 * before wanted the active sheet and now says so; the ones that want *all* the
 * sheets — the compositor and the exporter — walk [layers] instead.
 *
 * **[paperColor] lives here and is drawn by the renderer.** The render body
 * paints it with `drawColor` before blitting the layer over it, once per frame,
 * into the frame — never into the layer's pixels. See [Layer]: that is the
 * invariant, and this field is where the temptation to bake it in starts.
 *
 * **No transform, no `Canvas`, no `Matrix`.** A `CanvasTransform` field here
 * would invite exactly the read the plan forbids absolutely: the transform is
 * frozen at ACTION_DOWN and both render callbacks concat that one snapshot, so
 * the render thread must never see a live one. A stroke rendered at two
 * transforms is ink that lands somewhere other than the pen, and the shared
 * mutable field is the only way to get there.
 *
 * **No dabs are retained.** Only a [Bounds] per stroke — four floats. Keeping
 * the dab payload after the commit rebuilds the ever-growing scene list the
 * layer bitmap exists to replace: `LowLatencyInkView`'s `ArrayList<Segment>`,
 * replayed on every commit and mutated from two threads. Phase 1 adopts that
 * arm's front-buffered rendering and drops exactly this part of its scene
 * model. (The class the plan rejects outright is `LowLatencyCanvasView`, and
 * for a different reason: its scene `Bitmap` is view-sized and its `onDraw`
 * calls `setMatrix`.) The bounds is all Phase 3's undo snapshots.
 *
 * Phase 1 has no autosave, so process death loses everything that was not
 * exported. That is a deliberate deferral and not an oversight, but it is now a
 * property of this type's lifetime, and it belongs in the release notes rather
 * than being discovered on the tablet.
 *
 * UI thread only, and deliberately not synchronized. The stroke history is
 * appended at pen-up beside the handoff that publishes the stroke, and the
 * render thread never reads it: it consumes a `Stroke`, stamps it and forgets
 * it. [Layer]'s lock is the app's only lock, and a second one here would be the
 * start of a lock ordering nobody has designed.
 */
class Document(
    val widthPx: Int = DEFAULT_WIDTH_PX,
    val heightPx: Int = DEFAULT_HEIGHT_PX,
    val paperColor: Int = Color.WHITE,
    /** See [Layer]'s parameter of the same name. Off in unit tests only. */
    enforceOffMainThread: Boolean = true,
) {

    init {
        // A zero or negative extent reaches Bitmap.createBitmap and throws there
        // naming a width, several frames from whatever computed it.
        require(widthPx > 0 && heightPx > 0) { "document was ${widthPx}x$heightPx" }
    }

    /**
     * Allocated with the document, not at attach and not at `surfaceChanged`.
     *
     * `:spike` allocates at attach and gives the reason — document size is
     * independent of surface size, so there is never a window in which the layer
     * is null and rotation changes only the matrix. The same reason applies one
     * level further out: the document outlives the view, so a configuration
     * change rebuilds the view against the same pixels rather than reallocating
     * them, which is what keeps rotation from both losing the drawing and
     * opening a recycle-against-render race. Keep the layer out of the view's
     * lifecycle.
     */
    val layers: LayerStack = LayerStack(widthPx, heightPx, enforceOffMainThread)

    /**
     * The stencil: where the pen is allowed to put ink.
     *
     * On the document and not on a sheet, and the reason is in [Selection]'s
     * header: a selection is something you hold over the drawing while you work
     * through several layers.
     */
    val selection: Selection = Selection(widthPx, heightPx)

    /**
     * Pixels lifted off a sheet and not yet put back, or null.
     *
     * **Render thread**, like everything else that touches pixels — but read by
     * the UI thread as well, which is what the volatile is for: the transform
     * box has to know whether there is anything to transform, and the export
     * has to know whether to drop one first.
     */
    @Volatile
    var floating: FloatingPixels? = null
        private set

    /**
     * The sheet the pen is on.
     *
     * A property over [layers] rather than a field, so that "the active layer"
     * has exactly one answer and switching sheets cannot leave a stale
     * reference behind. **Render thread**, like everything else about the
     * stack's shape: the UI thread reads `layers.snapshot`, which carries no
     * bitmaps.
     */
    val layer: Layer get() = layers.active.layer

    /**
     * Layer changes the render thread has not applied yet.
     *
     * Owned here rather than by the view, and that placement is the point: the
     * document outlives every attach and detach, so a rotation that rebuilds
     * the `SurfaceView` finds its queue intact and the first render against the
     * new surface stamps whatever was still waiting. A queue owned by the view
     * would lose exactly the strokes that were in flight when the surface went
     * away — the ones the user had just finished.
     */
    private val commits = CommitQueue()

    /** Commits waiting for the render thread. Diagnostic only. */
    val pendingCommits: Int get() = commits.pending

    /**
     * The undo chains. **Render thread only** — see [UndoHistory]'s threading
     * note. Every entry point that touches it below says so in its own KDoc.
     */
    private val history = UndoHistory<PixelPatch>()

    /** How many sheets the drawing has. Diagnostic; the panel reads the snapshot. */
    val layerCount: Int get() = layers.snapshot.size

    /**
     * Whether the buttons should be live, published for the UI thread.
     *
     * Written by the render thread after each history change and read by the
     * UI, so it can be one frame behind — the same skew already documented for
     * the stroke history, and for the same reason: the crossing is an ordered
     * queue, not a lock. A stale `false` costs a button that lights up 11 ms
     * late. Nothing acts on these except the enabled state of a control; the
     * *action* always queues and lets the render thread decide whether there
     * was anything there, so a stale `true` costs nothing at all.
     */
    @Volatile
    var canUndo: Boolean = false
        private set

    /** See [canUndo]. */
    @Volatile
    var canRedo: Boolean = false
        private set

    /** How much the undo history is holding. Diagnostic; render thread writes it. */
    @Volatile
    var historyBytes: Long = 0L
        private set

    /** See [historyBytes]. */
    @Volatile
    var undoDepth: Int = 0
        private set

    /** See [historyBytes]. */
    @Volatile
    var redoDepth: Int = 0
        private set

    // Append-only, and the only history Phase 1 keeps. An ArrayList rather than
    // a primitive float buffer because a Bounds is immutable and shareable, so
    // there is nothing to copy out and no aliasing to prevent; four floats
    // unpacked into a growable FloatArray would save one header per stroke and
    // cost the type that makes the values safe to hand around.
    private val strokeBounds = ArrayList<Bounds>()

    /**
     * The bounds of strokes that have been undone, so redo can put them back.
     *
     * Bookkeeping for [strokeCount] and nothing more — the undo that matters is
     * the pixel history, which the render thread owns. Two cases make this
     * count approximate rather than exact, and they are named here rather than
     * discovered: undoing a **Clear** restores the pixels but not the stroke
     * list, because Clear discarded it; and a stroke evicted from the pixel
     * history by its memory budget still has a bounds here. Both leave a
     * diagnostic readout slightly wrong and neither can lose ink.
     */
    private val undoneBounds = ArrayList<Bounds>()

    /** How many strokes are committed into [layer]. */
    val strokeCount: Int get() = strokeBounds.size

    /** The document-space rectangle stroke [i] painted into. */
    fun strokeBoundsAt(i: Int): Bounds = strokeBounds[i]

    /**
     * Record that a stroke was committed. Called once per commit, at pen-up.
     *
     * Phase 1's second forward obligation, and the whole of it. An empty bounds
     * is refused rather than stored: a stroke that painted nothing has no region
     * for Phase 3 to restore, and storing one would put an index in the history
     * that undo cannot act on. `Stroke.copyOf` already refuses to build a
     * non-empty stroke with an empty bounds, so reaching here with one means the
     * caller committed a stroke it never built.
     */
    fun recordStroke(bounds: Bounds) {
        require(!bounds.isEmpty) { "committed stroke has an empty bounds" }
        strokeBounds.add(bounds)
        // Drawing leaves the branch the redo chain led onto. The pixel history
        // does the same thing in `UndoHistory.record`; this is its shadow.
        undoneBounds.clear()
    }

    /**
     * Pen-up: take a finished stroke into the document. UI thread.
     *
     * Records the bounds and queues the pixels **in one call**, which is the
     * only way the two can be kept in step. They were deliberately separate
     * through W8, on the grounds that the history is the UI thread's and the
     * pixels are the render thread's and no single entry point should run on
     * two threads. [CommitQueue] is what makes that reasoning obsolete: the
     * crossing is now an ordered queue, so this method does its UI-thread half
     * and hands the other half over, and there is no arrangement of threads in
     * which one happens and the other does not.
     *
     * Returns false for a stroke that painted nothing — a tap that produced no
     * dabs — which is neither recorded nor queued. `recordStroke` would refuse
     * its empty bounds, and rightly: Phase 3 cannot restore a region that does
     * not exist.
     *
     * The history leads the pixels by up to one frame, and that is the whole of
     * the skew. Nothing reads both: `PngExporter` reads the layer, undo reads
     * the bounds, and the readout is a readout.
     */
    fun commitStroke(stroke: Stroke): Boolean {
        if (stroke.dabCount == 0) return false
        recordStroke(stroke.bounds)
        commits.commit(stroke)
        return true
    }

    /**
     * Clear: drop the history and queue the blank behind everything already
     * committed. UI thread.
     *
     * Queued rather than applied so that Clear means what the user meant by it
     * — after everything drawn so far — including a stroke finished a
     * millisecond earlier that the render thread has not stamped yet.
     */
    fun requestClear() {
        forgetStrokes()
        commits.clear()
    }

    /**
     * Undo: queue the pixel restore, and move one stroke's bookkeeping across.
     * UI thread.
     *
     * Unconditional. The queue is the authority on whether there is anything to
     * undo — [canUndo] can be a frame stale, and refusing here on a stale read
     * would make the button dead for the first frame after a stroke. An undo
     * that arrives with an empty history is a no-op on the render thread, which
     * is the cheapest possible way to be wrong.
     */
    fun requestUndo() {
        if (strokeBounds.isNotEmpty()) {
            undoneBounds.add(strokeBounds.removeAt(strokeBounds.size - 1))
        }
        commits.undo()
    }

    /** Redo. The mirror of [requestUndo]. UI thread. */
    fun requestRedo() {
        if (undoneBounds.isNotEmpty()) {
            strokeBounds.add(undoneBounds.removeAt(undoneBounds.size - 1))
        }
        commits.redo()
    }

    /**
     * Snapshot the region a stroke is about to paint. **Render thread**, called
     * from the commit sink immediately before the stroke is rasterised.
     *
     * A stroke entirely off the page snapshots nothing and records nothing:
     * there is no region to restore, and an entry that restores nothing is an
     * undo press that appears to do nothing.
     */
    fun snapshotBeforeStroke(bounds: Bounds) {
        val active = layers.active
        val patch = PixelPatch.capture(
            active.id, active.layer, confined(bounds), widthPx, heightPx,
        ) ?: return
        history.record(patch)
        publishHistory()
    }

    /**
     * Snapshot what a Clear is about to remove. **Render thread.**
     *
     * The whole page, or the stencil's extent when there is one — because with
     * a selection on the page a Clear removes only what is inside it.
     */
    fun snapshotBeforeClear() {
        val active = layers.active
        val patch = if (selection.active) {
            PixelPatch.capture(
                active.id, active.layer, boundsOf(selection.bounds), widthPx, heightPx,
            )
        } else {
            PixelPatch.captureAll(active.id, active.layer, widthPx, heightPx)
        } ?: return
        history.record(patch)
        publishHistory()
    }

    /**
     * [bounds], cut down to what the stencil could possibly have let through.
     *
     * A stroke that runs across the page with a small selection on it can only
     * have changed pixels inside that selection, so the rectangle worth
     * snapshotting is the overlap. Free, and it keeps the 48 MB history budget
     * from being spent on rows that could not have moved.
     *
     * Returns [bounds] unchanged when nothing is selected, and when the two do
     * not overlap returns something empty — which `PixelPatch.capture` turns
     * into null, so the stroke records no undo step at all. That is right: a
     * stroke entirely outside the stencil changed nothing.
     */
    private fun confined(bounds: Bounds): Bounds {
        if (!selection.active || bounds.isEmpty) return bounds
        val r = selection.bounds
        val left = maxOf(bounds.left, r.left.toFloat())
        val top = maxOf(bounds.top, r.top.toFloat())
        val right = minOf(bounds.right, r.right.toFloat())
        val bottom = minOf(bounds.bottom, r.bottom.toFloat())
        // `Bounds.of` refuses an inverted rectangle rather than normalising it,
        // and no overlap is exactly that. EMPTY is the answer the caller can
        // use: it captures nothing and records nothing.
        if (left > right || top > bottom) return Bounds.EMPTY
        return Bounds.of(left, top, right, bottom)
    }

    private fun boundsOf(r: android.graphics.Rect): Bounds =
        Bounds.of(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())

    /** **Render thread.** Returns false if there was nothing to undo. */
    fun applyUndo(): Boolean {
        val moved = history.undo(exchange)
        publishHistory()
        return moved
    }

    /** **Render thread.** Returns false if there was nothing to redo. */
    fun applyRedo(): Boolean {
        val moved = history.redo(exchange)
        publishHistory()
        return moved
    }

    /**
     * Restore a patch and hand back what was under it, in one step.
     *
     * Stored rather than written inline at both call sites because it is
     * identical in both directions — undo and redo differ only in which chain
     * they pull from — and because a capturing lambda per press would allocate
     * on the render thread, which is the one path in this app with a measured
     * budget.
     *
     * The recapture happens **before** the restore. Reversed, the inverse would
     * be a copy of the patch itself and redo would put back what undo had just
     * removed.
     */
    private val exchange: (PixelPatch) -> PixelPatch = { patch ->
        // Against the stack and not against `layer`: a patch knows which sheet
        // it came from, and undoing a stroke made on a sheet the pen has since
        // left must put those pixels back where they were rather than onto
        // whatever is under the pen now. A patch whose sheet has been deleted
        // recaptures nothing and restores nothing, and is still consumed --
        // pressing Undo past a deleted layer walks over it rather than stopping
        // on it.
        val inverse = patch.recapture(layers) ?: patch
        patch.restoreInto(layers)
        layers.touchAll()
        inverse
    }

    /** **Render thread.** Publish the history's state for the UI to read. */
    private fun publishHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        undoDepth = history.undoDepth
        redoDepth = history.redoDepth
        historyBytes = history.bytes
    }

    /**
     * Apply every queued commit. **Render thread only**, and the sink is what
     * actually touches [layer].
     */
    fun drainCommits(sink: CommitQueue.Sink): Int = commits.drain(sink)

    /**
     * Wait, briefly and with a bound, until the render thread has stamped
     * everything queued. Returns what is still outstanding.
     *
     * **Why anything that reads the pixels has one.** [commitStroke] records a
     * stroke's bounds here on the UI thread and *queues* its pixels; the layer
     * receives them at the next render. Copy the layer inside that window and
     * the copy is missing the last stroke, this object's own count says it is
     * there, and nothing anywhere reports a problem. `PngExporter` found that
     * first and `ProjectSaver` has exactly the same hole — which is why the
     * wait lives here now rather than in one of them.
     *
     * Polling rather than a completion latch, because the render thread belongs
     * to graphics-core and the only signal is a callback on the view; a model
     * that took a dependency on the view to get it would be coupling the file
     * format to the `SurfaceView` for one bit. At 2 ms granularity against a
     * 16.7 ms frame the poll costs one wakeup.
     *
     * **The caller still has to have asked for a render.** Nothing here can. If
     * the queue is non-empty because there is no surface at all — the app is
     * backgrounded — the wait runs out and the caller proceeds with what there
     * is and says so.
     */
    suspend fun awaitStamped(waitMs: Long = DEFAULT_WAIT_MS): Int {
        if (pendingCommits == 0) return 0
        val deadlineNs = System.nanoTime() + waitMs * 1_000_000L
        while (pendingCommits > 0 && System.nanoTime() < deadlineNs) {
            kotlinx.coroutines.delay(POLL_MS)
        }
        return pendingCommits
    }

    /**
     * Queue a change to the layer stack. UI thread, from the layers panel.
     *
     * Queued for the reason [requestClear] is, and unconditional for the reason
     * [requestUndo] is: the render thread is the authority on whether the sheet
     * an operation names still exists, and refusing here on a stale snapshot
     * would make a button dead for the first frame after a change.
     */
    fun requestLayers(op: LayerOp) {
        commits.layers(op)
    }

    /**
     * Forget every undo step. **Render thread**, as a project is opened.
     *
     * It has to happen, and it has to happen here rather than being left to the
     * caller: a patch holds pixels belonging to a sheet that has just been
     * closed, keyed by an id the new stack will never hand out again. Replaying
     * one would restore nothing at best. The pixels themselves are the other
     * half of the reason — up to 48 MiB of them, describing a drawing that is
     * no longer open.
     */
    fun resetHistory() {
        history.clear()
        publishHistory()
    }

    /**
     * Queue a change to what is selected. UI thread, from a marquee gesture or
     * the selection panel.
     *
     * Queued, and for the third time in this class for the same reason: order
     * against the strokes is the thing that has to survive. See
     * [CommitQueue.Commit.Select].
     */
    fun requestSelect(op: SelectOp) {
        commits.select(op)
    }

    /** Queue a change to the floating pixels. UI thread. See [CommitQueue.Commit.Float]. */
    fun requestFloat(op: FloatOp) {
        commits.float(op)
    }

    /**
     * Apply one float operation. **Render thread**, from the commit sink.
     *
     * Returns true if anything changed, which is what the caller uses to decide
     * whether a redraw is worth asking for.
     */
    fun applyFloat(op: FloatOp): Boolean {
        val current = floating
        return when (op) {
            FloatOp.LiftSelection -> {
                if (current != null) return false
                val lifted = FloatingPixels.lift(layers.active, selection) ?: return false
                floating = lifted
                true
            }

            FloatOp.LiftLayer -> {
                if (current != null) return false
                val lifted = FloatingPixels.liftWhole(layers.active, widthPx, heightPx)
                    ?: return false
                floating = lifted
                true
            }

            is FloatOp.Move -> {
                if (current == null) return false
                current.matrix = op.matrix
                true
            }

            FloatOp.Drop -> {
                if (current == null) return false
                // The stencil follows the pixels. Read before the drop closes
                // the float, because `release` is the last thing `dropFloat`
                // does and a matrix read after it would be reading a corpse.
                val moved = current.matrix
                dropFloat(current)
                selection.transformBy(moved)
                floating = null
                true
            }

            FloatOp.Cancel -> {
                // Nothing to undo: the sheet was never written. That is the
                // whole point of leaving the source in place -- see
                // [FloatingPixels].
                if (current == null) return false
                current.release()
                floating = null
                true
            }
        }
    }

    /**
     * The one write a transform makes, and the one undo step it records.
     *
     * The patch covers the union of where the pixels were and where they went,
     * taken **before** either half of the write. Two patches would be two
     * presses of undo for one move, which is not what the hand did.
     */
    private fun dropFloat(float: FloatingPixels) {
        val entry = layers.byId(float.sourceLayerId)
        if (entry == null) {
            // The sheet was deleted while the pixels were in the air. There is
            // nowhere to put them and nothing to undo.
            float.release()
            return
        }
        val union = Rect(float.sourceBounds)
        val moved = Rect()
        float.transformedBounds(moved)
        union.union(moved)
        val patch = PixelPatch.capture(
            float.sourceLayerId,
            entry.layer,
            Bounds.of(
                union.left.toFloat(), union.top.toFloat(),
                union.right.toFloat(), union.bottom.toFloat(),
            ),
            widthPx,
            heightPx,
        )
        float.dropInto(entry.layer)
        float.release()
        if (patch != null) {
            history.record(patch)
            publishHistory()
        }
        layers.touchActive()
    }

    /** A fresh empty sheet, allocated by the UI thread. See [LayerStack]. */
    fun newLayer(): Layer = layers.newLayer()

    /** The next unused sheet name built on [base]. UI thread. */
    fun suggestLayerName(base: String = "Layer"): String = layers.suggestName(base)

    /** Drop queued commits without applying them. Teardown only. */
    fun abandonCommits() {
        commits.abandon()
    }

    /**
     * Drop the stroke history. The clear button's half of the clear, and it has
     * to happen or the history describes ink that is no longer in the layer.
     *
     * Left as a primitive. [requestClear] is what callers want — it queues the
     * blank behind everything already committed, so the two halves cannot
     * disagree — and this is the UI-thread half of it, kept separate because
     * `DocumentTest` drives the history on its own. The invariant either way is
     * that this list describes what is in the layer, within one frame.
     */
    fun forgetStrokes() {
        strokeBounds.clear()
        undoneBounds.clear()
    }

    /**
     * Release the pixels. Only when the owner is genuinely finishing, and only
     * after the last view detach has joined its render thread — see [Layer.close].
     */
    fun close() {
        // Commits first, and it is not merely tidy: a queued stroke whose sink
        // reached a closed layer would be a write against recycled pixels.
        // Layer.write refuses after close, so this is belt to that braces —
        // but the belt is what says the strokes are gone on purpose.
        commits.abandon()
        // Before the layer, because these are bitmaps of their own and the
        // history outliving the layer would be a pile of pixels nobody frees.
        history.clear()
        publishHistory()
        layers.close()
        selection.close()
        floating?.release()
        floating = null
    }

    companion object {

        /**
         * How long anything reading the pixels waits for the render thread.
         *
         * A quarter of a second is fifteen frames: long enough that a stamp
         * which is going to happen has happened, short enough that a save with
         * no surface behind it is not a hang. Was `PngExporter`'s, and moved
         * here with [awaitStamped] when the saver turned out to need the same
         * wait for the same reason.
         */
        const val DEFAULT_WAIT_MS = 250L

        /** One wakeup against a 16.7 ms frame. See [awaitStamped]. */
        private const val POLL_MS = 2L

        /**
         * 3300 x 2160 — **landscape**, matching how the tablet is held.
         *
         * The number is `:spike`'s `DOC_W`/`DOC_H` finally out of a private
         * constant in a frozen module, and the rule behind it is unchanged: it
         * is 1.5x the panel on both axes, so the fit is exactly 2/3 with no
         * letterbox on either axis. The panel reports 1440x2200 portrait in all
         * five Phase 0 probe dumps and the device is used at 2200x1440, so
         * 1.5x is 3300x2160 and the two-thirds property survives the rotation
         * exactly. Phase 1 shipped the portrait orientation of the same
         * rectangle, which left a page standing up in a landscape window with
         * bars down both sides — the user's report, and the reason this is the
         * way round it is.
         *
         * Nothing binds at this size on this device. Memory: 27.19 MiB of a
         * 121 MiB peak against 4.4 GiB free. Texture cap: 3300 against a
         * measured `GL_MAX_TEXTURE_SIZE` of 16383, a factor of 4.96. Fill rate:
         * W2 measured the full-redraw loop at p99 4.00 ms against an 11.1 ms
         * budget with this exact layer live as a rotating texture, because the
         * per-frame blit is destination-bound on the 2.6 Mpx surface rather than
         * on the 7.1 Mpx document. Every one of those is a property of the
         * pixel count, which the rotation does not change.
         */
        const val DEFAULT_WIDTH_PX = 3300

        /** See [DEFAULT_WIDTH_PX]. */
        const val DEFAULT_HEIGHT_PX = 2160
    }
}
