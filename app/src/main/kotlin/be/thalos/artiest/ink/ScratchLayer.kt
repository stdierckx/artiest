package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import be.thalos.artiest.engine.ink.Bounds

/**
 * The stroke in progress, on its own surface, so overlapping translucent dabs
 * stop compositing against each other.
 *
 * **What this fixes, in one sentence:** at 1/8 spacing a stroke lays eight dabs
 * per nib diameter, and if each composites separately onto the layer then a
 * 30%-alpha brush reaches 94% after five overlaps — a slow curve turns into a
 * string of dark beads, and every place the stroke crosses itself goes black.
 * Accumulating the dabs here and compositing **once** at pen-up makes the
 * stroke's own overlaps free, which is what `Brush`'s tripwire has been waiting
 * for since Phase 1.
 *
 * **Stroke-bounds-sized, not document-sized, and that is the whole affordability
 * argument.** A 3300x2160 page is 27 MiB at `ARGB_8888` and 57 MiB at
 * `RGBA_F16`; a typical stroke's bounds is a few hundred pixels a side, which is
 * under a megabyte either way. The buffer grows to fit the stroke as it goes
 * and is reused between strokes, so the steady state is one allocation per size
 * class rather than one per stroke.
 *
 * **Growth copies rather than reallocating from scratch**, because the dabs
 * already laid are the stroke so far and cannot be regenerated: `Stroke` is
 * built incrementally and the resampler has already consumed its input.
 *
 * Not thread-safe. Owned by the render thread, like everything else that
 * touches pixels here.
 */
class ScratchLayer(
    /**
     * `ARGB_8888` or `RGBA_F16`.
     *
     * The plan leaves this to be settled by measurement, and it is a real
     * question rather than a preference: eight bits of alpha quantise to 1/255,
     * and a pencil laying dabs at 3% flow spends its first eight dabs inside
     * one quantisation step, so the light end of a graphite build-up stair-steps
     * instead of growing. F16 has no such floor. What it costs is double the
     * bytes and, on some GPUs, a slower blit — hence a field rather than a
     * constant, so both can be run on the device.
     */
    var config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    /**
     * A ceiling on either axis, in pixels. The document's own extent.
     *
     * **Not a tidiness measure — the device produced an out-of-memory crash
     * without it.** A stroke that sweeps the page grows the buffer by doubling,
     * and at `RGBA_F16` a 2048x2048 buffer is 33.5 MiB, so the next doubling
     * asks for 134 MiB. A stroke cannot be larger than the document it is drawn
     * on, so the document's extent is both the correct cap and one that can
     * never clip a real stroke.
     */
    var maxWidth: Int = Int.MAX_VALUE,
    var maxHeight: Int = Int.MAX_VALUE,
) {

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    /** Document-space position of the bitmap's top-left. */
    var originX: Int = 0
        private set
    var originY: Int = 0
        private set

    val width: Int get() = bitmap?.width ?: 0
    val height: Int get() = bitmap?.height ?: 0

    /** Whether a stroke is accumulating. */
    var isOpen: Boolean = false
        private set

    /** Allocations made since construction, for the instruments. */
    var allocations: Long = 0L
        private set

    /** Growths that copied an existing buffer, for the instruments. */
    var growths: Long = 0L
        private set

    private val clearPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val compositePaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    /**
     * Open a stroke covering [bounds], reusing the buffer when it already fits.
     *
     * The bitmap is cleared rather than reallocated when it is reused, which is
     * the case that matters: a page of hatching is hundreds of similar strokes,
     * and reallocating each would be hundreds of multi-megabyte allocations on
     * the render thread.
     */
    fun begin(bounds: Bounds, padPx: Int = PAD) {
        val l = Math.floor(bounds.left.toDouble()).toInt() - padPx
        val t = Math.floor(bounds.top.toDouble()).toInt() - padPx
        val r = Math.ceil(bounds.right.toDouble()).toInt() + padPx
        val b = Math.ceil(bounds.bottom.toDouble()).toInt() + padPx
        originX = l
        originY = t
        ensureSize(r - l, b - t, copy = false)
        canvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        isOpen = true
    }

    /**
     * Grow to also cover [bounds], keeping what has been drawn.
     *
     * Returns false when there is no open stroke, so a caller cannot silently
     * draw into a closed buffer.
     */
    fun ensureCovers(bounds: Bounds, padPx: Int = PAD): Boolean {
        if (!isOpen) return false
        val bmp = bitmap ?: return false
        val l = minOf(originX, Math.floor(bounds.left.toDouble()).toInt() - padPx)
        val t = minOf(originY, Math.floor(bounds.top.toDouble()).toInt() - padPx)
        val r = maxOf(originX + bmp.width, Math.ceil(bounds.right.toDouble()).toInt() + padPx)
        val b = maxOf(originY + bmp.height, Math.ceil(bounds.bottom.toDouble()).toInt() + padPx)
        if (l == originX && t == originY && r == originX + bmp.width && b == originY + bmp.height) {
            return true
        }
        val oldX = originX
        val oldY = originY
        val old = bmp
        originX = l
        originY = t
        ensureSize(r - l, b - t, copy = false)
        val c = canvas ?: return false
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (old !== bitmap) {
            c.drawBitmap(old, (oldX - originX).toFloat(), (oldY - originY).toFloat(), null)
            growths++
        }
        return true
    }

    /**
     * The canvas to lay dabs on, translated so callers draw in **document**
     * coordinates and never have to know where the buffer sits.
     *
     * Returns null when no stroke is open, rather than lazily opening one: a
     * dab arriving outside a stroke is a bug in the caller, and quietly
     * accepting it is how a stroke ends up composited twice.
     */
    fun canvasInDocSpace(): Canvas? {
        val c = canvas ?: return null
        if (!isOpen) return null
        c.setMatrix(null)
        c.translate(-originX.toFloat(), -originY.toFloat())
        return c
    }

    /**
     * Composite the accumulated stroke onto [dst] at [alpha], and close it.
     *
     * [dst] must be in document space — the layer's own canvas is, because one
     * layer pixel is one document pixel.
     */
    fun compositeInto(dst: Canvas, alpha: Float, grain: Shader? = null, erase: Boolean = false) {
        drawOnto(dst, alpha, grain, erase)
        isOpen = false
    }

    /**
     * Paint the buffer onto [dst] without closing it. The wet pass, which has
     * to show the stroke so far on every frame and must not consume it.
     */
    fun drawOnto(dst: Canvas, alpha: Float, grain: Shader? = null, erase: Boolean = false) {
        val bmp = bitmap ?: return
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        // DST_OUT subtracts the source's alpha from the destination's, which is
        // what erasing is. It is set on the shared paint and cleared again
        // below, because a leftover xfermode would turn the next ordinary
        // stroke into an eraser -- a bug that looks like the undo being broken.
        compositePaint.xfermode = if (erase) eraseMode else null
        if (grain == null) {
            compositePaint.shader = null
            compositePaint.alpha = a
            dst.drawBitmap(bmp, originX.toFloat(), originY.toFloat(), compositePaint)
            compositePaint.xfermode = null
            return
        }
        // The stroke and the grain, multiplied. DST_IN keeps the stroke's
        // colour and multiplies its alpha by the grain's, which reads as
        // "keep the ink where the paper caught it".
        if (selfShader == null || selfShaderFor !== bmp) {
            selfShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            selfShaderFor = bmp
        }
        selfMatrix.reset()
        selfMatrix.setTranslate(originX.toFloat(), originY.toFloat())
        selfShader!!.setLocalMatrix(selfMatrix)
        compositePaint.shader = ComposeShader(selfShader!!, grain, PorterDuff.Mode.DST_IN)
        compositePaint.alpha = a
        dst.drawRect(
            originX.toFloat(), originY.toFloat(),
            (originX + bmp.width).toFloat(), (originY + bmp.height).toFloat(),
            compositePaint,
        )
        compositePaint.shader = null
        compositePaint.xfermode = null
    }

    private val eraseMode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private var selfShader: BitmapShader? = null
    private var selfShaderFor: Bitmap? = null
    private val selfMatrix = Matrix()

    /** Abandon without compositing. Pen-up on a cancelled stroke. */
    fun abandon() {
        isOpen = false
    }

    /** Release the pixels. Called when the view is torn down. */
    fun release() {
        isOpen = false
        canvas = null
        bitmap = null
    }

    /** The buffer's own bitmap, for the composite blit and for tests. */
    fun peekBitmap(): Bitmap? = bitmap

    private fun ensureSize(w: Int, h: Int, copy: Boolean) {
        val want = maxOf(w, 1)
        val hant = maxOf(h, 1)
        val bmp = bitmap
        if (bmp != null && bmp.width >= want && bmp.height >= hant && bmp.config == config) {
            return
        }
        // Round up to a grain *and* at least double, so growth is logarithmic
        // in the stroke's extent rather than linear.
        //
        // The grain alone is not enough, and the device said so: a zigzag
        // across the page reallocated 25 times in one stroke, because each
        // sweep pushed the bounds out by more than a grain and every push
        // copied the whole buffer. Doubling turns that into about six. The
        // wasted pixels are the usual doubling overhead and are transparent,
        // so they cost memory and nothing else.
        val bmpW = bmp?.width ?: 0
        val bmpH = bmp?.height ?: 0
        // Doubled, then capped: never smaller than what was asked for, never
        // larger than the page. The order matters -- capping before taking the
        // maximum would let the double win and defeat the cap.
        val gw = maxOf(((want + GRAIN - 1) / GRAIN) * GRAIN, bmpW * 2)
            .coerceAtMost(maxOf(want, maxWidth))
        val gh = maxOf(((hant + GRAIN - 1) / GRAIN) * GRAIN, bmpH * 2)
            .coerceAtMost(maxOf(hant, maxHeight))
        val fresh = Bitmap.createBitmap(gw, gh, config)
        allocations++
        bitmap = fresh
        canvas = Canvas(fresh)
    }

    companion object {
        /**
         * Document pixels of margin round the stroke bounds. The dab's own
         * antialiased rim lands outside the geometric bounds, and a scratch one
         * pixel short clips every stroke's edge.
         */
        const val PAD: Int = 2

        /** Allocation granularity, in pixels, on both axes. */
        const val GRAIN: Int = 128
    }
}
