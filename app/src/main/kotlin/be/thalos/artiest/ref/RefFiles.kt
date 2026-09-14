package be.thalos.artiest.ref

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The reference library, as a directory of pictures and one small text file
 * each.
 *
 * Lr3, and it follows `BrushFiles` in every decision that is not new:
 *
 * **Two files per picture, no index.** `<id>.jpg` and `<id>.txt`. A single
 * index would have to be rewritten whenever anything changed, which is the
 * shape of bug where adding your fortieth reference loses the other
 * thirty-nine. A delete is two deletes and a copy is two copies.
 *
 * **Nothing here throws.** A directory is exactly where a truncated file, a
 * file somebody edited by hand and a file from a later build all come from. A
 * picture that will not read is skipped and the rest of the library loads.
 *
 * **An id is a slug, and a slug is a safe file name.** Nothing that reaches
 * [fileFor] can carry a path separator or a `..`; the names read back off the
 * disk are checked anyway, because the cheap check is the one that survives a
 * later change to how ids are made.
 *
 * ## Copied in, not linked to
 *
 * Krita offers both for its reference images and is right to on a desktop.
 * Here a photograph that was moved, renamed or tidied away by the gallery app
 * would break the link silently, months later, in a drawing that had been
 * working. So the bytes are copied into the app's own files and the original is
 * never touched or needed again.
 *
 * ## JPEG, and the one thing it costs
 *
 * At [MAX_SIDE] on the long edge and quality [QUALITY]. A phone photograph is
 * then a few hundred kilobytes rather than the three to eight megabytes it
 * arrived as, and a beginner will import forty of them — `docs/learner-plan.md`
 * trap 5.
 *
 * What it costs is exact colour: a JPEG's blocks move a pixel by a unit or two,
 * and the pen can pick a colour off this picture. That is the right trade for
 * what this is — nobody matches a photograph to the exact byte, and the eye
 * cannot see two units — but it is a trade and it is written down here rather
 * than discovered.
 */
class RefFiles(private val dir: File) {

    /**
     * Every picture, newest first.
     *
     * Newest first and not by name, because a reference library is used the way
     * a desk is: the thing you want is usually the thing you just put down.
     */
    fun list(): List<RefPicture> = runCatching {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(META) }
            ?: return emptyList()
        files.take(MAX_PICTURES)
            .mapNotNull { read(it) }
            .sortedByDescending { it.addedMs }
    }.getOrDefault(emptyList())

    /** Every tag in use, in alphabetical order, each one once. */
    fun tags(): List<String> =
        list().flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** What the library takes on disk, in bytes. Shown so it can be reclaimed. */
    fun bytes(): Long = runCatching {
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }.getOrDefault(0L)

    /**
     * Write [bitmap] into the library and answer what was written.
     *
     * The caller has already decoded and scaled; see `RefImport`. Null if
     * anything about the write failed, and in that case nothing is left behind
     * — a half-written picture that shows as a grey box in the strip is worse
     * than one that never arrived.
     */
    fun add(bitmap: Bitmap, label: String = "", tags: List<String> = emptyList()): RefPicture? {
        val id = newId()
        val image = fileFor(id)
        val ok = runCatching {
            dir.mkdirs()
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            true
        }.getOrDefault(false)
        if (!ok || image.length() == 0L) {
            image.delete()
            return null
        }
        val picture = RefPicture(
            id = id,
            label = label,
            tags = tags,
            addedMs = System.currentTimeMillis(),
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            bytes = image.length(),
        )
        if (!write(picture)) {
            image.delete()
            return null
        }
        return picture
    }

    /** The pixels, or null. Decoded on the caller's thread; see `RefImport`. */
    fun load(id: String): Bitmap? = runCatching {
        val file = fileFor(id)
        if (!file.isFile) return null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    /**
     * A small one, for the strip. [maxSide] is an upper bound, not a size.
     *
     * `inSampleSize` and not a decode-then-scale, because the point is to avoid
     * ever holding the big one: a strip of forty thumbnails that each passed
     * through a full-size bitmap is forty full-size allocations.
     */
    fun loadSmall(id: String, maxSide: Int): Bitmap? = runCatching {
        val file = fileFor(id)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /** Rename, retag, or both. False if it could not be written. */
    fun update(picture: RefPicture): Boolean = write(picture)

    /** Both files. True if the picture is gone, whether or not it was there. */
    fun delete(id: String): Boolean = runCatching {
        if (!isSlug(id)) return false
        fileFor(id).delete()
        metaFor(id).delete()
        true
    }.getOrDefault(false)

    private fun write(picture: RefPicture): Boolean = runCatching {
        if (!isSlug(picture.id)) return false
        dir.mkdirs()
        metaFor(picture.id).writeText(encode(picture))
        true
    }.getOrDefault(false)

    private fun read(file: File): RefPicture? = runCatching {
        if (file.length() > MAX_META_BYTES) return null
        val id = file.name.removeSuffix(META)
        if (!isSlug(id)) return null
        if (!fileFor(id).isFile) return null
        decode(id, file.readText(), fileFor(id).length())
    }.getOrNull()

    private fun fileFor(id: String) = File(dir, id + IMAGE)

    private fun metaFor(id: String) = File(dir, id + META)

    /**
     * `key value` a line, the way `BrushCodec` writes a brush.
     *
     * One line per idea, in an order that does not matter, and an unknown line
     * is skipped rather than fatal — which is what lets a later build add a
     * field without this one refusing the file.
     */
    private fun encode(p: RefPicture): String = buildString {
        append("added ").append(p.addedMs).append('\n')
        append("size ").append(p.widthPx).append(' ').append(p.heightPx).append('\n')
        if (p.label.isNotEmpty()) append("label ").append(oneLine(p.label)).append('\n')
        for (tag in p.tags) append("tag ").append(oneLine(tag)).append('\n')
    }

    private fun decode(id: String, text: String, bytes: Long): RefPicture {
        var added = 0L
        var w = 0
        var h = 0
        var label = ""
        val tags = ArrayList<String>()
        for (line in text.lineSequence()) {
            val at = line.indexOf(' ')
            if (at <= 0) continue
            val value = line.substring(at + 1)
            when (line.substring(0, at)) {
                "added" -> added = value.trim().toLongOrNull() ?: 0L
                "size" -> {
                    val parts = value.trim().split(' ')
                    w = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    h = parts.getOrNull(1)?.toIntOrNull() ?: 0
                }
                "label" -> label = value.take(MAX_LABEL)
                "tag" -> if (tags.size < MAX_TAGS) tags += value.take(MAX_TAG)
            }
        }
        return RefPicture(id, label, tags, added, w, h, bytes)
    }

    /** Newlines out, because the format is one line per idea. */
    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    private fun newId(): String {
        val stamp = System.currentTimeMillis()
        var id = "r$stamp"
        var n = 0
        while (metaFor(id).exists() || fileFor(id).exists()) {
            n++
            id = "r$stamp-$n"
        }
        return id
    }

    private fun isSlug(s: String) = s.isNotEmpty() && s.length <= 40 && s.all {
        it.isLetterOrDigit() || it == '-'
    }

    companion object {
        const val IMAGE = ".jpg"
        const val META = ".txt"

        /**
         * The long edge a stored picture is scaled to.
         *
         * Bigger than the page's short side and smaller than a phone camera, so
         * a reference can be zoomed into in the pane without being a
         * photograph's worth of megabytes. The page is 3300x2160.
         */
        const val MAX_SIDE = 2048

        /** High enough that the blocks are invisible, low enough to be the point. */
        const val QUALITY = 92

        /** A library, not an archive. Forty is already more than anybody browses. */
        const val MAX_PICTURES = 200

        const val MAX_LABEL = 60
        const val MAX_TAG = 24
        const val MAX_TAGS = 8

        private const val MAX_META_BYTES = 4096L
    }
}
