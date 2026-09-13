package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import be.thalos.artiest.engine.brush.Tip
import be.thalos.artiest.engine.brush.TipLibrary
import java.io.File

/**
 * The brush tips this process has, and the one place that turns a PNG into one.
 *
 * ## Why this is process-wide, against the repo's habit
 *
 * Almost everything in this app is threaded through explicitly, and a global is
 * normally the wrong answer. Three facts make a tip the exception.
 *
 * 1. **A tip is immutable and shared.** It is read by the render thread while a
 *    stroke is down and by the swatch renderer on a background thread, and it
 *    never changes after it is registered. That is the one shape where sharing
 *    costs nothing to reason about.
 * 2. **There is exactly one sensible copy.** A second [TipLibrary] would hand
 *    out the same [Tip.slot] numbers to different pictures, and those numbers
 *    are cache keys — two libraries means a dab drawn with the wrong nib, which
 *    is a bug nobody would find by reading either half.
 * 3. **The alternative is a parameter on eight signatures** that exist to draw
 *    a stroke and have no other reason to know that a tip can be a file.
 *
 * ## What a tip file looks like
 *
 * A PNG, and this decides what its pixels mean by looking at them:
 *
 * - **A usable alpha channel → the alpha is the tip.** That is how a tip drawn
 *   in any modern editor is stored.
 * - **No usable alpha → the tip is `255 - grey`.** That is GIMP's `.gbr` and
 *   Krita's own convention, where a brush is black ink on white paper: the
 *   darkest pixel is the one that paints most. Reading such a file as alpha
 *   gives a solid square, which is the failure this branch exists to prevent.
 *
 * **"Usable" is a fraction and not "any", and that is worth the extra pass.**
 * The first version of this asked whether *any* pixel was less than opaque, and
 * the bundle broke it immediately: `oil_knife.png` is a grey-plus-alpha PNG
 * whose picture is entirely in the grey channel and whose alpha is 255 for all
 * but 0.004% of its 90,000 pixels. Four stray pixels were enough to send it
 * down the alpha branch and make a 300-pixel solid slab out of a palette knife.
 * So the question is whether the alpha channel carries a *mark* — [ALPHA_FLOOR]
 * of the picture translucent — and a rim of antialiasing on the smallest
 * plausible tip clears that by three times over.
 *
 * See `docs/brushes-plan.md`, Wb5.
 */
object Tips {

    /** Every tip loaded. Empty until something calls [loadDirectory]. */
    val library = TipLibrary()

    /** The tip called [id], or null — which every caller reads as "no picture". */
    fun find(id: String?): Tip? = library.find(id)

    /**
     * Load every `*.png` in [dir] as a tip named after its file.
     *
     * Returns how many are held afterwards. A file that will not decode is
     * skipped rather than fatal, for `BrushFiles`' reason: a directory is
     * exactly where a truncated file comes from, and one bad tip must not stop
     * the other thirty loading.
     */
    fun loadDirectory(dir: File): Int {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return library.size
        for (file in files.sortedBy { it.name }) {
            if (!file.isFile || !file.name.endsWith(SUFFIX, ignoreCase = true)) continue
            if (file.length() > MAX_BYTES) continue
            val id = file.name.dropLast(SUFFIX.length)
            if (library.find(id) != null) continue
            val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: continue
            adopt(id, bitmap)
            bitmap.recycle()
        }
        return library.size
    }

    /**
     * Take [bitmap] as the tip called [id]. Public for the importer and for
     * tests; the file path goes through [loadDirectory].
     */
    fun adopt(id: String, bitmap: Bitmap): Tip? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        val pixels = IntArray(w * h)
        runCatching { bitmap.getPixels(pixels, 0, w, 0, 0, w, h) }.getOrNull() ?: return null
        return library.add(id, w, h, coverageOf(pixels))
    }

    /**
     * One byte of coverage per pixel, by whichever convention the picture uses.
     *
     * The scan for transparency is a whole extra pass over the image and it is
     * worth it: the two conventions disagree completely — a tip read the wrong
     * way is either a solid square or nothing at all — and there is no field
     * anywhere in a PNG that says which one it is.
     */
    internal fun coverageOf(pixels: IntArray): ByteArray {
        var translucent = 0
        for (p in pixels) if ((p ushr 24) < 250) translucent++
        val out = ByteArray(pixels.size)
        if (translucent > pixels.size * ALPHA_FLOOR) {
            for (i in pixels.indices) out[i] = (pixels[i] ushr 24).toByte()
            return out
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma, in integers. The exact weights matter less than
            // using some — a plain mean turns a red-tinted scan of a charcoal
            // stub into a nib a quarter too light.
            val grey = (r * 77 + g * 150 + b * 29) shr 8
            out[i] = (255 - grey).toByte()
        }
        return out
    }

    /** Tips live in `files/tips`, beside `files/brushes`. */
    fun directoryIn(filesDir: File): File = File(filesDir, "tips")

    /**
     * How much of a picture has to be translucent before its alpha is believed.
     *
     * 0.4%, which is one pixel in 256. Below it the channel is flat and the
     * mark is somewhere else; above it, something was drawn. See the note on
     * `oil_knife.png` above for where the number's floor comes from and the
     * antialiased rim of a 16-pixel tip — about 1.2% — for its ceiling.
     */
    internal const val ALPHA_FLOOR = 1f / 256f

    private const val SUFFIX = ".png"

    /** A tip is a nib, not a photograph. 2 MiB of file and 4 megapixels. */
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_PIXELS = 4L * 1024 * 1024
}
