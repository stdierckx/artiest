package be.thalos.artiest.engine.brush

/**
 * The brush. Replaces Phase 1's `Brush`, and must not change a single dab
 * doing it.
 *
 * **The acceptance test for this type is that the W7 dab goldens do not move.**
 * A refactor of the brush model that changes the strokes is not a refactor, so
 * the arithmetic below is the arithmetic `Brush` had, moved rather than
 * rewritten: [sizeFor] is still `sizeMin + (sizeMax - sizeMin) * max(curve,
 * lift)`, the curve is still a multiply for exponent 3, and the onset floor is
 * still applied after the curve and still measured in wall-clock milliseconds.
 * What changed is where those numbers live — a [CurveOption] and a
 * [ResponseCurve] instead of two floats and a `when` — which is what lets W9
 * attach tilt to the same parameter without a second code path.
 *
 * **The tripwire, paid at W7 rather than deleted.** It read, from Phase 1 until
 * W6: *Phase 1 has no scratch buffer, and that is defensible for exactly one
 * brush configuration — fully opaque. Overlapping dabs from an opaque nib
 * composite to the same colour as one dab, so the dark beads a scratch buffer
 * exists to prevent cannot occur. The moment opacity becomes a slider, or a
 * translucent brush ships, or a second blend mode appears, the eight dabs laid
 * per nib diameter each composite separately and a slow curve turns into a
 * string of dark beads. Do not ship any of those three before the scratch
 * buffer exists.*
 *
 * It was right, and `ScratchLayerTest` now measures what it was protecting
 * against: five overlapping dabs at 30% reach 218 of 255 drawn directly, and
 * exactly 77 — one dab's worth — through the buffer. So the condition is
 * discharged rather than waived. [opacity] and [flow] are sliders from W7, and
 * what enforces the old warning now is a rule in
 * `InkSurfaceView.indirectNeeded`: any brush with an opacity, flow or hardness
 * below 1 goes through the scratch buffer, and only a fully opaque nib takes
 * the direct path. **The rule to keep is that one, not the ban** — a new brush
 * parameter that makes dabs translucent has to answer to `indirectNeeded`, or
 * the beads come back for it alone.
 *
 * Mutable, non-`data`, and reads no globals, for the reason `Brush` gave: a
 * toolbar slider writes a field on this instance and the next stroke picks it
 * up. The stroke in flight does not and must not, which is why `Stroke` carries
 * [antiAlias] on itself.
 */
class Brush {

    /**
     * Dab diameter in document pixels: the range, and what drives it.
     *
     * The pen leaves this with no sensors attached and applies [sizeCurve] by
     * hand in [sizeFor], because the onset floor has to be applied *between*
     * the curve and the range — see [CurveOption.valueForFraction]. W9's pencil
     * attaches tilt and speed here properly.
     */
    val size: CurveOption = CurveOption(1.5f, 24f)

    /**
     * Diameter at zero pressure. Not zero and not sub-pixel: a stroke has to
     * leave a visible mark the instant the nib touches, or the light end of the
     * pressure range is indistinguishable from the pen not being down.
     */
    var sizeMin: Float
        get() = size.min
        set(v) { size.min = v }

    /**
     * Diameter at full pressure. 24 doc px, which is `:spike`'s
     * `1.5f + pressure * 22f` rounded to a round number at the top.
     */
    var sizeMax: Float
        get() = size.max
        set(v) { size.max = v }

    /**
     * Pressure to size. Cubic by default.
     *
     * The cubic is a strong choice and worth restating: at the defaults a
     * half-pressure press gives `1.5 + 22.5 * 0.125 = 4.3 px`, not the 12.75 px
     * a linear map would give. That is deliberate — 8192 pressure levels are
     * mostly spent in the light half, and a linear map puts the whole usable
     * range in the first gram of force. It is also why [onsetMillis] exists.
     */
    var sizeCurve: ResponseCurve = ResponseCurve.CUBIC

    /**
     * [sizeCurve]'s exponent, as the single float the brush file format stores.
     *
     * Reading it from a table curve gives `NaN`, which is honest: a
     * piecewise-linear curve has no exponent, and returning 1 would let a
     * round-trip through the format silently flatten an authored curve. W12
     * serializes the curve itself; this stays for the simple case and for the
     * slider.
     */
    var pressureCurve: Float
        get() = sizeCurve.power
        set(v) { sizeCurve = ResponseCurve.power(v) }

    /**
     * Dab spacing as a fraction of dab *diameter*. 1/8 by default.
     *
     * At 1/8 the dabs overlap seven-eighths of their width, which is what makes
     * a chain of circles read as a stroke instead of as a chain of circles. It
     * also sets the dab count: a 24 px nib spaces at 3 px, so a 600 px stroke is
     * 200 dabs, and the same stroke with a 1.5 px nib is limited by
     * [MIN_SPACING_DOC] instead.
     */
    var spacing: Float = 0.125f

    /**
     * Nib edge hardness, 0..1. Read by W4's mask generator, which is the only
     * thing that can express it — `drawCircle` has one edge and it is hard.
     *
     * A soft edge is a translucent rim, so a value below 1 puts the stroke on
     * the indirect path for the same reason [flow] does.
     */
    var hardness: Float = 1f

    /**
     * The ceiling the whole stroke composites at, 0..1. A W7 slider.
     *
     * Applied **once**, to the finished stroke, which is what makes it a
     * ceiling: however often the stroke crosses itself it cannot come out
     * darker than this. That is the property the scratch buffer buys, and the
     * reason opacity and [flow] are two controls rather than one.
     */
    var opacity: Float = 1f

    /**
     * Paint laid per dab, 0..1. A W7 slider.
     *
     * Applied to each dab as it is laid, so it *does* build up along a stroke —
     * up to [opacity] and no further. Low flow under a high ceiling is what
     * graphite is: each pass leaves a little and repeated passes darken toward
     * a limit instead of straight to black. Collapsing the two into one slider
     * is the shortcut that makes a pencil impossible.
     */
    var flow: Float = 1f

    /**
     * The paper's tooth. See [GrainField] for why it is generated rather than
     * shipped as an image, and [GrainSpec.scaleDocPx] for why it is measured in
     * document pixels rather than in dab widths.
     *
     * Inactive by default, so the pen is untouched by W8. A brush with an
     * active grain is translucent by construction — the grain multiplies alpha
     * — so it goes through the scratch buffer for the same reason [flow] does.
     */
    var grain: GrainSpec = GrainSpec()

    /**
     * The dab's minor axis over its major. W9.
     *
     * Both ends are 1 by default and no sensor is attached, so every dab the
     * pen lays is a circle and the dab goldens do not move. The pencil sets
     * `min = 1, max = 0.25` and drives it from [Sensor.TILT]: held upright the
     * nib is round, laid over it flattens into the elongated contact patch a
     * real pencil makes. The range is inverted on purpose — [CurveOption]
     * interpolates either way, and "more of this sensor means less of this
     * value" is a real brush rather than a special case.
     */
    val aspect: CurveOption = CurveOption(1f, 1f)

    /**
     * The dab's major-axis angle, in radians. W9.
     *
     * Driven from [Sensor.ORIENTATION] for the pencil, whose reading is
     * `(orientation + PI) / 2PI`, so a range of `0..PI` maps a full turn of the
     * pen onto the ellipse's whole symmetry period — an ellipse at θ and θ+PI
     * being the same shape. Zero-width by default, so the pen's dabs have a
     * rotation of exactly 0.
     */
    val rotation: CurveOption = CurveOption(0f, 0f)

    /**
     * How far a dab may be thrown off the path, in document pixels. W9.
     *
     * The direction is random per dab; this is only the distance. Scatter is
     * what stops a hatching stroke reading as a ruled line — real graphite
     * skips sideways off the tooth of the paper — and it is the one shape
     * parameter that can make a stroke *wider* than its nib.
     */
    val scatter: CurveOption = CurveOption(0f, 0f)

    /**
     * Per-dab size variation, as a fraction of the dab's own size. W9.
     *
     * 0.3 means each dab is between 70% and 100% of the size the curve asked
     * for. One-sided downward rather than symmetric, because a jitter that can
     * *grow* a dab makes the stroke's bounds unpredictable, and the bounds are
     * what undo snapshots.
     */
    val sizeJitter: CurveOption = CurveOption(0f, 0f)

    /** Passed to `Stabilizer` at `StrokeBuilder` construction. */
    var stabilization: Float = 0.15f

    /** Carried onto every `Stroke` so the wet pass and the commit agree. */
    var antiAlias: Boolean = true

    /**
     * How long the pressure onset floor holds at full strength, in
     * milliseconds from pen-down. 12 ms, released linearly over 12 more.
     *
     * **Milliseconds, not samples, and Phase 2 does not get to relitigate it.**
     * Four samples is 12.4 ms at 321.75 Hz and 16.2 ms at 246.85 Hz, so a
     * sample-counted ramp changes length by a third when the panel switches
     * refresh rate and every tuning decision made at one rate is wrong at the
     * other.
     *
     * **What it fixes.** With [sizeMin] at 1.5 doc px the first dab is not
     * invisible — the measured minimum pressure of 0.00208 cubes to 9e-9, and
     * 1.5 px is a mark. The real complaint is smaller and still worth fixing:
     * for the first stretch of every stroke, however hard the user actually
     * pressed, the nib sits at its floor and then swells, which reads as the
     * ink starting a beat behind the pen and gets misdiagnosed as latency —
     * expensive, because latency is what this whole architecture spends its
     * complexity on.
     *
     * Set to 0 and [sizeFor] is bit-exactly the bare curve. A test, not a claim.
     */
    var onsetMillis: Float = 12f

    /**
     * The pressure the onset floor holds during [onsetMillis]. 0.25 cubes to
     * 0.015625, so a stroke opens at 1.8516 doc px instead of 1.5 — a
     * deliberately small lift. A floor, not a substitution: [sizeFor] takes the
     * larger of the measured curve and the lift, so a firm press is untouched
     * from its very first sample.
     */
    var onsetPressure: Float = 0.25f

    /**
     * Dab diameter in document pixels for [pressure] at [elapsedMillis] after
     * pen-down.
     *
     * [pressure] is clamped rather than required in range. It arrives from
     * `Stabilizer`, whose output is a convex combination and cannot overshoot,
     * but also interpolated along a Catmull-Rom segment — and the only reason
     * *that* cannot overshoot is that the resampler interpolates pressure
     * linearly on purpose. A negative pressure becomes a negative radius, which
     * reaches `MutableBounds.add` as a rectangle inverted around the dab centre.
     */
    fun sizeFor(pressure: Float, elapsedMillis: Float): Float {
        val curved = sizeCurve.evaluate(pressure)
        val lift = onsetLift(elapsedMillis)
        return size.valueForFraction(if (curved > lift) curved else lift)
    }

    /**
     * Dab diameter for a full sensor context, which is what W9's pencil needs.
     *
     * The onset floor still applies, and still after the curve. When [size] has
     * no sensors attached this is the constant [sizeMax] rather than the
     * pressure curve — that is [CurveOption]'s documented empty case, and it is
     * why the pen uses [sizeFor] instead of this.
     */
    fun sizeFor(c: DabContext): Float {
        val combined = size.combined(c)
        val lift = onsetLift(c.elapsedMillis)
        return size.valueForFraction(if (combined > lift) combined else lift)
    }

    /**
     * The onset lift at [elapsedMillis], **in curve space** — already through
     * [sizeCurve], so it floors the size fraction rather than the pressure.
     *
     * Curve space, and that is not a rearrangement. Releasing a *pressure*
     * floor linearly and then cubing it releases the width along a cubic, and a
     * cubic sheds most of its height at the start: measured at 321.75 Hz, the
     * first 3 ms of the release moved 55% of the total width change — a notch
     * with a soft tail rather than a taper. Releasing after the curve makes the
     * width fall at a constant rate.
     *
     * The release is a second span rather than a cliff because the lift
     * competes with a real pressure signal that is still rising. Dropping it in
     * one step at 12 ms puts a visible notch in the width for any press slow
     * enough that measured pressure has not yet overtaken it.
     */
    private fun onsetLift(elapsedMillis: Float): Float {
        if (onsetMillis <= 0f || !(elapsedMillis >= 0f)) return 0f
        val full = sizeCurve.evaluate(onsetPressure)
        if (elapsedMillis < onsetMillis) return full
        val released = (elapsedMillis - onsetMillis) / onsetMillis
        if (released >= 1f) return 0f
        return full * (1f - released)
    }

    /**
     * Whether spacing ignores a dab's aspect ratio.
     *
     * Only reaches anything once W9's dabs can be elliptical; for a round dab
     * the two branches of [spacingFor] agree exactly, which is why adding this
     * moves no golden.
     *
     * **False by default, and the default is the conservative one.** With an
     * anisotropic dab the two sensible answers are the minor radius and the
     * equal-area radius. The minor radius is tighter, so it lays more dabs and
     * cannot leave gaps across the ellipse's narrow direction; the equal-area
     * radius is orientation-independent, so a pencil rolled through a quarter
     * turn mid-stroke does not change its dab density. Gaps are a visible
     * defect and a density change is a subtle one, so the default protects
     * against the visible one and this flag buys the other behaviour when W10
     * decides it wants it.
     */
    var isotropicSpacing: Boolean = false

    /**
     * Arc distance to the next dab for an elliptical dab.
     *
     * [radiusMinor] governs when [isotropicSpacing] is false, and the
     * equal-area radius `sqrt(major * minor)` when it is true. For a round dab
     * the two are the same number, so this is [spacingFor] with extra steps
     * until W9 makes the radii differ.
     */
    fun spacingFor(radiusMajor: Float, radiusMinor: Float): Float {
        val a = if (radiusMajor > 0f) radiusMajor else 0f
        val b = if (radiusMinor > 0f) radiusMinor else 0f
        val r = if (isotropicSpacing) kotlin.math.sqrt(a * b) else if (a < b) a else b
        return spacingFor(r)
    }

    /**
     * Arc distance from one dab to the next, for a dab of [radius].
     *
     * Floored at [MIN_SPACING_DOC], and the floor is not a rounding
     * convenience: it is what makes `CatmullRomResampler`'s walk terminate. A
     * zero-pressure dab with a zero [sizeMin] returns a zero spacing, and a
     * zero spacing is a loop emitting dabs at the same point forever. That the
     * default [sizeMin] is 1.5 does not make it optional — [sizeMin] is public
     * and W12 deserializes it from a file.
     *
     * Half a document pixel is also where extra dabs stop buying anything: two
     * dabs 0.4 px apart land on the same pixel and cost a draw call to
     * composite identically over themselves.
     *
     * **This is already recomputed per dab from the dab just laid**, which is
     * what W3 was scheduled to change. Phase 1 built it that way: the emitter
     * returns this value and `CatmullRomResampler` sets its `need` from it
     * after every dab. Measured on the golden corpus, the taper stroke runs
     * radius 0.773..10.255 with gaps 0.500..2.564 -- exactly `0.25 * radius`,
     * floored -- so the plan's predicted golden break cannot happen and did
     * not.
     */
    fun spacingFor(radius: Float): Float {
        val s = spacing * 2f * radius
        return if (s > MIN_SPACING_DOC) s else MIN_SPACING_DOC
    }

    /** A deep copy. W10 hands presets out; a shared instance would let one preset's slider move another's. */
    fun copy(): Brush = Brush().also {
        it.size.min = size.min
        it.size.max = size.max
        it.sizeCurve = sizeCurve
        it.spacing = spacing
        it.hardness = hardness
        it.opacity = opacity
        it.flow = flow
        it.stabilization = stabilization
        it.antiAlias = antiAlias
        it.onsetMillis = onsetMillis
        it.onsetPressure = onsetPressure
        it.isotropicSpacing = isotropicSpacing
        it.grain = grain
        copyOption(aspect, it.aspect)
        copyOption(rotation, it.rotation)
        copyOption(scatter, it.scatter)
        copyOption(sizeJitter, it.sizeJitter)
    }

    /** Whether any W9 shape dynamic is switched on. */
    val hasShapeDynamics: Boolean
        get() = aspect.inputCount > 0 || rotation.inputCount > 0 ||
            scatter.inputCount > 0 || sizeJitter.inputCount > 0 ||
            aspect.min != 1f || aspect.max != 1f ||
            scatter.max != 0f || sizeJitter.max != 0f

    override fun toString(): String =
        "Brush(size=$sizeMin..$sizeMax, spacing=$spacing, curve=$sizeCurve, " +
            "onset=${onsetMillis}ms@$onsetPressure, stab=$stabilization)"

    companion object {

        private fun copyOption(from: CurveOption, to: CurveOption) {
            to.min = from.min
            to.max = from.max
            to.combine = from.combine
            to.clearInputs()
            for (i in 0 until from.inputCount) to.drive(from.sensorAt(i), from.curveAt(i))
        }

        /** See [spacingFor]. Document pixels. */
        const val MIN_SPACING_DOC: Float = 0.5f

        /**
         * Phase 1's brush, exactly: round, hard-edged, fully opaque, cubic
         * pressure to size, 1/8 spacing.
         *
         * The defaults already are this, so the factory is a name rather than a
         * configuration — and that is the point. If it ever has to set a field
         * to reproduce Phase 1, the defaults have drifted and the goldens will
         * have said so first.
         */
        fun pen(): Brush = Brush()
    }
}
