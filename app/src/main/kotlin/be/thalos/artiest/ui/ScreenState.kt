package be.thalos.artiest.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import be.thalos.artiest.canvas.MarqueeShape
import be.thalos.artiest.doc.EraseMode
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectionInfo
import be.thalos.artiest.doc.StrokePickInfo

/**
 * Selecting, picking strokes, the guides, and the scratch objects the overlays
 * draw with. One holder, because ART will not compile the screen otherwise.
 *
 * ## Why a holder and not fifteen `remember`s
 *
 * This is the same argument [LearnerState] makes and the numbers are the point
 * of it. ART refuses to compile any method over **10 000 dex code units** —
 * `kHugeMethodThreshold` — and a method it refuses is a method that runs
 * interpreted for the life of the process. `CanvasScreen` was measured at
 * 11 655 with `dexdump`, and the census said what it was made of: 130 `remember`
 * sites, about ninety code units each, and almost nothing else.
 *
 * So the way down is not to draw less. It is to have fewer *remembered things*,
 * and the cheapest way to have fewer is to put related ones in an object that
 * is remembered once. Fifteen sites become one.
 *
 * ## What it does not change
 *
 * Every field keeps the kind of state it had, and the kinds are not
 * interchangeable — this is the distinction the screen's own comment drew and
 * it still holds:
 *
 * - [selecting] and [marqueeShape] are pressed by hand and recompose the chrome;
 * - [selectionShape] and [pickInfo] change when the render thread republishes;
 * - [outlineTick], [pickTick], [pickRingTick] and [guideTick] are
 *   `MutableIntState` **read inside draw lambdas**, so they invalidate a draw
 *   and recompose nothing. They are handed about as the state object and never
 *   as `.intValue`, or the read moves into composition and an outline animating
 *   at pen rate starts recomposing the toolbar.
 *
 * The screen still spells them `selecting`, `pickInfo` and the rest: it takes
 * local delegates onto these fields (`var selecting by canvas::selecting`),
 * which cost no Composer group at all. That is deliberate — moving the state
 * out should not mean touching two hundred call sites, and a delegate makes the
 * move invisible to everything downstream.
 */
@Stable
class CanvasState(selection: SelectionInfo) {

    // ---- selecting ----------------------------------------------------------

    var selecting by mutableStateOf(false)
    var marqueeShape by mutableStateOf(MarqueeShape.RECTANGLE)
    var marqueeMode by mutableStateOf(SelectMode.NEW)
    var selectionShape by mutableStateOf(selection)

    /** The marching ants. Read in a draw lambda; see the class header. */
    val outlineTick: MutableIntState = mutableIntStateOf(0)

    /**
     * The pixels lifted off a sheet, as the chrome sees them: the rectangle
     * they came from, and a token that changes when a *different* float is
     * lifted so the transform box starts over rather than inheriting the last
     * one's matrix.
     */
    var floatingBox by mutableStateOf<Rect?>(null)
    var floatToken by mutableIntStateOf(0)

    // ---- picking strokes ----------------------------------------------------

    var pickStrokes by mutableStateOf(true)

    /**
     * How much of a stroke the eraser takes on an ink sheet. Ik8.
     *
     * **Whole by default**, because it is the mode a person guesses at: the
     * eraser takes what it touches. To-the-junction is the one an inker learns
     * and then reaches for constantly, and it is one tap away.
     */
    var eraseMode by mutableStateOf(EraseMode.WHOLE)

    /** The highlight, republished by the render thread. See `StrokePickInfo`. */
    var pickInfo by mutableStateOf(StrokePickInfo.NONE)

    /** Ik9's live transform, or null when nothing is being dragged. */
    var pickMatrix by mutableStateOf<Matrix?>(null)

    /** Bumped so the overlay redraws the preview without recomposing. */
    val pickTick: MutableIntState = mutableIntStateOf(0)

    /**
     * Restarts the transform box when it has to forget what it was holding: a
     * new selection, or a drag that has been absorbed into the strokes.
     */
    var pickToken by mutableIntStateOf(0)

    // ---- the guides ---------------------------------------------------------

    /**
     * Bumped whenever a guide changes. Ik13.
     *
     * `GuideSet` is a plain mutable object, deliberately: it is read on the
     * render thread at pen-down and a Compose snapshot would not survive that
     * crossing. So the panel reads a `GuideInfo` rebuilt on this counter, and
     * the overlay reads the set itself inside its draw lambda.
     */
    val guideTick: MutableIntState = mutableIntStateOf(0)

    // ---- what the overlays draw with ---------------------------------------

    /**
     * The colour ring under the pen. Its own counter and not [outlineTick],
     * which three other draw lambdas read: a pick moves at pointer rate and
     * there is no reason for it to re-cut the guides against the viewport.
     */
    val pickRingTick: MutableIntState = mutableIntStateOf(0)

    /** Where the nib is hovering, or unspecified. Read in a draw lambda. */
    val cursorAt = mutableStateOf(Offset.Unspecified)

    /**
     * Document-to-view, rebuilt only when the canvas actually moves.
     *
     * A `Matrix` is mutable native state and this one is written and read on
     * the UI thread, in a draw lambda, so one instance is enough — but it must
     * not be the renderer's, which the render thread concatenates.
     */
    val outlineMatrix = Matrix()

    /**
     * Ik13's two scratch paths. One each for the life of the screen: they are
     * refilled on every pan and zoom frame, and a `Path` per frame is a native
     * allocation per frame.
     */
    val guidePath = Path()
    val guideDim = Path()
}

/**
 * The notes and flags around getting a drawing on and off this tablet: what the
 * last save, export, import or `.ora` said, and whether the gallery is up.
 *
 * A second holder for [CanvasState]'s reason and no other — ten `remember`
 * sites in `CanvasScreen`, which is nine hundred code units of a method ART
 * will not compile past 10 000. Nothing here is clever and nothing here is
 * shared; it is a bag with a name, and the name is the only design in it.
 *
 * The three that are *not* here are [be.thalos.artiest.project.ProjectStore],
 * `ProjectSaver` and the open `Project` itself. They are read by suspend
 * functions declared in the screen that close over half of it, and moving a
 * store is a different change from moving a flag.
 */
@Stable
class ProjectState {

    /** The last thing the saver or the loader said that the user has to see. */
    var note by mutableStateOf("")

    /** The last save, for the instruments. See `readout`. */
    var lastSave by mutableStateOf<be.thalos.artiest.project.SaveResult.Saved?>(null)

    /** Which drawings there are, and whether the gallery is over the paper. */
    var entries by mutableStateOf(emptyList<be.thalos.artiest.project.ProjectFiles.Entry>())
    var gallery by mutableStateOf(false)

    /**
     * Whether the drawing on screen is the whole of what is in the file.
     *
     * **Nothing is written until this is true**, and it is the guard on the one
     * unrecoverable thing this feature can do. Found on the tablet the first
     * time it ran: the open put its sheets in the queue and nothing asked for a
     * frame, so the document stayed empty, so three seconds later the autosave
     * encoded that emptiness over a drawing. The file was blank and there was
     * nothing to undo.
     */
    var attached by mutableStateOf(false)

    /** What the last export or import said. Shown where it happened. */
    var oraNote by mutableStateOf("")

    /** The PNG export, and whether one is running. */
    var export by mutableStateOf<be.thalos.artiest.io.ExportResult?>(null)
    var exporting by mutableStateOf(false)

    /** A picture being brought in, and the one line it leaves behind. */
    var importing by mutableStateOf(false)
    var importNote by mutableStateOf("")
}
