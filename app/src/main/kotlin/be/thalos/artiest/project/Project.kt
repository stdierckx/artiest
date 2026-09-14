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
    /**
     * The rulers and guides on the page, as `GuideText`, and the two numbers
     * that say how hard they pull.
     *
     * On the project and not on a sheet, because that is where they are on the
     * document: a guide is a property of the drawing, and one that vanished
     * when a layer was added would be one nobody would set up. See `GuideSet`.
     */
    val guides: List<String> = emptyList(),
    val guideStrength: Float = 1f,
    val guideReachDoc: Float = 0f,
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

        /**
         * What a sheet's stroke file is called, for a sheet that keeps its
         * strokes. Beside the PNG and named the same way, for the same reason.
         */
        fun strokesFor(index: Int): String = "$STROKES/$index.ink"

        /** The directory sheets live in, inside a project's own directory. */
        const val LAYERS = "layers"

        /** Where `strokes/<n>.ink` lives. See [strokesFor]. */
        const val STROKES = "strokes"

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
    /**
     * Lr4. Whether the pen may touch this sheet, whether it is a thing to look
     * at rather than part of the drawing, and whether its colour is taken out
     * while it is drawn. See `LayerStack.Entry`.
     *
     * All three are properties of the *sheet* and not of the session, which is
     * why they are saved: a reference photograph that came back unlocked, or
     * back in colour, after closing the app would have to be set up again every
     * morning — and the one that came back *exportable* would end up in a PNG.
     */
    val locked: Boolean = false,
    val reference: Boolean = false,
    val desaturate: Boolean = false,
    /**
     * `strokes/<n>.ink` for a sheet that keeps the strokes that made it, null
     * for an ordinary one. See `VectorSheet`.
     *
     * **The PNG stays and is still written.** It is three things at once: what
     * the autosave already writes, what the gallery thumbnail and the `.ora`
     * export read, and what opens the drawing if the stroke file is ever
     * unreadable. Disk is the cheapest thing this project spends — a full
     * drawing's stroke file is under a megabyte beside sheet PNGs that are
     * already hundreds of kilobytes — and the alternative is a drawing whose
     * only copy is in a format one build of one program understands.
     */
    val strokes: String? = null,
    /**
     * The sheet's brush table: `BrushCodec` text, deduplicated, indexed by
     * `StrokeRecord.brush`.
     *
     * Here rather than in the `.ink` file because it is the *sheet's* and not
     * the strokes', because it is text beside other text a person can read, and
     * because the whole argument for the stroke file is that it is nine bytes a
     * sample. Usually two or three entries.
     */
    val brushes: List<String> = emptyList(),
    /**
     * The sheet's clip table, as [PathText]. Usually empty.
     *
     * A stroke drawn into a selection is clipped pixels; a clip table that did
     * not survive a save would let the first edit after reopening re-render
     * that stroke outside its stencil.
     */
    val clips: List<String> = emptyList(),
    /**
     * The sheet's guide table, as `GuideText.encodeSnap`. Usually empty.
     *
     * The clip table's argument word for word. A stroke drawn against a ruler
     * stores raw samples and the snap is applied on the way to the dabs, so a
     * guide table that did not survive a save would let the first repaint after
     * reopening put the line back where the hand wobbled rather than where the
     * ruler was.
     */
    val guides: List<String> = emptyList(),
)
