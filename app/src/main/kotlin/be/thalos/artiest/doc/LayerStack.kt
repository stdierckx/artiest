package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * The sheets the drawing is made of, bottom to top, and which one the pen is
 * on.
 *
 * ## Why this is not a `List<Layer>` on `Document`
 *
 * A stack is not a list of pixels. It is a list of pixels *plus* a name, an
 * opacity and a visibility for each, an active index, an order that can be
 * rearranged, and a rule about which thread may do any of that. `Document`
 * already owns the commit queue, the undo history and the stroke bookkeeping;
 * putting seven more mutable fields and an ordering discipline beside them
 * would make the one class that has to stay readable the one class nobody can
 * read.
 *
 * ## The threading rule, which is the whole of the design
 *
 * [Layer]'s contract is that its pixels belong to the render thread. This class
 * extends that to the *shape* of the stack: **every mutation happens on the
 * render thread, inside `CommitQueue.drain`.** Adding a layer, deleting one,
 * reordering, renaming, changing an opacity — all of it arrives as a
 * [LayerOp] through the same queue a stroke does, for exactly the reason a
 * Clear does: "delete this layer" means *after everything I have drawn*, and a
 * stroke that has been finished but not yet stamped is part of what the user
 * drew. Two independent paths into the same state cannot express that ordering
 * however carefully each one is written.
 *
 * The UI thread never touches the entries. It reads [snapshot], which is a
 * plain immutable list republished after every mutation — a handful of strings
 * and floats, no bitmaps, nothing that can be recycled underneath a
 * recomposition. The crossing is one volatile write and one volatile read, in
 * that direction only, which is the same shape as `Document.canUndo`.
 *
 * The one exception is construction. A `Layer` is 27.19 MiB and allocating one
 * takes long enough to be seen; doing it inside a render callback would hitch
 * the frame the user is drawing into. So **the UI thread allocates the empty
 * layer and the render thread fills and installs it** — see [LayerOp.Add] and
 * [LayerOp.Duplicate], which both carry a ready-made [Layer]. Allocation is not
 * a pixel operation and `Layer` permits it anywhere; it is `write` and `read`
 * that refuse the main thread.
 *
 * ## Why there is a cap
 *
 * Every layer is a full-page ARGB_8888 bitmap: 3300 x 2160 x 4 = 27.19 MiB.
 * Eight of them is 217.5 MiB, against the 4.4 GiB this device reported free in
 * W0 and beside a 48 MiB undo budget and roughly 121 MiB of Phase 1 graphics.
 * That is comfortable. Thirty would not be, and the failure when a bitmap
 * allocation runs out is an `OutOfMemoryError` with a **null message** — see
 * [Layer]'s note — which is to say a crash with nothing in it. A cap that
 * refuses the ninth layer is a better answer than a number nobody chose.
 */
class LayerStack(
    val widthPx: Int,
    val heightPx: Int,
    /** See [Layer]'s parameter of the same name. Off in unit tests only. */
    private val enforceOffMainThread: Boolean = true,
) {

    /**
     * One sheet: its pixels and everything about it that is not pixels.
     *
     * A class with `var`s rather than a data class, because the fields are
     * mutated in place on the render thread and the immutable thing the UI
     * sees is [LayerInfo]. Two types and not one, so that the mutable one
     * cannot escape to the other thread by being convenient.
     */
    class Entry internal constructor(
        /**
         * Stable for the entry's life and never reused.
         *
         * Positions are not identity: a layer moved from index 2 to index 0 is
         * the same layer, and an undo step recorded against "index 2" would
         * restore pixels into whatever sheet happens to be there now. Every
         * reference held outside this class — an undo patch, a pending
         * operation, a row in the panel — is by id.
         */
        val id: Int,
        val layer: Layer,
        var name: String,
        var opacity: Float,
        var visible: Boolean,
    ) {
        /**
         * The last thumbnail built for this entry, or null if none has been.
         *
         * Handed to the UI thread through [LayerInfo] and **never recycled
         * here**. A bitmap the render thread frees while a recomposition is
         * drawing it is a crash in the UI thread with a stack pointing at
         * Compose, and the alternative costs 43 KB per layer for the garbage
         * collector to deal with in its own time.
         */
        var thumbnail: Bitmap? = null

        /** Whether [thumbnail] is older than the pixels. See [refreshThumbnails]. */
        var thumbDirty: Boolean = true
    }

    private val entries = ArrayList<Entry>()

    private var nextId = 1

    private var activeIndex = 0

    // ---- the render thread's view -------------------------------------------

    /** How many sheets there are. **Render thread.** */
    val size: Int get() = entries.size

    /** Bottom to top. **Render thread.** */
    fun entryAt(i: Int): Entry = entries[i]

    /** The sheet the pen draws on. **Render thread.** */
    val active: Entry get() = entries[activeIndex.coerceIn(0, entries.size - 1)]

    /** Where [active] sits, bottom-first. **Render thread.** */
    val activePosition: Int get() = activeIndex.coerceIn(0, entries.size - 1)

    /**
     * The sheet with this id, or null if it has been deleted. **Render
     * thread.**
     *
     * Null rather than an exception, and that is the contract undo depends on:
     * a patch recorded against a layer the user has since deleted is a step
     * that restores nothing, not a crash. See [PixelPatch].
     */
    fun byId(id: Int): Entry? {
        for (i in entries.indices) if (entries[i].id == id) return entries[i]
        return null
    }

    // ---- the UI thread's view -----------------------------------------------

    /**
     * What the panel draws, bottom-first, republished after every change.
     *
     * Volatile and immutable: the UI thread reads the reference once and walks
     * a list nobody else can touch. Rebuilt whole rather than patched, because
     * a list of eight small objects costs nothing to rebuild and a partially
     * updated shared list is the bug this design exists to make unwritable.
     */
    @Volatile
    var snapshot: List<LayerInfo> = emptyList()
        private set

    /** The id of the sheet the pen is on, for the UI thread. */
    @Volatile
    var activeId: Int = 0
        private set

    /**
     * Whether anyone is looking at the thumbnails.
     *
     * Set by the UI when the layers panel opens and cleared when it closes.
     * Building a thumbnail means reading a full 7.1 Mpx page and scaling it
     * down; doing that after every stroke, for a panel that is not on screen,
     * would put a measurable cost on the commit path in exchange for a picture
     * nobody is looking at. Dirty flags are kept either way, so opening the
     * panel shows the drawing as it is now rather than as it was when the panel
     * was last open.
     */
    @Volatile
    var wantThumbnails: Boolean = false

    /**
     * The first sheet, and the first snapshot.
     *
     * **Below the two published fields and not at the top of the class**, and
     * that placement is load-bearing rather than tidy: Kotlin runs property
     * initializers and `init` blocks in declaration order, so an `init` above
     * `snapshot` publishes a list and then has `= emptyList()` written straight
     * over it. The stack then holds one sheet and tells the UI it holds none —
     * a panel with no rows over a document that draws perfectly well.
     */
    init {
        entries.add(newEntry("Layer 1"))
        publish()
    }

    // ---- mutation, render thread only ---------------------------------------

    /**
     * Apply one operation. **Render thread**, from inside `CommitQueue.drain`.
     *
     * Returns false when the operation could not be applied — a layer that has
     * since been deleted, a ninth layer against the cap, the last layer being
     * deleted. False is not an error: the queue is asynchronous by design, so
     * an operation aimed at something that is no longer there is an ordinary
     * outcome and the right response is to do nothing.
     *
     * A rejected [LayerOp.Add] or [LayerOp.Duplicate] closes the layer it was
     * carrying. Nothing else will: the UI thread allocated it, handed it over
     * and forgot it, so refusing the operation without releasing the pixels
     * leaks 27.19 MiB per press.
     */
    fun apply(op: LayerOp): Boolean {
        val ok = applyInner(op)
        if (!ok && op is LayerOp.Carrying) op.layer.close()
        if (ok) publish()
        return ok
    }

    private fun applyInner(op: LayerOp): Boolean = when (op) {
        is LayerOp.Add -> {
            if (entries.size >= MAX_LAYERS) {
                false
            } else {
                // Above the active one and not on top of the stack. "New layer"
                // while working on the third of five means a new sheet over
                // *this* one, which is where the next mark is going; putting it
                // at the top would put it over work the user had deliberately
                // left above.
                val at = activePosition + 1
                entries.add(at, Entry(nextId++, op.layer, op.name, 1f, true))
                activeIndex = at
                true
            }
        }

        is LayerOp.Duplicate -> {
            val from = byId(op.id)
            when {
                from == null -> false
                entries.size >= MAX_LAYERS -> false
                else -> {
                    // Through a patch rather than by nesting one layer's lock
                    // inside the other's. `Layer`'s lock is documented as a
                    // leaf -- a thread holding it may touch those pixels and
                    // nothing else -- and a copy written as `dst.write {
                    // src.read { ... } }` is two locks held at once and a lock
                    // ordering nobody has designed. `PixelPatch` already copies
                    // out under one lock and back under the other, which is the
                    // same work with no nesting.
                    val at = entries.indexOfFirst { it.id == op.id } + 1
                    val entry = Entry(nextId++, op.layer, op.name, from.opacity, from.visible)
                    val patch = PixelPatch.captureAll(from.layer, widthPx, heightPx)
                    patch?.restoreInto(entry.layer)
                    patch?.recycle()
                    entries.add(at, entry)
                    activeIndex = at
                    true
                }
            }
        }

        is LayerOp.Delete -> {
            val at = entries.indexOfFirst { it.id == op.id }
            when {
                at < 0 -> false
                // The last sheet is not deletable. A document with no layers
                // has nowhere to put the next stroke, and every path out of
                // that state -- refusing strokes, silently making one, showing
                // an empty canvas that ignores the pen -- is worse than a
                // button that does nothing. Clear is what "empty this" means.
                entries.size == 1 -> false
                else -> {
                    val gone = entries.removeAt(at)
                    gone.layer.close()
                    gone.thumbnail = null
                    if (activeIndex >= entries.size) activeIndex = entries.size - 1
                    true
                }
            }
        }

        is LayerOp.Move -> {
            val from = entries.indexOfFirst { it.id == op.id }
            val to = op.toIndex
            when {
                from < 0 -> false
                to !in entries.indices -> false
                to == from -> false
                else -> {
                    val active = entries[activePosition]
                    entries.add(to, entries.removeAt(from))
                    // The pen stays on the sheet it was on, wherever that sheet
                    // has moved to. Keeping the *index* would silently move the
                    // pen to a different layer as a side effect of reordering,
                    // which is a lost stroke waiting to happen.
                    activeIndex = entries.indexOf(active)
                    true
                }
            }
        }

        is LayerOp.SetOpacity -> byId(op.id)?.let {
            it.opacity = op.opacity.coerceIn(0f, 1f)
            true
        } ?: false

        is LayerOp.SetName -> byId(op.id)?.let {
            it.name = op.name
            true
        } ?: false

        is LayerOp.SetVisible -> byId(op.id)?.let {
            it.visible = op.visible
            true
        } ?: false

        is LayerOp.SetActive -> {
            val at = entries.indexOfFirst { it.id == op.id }
            if (at < 0) {
                false
            } else {
                activeIndex = at
                true
            }
        }
    }

    /** Mark the active sheet's thumbnail stale. **Render thread**, after a commit. */
    fun touchActive() {
        active.thumbDirty = true
    }

    /** Mark every sheet's thumbnail stale. **Render thread.** */
    fun touchAll() {
        for (i in entries.indices) entries[i].thumbDirty = true
    }

    /**
     * Rebuild **at most one** stale thumbnail, and republish if one was built.
     * **Render thread**, at the end of a frame that drained commits.
     *
     * One per frame and not all of them, because a thumbnail is a full-page
     * read scaled down and eight of those in one callback is a visible stall on
     * the thread that is also drawing ink. A panel that fills in over four
     * frames of a 90 Hz display is 44 ms and reads as instant; a frame that
     * drops because eight pages were rescaled inside it does not.
     *
     * Returns true if anything changed.
     */
    fun refreshThumbnails(): Boolean {
        if (!wantThumbnails) return false
        for (i in entries.indices) {
            val e = entries[i]
            if (!e.thumbDirty) continue
            e.thumbnail = buildThumbnail(e.layer)
            e.thumbDirty = false
            publish()
            return true
        }
        return false
    }

    /**
     * Release every sheet. UI thread, at teardown, after the render thread has
     * been joined — see [Layer.close].
     */
    fun close() {
        for (i in entries.indices) {
            entries[i].layer.close()
            entries[i].thumbnail = null
        }
    }

    // ---- internals ----------------------------------------------------------

    private fun newEntry(name: String): Entry =
        Entry(nextId++, Layer(widthPx, heightPx, enforceOffMainThread), name, 1f, true)

    /**
     * A fresh empty sheet, allocated by whoever is about to hand it over.
     *
     * On the class rather than at the call site so that a layer added later
     * cannot be a different size from the ones already in the stack — which
     * would composite as a page with a corner missing and would only be visible
     * after the fact.
     */
    fun newLayer(): Layer = Layer(widthPx, heightPx, enforceOffMainThread)

    /**
     * The next unused name built on [base], so two sheets never read the same.
     *
     * Names are not identity -- [Entry.id] is -- so a collision is cosmetic
     * rather than dangerous. It is still worth avoiding: two rows reading
     * "Picture" in a panel whose whole job is telling sheets apart is a panel
     * that has stopped working.
     */
    fun suggestName(base: String = "Layer"): String {
        var n = if (base == "Layer") entries.size + 1 else 1
        while (entries.any { it.name == nameOf(base, n) }) n++
        return nameOf(base, n)
    }

    private fun nameOf(base: String, n: Int): String = if (n <= 1) base else base + " " + n

    private fun publish() {
        val list = ArrayList<LayerInfo>(entries.size)
        for (i in entries.indices) {
            val e = entries[i]
            list.add(LayerInfo(e.id, e.name, e.opacity, e.visible, e.thumbnail))
        }
        snapshot = list
        activeId = active.id
    }

    /**
     * A thumbnail, built by **halving repeatedly** rather than in one step.
     *
     * The one-step version is the obvious code and it does not work. A single
     * `drawBitmap` from 3300 px to 128 is a 26:1 reduction, and a bilinear
     * filter samples a 2x2 neighbourhood however far apart the samples are — so
     * a pencil line two document pixels wide falls between the taps about
     * ninety-two times in a hundred and the thumbnail comes back blank. Caught
     * on the tablet: two sheets, a stroke on each, one thumbnail showing a line
     * and the other showing nothing, with both sheets visibly drawn on.
     *
     * Halving is the fix because at 2:1 the 2x2 neighbourhood *is* the four
     * pixels being merged, so nothing can fall between the samples. Five steps
     * get 3300 down to 206 and the last one lands on 128 exactly.
     *
     * The intermediates are recycled as they are consumed. The largest is
     * 1650x1080 — 7.1 MiB — which is why they are not kept: this runs at most
     * once a frame and only while the panel is open, but eight of them held
     * would be another page and a half of memory for pictures 43 KB each.
     */
    private fun buildThumbnail(layer: Layer): Bitmap? {
        val targetH = ((THUMB_WIDTH.toLong() * heightPx) / widthPx).toInt().coerceAtLeast(1)
        // The first halving is the only step that reads the layer, so it is the
        // only one that happens on the lock.
        var current: Bitmap? = null
        val ok = layer.read { current = halve(it) }
        var step = if (ok) current else null
        if (step == null) return null
        while (step!!.width > THUMB_WIDTH * 2) {
            val next = halve(step!!)
            step!!.recycle()
            step = next
        }
        val out = Bitmap.createBitmap(THUMB_WIDTH, targetH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            step!!,
            Rect(0, 0, step!!.width, step!!.height),
            Rect(0, 0, THUMB_WIDTH, targetH),
            thumbPaint,
        )
        step!!.recycle()
        return out
    }

    /** Half the width and half the height, never below one pixel. */
    private fun halve(src: Bitmap): Bitmap {
        val w = (src.width / 2).coerceAtLeast(1)
        val h = (src.height / 2).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), thumbPaint)
        return out
    }

    /**
     * Filtered, because unfiltered is point sampling: a pencil line one
     * document pixel wide either lands on a sample or does not, so a drawing
     * made of fine lines produces a thumbnail that is blank in some places and
     * speckled in others, and which changes at random as the drawing grows.
     * Filtering alone is not enough — see [buildThumbnail] for why the
     * reduction is done in halves.
     */
    private val thumbPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    companion object {

        /** See the class header for the arithmetic. */
        const val MAX_LAYERS = 8

        /**
         * 128 px across, which is 43 KB a sheet at the document's aspect.
         *
         * Wide enough to tell two drawings apart at a glance on a 230 dpi
         * panel and small enough that eight of them are a third of a megabyte.
         */
        const val THUMB_WIDTH = 128
    }
}

/**
 * One sheet as the UI sees it: immutable, no `Layer`, no lock.
 *
 * [thumbnail] is the one thing here that is not a value, and it is safe for the
 * same reason it is not recycled: the render thread builds a *new* bitmap and
 * publishes it, so a list the UI is holding always names bitmaps nobody is
 * going to free. See [LayerStack.Entry.thumbnail].
 */
data class LayerInfo(
    val id: Int,
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val thumbnail: Bitmap?,
)

/**
 * Something the user asked to do to the stack, on its way to the render thread.
 *
 * A sealed hierarchy rather than a method per operation on [LayerStack],
 * because these are *queued*: they travel through `CommitQueue` beside strokes
 * and clears so that the order the user did things in survives the crossing.
 * A method that is called is a thing that has already happened; a value that is
 * enqueued is a thing that will happen in its turn.
 */
sealed interface LayerOp {

    /**
     * The operations that arrive holding pixels the UI thread allocated.
     *
     * Named as an interface of its own so that the one thing that must happen
     * to a rejected operation — closing the layer it was carrying — can be
     * written once and cannot be forgotten for a third case added later.
     */
    sealed interface Carrying : LayerOp {
        val layer: Layer
    }

    /** A new empty sheet above the active one. */
    class Add(override val layer: Layer, val name: String) : Carrying

    /** A copy of [id], above it. [layer] is the empty sheet to copy into. */
    class Duplicate(val id: Int, override val layer: Layer, val name: String) : Carrying

    /** Remove [id]. Refused for the last remaining sheet. */
    class Delete(val id: Int) : LayerOp

    /** Put [id] at [toIndex], counting from the bottom. */
    class Move(val id: Int, val toIndex: Int) : LayerOp

    class SetOpacity(val id: Int, val opacity: Float) : LayerOp

    class SetName(val id: Int, val name: String) : LayerOp

    class SetVisible(val id: Int, val visible: Boolean) : LayerOp

    /** Put the pen on [id]. */
    class SetActive(val id: Int) : LayerOp
}
