package be.thalos.artiest.project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.doc.StackCompositor
import be.thalos.artiest.doc.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The drawing, onto the disk, while it is still being drawn.
 *
 * ## The shape of it, and the order is the design
 *
 * 1. **Wait for the render thread**, briefly and with a bound —
 *    [Document.awaitStamped]. A save that misses the last stroke is the same
 *    silent failure `PngExporter` was written against, one file down.
 * 2. **Write only the sheets that changed.** See [clean].
 * 3. **One blit under the lock, never an encode.** Allocate the transient
 *    bitmap first, copy on-lock, encode off it. A PNG encode inside [Layer]'s
 *    lock would stall the render thread for a fifth of a second while somebody
 *    is drawing.
 * 4. **The manifest last**, because it is the file that says what the directory
 *    means and it must never name a sheet that is not there yet.
 * 5. **Then prune, then the thumbnail.** Both are tidying, and neither may run
 *    before the manifest that makes them correct.
 *
 * Every step is on [Dispatchers.IO]. [Layer.read] refuses the main thread
 * outright, so there is no accidental version of this.
 *
 * ## What "changed" means, and why it is identity and not an id
 *
 * A sheet's file is named after its **position** — `layers/0.png` — so the
 * question this class has to answer is not "did this layer change" but "is
 * index 3 still the same pixels it was when `layers/3.png` was written". Two
 * things break that: drawing on the sheet, which moves [Layer.revision], and
 * *reordering*, which moves nothing at all and yet makes every file below the
 * move describe the wrong sheet.
 *
 * So [clean] remembers the `Layer` **instance** that was at each index and the
 * revision it was at, and re-encodes unless both still hold. Identity rather
 * than `LayerStack.Entry.id` because the one case that has to work without an
 * id is the one that matters most: a project that has just been *opened* has
 * files on disk that are already correct, and [seed] is how the loader says so
 * — it has the `Layer`s it built, and the ids have not been handed out yet.
 *
 * ## Reading the stack off the render thread
 *
 * `LayerStack`'s shape is the render thread's, and this walks it from IO. That
 * is `PngExporter`'s existing bargain, not a new one: the only thing that
 * changes the shape is a queued operation, the window is a frame, and the cost
 * of being wrong is a save that describes the stack as it was a moment ago and
 * is corrected by the next one. The alternative — taking the render thread's
 * lock for the length of eight PNG encodes — is the thing this whole class is
 * arranged to avoid.
 */
class ProjectSaver(private val files: ProjectFiles) {

    /**
     * One save at a time, and the open takes it too.
     *
     * Two savers writing one directory is the directory being half of each.
     * More to the point, opening a project while a save is in flight would be
     * the save finishing into the project the user has just left — writing the
     * new drawing's pixels under the old drawing's name.
     */
    val lock = Mutex()

    /**
     * What was at each index when it was last written: the sheet, and the
     * revision its pixels were at.
     */
    private var written: List<Pair<Layer, Long>> = emptyList()

    /**
     * Take these sheets, at these revisions, as already on disk.
     *
     * Called by `ProjectLoader` with the layers it has just built from the
     * files, in the order it built them. Without it the first save after every
     * open re-encodes the whole drawing to produce the bytes that are already
     * there — eight sheets, several seconds, for nothing.
     */
    fun seed(layers: List<Layer>) {
        written = layers.map { it to it.revision }
    }

    /** Nothing on disk belongs to what is in the document. After a New. */
    fun forget() {
        written = emptyList()
    }

    /**
     * Write [document] into [project]'s directory and return what it is now.
     *
     * The returned project is the one to hold on to: its revision has gone up,
     * its `modified` is [now], and its sheet list is what the stack actually
     * contains rather than what it contained when it was opened.
     */
    suspend fun save(project: Project, document: Document, now: Long): SaveResult =
        withContext(Dispatchers.IO) {
            lock.withLock { saveInner(project, document, now) }
        }

    private suspend fun saveInner(project: Project, document: Document, now: Long): SaveResult {
        val startNs = System.nanoTime()
        val notYetStamped = document.awaitStamped()

        val stack = document.layers
        val count = stack.size
        if (count == 0) return SaveResult.Failed("the drawing has no sheets")

        val sheets = ArrayList<ProjectSheet>(count)
        val nowWritten = ArrayList<Pair<Layer, Long>>(count)
        var encoded = 0
        // One transient for the whole save, not one per sheet: 27.19 MiB is the
        // largest allocation in the app and the second one is the one that
        // fails. Erased between sheets, because a blit over stale pixels leaves
        // the previous sheet showing through wherever this one is transparent.
        var transient: Bitmap? = null

        try {
            for (i in 0 until count) {
                val entry = stack.entryAt(i)
                val layer = entry.layer
                val revision = layer.revision
                sheets += ProjectSheet(
                    file = Project.fileFor(i),
                    name = entry.name,
                    opacity = entry.opacity,
                    visible = entry.visible,
                    blend = entry.blend,
                )
                nowWritten += layer to revision

                if (clean(i, layer, revision) && files.sheetOf(project.id, i).isFile) continue

                val out = transient ?: allocate(document) ?: return SaveResult.Failed(
                    "there was not enough memory to copy a sheet"
                )
                transient = out
                if (!copy(layer, out)) return SaveResult.Failed("the drawing was closed")
                if (!write(files.sheetOf(project.id, i), out)) {
                    return SaveResult.Failed("sheet ${i + 1} could not be written")
                }
                encoded++
            }

            // Last, and only once every sheet it names is on disk.
            val saved = project.revised(now, sheets, stack.activePosition)
            if (!files.save(saved)) return SaveResult.Failed("the project file could not be written")

            // Only now: these are the files the manifest no longer names.
            files.pruneSheets(project.id, keep = count)
            written = nowWritten

            thumbnail(project, document)

            return SaveResult.Saved(
                project = saved,
                sheetsWritten = encoded,
                notYetStamped = notYetStamped,
                ms = (System.nanoTime() - startNs) / 1_000_000L,
            )
        } finally {
            transient?.recycle()
        }
    }

    /** Whether `layers/<i>.png` is still a picture of this sheet. */
    private fun clean(i: Int, layer: Layer, revision: Long): Boolean {
        val was = written.getOrNull(i) ?: return false
        return was.first === layer && was.second == revision
    }

    private fun allocate(document: Document): Bitmap? = try {
        Bitmap.createBitmap(document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888)
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * The one thing that happens under [Layer]'s lock: a single blit.
     *
     * The erase is outside it. It is a full-page memset and it has nothing to
     * do with the sheet being copied, so doing it inside would be holding the
     * render thread's lock to clean up after the last sheet.
     */
    private fun copy(layer: Layer, out: Bitmap): Boolean {
        out.eraseColor(0)
        val canvas = Canvas(out)
        return layer.read { src -> canvas.drawBitmap(src, 0f, 0f, null) }
    }

    /** Encode to `x.tmp` and rename. See [ProjectFiles.writeAtomically]. */
    private fun write(target: File, bitmap: Bitmap): Boolean =
        files.writeAtomically(target) { tmp ->
            tmp.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw java.io.IOException("compress returned false")
                }
                stream.flush()
            }
        }

    /**
     * What the gallery shows: the whole drawing, small.
     *
     * The screen's own compositor and not a second loop — `PngExporter`'s
     * header records what happened the last time this app had two of those, and
     * they disagreed about the paper.
     *
     * **It is composited at exactly half size and then halved again**, never
     * scaled down in one step. `Thumbnails` carries the reason at length and it
     * was paid for on the tablet: a single big reduction samples a 2x2
     * neighbourhood however far apart the taps are, so a drawing made of pencil
     * lines comes back as an empty card. A half-size composite is itself a
     * 2:1 reduction, which is the one ratio that cannot drop a line — and it
     * costs 6.8 MiB rather than the 27.19 a full-size one would.
     *
     * **The filter is set on the canvas rather than on a `Paint`**, because the
     * paints belong to `StackCompositor` and are none of this class's business.
     * `setDrawFilter` puts `FILTER_BITMAP` on every one of them for the extent
     * of the draw, which is exactly the scope wanted, and a software canvas
     * honours it.
     *
     * A failure here is not a failure of the save. The drawing is on disk; the
     * picture of it is a convenience, and a project with no thumbnail draws a
     * card with its name on it.
     */
    private fun thumbnail(project: Project, document: Document) {
        val long = max(document.widthPx, document.heightPx)
        if (long <= 0) return
        val halfW = (document.widthPx / 2).coerceAtLeast(1)
        val halfH = (document.heightPx / 2).coerceAtLeast(1)
        val scale = halfW.toFloat() / document.widthPx
        val half = try {
            Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return
        }
        val composed = try {
            val canvas = Canvas(half)
            canvas.drawFilter = PaintFlagsDrawFilter(0, Paint.FILTER_BITMAP_FLAG)
            canvas.scale(scale, scale)
            StackCompositor().compose(
                canvas = canvas,
                stack = document.layers,
                paperColor = document.paperColor,
                widthPx = document.widthPx,
                heightPx = document.heightPx,
                wet = null,
                left = 0f,
                top = 0f,
                right = document.widthPx.toFloat(),
                bottom = document.heightPx.toFloat(),
            )
        } catch (e: OutOfMemoryError) {
            half.recycle()
            return
        }
        if (!composed) {
            half.recycle()
            return
        }

        val shrink = (THUMB_LONG_SIDE / long.toFloat()).coerceAtMost(1f)
        val w = (document.widthPx * shrink).roundToInt().coerceAtLeast(1)
        val h = (document.heightPx * shrink).roundToInt().coerceAtLeast(1)
        // Consumes `half`, and every intermediate with it.
        val out = try {
            Thumbnails.reduce(half, w, h)
        } catch (e: OutOfMemoryError) {
            return
        }
        try {
            write(files.thumbnailOf(project.id), out)
        } finally {
            out.recycle()
        }
    }

    companion object {
        /** Big enough to recognise a drawing by on a tablet, small enough to be free. */
        const val THUMB_LONG_SIDE = 400f
    }
}

/**
 * What a save did.
 *
 * A result and not a boolean, for `ExportResult`'s reason: the interesting
 * failures are all different from each other and "it did not work" is not
 * something a user can act on. [Saved.sheetsWritten] is the number this whole
 * class exists to keep small.
 */
sealed interface SaveResult {

    data class Saved(
        val project: Project,
        val sheetsWritten: Int,
        /** Commits the render thread had not stamped when the wait ran out. */
        val notYetStamped: Int,
        val ms: Long,
    ) : SaveResult

    data class Failed(val reason: String) : SaveResult
}
