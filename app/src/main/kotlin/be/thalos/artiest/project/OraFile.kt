package be.thalos.artiest.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.StackCompositor
import be.thalos.artiest.doc.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A project in and out of one `.ora` file.
 *
 * See [Ora] for what the format is and why it is the export rather than the
 * working copy. This is the half that touches pixels and the filesystem.
 *
 * ## Writing costs one image, not eight
 *
 * The sheets in the project directory are already PNGs, so they are **copied
 * into the zip byte for byte** — stored rather than deflated, because a PNG is
 * already deflated and asking zip to do it again spends a second to save
 * nothing. The only thing that has to be computed is `mergedimage.png`, which
 * the format requires and which is the same composite the screen draws.
 *
 * **The project must be saved first.** This reads the directory, not the
 * document — except for the merged image, which has to be the drawing as it is
 * now. The caller saves and then exports; that is one line at the call site and
 * it keeps this from having to know about the saver's lock.
 *
 * ## Reading always makes a new project
 *
 * A file from somebody else never lands on top of a drawing you have. Same rule
 * as `WorkspaceFiles.import`, and more so here: the thing that would be
 * overwritten is a month of work rather than a toolbar.
 *
 * What a foreign file brings that ours never does is handled rather than
 * ignored: a layer smaller than the image, at an offset, is drawn where it
 * belongs; a layer group is flattened and the import says so; a layer whose
 * PNG will not decode becomes an empty sheet with its name, so the stack still
 * matches what the file described.
 */
object OraFile {

    // -----------------------------------------------------------------------
    // out
    // -----------------------------------------------------------------------

    suspend fun write(
        files: ProjectFiles,
        project: Project,
        document: Document,
        sink: OutputStream,
    ): OraResult = withContext(Dispatchers.IO) {
        if (project.sheets.isEmpty()) {
            return@withContext OraResult.Failed("there is nothing in this drawing yet")
        }
        try {
            ZipOutputStream(sink).use { zip ->
                // First, stored, uncompressed, exactly as the format requires:
                // it is how a reader identifies the file without unzipping it.
                val mime = Ora.MIME_BYTES.toByteArray(Charsets.US_ASCII)
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(stored(Ora.MIMETYPE.let { "mimetype" }, mime))
                zip.write(mime)
                zip.closeEntry()

                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                val xml = Ora.stackXml(project).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(Ora.STACK))
                zip.write(xml)
                zip.closeEntry()

                // Byte for byte, and stored: these are PNGs already.
                zip.setLevel(Deflater.NO_COMPRESSION)
                for (i in project.sheets.indices) {
                    val file = files.sheetOf(project.id, i)
                    if (!file.isFile) continue
                    val bytes = file.readBytes()
                    zip.putNextEntry(stored(Ora.dataFor(i), bytes))
                    zip.write(bytes)
                    zip.closeEntry()
                }

                val merged = merged(document)
                    ?: return@withContext OraResult.Failed("the drawing could not be flattened")
                zip.putNextEntry(stored(Ora.MERGED, merged))
                zip.write(merged)
                zip.closeEntry()

                thumbnail(files, project)?.let { thumb ->
                    zip.putNextEntry(stored(Ora.THUMBNAIL, thumb))
                    zip.write(thumb)
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return@withContext OraResult.Failed(e.message ?: "it could not be written")
        } catch (e: OutOfMemoryError) {
            return@withContext OraResult.Failed("there was not enough memory to flatten the drawing")
        }
        OraResult.Written(project.sheets.size)
    }

    /** A stored entry has to carry its own size and checksum. */
    private fun stored(name: String, bytes: ByteArray): ZipEntry {
        val crc = CRC32().apply { update(bytes) }
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
    }

    /** The whole drawing on its paper, which is what the format calls merged. */
    private fun merged(document: Document): ByteArray? {
        val out = try {
            Bitmap.createBitmap(document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        return try {
            val read = StackCompositor().compose(
                canvas = Canvas(out),
                stack = document.layers,
                paperColor = document.paperColor,
                widthPx = document.widthPx,
                heightPx = document.heightPx,
                wet = null,
                left = 0f,
                top = 0f,
                right = document.widthPx.toFloat(),
                bottom = document.heightPx.toFloat(),
            )
            if (!read) null else encode(out)
        } finally {
            out.recycle()
        }
    }

    /** The project's own picture, brought down to what the format asks for. */
    private fun thumbnail(files: ProjectFiles, project: Project): ByteArray? {
        val file = files.thumbnailOf(project.id)
        if (!file.isFile) return null
        val source = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        val scale = minOf(
            Ora.THUMB_MAX.toFloat() / source.width,
            Ora.THUMB_MAX.toFloat() / source.height,
            1f,
        )
        if (scale >= 1f) return encode(source).also { source.recycle() }
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        // Consumes `source`.
        val small = Thumbnails.reduce(source, w, h)
        return encode(small).also { small.recycle() }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val bytes = java.io.ByteArrayOutputStream(1 shl 18)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return bytes.toByteArray()
    }

    // -----------------------------------------------------------------------
    // in
    // -----------------------------------------------------------------------

    /**
     * Read [source] as a new project in [files].
     *
     * [source] is a file rather than a stream because a zip is read by its
     * directory, which is at the end: a reader given a stream has to buffer the
     * whole thing to find out what is in it, and this way the caller decides
     * where that copy lives.
     */
    suspend fun read(
        files: ProjectFiles,
        source: File,
        name: String,
        now: Long,
    ): OraResult = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val zip = try {
            ZipFile(source)
        } catch (e: Exception) {
            return@withContext OraResult.Failed("that file is not an .ora")
        }
        zip.use {
            val xml = it.getEntry(Ora.STACK)?.let { entry ->
                it.getInputStream(entry).use { stream -> stream.readBytes() }
            }?.toString(Charsets.UTF_8)
                ?: return@withContext OraResult.Failed("that file has no stack.xml in it")

            val stack = Ora.readStack(xml)
                ?: return@withContext OraResult.Failed("its stack.xml could not be read")
            if (stack.sheets.isEmpty()) {
                return@withContext OraResult.Failed("it describes no layers")
            }
            if (stack.nested) {
                notes += "it has layer groups, which are flattened into the list of sheets"
            }

            val project = files.create(name, stack.widthPx, stack.heightPx, now)
                ?: return@withContext OraResult.Failed("a project directory could not be made")

            val sheets = ArrayList<ProjectSheet>(stack.sheets.size)
            for ((i, sheet) in stack.sheets.withIndex()) {
                val target = files.sheetOf(project.id, i)
                val ok = extract(it, sheet, stack.widthPx, stack.heightPx, target, files)
                if (!ok) notes += "\"${sheet.name}\" came in empty — ${sheet.src} could not be read"
                sheets += ProjectSheet(
                    file = Project.fileFor(i),
                    name = sheet.name,
                    opacity = sheet.opacity,
                    visible = sheet.visible,
                    blend = sheet.blend,
                )
            }

            // The file's own picture, if it brought one. Without it the card in
            // the gallery is blank until the drawing is next drawn on -- and a
            // drawing that has just been opened from somewhere else is exactly
            // the one the user wants to recognise.
            it.getEntry(Ora.THUMBNAIL)?.let { entry ->
                runCatching {
                    val bytes = it.getInputStream(entry).use { stream -> stream.readBytes() }
                    files.writeAtomically(files.thumbnailOf(project.id)) { tmp ->
                        tmp.writeBytes(bytes)
                    }
                }
            }

            val saved = project.revised(now, sheets, active = sheets.lastIndex.coerceAtLeast(0))
            if (!files.save(saved)) {
                return@withContext OraResult.Failed("the project file could not be written")
            }
            return@withContext OraResult.Read(saved, notes)
        }
    }

    /**
     * One layer out of the zip and into `layers/<i>.png`.
     *
     * **Re-encoded rather than copied, and only when it has to be.** A layer
     * that is already the size of the image goes across as bytes; one that is
     * smaller or offset — which is most of what Krita writes, because it trims
     * a layer to what is drawn on it — is decoded, drawn where the file says it
     * belongs, and written out at page size. The alternative is a drawing whose
     * every layer is in the top-left corner.
     */
    private fun extract(
        zip: ZipFile,
        sheet: Ora.Sheet,
        widthPx: Int,
        heightPx: Int,
        target: File,
        files: ProjectFiles,
    ): Boolean {
        val entry = zip.getEntry(sheet.src) ?: return false
        val bytes = try {
            zip.getInputStream(entry).use { it.readLimited(MAX_LAYER_BYTES) }
        } catch (e: Exception) {
            null
        } ?: return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val fits = sheet.x == 0 && sheet.y == 0 &&
            bounds.outWidth == widthPx && bounds.outHeight == heightPx

        if (fits) {
            return files.writeAtomically(target) { tmp -> tmp.writeBytes(bytes) }
        }

        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false

        val page = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            return false
        }
        return try {
            Canvas(page).drawBitmap(decoded, sheet.x.toFloat(), sheet.y.toFloat(), null)
            files.writeAtomically(target) { tmp ->
                tmp.outputStream().use { out ->
                    page.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
            }
        } finally {
            decoded.recycle()
            page.recycle()
        }
    }

    /**
     * Read at most [limit] bytes, or nothing.
     *
     * A zip entry declares its size and can lie about it; this is what stops a
     * forty-kilobyte file from claiming to be four gigabytes of layer. The
     * limit is generous — a full page of dense ink encodes to a few megabytes —
     * and being over it means the file is not one of ours to open.
     */
    private fun InputStream.readLimited(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** A page of dense ink is a few megabytes. Sixty-four is not a page. */
    private const val MAX_LAYER_BYTES = 64 * 1024 * 1024
}

/** What an `.ora` did, in or out. */
sealed interface OraResult {

    data class Written(val sheets: Int) : OraResult

    data class Read(val project: Project, val notes: List<String>) : OraResult

    data class Failed(val reason: String) : OraResult
}
