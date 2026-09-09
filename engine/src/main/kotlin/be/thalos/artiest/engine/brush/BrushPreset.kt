package be.thalos.artiest.engine.brush

/**
 * The tools the app ships with. Three, and the third one earned its place.
 *
 * **Why the marker was refused, and why it is here now.** The plan's first
 * draft specced a marker and its second a broad chisel shader, and both were
 * the same mistake: inventing a second tool to do what one tilted pencil
 * already does. The reference the pencil was measured against is a page of
 * figure studies made with one pencil at two attitudes, so nothing in the
 * evidence asked for a second tool, and this file said the marker would come
 * back only if it was wanted for its own sake. It was asked for by name.
 *
 * That is the standard the list is held to, and [MARKER] meets it on its own
 * terms rather than by being wider: a wedge nib turned by the barrel, one flat
 * pass that darkens where strokes cross, and no paper tooth at all. None of
 * those is something the pencil can be talked into doing.
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
     * Seven things at once, and each is doing a specific job that the others
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
     * - **Pressure sets the darkness, tilt takes it away again.** Flow runs
     *   0.02 to 0.85 on pressure under a 0.90 ceiling, and tilt multiplies it
     *   down to 0.42 of that: the same graphite spread over a wider mark. The
     *   four numbers come off a measured reference sheet — see [applyTo].
     * - **Tilt widens the mark**, by 2.7x over an upright heavy stroke, while
     *   pressure moves the width by only 1.9x. That split is the reference's,
     *   not a guess.
     * - **Grain.** Opacity alone makes a uniformly fainter stroke, which reads
     *   as ink at low alpha. Graphite catches on the paper's tooth and skips
     *   the pits.
     * - **Scatter**, a pixel and a half, so a hatching stroke does not read as
     *   a ruled line.
     * - **Softer edge** at 0.72 rather than 1, because a pencil's mark has no
     *   crisp boundary.
     *
     * Bigger than the pen at 32 doc px, and smoothed less at 0.10: sketching
     * wants the hand's own wobble, which is exactly what a stabilizer removes.
     */
    PENCIL("Pencil") {
        override fun applyTo(brush: Brush) {
            reset(brush)
            brush.sizeMin = 1.5f
            // 48 document pixels, which is 3.5 mm across the flat on this
            // tablet at a fitted page -- the width the side of a sharpened
            // 4 mm cone actually leaves. The slider sets the *widest* mark the
            // pencil can make, the one it makes laid over; the point is a fifth
            // of that, which is where a pencil's point is.
            brush.sizeMax = 48f
            // Soft, and it had never taken effect: `DabRasterizer.hardness`
            // was never assigned from the brush, so every pencil dab up to
            // here was stamped with a hard rim however low this was set.
            brush.hardness = 0.72f
            // **Flow is coverage now.** `StrokeBuilder.emit` inverts the dab
            // overlap, so these numbers are how dark one pass is rather than
            // how much alpha one dab carries -- see `dabAlphaFor`. Before that
            // inversion a flow of 0.3 arrived on the paper as 0.94 and the
            // whole top two thirds of the pressure range was one flat black,
            // which is the "dynamic range is too small" report.
            //
            // The floor is zero. A very light press has to leave a mark you
            // can only just see, because that is what the first lines of a
            // drawing are: composition, measurement, reference. A tool that
            // cannot draw them cannot start a drawing.
            brush.opacity = 0.97f
            brush.flowOption.combine = CurveOption.Combine.MULTIPLY
            brush.flowOption.min = 0f
            brush.flowOption.max = 0.95f
            // Steep, and authored point by point rather than fitted to a power
            // law, because the shape matters most exactly where a power law is
            // least controllable: the bottom fifth, which is the whole of the
            // light end an artist works in.
            brush.flowOption.drive(
                Sensor.PRESSURE,
                // Solved backwards from measured pixels, not authored by
                // feel: `PencilResponseTest` draws the stroke and reads the
                // coverage off it, and these eight points are what that
                // measurement needs in order to land on 0.02 of coverage at a
                // feather touch, 0.15 at a normal sketching press, and 0.86
                // leant on. The curve looks nearly straight and that is the
                // finding -- graphite deposits about linearly with load, and
                // every power law tried here bent it somewhere it should not.
                ResponseCurve.of(
                    0f to 0.02f,
                    0.10f to 0.10f,
                    0.20f to 0.15f,
                    0.35f to 0.26f,
                    0.50f to 0.43f,
                    0.70f to 0.69f,
                    0.85f to 0.86f,
                    1f to 1f,
                ),
            )
            // Laid over, the same graphite covers four to five times the
            // paper, so one pass is that much paler. Not the full geometric
            // ratio: some of the width a tilted pencil gains is the soft
            // shoulder of the cone rather than the flat, and paling by five
            // makes the tilted stroke disappear where the reference sheet
            // still clearly shows one.
            brush.flowOption.drive(
                Sensor.TILT,
                ResponseCurve.of(0f to 1f, 1f to 0.40f),
            )
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
            // Leaning on a pencil crushes the tooth and fills the pits, so a
            // hard press is darker *and* smoother than a light one scaled up.
            // See [Brush.burnish]; 0.75 rather than 1 so the tooth is damped
            // at the top of the range rather than erased.
            brush.burnish = 0.75f
            // **Tilt widens the mark, and pressure barely does.**
            // An ellipse whose minor axis shrinks with tilt gets *narrower*
            // laid over, which is the opposite of a pencil: laying one down
            // puts the side of the lead on the paper and makes a broader mark.
            // So tilt drives the size as well, combined with pressure by
            // MAXIMUM rather than MULTIPLY -- a pencil on its side leaves a
            // broad mark however lightly it is held, and multiplying would
            // make a light tilted stroke vanish.
            //
            // The numbers are the cone's. A sharpened pencil is a cone about
            // 4 mm long; on its point it marks well under a millimetre, on its
            // side it marks the length of the cone. That is a factor of four to
            // five, which is what these fractions of the slider come to: 0.06
            // at rest, 0.18 leant on, 1.0 flat. A reference sheet drawn in
            // another program measured 2.7, and the cone is the better
            // authority -- that sheet is evidence about that program's brush.
            brush.size.combine = CurveOption.Combine.MAXIMUM
            brush.size.drive(
                Sensor.PRESSURE,
                ResponseCurve.of(0f to 0.035f, 0.25f to 0.06f, 0.6f to 0.12f, 1f to 0.18f),
            )
            brush.size.drive(Sensor.TILT, ResponseCurve.power(1.2f))
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

    /**
     * A felt marker with a chisel nib.
     *
     * The third tool, and the reason the second one's KDoc says a marker
     * "comes back only if it is wanted for its own sake": it was wanted. What
     * makes it a different tool rather than a fat pencil is four things, and
     * none of them is size.
     *
     * - **The nib is a wedge, always.** A chisel marker's tip is a flattened
     *   block of felt, so the mark is elliptical whatever the pen is doing —
     *   [aspect] is a constant here rather than a sensor. Turn the barrel and
     *   the wedge turns with it, so a stroke drawn along the nib's long axis is
     *   a hairline and the same stroke across it is the full width. That is the
     *   whole expressive range of a chisel marker, and it comes from
     *   [Sensor.ORIENTATION] alone.
     * - **One pass is flat, two passes are darker.** The defining property, and
     *   the exact opposite of the pencil's. Flow is near 1 and constant, so the
     *   ink does not build up *along* a stroke — no darker patch where the hand
     *   slowed down — and the whole stroke composites once at [opacity] 0.72.
     *   Cross it with a second stroke and the two multiply out to 0.92, which
     *   is why marker drawings have that stack of visible overlaps. Both halves
     *   need the scratch buffer, which is what `opacity < 1` turns on.
     * - **Pressure barely does anything**, because a felt nib is firm. It
     *   splays a little under load — the size runs 0.55 to 0.80 of the range,
     *   about a fifth wider leant on — and it does not get darker at all. A
     *   marker whose darkness followed pressure is a brush pen, which is a
     *   different tool again.
     * - **No grain and no scatter.** Marker ink floods the tooth instead of
     *   sitting on it. The grain field and the burnish that make graphite look
     *   like graphite are both off, and their absence is what makes this read
     *   as ink rather than as a very wide pencil.
     *
     * The edge is soft at 0.88 but nowhere near the pencil's 0.72: felt bleeds
     * a fraction of a millimetre into the paper and then stops.
     *
     * 84 doc px is about 6.2 mm across the long axis of the wedge and 2 mm
     * across the short one at a fitted page, which is a broad marker.
     */
    MARKER("Marker") {
        override fun applyTo(brush: Brush) {
            reset(brush)
            // Not 1.5. A marker has no point: the lightest touch that registers
            // at all still puts the whole nib on the paper, so the floor is a
            // nib and not a dot. This is also what stops the taper at the start
            // of a stroke, which a felt tip does not have.
            brush.sizeMin = 24f
            brush.sizeMax = 84f
            brush.hardness = 0.88f
            // The number that makes it a marker. One pass is 72% covered, two
            // crossing passes are 1 - 0.28^2 = 92%, three are 98%. Push it to 1
            // and overlapping strokes become invisible, which is the single
            // most recognisable thing about drawing with markers.
            brush.opacity = 0.72f
            brush.flowOption.combine = CurveOption.Combine.MULTIPLY
            brush.flowOption.min = 0.94f
            brush.flowOption.max = 1f
            // Nearly flat on purpose. The tiny lift with pressure is the felt
            // pressing more ink out, and it is small enough that a stroke drawn
            // with a varying hand still reads as one even tone.
            brush.flowOption.drive(
                Sensor.PRESSURE,
                ResponseCurve.of(0f to 0.94f, 0.4f to 0.98f, 1f to 1f),
            )
            // More than the pencil. A marker is used for committed lines —
            // outlines, blocking in, lettering — and the hand's tremor that a
            // sketching pencil wants to keep is exactly what spoils one.
            brush.stabilization = 0.28f
            brush.burnish = 0f
            brush.size.combine = CurveOption.Combine.MAXIMUM
            brush.size.drive(
                Sensor.PRESSURE,
                ResponseCurve.of(0f to 0.55f, 0.5f to 0.68f, 1f to 0.80f),
            )
            // A wedge, not a cone: constant, and turned by the barrel rather
            // than by tilt. See the class doc above for why this is the whole
            // tool.
            brush.aspect.min = 0.30f
            brush.aspect.max = 0.30f
            brush.rotation.min = 0f
            brush.rotation.max = MaskSpec.PI_F
            brush.rotation.drive(Sensor.ORIENTATION)
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
        // Size and flow get the wiring back but keep their numbers, and the
        // difference matters: `sizeMax` and `flow` are what two sliders on the
        // toolbar hold, so copying the preset's values over them would undo the
        // user's last drag every time the app started.
        for ((from, to) in listOf(
            fresh.size to brush.size,
            fresh.flowOption to brush.flowOption,
        )) {
            if (to.inputCount > 0) continue
            if (from.inputCount == 0) continue
            to.combine = from.combine
            for (i in 0 until from.inputCount) to.drive(from.sensorAt(i), from.curveAt(i))
        }
    }

    companion object {

        /**
         * Bumped whenever a preset's numbers change, so a saved brush can tell
         * that it was tuned against an older idea of the tool.
         *
         * **Why this is needed at all.** The app restores the brush the user
         * last held, which is right: a moved slider is a decision and it should
         * survive a restart. But it means a retuned preset is invisible to
         * everyone who has ever drawn with the app — the saved scalars win, the
         * new numbers are never loaded, and the tool reads exactly as it did
         * before. That happened here: the pencil was re-solved against a
         * measured reference sheet and the device showed no change whatever,
         * because the tablet had a brush saved from the build before.
         *
         * The cost is one-directional and worth stating: when this number
         * moves, the sliders a user has dragged go back to the preset's, except
         * the two `BrushStore` keeps by hand. That is a worse outcome than
         * keeping everything and a much better one than shipping a change
         * nobody can see.
         *
         * 1. Phase 2's pencil as first written.
         * 2. Re-solved against the user's reference sheet: tilt widens *and*
         *    pales, pressure carries the darkness, the soft edge is connected.
         * 3. Flow became coverage rather than dab alpha, which is what gave the
         *    pencil a real range; tilt widens by the cone's four to five rather
         *    than the sheet's 2.7; a hard press burnishes the tooth flat. The
         *    size slider changed meaning with it — it is now the width of the
         *    mark laid *over*, not on the point — so this bump does not carry
         *    the old value across.
         */
        const val TUNING: Int = 3
    }

    protected fun reset(brush: Brush) {
        val d = Brush()
        // `size` and `flowOption` are cleared here too, and they were not
        // before. Switching pencil to pen left the pencil's tilt-to-size and
        // pressure-to-flow wiring attached to a brush whose whole claim is that
        // it has none, so the pen came back with a tilt-sensitive width and
        // never went back on the fast direct path.
        brush.size.clearInputs()
        brush.size.combine = d.size.combine
        brush.flowOption.clearInputs()
        brush.flowOption.combine = d.flowOption.combine
        brush.flowOption.min = d.flowOption.min
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
        brush.burnish = d.burnish
        brush.eraseSizeMax = d.eraseSizeMax
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
