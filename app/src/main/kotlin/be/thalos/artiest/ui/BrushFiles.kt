package be.thalos.artiest.ui

import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushLibrary
import java.io.File

/**
 * The brushes you have made, as a directory of small text files.
 *
 * ## Why files and not a preference
 *
 * `BrushStore` keeps one brush in `SharedPreferences` and that is right for one
 * brush. A shelf is a list that grows, that a user will want to back up, copy
 * between tablets and eventually receive from somebody else — and the format is
 * already text a person can read, so the thing to store it in is the thing the
 * rest of the world calls a brush: a file.
 *
 * One file per brush, named `<id>.brush`, because that makes a delete a delete
 * and a copy a copy. A single index file would have to be rewritten whenever
 * anything changed, which is the shape of bug where saving your eighth brush
 * loses the other seven.
 *
 * ## What it is careful about
 *
 * **Nothing here throws.** `BrushCodec`'s rule — decoding never throws — has to
 * extend to the directory, because a directory is exactly where a truncated
 * file, a file somebody edited by hand and a file from a later build all come
 * from. A file that will not parse is skipped and the rest of the shelf loads.
 *
 * **An id is a slug, and a slug is a safe file name.** [BrushEntry.slug] keeps
 * only letters and digits, so nothing that reaches [fileFor] can contain a path
 * separator or a `..`; [list] checks the name it read back anyway, because the
 * cheap check is the one that survives a later change to the slug.
 */
class BrushFiles(private val dir: File) {

    /**
     * Every saved brush, by label, case-insensitively.
     *
     * By label rather than by when it was written, because a shelf is read
     * rather than scanned: the brush you want is the one you know the name of,
     * and a list that reorders itself every time you save is a list you have to
     * search again each time.
     */
    fun list(): List<BrushEntry> = runCatching {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?: return emptyList()
        files.take(MAX_BRUSHES)
            .mapNotNull { read(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }.getOrDefault(emptyList())

    /** The library this device has: the shipped three, then whatever is on disk. */
    fun library(): BrushLibrary = BrushLibrary(list())

    /** One file, or null if it is not a brush. The id comes from the file name. */
    private fun read(file: File): BrushEntry? = runCatching {
        if (file.length() > MAX_BYTES) return null
        val id = file.name.removeSuffix(SUFFIX)
        if (id.isEmpty() || id != BrushEntry.slug(id)) return null
        BrushCodec.decodeFile(file.readText(), fallbackId = id)
    }.getOrNull()

    /**
     * Write [entry], replacing one with the same id.
     *
     * Atomically, for `ProjectFiles`' reason at a smaller scale: a half-written
     * brush that replaced a good one is a brush that silently becomes the pen
     * the next time the app starts.
     */
    fun save(entry: BrushEntry): Boolean = runCatching {
        val target = fileFor(entry.id) ?: return false
        dir.mkdirs()
        val tmp = File(dir, entry.id + SUFFIX + TMP)
        tmp.writeText(BrushCodec.encodeFile(entry))
        if (tmp.renameTo(target)) return true
        target.delete() && tmp.renameTo(target)
    }.getOrElse {
        runCatching { File(dir, entry.id + SUFFIX + TMP).delete() }
        false
    }

    fun delete(id: String): Boolean =
        runCatching { fileFor(id)?.delete() ?: false }.getOrDefault(false)

    fun exists(id: String): Boolean = fileFor(id)?.isFile ?: false

    /**
     * [wanted], or [wanted] with the smallest number that is not taken.
     *
     * Same rule `ProjectFiles` uses, and it matters more here: two brushes
     * called "2B" is a normal thing to want, and refusing the second is a worse
     * answer than calling it `2b-2` on disk. The *label* is untouched — the
     * shelf can show two rows called 2B, because a row is told apart by the
     * mark it makes rather than by its name.
     */
    fun freeId(wanted: String, taken: Set<String> = emptySet()): String {
        val base = BrushEntry.slug(wanted)
        if (base !in taken && !exists(base)) return base
        var n = 2
        while (n < MAX_BRUSHES) {
            val id = "$base-$n"
            if (id !in taken && !exists(id)) return id
            n++
        }
        return base
    }

    private fun fileFor(id: String): File? {
        if (id.isEmpty() || id != BrushEntry.slug(id)) return null
        return File(dir, id + SUFFIX)
    }

    private companion object {
        const val SUFFIX = ".brush"
        const val TMP = ".tmp"

        /**
         * A shelf, not a library card catalogue. Thirty is the number
         * `docs/brush-shelf-plan.md` sets as the scrolling test; this is the
         * point at which a directory somebody has copied a thousand files into
         * stops being read rather than the point at which saving is refused.
         */
        const val MAX_BRUSHES = 500

        /** A brush is two kilobytes. Anything larger is not one. */
        const val MAX_BYTES = 1L shl 20
    }
}
