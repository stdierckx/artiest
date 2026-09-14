package be.thalos.artiest.card

import android.graphics.Bitmap
import android.graphics.Canvas
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.StackCompositor
import be.thalos.artiest.doc.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * A picture of the drawing as it is now, for a card to carry.
 *
 * Lr7. The same composite the export takes and through the same class, so a
 * card is a picture of what was on the screen rather than a second opinion
 * about it — `StackCompositor` is *the one place that knows what a drawing
 * looks like* and this is another caller of it, not another loop.
 *
 * ## Reference sheets are left out
 *
 * For the PNG export's reason, which is Lr4's: a card is a thing you keep and
 * hand to somebody, and a photograph you were drawing *from* does not belong in
 * it. The count comes back so the caller can say so.
 *
 * ## Where it runs
 *
 * `Dispatchers.IO`, and that is not a preference. `Layer.read` refuses the
 * **main** thread — the sheets belong to the renderer — so this may not be
 * called from a click handler directly. Any background thread is allowed, which
 * is how `PngExporter` has always worked, and this follows it.
 */
object CardSnapshot {

    /** What a snapshot produced. */
    data class Taken(
        val bitmap: Bitmap,
        /** Reference sheets left out. See the header. */
        val referencesSkipped: Int,
    )

    /**
     * The drawing, at no more than [maxSide] on the long edge.
     *
     * Null if the document was closed underneath, which is the one failure a
     * caller cannot do anything about and must not save over.
     *
     * **Halved rather than reduced in one step.** A single `drawBitmap` from
     * 3300 px to 1024 is a 3.2:1 reduction and a bilinear filter samples a 2x2
     * neighbourhood however far apart the taps are, so fine ink falls between
     * them. `Thumbnails` learned that on the tablet with a layer panel full of
     * blank cards, and a deck of blank cards would be the same bug wearing a
     * different name.
     */
    suspend fun of(document: Document, maxSide: Int = MAX_SIDE): Taken? =
        withContext(Dispatchers.IO) {
            val full = Bitmap.createBitmap(
                document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888,
            )
            val compositor = StackCompositor()
            val read = compositor.compose(
                Canvas(full),
                document.layers,
                document.paperColor,
                document.widthPx,
                document.heightPx,
                wet = null,
                left = 0f,
                top = 0f,
                right = document.widthPx.toFloat(),
                bottom = document.heightPx.toFloat(),
                skipReference = true,
            )
            if (!read) {
                full.recycle()
                return@withContext null
            }
            val scale = maxSide.toFloat() / max(full.width, full.height)
            if (scale >= 1f) return@withContext Taken(full, compositor.referencesSkipped)
            // `reduce` releases what it is given, which is why `full` is not
            // recycled here: the page and a half of intermediates it walks
            // through are its own to free and so is the one it started from.
            val small = Thumbnails.reduce(
                full,
                (full.width * scale).toInt().coerceAtLeast(1),
                (full.height * scale).toInt().coerceAtLeast(1),
            )
            Taken(small, compositor.referencesSkipped)
        }

    /**
     * Big enough to read the drawing back off, small enough that a deck of
     * three hundred is megabytes rather than gigabytes.
     */
    const val MAX_SIDE = 1024
}
