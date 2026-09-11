package be.thalos.artiest.project

import android.graphics.Color
import be.thalos.artiest.doc.LayerBlend
import be.thalos.artiest.doc.LayerStack
import be.thalos.artiest.ui.Workspace

/**
 * A drawing that is kept: a name, a page size, and the sheets it is made of.
 *
 * **This is the thing a `Document` is not.** A `Document` is 217 MiB of live
 * pixels bound to a render thread, a commit queue and an undo history. A
 * project is the small description of what those pixels *are* — where they came
 * from, what each sheet is called, which one the pen is on — and it is the only
 * part of a drawing that is worth writing down in a file a person might read.
 *
 * The reference is `docs/projects-plan.md`. Two things in it are worth
 * repeating here, where they could be broken:
 *
 * **A sheet is identified by its position and nothing else.** `LayerStack`
 * hands out ids at runtime and starts again from 1 in every new stack, so an id
 * written to a file names a different sheet on the next launch. [active] is an
 * index for the same reason.
 *
 * **The pixels are beside this file, not in it.** [ProjectSheet.file] is a path
 * relative to the project's own directory, and the only paths this type will
 * ever produce are `layers/<n>.png` — see [ProjectJson], which refuses anything
 * else that a file might contain.
 */
data class Project(
    /** A slug, the directory name, and not [name]. */
    val id: String,
    val name: String,
    /** Milliseconds since the epoch, from the clock that made it. */
    val created: Long = 0L,
    val modified: Long = 0L,
    /**
     * Goes up on every save.
     *
     * An integer rather than a clock for the reason `Workspace.revision` is one:
     * two tablets in different time zones should not be a problem anybody has
     * to think about to know which of two files is newer.
     */
    val revision: Int = 1,
    val widthPx: Int,
    val heightPx: Int,
    val paperColor: Int = Color.WHITE,
    /** Which sheet the pen is on, counting from the bottom. */
    val active: Int = 0,
    /** Bottom to top, the order they are composited in. */
    val sheets: List<ProjectSheet> = emptyList(),
) {

    /** The same project, one revision on, with the clock moved. */
    fun revised(now: Long, sheets: List<ProjectSheet> = this.sheets, active: Int = this.active) =
        copy(sheets = sheets, active = active, revision = revision + 1, modified = now)

    /** The same project under a new name, keeping its id and its directory. */
    fun renamed(name: String, now: Long): Project =
        copy(name = name, revision = revision + 1, modified = now)

    companion object {
        /** A file name from a display name. One implementation, in [Workspace]. */
        fun slug(name: String): String = Workspace.slug(name)

        /** Long enough for a sentence, short enough for a file name. */
        const val MAX_NAME = 64

        /**
         * What a sheet's file is called: its position, and nothing a stranger
         * chose.
         *
         * The number is the index in [sheets], so a project that has just been
         * opened already has the right names on disk and the first save after
         * it writes nothing. See `docs/projects-plan.md` and [ProjectSheet.file].
         */
        fun fileFor(index: Int): String = "$LAYERS/$index.png"

        /** The directory sheets live in, inside a project's own directory. */
        const val LAYERS = "layers"

        /** What the gallery shows. Composited by the same code the screen uses. */
        const val THUMBNAIL = "thumbnail.png"

        /** The description beside the pixels. */
        const val MANIFEST = "project.json"

        /** More sheets than `LayerStack` will hold. See that class's cap. */
        const val MAX_SHEETS = LayerStack.MAX_LAYERS

        /** The first thing a fresh install is in. */
        const val FIRST_NAME = "Drawing 1"
    }
}

/**
 * One sheet as the file describes it: everything about a layer except its
 * pixels.
 *
 * The fields are `LayerStack.Entry`'s, minus the `Layer` and minus the id — see
 * [Project] for why the id is not here — and they are written in the same order
 * the stack keeps them in, bottom first.
 */
data class ProjectSheet(
    /** Relative to the project's directory. Always `layers/<n>.png`. */
    val file: String,
    val name: String,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val blend: LayerBlend = LayerBlend.NORMAL,
)
