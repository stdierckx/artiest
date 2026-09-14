package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * The colour of one pixel of the drawing, as the eye sees it.
 *
 * Lr1. There was no colour picker in this program at all, which is a hole in
 * the drawing program before it is a hole in the learning one — a beginner
 * matching a photograph in the reference pane is the case that made it obvious,
 * but every artist who has ever wanted the grey they mixed four strokes ago
 * wanted this.
 *
 * ## It composes rather than reads
 *
 * The obvious implementation reads the active sheet's bitmap and hands back
 * what is in it, and it is wrong in the way that is only found with a pen in
 * hand: what the eye picked was **what it could see**, which is the whole stack
 * over the paper at the opacities and blend modes the sheets are set to. A
 * picker that answered with the active sheet's own pixel would give
 * transparent-black on a blank layer over a finished painting, and the user
 * would have no way to tell that from a bug.
 *
 * So this composes, through [StackCompositor] — *the one place that knows what
 * a drawing looks like* — into a one-pixel bitmap, and reads that. Every rule
 * the screen obeys is then obeyed here for free, including the ones that have
 * not been written yet.
 *
 * The cost is one `drawColor` and one clipped blit per visible sheet, into a
 * 1×1 canvas. Skia clips the blits; the page is never touched.
 *
 * ## What it deliberately does not see
 *
 * **The wet stroke.** `compose` takes a `Wet` and this passes null, because the
 * pick happens while the pen is on the glass and the pen is the picker: there
 * is no ink under it to see. That is the same null the export passes, and for a
 * related reason — a colour taken from a half-drawn stroke is a colour nobody
 * asked for.
 *
 * **Pixels in the air are seen**, because they are on the screen. A selection
 * being dragged is part of the picture until it is dropped.
 *
 * ## Threading
 *
 * One instance per user, like the compositor it holds: it owns a `Bitmap`, a
 * `Canvas` and a `StackCompositor` whose `Paint` is rewritten per sheet.
 * `InkSurfaceView` owns one and probes on the UI thread, which takes the
 * sheets' locks briefly — they are leaves, and held only for a blit.
 */
class ColourProbe {

    private val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(pixel)
    private val compositor = StackCompositor()

    /**
     * The colour at document pixel [xDoc], [yDoc], or null when there is none.
     *
     * Null means one of two things and the caller treats them the same: the
     * point is off the page, or a sheet could not be read because the document
     * was closed underneath. Neither is a colour, and neither should quietly
     * become black.
     *
     * [activeOnly] answers with the active sheet's own pixel instead of the
     * composite — *this layer only*, for picking out of a reference photograph
     * that has been drawn over. A fully transparent pixel is **not** a colour
     * and comes back null, because the alternative is handing the user
     * transparent black and calling it a pick.
     */
    fun at(document: Document, xDoc: Int, yDoc: Int, activeOnly: Boolean = false): Int? {
        if (xDoc < 0 || yDoc < 0) return null
        if (xDoc >= document.widthPx || yDoc >= document.heightPx) return null
        if (activeOnly) return activePixel(document, xDoc, yDoc)

        val x = xDoc.toFloat()
        val y = yDoc.toFloat()
        canvas.save()
        // The compositor works in document space and starts by painting the
        // whole page. The translate is what makes the one pixel this canvas
        // has be *that* pixel of the page.
        canvas.translate(-x, -y)
        val read = compositor.compose(
            canvas = canvas,
            stack = document.layers,
            paperColor = document.paperColor,
            widthPx = document.widthPx,
            heightPx = document.heightPx,
            wet = null,
            left = x,
            top = y,
            right = x + 1f,
            bottom = y + 1f,
            floating = document.floating,
        )
        canvas.restore()
        if (!read) return null
        return pixel.getPixel(0, 0)
    }

    /**
     * The active sheet's own pixel, opaque, or null if there is nothing there.
     *
     * The alpha is dropped rather than carried: a colour in this program is an
     * opaque ARGB that the brush's own opacity and flow are then applied to, so
     * handing back a half-transparent pick would make the next stroke fainter
     * than the one it was copied from and there would be no control that showed
     * why.
     */
    private fun activePixel(document: Document, xDoc: Int, yDoc: Int): Int? {
        var argb = 0
        val read = document.layers.active.layer.read { argb = it.getPixel(xDoc, yDoc) }
        if (!read) return null
        if (argb ushr 24 == 0) return null
        return argb or (0xFF shl 24)
    }
}
