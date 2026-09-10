package be.thalos.artiest.doc

import android.graphics.Canvas
import android.graphics.Paint

/**
 * The paper and every visible sheet on it, bottom to top. The one place that
 * knows what a drawing looks like.
 *
 * ## Why this is a class and not two loops
 *
 * It was two loops. `InkSurfaceView.compositeStack` drew the stack for the
 * screen and `PngExporter` drew it again for the file, and they already
 * disagreed about something that did not matter yet: **the screen paints the
 * paper first and the export slides it underneath at the end with
 * `DST_OVER`.** For sheets composited with source-over those are the same
 * image, which is why the difference survived being written down twice. They
 * stop being the same image the moment a sheet blends, because multiplying
 * against transparency is not multiplying against paper — and layer blend
 * modes are the next thing this phase adds.
 *
 * A defect of that shape is not found by the person who writes it. It is found
 * by a user, months later, in a file they have already sent somewhere. So the
 * loop is extracted first, with a test that composes a document both ways and
 * asserts the pixels are identical, and the blend modes are added afterwards to
 * one place.
 *
 * The export pays a full-canvas `drawColor` it used to avoid — measured at
 * 12.9–13.8 ms in `PngExporter`'s header, off-lock, out of an export that takes
 * 450 ms. That is the price of the two agreeing and it is worth it. The 24-bit
 * PNG saving is untouched: an opaque paper still leaves every pixel at alpha
 * 255 whichever order it is painted in.
 *
 * ## Threading
 *
 * **One instance per user, and never a singleton.** This object holds a `Paint`
 * whose alpha is rewritten per sheet. The render thread composes every frame
 * and `PngExporter` composes on a coroutine; sharing one `Paint` between them
 * is two threads writing one field, and the visible result would be a sheet
 * exported at another sheet's opacity. `InkSurfaceView` owns one, an export
 * builds its own and drops it.
 *
 * Sheet pixels are read one at a time, under one lock, never two at once — see
 * [Layer], whose lock is documented as a leaf.
 */
class StackCompositor {

    /**
     * Ink that has not landed in a sheet yet: the stroke under the pen.
     *
     * An interface rather than a `ScratchLayer` parameter, because the scratch
     * buffer belongs to the ink package and this class belongs to the document.
     * It also keeps the export honest: it passes null, and there is then no way
     * for a wet stroke to reach a file.
     */
    interface Wet {

        /** Where in the stack it goes, counting from the bottom. */
        val position: Int

        /** Whether there is anything to draw. */
        val isOpen: Boolean

        /**
         * Whether the ink subtracts rather than adds.
         *
         * Not cosmetic: `DST_OUT` applied straight to the frame cuts through
         * the paper and every sheet already painted, so an erasing stroke needs
         * an offscreen layer to be subtracted *from*, and the stroke would
         * otherwise read as a window onto the desk.
         */
        val erases: Boolean

        /** Paint it onto [canvas], which is in document space. */
        fun draw(canvas: Canvas)
    }

    /**
     * Paper, then sheets, then done. [canvas] is in **document space** and the
     * caller has already clipped it to whatever it wants repainted.
     *
     * [left], [top], [right] and [bottom] bound the offscreen layer the active
     * sheet needs while it is translucent or being erased into. They are the
     * dirty rectangle on the front-buffered path and the whole page on the dry
     * one, because a `saveLayer` costs its own area and handing it the page for
     * one erased dab would allocate 7.1 Mpx a batch.
     *
     * Returns false if any visible sheet could not be read, which means the
     * document was closed underneath the caller. The frame draws what it got —
     * paper with less ink on it is a better outcome than a torn read — and the
     * export turns it into a failure, because a file missing a layer is not a
     * file anybody wants saved silently.
     */
    fun compose(
        canvas: Canvas,
        stack: LayerStack,
        paperColor: Int,
        widthPx: Int,
        heightPx: Int,
        wet: Wet?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        // The paper is the bottom of the stack and not a backdrop slid under it
        // afterwards. See the class header: it is the same image today and a
        // different one as soon as anything blends.
        paperPaint.color = paperColor
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paperPaint)

        val wetAt = if (wet != null && wet.isOpen) wet.position else NO_WET
        var read = true
        var painted = 0
        for (i in 0 until stack.size) {
            val entry = stack.entryAt(i)
            if (!entry.visible) continue
            val alpha = (entry.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            // A sheet at zero opacity is not merely invisible, it is a full-page
            // blit that cannot change a pixel.
            if (alpha <= 0) continue
            painted++
            if (i != wetAt) {
                sheetPaint.alpha = alpha
                if (!entry.layer.read { canvas.drawBitmap(it, 0f, 0f, sheetPaint) }) read = false
                continue
            }
            // An offscreen layer only when it buys something: it is what scopes
            // the erase, and it is what makes a translucent sheet fade the
            // stroke *with* the ink under it rather than over it. An opaque
            // sheet taking ink stays on the cheap path.
            val grouped = wet!!.erases || alpha < 255
            val save = if (grouped) canvas.saveLayerAlpha(left, top, right, bottom, alpha) else -1
            sheetPaint.alpha = if (grouped) 255 else alpha
            if (!entry.layer.read { canvas.drawBitmap(it, 0f, 0f, sheetPaint) }) read = false
            wet.draw(canvas)
            if (grouped) canvas.restoreToCount(save)
        }
        sheetsPainted = painted
        return read
    }

    /** Visible sheets in the last [compose]. Diagnostic only. */
    var sheetsPainted: Int = 0
        private set

    /**
     * The stack's blit paint, and the reason there is exactly one of it.
     *
     * Its alpha is rewritten per sheet, so a `Paint` shared with anything else
     * — the paper, another thread's compose — is the classic way to make one
     * drawing fade another. Filtered because the screen draws it under a
     * transform; at the export's 1:1 that costs nothing and, more to the point,
     * it is the same paint, which is the whole purpose of this class.
     */
    private val sheetPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    private val paperPaint = Paint()

    private companion object {
        /** No sheet has wet ink on it. Not a position, so it matches nothing. */
        const val NO_WET = -1
    }
}
