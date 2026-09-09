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

        /** [rotationRad] folded into 0..PI. */
        fun foldRotation(r: Float): Float {
            var v = r % PI_F
            if (v < 0f) v += PI_F
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
) {

    init {
        require(sizeRatio > 1f) { "sizeRatio must exceed 1, was $sizeRatio" }
        require(hardnessSteps >= 1 && aspectSteps >= 1 && rotationSteps >= 1)
        require(minDiameter > 0f)
    }

    private val lnRatio = ln(sizeRatio.toDouble())

    /** Which geometric bucket [diameter] falls in. */
    fun sizeBucket(diameter: Float): Int {
        val d = if (diameter < minDiameter) minDiameter else diameter
        return Math.round(ln(d.toDouble() / minDiameter) / lnRatio).toInt()
    }

    /** The representative diameter for a bucket — the value the mask is built at. */
    fun sizeForBucket(bucket: Int): Float =
        (minDiameter * Math.pow(sizeRatio.toDouble(), bucket.toDouble())).toFloat()

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
    fun rotationBucket(rotationRad: Float, aspectBucket: Int): Int {
        if (aspectBucket >= aspectSteps) return 0
        val folded = MaskSpec.foldRotation(rotationRad)
        val b = Math.round(folded / MaskSpec.PI_F * rotationSteps).toInt()
        return if (b >= rotationSteps) 0 else b   // PI and 0 are the same angle
    }

    /** [spec] snapped to the nearest representable dab. */
    fun quantize(spec: MaskSpec): MaskSpec {
        val sb = sizeBucket(spec.diameter)
        val ab = aspectBucket(spec.aspect)
        val hb = hardnessBucket(spec.hardness)
        val rb = rotationBucket(spec.rotationRad, ab)
        return MaskSpec(
            diameter = sizeForBucket(sb),
            hardness = hb.toFloat() / hardnessSteps,
            aspect = ab.toFloat() / aspectSteps,
            rotationRad = rb.toFloat() / rotationSteps * MaskSpec.PI_F,
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
        val hb = hardnessBucket(spec.hardness)
        val rb = rotationBucket(spec.rotationRad, ab)
        // 20 bits of size (signed, biased), 8 each of the rest.
        return ((sb + BIAS).toLong() shl 24) or
            (hb.toLong() shl 16) or
            (ab.toLong() shl 8) or
            rb.toLong()
    }

    private companion object {
        /** Keeps a below-minimum diameter's negative bucket inside the shift. */
        const val BIAS = 1 shl 19
    }
}
