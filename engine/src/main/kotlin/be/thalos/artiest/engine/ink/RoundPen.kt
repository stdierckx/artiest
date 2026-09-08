package be.thalos.artiest.engine.ink

import kotlin.math.pow

/**
 * The one brush Phase 1 ships: a round, hard-edged, fully opaque nib.
 *
 * A plain mutable class carrying the *field names* the Phase 2 document format
 * specifies for a serializable brush, so Phase 2 is a lift rather than a
 * redesign. Nothing here is `data`, nothing is a `val` the UI cannot move, and
 * nothing reads a global — a toolbar slider writes [stabilization] or
 * [sizeMax] on this instance and the next stroke picks it up. The stroke in
 * flight does not, and must not: `Stroke` carries [antiAlias] on itself for
 * exactly that reason, because a wet pass and a dry commit that disagree about
 * the paint make the stroke visibly shift at pen-up.
 *
 * **Tripwire, and the reason it is written here rather than in the plan.**
 * Phase 1 has no scratch FBO, and that is defensible for precisely one brush
 * configuration: fully opaque. Overlapping dabs from an opaque nib composite to
 * the same colour as one dab, so the dark blobs a scratch buffer exists to
 * prevent cannot occur. The moment [opacity] is exposed as a slider, or a
 * translucent brush ships, or a second blend mode appears, the eight dabs this
 * class lays down per nib diameter each composite separately and a slow curve
 * turns into a string of dark beads. **Do not ship any of those three before
 * the scratch buffer exists.** [opacity] is a field here so Phase 2's format
 * has somewhere to deserialize into; it is not a feature.
 */
class RoundPen {

    /**
     * Dab spacing as a fraction of dab *diameter*. 1/8 by default.
     *
     * At 1/8 the dabs overlap seven-eighths of their width, which is what makes
     * a chain of circles read as a stroke instead of as a chain of circles. It
     * is also the number that sets the dab count: a 24 px nib spaces at 3 px,
     * so a 600 px stroke is 200 dabs, and the same stroke with a 1.5 px nib is
     * limited by [MIN_SPACING_DOC] rather than by this fraction.
     */
    var spacing: Float = 0.125f

    /**
     * Diameter in document pixels at zero pressure, and the floor of the size
     * curve. Not zero, and not sub-pixel: a stroke has to leave a visible mark
     * the instant the nib touches, or the light end of the pressure range is
     * indistinguishable from the pen not being down.
     */
    var sizeMin: Float = 1.5f

    /**
     * Diameter in document pixels at full pressure. 24 doc px on a 2160 x 3300
     * document, which is `:spike`'s `1.5f + pressure * 22f` rounded to a round
     * number at the top.
     */
    var sizeMax: Float = 24f

    /**
     * Pressure-to-size exponent. 3 is the plan's cubic and the default.
     *
     * A field rather than a constant because it is one of the Phase 2 brush
     * parameters, and because it is the single knob that decides whether the
     * pen feels dead or twitchy. The cubic is a strong choice and worth stating
     * plainly: at the defaults, a half-pressure press gives
     * `1.5 + 22.5 * 0.125 = 4.3 px`, not the 12.75 px a linear curve would
     * give. That is deliberate — 8192 pressure levels are mostly spent in the
     * light half, and a linear map puts the whole usable range in the first
     * gram of force. It is also the reason [onsetMillis] exists.
     *
     * [sizeFor] special-cases exactly 3 to `p * p * p` rather than calling
     * `pow`, which is a real difference on a path that runs a few hundred times
     * per stroke; other values go through `pow` and are a Phase 2 concern.
     */
    var pressureCurve: Float = 3f

    /**
     * Nib edge hardness, 0..1. Phase 1 paints with `ANTI_ALIAS_FLAG` and
     * nothing else, so this is a format placeholder, not a rendering parameter.
     * It has no reader. Do not add one without the scratch buffer — a soft edge
     * is a translucent rim, and translucent rims are the beading case above.
     */
    var hardness: Float = 1f

    /** See the tripwire in this class's header. Fixed at 1. Not a slider. */
    var opacity: Float = 1f

    /** Passed to `Stabilizer` at [StrokeBuilder] construction. */
    var stabilization: Float = 0.15f

    /** Carried onto every `Stroke` so the wet pass and the commit agree. */
    var antiAlias: Boolean = true

    /**
     * How long the pressure onset floor stays at full strength, in
     * milliseconds, measured from pen-down. 12 ms by default, released linearly
     * over the following 12 ms, so the ramp is finished 24 ms in.
     *
     * **Milliseconds, and not "the first few samples", and that is a
     * correction to the plan.** The plan specifies an onset ramp "over the
     * first few samples", which is the same defect it correctly rejects one
     * paragraph earlier for the stabilizer: four samples is 12.4 ms at
     * 321.75 Hz and 16.2 ms at 246.85 Hz, so a sample-counted ramp changes
     * length by a third when the panel switches refresh rate, and every tuning
     * decision made at one rate is wrong at the other. Wall-clock is the only
     * unit that survives that, and it is what [sizeFor] takes.
     *
     * **What the ramp actually fixes, stated honestly.** The plan says a cubic
     * maps ACTION_DOWN's near-zero pressure "to an invisible tip". With
     * [sizeMin] at 1.5 doc px the tip is not invisible — the measured minimum
     * pressure of 0.00208 cubes to 9e-9, so the first dab is 1.5 px wide, which
     * is a mark. The real complaint is one step less dramatic and still worth
     * fixing: for the first stretch of every stroke, however hard the user
     * actually pressed, the nib sits at its 1.5 px floor and then swells. That
     * reads as the ink starting a beat behind the pen, and it gets
     * misdiagnosed as latency, which is expensive because latency is the one
     * thing this whole architecture is spending its complexity on. The floor
     * lifts the effective pressure so the stroke opens near [onsetPressure]'s
     * width and the real signal takes over underneath it.
     *
     * Set to 0 to disable, which makes [sizeFor] bit-exactly the bare curve —
     * a test, not a claim. See [onsetLift] for the release shape.
     */
    var onsetMillis: Float = 12f

    /**
     * The pressure the onset floor holds during [onsetMillis]. 0.25 cubes to
     * 0.015625, so a stroke opens at `1.5 + 22.5 * 0.015625 = 1.8516` doc px
     * instead of 1.5 — a deliberately small lift. It is a floor and not a
     * substitution: [sizeFor] takes the larger of the measured curve and the
     * lift, so a firm press is untouched by the ramp from its very first
     * sample, which is the case that must not regress.
     */
    var onsetPressure: Float = 0.25f

    /**
     * Dab diameter in document pixels for a given pressure at
     * [elapsedMillis] after pen-down.
     *
     * [pressure] is clamped rather than required to be in range. It arrives
     * from `Stabilizer`, whose output is a convex combination of inputs and so
     * cannot overshoot — but it also arrives interpolated along a Catmull-Rom
     * segment in `CatmullRomResampler`, and the only reason *that* cannot
     * overshoot is that the resampler interpolates pressure linearly on
     * purpose. A negative pressure here would produce a negative radius, and a
     * negative radius reaches `MutableBounds.add` as a rectangle inverted
     * around the dab centre. Clamping is one comparison on a path that already
     * does a cube.
     */
    fun sizeFor(pressure: Float, elapsedMillis: Float): Float {
        var p = pressure
        if (!(p > 0f)) p = 0f // also catches NaN
        if (p > 1f) p = 1f
        val curved = curve(p)
        val lift = onsetLift(elapsedMillis)
        return sizeMin + (sizeMax - sizeMin) * if (curved > lift) curved else lift
    }

    /**
     * [pressureCurve] applied to an already-clamped pressure.
     *
     * Exactly 3 and exactly 1 take multiply paths rather than `pow`, which is a
     * real difference on a path that runs a few hundred times per stroke.
     */
    private fun curve(p: Float): Float = when (pressureCurve) {
        3f -> p * p * p
        1f -> p
        else -> p.pow(pressureCurve)
    }

    /**
     * The onset lift at [elapsedMillis], **in curve space** — already through
     * [curve], so it is a floor on the size fraction rather than on pressure.
     * Full until [onsetMillis], then linearly to zero over an equal span.
     *
     * Curve space, and that is not a rearrangement. Releasing a *pressure*
     * floor linearly and then cubing it releases the width along a cubic, and a
     * cubic sheds most of its height at the start: measured at 321.75 Hz, the
     * first 3 ms of the release moved 55% of the total width change, which is a
     * notch with a soft tail rather than a taper. Releasing the lift after the
     * curve makes the width fall at a constant rate — a quarter of the drop in
     * each of the four samples the release spans, which is as smooth as a 12 ms
     * ramp can be at this sample rate. `RoundPenTest` measures that step.
     *
     * The release is a second span rather than a cliff because the lift is
     * competing with a real pressure signal that is still rising. Dropping it
     * in one step at 12 ms puts a visible notch in the width for any press slow
     * enough that the measured pressure has not yet overtaken it; sliding it
     * out over 12 more milliseconds means the crossing happens wherever the two
     * curves actually meet.
     *
     * The honest limit of that, which `RoundPenTest` pins in both directions:
     * for a press that reaches ordinary drawing force within the ramp, the
     * width never decreases at all. For a press so light that the measured
     * pressure never overtakes the lift, the width *does* ease back down as the
     * lift releases — by at most the onset lift, spread over 12 ms. That is
     * also the right picture: a genuinely feather-light stroke should end up
     * thin.
     */
    private fun onsetLift(elapsedMillis: Float): Float {
        if (onsetMillis <= 0f || !(elapsedMillis >= 0f)) return 0f
        if (elapsedMillis < onsetMillis) return curve(onsetPressure)
        val released = (elapsedMillis - onsetMillis) / onsetMillis
        if (released >= 1f) return 0f
        return curve(onsetPressure) * (1f - released)
    }

    /**
     * Arc distance from one dab to the next, for a dab of [radius].
     *
     * Floored at [MIN_SPACING_DOC]. The floor is not a rounding convenience —
     * it is what makes `CatmullRomResampler`'s walk terminate. A zero-pressure
     * dab with a zero [sizeMin] would return a zero spacing, and a zero spacing
     * is a loop that emits dabs at the same point forever. That the default
     * [sizeMin] is 1.5 does not make the floor optional: [sizeMin] is a public
     * field and Phase 2 deserializes it from a file.
     *
     * Half a document pixel is also the point below which extra dabs stop
     * buying anything — two dabs 0.4 px apart land on the same pixel and cost a
     * draw call to composite identically over themselves.
     */
    fun spacingFor(radius: Float): Float {
        val s = spacing * 2f * radius
        return if (s > MIN_SPACING_DOC) s else MIN_SPACING_DOC
    }

    override fun toString(): String =
        "RoundPen(size=$sizeMin..$sizeMax, spacing=$spacing, curve=$pressureCurve, " +
            "onset=${onsetMillis}ms@$onsetPressure, stab=$stabilization)"

    companion object {

        /** See [spacingFor]. Document pixels. */
        const val MIN_SPACING_DOC: Float = 0.5f
    }
}
