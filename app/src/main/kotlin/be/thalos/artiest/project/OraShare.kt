package be.thalos.artiest.project

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import be.thalos.artiest.doc.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The way a drawing leaves this tablet, and the way one arrives.
 *
 * [OraFile] is the format; this is the Android around it — where an exported
 * file goes, and how a file somebody sends you gets read without being trusted.
 *
 * ## Out, to Downloads
 *
 * `MediaStore.Downloads`, not `Images`: an `.ora` is not a picture the gallery
 * can show, and a file the photo app lists and then cannot open is worse than
 * one it does not list. The publish dance is `PngExporter`'s, for the reason
 * that file argues at length — the row is created pending, and it is published
 * on exactly one path, the one where the bytes are already written. Every other
 * path deletes it, so a failed export leaves no empty file behind.
 *
 * ## In, through a copy
 *
 * A content URI is a stream, and a zip is read from its directory at the end.
 * So the file is copied into the cache first and read from there. That copy is
 * also the security boundary: nothing inside the zip is opened until it is on
 * our own disk with a name we chose, and the copy is deleted whatever happens.
 *
 * **An import is always a new project**, never a merge and never an overwrite.
 * A file from a stranger that could replace a month of work is not a file
 * anybody should open, and "are you sure" is not a defence.
 */
object OraShare {

    /** Where an exported drawing lands, and what the user is told to look for. */
    const val FOLDER = "Download/Artiest"

    suspend fun export(
        context: Context,
        files: ProjectFiles,
        project: Project,
        document: Document,
    ): OraResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${Project.slug(project.name)}.ora")
            put(MediaStore.Downloads.MIME_TYPE, Ora.MIMETYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return@withContext OraResult.Failed("the tablet refused a file there")

        val written = try {
            val stream = resolver.openOutputStream(uri)
                ?: return@withContext failAndDelete(context, uri, "the file could not be opened")
            stream.use { OraFile.write(files, project, document, it) }
        } catch (e: Exception) {
            return@withContext failAndDelete(context, uri, e.message ?: "it could not be written")
        }

        if (written is OraResult.Failed) return@withContext failAndDelete(context, uri, written.reason)

        // Only here, and only now: the bytes are on disk.
        try {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            return@withContext failAndDelete(context, uri, "it could not be published")
        }
        written
    }

    private fun failAndDelete(context: Context, uri: Uri, reason: String): OraResult {
        runCatching { context.contentResolver.delete(uri, null, null) }
        return OraResult.Failed(reason)
    }

    /**
     * Take in a file somebody sent, as a new project.
     *
     * [name] is what the new project is called; the file's own name is a
     * suggestion the caller makes, not something read out of the zip. Nothing
     * inside an archive should ever decide a path.
     */
    suspend fun import(
        context: Context,
        files: ProjectFiles,
        uri: Uri,
        name: String,
        now: Long,
    ): OraResult = withContext(Dispatchers.IO) {
        val copy = File(context.cacheDir, "import-${System.nanoTime()}.ora")
        try {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    copy.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                null
            } ?: return@withContext OraResult.Failed("that file could not be read")
            if (bytes > MAX_FILE) return@withContext OraResult.Failed("that file is too big to open")

            OraFile.read(files, copy, name, now)
        } finally {
            runCatching { copy.delete() }
        }
    }

    /** Eight full pages of dense ink, with room. Past this it is not a drawing. */
    private const val MAX_FILE = 512L * 1024 * 1024
}
