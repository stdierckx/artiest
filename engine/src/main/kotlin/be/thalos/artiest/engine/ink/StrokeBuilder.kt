package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.DabContext
import be.thalos.artiest.engine.brush.TiltFilter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.Stabilizer

/**
 * Turns a live sequence of [PenSample]s into dabs, and at pen-up into a
 * [Stroke].
 *
 * This is where the plan's pipeline stops being a diagram:
 *
 * ```
 * ... -> toDoc -> predict -> [ stabilize -> Catmull-Rom -> resample -> dab ]
 * ```
 *
 * Everything in brackets happens inside this class, in that order, on the UI
 * thread. Upstream of it, `InputRouter` has already decided this sample belongs
 * to a stroke and `CanvasTransform` has already mapped it into document space;
 * downstream, W8 reads the dabs out for the wet pass and W10 hands [end]'s
 * `Stroke` to the render thread.
 *
 * **The document-space boundary is load-bearing.** Samples arrive already
 * mapped, and every number in this class — dab centres, radii, spacing,
 * bounds — is document space. The transform is frozen at ACTION_DOWN, so a
 * stroke that got as far as this class cannot be affected by a pan or a zoom
 * arriving mid-stroke; and the layer bitmap this eventually rasterizes into is
 * document-sized and stamped at identity. Nothing here may learn about view
 * coordinates. See `Bounds` for the same argument made about the rectangle.
 *
 * **Allocation.** Per stroke: nothing, after the first. The dab buffer grows by
 * doubling and is never shrunk, so a builder that has drawn one long stroke has
 * a buffer big enough for every later one; [begin] resets a count, not a
 * buffer. Per sample: nothing at all — [Stabilizer] writes to fields,
 * [CatmullRomResampler] walks into a `FloatArray`, and [emit] indexes. Per
 * pen-up: two, the `FloatArray` copy inside [Stroke.copyOf] and the
 * [MutableBounds.snapshot]. That is the budget the plan sets, and the one
 * `PenSample` per digitizer sample allocated upstream is the only other thing
 * on the path.
 *
 * **No `Dab` type, and no `DabList`, against the plan's module tree.** The plan
 * lists both. W6 already decided against them when it gave `Stroke` a flat
 * `FloatArray` of x, y, radius triples, and the reasoning holds harder here: a
 * `Dab` object per dab is an allocation per dab on the one path with a
 * per-sample budget, and a thousand-dab stroke is a thousand objects the
 * rasterizer then has to chase pointers through in exactly the order the flat
 * array already lays them out. The plan's tree is amended rather than followed.
 *
 * Not thread-safe. One instance, UI thread, for the life of the app.
 */
class StrokeBuilder(val pen: Brush = Brush()) : DabEmitter {

    /**
     * Rebuilt in [begin] when the toolbar has moved `pen.stabilization` since
     * the last stroke, and not otherwise. [Stabilizer.strength] is a `val`
     * because a filter whose time constant changes mid-stroke is a filter whose
     * output is not a function of its input, and the visible result is the line
     * width and position stepping under the pen. One allocation per slider
     * change is the whole cost of that guarantee.
     */
    private var stabilizer = Stabilizer(pen.stabilization)

    private val resampler = CatmullRomResampler(this)

    private val accumulator = MutableBounds()

    /** W9's tilt and orientation, low-passed. See [TiltFilter]. */
    private val tilt = TiltFilter()

    /** Reused per dab; nothing retains it. See [DabContext]. */
    private val context = DabContext()

    private var randomState: Int = 1
    private var strokeSeed: Int = 0
    private var strokeRandom: Float = 0f
    private var lastSpeed: Float = 0f
    private var lastDirection: Float = 0f
    private var lastSampleNanos: Long = 0L
    private var lastTiltNanos: Long = 0L
    private var lastSampleX: Float = Float.NaN
    private var lastSampleY: Float = Float.NaN

    /** x, y, radius, aspect, rotation, as `Stroke` lays them out. See [Stroke.STRIDE]. */
    private var dabs = FloatArray(INITIAL_DABS * Stroke.STRIDE)

    /** How many dabs the stroke in flight has emitted so far. */
    var dabCount: Int = 0
        private set

    private var colorArgb: Int = 0

    private var downTimeNanos: Long = 0L

    private var sampleCount: Int = 0

    private var open: Boolean = false

    /** True between [begin] and [end]. W8 checks it before reading dabs. */
    val isOpen: Boolean get() = open

    /**
     * Start a stroke in [colorArgb], which is packed ARGB as `Paint.setColor`
     * takes it.
     *
     * Colour is fixed at ACTION_DOWN for the same reason `Stroke` carries
     * `antiAlias`: the wet pass and the dry commit have to paint the same
     * stroke the same way, and a colour read at commit time from a swatch the
     * user has since changed makes the stroke jump colour at pen-up.
     */
    fun begin(colorArgb: Int) {
        if (stabilizer.strength != pen.stabilization) {
            stabilizer = Stabilizer(pen.stabilization)
        }
        stabilizer.reset()
        tilt.reset()
        // Seeded from the stroke counter, so two strokes differ but a replay of
        // the same stroke does not.
        strokeSeed++
        randomState = 0x9E3779B9.toInt() * strokeSeed + 0x85EBCA6B.toInt()
        strokeRandom = nextRandom()
        lastSpeed = 0f
        lastDirection = 0f
        lastSampleNanos = 0L
        lastTiltNanos = 0L
        lastSampleX = Float.NaN
        lastSampleY = Float.NaN
        resampler.begin()
        accumulator.reset()
        dabCount = 0
        sampleCount = 0
        downTimeNanos = 0L
        this.colorArgb = colorArgb
        open = true
    }

    /**
     * Feed one document-space sample.
     *
     * Non-finite coordinates are refused rather than absorbed. They should not
     * reach here — they would have to survive `MotionEvent`, `InputRouter` and
     * a `CanvasTransform` whose own constructor rejects a non-finite scale or
     * rotation — but a NaN that did get through would propagate silently
     * through the spline into `MutableBounds`, and a NaN bounds is a stroke
     * that fails `Stroke.copyOf`'s agreement check at pen-up with no clue where
     * it came from. Failing at the sample that carried it names the source.
     */
    /** The smoothed x the resampler last saw. See [forkSmoothing]. */
    val smoothedX: Float get() = stabilizer.x

    /** The smoothed y. See [smoothedX]. */
    val smoothedY: Float get() = stabilizer.y

    /** The smoothed pressure. See [smoothedX]. */
    val smoothedPressure: Float get() = stabilizer.pressure

    /**
     * Copy this stroke's smoothing state into [into], which must have been
     * built with the same strength.
     *
     * W11's fork, and the reason `Stabilizer.copyStateTo` exists. A speculative
     * tail has to be smoothed the same way the real ink is or it joins the
     * stroke with a visible kink, and it must not *be* the same filter or the
     * next real sample arrives having been dragged toward a guess. Handing out
     * the filter itself would make that distinction a convention; handing out a
     * copy makes it a fact.
     */
    fun forkSmoothing(into: Stabilizer) {
        stabilizer.copyStateTo(into)
    }

    /** The smoothing strength currently in force, for building a fork. */
    val smoothingStrength: Float get() = stabilizer.strength

    fun add(sample: PenSample) {
        addTilt(sample.tilt, sample.orientation, sample.eventTimeNanos)
        add(sample.x, sample.y, sample.pressure, sample.eventTimeNanos)
    }

    /**
     * Feed the tilt half of a sample, which the by-parts [add] cannot carry.
     *
     * Separate because the app converts coordinates in place and hands them
     * over as floats — see the other [add]'s note — while tilt needs no
     * conversion at all: it is an angle of the pen against the glass and has
     * nothing to do with where the document is. Callers that never tilt
     * anything simply do not call this, and every shape dynamic then reads a
     * filter that was never started, which is a tilt of zero: upright.
     *
     * **Fed to the filter here rather than interpolated along the spline.**
     * Tilt is low-passed with a 40 ms time constant against samples 3 ms apart,
     * so it barely moves within one segment, and widening the resampler to
     * interpolate a value that changes by a thousandth of a radian between
     * knots would cost two more arrays on the hottest path in the engine to buy
     * nothing measurable.
     */
    fun addTilt(tiltRad: Float, orientationRad: Float, eventTimeNanos: Long) {
        val dtMillis =
            if (lastTiltNanos == 0L) 0f else (eventTimeNanos - lastTiltNanos) / 1_000_000f
        lastTiltNanos = eventTimeNanos
        tilt.update(tiltRad, orientationRad, dtMillis)
    }

    /**
     * Feed one document-space sample by parts.
     *
     * This overload is the one the app actually calls, and the reason is the
     * word *document* three lines up. A `PenSample` carries the coordinates
     * `MotionEvent` reported, which are **view** coordinates; the pipeline's
     * `toDoc` step sits between the router and here, and it has to produce two
     * floats without allocating a second `PenSample` per digitizer sample.
     * Taking them by parts is what lets the caller convert in place.
     *
     * The [add] above stays because a trace replayed at identity is the one
     * case where view and document space coincide, and every `:engine` test is
     * written against it.
     */
    fun add(xDoc: Float, yDoc: Float, pressure: Float, eventTimeNanos: Long) {
        check(open) { "add() before begin()" }
        require(xDoc.isFinite() && yDoc.isFinite()) {
            "sample $sampleCount was ($xDoc, $yDoc)"
        }
        updateTravel(xDoc, yDoc, eventTimeNanos)
        require(pressure.isFinite()) { "sample $sampleCount pressure was $pressure" }
        if (sampleCount == 0) downTimeNanos = eventTimeNanos
        sampleCount++
        stabilizer.push(xDoc, yDoc, pressure, eventTimeNanos)
        val elapsedMillis = (eventTimeNanos - downTimeNanos) / 1_000_000f
        resampler.add(stabilizer.x, stabilizer.y, stabilizer.pressure, elapsedMillis)
    }

    /**
     * Close the stroke and freeze it.
     *
     * [CatmullRomResampler.end] runs first and can emit — it flushes the
     * segment the one-sample lag was holding — so the dab count and the bounds
     * are not final until it has. A [Stroke] built before that flush is short
     * by the last stretch of ink the user drew, which is the tail of every
     * stroke and therefore the most visible pixel in it.
     *
     * The builder is left closed and reusable. The returned [Stroke] holds a
     * copy, so the next [begin] can overwrite this one's buffer while the
     * render thread is still stamping it.
     */
    fun end(): Stroke {
        check(open) { "end() before begin()" }
        resampler.end()
        open = false
        return Stroke.copyOf(
            dabs = dabs,
            dabCount = dabCount,
            colorArgb = colorArgb,
            antiAlias = pen.antiAlias,
            bounds = accumulator.snapshot(),
        )
    }

    /**
     * Abandon the stroke in flight without producing a [Stroke].
     *
     * ACTION_CANCEL and the exclusivity state machine's DISOWNED both land
     * here. Separate from [end] because the difference is not cosmetic: `end()`
     * commits ink to the layer, and a cancelled stroke must leave the document
     * exactly as it found it. W13 owns the routing; this is the hook.
     */
    fun cancel() {
        open = false
        dabCount = 0
        accumulator.reset()
    }

    /** Dab [i]'s centre x, in document space. See [Stroke.x]. */
    fun x(i: Int): Float = dabs[i * Stroke.STRIDE]

    /** Dab [i]'s centre y. */
    fun y(i: Int): Float = dabs[i * Stroke.STRIDE + 1]

    /** Dab [i]'s painted radius. */
    fun radius(i: Int): Float = dabs[i * Stroke.STRIDE + 2]

    /** Dab [i]'s minor over major. See `Stroke.aspect`. */
    fun aspect(i: Int): Float = dabs[i * Stroke.STRIDE + 3]

    /** Dab [i]'s major-axis angle. See `Stroke.rotation`. */
    fun rotation(i: Int): Float = dabs[i * Stroke.STRIDE + 4]

    /** Dab [i]'s own paint. See `Stroke.flow`. */
    fun flow(i: Int): Float = dabs[i * Stroke.STRIDE + 5]

    /**
     * The stroke's document-space extent so far, for the wet pass's dirty
     * rectangle. Allocates; call it once per frame, not once per dab.
     */
    fun boundsSoFar(): Bounds = accumulator.snapshot()

    /**
     * [DabEmitter]'s callback. Records one dab and answers with the distance to
     * the next.
     *
     * The radius goes into [MutableBounds.add] with the dab, never after and
     * never as a centre — `add` has no two-argument overload precisely so that
     * the stale-rim bug cannot be written here.
     */
    override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float {
        var radius = pen.sizeFor(pressure, elapsedMillis) * 0.5f
        var px = x
        var py = y
        var aspect = 1f
        var rotation = 0f
        var flow = pen.flow

        // W9. Skipped entirely for a brush with no dynamics, which is every
        // brush Phase 1 had: filling a context and evaluating five options
        // costs more than the pen's whole dab, and the pen's answers would all
        // be the constants above.
        if (pen.hasDynamics) {
            val c = context
            c.pressure = pressure
            c.elapsedMillis = elapsedMillis
            c.tiltRad = tilt.tiltRad
            c.orientationRad = tilt.orientationRad
            c.speedDocPxPerMs = lastSpeed
            c.directionRad = lastDirection
            c.randomDab = nextRandom()
            c.randomStroke = strokeRandom
            // Size through the option when it has sensors, so tilt can widen
            // the mark the way laying a pencil over does. Without a sensor the
            // option is the constant max, which is why the pen keeps the
            // two-argument form above.
            if (pen.size.inputCount > 0) radius = pen.sizeFor(c) * 0.5f
            flow = pen.flowOption.valueFor(c)
            aspect = pen.aspect.valueFor(c).coerceIn(ASPECT_MIN, 1f)
            rotation = pen.rotation.valueFor(c)
            val jitter = pen.sizeJitter.valueFor(c)
            if (jitter > 0f) radius *= 1f - jitter * nextRandom()
            val throwPx = pen.scatter.valueFor(c)
            if (throwPx > 0f) {
                val a = nextRandom() * TWO_PI
                px += throwPx * cos(a)
                py += throwPx * sin(a)
            }
        }

        ensureCapacity()
        val o = dabCount * Stroke.STRIDE
        dabs[o] = px
        dabs[o + 1] = py
        dabs[o + 2] = radius
        dabs[o + 3] = aspect
        dabs[o + 4] = rotation
        // **Flow is the stroke's coverage, not the dab's alpha**, and the two
        // are a long way apart. Dabs are laid an eighth of a diameter along the
        // path, so about eight of them paint any pixel on the spine of the
        // stroke and its alpha comes out at `1 - (1 - dabAlpha)^8`. Written
        // straight through, a flow of 0.2 arrived as a coverage of 0.83 and
        // anything above about 0.3 arrived as solid black — which is why the
        // pencil had almost no usable range between "faint" and "saturated",
        // and why tilt's paling was invisible at the top of the pressure range.
        //
        // Inverting the overlap here makes `flow` mean what an artist means by
        // it: how dark one pass is. It also makes that meaning independent of
        // the spacing and of the dab size, so changing either stops silently
        // changing how dark the tool draws.
        val spacing = pen.spacingFor(radius, radius * aspect)
        dabs[o + 5] = dabAlphaFor(flow, alongTravel(radius, aspect), spacing)
        dabCount++
        // The bounds take the *major* radius whatever the aspect, because an
        // ellipse fits inside the circle of its major axis at every rotation.
        // Using the minor axis would leave the undo snapshot short of the ink
        // wherever the dab was turned across the stroke.
        accumulator.add(px, py, radius)
        return spacing
    }

    /**
     * How far the dab reaches along the direction of travel, as a full extent.
     *
     * The minor axis, and not an average of the two: `isotropicSpacing = false`
     * spaces by the minor axis precisely because the flat of a tilted lead
     * lies *across* the line it is drawing, so the minor axis is the one
     * pointing where the pen is going. Isotropic spacing takes the geometric
     * mean, and so does this, for the same reason — the two have to agree or
     * the overlap count is wrong in whichever direction the spacing is right.
     */
    private fun alongTravel(radius: Float, aspect: Float): Float {
        val minor = radius * aspect
        return if (pen.isotropicSpacing) 2f * kotlin.math.sqrt(radius * minor) else 2f * minor
    }

    /**
     * The alpha one dab must carry for a whole pass to arrive at [flow].
     *
     * `n` dabs at alpha `a` composite to `1 - (1 - a)^n`, so the dab that gives
     * a coverage of `flow` is `1 - (1 - flow)^(1/n)`, and `n` is how many dabs
     * fall on one pixel: the dab's extent along the path divided by the
     * spacing.
     *
     * Two guards, both reachable. `n` below 1 happens whenever `spacingFor`
     * hits its half-pixel floor on a tiny dab — there the dabs no longer
     * overlap at all and the dab's own alpha *is* the coverage. And a flow of 1
     * has to stay exactly 1: `1 - 0^(1/n)` is 1 arithmetically but the pen
     * takes this path too, and its dab goldens compare floats exactly.
     */
    private fun dabAlphaFor(flow: Float, extent: Float, spacing: Float): Float {
        if (flow >= 1f) return 1f
        if (!(flow > 0f)) return 0f
        if (!(spacing > 0f)) return flow
        val n = extent / spacing
        if (!(n > 1f)) return flow
        return 1f - (1f - flow).pow(1f / n)
    }

    /**
     * A deterministic 0..1 for scatter and jitter.
     *
     * Its own generator rather than `Math.random`, seeded per stroke, because
     * the dab goldens have to be reproducible: a stroke replayed from the same
     * samples must produce the same dabs, and a shared global generator makes
     * that depend on what else in the process drew first. xorshift because it
     * is four operations and this runs twice a dab.
     */
    /**
     * Speed and heading, from consecutive raw samples.
     *
     * Raw rather than smoothed on purpose: [Sensor.SPEED] is asking how fast
     * the hand is moving, and the stabilizer's output is a lagged version of
     * that which would make a speed-driven brush respond late to exactly the
     * flicks it is meant to catch.
     *
     * A zero or backwards time step holds the previous reading rather than
     * dividing by it. Samples within one `MotionEvent` batch can share a
     * timestamp, which `MotionEvents` documents for a related reason.
     */
    private fun updateTravel(x: Float, y: Float, eventTimeNanos: Long) {
        if (lastSampleX.isFinite()) {
            val dx = x - lastSampleX
            val dy = y - lastSampleY
            val dtMillis = (eventTimeNanos - lastSampleNanos) / 1_000_000f
            if (dtMillis > 0f) lastSpeed = hypot(dx, dy) / dtMillis
            if (dx != 0f || dy != 0f) lastDirection = atan2(dy, dx)
        }
        lastSampleX = x
        lastSampleY = y
        lastSampleNanos = eventTimeNanos
    }

    private fun nextRandom(): Float {
        var v = randomState
        v = v xor (v shl 13)
        v = v xor (v ushr 17)
        v = v xor (v shl 5)
        randomState = v
        return (v ushr 8 and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
    }

    /**
     * Grows the dab buffer by doubling, and refuses past [MAX_DABS].
     *
     * The cap is a loud failure on purpose, and the arithmetic is what makes
     * that defensible. The tightest spacing any dab can ask for is
     * [Brush.MIN_SPACING_DOC], half a document pixel, so [MAX_DABS] dabs is
     * 262,144 document pixels of unbroken path — 121 full traverses of a
     * 2160 px document without the pen leaving the glass. No hand does that.
     * Reaching it means a spacing or a transform is wrong, and a `check` that
     * names the pen beats a 6 MB buffer that keeps doubling behind a stroke
     * nobody drew.
     */
    private fun ensureCapacity() {
        val needed = (dabCount + 1) * Stroke.STRIDE
        if (needed <= dabs.size) return
        check(dabCount < MAX_DABS) {
            "stroke passed $MAX_DABS dabs; spacing is wrong somewhere ($pen)"
        }
        val grown = FloatArray(minOf(dabs.size * 2, MAX_DABS * Stroke.STRIDE))
        dabs.copyInto(grown, 0, 0, dabCount * Stroke.STRIDE)
        dabs = grown
    }

    override fun toString(): String =
        "StrokeBuilder(open=$open, samples=$sampleCount, dabs=$dabCount, $pen)"

    companion object {

        /** The flattest a dab may get. Below this an ellipse is a line and the mask is empty. */
        const val ASPECT_MIN = 0.05f

        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /**
         * 256 dabs, 3 KB. A 24 px nib spaces at 3 px, so this covers a 768 px
         * stroke before the first growth — most strokes, and the ones it does
         * not cover only pay for growth once in the life of the builder.
         */
        const val INITIAL_DABS: Int = 256

        /** See [ensureCapacity]. 524,288 dabs, a 6 MB buffer at the limit. */
        const val MAX_DABS: Int = 1 shl 19
    }
}
