package be.thalos.artiest.engine.xform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the document sits under the viewport: four floats, not a `Matrix`.
 *
 * `android.graphics.Matrix` is out of reach here: `:engine` compiles against
 * no android.jar at all, and in a plain AGP unit test an unmocked android.*
 * method throws "not mocked" rather than computing anything (app/build.gradle.kts
 * leaves `isReturnDefaultValues` unset for exactly that reason). A Matrix-backed
 * transform could only be exercised one module up, in `:app` under Robolectric
 * with @GraphicsMode(NATIVE) — which is what `MatricesTest` does — or on a
 * device. Neither is available to the module that owns the mapping.
 * Components buy more than testability though: a clamp on `scale` and a
 * normalized `rotationRad` are statable as invariants here, where reading them
 * back out of matrix entries means undoing a decomposition first. `Matrices.kt`
 * in `:app` is the only place that turns this into a `Matrix`.
 *
 * The mapping, written out because every consumer inherits it and none of them
 * can see it:
 *
 * ```
 * view = scale * (R(rotationRad) * doc + t)
 *
 * xView = scale * (cos * xDoc - sin * yDoc + txDoc)
 * yView = scale * (sin * xDoc + cos * yDoc + tyDoc)
 * ```
 *
 * Translation is applied after the rotation and before the scale. That is not
 * a free choice: it is the only placement under which a pan of d view pixels is
 * d/[scale] in document units at *every* rotation, which is what the [txDoc]
 * paragraph below asserts. Put the translation inside the rotation instead —
 * `scale * R * (doc + t)` — and a drag needs an inverse rotation as well, and
 * dragging right moves the canvas sideways as soon as the canvas is twisted.
 *
 * `:spike`'s `DirectSurfaceInkView` composes the other way — `setScale` then
 * `postTranslate`, translation in view pixels, view-to-doc as
 * `(viewX - docTx) / scale`. Its formulas agree with these at rotation 0 and
 * scale 1 and disagree everywhere else. Do not copy them.
 *
 * Immutable, and every operation returns a new instance. The render thread
 * snapshots `docToView` at ACTION_DOWN and holds that snapshot for the whole
 * stroke, so a gesture mutating a shared transform underneath it would move
 * ink already committed at the old one. Immutability is all this type can
 * contribute to that rule — there is deliberately no holder, no listener and no
 * mutable global here, so the only way the render side can obtain a mapping is
 * for the UI thread to have handed it one.
 *
 * **The ACTION_DOWN snapshot is this instance, and one instance freezes both
 * threads.** A stroke needs two mappings frozen together — the `Matrix` the
 * render thread concats and the four floats the input thread inverts — and they
 * are handed out as two unrelated objects, so nothing but this sentence stops
 * them skewing. The rule: capture one `CanvasTransform` at ACTION_DOWN, build
 * the stroke's `Matrix` from *that* instance via `Matrices.docToViewMatrix`,
 * and call [viewToDoc] on *that same* instance for every sample until the pen
 * lifts — including the commit at pen-up, which is still the stroke path. Read
 * the live transform for either half and the stroke is drawn at one mapping and
 * recorded at another.
 *
 * All state is `val`, and that buys exactly one of the two things handing one
 * over needs. The seven fields — four in the constructor, three derived — are
 * final and the constructor does not leak `this`, so a thread that obtains the
 * reference sees a fully built transform with no synchronization: a half-built
 * one is not representable. It does not buy visibility of the *reference*. A
 * plain field write can be cached indefinitely, and the render thread would go
 * on reading the transform before it, so the slot holding one has to be
 * `@Volatile`. One writer and no read-modify-write means an `AtomicReference`
 * buys nothing over that — and specifically not its `getAndSet(null)`, which
 * belongs to the wet dab list, where consumption has to be exactly-once so a
 * dropped frame cannot double-stamp. A transform is read-many, and more than
 * one callback in a flush reads it; the second one would get a null mapping.
 * A `Matrix` built from this gets none of the above, because its state is
 * native bytes written after its constructor returns; see
 * [docToViewCoefficients].
 *
 * [txDoc]/[tyDoc] are in **document** units, not pixels — the suffix is not
 * decoration. Translating in view pixels means dividing by [scale] at every
 * use, and the one call site that forgets produces a pan that accelerates as
 * you zoom in. [pannedByView] is that division, done once, in the one place.
 */
data class CanvasTransform(
    val scale: Float = 1f,
    val rotationRad: Float = 0f,
    val txDoc: Float = 0f,
    val tyDoc: Float = 0f,
) {
    init {
        // Rejected rather than clamped, because clamping in the constructor
        // would make `copy(scale = 9f)` look like it succeeded and silently
        // produce a different transform than the caller wrote. Gestures go
        // through [zoomedTo], which clamps; anything reaching here out of range
        // is a bug upstream, and this is where it stops being invisible.
        require(scale in MIN_SCALE..MAX_SCALE) {
            "scale $scale outside $MIN_SCALE..$MAX_SCALE — use zoomedTo() to clamp"
        }
        // Checked because NaN is reachable, not as defensive habit: a rotate
        // gesture divides by the span between two pointers, and two pointers
        // reported at the same coordinate make that span zero — 0/0 is NaN,
        // which then propagates through every later frame silently, since NaN
        // comparisons are false and nothing downstream throws. (atan2(0, 0)
        // itself is not a source; Java returns 0.0 for it.) Same for
        // translation once a pan divides by a scale that arrived as zero.
        require(rotationRad.isFinite()) { "rotationRad was $rotationRad" }
        require(txDoc.isFinite() && tyDoc.isFinite()) {
            "translation was ($txDoc, $tyDoc)"
        }
    }

    // Declared after the init block so the requires run first, and computed
    // once per instance rather than once per sample: `viewToDoc` runs on every
    // PenSample at 250-320 Hz, and a sin/cos pair per call is trig this type
    // already knows the answer to. Body properties, not constructor ones, so
    // equals/hashCode/copy/componentN stay generated from the four floats —
    // the trace file format serializes exactly those four, positionally.
    private val cosR: Float = cos(rotationRad)
    private val sinR: Float = sin(rotationRad)

    // Safe unconditionally, and MIN_SCALE is what makes it safe. The 0.5 floor
    // is usually justified by bilinear filtering (see MIN_SCALE), but it is
    // load-bearing for correctness here too: it is the only reason the inverse
    // map always exists and never divides by zero. Lowering MIN_SCALE toward 0
    // breaks this silently.
    private val invScale: Float = 1f / scale

    /**
     * Document point to view pixels, written into [out] at [offset] and
     * [offset] + 1.
     *
     * An out-param rather than a returned point, because the render path has a
     * zero-allocation budget and the input path calls the inverse once per
     * sample at pen rate; a returned pair would allocate 320 times a second for
     * the life of a stroke. Not a pair of separate x/y functions either: the
     * inverse shares the two subtractions between its components, and split
     * functions would either duplicate that work or tempt a caller into
     * computing one and forgetting the other.
     */
    fun docToView(xDoc: Float, yDoc: Float, out: FloatArray, offset: Int = 0) {
        out[offset] = scale * (cosR * xDoc - sinR * yDoc + txDoc)
        out[offset + 1] = scale * (sinR * xDoc + cosR * yDoc + tyDoc)
    }

    /**
     * View pixels to a document point, written into [out] at [offset] and
     * [offset] + 1. The `toDoc` stage of the input pipeline.
     *
     * No `require` on the coordinates. This runs on every sample, [scale] is
     * already >= [MIN_SCALE] so the division is total, and finite input gives
     * finite output — the only NaN source is a NaN coordinate out of a
     * MotionEvent, which is not this type's failure to notice.
     */
    fun viewToDoc(xView: Float, yView: Float, out: FloatArray, offset: Int = 0) {
        val ux = xView * invScale - txDoc
        val uy = yView * invScale - tyDoc
        out[offset] = cosR * ux + sinR * uy
        out[offset + 1] = -sinR * ux + cosR * uy
    }

    /**
     * Pan by a document-space delta.
     *
     * The raw form. A drag reports view pixels and wants [pannedByView].
     */
    fun pannedBy(dxDoc: Float, dyDoc: Float): CanvasTransform =
        copy(txDoc = txDoc + dxDoc, tyDoc = tyDoc + dyDoc)

    /**
     * Pan by a view-pixel delta — the front door for a drag.
     *
     * This exists so the division by [scale] has exactly one call site. Written
     * out at each gesture handler instead, it is the omission the class KDoc
     * names: a pan that moves the canvas eight times too far at 8x zoom, which
     * reads as an oversensitive gesture rather than as a units bug.
     *
     * There is no rotation term, and that is a property of the composition
     * order, not an oversight. [txDoc] is added after the rotation, so a view
     * delta needs scaling only — the canvas follows the finger whatever the
     * canvas is twisted to.
     */
    fun pannedByView(dxView: Float, dyView: Float): CanvasTransform =
        pannedBy(dxView * invScale, dyView * invScale)

    /** [target] clamped into the legal range. The pinch-gesture entry point. */
    fun zoomedTo(target: Float): CanvasTransform = copy(scale = clampScale(target))

    /**
     * Relative zoom, which is what a pinch actually reports. Composing factors
     * and clamping once at the end means a pinch that overshoots the ceiling
     * and comes back lands where the fingers say, rather than being pinned at
     * 8.0 by the overshoot.
     */
    fun zoomedBy(factor: Float): CanvasTransform {
        require(factor > 0f) { "zoom factor must be positive, was $factor" }
        return zoomedTo(scale * factor)
    }

    /**
     * Zoom by [factor] keeping the document point under the view-space pivot
     * under it. What a pinch does; [zoomedBy] zooms about the document origin,
     * which for a two-finger pinch on a 2160x3300 page throws the content the
     * fingers are on straight off the screen.
     *
     * The document point under the pivot satisfies `R * p + t = pivot / scale`,
     * so holding it fixed at the new scale needs only
     * `t' = t + pivot * (1/scale' - 1/scale)`. No rotation term — the pivot
     * correction is the same at every angle.
     */
    fun zoomedAbout(pivotXView: Float, pivotYView: Float, factor: Float): CanvasTransform {
        require(factor > 0f) { "zoom factor must be positive, was $factor" }
        // Guarded here rather than left to the constructor. The NaN would
        // propagate into the translation and throw there, blaming a translation
        // the caller never wrote. A pivot is a centroid, so it arrives NaN from
        // the one division a gesture controller cannot avoid: 0/0 over an empty
        // or single-frame-stale pointer set.
        require(pivotXView.isFinite() && pivotYView.isFinite()) {
            "pivot was ($pivotXView, $pivotYView)"
        }
        // The clamped scale, not `scale * factor`. Computing the correction
        // from the requested factor while rendering at the clamped one is the
        // bug where a pinch past 8.0 stops zooming but keeps sliding the canvas
        // out from under the fingers, and it only appears at the ceiling. When
        // the clamp fully absorbs the factor this delta is 0 and the transform
        // is unchanged, which is the right answer.
        val applied = clampScale(scale * factor)
        val deltaInv = 1f / applied - invScale
        return copy(
            scale = applied,
            txDoc = txDoc + pivotXView * deltaInv,
            tyDoc = tyDoc + pivotYView * deltaInv,
        )
    }

    /**
     * Rotate, keeping the angle in (-PI, PI].
     *
     * Normalized on every step rather than at the end: rotation accumulates for
     * as long as the canvas is open, and an unbounded float fed to sin/cos
     * loses mantissa bits to the integer turns it is carrying. A few hundred
     * turns is enough to make a slow twist visibly quantize.
     */
    fun rotatedBy(deltaRad: Float): CanvasTransform =
        copy(rotationRad = normalizeRotation(rotationRad + deltaRad))

    /**
     * Rotate by [deltaRad] keeping the document point under the view-space
     * pivot under it — the twist half of a two-finger gesture.
     *
     * With `a = pivot / scale`, holding the point fixed means rotating the
     * translation about `a`: `t' = R(delta) * (t - a) + a`.
     */
    fun rotatedAbout(pivotXView: Float, pivotYView: Float, deltaRad: Float): CanvasTransform {
        // Only the pivot is guarded. A non-finite [deltaRad] reaches the
        // constructor through normalizeRotation and throws there naming
        // rotationRad — which is the correct blame, unlike a NaN pivot, which
        // would surface as a translation the caller never wrote.
        require(pivotXView.isFinite() && pivotYView.isFinite()) {
            "pivot was ($pivotXView, $pivotYView)"
        }
        val ax = pivotXView * invScale
        val ay = pivotYView * invScale
        val c = cos(deltaRad)
        val s = sin(deltaRad)
        val ux = txDoc - ax
        val uy = tyDoc - ay
        return copy(
            rotationRad = normalizeRotation(rotationRad + deltaRad),
            txDoc = c * ux - s * uy + ax,
            tyDoc = s * ux + c * uy + ay,
        )
    }

    /**
     * Fills [into] with document-to-view in the row-major layout
     * `android.graphics.Matrix.setValues` expects: MSCALE_X, MSKEW_X, MTRANS_X,
     * MSKEW_Y, MSCALE_Y, MTRANS_Y, then 0, 0, 1.
     *
     * Not on the render path today. `Matrices.kt` builds the `Matrix` from
     * [rotationSin]/[rotationCos] with postScale/postTranslate, and this array
     * exists so `MatricesTest` can check that matrix against coefficients
     * derived independently rather than against itself. `Canvas` has no
     * float-array `concat` — `concat(Matrix)`, and an API-35 `concat(Matrix44)`
     * a minSdk-29 app cannot call — so a `Matrix` has to exist on the render
     * side whatever this module ships. If these ever become the build path it
     * is the UI thread that calls `setValues`, once per ACTION_DOWN; the
     * front-buffered callback runs at up to 320 Hz doing nothing but
     * save/concat/draw/restoreToCount.
     *
     * The array is a caller-owned scratch buffer and is expected to be reused.
     * The `Matrix` built from it is not: it is mutable native state, so a
     * published one must never be written again. `:spike` recorded that race at
     * `DirectSurfaceInkView.kt:154`.
     */
    fun docToViewCoefficients(into: FloatArray) {
        require(into.size >= COEFFICIENT_COUNT) {
            "need $COEFFICIENT_COUNT coefficients, array holds ${into.size}"
        }
        val c = scale * cosR
        val s = scale * sinR
        into[0] = c
        into[1] = -s
        // scale * t, not scale * R * t. The translation is added after the
        // rotation, so the rotation never reaches it. Rotating it here builds
        // the S.R.T transform instead, which agrees with this one at rotation 0
        // and is wrong at every other angle. `Matrices.kt`'s equivalent is
        // setSinCos(sin, cos); postScale(s, s); postTranslate(s * txDoc,
        // s * tyDoc) — postTranslate takes view pixels, which is why the s is
        // there.
        into[2] = scale * txDoc
        into[3] = s
        into[4] = c
        into[5] = scale * tyDoc
        into[6] = 0f
        into[7] = 0f
        into[8] = 1f
    }

    /**
     * Fills [into] with view-to-document in the same layout as
     * [docToViewCoefficients].
     *
     * Always exists, and needs no invertibility check: the determinant of the
     * linear part is `scale * scale`, and [MIN_SCALE] keeps that at or above
     * 0.25.
     */
    fun viewToDocCoefficients(into: FloatArray) {
        require(into.size >= COEFFICIENT_COUNT) {
            "need $COEFFICIENT_COUNT coefficients, array holds ${into.size}"
        }
        into[0] = cosR * invScale
        into[1] = sinR * invScale
        // -R(-rotationRad) * t. The forward map subtracts the translation
        // before un-rotating, so the inverse's translation column carries the
        // rotation even though the forward one does not.
        into[2] = -(cosR * txDoc + sinR * tyDoc)
        into[3] = -sinR * invScale
        into[4] = cosR * invScale
        into[5] = sinR * txDoc - cosR * tyDoc
        into[6] = 0f
        into[7] = 0f
        into[8] = 1f
    }

    /**
     * `sin(rotationRad)` and `cos(rotationRad)`, already computed.
     *
     * Exposed so `Matrices.kt` can call `Matrix.setSinCos` with the same two
     * floats this type maps with, rather than a degrees round trip.
     * `Matrix.setRotate(degrees)` snaps sin to exactly zero below Skia's
     * SK_ScalarNearlyZero, measured at 1/4096 rad — so it silently discards
     * small rotations, and the drawn canvas would sit level while samples
     * mapped through here arrive rotated.
     */
    val rotationSin: Float get() = sinR

    /** See [rotationSin]. */
    val rotationCos: Float get() = cosR

    companion object {

        /**
         * The floor is 0.5, not 0.35. Bilinear filtering is honest to about 2x
         * minification and 0.35 is past it, so a document zoomed further out
         * shimmers while panning. Fixing that means a mip chain, and generating
         * one over a full layer costs ~11 ms — a guaranteed dropped frame — for
         * nothing Phase 1 gains.
         *
         * It also keeps the inverse map total; see [invScale].
         */
        const val MIN_SCALE = 0.5f

        const val MAX_SCALE = 8.0f

        val IDENTITY = CanvasTransform()

        /** Length `android.graphics.Matrix.setValues` requires. */
        const val COEFFICIENT_COUNT = 9

        // Compared against in Float, not against kotlin.math.PI directly: the
        // Float nearest to pi is *larger* than the Double one, so `r > PI`
        // would be true for exactly half a turn and wrap it to -pi.
        private val PI_F = PI.toFloat()
        private val TWO_PI = (2.0 * PI).toFloat()

        fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

        /**
         * The whole document centred in a [wView] x [hView] viewport at the
         * largest legal scale, level.
         *
         * Int, because `surfaceChanged` and `Bitmap.createBitmap` deal in whole
         * pixels and Int makes a NaN viewport unrepresentable rather than
         * merely rejected.
         *
         * Rotation is reset. Fit is a recovery control — the plan pairs it with
         * double-tap-to-reset "so a lost canvas is always recoverable" — and a
         * recovery that hands back the whole page still at 37 degrees has not
         * recovered the thing that was lost. Preserving the angle is not free
         * either: the fit scale would have to come off the rotated bounding
         * box, so pressing Fit twice at two angles would give two different
         * zooms of the same page.
         *
         * No margin. On this device the panel is exactly 1.5x the document on
         * both axes — 1440x2200, which all five Phase 0 probe dumps report,
         * against the 2160x3300 that is `DOC_W`/`DOC_H` in `:spike`'s
         * `DirectSurfaceInkView` and appears in no dump — so portrait fit is
         * exactly 2/3 with zero letterbox on both axes at once, in float32 and
         * not just in real arithmetic. A fudge factor throws that away. Chrome insets belong in [wView]/[hView]
         * as the content viewport, which the tighter-axis rule then turns into
         * a correct margin on its own.
         *
         * This does **not** guarantee the document is fully visible; it
         * guarantees the largest *legal* scale and a centred document. The two
         * differ only below [MIN_SCALE], which on this device is reached by
         * turning the tablet: landscape 2200x1440 wants 0.436 and gets 0.5, so
         * the page overflows 105 px top and bottom. That is centred overflow,
         * not a bug — no legal scale fits 3300 document rows into 1440 pixels.
         *
         * It says nothing about *when* the result may be applied. The freeze
         * rule is absolute: swapping the live transform while a stroke is down
         * renders one stroke at two transforms. W8/W12 own that guard.
         */
        fun fitTo(wView: Int, hView: Int, wDoc: Int, hDoc: Int): CanvasTransform {
            // A View measured before layout is 0x0, and `:spike` early-returns
            // there, keeping its previous transform. A factory has no previous
            // transform to keep, and the alternatives are both worse than
            // throwing: a zero viewport does not produce NaN, it produces a
            // finite, plausible-looking garbage transform (scale clamped to
            // 0.5, txDoc -1080) that renders as a mysteriously offset page.
            // The call site guards with `if (w > 0 && h > 0)` before calling,
            // exactly as `surfaceChanged` in the spike does.
            require(wView > 0 && hView > 0) { "viewport was ${wView}x$hView" }
            // A zero-size document has no lifecycle excuse, and it degrades
            // quietly rather than loudly: wView / 0f is +Infinity, which
            // clampScale coerces to MAX_SCALE and the constructor accepts
            // without complaint. The bug would ship as an unexplained max zoom.
            require(wDoc > 0 && hDoc > 0) { "document was ${wDoc}x$hDoc" }

            // The tighter axis, so the whole page is on screen; clamped before
            // it is used anywhere else. Centring from the raw ratio and
            // rendering at the clamped one puts the page off-centre by half the
            // difference — invisible in portrait, where the clamp never bites,
            // and visible the moment the tablet is turned.
            val fitted = clampScale(min(wView.toFloat() / wDoc, hView.toFloat() / hDoc))
            return CanvasTransform(
                scale = fitted,
                // Half the viewport measured in document units, minus half the
                // document. Algebraically ((wView - wDoc * s) / 2) / s, which
                // is `:spike`'s view-pixel centring converted to document
                // units, but written so it never multiplies the document extent
                // by the scale only to divide it back out.
                //
                // Not floored. The spike floors to keep a 1:1 blit texel
                // aligned, and says so — at scale 1 a half-pixel translate
                // through isFilterBitmap resamples every dry pixel. That
                // argument does not reach here. floor is a view-pixel
                // operation and txDoc is a document quantity, so a floored
                // offset divided by the scale is not whole anyway; on both real
                // viewports the exact offset is already integral (0/0 portrait,
                // 560/-105 landscape), so it would change nothing; and the
                // first pinch afterwards destroys any alignment it did buy.
                // Snapping is a property of the moment the Matrix is built.
                txDoc = wView / (2f * fitted) - wDoc / 2f,
                tyDoc = hView / (2f * fitted) - hDoc / 2f,
            )
        }

        private fun normalizeRotation(rad: Float): Float {
            var r = rad % TWO_PI
            if (r > PI_F) r -= TWO_PI else if (r <= -PI_F) r += TWO_PI
            // `%` keeps the sign of the dividend, so rotating by -0.5 then +0.5
            // lands on -0.0f. That is == 0.0f as a primitive, but this is a data
            // class: equals() and hashCode() go through java.lang.Float, which
            // separates the two zeroes. Without this line an untouched transform
            // and a there-and-back one are geometrically identical, compare
            // unequal, and hash to different buckets.
            return if (r == 0f) 0f else r
        }
    }
}
