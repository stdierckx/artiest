package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * Pixels lifted off a sheet and floating over it, with a matrix the user is
 * still editing.
 *
 * ## The lift does not touch the sheet
 *
 * The textbook floating selection cuts the pixels out, floats them, and writes
 * them back: two undo steps for one move, and a cancel that has to be a third
 * operation putting things back. This one leaves the source exactly where it
 * is and merely *hides* it while the float is live — the compositor punches the
 * hole at draw time — which buys three things:
 *
 * - **Cancel is free.** Nothing was written, so nothing has to be put back.
 * - **A move is one undo step.** At [drop] a single [PixelPatch] covers the
 *   union of where the pixels were and where they went. One rectangle, one
 *   press.
 * - **Quality does not decay.** [pixels] is always the original; the matrix is
 *   applied once, at drop. Twenty nudges and three rotations resample once.
 *
 * ## The matrix crosses threads and the pixels do not
 *
 * The UI thread edits the transform — a drag, a rotate handle — and the render
 * thread draws through it. `Matrix` is mutable native state, so what crosses is
 * a **fresh instance per update**, published through a volatile field and never
 * written again. At eight updates a second (see the 120 ms preview floor) that
 * is eight small allocations, against the alternative of a lock on the frame
 * path.
 *
 * Everything else here — the bitmaps, the lift, the drop — is the render
 * thread's, reached only from inside `CommitQueue.drain`.
 *
 * ## What it costs
 *
 * One bitmap the size of the selection's bounding box, so a whole-page
 * transform is 27.19 MiB and a small selection is small. Plus, at drop, one
 * `PixelPatch` over the union of the two rectangles. Both are released as soon
 * as the float is dropped or cancelled.
 */
class FloatingPixels private constructor(
    /** The sheet the pixels came from, by id — never an index. See [PixelPatch]. */
    val sourceLayerId: Int,

    /** Where they came from, in document coordinates. */
    val sourceBounds: Rect,

    /**
     * The lifted pixels, already masked by the stencil that selected them.
     *
     * A copy that owes the layer nothing: it is taken inside `Layer.read` and
     * carried out, which is the contract that method's KDoc states.
     */
    val pixels: Bitmap,

    /**
     * The stencil that lifted them, or null when the whole sheet was taken.
     *
     * Kept because the *hole* needs it: the source region has to be hidden with
     * the same soft edge the pixels were cut with, or a moved selection leaves
     * a hard-edged ghost of itself behind.
     */
    val mask: Bitmap?,
) {

    /**
     * What the user has done to the pixels so far, **in document space**.
     *
     * Identity means "where they came from". That is the whole reason this is
     * not the matrix handed to `drawBitmap`: a bitmap's origin is its own top
     * left, so the matrix that actually draws the float has to carry
     * [sourceBounds] as well, and if that were the caller's job then an
     * untouched float would land in the corner of the page. Found by a test
     * that rotated a bar about the middle of the selection and got it back
     * somewhere else entirely.
     *
     * So the UI works in the coordinates it can reason about — rotate about the
     * centre of the selection, drag by so many document pixels — and
     * [placement] composes the rest.
     *
     * **Replaced, never mutated.** A caller that edits this instance instead of
     * setting a new one is writing native state under a thread that is
     * concatenating it.
     */
    @Volatile
    var matrix: Matrix = Matrix()

    /** True until [release]. */
    var isOpen: Boolean = true
        private set

    /**
     * Where the pixels are now, in document coordinates.
     *
     * Rounded outward: a rotated rectangle's corners land between pixels, and a
     * bounding box one pixel short is a row of the moved selection left behind
     * on the page.
     */
    fun transformedBounds(into: Rect) {
        val box = RectF(sourceBounds)
        matrix.mapRect(box)
        into.set(
            Math.floor(box.left.toDouble()).toInt(),
            Math.floor(box.top.toDouble()).toInt(),
            Math.ceil(box.right.toDouble()).toInt(),
            Math.ceil(box.bottom.toDouble()).toInt(),
        )
    }

    /**
     * Draw the float where it is now. **Render thread**, document space.
     *
     * Filtered, because the matrix is a rotation as often as not and point
     * sampling a rotated bitmap is a staircase. See [dropInto] for the case
     * filtering alone cannot handle.
     */
    fun drawInto(canvas: Canvas) {
        if (!isOpen) return
        canvas.drawBitmap(pixels, placement(matrix, 1f, scratch), filtered)
    }

    /**
     * Bitmap coordinates to document coordinates: put the pixels back where
     * they came from, then apply what the user has done.
     *
     * [sourceScale] is how much larger the source *was* than the bitmap being
     * drawn, which is 1 except on the halved path in [dropInto].
     *
     * Written into [into] rather than returning a fresh `Matrix`, because this
     * runs on the render thread on every frame the float is on screen.
     */
    private fun placement(user: Matrix, sourceScale: Float, into: Matrix): Matrix {
        into.setScale(sourceScale, sourceScale)
        into.postTranslate(sourceBounds.left.toFloat(), sourceBounds.top.toFloat())
        into.postConcat(user)
        return into
    }

    /** See [placement]. Render thread only, so one instance is enough. */
    private val scratch = Matrix()

    /**
     * Hide the source region on [canvas], which must already hold the sheet
     * these pixels came from. **Render thread**, document space.
     *
     * `DST_OUT` through the stencil, so the hole has the same soft edge the
     * lifted pixels do. Without the mask — a whole-sheet lift — the rectangle
     * is cleared outright.
     *
     * The caller has to have opened a `saveLayer` first: `DST_OUT` straight onto
     * the frame would cut through the paper and every sheet already painted,
     * which is the same trap the eraser's wet pass documents.
     */
    fun punchInto(canvas: Canvas) {
        if (!isOpen) return
        val stencil = mask
        if (stencil == null) {
            canvas.drawRect(RectF(sourceBounds), clearPaint)
            return
        }
        canvas.drawBitmap(stencil, 0f, 0f, subtractPaint)
    }

    /**
     * Write the float into [layer] and close it. **Render thread.**
     *
     * Two steps under one lock: take the source region away, then lay the
     * pixels down where the matrix says. The caller has already snapshotted the
     * union of the two rectangles, so both are one undo step.
     *
     * **Scaled down by more than half, the pixels are halved first.** A
     * bilinear filter samples a 2x2 neighbourhood however far apart the taps
     * are, so a pencil line two document pixels wide falls between them about
     * ninety-two times in a hundred and simply disappears. That is not a
     * prediction: `LayerStack.buildThumbnail` shipped with exactly this bug and
     * the tablet found it — one sheet's thumbnail showing a line, another's
     * blank, with both visibly drawn on. Halving is the fix there and it is the
     * fix here.
     */
    fun dropInto(layer: Layer): Boolean {
        if (!isOpen) return false
        var reduced: Bitmap? = null
        var draw = pixels
        var sourceScale = 1f
        val scale = averageScale(matrix)
        if (scale < HALVE_BELOW) {
            var steps = 0
            var current = pixels
            var remaining = scale
            while (remaining < HALVE_BELOW && current.width > 1 && current.height > 1 &&
                steps < MAX_HALVINGS
            ) {
                val next = halve(current)
                if (current !== pixels) current.recycle()
                current = next
                remaining *= 2f
                steps++
            }
            if (steps > 0) {
                reduced = current
                draw = current
                // The bitmap is 2^steps smaller than the region it came from,
                // so the placement has to grow it back by exactly that before
                // the user's transform is applied.
                sourceScale = (1 shl steps).toFloat()
            }
        }
        val place = placement(matrix, sourceScale, Matrix())
        val ok = layer.write { canvas ->
            punchInto(canvas)
            canvas.drawBitmap(draw, place, filtered)
        }
        reduced?.recycle()
        return ok
    }

    /** Release the bitmaps. Idempotent. */
    fun release() {
        if (!isOpen) return
        isOpen = false
        if (!pixels.isRecycled) pixels.recycle()
        // The mask is `Selection`'s and is not recycled here: the selection
        // outlives the float, and freeing a bitmap somebody else still owns is
        // the kind of crash that arrives a hundred frames later.
    }

    /** Bytes held, for the instruments. */
    val bytes: Long get() = if (isOpen) pixels.allocationByteCount.toLong() else 0L

    companion object {

        /**
         * Below this the bilinear filter starts missing thin lines. Half is not
         * a tuned number: it is the point at which the 2x2 neighbourhood stops
         * covering the pixels being merged.
         */
        private const val HALVE_BELOW = 0.5f

        /**
         * Eight halvings is a 256:1 reduction, which is past what the zoom
         * range can ask for. A bound rather than a `while (true)`: a matrix with
         * a zero or a NaN scale would otherwise loop until the allocator gave
         * up.
         */
        private const val MAX_HALVINGS = 8

        private val filtered = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }

        private val subtractPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            isAntiAlias = false
            isFilterBitmap = false
        }

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = false
        }

        /** The geometric mean of the two axis scales, which is what an area shrinks by. */
        private fun averageScale(matrix: Matrix): Float {
            val values = FloatArray(9)
            matrix.getValues(values)
            val sx = Math.hypot(values[0].toDouble(), values[3].toDouble()).toFloat()
            val sy = Math.hypot(values[1].toDouble(), values[4].toDouble()).toFloat()
            val mean = Math.sqrt((sx * sy).toDouble()).toFloat()
            return if (mean.isFinite() && mean > 0f) mean else 1f
        }

        private fun halve(src: Bitmap): Bitmap {
            val w = (src.width / 2).coerceAtLeast(1)
            val h = (src.height / 2).coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                src,
                Rect(0, 0, src.width, src.height),
                Rect(0, 0, w, h),
                filtered,
            )
            return out
        }

        /**
         * Cut [bounds] out of [layer] and keep only what [mask] covers.
         *
         * The mask goes on through a `BitmapShader`, for the reason
         * `ScratchLayer.maskBy` states at length: Skia draws an `ALPHA_8` bitmap
         * as a *mask*, and a mask blit never visits the pixels the mask misses,
         * so the obvious `drawBitmap(mask, DST_IN)` leaves everything outside
         * the selection exactly where it was.
         */
        private fun cut(layer: Layer, bounds: Rect, mask: Bitmap?): Bitmap? {
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            var copy: Bitmap? = null
            val ok = layer.read {
                copy = Bitmap.createBitmap(it, bounds.left, bounds.top, bounds.width(), bounds.height())
            }
            val out = if (ok) copy else null
            if (out == null || mask == null) return out
            val canvas = Canvas(out)
            val shader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(
                Matrix().apply { setTranslate(-bounds.left.toFloat(), -bounds.top.toFloat()) },
            )
            canvas.drawRect(
                0f, 0f, out.width.toFloat(), out.height.toFloat(),
                Paint().apply {
                    this.shader = shader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    isAntiAlias = false
                    isFilterBitmap = false
                },
            )
            return out
        }

        /**
         * Lift what the stencil covers off [entry]'s sheet. **Render thread.**
         *
         * Null when there is nothing to lift — no selection and no sheet, or a
         * region with no area. The sheet is **not** modified: see the class
         * header.
         */
        fun lift(entry: LayerStack.Entry, selection: Selection): FloatingPixels? {
            if (!selection.active) return null
            val bounds = Rect(selection.bounds)
            val mask = selection.maskBitmap() ?: return null
            val pixels = cut(entry.layer, bounds, mask) ?: return null
            return FloatingPixels(entry.id, bounds, pixels, mask)
        }

        /**
         * Lift the whole sheet. **Render thread.**
         *
         * The layer transform, and it is the same object with no stencil —
         * which is the reason the selection transform was built first. A second
         * mechanism for "rotate this layer" would have had to be retired the
         * moment this one existed.
         */
        fun liftWhole(entry: LayerStack.Entry, widthPx: Int, heightPx: Int): FloatingPixels? {
            val bounds = Rect(0, 0, widthPx, heightPx)
            val pixels = cut(entry.layer, bounds, null) ?: return null
            return FloatingPixels(entry.id, bounds, pixels, null)
        }
    }
}

/** Something the user asked to do to the floating pixels. See [CommitQueue.Commit.Float]. */
sealed interface FloatOp {

    /** Lift the selection off the active sheet. Refused when nothing is selected. */
    object LiftSelection : FloatOp

    /** Lift the whole active sheet. */
    object LiftLayer : FloatOp

    /**
     * Where the pixels are now.
     *
     * Carries a **copy**, for the reason `SelectOp.Shape` does: the UI thread
     * goes on editing its own instance and the render thread must not see that.
     */
    class Move(source: Matrix) : FloatOp {
        val matrix: Matrix = Matrix(source)
    }

    /** Write the pixels down where they are and close the float. */
    object Drop : FloatOp

    /** Throw the float away. The source was never touched, so nothing is undone. */
    object Cancel : FloatOp
}
