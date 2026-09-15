package be.thalos.artiest.ref

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A 3D model from somewhere on the tablet, into the reference library.
 *
 * Lr12, and the shape is `RefImport`'s: everything on [Dispatchers.IO], nothing
 * that touches the document or the render thread, and a sealed result carrying
 * a reason a person can act on.
 *
 * ## Why it reads the first twelve bytes before anything else
 *
 * A `.glb` begins with the four characters `glTF`, a version, and its own total
 * length. Checking that costs one read and it is the difference between *"that
 * file is not a 3D model"* at the moment the user chose it, and a reference
 * that sits in the strip until it is opened in front of a drawing and shows
 * nothing.
 *
 * It is also the check that catches the common mistake: a `.gltf` chosen
 * instead of a `.glb`. That file begins with `{`, so it fails here, and the
 * message says what to do about it — a `.gltf` names its meshes and textures in
 * files beside it, none of which the picker handed over. See [RefKind.MODEL].
 *
 * ## Why the whole file is read into memory
 *
 * Because the size has to be known before the write, and a content URI has no
 * reliable length: `OpenableColumns.SIZE` is what the other app chose to
 * report and may be absent, stale or a lie. Reading with a hard cap is the only
 * check that cannot be talked out of. [RefFiles.MAX_MODEL_BYTES] is the cap, so
 * the worst case this holds is sixty-four megabytes, once, on a background
 * thread.
 */
object RefModelImport {

    sealed interface Result {
        data class Added(val model: RefPicture) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun add(
        context: Context,
        files: RefFiles,
        uri: Uri,
        label: String = "",
        tags: List<String> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        val name = label.ifEmpty { displayName(context, uri) }
        val bytes = try {
            readCapped(context, uri)
        } catch (e: Exception) {
            return@withContext Result.Failed(reasonOf(e))
        } ?: return@withContext Result.Failed(
            "that file is bigger than ${RefFiles.MAX_MODEL_BYTES / (1024 * 1024)} MB"
        )

        if (!isGlb(bytes)) {
            return@withContext Result.Failed(
                if (looksLikeGltfJson(bytes)) {
                    "that is a .gltf, which keeps its meshes and textures in " +
                        "separate files. Export it as .glb and it will come in whole"
                } else {
                    "that file is not a 3D model"
                }
            )
        }

        val model = files.addModel(bytes, name.take(RefFiles.MAX_LABEL), tags)
        model?.let { Result.Added(it) } ?: Result.Failed("it could not be written")
    }

    /** The bytes, or null when the stream ran past the cap. */
    private fun readCapped(context: Context, uri: Uri): ByteArray? {
        val cap = RefFiles.MAX_MODEL_BYTES
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IllegalStateException("it would not open")
            val out = ByteArrayOutputStream(1 shl 20)
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                // One byte past the cap is enough to know, and stopping there
                // means a wrong pick never costs more than the cap in memory.
                if (total > cap) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /**
     * `glTF`, little-endian version 2, and a length that agrees with the file.
     *
     * The length is checked because a truncated download is the failure that
     * otherwise reaches the renderer, and gltfio's answer to a truncated buffer
     * is a null asset with nothing said about why.
     */
    private fun isGlb(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] != 'g'.code.toByte() || bytes[1] != 'l'.code.toByte() ||
            bytes[2] != 'T'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return false
        }
        val version = leInt(bytes, 4)
        val length = leInt(bytes, 8)
        return version == 2 && length in 12..bytes.size
    }

    /** A JSON glTF: the first non-space character is `{`. */
    private fun looksLikeGltfJson(bytes: ByteArray): Boolean {
        for (b in bytes.take(64)) {
            if (b == ' '.code.toByte() || b == '\n'.code.toByte() ||
                b == '\r'.code.toByte() || b == '\t'.code.toByte()
            ) {
                continue
            }
            return b == '{'.code.toByte()
        }
        return false
    }

    private fun leInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /**
     * What the other app calls the file, without its extension.
     *
     * The label is what the strip and the pane show, and `marble_bust_01.glb`
     * reads better as *marble_bust_01*. Empty is a legitimate answer; a model
     * with no name is shown by its poster, the way a picture is shown by its
     * picture.
     */
    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val at = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (at >= 0 && cursor.moveToFirst()) {
                return cursor.getString(at).orEmpty().substringBeforeLast('.')
            }
        }
        ""
    }.getOrDefault("")

    /** See `RefImport.reasonOf`; the same two cases and the same words. */
    private fun reasonOf(e: Exception): String = when (e) {
        is SecurityException -> "the app it came from did not allow this one to read it"
        else -> e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "it would not open")
    }
}
