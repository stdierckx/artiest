package be.thalos.artiest.project

import be.thalos.artiest.doc.GuideKind
import be.thalos.artiest.doc.Guideline

/**
 * One [Guideline], as a line of text that survives a save and a reload.
 *
 * ## The format
 *
 * ```
 * <id> <kind> <on> <x0>,<y0> <x1>,<y1> ...
 * 3 ruler 1 100.0,50.0 900.0,780.0
 * ```
 *
 * Text and not base64, for `PathText`'s reason and with more force: there are a
 * handful of guides on a page, not two thousand points, so the whole table is a
 * few hundred bytes however it is written, and a format a person can read in
 * `project.json` is worth more than the bytes.
 *
 * ## What is *not* in a line, and why
 *
 * The strength and the reach. They belong to the page and not to a guide — see
 * `GuideSet`'s note on why there is one of each — so they are two numbers in
 * `project.json` beside the list rather than repeated on every row.
 *
 * ## Unknown kinds are dropped, not guessed
 *
 * A file written by a later build carrying an `ellipse` row opens in this one
 * with the ellipse missing and everything else intact. The alternative — refuse
 * the file, or map it to the nearest kind — is worse in both directions: one
 * loses a drawing over a ruler, and the other silently puts a line where an
 * ellipse was and lets somebody ink against it.
 */
object GuideText {

    /**
     * More guides than this on one page is not a page anybody arranged.
     *
     * A cap and not a validation: a file claiming forty thousand rulers is
     * either corrupt or hostile, and the overlay would draw every one of them
     * on every frame.
     */
    const val MAX_GUIDES: Int = 256

    /** Beyond this a coordinate is not on any page this app can make. */
    private const val MAX_COORD = 1e6f

    fun encode(line: Guideline): String = buildString {
        append(line.id)
        append(' ')
        append(line.kind.id)
        append(' ')
        append(if (line.on) '1' else '0')
        for (i in 0 until line.pointCount) {
            append(' ')
            append(round(line.xAt(i)))
            append(',')
            append(round(line.yAt(i)))
        }
    }

    /**
     * One line back, or null when it is not one.
     *
     * Null rather than an exception: a decoder on the load path answers "not
     * this one" and the loader drops the row, exactly as `PathText` and
     * `BrushCodec` do. A guide that will not read is a guide missing from a
     * drawing that otherwise opens.
     */
    fun decode(text: String): Guideline? {
        val parts = text.trim().split(' ')
        if (parts.size < 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        if (id <= 0L) return null
        val kind = GuideKind.byId(parts[1]) ?: return null
        val on = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        // Exactly as many points as the kind takes. Too few cannot be placed;
        // too many means the row was written by a build where this kind meant
        // something else, and that is the one case where guessing is worse than
        // dropping it.
        if (parts.size != 3 + kind.points) return null
        val points = FloatArray(kind.points * 2)
        for (i in 0 until kind.points) {
            val pair = parts[3 + i].split(',')
            if (pair.size != 2) return null
            val x = pair[0].toFloatOrNull() ?: return null
            val y = pair[1].toFloatOrNull() ?: return null
            if (!x.isFinite() || !y.isFinite()) return null
            if (kotlin.math.abs(x) > MAX_COORD || kotlin.math.abs(y) > MAX_COORD) return null
            points[i * 2] = x
            points[i * 2 + 1] = y
        }
        return Guideline(id, kind, points, on)
    }

    /** Every line that reads, in file order, capped at [MAX_GUIDES]. */
    fun decodeAll(lines: List<String>): List<Guideline> {
        val out = ArrayList<Guideline>(minOf(lines.size, MAX_GUIDES))
        val seen = HashSet<Long>()
        for (text in lines) {
            if (out.size >= MAX_GUIDES) break
            val line = decode(text) ?: continue
            // A repeated id would give one guide two handles that move
            // independently and one that cannot be deleted. The first wins,
            // which is `DockLayout.of`'s rule for a repeated surface id.
            if (!seen.add(line.id)) continue
            out += line
        }
        return out
    }

    fun encodeAll(lines: List<Guideline>): List<String> =
        lines.take(MAX_GUIDES).map { encode(it) }

    /**
     * A tenth of a document pixel, which is [PathText]'s precision and for the
     * same reason: below that the difference is inside the antialiasing of the
     * line drawn against it.
     */
    private fun round(v: Float): Float = Math.round(v * 10f) / 10f
}
