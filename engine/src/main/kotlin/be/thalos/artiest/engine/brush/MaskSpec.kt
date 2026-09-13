package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * What a dab looks like, before it is rasterized: a size, an edge, and a shape.
 *
 * Deliberately not a `Brush`. A brush is a set of ranges and sensors; a
 * [MaskSpec] is one dab's worth of resolved numbers, which is what a cache can
 * be keyed on. The two are separated so the cache never has to know that
 * pressure exists.
 */
data class MaskSpec(
    /** Major-axis diameter in document pixels. */
    val diameter: Float,
    /**
     * Edge hardness, 0..1. At 1 the disc is solid to its rim with only the
     * antialiasing the rasterizer gives it; at 0 it falls off from the centre.
     */
    val hardness: Float,
    /**
     * Minor axis over major, 0..1. 1 is a circle. W9's tilt drives this.
     */
    val aspect: Float,
    /**
     * Major-axis angle in radians.
     *
     * Reduced to 0..PI, not 0..2PI, because an ellipse at θ and at θ+PI are the
     * same shape. Halving the space halves the cache pressure from rotation for
     * free, and forgetting it is the kind of thing that shows up as a
     * mysteriously poor hit rate rather than as a wrong pixel.
     */
    val rotationRad: Float,
    /**
     * The picture this dab is stamped from, or null for the procedural ellipse.
     *
     * **A whole second nib hanging off one nullable field**, and it is the
     * smallest change that could work: everything upstream of here — the
     * sensors, the curves, the spacing, the scratch buffer — asks the same
     * questions of a bristle stub as of a round dab, and only the eleven lines
     * that turn a size into coverage differ. See [Tip].
     *
     * It is the [Tip] and not its id because [MaskGenerator] needs the pixels
     * and looking them up per dab would put a string hash on the path that runs
     * two hundred times an event. The resolution happens once, when the brush
     * is picked up.
     *
     * [hardness] is **ignored** when this is set. A picture brings its own
     * edge, and there is nothing sensible for a hardness of 0.4 to mean over a
     * splatter — Krita does not offer it there either.
     */
    val tip: Tip? = null,
) {

    init {
        require(diameter.isFinite() && diameter > 0f) { "diameter was $diameter" }
        require(hardness.isFinite()) { "hardness was $hardness" }
        require(aspect.isFinite() && aspect > 0f) { "aspect was $aspect" }
        require(rotationRad.isFinite()) { "rotation was $rotationRad" }
    }

    companion object {

        /** A plain round dab of [diameter], hard-edged. */
        fun round(diameter: Float, hardness: Float = 1f): MaskSpec =
            MaskSpec(diameter, hardness, 1f, 0f)

        val PI_F: Float = PI.toFloat()

        val TWO_PI_F: Float = (PI * 2.0).toFloat()

        /** [rotationRad] folded into 0..PI. An ellipse's own symmetry. */
        fun foldRotation(r: Float): Float {
            var v = r % PI_F
            if (v < 0f) v += PI_F
            return v
        }

        /**
         * [rotationRad] folded into 0..2PI, which is what a picture needs.
         *
         * An ellipse at an angle and at that angle plus half a turn are the
         * same shape, and [foldRotation] exploits it. A tip is not symmetric —
         * a bristle fan upside down is a different mark — so a tipped dab folds
         * over the whole turn and pays for it with twice the rotation buckets.
         */
        fun foldTurn(r: Float): Float {
            var v = r % TWO_PI_F
            if (v < 0f) v += TWO_PI_F
            return v
        }
    }
}

/**
 * The tolerance policy: how near two dabs have to be to share a mask.
 *
 * **This is the tuning knob the plan names as the one that decides whether the
 * architecture is viable**, so its two failure directions are stated here
 * rather than discovered later. Too coarse and a pressure ramp visibly
 * stair-steps, because the dab width jumps between buckets instead of growing.
 * Too fine and every dab misses, and a mask generator that runs per dab is
 * slower than the `drawCircle` it replaced, for no gain at all.
 *
 * **Size is quantised geometrically, not in fixed pixel steps, and that is the
 * decision most likely to be questioned.** A fixed step cannot serve both ends
 * of the range: 0.5 px is invisible on a 24 px dab and is a third of the width
 * of a 1.5 px one. A constant *ratio* gives constant relative precision, which
 * is how size error is actually perceived, and it bounds the bucket count
 * logarithmically — 1.5 px to 128 px at 3% is 151 buckets, not thousands.
 *
 * **Rotation collapses when the dab is round.** A circle looks the same at
 * every angle, so keying it by the stroke's travel direction would miss on
 * essentially every dab of a curve while producing identical bitmaps. That is
 * the specific trap the plan's "hit rate collapsing on the ramp strokes"
 * warning describes, and it is cheaper to avoid than to measure.
 */
data class MaskTolerance(
    /** Size ratio between adjacent buckets. 1.03 is 3%. */
    val sizeRatio: Float = 1.03f,
    /** Number of hardness buckets across 0..1. */
    val hardnessSteps: Int = 16,
    /** Number of aspect buckets across 0..1. */
    val aspectSteps: Int = 16,
    /** Number of rotation buckets across 0..PI. */
    val rotationSteps: Int = 64,
    /** Diameters at or below this are quantised to whole tenths instead of geometrically. */
    val minDiameter: Float = 0.5f,
    /**
     * Above this diameter the size step widens from [sizeRatio] to
     * [coarseRatio].
     *
     * **One ratio cannot serve a 1.5 px fineliner and a 600 px airbrush**, for
     * the same reason a fixed pixel step cannot: 3% is right where the nib is
     * small, and where it is large it buys a precision nothing can see while
     * multiplying the number of distinct masks the cache has to hold.
     *
     * A 602 px `ALPHA_8` mask is 353 KiB, so eleven of them fill the whole
     * default budget. At 3% a pressure ramp walks about 150 buckets above this
     * pivot; `BigNibBench` measured that as **579 ms of mask generation for 240
     * dabs, a 40% hit rate and 100 evictions** — several times worse than the
     * airbrush that prompted the work, and reachable by anyone who drags a size
     * slider. At 8% the same ramp walks 29, and one step at 600 px is 48 px of
     * width on a rim that falls off over 180.
     *
     * The pivot is 64 px, which is also `DabRasterizer.SNAP_ABOVE_PX`. Both are
     * answering the same question — *is this nib large enough that half a pixel
     * of anything cannot be seen?* — and two different answers to one question
     * is how a renderer acquires a fudge factor.
     */
    val coarseAbove: Float = 64f,
    /** The size step above [coarseAbove]. See there. */
    val coarseRatio: Float = 1.08f,
) {

    init {
        require(sizeRatio > 1f) { "sizeRatio must exceed 1, was $sizeRatio" }
        require(coarseRatio >= sizeRatio) { "coarseRatio must not be finer than sizeRatio" }
        require(hardnessSteps >= 1 && aspectSteps >= 1 && rotationSteps >= 1)
        require(minDiameter > 0f)
        require(coarseAbove >= minDiameter)
    }

    private val lnRatio = ln(sizeRatio.toDouble())
    private val lnCoarse = ln(coarseRatio.toDouble())

    /** The last bucket of the fine regime. */
    private val pivotBucket: Int =
        Math.round(ln(coarseAbove.toDouble() / minDiameter) / lnRatio).toInt()

    /**
     * The diameter [pivotBucket] stands for, which is where the two regimes
     * meet.
     *
     * Derived from the bucket rather than from [coarseAbove] directly, because
     * the two have to agree exactly: the coarse regime measures from this
     * value, and a pivot half a bucket away from the fine regime's last step
     * would make `sizeBucket(sizeForBucket(n)) != n` right at the join — a
     * cache that misses every dab in one narrow band, which is the hardest kind
     * of miss to find.
     */
    private val pivotDiameter: Float =
        (minDiameter * Math.pow(sizeRatio.toDouble(), pivotBucket.toDouble())).toFloat()

    /** Which geometric bucket [diameter] falls in. */
    fun sizeBucket(diameter: Float): Int {
        val d = if (diameter < minDiameter) minDiameter else diameter
        if (d <= pivotDiameter) {
            return Math.round(ln(d.toDouble() / minDiameter) / lnRatio).toInt()
        }
        return pivotBucket + Math.round(ln(d.toDouble() / pivotDiameter) / lnCoarse).toInt()
    }

    /** The representative diameter for a bucket — the value the mask is built at. */
    fun sizeForBucket(bucket: Int): Float {
        if (bucket <= pivotBucket) {
            return (minDiameter * Math.pow(sizeRatio.toDouble(), bucket.toDouble())).toFloat()
        }
        return (
            pivotDiameter *
                Math.pow(coarseRatio.toDouble(), (bucket - pivotBucket).toDouble())
            ).toFloat()
    }

    fun hardnessBucket(h: Float): Int =
        (h.coerceIn(0f, 1f) * hardnessSteps).roundToInt().coerceIn(0, hardnessSteps)

    fun aspectBucket(a: Float): Int =
        (a.coerceIn(0f, 1f) * aspectSteps).roundToInt().coerceIn(1, aspectSteps)

    /**
     * Rotation bucket, or 0 when the quantised aspect is round.
     *
     * Takes the aspect bucket rather than the raw aspect on purpose: what
     * matters is whether the *mask that will be built* is a circle, and that is
     * decided after quantisation. Testing the raw value would let an aspect of
     * 0.999 keep 64 rotation buckets of identical bitmaps.
     */
    fun rotationBucket(rotationRad: Float, aspectBucket: Int, tip: Tip? = null): Int {
        if (tip != null) {
            // A picture has no symmetry to exploit, so it folds over the whole
            // turn — and it keeps its buckets even when the dab is round,
            // because "round" here describes the *squash*, not the mark. A
            // round bristle stub still faces a direction.
            val steps = rotationSteps * 2
            val folded = MaskSpec.foldTurn(rotationRad)
            val b = Math.round(folded / MaskSpec.TWO_PI_F * steps).toInt()
            return if (b >= steps) 0 else b
        }
        if (aspectBucket >= aspectSteps) return 0
        val folded = MaskSpec.foldRotation(rotationRad)
        val b = Math.round(folded / MaskSpec.PI_F * rotationSteps).toInt()
        return if (b >= rotationSteps) 0 else b   // PI and 0 are the same angle
    }

    /**
     * The angle a rotation bucket stands for, over whichever turn applies.
     *
     * Separate from [rotationBucket] rather than inlined into [quantize]
     * because the two folds have to agree, and a quantised angle that lands in
     * a different bucket than the one it came from is a cache that misses every
     * dab while looking correct in every test of one dab.
     */
    fun rotationForBucket(bucket: Int, tip: Tip?): Float =
        if (tip != null) bucket.toFloat() / (rotationSteps * 2) * MaskSpec.TWO_PI_F
        else bucket.toFloat() / rotationSteps * MaskSpec.PI_F

    /**
     * The hardness bucket a spec actually uses: zero for a tip.
     *
     * [MaskGenerator] ignores hardness over a picture, so letting it into the
     * key would build the same bitmap sixteen times for a brush whose hardness
     * happens to be driven by pressure.
     */
    private fun effectiveHardness(spec: MaskSpec): Int =
        if (spec.tip != null) 0 else hardnessBucket(spec.hardness)

    /** [spec] snapped to the nearest representable dab. */
    fun quantize(spec: MaskSpec): MaskSpec {
        val sb = sizeBucket(spec.diameter)
        val ab = aspectBucket(spec.aspect)
        val hb = effectiveHardness(spec)
        val rb = rotationBucket(spec.rotationRad, ab, spec.tip)
        return MaskSpec(
            diameter = sizeForBucket(sb),
            hardness = hb.toFloat() / hardnessSteps,
            aspect = ab.toFloat() / aspectSteps,
            rotationRad = rotationForBucket(rb, spec.tip),
            tip = spec.tip,
        )
    }

    /**
     * A cache key packing all four buckets into one `Long`.
     *
     * One primitive rather than a `MaskSpec` key: this is looked up once per
     * dab and a `HashMap<MaskSpec, _>` would hash four floats and allocate a
     * boxed key on every one of the corpus's 19,826 dabs.
     */
    fun key(spec: MaskSpec): Long {
        val ab = aspectBucket(spec.aspect)
        val sb = sizeBucket(spec.diameter)
        val hb = effectiveHardness(spec)
        val rb = rotationBucket(spec.rotationRad, ab, spec.tip)
        // 8 bits of tip slot, 20 of size (signed, biased), 8 each of the rest.
        // The slot goes above the size rather than into the spare low bits,
        // because zero has to mean "no tip" and a field that is zero for every
        // procedural dab keeps every key this cache has ever produced exactly
        // where it was.
        return (((spec.tip?.slot ?: 0).toLong()) shl 44) or
            ((sb + BIAS).toLong() shl 24) or
            (hb.toLong() shl 16) or
            (ab.toLong() shl 8) or
            rb.toLong()
    }

    private companion object {
        /** Keeps a below-minimum diameter's negative bucket inside the shift. */
        const val BIAS = 1 shl 19
    }
}
