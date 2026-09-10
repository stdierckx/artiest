package be.thalos.artiest.ui

import android.content.Context
import java.io.File

/**
 * The workspaces on this tablet: what there are, which one you are in, and
 * getting one in or out.
 *
 * ## Two stores, on purpose
 *
 * | What | Where | Written when |
 * |---|---|---|
 * | The current workspace's **arrangement** | `SharedPreferences`, a `DockCodec` string | every drag |
 * | The workspace **file** — filter, defaults, name, and the arrangement | `filesDir/workspaces/<id>.json` | switch, rename, export, and on a debounce |
 * | Which workspace is current | `SharedPreferences`, one key | switch |
 *
 * A drag writes the fast path only. Crash after a drag and the arrangement is
 * in preferences and comes back; the file catches up on the next save. Losing
 * that split would mean writing a JSON file on every drop, which is the kind of
 * thing that turns up as a dropped frame six months later and is impossible to
 * find.
 *
 * ## Switching must be instant
 *
 * Load a file, set the docks, apply the filter to the chooser, apply whichever
 * defaults have a subsystem to receive them. No animation, no dialog, no reload
 * of the document. The workspace plan made that a stop condition and it is
 * worth repeating here, where it could be broken: *if switching takes long
 * enough to see, it is a mode change rather than a personality change, and it
 * will not be used.*
 *
 * ## Importing never overwrites
 *
 * [import] always creates a new workspace, with a `-2` on the end when the name
 * clashes. A file from a stranger that could silently replace the workspace you
 * have spent a month arranging is not a file anybody should open, and "are you
 * sure" is not a defence — people say yes.
 *
 * The Android half of this is [Context.getFilesDir] and one preference file.
 * Everything else is [WorkspaceFiles], which takes a directory and can
 * therefore be tested on a plain JVM.
 */
class WorkspaceStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val files = WorkspaceFiles(File(app.filesDir, DIRECTORY))

    /** Every workspace, by name, with the built-in ones first. */
    fun list(): List<WorkspaceFiles.Entry> = files.list()

    fun load(id: String): Workspace? = files.load(id)?.workspace

    fun save(workspace: Workspace) = files.save(workspace)

    /** Which workspace is current. The default one when nothing has been chosen. */
    fun currentId(): String = prefs.getString(KEY_CURRENT, null) ?: DEFAULT_ID

    fun switchTo(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun duplicate(id: String, name: String): Workspace? = files.duplicate(id, name)

    fun delete(id: String) {
        files.delete(id)
        if (currentId() == id) switchTo(DEFAULT_ID)
    }

    /** Read a file somebody sent, without touching what is already here. */
    fun import(text: String): WorkspaceJson.Decoded = files.import(text)

    fun export(id: String): String? = files.load(id)?.workspace?.let(WorkspaceJson::encode)

    companion object {
        private const val PREFS = "chrome"
        private const val KEY_CURRENT = "workspace.current"
        private const val DIRECTORY = "workspaces"

        /** The one that is there before anybody has made one. */
        const val DEFAULT_ID = "everything"
    }
}

/**
 * Workspaces as files in a directory.
 *
 * Split out from [WorkspaceStore] so that everything with a decision in it —
 * naming, clashes, what a corrupt file does — is a plain JVM test against a
 * temporary directory, rather than something only a device can answer.
 *
 * **Nothing here throws.** A directory that cannot be made, a file that cannot
 * be read, a file full of junk: each one is a workspace that is not in the
 * list, and the app still starts. The failure mode this is avoiding is the
 * whole reason `DockCodec` never throws either.
 */
class WorkspaceFiles(private val root: File) {

    /** What the chooser lists: enough to draw a row without reading every file. */
    data class Entry(val id: String, val name: String, val description: String)

    fun list(): List<Entry> {
        val names = root.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: emptyList()
        return names
            .mapNotNull { file ->
                val ws = load(file.name.removeSuffix(SUFFIX))?.workspace ?: return@mapNotNull null
                Entry(ws.id, ws.name, ws.description)
            }
            .sortedBy { it.name.lowercase() }
    }

    fun exists(id: String): Boolean = fileFor(id).isFile

    fun load(id: String): WorkspaceJson.Decoded? {
        val file = fileFor(id)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val decoded = WorkspaceJson.decode(text)
        return if (decoded.workspace == null) null else decoded
    }

    fun save(workspace: Workspace): Boolean = runCatching {
        root.mkdirs()
        fileFor(workspace.id).writeText(WorkspaceJson.encode(workspace))
        true
    }.getOrDefault(false)

    fun delete(id: String): Boolean = runCatching { fileFor(id).delete() }.getOrDefault(false)

    fun duplicate(id: String, name: String): Workspace? {
        val source = load(id)?.workspace ?: return null
        val copy = source.copy(id = freeId(Workspace.slug(name)), name = name, revision = 1)
        return if (save(copy)) copy else null
    }

    /**
     * Take in a file somebody sent, as a **new** workspace.
     *
     * The id in the file is a suggestion. If something is already using it the
     * import gets the next free one — `sketcher`, `sketcher-2`, `sketcher-3` —
     * because an import that overwrote a month of arranging would be the one
     * unrecoverable thing this feature could do.
     */
    fun import(text: String): WorkspaceJson.Decoded {
        val decoded = WorkspaceJson.decode(text)
        val workspace = decoded.workspace ?: return decoded
        val fresh = workspace.copy(id = freeId(workspace.id), revision = 1)
        if (!save(fresh)) {
            return decoded.copy(
                workspace = null,
                dropped = decoded.dropped + "it could not be saved",
            )
        }
        return decoded.copy(workspace = fresh)
    }

    /** [wanted], or [wanted] with the smallest number that is not taken. */
    private fun freeId(wanted: String): String {
        if (!exists(wanted)) return wanted
        var n = 2
        while (exists("$wanted-$n") && n < MAX_COPIES) n++
        return "$wanted-$n"
    }

    /**
     * The file for an id, with the id put through the slug rules first.
     *
     * Belt and braces: every id in the app is already a slug, and this is the
     * one place a string becomes a path. A `../` that got this far would be a
     * file written outside the app's own directory, and the cost of being sure
     * is one function call.
     */
    private fun fileFor(id: String): File = File(root, Workspace.slug(id) + SUFFIX)

    companion object {
        private const val SUFFIX = ".json"

        /** Past this many copies of one name, the name is not the problem. */
        private const val MAX_COPIES = 100
    }
}
