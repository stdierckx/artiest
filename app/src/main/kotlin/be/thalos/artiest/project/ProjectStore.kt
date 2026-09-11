package be.thalos.artiest.project

import android.content.Context
import java.io.File

/**
 * The projects on this tablet: what there are, which one you are in, and making
 * one when there is none.
 *
 * The Android half of the store, and deliberately thin — [ProjectFiles] takes a
 * directory and can therefore be tested on a plain JVM, which is where all the
 * decisions are. This adds `filesDir`, one preference key, and the rule about
 * what a fresh install opens into.
 *
 * ## Where the pixels live, and what that costs
 *
 * `filesDir/projects/`, which is app-private. They are not documents the user
 * manages with a file browser; they are the app's own state, and the export is
 * how work leaves. The consequence is stated here rather than discovered:
 * **uninstalling the app deletes the projects**. That is the reason export is
 * in the same item as the gallery and not two items later.
 *
 * ## A fresh install opens into a drawing, not a question
 *
 * There is always a current project. If there is none on disk one is made,
 * called *Drawing 1*, and the app opens in it. A gallery in front of the paper
 * on a device whose own launcher is already a gallery is a tax on every launch,
 * and the promise this whole item makes is that the drawing is still there when
 * you come back — not that you will be asked which one.
 */
class ProjectStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val files = ProjectFiles(File(app.filesDir, DIRECTORY))

    /** Which project is current, whether or not it still exists. */
    fun currentId(): String? = prefs.getString(KEY_CURRENT, null)

    fun switchTo(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    /**
     * The project to open, whatever has happened to the files.
     *
     * Never null, and the order of the fallbacks is the order of how much the
     * user would mind: the one they were in, then the most recent one they have,
     * then a new one. The app has to open — a drawing program that will not
     * start is a drawing nobody can reach.
     */
    fun current(widthPx: Int, heightPx: Int): Project {
        currentId()?.let { id -> files.load(id)?.project?.let { return it } }
        files.list().firstOrNull()?.let { entry ->
            files.load(entry.id)?.project?.let {
                switchTo(it.id)
                return it
            }
        }
        return make(Project.FIRST_NAME, widthPx, heightPx)
    }

    /**
     * A new project, opened.
     *
     * Falls back to a project that is not on disk if the directory could not be
     * made. Drawing into memory is a bad day; refusing to start is a worse one,
     * and the save that fails will say so where the user can see it.
     */
    fun make(name: String, widthPx: Int, heightPx: Int): Project {
        val now = clock()
        val made = files.create(name, widthPx, heightPx, now)
            ?: Project(
                id = Project.slug(name),
                name = name,
                created = now,
                modified = now,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        switchTo(made.id)
        return made
    }

    /** The next free *Drawing n*, so two new projects never read the same. */
    fun suggestName(): String {
        val taken = files.list().map { it.name }.toSet()
        var n = 1
        while ("$BASE_NAME $n" in taken && n < MAX_NEW) n++
        return "$BASE_NAME $n"
    }

    fun list(): List<ProjectFiles.Entry> = files.list()

    fun load(id: String): Project? = files.load(id)?.project

    fun save(project: Project): Boolean = files.save(project)

    fun rename(project: Project, name: String): Project {
        val renamed = project.renamed(name, clock())
        files.save(renamed)
        return renamed
    }

    fun duplicate(id: String, name: String): Project? = files.duplicate(id, name, clock())

    /**
     * Throw one away, and make sure something is current afterwards.
     *
     * The current id is cleared rather than pointed at a guess: [current] knows
     * how to choose, and it is the one place that knowledge belongs.
     */
    fun delete(id: String) {
        files.delete(id)
        if (currentId() == id) prefs.edit().remove(KEY_CURRENT).apply()
    }

    companion object {
        /** The same preference file the chrome uses. One file, one process. */
        private const val PREFS = "chrome"
        private const val KEY_CURRENT = "project.current"
        private const val DIRECTORY = "projects"

        private const val BASE_NAME = "Drawing"

        /** Past this many unnamed drawings, the name is not the problem. */
        private const val MAX_NEW = 1000

        /**
         * How long after the last change the drawing is written.
         *
         * Three seconds because that is longer than a pause between strokes and
         * shorter than a trip to make tea. It is a *poll* interval as well as a
         * debounce — see [ProjectSaver.dirty] for why the autosave asks rather
         * than being told — so it is also the longest anything can go unsaved
         * while the app is in front of you.
         */
        const val AUTOSAVE_MS = 3_000L
    }
}
