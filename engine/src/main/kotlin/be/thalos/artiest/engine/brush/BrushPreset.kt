package be.thalos.artiest.engine.brush

/**
 * The tools the app ships with. Two, and the third slot is deleted rather than
 * filled.
 *
 * **Why two and not three.** The plan's first draft specced a marker, its
 * second a broad chisel shader, and both were the same mistake: inventing a
 * second tool to do what one tilted pencil already does. The reference this
 * phase is measured against is a page of figure studies made with one pencil at
 * two attitudes, so the use case is sketching and hatching, and a preset list
 * longer than the toolset is a plan describing itself rather than the drawing.
 * The marker is an open question, not a backlog item; it comes back only if it
 * is wanted for its own sake.
 */
enum class BrushPreset(val label: String) {

    /**
     * Phase 1's brush, unchanged and still on the fast path.
     *
     * Opaque, hard-edged, round, cubic pressure to size. Nothing here is
     * translucent, so `InkSurfaceView.indirectNeeded` leaves it on the direct
     * front-buffer path and it costs exactly what it cost before Phase 2
     * started. That is deliberate: the phase adds a pencil, it does not tax the
     * pen to do it.
     */
    PEN("Pen") {
        override fun applyTo(brush: Brush) {
            reset(brush)
        }
    },

    /**
     * The pencil. This is the phase.
     *
     * Six things at once, and each is doing a specific job that the others
     * cannot:
     *
     * - **Elliptical from tilt.** Held upright the nib is round; laid over it
     *   flattens to 0.3, which is the contact patch of a pencil on its side.
     *   This is what lets one tool cover both of the reference's postures
     *   without a mode switch, and W10's judgement is specifically whether it
     *   does.
     * - **Turned by orientation**, so the flat follows the barrel rather than
     *   the direction of travel. A pencil rolled between the fingers changes
     *   its mark without moving.
     * - **Low flow under a high ceiling.** 0.35 flow at 0.85 opacity: each pass
     *   leaves a little and repeated passes darken toward a limit instead of
     *   straight to black. That is what makes hatching build.
     * - **Grain.** Opacity alone makes a uniformly fainter stroke, which reads
     *   as ink at low alpha. Graphite catches on the paper's tooth and skips
     *   the pits.
     * - **Scatter**, a pixel and a half, so a hatching stroke does not read as
     *   a ruled line.
     * - **Softer edge** at 0.85 rather than 1, because a pencil's mark has no
     *   crisp boundary.
     *
     * Bigger than the pen at 32 doc px, and smoothed less at 0.10: sketching
     * wants the hand's own wobble, which is exactly what a stabilizer removes.
     */
    PENCIL("Pencil") {
        override fun applyTo(brush: Brush) {
            reset(brush)
            brush.sizeMin = 1.5f
            brush.sizeMax = 32f
            brush.hardness = 0.85f
            // Less opaque than a pen, and it builds. A stroke tops out at 0.72
            // however often it crosses itself, and each pass lays between 0.10
            // and 0.45 depending on how hard you lean -- so going over the same
            // place darkens it toward the ceiling instead of straight to black.
            brush.opacity = 0.72f
            brush.flowOption.min = 0.10f
            brush.flowOption.max = 0.45f
            brush.flowOption.drive(Sensor.PRESSURE, ResponseCurve.power(1.4f))
            brush.stabilization = 0.10f
            // Tuned against a screenshot rather than guessed, and the first
            // guess was wrong in a specific way: strength 0.55 over a 0.34..0.72
            // window came out as salt and pepper, a scatter of near-black
            // specks on light grey. That is what a *narrow* window does — it
            // polarises the noise, which is right for a tooth and wrong for the
            // whole field. Widening the window keeps the tooth's character in
            // the tails while leaving most of the stroke in the middle, and the
            // strength comes down so the darkest specks are graphite rather
            // than ink.
            // 160 doc px a tile, against a 128-cell fine lattice, puts a grain
            // cell at about 1.25 document pixels -- roughly a fifth of a
            // millimetre on this page, which is paper tooth. The first two
            // attempts were 220 and 256, where a cell was several pixels and
            // the specks could be picked out individually: that reads as dirt
            // on the paper rather than as pencil.
            brush.grain = GrainSpec(
                scaleDocPx = 160f,
                strength = 0.42f,
                cutoffLow = 0.22f,
                cutoffHigh = 0.86f,
                seed = 11,
            )
            // **Tilt widens the mark, and that is the half that was missing.**
            // An ellipse whose minor axis shrinks with tilt gets *narrower*
            // laid over, which is the opposite of a pencil: laying one down
            // puts the side of the lead on the paper and makes a broader mark.
            // So tilt drives the size as well, combined with pressure by
            // MAXIMUM rather than MULTIPLY -- a pencil on its side leaves a
            // broad mark however lightly it is held, and multiplying would
            // make a light tilted stroke vanish.
            brush.size.combine = CurveOption.Combine.MAXIMUM
            brush.size.drive(Sensor.PRESSURE, ResponseCurve.CUBIC)
            brush.size.drive(Sensor.TILT, ResponseCurve.power(1.3f))
            brush.aspect.min = 1f
            brush.aspect.max = 0.30f
            brush.aspect.drive(Sensor.TILT)
            brush.rotation.min = 0f
            brush.rotation.max = MaskSpec.PI_F
            brush.rotation.drive(Sensor.ORIENTATION)
            brush.scatter.min = 1.5f
            brush.scatter.max = 1.5f
            // Spacing by the minor axis, so laying the pencil over lays more
            // dabs rather than leaving gaps across the flat.
            brush.isotropicSpacing = false
        }
    },
    ;

    /** Configure [brush] to be this preset. Overwrites everything it sets. */
    abstract fun applyTo(brush: Brush)

    /** A fresh brush configured as this preset. */
    fun create(): Brush = Brush().also { applyTo(it) }

    /**
     * Re-attach only this preset's sensor wiring, leaving every scalar alone.
     *
     * For restoring a saved brush: [BrushCodec] round-trips the sensors
     * faithfully, but a stored file is also the one place a *partial* brush
     * comes from — an older build's save has no `aspect.drive` line at all —
     * and a pencil that loads without its tilt is a pencil that has silently
     * become a fat pen. Re-applying the wiring costs nothing and cannot be
     * wrong, because the preset is what the wiring is *for*.
     */
    fun applyToShapeOnly(brush: Brush) {
        val fresh = create()
        for ((from, to) in listOf(
            fresh.aspect to brush.aspect,
            fresh.rotation to brush.rotation,
            fresh.scatter to brush.scatter,
            fresh.sizeJitter to brush.sizeJitter,
        )) {
            if (to.inputCount > 0) continue
            to.min = from.min
            to.max = from.max
            to.combine = from.combine
            for (i in 0 until from.inputCount) to.drive(from.sensorAt(i), from.curveAt(i))
        }
    }

    protected fun reset(brush: Brush) {
        val d = Brush()
        brush.sizeMin = d.sizeMin
        brush.sizeMax = d.sizeMax
        brush.sizeCurve = d.sizeCurve
        brush.spacing = d.spacing
        brush.hardness = d.hardness
        brush.opacity = d.opacity
        brush.flow = d.flow
        brush.stabilization = d.stabilization
        brush.antiAlias = d.antiAlias
        brush.onsetMillis = d.onsetMillis
        brush.onsetPressure = d.onsetPressure
        brush.isotropicSpacing = d.isotropicSpacing
        brush.grain = d.grain
        for (o in listOf(brush.aspect, brush.rotation, brush.scatter, brush.sizeJitter)) {
            o.clearInputs()
        }
        brush.aspect.min = 1f
        brush.aspect.max = 1f
        brush.rotation.min = 0f
        brush.rotation.max = 0f
        brush.scatter.min = 0f
        brush.scatter.max = 0f
        brush.sizeJitter.min = 0f
        brush.sizeJitter.max = 0f
    }
}
