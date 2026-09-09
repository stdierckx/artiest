package be.thalos.artiest.doc

import android.graphics.Bitmap
import be.thalos.artiest.engine.ink.Bounds

/**
 * What a rectangle of the layer looked like before something was drawn into it.
 *
 * The one [UndoStep] that knows about pixels. Capture is a copy out of the
 * layer, restore is a copy back with `Mode.SRC`; between those two the bitmap
 * belongs to this object and to the [UndoHistory] holding it, and
 * [recycle] is called exactly once by the history.
 *
 * **It remembers which sheet it came from, by id.** With a stack of layers a
 * patch is no longer "the document's pixels at this rectangle" but "layer 3's
 * pixels at this rectangle", and the difference is the whole of undo's
 * correctness: draw on one sheet, switch to another, draw again, and two
 * presses of Undo have to land on two different bitmaps. An index would not do
 * it — sheets are reordered — which is why [LayerStack.Entry.id] exists and is
 * never reused.
 *
 * A patch whose sheet has since been deleted restores nothing and reports
 * success anyway. That is deliberate: the alternative is an undo press that
 * throws, and there is nothing for the user to do about a layer that is gone.
 */
class PixelPatch private constructor(
    private val layerId: Int,
    private val x: Int,
    private val y: Int,
    private val pixels: Bitmap,
) : UndoStep {

    override val bytes: Long get() = pixels.allocationByteCount.toLong()

    override fun recycle() {
        if (!pixels.isRecycled) pixels.recycle()
    }

    /** Put these pixels back where they came from. Render thread. */
    fun restoreInto(layer: Layer): Boolean = layer.restoreRegion(x, y, pixels)

    /**
     * Put these pixels back on the sheet they came from. Render thread.
     *
     * True when the sheet is still there, whether or not the write landed;
     * false only when it is gone. Nothing branches on it except diagnostics —
     * see the class header for why a vanished sheet is not an error.
     */
    fun restoreInto(stack: LayerStack): Boolean {
        val entry = stack.byId(layerId) ?: return false
        return entry.layer.restoreRegion(x, y, pixels)
    }

    /** Take a fresh copy of the same rectangle, for the opposite direction. */
    fun recapture(layer: Layer): PixelPatch? =
        captureRect(layerId, layer, x, y, pixels.width, pixels.height)

    /** [recapture], against whichever sheet this patch belongs to. */
    fun recapture(stack: LayerStack): PixelPatch? {
        val entry = stack.byId(layerId) ?: return null
        return recapture(entry.layer)
    }

    override fun toString(): String =
        "PixelPatch(${pixels.width}x${pixels.height} at $x,$y on layer $layerId)"

    companion object {

        /**
         * One pixel of margin around the region a stroke reports.
         *
         * `MutableBounds.add` already takes each dab's painted radius, so the
         * bounds are the geometric extent rather than a centreline — that trap
         * is documented there and avoided. What the geometry does not include
         * is antialiasing, which can tint the pixel just outside the exact
         * edge. A patch one pixel short leaves a faint outline of every undone
         * stroke, which is the kind of defect that is invisible on the first
         * stroke and obvious after fifty. A row and a column is the cheapest
         * possible insurance against it.
         */
        private const val PAD = 1f

        /**
         * Copy out the region [bounds] covers, clipped to the document.
         *
         * Null when there is nothing to snapshot — a stroke entirely off the
         * page — which the history should not be asked to hold. Render thread:
         * `Layer.copyRegion` refuses the main thread.
         */
        fun capture(
            layer: Layer,
            bounds: Bounds,
            docWidth: Int,
            docHeight: Int,
        ): PixelPatch? = capture(0, layer, bounds, docWidth, docHeight)

        /** [capture], recording which sheet it came from. */
        fun capture(
            layerId: Int,
            layer: Layer,
            bounds: Bounds,
            docWidth: Int,
            docHeight: Int,
        ): PixelPatch? {
            val r = IntArray(4)
            if (!bounds.toPixelRect(r, docWidth, docHeight, PAD)) return null
            return captureRect(layerId, layer, r[0], r[1], r[2] - r[0], r[3] - r[1])
        }

        /** The whole page, for an edit that touches all of it. Render thread. */
        fun captureAll(layer: Layer, docWidth: Int, docHeight: Int): PixelPatch? =
            captureAll(0, layer, docWidth, docHeight)

        /** [captureAll], recording which sheet it came from. */
        fun captureAll(
            layerId: Int,
            layer: Layer,
            docWidth: Int,
            docHeight: Int,
        ): PixelPatch? = captureRect(layerId, layer, 0, 0, docWidth, docHeight)

        /**
         * The only place in the app that copies pixels out of a layer.
         *
         * Written against [Layer.read] rather than against a `copyRegion`
         * method on `Layer`, because a method there returning a `Bitmap` would
         * put `Bitmap` on that class's surface — see the note where such a
         * method would have gone. `createBitmap(src, ...)` copies, so what is
         * carried out of the block owes the layer nothing, which is exactly the
         * contract `read`'s KDoc states.
         *
         * Render thread: `Layer.read` refuses the main thread.
         */
        private fun captureRect(
            layerId: Int,
            layer: Layer,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ): PixelPatch? {
            if (w <= 0 || h <= 0) return null
            var copy: Bitmap? = null
            layer.read { copy = Bitmap.createBitmap(it, x, y, w, h) }
            return copy?.let { PixelPatch(layerId, x, y, it) }
        }
    }
}
