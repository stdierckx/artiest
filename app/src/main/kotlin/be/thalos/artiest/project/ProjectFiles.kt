package be.thalos.artiest.project

import java.io.File

/**
 * Projects as directories, and every decision about names, clashes and broken
 * files in one testable place.
 *
 * Split out from anything Android for the reason `WorkspaceFiles` was: naming a
 * copy, getting out of the way of an id that is taken, and what a half-written
 * file does are decisions, and decisions belong in tests that run on a plain
 * JVM against a temporary directory rather than only on a tablet.
 *
 * ## Nothing here throws
 *
 * A directory that cannot be made, a manifest that cannot be read, a directory
 * full of junk: each one is a project that is not in the list, and the app
 * still starts. That is `DockCodec`'s rule and `WorkspaceFiles`' rule, and it
 * matters more here than in either, because this is the one store that holds
 * something the user cannot make again.
 *
 * ## Every write is a rename
 *
 * [writeAtomically] writes `x.tmp` and renames it over `x`. A half-written
 * `project.json` that replaced a good one would be a drawing that comes back
 * with no sheets in it, and the failure would arrive a week later with nothing
 * to point at. `File.renameTo` within one directory on one filesystem is the
 * atomic operation this relies on, and `docs/projects-plan.md` makes it a stop
 * condition: if it turns out not to be atomic here, the save writes a second
 * directory and swaps.
 *
 * The manifest is written **last** and the sheets before it, for the same
 * reason in a different shape: the manifest is the file that says what the
 * directory means, so it must never name a sheet that is not there yet.
 */
class ProjectFiles(private val root: File) {

    /** What the gallery lists: enough to draw a card without decoding a page of pixels. */
    data class Entry(
        val id: String,
        val name: String,
        val modified: Long,
        /** The thumbnail on disk, or null for a project that has not been saved with one. */
        val thumbnail: File?,
    )

    /** Newest first, which is the order a gallery is looked at in. */
    fun list(): List<Entry> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs
            .mapNotNull { dir ->
                val p = load(dir.name)?.project ?: return@mapNotNull null
                Entry(p.id, p.name, p.modified, thumbnailOf(p.id).takeIf { it.isFile })
            }
            .sortedWith(compareByDescending<Entry> { it.modified }.thenBy { it.name.lowercase() })
    }

    fun exists(id: String): Boolean = manifestOf(id).isFile

    /** The project's own directory. Created on demand by [save]. */
    fun dirFor(id: String): File = File(root, Project.slug(id))

    fun manifestOf(id: String): File = File(dirFor(id), Project.MANIFEST)

    fun thumbnailOf(id: String): File = File(dirFor(id), Project.THUMBNAIL)

    /** Where a sheet's pixels go. The name comes from the position, never from the file. */
    fun sheetOf(id: String, index: Int): File = File(dirFor(id), Project.fileFor(index))

    fun load(id: String): ProjectJson.Decoded? {
        val file = manifestOf(id)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val decoded = ProjectJson.decode(text)
        return if (decoded.project == null) null else decoded
    }

    /** The manifest, and only the manifest. The sheets are [ProjectSaver]'s. */
    fun save(project: Project): Boolean = runCatching {
        dirFor(project.id).mkdirs()
        writeAtomically(manifestOf(project.id)) { it.writeText(ProjectJson.encode(project)) }
    }.getOrDefault(false)

    /**
     * A new, empty project. The directory exists when this returns.
     *
     * It has no sheets, and that is not the same as a drawing with no layers:
     * the sheets appear the first time it is saved from a live document, which
     * is the only place pixels come from. A project loaded with no sheets opens
     * the document it is loaded into unchanged — see `ProjectLoader`.
     */
    fun create(name: String, widthPx: Int, heightPx: Int, now: Long): Project? {
        val project = Project(
            id = freeId(Project.slug(name)),
            name = name,
            created = now,
            modified = now,
            widthPx = widthPx,
            heightPx = heightPx,
        )
        return if (save(project)) project else null
    }

    /**
     * A copy under a new name, pixels and all.
     *
     * The whole directory, because a project that copied its manifest and
     * shared its sheets would be two names for one drawing, and the second edit
     * would change both.
     */
    fun duplicate(id: String, name: String, now: Long): Project? {
        val source = load(id)?.project ?: return null
        val copy = source.copy(
            id = freeId(Project.slug(name)),
            name = name,
            revision = 1,
            created = now,
            modified = now,
        )
        val copied = runCatching {
            dirFor(id).copyRecursively(dirFor(copy.id), overwrite = true)
        }.getOrDefault(false)
        if (!copied) return null
        // Over the manifest that came with the pixels, which still carries the
        // old name and the old id.
        return if (save(copy)) copy else null
    }

    /** Everything, including the pixels. There is no way back from this one. */
    fun delete(id: String): Boolean {
        val dir = dirFor(id)
        if (!dir.isDirectory) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * Delete sheet files at or above [keep].
     *
     * What happens after a layer is deleted: the stack is five sheets and the
     * directory still has six PNGs, and the sixth is 2 MB of a drawing nobody
     * can see. Called by the saver once the manifest that stops naming them is
     * safely written.
     */
    fun pruneSheets(id: String, keep: Int) {
        val dir = File(dirFor(id), Project.LAYERS)
        for (file in dir.listFiles().orEmpty()) {
            val n = file.name.removeSuffix(".png").toIntOrNull() ?: continue
            if (n >= keep) runCatching { file.delete() }
        }
    }

    /**
     * Write through a temporary file and rename. See the class header.
     *
     * The temporary lives beside its target rather than in a cache directory,
     * because a rename across filesystems is a copy and a copy is exactly the
     * non-atomic thing this exists to avoid.
     */
    fun writeAtomically(target: File, write: (File) -> Unit): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP)
        write(tmp)
        if (tmp.renameTo(target)) {
            true
        } else {
            // Some filesystems refuse a rename over an existing file. Losing the
            // old copy for the width of one rename is worse than nothing but far
            // better than a half-written file, and it is reported either way.
            target.delete() && tmp.renameTo(target)
        }
    }.getOrElse {
        runCatching { File(target.parentFile, target.name + TMP).delete() }
        false
    }

    /** [wanted], or [wanted] with the smallest number that is not taken. */
    private fun freeId(wanted: String): String {
        if (!exists(wanted) && !dirFor(wanted).exists()) return wanted
        var n = 2
        while ((exists("$wanted-$n") || dirFor("$wanted-$n").exists()) && n < MAX_COPIES) n++
        return "$wanted-$n"
    }

    companion object {
        private const val TMP = ".tmp"

        /** Past this many copies of one name, the name is not the problem. */
        private const val MAX_COPIES = 100
    }
}
