package be.thalos.artiest.doc

import be.thalos.artiest.engine.ink.Bounds
import be.thalos.artiest.engine.ink.StrokeRecord

/**
 * An edit to a sheet's stroke list, and its own undo.
 *
 * **Kilobytes where a `PixelPatch` is up to 28 MB.** A stroke drawn on an
 * ordinary sheet is undone by putting back the pixels it covered, which for a
 * sweep across the page is 28.5 MB of the 48 MiB budget. The same stroke on an
 * ink sheet is undone by taking its record off the list and repainting the
 * rectangle from what is left — a few kilobytes, and the depth cap bites before
 * the byte cap, which `UndoHistory` says is the correct end.
 *
 * ## Two lists, and it is its own inverse
 *
 * [added] and [removed] describe what the edit did. Applying it in reverse is
 * the same operation with the two swapped, so the inverse is another
 * `VectorStep` with the lists exchanged, and every edit this plan adds is one
 * shape:
 *
 * | Edit | added | removed |
 * |---|---|---|
 * | Draw a stroke | the new record | — |
 * | Erase whole strokes | — | what was taken |
 * | Split a stroke (Ik8) | the pieces | the parent |
 * | Recolour or re-brush (Ik10) | the new records | the old ones |
 * | Move a selection (Ik9) | the moved records | where they were |
 *
 * ## Why it names a layer id
 *
 * The same reason `PixelPatch` does: a step recorded against "the active sheet"
 * would apply to whatever is active when it is walked back, and an undo that
 * edits the wrong sheet is worse than one that does nothing. A step whose sheet
 * has been deleted — or which has stopped being a vector sheet — does nothing
 * and hands back *itself*, so that pressing undo past it walks over it rather
 * than stopping on it.
 *
 * ## The repaint
 *
 * The union of both lists' bounds, which is the rectangle the edit could have
 * changed. Through [Document.rebuilder], because the rebuild lives with the
 * rasterizer and the scratch buffer, and `docs/inker-plan.md`'s third stop
 * condition is that there must be exactly one of those.
 *
 * A rebuild repaints *every* stroke overlapping the rectangle, not only the
 * ones this step touched, so neighbours are re-rendered from their records as
 * well. That is a real effect and it is bounded: Ik4 measured a record's
 * re-render against the live drawing at under 0.4% of the total ink, and after
 * the first rebuild the pixels come from the records, so a second rebuild of
 * the same region is pixel-identical. It converges after one press rather than
 * drifting with every press.
 */
class VectorStep(
    val layerId: Int,
    /** Records this edit put on the sheet, in draw order. */
    val added: List<StrokeRecord>,
    /** Records this edit took off, in draw order. */
    val removed: List<StrokeRecord>,
) : DocStep {

    /**
     * The samples both lists hold, plus a word per record for the lists
     * themselves.
     *
     * Real rather than nominal, because `UndoHistory`'s byte cap is only a cap
     * if every step reports what it occupies. It is small, and that is the
     * point of the type — but a step that reported zero would let a thousand of
     * them sit under a budget that thinks it is empty.
     */
    override val bytes: Long
        get() {
            var n = 64L
            for (r in added) n += r.byteCount + PER_RECORD_BYTES
            for (r in removed) n += r.byteCount + PER_RECORD_BYTES
            return n
        }

    /**
     * Nothing to release. The records are immutable values that the sheet may
     * still be holding — this step and the sheet share them on purpose, because
     * copying a stroke's samples to put it in the history would double the one
     * cost this design is careful about.
     */
    override fun recycle() = Unit

    override fun exchange(doc: Document): DocStep {
        val entry = doc.layers.byId(layerId) ?: return this
        val sheet = entry.vector ?: return this
        // Both directions before the repaint, so the rectangle is painted once
        // against a list that is already correct.
        if (added.isNotEmpty()) sheet.remove(LongArray(added.size) { added[it].id })
        for (r in removed) sheet.add(r)
        if (removed.isNotEmpty()) sheet.reorder()
        doc.rebuilder?.rebuild(entry, damage())
        doc.layers.touchAll()
        return VectorStep(layerId, added = removed, removed = added)
    }

    /** The rectangle this edit could have changed, either way round. */
    fun damage(): Bounds {
        var b = Bounds.EMPTY
        for (r in added) b = b.unionWith(r.bounds)
        for (r in removed) b = b.unionWith(r.bounds)
        return b
    }

    override fun toString(): String =
        "VectorStep(+${added.size} -${removed.size} on layer $layerId, $bytes B)"

    companion object {

        /**
         * What a record costs beyond its samples: nine fields, an object
         * header, and the list slot pointing at it. Rounded up, because a
         * budget that under-counts is a budget that overruns.
         */
        const val PER_RECORD_BYTES: Long = 96L
    }
}

/**
 * Repaint a rectangle of a sheet from the strokes that made it.
 *
 * One method, so that `Document` can ask for a rebuild without knowing about
 * the rasterizer, the scratch buffer or the view that owns them — and so that
 * there is still exactly one implementation of it, which is
 * `docs/inker-plan.md`'s third stop condition.
 */
interface SheetRebuilder {
    /** **Render thread.** */
    fun rebuild(entry: LayerStack.Entry, damage: Bounds)
}
