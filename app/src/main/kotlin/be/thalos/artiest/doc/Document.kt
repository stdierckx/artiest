package be.thalos.artiest.doc

import android.graphics.Color
import be.thalos.artiest.engine.ink.Bounds

/**
 * What is being drawn: a size, one [Layer], and the rectangle each committed
 * stroke painted into.
 *
 * A pure model that happens to hold an Android type. It is in `:app` by
 * transitivity — [Layer] owns a `Bitmap` and cannot leave — not because
 * anything about a document needs a device.
 *
 * **One layer, not a stack.** Phase 3 adds the stack, and it adds it by
 * replacing this field, not by generalising it now: a `List<Layer>` of size one
 * with a compositor that composites nothing is machinery for a feature that has
 * not been designed yet, and the layer's own alpha-carrying invariant is
 * already the whole of what Phase 3 needs from Phase 1.
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
    val layer: Layer = Layer(widthPx, heightPx, enforceOffMainThread)

    // Append-only, and the only history Phase 1 keeps. An ArrayList rather than
    // a primitive float buffer because a Bounds is immutable and shareable, so
    // there is nothing to copy out and no aliasing to prevent; four floats
    // unpacked into a growable FloatArray would save one header per stroke and
    // cost the type that makes the values safe to hand around.
    private val strokeBounds = ArrayList<Bounds>()

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
    }

    /**
     * Drop the stroke history. The clear button's half of the clear, and it has
     * to happen or the history describes ink that is no longer in the layer.
     *
     * Deliberately *not* paired with [Layer.blank] in one call. Blanking the
     * pixels is the render thread's, this list is the UI thread's, and a method
     * that did both would be a single entry point that has to run on two
     * threads. W8 sequences them; the invariant it owes is that this list
     * describes exactly what is in the layer.
     */
    fun forgetStrokes() {
        strokeBounds.clear()
    }

    /**
     * Release the pixels. Only when the owner is genuinely finishing, and only
     * after the last view detach has joined its render thread — see [Layer.close].
     */
    fun close() {
        layer.close()
    }

    companion object {

        /**
         * 2160 x 3300, which is `:spike`'s `DOC_W`/`DOC_H` finally out of a
         * private constant in a frozen module. It is 1.5x the panel on both
         * axes — the panel is 1440x2200 in all five Phase 0 probe dumps — so
         * portrait fit is exactly 2/3 with no letterbox on either axis.
         *
         * Nothing binds at this size on this device. Memory: 27.19 MiB of a
         * 121 MiB peak against 4.4 GiB free. Texture cap: 3300 against a
         * measured `GL_MAX_TEXTURE_SIZE` of 16383, a factor of 4.96. Fill rate:
         * W2 measured the full-redraw loop at p99 4.00 ms against an 11.1 ms
         * budget with this exact layer live as a rotating texture, because the
         * per-frame blit is destination-bound on the 2.6 Mpx surface rather than
         * on the 7.1 Mpx document.
         */
        const val DEFAULT_WIDTH_PX = 2160

        /** See [DEFAULT_WIDTH_PX]. */
        const val DEFAULT_HEIGHT_PX = 3300
    }
}
