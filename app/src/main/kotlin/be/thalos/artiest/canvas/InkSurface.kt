package be.thalos.artiest.canvas

import android.graphics.Matrix
import be.thalos.artiest.engine.ink.Stroke

/**
 * Everything the stroke pipeline is allowed to ask of the render path.
 *
 * Six methods, expressed in strokes and dabs, with **no graphics-core type in
 * any signature**. That is the seam `docs/phase1-plan.md` insists on keeping:
 * when W0 looked like it would force a pivot away from
 * `CanvasFrontBufferedRenderer`, this interface is why the cost was a day of
 * plan rather than a rewrite of the input path. The pivot was ultimately
 * reversed and the seam stays anyway, because Phase 2's GL rasterizer is a
 * second implementation of exactly this.
 *
 * The threading contract is the interesting half, and it is the same for every
 * implementation:
 *
 * - Every method here is called on the **UI thread**, from the input path.
 * - The work happens on a **render thread** the implementation owns, later, and
 *   with no completion signal that names the call.
 * - So nothing passed across may be mutated afterwards by the caller. [Stroke]
 *   is immutable by construction and [DabBatch] is recycled only through
 *   [DabBatchPool], which is the whole reason that class counts sequences.
 */
interface InkSurface {

    /**
     * Open a stroke and freeze the transform it will be drawn at.
     *
     * [docToView] must be a `Matrix` **nobody else holds** — build it with
     * `docToViewMatrix(transform)`, which allocates, and never with
     * `setDocToView` on an instance that has been published before. A `Matrix`
     * is mutable native state; the freeze is the point, and a shared instance
     * is a matrix changing under a thread that is concatenating it.
     *
     * The freeze is a semantic constraint of the renderer, not a performance
     * one. `CanvasFrontBufferedRenderer` never re-records pixels it has already
     * drawn, so a transform change mid-stroke renders one stroke at two
     * transforms at once: the drawn half keeps the old scale and screen
     * position while every later dab lands at the new one. There is no
     * invalidate and no partial re-transform to rescue it, and
     * `SurfaceControlCompat.Transaction` has no `setMatrix`.
     *
     * [colorArgb] and [antiAlias] are frozen here for the same reason and a
     * smaller one: the wet pass and the dry commit must paint the same stroke
     * the same way, or the ink visibly shifts at pen-up.
     */
    fun beginStroke(docToView: Matrix, colorArgb: Int, antiAlias: Boolean)

    /**
     * Take an empty batch, already carrying the open stroke's colour and
     * antialias flag.
     *
     * Comes from the implementation rather than from a pool the caller keeps,
     * because whether a slot is safe to reuse depends on the implementation's
     * completion signal — see [DabBatchPool].
     */
    fun acquireBatch(): DabBatch

    /**
     * Submit a batch as wet ink. The batch belongs to the surface from this
     * call onward; the caller must not touch it again.
     */
    fun drawWet(batch: DabBatch)

    /**
     * Pen-up: the stroke becomes dry ink in the layer and the wet buffer is
     * released.
     */
    fun commitStroke(stroke: Stroke)

    /** Abandon the open stroke and drop its wet ink without committing. */
    fun cancelStroke()

    /**
     * Redraw the dry layer at the current transform.
     *
     * The one call the library will not make for you. Its own
     * `SurfaceHolder.Callback.surfaceChanged` only tears down and rebuilds its
     * SurfaceControls — verified against the 1.0.4 aar — so without an app-side
     * callback that lands here, the canvas blanks on rotation or resize while
     * the renderer looks perfectly healthy.
     */
    fun redrawDry()

    /** Drop the render thread and its buffers. Idempotent. */
    fun release()
}
