package be.thalos.artiest.ref

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture from somewhere on the tablet, into the reference library.
 *
 * Lr3. The same decode `PictureImporter` does and for the same three reasons —
 * software allocation because a hardware bitmap cannot be read back, sampling
 * during the read so the 48 MB original never exists, and `ImageDecoder`
 * rather than `BitmapFactory` because only the first applies a photograph's
 * EXIF rotation.
 *
 * Where it differs is the target. An import is fitted to the *page*, because it
 * is becoming a layer of the drawing. This is fitted to [RefFiles.MAX_SIDE],
 * because it is becoming a thing you look at and zoom into, and the page has no
 * opinion about how big that should be.
 *
 * Everything is on [Dispatchers.IO]. Nothing here touches the document, the
 * render thread or a `Layer`, which is what makes this the cheap half of Lr3:
 * adding a reference cannot disturb a drawing.
 */
object RefImport {

    /** What an add did. A sealed result for `ExportResult`'s reason. */
    sealed interface Result {
        data class Added(val picture: RefPicture) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Decode [uri], scale it, and put it in [files].
     *
     * [label] is what to call it, usually the file's own display name. Empty is
     * legitimate: a picture with no name is shown by its picture, which is what
     * the eye uses anyway.
     */
    suspend fun add(
        context: Context,
        files: RefFiles,
        uri: Uri,
        label: String = "",
        tags: List<String> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        val decoded = try {
            decode(context, uri)
        } catch (e: Exception) {
            // Unreadable stream, a file that is not an image, a provider that
            // has gone away. All three are "that picture would not open" to the
            // user and none of them may take the app with it.
            return@withContext Result.Failed(reasonOf(e))
        } ?: return@withContext Result.Failed("that file is not a picture")

        // `fit` hands the original straight back when it is already small
        // enough, so the recycle is guarded by **identity**: two bitmaps of the
        // same size are not the same bitmap, and recycling the one that is
        // still about to be written would produce an empty file.
        val scaled = fit(decoded)
        val picture = files.add(scaled, label.take(RefFiles.MAX_LABEL), tags)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        picture?.let { Result.Added(it) } ?: Result.Failed("it could not be written")
    }

    /**
     * At most [RefFiles.MAX_SIDE] on the long edge, keeping the shape.
     *
     * Hands the original straight back when it is already small enough, which
     * is the usual case for a screenshot and never the case for a photograph.
     * Filtered and antialiased, unlike every other blit in this app: those are
     * 1:1 copies of a document into a document and this is an arbitrary
     * rescale of a photograph.
     */
    private fun fit(picture: Bitmap): Bitmap {
        val longest = maxOf(picture.width, picture.height)
        if (longest <= RefFiles.MAX_SIDE) return picture
        val scale = min(
            RefFiles.MAX_SIDE.toFloat() / picture.width,
            RefFiles.MAX_SIDE.toFloat() / picture.height,
        )
        val w = (picture.width * scale).roundToInt().coerceAtLeast(1)
        val h = (picture.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            picture,
            Rect(0, 0, picture.width, picture.height),
            Rect(0, 0, w, h),
            Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
                isDither = true
            },
        )
        return out
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            var sample = 1
            while (
                w / (sample * 2) >= RefFiles.MAX_SIDE || h / (sample * 2) >= RefFiles.MAX_SIDE
            ) {
                sample *= 2
            }
            decoder.setTargetSampleSize(sample)
        }
    }

    /**
     * Why it would not open, in words a person can act on.
     *
     * `SecurityException` is named because it is the one that happens to
     * somebody rather than to a file: the app that shared the picture did not
     * hand over permission to read it, and its own message is
     * *"be.thalos.artiest has no access to content://media/external/…"*, which
     * tells the user nothing and reads like a crash.
     */
    private fun reasonOf(e: Exception): String = when (e) {
        is SecurityException -> "the app it came from did not allow this one to read it"
        else -> e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "it would not open")
    }
}
