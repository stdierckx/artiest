package be.thalos.artiest.doc

import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.StrokeRecord

/**
 * Give strokes a different colour or a different nib, after the fact.
 *
 * Ik10, and it is the cheapest item in this plan because of what a record is: a
 * stroke's colour and its brush are two of nine fields, and every other field —
 * the samples, the seed, the `dabBase`, the clip — is untouched. So a restyle
 * is a new record with two fields swapped and the same input, which means the
 * mark lands in exactly the same place and the grain falls in exactly the same
 * pattern. That is not a property the pixel version of this operation could
 * have at any price.
 *
 * **The four operations the plan lists are one.** Recolour is the colour.
 * Re-brush, scale the width and re-stabilise are all *a different brush* —
 * width is `sizeMin` and `sizeMax`, stabilisation is `stabilization` — so the
 * caller tunes a copy of the brush and hands over its text. One op, one undo
 * step, one repaint.
 *
 * **Render thread**, like the sheet it reads.
 */
object StrokeRestyle {

    /**
     * What [op] would do to these strokes, or null for nothing.
     *
     * Null when nothing is picked, when the change is a no-op, or when the
     * strokes already look like that — a restyle that changes nothing must not
     * put a press of the undo button between the user and the last thing they
     * actually did.
     *
     * The new bounds is the **union of the old and the new**, and it has to be:
     * a stroke restyled onto a wider nib paints outside the rectangle it
     * painted before, and a rebuild of only the old rectangle would clip it.
     * `VectorStep.damage` unions both lists, so getting each record's own
     * bounds right is what makes that union right.
     */
    fun plan(
        op: StrokeOp.Restyle,
        picked: LongArray,
        sheet: VectorSheet,
        layerId: Int,
    ): VectorStep? {
        if (picked.isEmpty()) return null
        if (op.colorArgb == null && op.brushText == null) return null
        val brush = op.brushText?.let { sheet.brushIndexOf(it) }
        val removed = ArrayList<StrokeRecord>(picked.size)
        val added = ArrayList<StrokeRecord>(picked.size)
        for (id in picked) {
            val old = sheet.byId(id) ?: continue
            val colour = op.colorArgb ?: old.colorArgb
            val nib = brush ?: old.brush
            if (colour == old.colorArgb && nib == old.brush) continue
            removed.add(old)
            added.add(
                StrokeRecord(
                    // A new id, because it is a new record: keeping the old one
                    // would make an undo step whose two halves name the same
                    // thing, and `VectorSheet.remove` would take the
                    // replacement out again.
                    id = sheet.nextIds(1)[0],
                    brush = nib,
                    colorArgb = colour,
                    erase = old.erase,
                    seed = old.seed,
                    dabBase = old.dabBase,
                    clip = old.clip,
                    bounds = boundsFor(old, sheet, nib),
                    packed = old.copyPackedBytes(),
                    sampleCount = old.sampleCount,
                )
            )
        }
        if (removed.isEmpty()) return null
        return VectorStep(layerId, added = added, removed = removed)
    }

    /** Apply a planned restyle. See `StrokeEraser.apply` for why it is shaped so. */
    fun apply(step: VectorStep, sheet: VectorSheet) {
        if (step.removed.isNotEmpty()) {
            sheet.remove(LongArray(step.removed.size) { step.removed[it].id })
        }
        for (r in step.added) sheet.add(r)
        sheet.reorder()
    }

    /**
     * What the stroke will paint under its new nib.
     *
     * Unioned with what it painted under the old one, because the *old*
     * rectangle is the ground a rebuild has to clear: a stroke moved onto a
     * narrower nib that reported only its new bounds would leave the wide
     * version's edges behind.
     */
    private fun boundsFor(old: StrokeRecord, sheet: VectorSheet, brush: Int): Bounds {
        val pen = sheet.brushAt(brush)
        val line = StrokeRecord(
            id = old.id, brush = brush, colorArgb = old.colorArgb, erase = old.erase,
            seed = old.seed, dabBase = old.dabBase, clip = old.clip, bounds = Bounds.EMPTY,
            packed = old.copyPackedBytes(), sampleCount = old.sampleCount,
        ).polyline(pen)
        if (line.pointCount == 0) return old.bounds
        val reach = pen.scatter.max
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until line.pointCount) {
            val half = line.halfWidth(i) + reach
            l = minOf(l, line.x(i) - half)
            t = minOf(t, line.y(i) - half)
            r = maxOf(r, line.x(i) + half)
            b = maxOf(b, line.y(i) + half)
        }
        return Bounds.of(l, t, r, b).unionWith(old.bounds)
    }
}
