package be.thalos.artiest.doc

import be.thalos.artiest.engine.guide.Guide
import be.thalos.artiest.engine.guide.NearestGuide
import be.thalos.artiest.engine.guide.Snap

/**
 * A [Guideline] — and a whole [Snap] — as text that survives a save and a
 * reload.
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
 * ## Two readers, one format
 *
 * The same text is written in two places for two reasons, and they have to
 * agree or the second one is worthless:
 *
 * - **`project.json`** keeps the page's guides, so a ruler you set up is still
 *   there tomorrow.
 * - **A vector sheet's guide table** keeps, per stroke, the snap that stroke
 *   was drawn against — see `StrokeRecord.guide`. A record stores raw samples
 *   and the snap is applied while they are turned into dabs, so a sheet that
 *   forgot it would re-render the stroke off the ruler the first time anything
 *   repainted that rectangle.
 *
 * The second is what [encodeSnap] and [decodeSnap] are for: a snap is a set of
 * guides plus the page's strength and reach, and it is interned whole, because
 * every stroke in a sitting was drawn against the same one.
 *
 * ## Unknown kinds are dropped, not guessed
 *
 * A file written by a later build carrying Ik15's `vanishing` row opens in this
 * one with that guide missing and everything else intact. The alternative —
 * refuse the file, or map it to the nearest kind — is worse in both directions:
 * one loses a drawing over a ruler, and the other silently puts a line where a
 * ray set was and lets somebody ink a hundred strokes against it.
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

    /**
     * The most points one guide may name, which only the curve can reach.
     *
     * `CurveGuide.MAX_POINTS`, because a row that claims more describes a curve
     * the engine would refuse to build — and the honest place to refuse it is
     * the reader, which drops one guide, rather than the constructor, which
     * would throw on the path that opens somebody's drawing.
     */
    private const val MAX_POINTS = be.thalos.artiest.engine.guide.CurveGuide.MAX_POINTS

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
        //
        // A kind whose count is `ANY` — the curve — takes what it is given,
        // between two points and the cap, because for that kind the count *is*
        // the shape rather than a property of the kind.
        val given = parts.size - 3
        val count = if (kind.points == GuideKind.ANY) given else kind.points
        if (given != count || count < 2 || count > MAX_POINTS) return null
        val points = FloatArray(count * 2)
        for (i in 0 until count) {
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

    // ---- a whole snap, for the sheet's guide table -------------------------

    /**
     * The pull a stroke was drawn against, as one line.
     *
     * `<strength> <reach>|<guide>|<guide>…`, and null when nothing was live —
     * which is the usual answer and the one that costs a record nothing.
     *
     * Only the **live** guides go in. A guide switched off did not touch the
     * stroke, so recording it would make two sittings that drew identical lines
     * intern two different entries, and the table is interned by text.
     */
    fun encodeSnap(strength: Float, reachDoc: Float, lines: List<Guideline>): String? {
        val live = lines.filter { it.live }
        if (live.isEmpty() || strength <= 0f) return null
        return buildString {
            append(strength)
            append(' ')
            append(reachDoc)
            for (line in live) {
                append('|')
                append(encode(line))
            }
        }
    }

    /**
     * The same snap description with every guide moved by [m], or null.
     *
     * **Why a moved stroke needs a moved ruler.** The line on the page is the
     * raw input smoothed, then snapped, then — for a stroke that has been
     * dragged — mapped. A record stores the *mapped raw* samples, so a rebuild
     * computes snap(smooth(M·raw)), and that equals M·snap(smooth(raw)) only
     * when the guide is mapped too. Leave it alone and the stroke springs back
     * onto the ruler it was drawn along, undoing the drag the first time
     * anything repaints that rectangle.
     *
     * The stabilizer needs no such treatment, and the reason is worth writing
     * down because it is why this is the only filter with the problem:
     * smoothing is a weighted average of past points, an affine map distributes
     * over one, and the two therefore commute. A projection does not.
     *
     * The reach is scaled by [scale] because it is a distance in document
     * pixels: a stroke scaled to twice the size against a ruler that reached 40
     * pixels was drawn against one that now reaches 80.
     */
    fun mapSnap(text: String, m: android.graphics.Matrix, scale: Float): String? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val head = parts[0].split(' ')
        if (head.size != 2) return null
        val strength = head[0].toFloatOrNull() ?: return null
        val reach = head[1].toFloatOrNull() ?: return null
        val moved = ArrayList<Guideline>(parts.size - 1)
        val point = FloatArray(2)
        for (i in 1 until parts.size) {
            var line = decode(parts[i]) ?: continue
            for (j in 0 until line.pointCount) {
                point[0] = line.xAt(j)
                point[1] = line.yAt(j)
                m.mapPoints(point)
                line = line.withPoint(j, point[0], point[1])
            }
            moved += line
        }
        return encodeSnap(strength, reach * scale, moved)
    }

    /**
     * That line back as a [Snap], or null.
     *
     * Null for anything that does not read, which is the same answer a stroke
     * drawn freehand gives — and the right one: a sheet whose guide table is
     * damaged re-renders its strokes without the ruler, which is wrong by a few
     * pixels, rather than refusing to open, which is wrong by a drawing.
     */
    fun decodeSnap(text: String): Snap? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val head = parts[0].split(' ')
        if (head.size != 2) return null
        val strength = head[0].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
        val reach = head[1].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return null
        if (strength <= 0f) return null
        val guides = ArrayList<Guide>(parts.size - 1)
        for (i in 1 until parts.size) {
            val line = decode(parts[i]) ?: continue
            line.guide?.let { guides += it }
        }
        val composed = NearestGuide.of(guides) ?: return null
        return Snap(composed, strength, reach)
    }

    /**
     * A tenth of a document pixel, which is [PathText]'s precision and for the
     * same reason: below that the difference is inside the antialiasing of the
     * line drawn against it.
     */
    private fun round(v: Float): Float = Math.round(v * 10f) / 10f
}
