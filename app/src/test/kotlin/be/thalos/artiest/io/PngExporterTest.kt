package be.thalos.artiest.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.provider.MediaStore
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.LayerOp
import be.thalos.artiest.doc.StackCompositor
import be.thalos.artiest.ink.DabRasterizer
import be.thalos.artiest.doc.CommitQueue
import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.Stroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export, checked as pixels and as MediaStore rows rather than as a return
 * value.
 *
 * Every image assertion here is two-sided, for the reason `LayerTest` states at
 * length: under Robolectric's *legacy* graphics shadow a `Canvas` records draw
 * calls instead of rasterizing them and every `getPixel` returns 0, so a file
 * of "this pixel is paper" assertions passes on an app that draws nothing.
 * `@Config(sdk = [34])` and `@GraphicsMode(NATIVE)` together are what stop that,
 * and `the ink is in the PNG where it was drawn` is the tripwire if a
 * Robolectric upgrade ever drops back.
 *
 * The MediaStore half is checked through `ShadowContentResolver`'s recorded
 * statements — what was inserted, what was updated, what was deleted — because
 * the bug being fixed is not "the export throws" but "the export leaves a
 * published, empty row and reports success". Only the statement log can see
 * that, and `the version this replaces publishes an empty file` builds that
 * version longhand and watches it happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// `ShadowContentResolver`'s statement log is deprecated in favour of asserting
// against a real `ContentProvider`. That advice does not reach the case here:
// the property under test is what the exporter does to a row it did *not*
// successfully write to, and a provider of our own would be answering for
// MediaStore rather than observing it. The `RefusingProvider` below is used
// where a provider genuinely is the right instrument.
@Suppress("DEPRECATION")
class PngExporterTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val resolver get() = context.contentResolver
    private val shadow: ShadowContentResolver get() = Shadow.extract(resolver)

    private var doc: Document? = null

    @After
    fun tearDown() {
        doc?.close()
    }

    /**
     * A document whose layer this test can write from the main thread.
     *
     * Small on purpose: these run under a CPU Skia build, and nothing here
     * depends on the document being 2160x3300. The one measurement that does —
     * what the alpha channel costs in the file — is stated in [PngExporter]'s
     * comment with the size it was taken at.
     */
    private fun document(w: Int = 40, h: Int = 24, paper: Int = Color.WHITE): Document =
        Document(w, h, paper, enforceOffMainThread = false).also { doc = it }

    /**
     * Capture the bytes of the next insert.
     *
     * `ShadowContentResolver.insert` with no registered provider fabricates
     * `<collection>/<n>` from a per-test counter starting at 1 (bytecode), so
     * the row this export will get is known before it runs and a stream can be
     * registered against it. The returned `Uri` is asserted against this so the
     * day that changes is a failure here and not a silently empty capture.
     */
    private fun captureNextInsert(): ByteArrayOutputStream {
        val bytes = ByteArrayOutputStream()
        shadow.registerOutputStream(firstInsertUri, bytes)
        return bytes
    }

    private val firstInsertUri: Uri
        get() = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "1")

    private fun exportOf(
        document: Document,
        open: (android.content.ContentResolver, Uri) -> OutputStream? =
            android.content.ContentResolver::openOutputStream,
        waitMs: Long = 20L,
    ): ExportResult = runBlocking {
        PngExporter.export(context, document, "test.png", waitMs, open)
    }

    private fun decode(bytes: ByteArrayOutputStream): Bitmap {
        val array = bytes.toByteArray()
        return BitmapFactory.decodeByteArray(array, 0, array.size)
            ?: error("the exported bytes are not a decodable image (${array.size} B)")
    }

    /** Antialiased black ink in the middle of the document, drawn into the layer. */
    private fun inkInto(document: Document) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }
        document.layer.write { it.drawCircle(20f, 12f, 6f, paint) }
    }

    // --- the image ---------------------------------------------------------

    @Test
    fun `a blank document exports as paper, not as transparency`() {
        val document = document()
        val bytes = captureNextInsert()

        val result = assertIs<ExportResult.Written>(exportOf(document))
        assertEquals(firstInsertUri, result.uri)
        assertEquals(bytes.size().toLong(), result.bytes, "the reported size is what was written")

        val png = decode(bytes)
        assertEquals(40, png.width)
        assertEquals(24, png.height)
        assertEquals(Color.WHITE, png.getPixel(20, 12))
        assertFalse(png.hasAlpha(), "opaque paper should give a 24-bit PNG")
    }

    @Test
    fun `the ink is in the PNG where it was drawn`() {
        val document = document()
        inkInto(document)
        val bytes = captureNextInsert()

        assertIs<ExportResult.Written>(exportOf(document))
        val png = decode(bytes)

        // Two-sided, and the second half is the one that catches a rasterizer
        // that never ran: ink where the dab is, paper where it is not.
        assertEquals(Color.BLACK, png.getPixel(20, 12), "the dab")
        assertEquals(Color.WHITE, png.getPixel(2, 2), "the corner")
        // Partial coverage is something only a real rasterizer produces. If a
        // Robolectric upgrade drops back to the legacy shadow this is what
        // fails, rather than everything above passing vacuously. Searched along
        // the row rather than asserted at one index: where the rim lands is a
        // property of Skia's sampling, and pinning a coordinate would make this
        // a test of that instead of a test of antialiasing.
        val rim = (20..30).map { png.getPixel(it, 12) }
            .firstOrNull { it != Color.BLACK && it != Color.WHITE }
        assertTrue(rim != null, "no antialiased rim anywhere along the dab's radius")
    }

    @Test
    fun `the export is paper-first compositing, pixel for pixel`() {
        val document = document()
        inkInto(document)
        val bytes = captureNextInsert()

        assertIs<ExportResult.Written>(exportOf(document))
        val exported = decode(bytes)

        // Paper down first, ink over it, written out by hand. Until Phase 3
        // the exporter did the opposite — it slid the paper underneath at the
        // end with `DST_OVER`, to keep a full-canvas `drawColor` out of the
        // critical section — and this test existed to pin the claim that the
        // two are the same image. That trick is gone, because it stops being
        // true the moment a sheet blends, so what this now checks is that the
        // straightforward reading of the file is the right one. It is cheap
        // and it is the assertion that fails first if the order ever moves
        // again.
        val longhand = Bitmap.createBitmap(document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(longhand)
        canvas.drawColor(document.paperColor)
        document.layer.read { canvas.drawBitmap(it, 0f, 0f, null) }

        assertContentEquals(pixels(longhand), pixels(exported))
    }

    /**
     * The file is the screen, pixel for pixel.
     *
     * The reason [StackCompositor] exists, stated as the test that would have
     * caught the defect it was extracted to prevent. The screen and the export
     * had a sheet loop each, and the two already differed about where the paper
     * goes — invisibly, because for source-over sheets the difference computes
     * to the same image. This composes the same document through the object the
     * renderer uses and asserts the exported bytes agree.
     *
     * Deliberately not a test of *how* either one is written: it would pass
     * against two copies of the loop that happened to agree, and fail the day
     * they stopped. That is exactly the guarantee wanted, and it is why this is
     * a pixel comparison rather than a check that both call the same method.
     *
     * Three sheets, two of them translucent, one hidden, so that an opacity or
     * a visibility handled differently on the two paths shows up here rather
     * than in somebody's file.
     */
    @Test
    fun `the export is the same image the compositor draws`() {
        val document = document()
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = Color.RED
        document.layer.write { it.drawCircle(12f, 12f, 8f, paint) }

        val second = document.newLayer()
        paint.color = Color.BLUE
        second.write { it.drawCircle(20f, 12f, 8f, paint) }
        document.layers.apply(LayerOp.Add(second, "blue"))
        document.layers.apply(LayerOp.SetOpacity(document.layers.active.id, 0.4f))

        val third = document.newLayer()
        paint.color = Color.GREEN
        third.write { it.drawCircle(28f, 12f, 8f, paint) }
        document.layers.apply(LayerOp.Add(third, "green"))
        document.layers.apply(LayerOp.SetVisible(document.layers.active.id, false))

        // A fourth, blended, because a blend mode is exactly what the two loops
        // used to disagree about: the screen painted paper first and the export
        // slid it underneath at the end, and multiplying against transparency
        // is not multiplying against paper.
        val fourth = document.newLayer()
        paint.color = Color.rgb(120, 120, 120)
        fourth.write { it.drawRect(4f, 4f, 36f, 20f, paint) }
        document.layers.apply(LayerOp.Add(fourth, "shade"))
        document.layers.apply(
            LayerOp.SetBlend(document.layers.active.id, be.thalos.artiest.doc.LayerBlend.MULTIPLY),
        )

        val onScreen = Bitmap.createBitmap(
            document.widthPx, document.heightPx, Bitmap.Config.ARGB_8888,
        )
        StackCompositor().compose(
            Canvas(onScreen),
            document.layers,
            document.paperColor,
            document.widthPx,
            document.heightPx,
            wet = null,
            left = 0f,
            top = 0f,
            right = document.widthPx.toFloat(),
            bottom = document.heightPx.toFloat(),
        )

        val bytes = captureNextInsert()
        assertIs<ExportResult.Written>(exportOf(document))
        assertContentEquals(pixels(onScreen), pixels(decode(bytes)))
    }

    // --- the stack ----------------------------------------------------------

    /**
     * The export is the picture on the screen, which since the stack arrived
     * means every sheet and not the top one.
     *
     * Written as three sheets with three colours so that a compositor which
     * copies each one with `SRC` — the shape the single-layer version had, and
     * the natural thing to leave behind — produces an image made only of the
     * last colour, which is exactly what this asserts against.
     */
    @Test
    fun `every visible sheet reaches the PNG, bottom to top`() {
        val document = document()
        val paint = Paint().apply { isAntiAlias = false }

        paint.color = Color.RED
        document.layer.write { it.drawRect(0f, 0f, 40f, 24f, paint) }

        document.layers.apply(LayerOp.Add(document.newLayer(), "middle"))
        paint.color = Color.GREEN
        document.layer.write { it.drawRect(0f, 0f, 20f, 24f, paint) }

        document.layers.apply(LayerOp.Add(document.newLayer(), "top"))
        paint.color = Color.BLUE
        document.layer.write { it.drawRect(0f, 0f, 10f, 24f, paint) }

        val bytes = captureNextInsert()
        assertIs<ExportResult.Written>(exportOf(document))
        val out = decode(bytes)

        assertEquals(Color.BLUE, out.getPixel(5, 12), "the top sheet is missing")
        assertEquals(Color.GREEN, out.getPixel(15, 12), "the middle sheet is missing")
        assertEquals(Color.RED, out.getPixel(30, 12), "the bottom sheet is missing")
    }

    /** A hidden sheet is hidden in the file too, or the export is not the picture. */
    @Test
    fun `a hidden sheet is not exported`() {
        val document = document()
        val paint = Paint().apply { isAntiAlias = false; color = Color.RED }
        document.layer.write { it.drawRect(0f, 0f, 40f, 24f, paint) }

        document.layers.apply(LayerOp.Add(document.newLayer(), "top"))
        paint.color = Color.BLUE
        document.layer.write { it.drawRect(0f, 0f, 40f, 24f, paint) }
        document.layers.apply(LayerOp.SetVisible(document.layers.activeId, false))

        val bytes = captureNextInsert()
        assertIs<ExportResult.Written>(exportOf(document))
        assertEquals(Color.RED, decode(bytes).getPixel(20, 12), "a hidden sheet was exported")
    }

    /**
     * A sheet at half opacity exports at half opacity. Half of blue over red
     * on opaque white is the same arithmetic the screen does, so the assertion
     * is on the mix rather than on either colour.
     */
    @Test
    fun `a sheet's opacity reaches the file`() {
        val document = document()
        val paint = Paint().apply { isAntiAlias = false; color = Color.RED }
        document.layer.write { it.drawRect(0f, 0f, 40f, 24f, paint) }

        document.layers.apply(LayerOp.Add(document.newLayer(), "top"))
        paint.color = Color.BLUE
        document.layer.write { it.drawRect(0f, 0f, 40f, 24f, paint) }
        document.layers.apply(LayerOp.SetOpacity(document.layers.activeId, 0.5f))

        val bytes = captureNextInsert()
        assertIs<ExportResult.Written>(exportOf(document))
        val mixed = decode(bytes).getPixel(20, 12)
        assertTrue(Color.red(mixed) in 100..155, "red came out at ${Color.red(mixed)}")
        assertTrue(Color.blue(mixed) in 100..155, "blue came out at ${Color.blue(mixed)}")
    }

    @Test
    fun `a translucent paper keeps the alpha channel`() {
        // The negative control for `setHasAlpha(false)`. Opaque paper is what
        // Phase 1 ships and the branch above asserts; this is the other side of
        // the condition, and without it the conditional is untested code that
        // could be an unconditional call.
        val document = document(paper = 0x80FFFFFF.toInt())
        val bytes = captureNextInsert()

        assertIs<ExportResult.Written>(exportOf(document))
        assertTrue(decode(bytes).hasAlpha(), "a translucent paper cannot be flattened to 24-bit")
    }

    @Test
    fun `the layer is still transparent after an export`() {
        val document = document()
        inkInto(document)

        assertIs<ExportResult.Written>(exportOf(document))

        // The alpha-carrying invariant, checked against the one class in the app
        // that has a reason to want an opaque layer. Paper white belongs in the
        // exported copy and nowhere else; baking it in here looks identical on
        // screen and costs Phase 3's layer stack and Phase 4's `.ora`.
        var corner = -1
        var dab = -1
        document.layer.read {
            corner = it.getPixel(2, 2)
            dab = it.getPixel(20, 12)
        }
        assertEquals(0, Color.alpha(corner), "paper was composited into the layer")
        assertEquals(255, Color.alpha(dab), "and the ink went missing, so the check above is vacuous")
    }

    // --- the render-thread skew --------------------------------------------

    @Test
    fun `a stroke the render thread has not stamped is missing from the PNG, and the result says so`() {
        val document = document()
        document.commitStroke(oneDab())
        val bytes = captureNextInsert()

        // Nothing drains the queue: no view, no surface, no render thread. This
        // is a backgrounded app, and it is the case in which the wait cannot
        // succeed rather than one in which it was not tried.
        val result = assertIs<ExportResult.Written>(exportOf(document))

        assertEquals(1, document.strokeCount, "the document counts the stroke")
        assertEquals(1, result.notYetStamped, "and the export says the pixels do not have it")
        assertEquals(Color.WHITE, decode(bytes).getPixel(20, 12), "so the PNG is paper there")
        assertTrue(result.waitMs >= 20L - 5L, "the wait ran to its bound, got ${result.waitMs} ms")
    }

    @Test
    fun `a stroke the render thread has stamped is in the PNG`() {
        val document = document()
        document.commitStroke(oneDab())
        drain(document)
        val bytes = captureNextInsert()

        val result = assertIs<ExportResult.Written>(exportOf(document))

        // The positive control for the test above. Without it that one passes
        // on an exporter that never draws anything at all.
        assertEquals(0, result.notYetStamped)
        assertEquals(1, result.strokes)
        assertEquals(Color.BLACK, decode(bytes).getPixel(20, 12))
        assertEquals(0L, result.waitMs, "an empty queue is not waited on")
    }

    // --- the failure paths --------------------------------------------------

    @Test
    fun `a null stream is a failure, and leaves no row behind`() {
        val document = document()

        val result = assertIs<ExportResult.Failed>(exportOf(document, open = { _, _ -> null }))

        assertEquals(ExportStage.OPEN, result.stage)
        assertEquals(1, shadow.insertStatements.size, "the row was created")
        assertEquals(
            listOf(firstInsertUri),
            shadow.deleteStatements.map { it.uri },
            "and taken away again",
        )
        assertTrue(
            shadow.updateStatements.isEmpty(),
            "nothing was published: IS_PENDING must stay 1 on a failure",
        )
    }

    @Test
    fun `the version this replaces publishes an empty file`() {
        // `:spike`'s SessionExporter, longhand, against the same failure. It is
        // four lines and every one of them is reasonable; the bug is only in
        // what they do together. Without this the test above is an assertion
        // about a thing that might never have been possible.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "spike.png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val stream: OutputStream? = null // what openOutputStream returned
        stream?.use { it.write(ByteArray(1)) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        assertEquals(1, shadow.updateStatements.size, "published")
        assertEquals(
            0,
            shadow.updateStatements.first().contentValues!!.getAsInteger(MediaStore.Images.Media.IS_PENDING),
        )
        assertTrue(shadow.deleteStatements.isEmpty(), "and nothing cleaned up")
        // The caller's view of this is a non-null Uri. That is the whole defect:
        // a success return, a visible gallery entry, and no bytes.
        assertNotEquals(Uri.EMPTY, uri)
    }

    @Test
    fun `MediaStore refusing the row is a failure and not a crash`() {
        ShadowContentResolver.registerProviderInternal(MEDIA_AUTHORITY, RefusingProvider(insert = null))
        val document = document()

        val result = assertIs<ExportResult.Failed>(exportOf(document))

        assertEquals(ExportStage.INSERT, result.stage)
    }

    @Test
    fun `a row that cannot be published is deleted rather than left pending`() {
        // Inserts fine, writes fine, refuses the update. An invisible pending
        // row is worse than no row: it holds the name, it never appears in the
        // gallery, and nothing ever cleans it up.
        val provider = RefusingProvider(insert = firstInsertUri, updated = 0)
        ShadowContentResolver.registerProviderInternal(MEDIA_AUTHORITY, provider)
        val document = document()

        val result = assertIs<ExportResult.Failed>(exportOf(document))

        assertEquals(ExportStage.PUBLISH, result.stage)
        assertEquals(1, provider.deletes, "the pending row was dropped")
    }

    @Test
    fun `a closed document fails before it touches MediaStore`() {
        val document = document()
        document.close()

        val result = assertIs<ExportResult.Failed>(exportOf(document))

        assertEquals(ExportStage.LAYER_CLOSED, result.stage)
        // The order matters and this is what pins it: the layer is copied
        // before the row is created, so a failure at the pixels leaves nothing
        // in MediaStore to clean up.
        assertTrue(shadow.insertStatements.isEmpty(), "no row for a document with no pixels")
    }

    // --- the name -----------------------------------------------------------

    @Test
    fun `the file name is the same in every locale`() {
        val fixed = Date(1_757_000_000_000L)
        val before = Locale.getDefault()
        try {
            Locale.setDefault(ARABIC_DIGITS)
            val name = PngExporter.defaultName(fixed)
            assertTrue(name.startsWith("artiest-") && name.endsWith(".png"), name)
            val stamp = name.removePrefix("artiest-").removeSuffix(".png")
            assertTrue(
                stamp.all { (it in '0'..'9') || it == '-' },
                "ASCII digits only, got $name",
            )
            // The control. `SimpleDateFormat` with no locale takes the default
            // one, and under this locale that is not ASCII — so the explicit
            // `Locale.US` in `defaultName` is load-bearing and not decoration.
            val defaulted = SimpleDateFormat("yyyyMMdd-HHmmss").format(fixed)
            assertNotEquals(
                defaulted,
                stamp,
                "this locale formats digits like the default one; pick another",
            )
        } finally {
            Locale.setDefault(before)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun oneDab(): Stroke = Stroke.copyOf(
        dabs = floatArrayOf(20f, 12f, 6f, 1f, 0f, 1f),
        dabCount = 1,
        colorArgb = Color.BLACK,
        antiAlias = true,
        bounds = Bounds.of(14f, 6f, 26f, 18f),
    )

    /** What `InkSurfaceView`'s commit sink does, on this thread. */
    private fun drain(document: Document) {
        val rasterizer = DabRasterizer(document.widthPx, document.heightPx)
        document.drainCommits(object : CommitQueue.Sink {
            override fun onStroke(stroke: Stroke) {
                document.snapshotBeforeStroke(stroke.bounds)
                document.layer.write { rasterizer.drawDry(it, stroke) }
            }

            override fun onClear() {
                document.snapshotBeforeClear()
                document.layer.blank()
            }

            override fun onLayers(op: be.thalos.artiest.doc.LayerOp) {
                document.layers.apply(op)
            }

            override fun onSelect(op: be.thalos.artiest.doc.SelectOp) {
                document.selection.apply(op)
            }

            override fun onFloat(op: be.thalos.artiest.doc.FloatOp) {
                document.applyFloat(op)
            }

            override fun onUndo() {
                document.applyUndo()
            }

            override fun onRedo() {
                document.applyRedo()
            }
        })
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    private fun hex(argb: Int): String = argb.toUInt().toString(16)

    /** Says no at one chosen step, and counts the deletes it is asked for. */
    private class RefusingProvider(
        private val insert: Uri?,
        private val updated: Int = 1,
    ) : ContentProvider() {
        var deletes = 0
            private set

        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = insert
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = updated
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            deletes++
            return 1
        }

        override fun query(
            u: Uri,
            p: Array<out String>?,
            s: String?,
            a: Array<out String>?,
            o: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
    }

    private companion object {
        const val MEDIA_AUTHORITY = "media"

        /**
         * Arabic with the `nu-arab` numbering system, which formats digits as
         * Arabic-Indic. nl-BE — the locale the project's serialization rule was
         * written against — would not do here: it changes the decimal separator
         * and not the digits, so an all-numeric date pattern comes out
         * identical and the control below could never fail.
         */
        val ARABIC_DIGITS: Locale = Locale.forLanguageTag("ar-EG-u-nu-arab")
    }
}
