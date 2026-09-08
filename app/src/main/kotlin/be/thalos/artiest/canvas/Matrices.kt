package be.thalos.artiest.canvas

import android.graphics.Matrix
import be.thalos.artiest.engine.xform.CanvasTransform

/**
 * The one place a [CanvasTransform] becomes an `android.graphics.Matrix`.
 *
 * `:engine` keeps the transform as four floats because it compiles against no
 * android.jar and cannot name a `Matrix` at all, and that split has a price
 * which is paid here: the mapping
 * now exists twice, once as float math in [CanvasTransform] and once as the
 * matrix built below. Two implementations of one mapping drift, and every way
 * they can drift is silent — a `pre` where a `post` belongs still compiles,
 * still runs, and is exactly equal at rotation 0, which is where anyone looks
 * first. `MatricesTest` is what holds them together, and it is why this file is
 * a few lines of code under a lot of prose.
 *
 * The mapping, as `:engine` defines it and this file has to reproduce:
 *
 * ```
 * view = scale * (R(rotationRad) * doc + t)
 * ```
 *
 * The translation is added after the rotation and before the scale, which is
 * what makes [CanvasTransform.txDoc] a document quantity. Composed as matrices
 * that is `T(scale * t) . S(scale) . R(rotationRad)`, and the `scale *` on the
 * translation is not decoration: `postTranslate` is applied after the scale, so
 * it takes view pixels. Passing [CanvasTransform.txDoc] raw is exactly right at
 * scale 1 and wrong in proportion to the zoom — up to 23100 px over
 * `MatricesTest`'s sweep, which is what
 * `postTranslate takes view pixels and document units are not close enough`
 * measures. A bug that is absent rather than small at 1:1 is one no amount of
 * looking at a freshly opened canvas finds.
 *
 * Nothing here reads or writes a `CanvasTransform` — a matrix goes one way
 * only. The inverse has no matrix form because it has no consumer: `Canvas`
 * concats document-to-view and nothing else, and the input path's `toDoc` stage
 * maps scalars through [CanvasTransform.viewToDoc] rather than through a
 * matrix. [CanvasTransform.viewToDocCoefficients] is there if that ever stops
 * being true.
 */

/**
 * Overwrites this matrix with [t]'s document-to-view mapping.
 *
 * Fills a caller-owned matrix and allocates nothing, because the multi-buffered
 * callback rebuilds its matrix once per frame while a gesture is running and a
 * per-frame allocation there is a per-frame GC risk in the one loop that cannot
 * afford one. The stroke path wants [docToViewMatrix] instead — see there for
 * why the two are separate calls rather than one.
 *
 * The live [CanvasTransform] is a legal argument here only while no stroke is
 * wet — that is the whole of the per-frame gesture regime, and the reason this
 * function exists. The other regime is the frozen one, and it includes more
 * than ACTION_DOWN: at the pen-up commit the argument must be the *same*
 * snapshot the stroke began with, never the live transform, because a commit
 * render that blits the dry layer at a new mapping while front-buffered wet
 * pixels still sit at the old one draws one stroke at two transforms at once.
 * Pen-up is the stroke path too.
 *
 * `setSinCos`, not `setRotate(degrees)`. Skia snaps sin to exactly zero
 * below SK_ScalarNearlyZero = 1/4096 rad, so `setRotate` discards any rotation
 * under 0.014 degrees outright — measured, against real Skia, in
 * `MatricesTest.a rotation too small for setRotate is not too small to draw`.
 * A twist that starts slowly would draw the canvas level for its first frames
 * while samples mapped through `:engine` arrived rotated.
 *
 * Handing over [CanvasTransform.rotationSin] and [CanvasTransform.rotationCos]
 * buys a second thing: the two implementations then share one trig evaluation
 * rather than each doing their own, so `Math.sin` on ART differing from
 * `Math.sin` on the JVM by an ulp cannot make them disagree. That difference is
 * unmeasurable from a host test, which is exactly why it is designed out
 * instead of asserted away.
 *
 * `postTranslate`, not `preTranslate`. A `pre` there builds
 * `S . R . T`, which puts the translation inside the rotation: it agrees with
 * this exactly at rotation 0 and disagrees by up to 52800 px over
 * `MatricesTest`'s sweep, which is the measured size of "invisible until
 * someone twists the canvas".
 */
fun Matrix.setDocToView(t: CanvasTransform) {
    setSinCos(t.rotationSin, t.rotationCos)
    postScale(t.scale, t.scale)
    postTranslate(t.scale * t.txDoc, t.scale * t.tyDoc)
}

/**
 * A new matrix holding [t]'s document-to-view mapping, for the snapshot that
 * crosses to the render thread at ACTION_DOWN.
 *
 * This one allocates, deliberately, and the allocation is the point. A `Matrix`
 * is mutable native state, so the transform that is handed over has to be one
 * nobody will write again: reuse a single instance and re-fill it on the next
 * stroke and the render thread is concatenating a matrix that changes under it,
 * which is the race `:spike` recorded at `DirectSurfaceInkView.kt:154` and
 * answered by rebuilding rather than sharing. One instance per pen-down is
 * order 1 Hz, and the render callback that runs at up to 320 Hz does no
 * allocation at all.
 *
 * Publishing it needs a `@Volatile` field. Immutability is not in play here and
 * would not be enough anyway: final-field semantics say that a thread which
 * sees a reference sees the fields, not when it sees the reference, and a
 * `Matrix`'s state is native bytes written after construction regardless. W8
 * owns that field and the rule that nothing writes the slot while a stroke is
 * live.
 *
 * Concretely, and naming the one function in this file that can break it: an
 * instance returned from here must never be passed to [setDocToView] again.
 * "Nobody will write it again" is not a property the type system carries — the
 * only Matrix-building API here is an in-place mutator available on every
 * `Matrix` in scope — so a later reader pooling these and re-filling them gets
 * a render thread concatenating a matrix that changes under it, with nothing
 * failing to compile. Reach for [setDocToView] on a matrix you own and nobody
 * else holds; allocate a fresh one for anything published.
 */
fun docToViewMatrix(t: CanvasTransform): Matrix = Matrix().also { it.setDocToView(t) }
