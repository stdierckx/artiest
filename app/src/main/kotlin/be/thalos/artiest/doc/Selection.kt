package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF

/**
 * The stencil: the part of the page the pen is allowed to touch.
 *
 * ## One selection, for the whole document
 *
 * Not one per sheet. A selection is a stencil you hold over the drawing while
 * you work through several sheets — mask off a shape, paint it on one layer,
 * switch to another and shade inside the same shape — and one that vanished
 * when you changed sheet would have to be redrawn every time it became useful.
 * Photoshop, Krita and GIMP all make the same choice.
 *
 * ## The path is the truth and the mask is a cache
 *
 * Every operation is expressed on a [Path] in document coordinates: a rectangle
 * is `addRect`, an ellipse is `addOval`, a lasso is the pen's own trail closed
 * at the end, and add, subtract and intersect are `Path.op`. The mask is then
 * rebuilt from the path in one antialiased fill and is the only thing the ink
 * path ever reads.
 *
 * **Rebuilt whole, every time, and that is a measurement rather than
 * laziness.** `CompositeBench` times a full-page `ALPHA_8` clear and
 * antialiased path fill at **0.31 ms** on the host, and a `Path.op` union of
 * two 240-segment lassos at **0.32 ms** — both CPU work that the app does on
 * the CPU too, so those numbers transfer. At that price there is no case for
 * incremental mask updates, no case for tiling the mask, and no case for
 * keeping a mask and a path in sync by any means other than throwing the mask
 * away. That deletes the most bug-prone thing in the design before it is
 * written.
 *
 * ## Threading
 *
 * The same shape as [LayerStack], for the same reason. **Every mutation happens
 * on the render thread, inside `CommitQueue.drain`**: "select this region"
 * means *after everything I have drawn*, and a stroke finished a millisecond
 * ago that has not been stamped yet is part of what the user drew. Applied
 * straight from the UI thread, a selection would land in front of that stroke
 * and confine ink that was laid before it existed.
 *
 * The UI thread reads [snapshot] and nothing else. **The published `Path` is a
 * copy, and that is the one new threading rule this class adds.** A `Path` is
 * mutable native state; `LayerStack` can publish strings and floats because
 * nothing in them can change, and handing out the live path instead would be a
 * shape changing underneath a draw call while the marching ants are stroking
 * it. A few hundred segments copy in microseconds and it happens once per
 * selection edit.
 *
 * ## Empty, and why an empty selection deselects
 *
 * **This departs from `docs/phase3-plan.md`, deliberately.** The plan drew a
 * distinction between "no selection", where the whole page is drawable, and
 * "an active selection with no area", where nothing is — and said conflating
 * them "makes the eraser stop working after an unlucky boolean op with no way
 * to tell why". That is right about the danger and wrong about which way round
 * it runs. An active-but-empty selection *is* the state where nothing works and
 * nothing explains it: there is no region, so there are no marching ants, so
 * the screen looks exactly like a document with no selection while the pen
 * silently does nothing.
 *
 * So an operation that leaves no area **deselects**. The cost is that
 * "select nothing" is not expressible, which nobody wants, and inverting a
 * full-page selection gives back the whole page rather than none of it.
 * Photoshop reaches the same place from the other direction: it refuses a
 * selection with no pixels in it and drops it.
 */
class Selection(
    val widthPx: Int,
    val heightPx: Int,
) {

    /**
     * The selected region, in document coordinates. Render thread only.
     *
     * Empty *and* [active] false is the ordinary "no selection" state; see the
     * class header for why the other combination does not occur.
     */
    private val path = Path()

    /**
     * The path as coverage, one byte a pixel, or null before anything has been
     * selected. Render thread only.
     *
     * `ALPHA_8` because coverage is all it carries: 3300 x 2160 is 6.80 MiB,
     * against 27.19 for a sheet. Allocated on the first selection and kept for
     * the document's life afterwards — a document that never selects anything
     * never pays, and one that does is not made to pay again every time the
     * user starts over.
     *
     * Antialiased, which is the whole reason this is a mask and not a
     * `clipPath`: a clip on the software `Layer` canvas antialiases and a clip
     * on the renderer's hardware canvas does not, so clipping both paths would
     * draw a stair-stepped selection edge under the pen that snapped smooth at
     * pen-up. `CompositeBench` pins the soft half of that as an assertion.
     */
    private var mask: Bitmap? = null

    private var maskCanvas: Canvas? = null

    private val boundsRect = Rect()

    private val scratchBounds = RectF()

    /** Whether anything is selected at all. **Render thread.** */
    var active: Boolean = false
        private set

    /**
     * The selected region's extent, clipped to the page. **Render thread.**
     *
     * Meaningless while [active] is false. Handed out by reference and not
     * copied, because the render thread is the only reader and a `Rect` per
     * stroke on the commit path is an allocation this repo has already decided
     * against elsewhere. Do not retain it and do not mutate it.
     */
    val bounds: Rect get() = boundsRect

    /**
     * What the marching ants draw, republished after every change.
     *
     * Volatile and immutable, holding a `Path` nobody else will touch again.
     * See the class header.
     */
    @Volatile
    var snapshot: SelectionInfo = SelectionInfo.NONE
        private set

    // ---- mutation, render thread only ---------------------------------------

    /**
     * Apply one operation. **Render thread**, from inside `CommitQueue.drain`.
     *
     * Returns true if the selection changed. False is not an error — a
     * `None` on a document with nothing selected is an ordinary thing for a
     * button to produce — and the caller uses it only to decide whether a
     * redraw is worth asking for.
     */
    fun apply(op: SelectOp): Boolean {
        val wasActive = active
        val before = if (wasActive) Path(path) else null

        when (op) {
            is SelectOp.Shape -> combine(op.path, op.mode)
            SelectOp.All -> {
                path.rewind()
                path.addRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), Path.Direction.CW)
            }
            SelectOp.None -> path.rewind()
            SelectOp.Invert -> {
                // The page minus what is selected. `DIFFERENCE` and not
                // `REVERSE_DIFFERENCE`: `a.op(b, DIFFERENCE)` is a minus b, and
                // the two are exactly the kind of pair that is read past.
                val whole = Path()
                whole.addRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), Path.Direction.CW)
                if (active) whole.op(path, Path.Op.DIFFERENCE)
                path.set(whole)
            }
        }

        // An operation that leaves no area deselects rather than leaving a
        // stencil with no hole in it. See the class header.
        active = !path.isEmpty
        if (!active) path.rewind()

        if (!active && !wasActive) return false
        if (active && before != null && samePath(before, path)) return false

        rebuild()
        return true
    }

    /**
     * Release the mask. UI thread, at teardown, after the render thread has
     * been joined — the same rule [Layer.close] states.
     *
     * The published snapshot is left alone: its `Path` is a plain object and
     * the garbage collector will have it when the UI lets go, whereas a bitmap
     * freed while a recomposition is drawing it is a crash with a stack
     * pointing at Compose.
     */
    fun close() {
        mask?.recycle()
        mask = null
        maskCanvas = null
    }

    // ---- the ink path -------------------------------------------------------

    /**
     * The coverage mask, or null when nothing is selected. **Render thread.**
     *
     * Internal because a `Bitmap` on this class's public surface is the same
     * mistake `Layer` refuses by reflection: the one caller is the ink path,
     * which reads a sub-rectangle of it and never keeps it.
     */
    internal fun maskBitmap(): Bitmap? = if (active) mask else null

    // ---- internals ----------------------------------------------------------

    private fun combine(shape: Path, mode: SelectMode) {
        when (mode) {
            SelectMode.NEW -> path.set(shape)
            SelectMode.ADD -> if (active) path.op(shape, Path.Op.UNION) else path.set(shape)
            // Subtracting from nothing is nothing, not "everything except".
            // The other reading would make a stray subtract gesture select the
            // whole page, which is the opposite of what the hand meant.
            SelectMode.SUBTRACT -> if (active) path.op(shape, Path.Op.DIFFERENCE) else path.rewind()
            SelectMode.INTERSECT -> if (active) path.op(shape, Path.Op.INTERSECT) else path.set(shape)
        }
    }

    private fun rebuild() {
        publish()
        if (!active) return
        val bitmap = mask ?: Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ALPHA_8).also {
            mask = it
            maskCanvas = Canvas(it)
        }
        val canvas = maskCanvas ?: Canvas(bitmap).also { maskCanvas = it }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), clearPaint)
        canvas.drawPath(path, fillPaint)
    }

    private fun publish() {
        if (!active) {
            boundsRect.setEmpty()
            snapshot = SelectionInfo.NONE
            return
        }
        path.computeBounds(scratchBounds, true)
        boundsRect.set(
            Math.floor(scratchBounds.left.toDouble()).toInt().coerceIn(0, widthPx),
            Math.floor(scratchBounds.top.toDouble()).toInt().coerceIn(0, heightPx),
            Math.ceil(scratchBounds.right.toDouble()).toInt().coerceIn(0, widthPx),
            Math.ceil(scratchBounds.bottom.toDouble()).toInt().coerceIn(0, heightPx),
        )
        snapshot = SelectionInfo(Path(path), Rect(boundsRect))
    }

    /**
     * Whether two paths describe the same region.
     *
     * `Path` has no `equals` worth the name, so this asks Skia: the symmetric
     * difference of two identical regions is empty. It exists so that dragging
     * a marquee to exactly where it already was does not republish a path and
     * rebuild a mask for nothing — which matters because the ants read the
     * published path on every frame of their animation.
     */
    private fun samePath(a: Path, b: Path): Boolean {
        val diff = Path()
        return diff.op(a, b, Path.Op.XOR) && diff.isEmpty
    }

    /** White, antialiased: full coverage inside, a soft edge across the boundary. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * `CLEAR` rather than `drawColor(TRANSPARENT)`, because an `ALPHA_8` canvas
     * has no colour to draw: source-over with a transparent source leaves the
     * old coverage exactly where it was, and the mask would then be the union
     * of every selection ever made.
     */
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
}

/**
 * The selection as the UI sees it: immutable, no mask, no lock.
 *
 * [path] is a copy the render thread will never touch again, so the marching
 * ants can stroke it on every frame of their animation without synchronising
 * with anything.
 */
class SelectionInfo(
    val path: Path?,
    val bounds: Rect?,
) {
    val active: Boolean get() = path != null

    companion object {
        val NONE = SelectionInfo(null, null)
    }
}

/** What a marquee gesture does to the selection that is already there. */
enum class SelectMode {
    /** Throw away what was selected and take this instead. */
    NEW,

    /** Union. */
    ADD,

    /** Take this away from what was selected. */
    SUBTRACT,

    /** Keep only the overlap. */
    INTERSECT,
}

/**
 * Something the user asked to do to the selection, on its way to the render
 * thread.
 *
 * A sealed hierarchy rather than methods on [Selection], for the reason
 * [LayerOp] is one: these are *queued*, so that the order the user did things
 * in survives the crossing. A method that is called has already happened; a
 * value that is enqueued happens in its turn.
 */
sealed interface SelectOp {

    /**
     * A shape from a marquee gesture, combined with what is already selected.
     *
     * **Copies [shape] on the way in.** The UI thread builds the path and the
     * render thread consumes it, and a `Path` the UI went on editing after
     * enqueueing would be a region changing while it is being filled. The same
     * rule `InkSurface` states for `DabBatch`, enforced here by construction
     * rather than by a sentence, because a marquee is built once per gesture
     * and one copy costs nothing.
     */
    class Shape(shape: Path, val mode: SelectMode) : SelectOp {
        val path: Path = Path(shape)
    }

    /** The whole page. */
    object All : SelectOp

    /** Nothing, so the pen may go anywhere. */
    object None : SelectOp

    /** The page minus what is selected. */
    object Invert : SelectOp
}
