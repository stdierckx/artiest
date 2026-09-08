package be.thalos.artiest.io

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import be.thalos.artiest.doc.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The drawing, as a PNG in `Pictures/Artiest/`.
 *
 * Four things happen here and the order between them is the design:
 *
 * 1. **Wait for the render thread**, briefly and with a bound. See [export].
 * 2. **Copy the layer on-lock**, into a bitmap allocated *before* the lock is
 *    taken, with nothing else inside the critical section.
 * 3. **Composite the paper off-lock**, under the ink rather than over it.
 * 4. **Encode and write off-lock**, through a stream whose failure is a result
 *    and not a shrug.
 *
 * **Step 2 is narrower than the plan specified, and deliberately.** The plan
 * says the export "takes `layerLock`, allocates one transient document-sized
 * `Bitmap`, `drawColor(paperWhite)` then `drawBitmap(layer, …)`, releases the
 * lock". That puts a 27.2 MiB allocation, a full-canvas `drawColor` and a
 * composite inside a lock the render thread needs to stamp a stroke — and
 * `Layer`'s own contract, written later, already forbids the first of those
 * outright ("no second `Bitmap` allocation" while holding it). `Layer.read`
 * hands out the `Bitmap` rather than a `Canvas` precisely so this can be one
 * blit; taking that offer is what keeps the stall the export imposes on a
 * concurrent commit to a single copy.
 *
 * Both versions were built and timed on the tablet, back to back in the same
 * export so neither gets a warm allocator the other paid for:
 *
 * ```
 *                     on-lock        of which
 * plan's shape     22.0-25.8 ms   alloc 0.07  drawColor 12.9-13.8  blit 9.1-9.9
 * shipped          13.6-14.8 ms   one drawBitmap
 * ```
 *
 * So the plan's critical section is about 1.7x the shipped one, and effectively
 * all of the difference is a full-canvas `drawColor` over 7.1 Mpx that does not
 * need the lock at all. 14 ms is still most of a frame: a commit that lands
 * during an export waits for it, and the cost of pressing Save is at worst one
 * dropped frame at the next pen-up. 24 ms would be two.
 *
 * **Step 3 is `DST_OVER`, not `drawColor` then `drawBitmap`.** Same pixels —
 * `dst + src*(1 - dstA)` with an opaque `src` is `ink + paper*(1 - inkA)`,
 * which is what painting the ink over the paper computes — but it does not
 * need the paper to be down *first*, so the composite can happen after the
 * lock is released rather than inside it. The equality is not obvious enough to
 * leave as a comment: `PngExporterTest` builds the paper-first version longhand
 * and asserts the two agree pixel for pixel.
 *
 * **The layer is never touched.** Paper white is composited into the transient
 * copy and never into the document, which is [be.thalos.artiest.doc.Layer]'s
 * alpha-carrying invariant and the one this class is most placed to break: it
 * is the only code in the app that legitimately wants an opaque image, and the
 * shortest way to get one is to draw white into the layer and blit it out. That
 * costs nothing today and costs Phase 3's layer stack and Phase 4's `.ora`.
 *
 * `suspend` on `Dispatchers.IO`, which is not decoration: the encode measured
 * 177 ms on the host for a full-size layer, and `Layer.read` refuses the main
 * thread outright, so there is no version of this that runs where the UI does.
 */
object PngExporter {

    /**
     * Export [document] and return what happened.
     *
     * **The wait, and why an exporter has one.** `Document.commitStroke` records
     * the stroke's bounds on the UI thread and *queues* its pixels for the
     * render thread; the layer receives them at the next multi-buffered render.
     * W10 settled that skew as harmless on the grounds that nothing reads both
     * halves — "`PngExporter` reads the layer, undo reads the bounds". That is
     * true of the code and false of the user, who lifts the pen and presses
     * Save. Copy the layer in that window and the PNG is missing the last
     * stroke, the document's own count says it is there, and nothing anywhere
     * reports a problem. It is the same silent shape as the zero-byte file, one
     * layer down.
     *
     * The window is normally under a frame, so the fix is to wait for it: poll
     * [Document.pendingCommits] for up to [waitMs]. Polling rather than a
     * completion latch because the render thread belongs to graphics-core and
     * the only signal is a callback on the view — an exporter that took a
     * dependency on the view to get it would be coupling the file format to the
     * `SurfaceView` for one bit. At 2 ms granularity against a 16.7 ms frame
     * the poll costs one wakeup.
     *
     * **The caller still has to ask for that render.** Nothing here can: the
     * renderer is the UI thread's. If the queue is non-empty because there is no
     * surface at all — the app is backgrounded, or the export is a scripted one
     * — the wait runs out and the export proceeds anyway, reporting the
     * shortfall in [ExportResult.Written.notYetStamped]. Exporting what there is
     * and saying so beats both alternatives: refusing to save a drawing the user
     * can see, or saving it and keeping quiet.
     *
     * **How much of this is reachable today, measured rather than assumed.**
     * Every path that queues a commit already asks for a render — a pen-up
     * calls `commit()`, Clear calls `redrawDry` — so with a live surface the
     * queue drains within a frame, and on the tablet the wait was 0 ms on every
     * export, including one fired as close behind a Clear as two `input tap`s
     * can be. The state that reliably leaves commits queued is having no
     * surface, and that is also the state in which nothing can press the
     * button. So this is a guarantee and a report rather than a save anyone has
     * watched happen — which is worth saying plainly, because W15 binds export
     * to more than a button and `PngExporterTest` is where the case is real.
     *
     * **[open] is a parameter and its default is the production path.** It is
     * there because the failure this whole class was written against — a null
     * from `openOutputStream` — cannot be produced any other way under test:
     * Robolectric's `ContentResolver` catches `FileNotFoundException` and
     * `SecurityException` inside `openOutputStream` and hands back a no-op
     * stream instead (bytecode, `ShadowContentResolver$1`), so the shadow
     * *never* returns null and never throws. A branch that cannot be reached in
     * a test is a branch nobody has run, and this is the branch `:spike` gets
     * wrong. Nothing in the app passes it.
     */
    suspend fun export(
        context: Context,
        document: Document,
        displayName: String = defaultName(),
        waitMs: Long = DEFAULT_WAIT_MS,
        open: (ContentResolver, Uri) -> OutputStream? = ContentResolver::openOutputStream,
    ): ExportResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()

        val waitStartNs = System.nanoTime()
        val notYetStamped = awaitStamped(document, waitMs)
        val waitNs = System.nanoTime() - waitStartNs

        // Before the lock, and this is the line the plan gets wrong. It is also
        // the only allocation here big enough to fail, so it is the only one
        // with a catch: unlike the layer's — allocated once per document, at
        // 0.6% of free memory, where a result nobody branches on is a result
        // nobody surfaces — this one is user-triggered, repeatable, and its
        // failure has an obvious thing to say. Killing the app on a Save button
        // is not it.
        val out = try {
            Bitmap.createBitmap(document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return@withContext ExportResult.Failed(
                ExportStage.ALLOCATE,
                "${document.widthPx}x${document.heightPx}: ${e.message ?: "OutOfMemoryError"}",
            )
        }

        try {
            val canvas = Canvas(out)
            val copyStartNs = System.nanoTime()
            // SRC and not the default SRC_OVER. Onto a fresh transparent bitmap
            // the two are identical — `src + 0*(1-a)` — so this is about what
            // the line means rather than what it computes today: it is a copy,
            // and it stays a copy if the destination is ever reused.
            val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val read = document.layer.read { canvas.drawBitmap(it, 0f, 0f, copy) }
            val copyNs = System.nanoTime() - copyStartNs
            if (!read) {
                return@withContext ExportResult.Failed(
                    ExportStage.LAYER_CLOSED,
                    "the document was closed",
                )
            }

            // Off-lock from here. Paper under the ink; see the class header for
            // why this is the same image as paper first.
            canvas.drawColor(document.paperColor, PorterDuff.Mode.DST_OVER)
            // An opaque paper leaves every pixel at alpha 255, and saying so
            // makes the encoder write a 24-bit PNG instead of a 32-bit one with
            // a channel that is uniformly 0xff. Measured, because the obvious
            // guess is wrong in both directions: on an ink-covered document it
            // is 547,826 bytes down to 468,416, a seventh — but on a *blank*
            // one it saves two bytes, because a constant channel is exactly
            // what PNG's row filters already reduce to nothing. So the saving
            // is real and it is proportional to how much was drawn.
            // Conditional because a translucent paper colour would make it a
            // lie: the encoder would then write premultiplied colours as if
            // they were straight.
            if (Color.alpha(document.paperColor) == 255) out.setHasAlpha(false)

            write(context, out, displayName, document, notYetStamped, waitNs, copyNs, startNs, open)
        } finally {
            // Always, including on every failure path above: 27.2 MiB held by a
            // failed export until the next GC is the kind of thing that only
            // shows up when someone presses Save twice.
            out.recycle()
        }
    }

    /**
     * Poll until the render thread has stamped everything queued, or [waitMs]
     * runs out. Returns what is still outstanding.
     */
    private suspend fun awaitStamped(document: Document, waitMs: Long): Int {
        if (document.pendingCommits == 0) return 0
        val deadlineNs = System.nanoTime() + waitMs * 1_000_000L
        while (document.pendingCommits > 0 && System.nanoTime() < deadlineNs) {
            delay(POLL_MS)
        }
        return document.pendingCommits
    }

    /**
     * Insert, write, publish — and delete the row on any failure.
     *
     * The whole method is the fix to `:spike`'s bug, so it is worth being
     * explicit about the shape. `insert` with `IS_PENDING = 1` creates a row the
     * gallery does not show yet; the update to 0 is what publishes it. `:spike`
     * runs that update unconditionally, which means a null stream — the app has
     * no rights, the volume vanished, storage is full — publishes an empty file
     * and returns its `Uri`. Here the update happens on exactly one path, the
     * one where the bytes are already written, and every other path calls
     * `delete` so that a failed export leaves no orphan row.
     *
     * No permission is requested or needed: `RELATIVE_PATH` into the app's own
     * MediaStore-owned collection has required none since API 29, which is this
     * project's floor. `:spike` proved that on this tablet in Phase 0, which is
     * why the export path was never a risk item.
     */
    private fun write(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        document: Document,
        notYetStamped: Int,
        waitNs: Long,
        copyNs: Long,
        startNs: Long,
        open: (ContentResolver, Uri) -> OutputStream?,
    ): ExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri: Uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return ExportResult.Failed(ExportStage.INSERT, "MediaStore refused the row")

        val encodeStartNs = System.nanoTime()
        val bytes: Long = try {
            val stream = open(resolver, uri)
                ?: return failAndDelete(resolver, uri, ExportStage.OPEN, "openOutputStream was null")
            val counted = CountingOutputStream(stream)
            counted.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    return failAndDelete(resolver, uri, ExportStage.ENCODE, "compress returned false")
                }
                it.flush()
            }
            counted.count
        } catch (e: Exception) {
            return failAndDelete(
                resolver, uri, ExportStage.OPEN, e.javaClass.simpleName + ": " + (e.message ?: ""),
            )
        }
        val encodeNs = System.nanoTime() - encodeStartNs

        // A zero-byte file is the exact artifact of the bug this replaces, so it
        // is refused here even though no path above can produce one: compress
        // returning true after writing nothing would otherwise publish it.
        if (bytes == 0L) {
            return failAndDelete(resolver, uri, ExportStage.ENCODE, "compress wrote 0 bytes")
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        val published = try {
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            0
        }
        if (published == 0) {
            return failAndDelete(resolver, uri, ExportStage.PUBLISH, "IS_PENDING stayed 1")
        }

        return ExportResult.Written(
            uri = uri,
            bytes = bytes,
            strokes = document.strokeCount,
            notYetStamped = notYetStamped,
            waitMs = waitNs / 1_000_000L,
            copyMs = copyNs / 1_000_000L,
            encodeMs = encodeNs / 1_000_000L,
            totalMs = (System.nanoTime() - startNs) / 1_000_000L,
        )
    }

    /**
     * Drop the row and report. The delete is best-effort by necessity — if the
     * resolver is the thing that is broken it will fail here too — but the
     * failure it is reported against is already the return value, so there is
     * nothing to add and nothing to hide.
     */
    private fun failAndDelete(
        resolver: ContentResolver,
        uri: Uri,
        stage: ExportStage,
        detail: String,
    ): ExportResult {
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            // Deliberately swallowed: see above.
        }
        return ExportResult.Failed(stage, detail)
    }

    /**
     * `artiest-20260908-142530.png`.
     *
     * `Locale.US` explicitly. The pattern here is all digits so no locale can
     * change it, but the project has exactly one rule about formatting —
     * default-locale formatting is banned, and an `:engine` test pins it under
     * nl-BE — and a filename that is *nearly* covered by it is a worse place to
     * make an exception than to just pass the locale.
     */
    fun defaultName(now: Date = Date()): String =
        "artiest-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".png"

    /**
     * Counts what actually reached the stream.
     *
     * `Bitmap.compress` returns a boolean and no size, and the size is the one
     * number that distinguishes a real export from the empty file this class
     * exists to stop publishing. Overriding the array form as well is not
     * pedantry: `FilterOutputStream.write(byte[], int, int)` loops calling
     * `write(int)` one byte at a time, so inheriting it would make a 27 MiB
     * encode 27 million virtual calls.
     */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len.toLong()
        }
    }

    /** `Pictures/Artiest/`, which MediaStore creates on demand. */
    private val RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/Artiest"

    private const val MIME_PNG = "image/png"

    /**
     * Long enough for a frame that has already been asked for, short enough
     * that a backgrounded export is not a hang. Fifteen frames at 60 Hz.
     */
    const val DEFAULT_WAIT_MS = 250L

    private const val POLL_MS = 2L
}
