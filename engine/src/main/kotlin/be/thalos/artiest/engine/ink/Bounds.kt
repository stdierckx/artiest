package be.thalos.artiest.engine.ink

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The document-space rectangle a stroke painted into, as four floats.
 *
 * Not `android.graphics.RectF`: `:engine` compiles against no android.jar. And
 * deliberately not `java.awt.geom.Rectangle2D`, which *does* compile here,
 * passes `:engine:test`, gets dexed, and then throws NoClassDefFoundError on
 * the tablet — engine/build.gradle.kts names that trap and this is exactly the
 * type that would fall into it. Geometry in this module is hand-written float
 * math.
 *
 * Phase 1 retains one of these per committed stroke and nothing else about the
 * stroke, because it is what Phase 3's undo snapshots. That makes an
 * *over-large* bounds a correctness bug rather than a wasted blit: undo
 * restores the region this names, so a rectangle inflated past the ink restores
 * pixels a neighbouring stroke owns.
 *
 * Document coordinates, never view. Three independent reasons, and each one
 * alone is enough: the layer bitmap is document-sized and the commit rasterizes
 * into it at identity; the transform is frozen at ACTION_DOWN, so a view-space
 * rectangle is wrong the moment the canvas pans; and Phase 3's undo region has
 * to survive a zoom. A view-space dirty rect is a different computation — four
 * corners through `docToView`, a bounding box, then [FILTER_PAD_DOC] — and it
 * belongs to W12.
 *
 * Immutable, and that is what makes it safe to hang off a [Stroke]. A stroke
 * crosses from the UI thread to the render thread through
 * `AtomicReference.getAndSet(null)` with no lock over its fields; only final
 * fields make a half-built value unrepresentable there. [MutableBounds] is the
 * accumulator, and it must never be the thing a [Stroke] holds.
 *
 * Allocation: [unionWith] allocates one instance, [toString] allocates, and
 * nothing else here does. On the pen path the only allocation is one
 * [MutableBounds.snapshot] per stroke at pen-up.
 *
 * Not a `data class`. The generated public constructor and `copy()` both bypass
 * [of], and [of] is where the finiteness check, the ordering check and the
 * negative-zero normalization live — which is to say, where the invariant this
 * whole type exists for is established.
 */
class Bounds private constructor(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {

    /**
     * True for [EMPTY] and for nothing else.
     *
     * A single comparison, and strictly `>`, so a zero-area rectangle — one
     * zero-radius dab, `left == right` — reports *not* empty. That distinction
     * is load-bearing: "nothing was drawn" and "something infinitesimal was
     * drawn" take different branches at redraw and at undo.
     */
    val isEmpty: Boolean get() = left > right

    /**
     * Zero when empty, guarded rather than computed.
     *
     * Unguarded, `right - left` on [EMPTY] is `-inf - +inf` = -Infinity, not
     * NaN — so a caller that skips the guard gets a value that fails a `> 0`
     * test instead of one that poisons every comparison it touches. The guard
     * is still here so nobody has to know that.
     */
    val width: Float get() = if (isEmpty) 0f else right - left

    /** See [width]. */
    val height: Float get() = if (isEmpty) 0f else bottom - top

    /**
     * The smallest rectangle containing both. Allocates one instance; off the
     * pen path, which unions through [MutableBounds.addBounds] instead.
     *
     * [EMPTY] is the identity on both sides, and it has to be special-cased
     * rather than min/max-ed, because [of] rejects the infinities the empty
     * sentinel is made of.
     */
    fun unionWith(other: Bounds): Bounds = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> of(
            min(left, other.left),
            min(top, other.top),
            max(right, other.right),
            max(bottom, other.bottom),
        )
    }

    /**
     * The integer pixel rectangle this covers, clipped to a [clipWidth] x
     * [clipHeight] page, written into [out] at [offset] as left, top, right,
     * bottom — `android.graphics.Rect`'s constructor order, so `:app` copies it
     * straight across. Returns false and writes nothing when there is nothing
     * to redraw.
     *
     * See [writePixelRect] for why the rounding is what it is. Caller-owned
     * out-array rather than a returned `Rect` because `:engine` cannot name one
     * and a returned `IntArray` would allocate; the same convention
     * `CanvasTransform.docToViewCoefficients` already uses.
     *
     * The page extent is a parameter, and the clip happens here rather than at
     * accumulation, for three reasons. A [Bounds] stays a pure rectangle that
     * can later be clipped against a tile, a viewport or an export region
     * instead of only against the page. Clamping while accumulating destroys
     * the fact that a stroke went off-page — a clamped rectangle looks like a
     * legitimate edge rectangle, whereas returning false here says there is
     * nothing to draw. And the true extent is what Phase 3's undo record should
     * carry, so that it survives a future crop or resize. `:app` passes
     * `Document`'s width and height, which is where `:spike`'s private
     * `DOC_W`/`DOC_H` finally come to rest.
     */
    fun toPixelRect(
        out: IntArray,
        clipWidth: Int,
        clipHeight: Int,
        pad: Float = 0f,
        offset: Int = 0,
    ): Boolean = writePixelRect(left, top, right, bottom, pad, clipWidth, clipHeight, out, offset)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bounds) return false
        return left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom
    }

    // Through toBits, not through the primitives: this has to agree with
    // equals(), and equals() compares primitives where -0.0f == 0.0f. [of]
    // normalizes the negative zero away so the two never disagree; without that
    // normalization this line is where they would.
    override fun hashCode(): Int {
        var h = left.toBits()
        h = 31 * h + top.toBits()
        h = 31 * h + right.toBits()
        h = 31 * h + bottom.toBits()
        return h
    }

    override fun toString(): String =
        if (isEmpty) "Bounds(EMPTY)" else "Bounds($left, $top, $right, $bottom)"

    companion object {

        /**
         * Nothing accumulated: inverted extents, with infinite sentinels.
         *
         * The infinities are not decoration, they are what makes
         * [MutableBounds.add] branch-free — `min(+inf, x - r)` is correct on the
         * first dab, so there is no first-point special case and no `hasAny`
         * flag on the hottest accumulator in the engine.
         *
         * The price, and it is paid in [of] and [MutableBounds.add]: infinity is
         * now a reserved value. An infinite coordinate accepted as input would
         * make a real rectangle indistinguishable from this one, and [isEmpty]
         * would start lying.
         *
         * NaN was the other candidate and is worse on every count: `min`/`max`
         * propagate it so `add` would need branches, `isEmpty` becomes an
         * `isNaN` call, and — the deciding one — NaN is the value a genuine
         * upstream bug produces, so the sentinel and the corruption would be the
         * same state.
         */
        val EMPTY: Bounds = Bounds(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )

        /**
         * How far a rectangle must be grown, in document pixels, before it is
         * used as a dirty region for the *filtered* `docToView` blit.
         *
         * One, derived rather than guessed. The render body's final step is
         * `drawBitmap(layer, 0f, 0f, filterPaint)` with `isFilterBitmap = true`,
         * so each destination pixel reads the 2x2 texel block around its source
         * point, which reaches half a texel past that point on each axis.
         * Rotation does not change that: the kernel is applied in source-texel
         * space, so it stays axis-aligned in the source and the reach is 0.5
         * texels per axis at every angle — the sqrt(2)/2 = 0.707 that suggests
         * itself here is the Euclidean length of the (0.5, 0.5) corner offset,
         * not its axis-aligned extent, and it is the wrong number. Round 0.5 up
         * to a whole texel — the round-up also covers the transform's own
         * rounding — and it is 1. It becomes 2 if the filter ever becomes
         * bicubic, whose 4-tap support reaches 1.5.
         *
         * It does **not** apply to the layer-raster rectangle. See
         * [writePixelRect]: floor/ceil already covers antialiased coverage
         * exactly, and padding that path costs a row and a column of blit on
         * every redraw for nothing.
         */
        const val FILTER_PAD_DOC = 1f

        /**
         * The only way to build a non-empty [Bounds].
         *
         * Rejects the infinities because they are [EMPTY]'s sentinel, rejects
         * NaN because a NaN in a bounds is invisible — `min`/`max` propagate it,
         * [isEmpty] reads false for it, and it surfaces later as a saturated
         * integer rectangle far from whatever produced it. Rejects inversion
         * because every consumer assumes `left <= right`.
         */
        fun of(left: Float, top: Float, right: Float, bottom: Float): Bounds {
            require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
                "bounds ($left, $top, $right, $bottom) is not finite; empty is Bounds.EMPTY"
            }
            require(left <= right && top <= bottom) {
                "inverted bounds ($left, $top, $right, $bottom)"
            }
            return Bounds(zero(left), zero(top), zero(right), zero(bottom))
        }

        // -0.0f is == 0.0f as a primitive but a different value to
        // Float.toBits, so two geometrically identical bounds would compare
        // equal and hash to different buckets — which breaks W7's dab-list
        // goldens rather than anything visible. `CanvasTransform.normalizeRotation`
        // already paid for this once; the source here is `x - radius` where x
        // is 0.0 and radius is 0.0.
        private fun zero(v: Float): Float = if (v == 0f) 0f else v
    }
}

/**
 * The accumulator [Stroke]s are built with: one instance per stroke, reset and
 * refilled, allocating nothing until [snapshot].
 *
 * Separate from [Bounds] rather than one type doing both, because the finished
 * value crosses a thread boundary inside a `Stroke` and a live accumulator
 * there is both a data race and a value that keeps changing after the commit.
 * W7's `StrokeBuilder` owns one of these; W7's `Stroke` gets a [snapshot].
 *
 * Allocation: none, on any method but [snapshot], which allocates one [Bounds]
 * per stroke at pen-up — and not even that when nothing was accumulated.
 */
class MutableBounds {

    var left: Float = Float.POSITIVE_INFINITY
        private set

    var top: Float = Float.POSITIVE_INFINITY
        private set

    var right: Float = Float.NEGATIVE_INFINITY
        private set

    var bottom: Float = Float.NEGATIVE_INFINITY
        private set

    /** See [Bounds.isEmpty]: strictly `>`, so one zero-radius dab is not empty. */
    val isEmpty: Boolean get() = left > right

    /**
     * Grow to cover one dab: centre ([x], [y]) with painted extent [radius].
     *
     * **There is no `add(x, y)` overload and there must never be one.** A bounds
     * accumulated from dab centres is one radius short on all four sides, which
     * leaves a rim of stale ink exactly one radius wide along every edge of
     * every redrawn region — and, because the same rectangle is Phase 3's undo
     * region, an undo that leaves a crust of the stroke it just removed. It is a
     * bug of omission, so the only reliable fix is a signature in which the
     * omission cannot be written. The radius is in the caller's hand at the
     * instant it emits the dab, which is why this is the right place to inflate.
     *
     * Inflating once at pen-up by the stroke's maximum radius is the tempting
     * shortcut and it is wrong: radius varies per dab under pressure, so that
     * rectangle is a correct superset that is too large at every tapered end,
     * and too large is not free — see [Bounds].
     *
     * [radius] means the extent actually painted, not a nominal brush size. The
     * dab paint today is a plain antialiased fill (`:spike`'s `InkPaints`), so
     * the two coincide. Any effect that paints outside `r` — blur, glow,
     * `Style.STROKE`, a shadow layer — silently invalidates every bounds in the
     * document, with no compile error and no test failure until ink smears, and
     * must inflate before calling this.
     *
     * Validated despite running at dab rate, and that is a departure from
     * `CanvasTransform.viewToDoc`, which declines to check at sample rate. The
     * line between them is accumulation: `viewToDoc` is a pure map whose bad
     * output is visible in its own result, while this absorbs a bad value into
     * state that is read minutes later at a redraw or an undo, with nothing
     * pointing back at the dab that caused it. Four comparisons against a
     * min/max chain and a `drawCircle` is not measurable; if W9's allocation and
     * timing trace ever says otherwise, the fix is one check per `PenSample`
     * before the resample loop, not deleting the check.
     */
    fun add(x: Float, y: Float, radius: Float) {
        require(x.isFinite() && y.isFinite()) { "dab centre was ($x, $y)" }
        // isFinite as well as `>= 0f`, because `>= 0f` alone admits +Infinity,
        // and an infinite extent is indistinguishable from Bounds.EMPTY.
        require(radius.isFinite() && radius >= 0f) { "dab radius was $radius" }
        left = min(left, x - radius)
        top = min(top, y - radius)
        right = max(right, x + radius)
        bottom = max(bottom, y + radius)
    }

    /**
     * Union in an already-finished rectangle.
     *
     * No empty check on either side: [Bounds.EMPTY]'s sentinels are exactly the
     * identities of `min` and `max`, so an empty [other] is a no-op and an empty
     * `this` becomes [other], both without a branch.
     */
    fun addBounds(other: Bounds) {
        left = min(left, other.left)
        top = min(top, other.top)
        right = max(right, other.right)
        bottom = max(bottom, other.bottom)
    }

    /**
     * Back to empty. Called at stroke begin, not at stroke end.
     *
     * Forgetting it unions each stroke with the one before. The redraw stays
     * correct-looking — merely large — so nothing catches it until Phase 3's
     * undo over-restores, by which point the cause is several features away.
     */
    fun reset() {
        left = Float.POSITIVE_INFINITY
        top = Float.POSITIVE_INFINITY
        right = Float.NEGATIVE_INFINITY
        bottom = Float.NEGATIVE_INFINITY
    }

    /**
     * The immutable value to hand to a [Stroke]. One allocation, once per
     * stroke; the shared [Bounds.EMPTY] when nothing was accumulated.
     */
    fun snapshot(): Bounds = if (isEmpty) Bounds.EMPTY else Bounds.of(left, top, right, bottom)

    /** See [Bounds.toPixelRect]. */
    fun toPixelRect(
        out: IntArray,
        clipWidth: Int,
        clipHeight: Int,
        pad: Float = 0f,
        offset: Int = 0,
    ): Boolean = writePixelRect(left, top, right, bottom, pad, clipWidth, clipHeight, out, offset)

    override fun toString(): String =
        if (isEmpty) "MutableBounds(EMPTY)" else "MutableBounds($left, $top, $right, $bottom)"
}

/**
 * Float rectangle to half-open integer pixel rectangle, shared by both types.
 *
 * The result is `[l, r) x [t, b)` — `Rect`'s convention, so `w = r - l` — while
 * [Bounds] itself is closed in floats. That mismatch is where a one-pixel
 * sliver comes back if it goes unsaid.
 *
 * **floor the near edges, ceil the far ones**, and with `pad = 0` that is not
 * merely conservative, it is exact. For a filled circle of centre c and radius
 * r under the half-open pixel convention (pixel i covers `[i, i+1)`):
 *
 * - analytic antialiasing, which is what the layer's `Canvas` does, gives a
 *   pixel non-zero coverage iff the disc meets its square, i.e. `i < c + r` and
 *   `i + 1 > c - r`, i.e. `floor(c-r) <= i < ceil(c+r)`;
 * - the GPU circle op's coverage is `clamp(r + 0.5 - |i + 0.5 - c|)`, non-zero
 *   iff `c - r - 1 < i < c + r`, which over the integers is the same set.
 *
 * So the outward rounding already absorbs the half-pixel antialiasing spill.
 * Padding this by 1 "because antialiasing" is the folk answer and it buys
 * nothing; the pad that is real belongs to the filtered blit, see
 * [Bounds.FILTER_PAD_DOC].
 *
 * Every other rounding is wrong in a way that ships:
 * - `round(left)` rounds up whenever the fraction passes 0.5, excluding the
 *   first column the dab painted — a stale column on the left edge of roughly
 *   half of all redraws, and the symmetric case on the right.
 * - `toInt()` truncates toward zero, so `(-0.3f).toInt()` is 0 and pixel -1 is
 *   dropped. Today the clamp to 0 hides that; it stops hiding it the moment
 *   Phase 3 clips against a tile origin that is not the page origin.
 *
 * `Float.toInt()` saturates to `Int.MAX_VALUE` rather than wrapping negative,
 * which is what makes an absurd but finite coordinate safe here — but only
 * because `coerceIn` follows immediately. Do not separate those two lines.
 */
private fun writePixelRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    pad: Float,
    clipWidth: Int,
    clipHeight: Int,
    out: IntArray,
    offset: Int,
): Boolean {
    require(offset >= 0 && out.size >= offset + 4) {
        "need 4 ints at $offset, array holds ${out.size}"
    }
    require(clipWidth >= 0 && clipHeight >= 0) { "clip was ${clipWidth}x$clipHeight" }
    // `>= 0f` is false for NaN, so this rejects both. A negative pad shrinks the
    // rectangle, which is the stale-rim bug written a different way.
    require(pad >= 0f && pad.isFinite()) { "pad was $pad" }
    // Before anything touches the infinities: floor(+inf).toInt() is
    // Int.MAX_VALUE, which would produce a plausible-looking inverted rectangle
    // instead of an obvious failure.
    if (left > right) return false
    val l = floor(left - pad).toInt().coerceIn(0, clipWidth)
    val t = floor(top - pad).toInt().coerceIn(0, clipHeight)
    val r = ceil(right + pad).toInt().coerceIn(0, clipWidth)
    val b = ceil(bottom + pad).toInt().coerceIn(0, clipHeight)
    // Entirely off the page, or clipped to nothing. Nothing is written, so a
    // caller reusing a scratch array cannot act on a half-overwritten one.
    if (r <= l || b <= t) return false
    out[offset] = l
    out[offset + 1] = t
    out[offset + 2] = r
    out[offset + 3] = b
    return true
}
