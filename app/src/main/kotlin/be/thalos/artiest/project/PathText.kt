package be.thalos.artiest.project

import android.graphics.Path
import android.graphics.PathMeasure

/**
 * A document-space `Path`, as text that survives a save and a reload.
 *
 * ## Why this exists at all
 *
 * A stroke drawn into a selection is *clipped pixels*, so a vector sheet keeps
 * the selection path that was live when the stroke was drawn — see
 * `VectorSheet.clips`. If that table did not survive being saved, then the
 * first edit after reopening a drawing would re-render the stroke **outside**
 * the stencil: silently, correctly-looking, and nowhere near the thing that
 * caused it. That is the exact failure the clip table was invented to prevent,
 * so a clip table that does not persist is a clip table that does not work.
 *
 * ## Flattened, and why that is not a compromise
 *
 * `Path` has no serialisation this build can use — `getPathIterator` is API 34
 * and `minSdk` is 29 — so each contour is walked with a [PathMeasure] and
 * emitted as points [STEP_DOC] document pixels apart. A curve comes back as a
 * polygon within half a step of where it was.
 *
 * That is exact enough by construction, because of what a clip *is*: a mask,
 * rasterised to whole pixels, whose edge Skia antialiases over one pixel. A
 * one-pixel step reproduces the same mask. It would be the wrong technique for
 * a stroke — which is why strokes are stored as their input and not as an
 * outline, and why `docs/inker-plan.md` refuses true Bézier outlines for ink.
 *
 * **Direction is kept.** A selection with a hole in it is two contours wound
 * opposite ways, and `PathMeasure` walks each in its own direction, so a
 * rebuild under `FillType.WINDING` has the hole back. Reversing one contour
 * would fill the hole in, which is the kind of defect that looks like a
 * rendering bug three features away.
 *
 * ## The format
 *
 * One line per path, contours separated by `;`, points by spaces, coordinates
 * by `,`, at a tenth of a pixel:
 *
 * ```
 * 100.0,50.0 300.0,50.0 300.0,200.0 100.0,200.0;150.0,90.0 ...
 * ```
 *
 * Text rather than base64 floats for `TraceFormat`'s reason: this goes in
 * `project.json` beside things a person reads, it diffs, and a malformed one
 * can be spotted by eye. The size is the thing to watch — a lasso with a
 * 2000 px perimeter is 2000 points and about 24 KB — which is why [STEP_DOC] is
 * a pixel rather than the tenth of one that would be free to ask for.
 */
object PathText {

    /**
     * How far apart the points of a flattened contour are, in document pixels.
     *
     * One. A clip is rasterised to whole pixels and its edge is antialiased
     * over one of them, so a finer walk buys nothing visible and costs bytes
     * linearly. See the class note.
     */
    const val STEP_DOC: Float = 1f

    /**
     * A contour with fewer points than this encloses nothing after rounding and
     * is dropped. Three, because two points is a line and a line has no inside.
     */
    const val MIN_POINTS: Int = 3

    /**
     * More points than any selection a hand makes. A lasso around the whole of
     * a 3300x2160 page is a perimeter of about 11 000, so this is a refusal of
     * nonsense rather than a limit on drawing.
     */
    const val MAX_POINTS: Int = 1 shl 16

    /** Empty for a path that encloses nothing, which is a legal answer. */
    fun encode(path: Path): String {
        if (path.isEmpty) return ""
        val measure = PathMeasure(path, false)
        val point = FloatArray(2)
        val out = StringBuilder()
        var total = 0
        do {
            val length = measure.length
            if (length <= 0f) continue
            // Ceil, then one more, so the last sample lands on the contour's
            // end rather than short of it: a contour that stopped a step early
            // would be closed by a chord across the gap.
            val steps = Math.ceil((length / STEP_DOC).toDouble()).toInt().coerceAtLeast(2)
            if (total + steps + 1 > MAX_POINTS) break
            if (out.isNotEmpty()) out.append(';')
            for (i in 0..steps) {
                val at = length * i / steps
                if (!measure.getPosTan(at, point, null)) continue
                if (i > 0) out.append(' ')
                out.append(round(point[0])).append(',').append(round(point[1]))
            }
            total += steps + 1
        } while (measure.nextContour())
        return out.toString()
    }

    /**
     * The inverse. Null for text that describes nothing, which is what a
     * caller wants to hear rather than an empty path it has to test for.
     *
     * Every malformed number is a refusal of the whole path rather than a
     * skipped point. A clip with a point missing is a clip of a slightly
     * different shape, which would confine ink somewhere almost right — and
     * "almost right" is worse here than "not read", because the pixels on disk
     * are still correct and a sheet that declines to rebuild keeps them.
     */
    fun decode(text: String): Path? {
        if (text.isBlank()) return null
        val path = Path()
        path.fillType = Path.FillType.WINDING
        var any = false
        for (contour in text.split(';')) {
            val points = contour.trim().split(' ').filter { it.isNotEmpty() }
            if (points.size < MIN_POINTS) continue
            if (points.size > MAX_POINTS) return null
            var first = true
            for (p in points) {
                val comma = p.indexOf(',')
                if (comma <= 0 || comma == p.length - 1) return null
                val x = p.substring(0, comma).toFloatOrNull() ?: return null
                val y = p.substring(comma + 1).toFloatOrNull() ?: return null
                if (!x.isFinite() || !y.isFinite()) return null
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            any = true
        }
        return if (any) path else null
    }

    /** A tenth of a pixel, without the locale a `%.1f` would bring. */
    private fun round(v: Float): String {
        val tenths = Math.round(v * 10.0)
        val whole = tenths / 10
        val frac = Math.abs(tenths % 10)
        return "$whole.$frac"
    }
}
