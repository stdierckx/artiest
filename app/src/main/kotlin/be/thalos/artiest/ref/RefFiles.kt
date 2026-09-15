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
 *
 * ## The third file, and only for models
 *
 * Lr12 put 3D models in the same library, and a model is `<id>.glb` where a
 * picture is `<id>.jpg`. A model may *also* have an `<id>.jpg`, which is not the
 * reference but a **poster**: the first frame the pane rendered of it, kept so
 * the strip has a face to show instead of a grey box. It is written once, from
 * the pane, and it is disposable — delete it and the next look at that model
 * makes another.
 *
 * So the payload of a reference is decided by its kind and nothing else, and
 * `<id>.jpg` means two different things depending on that kind. That is the one
 * sharp edge in this file and it is the reason [payloadFor] exists rather than
 * the callers reaching for an extension.
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

    // ---- models -------------------------------------------------------------

    /**
     * Write a `.glb` into the library and answer what was written.
     *
     * The bytes are handed over whole rather than as a stream, because the
     * caller has already had to read them to check the size and the magic —
     * see `RefModelImport` — and reading a sixty-megabyte file twice to avoid
     * holding it once is the wrong trade on a tablet with eight gigabytes.
     *
     * Null if anything failed, and nothing is left behind. Same rule as [add]:
     * a half-written model is a row in the strip that never opens.
     */
    fun addModel(
        bytes: ByteArray,
        label: String = "",
        tags: List<String> = emptyList(),
    ): RefPicture? {
        if (bytes.isEmpty() || bytes.size > MAX_MODEL_BYTES) return null
        val id = newId()
        val file = modelFor(id)
        val ok = runCatching {
            dir.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
        if (!ok || file.length() == 0L) {
            file.delete()
            return null
        }
        val model = RefPicture(
            id = id,
            kind = RefKind.MODEL,
            label = label,
            tags = tags,
            addedMs = System.currentTimeMillis(),
            bytes = file.length(),
        )
        if (!write(model)) {
            file.delete()
            return null
        }
        return model
    }

    /**
     * The mesh, as bytes, or null.
     *
     * Whole and not mapped. `AssetLoader.createAsset` wants a direct `Buffer`
     * it can read on its own threads, and a `MappedByteBuffer` over a file the
     * app might delete underneath it is a segfault rather than an exception.
     */
    fun loadModel(id: String): ByteArray? = runCatching {
        val file = modelFor(id)
        if (!file.isFile || file.length() > MAX_MODEL_BYTES) return null
        file.readBytes()
    }.getOrNull()

    /**
     * Keep a rendered frame of [id] as the face the strip shows.
     *
     * Called from the pane the first time a model has actually been drawn,
     * because that is the only moment in this app where a mesh has pixels. It
     * is a cache and it is treated as one: a failed write is dropped in silence
     * and tried again next time, and [delete] removes it with everything else.
     */
    fun setPoster(id: String, bitmap: Bitmap): Boolean = runCatching {
        if (!isSlug(id)) return false
        dir.mkdirs()
        val file = fileFor(id)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        file.length() > 0L
    }.getOrDefault(false)

    /** Whether [id] already has a poster, so the pane does not make a second one. */
    fun hasPoster(id: String): Boolean = fileFor(id).isFile

    /**
     * Every file. True if the reference is gone, whether or not it was there.
     *
     * All three names unconditionally, because a model may or may not have a
     * poster beside it and asking first costs a stat to save a delete of a file
     * that is not there.
     */
    fun delete(id: String): Boolean = runCatching {
        if (!isSlug(id)) return false
        fileFor(id).delete()
        modelFor(id).delete()
        metaFor(id).delete()
        true
    }.getOrDefault(false)

    private fun write(picture: RefPicture): Boolean = runCatching {
        if (!isSlug(picture.id)) return false
        dir.mkdirs()
        metaFor(picture.id).writeText(encode(picture))
        true
    }.getOrDefault(false)

    /**
     * A reference is only real when both its files are.
     *
     * The kind has to be decoded before the payload can be looked for, so the
     * text is parsed first and the existence check happens after — which is
     * also why the byte count is taken from the payload the kind names rather
     * than from the `.jpg` this used to assume.
     */
    private fun read(file: File): RefPicture? = runCatching {
        if (file.length() > MAX_META_BYTES) return null
        val id = file.name.removeSuffix(META)
        if (!isSlug(id)) return null
        val kind = kindOf(file.readText())
        val payload = payloadFor(id, kind)
        if (!payload.isFile) return null
        decode(id, kind, file.readText(), payload.length())
    }.getOrNull()

    /** The picture, or the mesh. See the note about the third file. */
    private fun payloadFor(id: String, kind: RefKind) = File(dir, id + kind.extension)

    private fun fileFor(id: String) = File(dir, id + IMAGE)

    private fun metaFor(id: String) = File(dir, id + META)

    private fun modelFor(id: String) = File(dir, id + MODEL)

    /**
     * `key value` a line, the way `BrushCodec` writes a brush.
     *
     * One line per idea, in an order that does not matter, and an unknown line
     * is skipped rather than fatal — which is what lets a later build add a
     * field without this one refusing the file.
     */
    private fun encode(p: RefPicture): String = buildString {
        append("added ").append(p.addedMs).append('\n')
        // Written only for models, and the size only when there is one, so a
        // picture's meta file is byte-for-byte what every earlier build wrote.
        // That is what makes Lr12 need no migration: an old file parses here
        // and a file this build writes parses in an old build, minus the line
        // it does not know, which `decode` has always skipped.
        if (p.kind == RefKind.MODEL) append("kind model\n")
        if (p.widthPx > 0 || p.heightPx > 0) {
            append("size ").append(p.widthPx).append(' ').append(p.heightPx).append('\n')
        }
        if (p.label.isNotEmpty()) append("label ").append(oneLine(p.label)).append('\n')
        for (tag in p.tags) append("tag ").append(oneLine(tag)).append('\n')
    }

    /**
     * The kind, from the text alone.
     *
     * Read on its own because [read] has to know what file to look for before
     * it can decide whether the reference exists at all. Anything that is not
     * the word `model` is a picture, which is the right way round: a meta file
     * from a later build naming a kind this one has never heard of shows as a
     * picture with a missing file and is skipped, rather than crashing the list.
     */
    private fun kindOf(text: String): RefKind {
        for (line in text.lineSequence()) {
            if (line.startsWith("kind ") && line.substring(5).trim() == "model") {
                return RefKind.MODEL
            }
        }
        return RefKind.PICTURE
    }

    private fun decode(id: String, kind: RefKind, text: String, bytes: Long): RefPicture {
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
        return RefPicture(id, kind, label, tags, added, w, h, bytes)
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
        const val MODEL = ".glb"
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

        /**
         * The biggest `.glb` this library will take, in bytes.
         *
         * A scanned bust with 2k textures is two megabytes; a photogrammetry
         * capture straight off a scanner is three hundred. The cap is what
         * stops one careless import filling the tablet, and it is checked
         * before a single byte is copied — see `RefModelImport`.
         */
        const val MAX_MODEL_BYTES = 64L * 1024 * 1024

        /** The poster the pane grabs for the strip. Small: it is a thumbnail. */
        const val POSTER_SIDE = 320
    }
}
