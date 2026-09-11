package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Making a big picture small, without losing the drawing on the way.
 *
 * ## The lesson, which was learned on the tablet and must not be learned twice
 *
 * **A single big reduction comes back blank.** `drawBitmap` from 3300 px to 128
 * is 26:1, and a bilinear filter samples a 2x2 neighbourhood however far apart
 * the samples are — so a pencil line two document pixels wide falls between the
 * taps about ninety-two times in a hundred. `LayerStack` found this with two
 * sheets, a stroke on each, one thumbnail showing a line and the other showing
 * nothing, both sheets visibly drawn on.
 *
 * **Halving is the fix**, because at 2:1 the 2x2 neighbourhood *is* the four
 * pixels being merged, so nothing can fall between the samples.
 *
 * It lives here rather than in `LayerStack` because there are two callers now —
 * the layers panel's per-sheet thumbnails and `ProjectSaver`'s picture of the
 * whole drawing — and the second one would otherwise have had to rediscover it
 * from a blank card in the gallery.
 */
internal object Thumbnails {

    /**
     * Filtered, because unfiltered is point sampling: a pencil line one
     * document pixel wide either lands on a sample or does not, so a drawing
     * made of fine lines produces a thumbnail that is blank in some places and
     * speckled in others, and which changes at random as the drawing grows.
     * Filtering alone is not enough — see the object header.
     */
    val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /** Half the width and half the height, never below one pixel. */
    fun halve(src: Bitmap): Bitmap {
        val w = (src.width / 2).coerceAtLeast(1)
        val h = (src.height / 2).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
        return out
    }

    /**
     * [src] reduced to [width] by [height], in halves and then one last step.
     *
     * **Consumes [src]**: it is recycled here, along with every intermediate.
     * The largest intermediate of a full page is 1650x1080 — 7.1 MiB — and the
     * caller has no use for any of them, so leaving the release to a caller who
     * has to remember is leaving a page and a half of pixels to the garbage
     * collector's timing.
     */
    fun reduce(src: Bitmap, width: Int, height: Int): Bitmap {
        var step = src
        while (step.width > width * 2 && step.height > height * 2) {
            val next = halve(step)
            step.recycle()
            step = next
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            step,
            Rect(0, 0, step.width, step.height),
            Rect(0, 0, width, height),
            paint,
        )
        step.recycle()
        return out
    }
}
