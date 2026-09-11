package be.thalos.artiest.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.doc.LayerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A project back into the document that is already open.
 *
 * ## Into the document that is already there, and not a new one
 *
 * `Document` is allocated in `onCreate` at the size the device probe allows,
 * and the view, the render thread, the gesture controller and every pointer
 * into it are bound to that instance. Replacing it is a second piece of work
 * with its own lifetime problems — `Layer.close` may only happen after the last
 * render thread has joined — and nothing about opening a drawing needs it.
 *
 * So this builds sheets and hands them over. One queued operation does the
 * swap; see [LayerOp.Open] for why it is one and not twenty.
 *
 * ## What happens when something in the directory is wrong
 *
 * **A sheet whose file will not open still becomes a sheet.** Empty, named,
 * in its place, and reported. The alternative is a drawing that comes back with
 * four layers instead of five and no way to tell which one is missing — and
 * worse, a stack whose positions no longer match the files, so the next save
 * would write the surviving sheets over the wrong names.
 *
 * **A project with no sheets at all opens as one empty sheet.** That is what a
 * project that has been made but never saved from is, and it is also the floor
 * `LayerStack` insists on: a document with no layers has nowhere to put the
 * next stroke.
 *
 * ## The page size
 *
 * A project records the size it was made at. If that is not the size of the
 * document it is being opened into, the sheets are drawn into the **corner** at
 * 1:1 and the difference is reported. Nothing is scaled — a drawing silently
 * resampled is a drawing whose lines have changed weight — and nothing is
 * thrown away, because what does not fit is still in the file and will be there
 * again on a device that can hold it. See `docs/projects-plan.md`, which makes
 * this the one stated limit of the item.
 */
object ProjectLoader {

    /**
     * Open [project] into [document].
     *
     * Call it from the UI thread's scope: the decoding happens on
     * [Dispatchers.IO] and the two lines that touch the document's own
     * bookkeeping happen back on the caller's thread, which is where that
     * bookkeeping lives.
     */
    suspend fun open(
        files: ProjectFiles,
        project: Project,
        document: Document,
        saver: ProjectSaver,
    ): OpenResult {
        val built = withContext(Dispatchers.IO) {
            // The saver's lock, because a save that finished after the swap
            // would write the drawing being left under the name of the one
            // being opened.
            saver.lock.withLock { build(files, project, document) }
        }
        if (built is Built.Failed) return OpenResult.Failed(built.reason)
        val ok = built as Built.Sheets

        // The files on disk are already a picture of these sheets, so the first
        // save after an open writes nothing. Without this it would re-encode the
        // whole drawing to produce bytes that are already there.
        saver.seed(ok.sheets.map { it.layer })

        // The UI thread's half of the swap: the stroke bookkeeping is its list.
        // The undo history is the render thread's and is cleared as the
        // operation lands -- see `Document.resetHistory`.
        document.forgetStrokes()
        document.requestLayers(LayerOp.Open(ok.sheets, project.active))

        return OpenResult.Opened(
            project = project,
            sheets = ok.sheets.size,
            notes = ok.notes,
            ms = ok.ms,
        )
    }

    private sealed interface Built {
        class Sheets(val sheets: List<LayerOp.Open.Sheet>, val notes: List<String>, val ms: Long) : Built
        class Failed(val reason: String) : Built
    }

    private fun build(files: ProjectFiles, project: Project, document: Document): Built {
        val startNs = System.nanoTime()
        val notes = ArrayList<String>()
        val sheets = ArrayList<LayerOp.Open.Sheet>(maxOf(1, project.sheets.size))

        if (project.widthPx != document.widthPx || project.heightPx != document.heightPx) {
            notes += "this drawing was made at ${project.widthPx}x${project.heightPx} and this " +
                "page is ${document.widthPx}x${document.heightPx} — it is placed in the corner, " +
                "and nothing has been cut from the file"
        }

        val described = project.sheets.ifEmpty {
            listOf(ProjectSheet(Project.fileFor(0), "Layer 1"))
        }

        for ((i, described) in described.withIndex()) {
            val layer = document.newLayer()
            val file = File(files.dirFor(project.id), described.file)
            val painted = paint(layer, file)
            if (painted == Painted.CLOSED) {
                // The document went away under us. Release what has been built
                // rather than queueing an operation that will be refused and
                // closed a frame later.
                layer.close()
                for (made in sheets) made.layer.close()
                return Built.Failed("the drawing was closed")
            }
            if (painted == Painted.MISSING && project.sheets.isNotEmpty()) {
                notes += "\"${described.name}\" came back empty — ${described.file} could not be read"
            }
            sheets += LayerOp.Open.Sheet(
                layer = layer,
                name = described.name,
                opacity = described.opacity,
                visible = described.visible,
                blend = described.blend,
            )
        }

        return Built.Sheets(sheets, notes, (System.nanoTime() - startNs) / 1_000_000L)
    }

    private enum class Painted { DRAWN, MISSING, CLOSED }

    /**
     * Decode one file into one sheet, in the corner, at 1:1.
     *
     * `BitmapFactory` and not `ImageDecoder`: this is the app's own file, so
     * there is no EXIF rotation to honour and no content provider to go
     * through, and the older API decodes straight to a software `ARGB_8888`
     * bitmap without being asked. A hardware one could not be drawn into a
     * software canvas at all — see `PictureImporter`, which had to say so.
     *
     * The decoded bitmap is released as soon as it has been drawn. Two full
     * pages exist at once for the width of one blit and no longer, which is
     * what keeps an eight-sheet open inside its memory.
     */
    private fun paint(layer: Layer, file: File): Painted {
        if (!file.isFile) return Painted.MISSING
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return Painted.MISSING

        val drawn = try {
            layer.write { canvas -> canvas.drawBitmap(decoded, 0f, 0f, null) }
        } finally {
            decoded.recycle()
        }
        return if (drawn) Painted.DRAWN else Painted.CLOSED
    }
}

/**
 * What an open did.
 *
 * [Opened.notes] is the same idea as `WorkspaceJson`'s dropped list and is shown
 * the same way: a drawing that came back with a sheet missing has to say so,
 * because the one failure a user cannot work around is the one nothing
 * mentioned.
 */
sealed interface OpenResult {

    data class Opened(
        val project: Project,
        val sheets: Int,
        val notes: List<String>,
        val ms: Long,
    ) : OpenResult

    data class Failed(val reason: String) : OpenResult
}
