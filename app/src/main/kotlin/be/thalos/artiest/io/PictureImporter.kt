package be.thalos.artiest.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.doc.LayerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture from the tablet, brought in **as a layer of its own**.
 *
 * ## Why a new layer and not the active one
 *
 * An imported picture is almost always a reference: a photograph to draw over,
 * a scan to trace, a colour study to match. All three want the picture
 * *underneath* or *behind* what is being drawn, at an opacity the user can pull
 * down, and switchable off when it has served. Every one of those is what a
 * layer is, and none of them survives being painted into the sheet that is
 * already being worked on. Landing it in the active layer would also make the
 * import an unrecoverable edit — undo restores rectangles, and the rectangle
 * here is the whole page.
 *
 * ## Where the work happens
 *
 * Not on the main thread and not on the render thread. Decoding a phone
 * photograph is tens of milliseconds and the scale down is another full-page
 * blit, and both of them would be a visible stall wherever they ran.
 *
 * The trick that makes this cheap is that **a layer nobody has been handed is
 * nobody's**: the `Layer` is allocated and painted here, on a background
 * dispatcher, while it is still private to this function. `Layer.write` refuses
 * only the main thread — see its threading note — so a worker may write into
 * one freely, and the render thread never sees these pixels until the finished
 * layer is queued as a [LayerOp.Add]. By the time the stack owns it, it is
 * already the picture.
 *
 * ## Fitted, not stretched and not cropped
 *
 * Scaled to fit inside the page and centred, keeping the picture's own aspect
 * — **up as well as down**. A reference smaller than the page would otherwise
 * be stuck small for the life of the drawing, because there is no transform
 * tool to enlarge a layer with yet; fitting is reversible in the only way that
 * matters here, which is importing it again.
 * A photograph is 4:3 or 3:2 and the page is 1.53:1, so something has to give:
 * stretching distorts the thing being used as a reference, which defeats the
 * purpose, and cropping silently throws away the part of the reference that ran
 * off the edge. Letterboxing is the only one of the three whose failure the
 * user can see and fix by zooming.
 */
object PictureImporter {

    /**
     * Decode [uri], fit it to the page, and queue it as a new layer.
     *
     * Everything before the queue happens on [Dispatchers.IO]; the queue itself
     * is a lock-free add, so this is safe to call from a coroutine on the main
     * thread and does nothing on it.
     *
     * A failure leaves no layer behind, queued or otherwise. The allocation
     * happens after the decode for exactly that reason: nothing to release on
     * the path that is most likely to fail.
     */
    suspend fun importInto(
        context: Context,
        document: Document,
        uri: Uri,
        name: String,
    ): ImportResult = withContext(Dispatchers.IO) {
        val decoded = try {
            decode(context, uri, document.widthPx, document.heightPx)
        } catch (e: Exception) {
            // ImageDecoder throws IOException for an unreadable stream and
            // DecodeException for a file that is not an image; a content
            // provider that has gone away throws SecurityException. All three
            // are the same thing to the user -- "that picture would not open"
            // -- and none of them should take the app with it.
            return@withContext ImportResult.Failed(reasonOf(e))
        } ?: return@withContext ImportResult.Failed("that file is not a picture")

        // Read before the recycle. `Bitmap.getWidth` on a recycled instance
        // happens to return the last value it held rather than throwing, which
        // is exactly the sort of thing that works until a platform release
        // decides otherwise.
        val sourceW = decoded.width
        val sourceH = decoded.height
        val layer: Layer = document.newLayer()
        val placed = try {
            paint(layer, decoded, document.widthPx, document.heightPx)
        } finally {
            decoded.recycle()
        }
        if (!placed) {
            // The document was closed under us -- the only way `write` refuses
            // from here. Release the sheet rather than queueing one that will
            // be refused and closed a frame later, so the failure has one shape.
            layer.close()
            return@withContext ImportResult.Failed("the drawing was closed")
        }

        document.requestLayers(LayerOp.Add(layer, name))
        ImportResult.Imported(name, sourceW, sourceH)
    }

    /**
     * Decode at no more than the page's own size.
     *
     * `setTargetSampleSize` rather than a full decode followed by a scale: a
     * modern phone photograph is 4000 px across and 48 MB decoded, and this
     * page is 3300. Sampling asks the decoder for the nearest power-of-two
     * reduction, which it does while reading, so the big bitmap never exists.
     *
     * **Software allocation, and it is not optional.** The default for
     * `ImageDecoder` is a hardware bitmap, which cannot be drawn into a
     * software `Canvas` and cannot be read back at all — the very next line
     * would throw. `Layer`'s own KDoc makes the same point about `HARDWARE` for
     * the same reason.
     *
     * Orientation is handled by the decoder: a photograph taken sideways
     * carries an EXIF rotation, and `ImageDecoder` applies it. `BitmapFactory`
     * does not, which is why this is not `BitmapFactory`.
     */
    private fun decode(context: Context, uri: Uri, pageW: Int, pageH: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            var sample = 1
            while (w / (sample * 2) >= pageW && h / (sample * 2) >= pageH) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }

    /**
     * Draw [picture] into [layer], fitted and centred.
     *
     * Filtered and antialiased, unlike every other blit in this app: those are
     * 1:1 copies of a document into a document, and this is an arbitrary
     * rescale of a photograph. Point sampling a photograph down produces the
     * aliasing everyone recognises as "resized in the wrong program".
     */
    internal fun paint(layer: Layer, picture: Bitmap, pageW: Int, pageH: Int): Boolean {
        val scale = min(pageW.toFloat() / picture.width, pageH.toFloat() / picture.height)
        val w = (picture.width * scale).roundToInt().coerceAtLeast(1)
        val h = (picture.height * scale).roundToInt().coerceAtLeast(1)
        val left = (pageW - w) / 2
        val top = (pageH - h) / 2
        val dst = Rect(left, top, left + w, top + h)
        val src = Rect(0, 0, picture.width, picture.height)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }
        return layer.write { canvas: Canvas -> canvas.drawBitmap(picture, src, dst, paint) }
    }

    private fun reasonOf(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "it would not open")
}

/**
 * What an import did.
 *
 * The same shape and the same argument as [ExportResult]: a nullable return
 * would let a failure look like "nothing happened", and "nothing happened" is
 * exactly what a successful import looks like too until the panel is opened.
 */
sealed interface ImportResult {

    /** [width] and [height] are the picture's, before it was fitted to the page. */
    data class Imported(val name: String, val width: Int, val height: Int) : ImportResult

    data class Failed(val reason: String) : ImportResult
}
