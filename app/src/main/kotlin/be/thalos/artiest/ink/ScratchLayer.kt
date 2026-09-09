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

    /**
     * The part of the allocation this stroke actually uses.
     *
     * Separate from the bitmap's own size, and that separation is the fix: the
     * allocation is reused and rounded up, so it is routinely much larger than
     * the stroke on it. Everything that costs — the clear, the composite, the
     * shader's rect — is measured by this and not by the bitmap.
     */
    var usedWidth: Int = 0
        private set
    var usedHeight: Int = 0
        private set

    /** The allocation's size. See [usedWidth] for what is actually painted. */
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
        originX = Math.floor(bounds.left.toDouble()).toInt() - padPx
        originY = Math.floor(bounds.top.toDouble()).toInt() - padPx
        usedWidth = Math.ceil(bounds.right.toDouble()).toInt() + padPx - originX
        usedHeight = Math.ceil(bounds.bottom.toDouble()).toInt() + padPx - originY
        if (usedWidth < 1) usedWidth = 1
        if (usedHeight < 1) usedHeight = 1
        usedWidth = usedWidth.coerceAtMost(maxWidth)
        usedHeight = usedHeight.coerceAtMost(maxHeight)
        ensureCapacity(usedWidth, usedHeight)
        // Only the region this stroke will use, not the whole allocation. The
        // buffer is reused between strokes and can be far larger than the
        // stroke that is about to be drawn; clearing all of it made every
        // stroke pay for the largest stroke of the session.
        clearUsed()
        isOpen = true
    }

    /**
     * Grow to also cover [bounds], keeping what has been drawn.
     *
     * **Unions against the *used* region, not against the bitmap.** Doing it
     * the other way round was the bug that made the pencil unusable: the
     * required extent became `originX + bitmap.width`, so once the allocation
     * was large every stroke demanded at least that much, and any stroke
     * reaching left or up of the origin doubled it again. The buffer ratcheted
     * to the size of the page within a few strokes and stayed there, and from
     * then on every stroke cleared and composited a full-page bitmap. That is
     * why it got slower dab after dab, and why only the pencil suffered — the
     * pen never touches this buffer.
     *
     * Returns false when there is no open stroke, so a caller cannot silently
     * draw into a closed buffer.
     */
    fun ensureCovers(bounds: Bounds, padPx: Int = PAD): Boolean {
        if (!isOpen) return false
        if (bitmap == null) return false
        val l = minOf(originX, Math.floor(bounds.left.toDouble()).toInt() - padPx)
        val t = minOf(originY, Math.floor(bounds.top.toDouble()).toInt() - padPx)
        val r = maxOf(originX + usedWidth, Math.ceil(bounds.right.toDouble()).toInt() + padPx)
        val b = maxOf(originY + usedHeight, Math.ceil(bounds.bottom.toDouble()).toInt() + padPx)
        if (l == originX && t == originY && r == originX + usedWidth && b == originY + usedHeight) {
            return true
        }
        val old = bitmap
        val oldX = originX
        val oldY = originY
        val oldW = usedWidth
        val oldH = usedHeight
        val fitsWhereItIs = l == originX && t == originY &&
            r - l <= (old?.width ?: 0) && b - t <= (old?.height ?: 0)
        originX = l
        originY = t
        // Clamped to the page. A stroke's bounds are the extent it *painted*
        // and are not clipped to the document, so a stroke that runs off the
        // edge can ask for a region larger than the page — which the layer
        // would discard anyway.
        usedWidth = (r - l).coerceIn(1, maxWidth)
        usedHeight = (b - t).coerceIn(1, maxHeight)
        if (fitsWhereItIs) {
            // The origin has not moved and the allocation already covers the
            // new extent, so the pixels already drawn are exactly where they
            // belong. Only the newly exposed strip has to be cleared.
            clearBeyond(oldW, oldH)
            return true
        }
        // A fresh allocation, always, when the origin moves. The ink has to be
        // copied to a new offset, and a bitmap cannot be copied onto itself:
        // clearing it first destroys the source, and not clearing it leaves the
        // old copy behind. Reusing the allocation here erased the stroke so far
        // every time the pen reached up or left.
        // The doubling is computed here, while the old allocation is still in
        // hand. See [ensureCapacity].
        //
        // **Doubling only when the request actually exceeds what is held.** The
        // first version doubled on every reallocation, including the ones
        // caused merely by the origin moving — so a stroke that fitted
        // comfortably still asked for twice the allocation, every commit, and
        // the buffer went 128, 256, ... to 32768 and killed the process with an
        // OutOfMemoryError. Where the request fits, the allocation is kept at
        // the size it already is.
        val capNowW = old?.width ?: 0
        val capNowH = old?.height ?: 0
        val capW = if (usedWidth > capNowW) {
            minOf(maxOf(usedWidth, capNowW * 2), maxWidth)
        } else {
            maxOf(usedWidth, capNowW)
        }
        val capH = if (usedHeight > capNowH) {
            minOf(maxOf(usedHeight, capNowH * 2), maxHeight)
        } else {
            maxOf(usedHeight, capNowH)
        }
        bitmap = null
        canvas = null
        ensureCapacity(capW, capH)
        val c = canvas ?: return false
        c.save()
        c.setMatrix(null)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.restore()
        if (old != null) {
            // Copied back whatever happened, including when the allocation was
            // reused: the origin has moved, so the ink is no longer where it
            // was. The previous version only copied when a *new* bitmap had
            // been made, which silently erased the stroke so far in every other
            // case.
            c.drawBitmap(
                old,
                android.graphics.Rect(0, 0, oldW, oldH),
                android.graphics.Rect(
                    oldX - originX, oldY - originY,
                    oldX - originX + oldW, oldY - originY + oldH,
                ),
                null,
            )
            growths++
        }
        return true
    }

    /**
     * Clear the used region only.
     *
     * **Every clip here is inside a save/restore, and that is not style.**
     * `Canvas.clipRect` intersects; it can only ever shrink the clip. A bare
     * `clipRect` followed by a `clipRect` back to the full bitmap does not
     * widen anything — the canvas stays narrowed for every dab that follows,
     * and the stroke is silently cut off at the region the *first* clear
     * happened to use. `Canvas.setMatrix(null)` resets the matrix and leaves
     * the clip exactly where it was, so nothing downstream rescues it either.
     */
    private fun clearUsed() {
        val c = canvas ?: return
        c.save()
        c.setMatrix(null)
        c.clipRect(0, 0, usedWidth, usedHeight)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.restore()
    }

    /** Clear the strip newly exposed by a grow that kept its origin. See [clearUsed]. */
    private fun clearBeyond(oldW: Int, oldH: Int) {
        val c = canvas ?: return
        if (usedWidth > oldW) {
            c.save()
            c.setMatrix(null)
            c.clipRect(oldW, 0, usedWidth, usedHeight)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.restore()
        }
        if (usedHeight > oldH) {
            c.save()
            c.setMatrix(null)
            c.clipRect(0, oldH, usedWidth, usedHeight)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.restore()
        }
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
            // The used region only. Blitting the whole allocation was the other
            // half of the cost: the source rectangle is what Skia has to sample
            // and, for a bitmap the CPU has just written, upload.
            dst.drawBitmap(
                bmp,
                android.graphics.Rect(0, 0, usedWidth, usedHeight),
                android.graphics.RectF(
                    originX.toFloat(), originY.toFloat(),
                    (originX + usedWidth).toFloat(), (originY + usedHeight).toFloat(),
                ),
                compositePaint,
            )
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
            (originX + usedWidth).toFloat(), (originY + usedHeight).toFloat(),
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

    /**
     * [minW] and [minH] are the smallest acceptable allocation, already
     * including any doubling the caller wants.
     *
     * The doubling is the caller's because [ensureCovers] releases the old
     * bitmap before calling — it has to, since ink cannot be copied onto the
     * bitmap it is being copied from — and a doubling computed from
     * `bitmap?.width` after that release reads zero and silently degrades to
     * linear growth. That is not hypothetical: it turned 5 reallocations into
     * 15 the first time.
     */
    private fun ensureCapacity(minW: Int, minH: Int) {
        val want = maxOf(minW, 1)
        val hant = maxOf(minH, 1)
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
        // Rounded up to a grain, then capped at the page.
        //
        // The cap used to read `coerceAtMost(maxOf(want, maxWidth))`, so that a
        // request larger than the page would not be clipped. That defeated the
        // cap completely — the ceiling became whatever was asked for — and it
        // is why a runaway doubling reached 32768 px instead of stopping at the
        // page. Requests are clamped to the page by the callers instead, which
        // is correct on its own terms: ink outside the document has nothing to
        // composite onto.
        val gw = (((want + GRAIN - 1) / GRAIN) * GRAIN).coerceAtMost(maxWidth)
        val gh = (((hant + GRAIN - 1) / GRAIN) * GRAIN).coerceAtMost(maxHeight)
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
