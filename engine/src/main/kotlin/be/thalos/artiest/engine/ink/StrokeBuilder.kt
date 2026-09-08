package be.thalos.artiest.engine.ink

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
class StrokeBuilder(val pen: RoundPen = RoundPen()) : DabEmitter {

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

    /** x, y, radius triples, as `Stroke` lays them out. See [Stroke.STRIDE]. */
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
    fun add(sample: PenSample) {
        add(sample.x, sample.y, sample.pressure, sample.eventTimeNanos)
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
        val radius = pen.sizeFor(pressure, elapsedMillis) * 0.5f
        ensureCapacity()
        val o = dabCount * Stroke.STRIDE
        dabs[o] = x
        dabs[o + 1] = y
        dabs[o + 2] = radius
        dabCount++
        accumulator.add(x, y, radius)
        return pen.spacingFor(radius)
    }

    /**
     * Grows the dab buffer by doubling, and refuses past [MAX_DABS].
     *
     * The cap is a loud failure on purpose, and the arithmetic is what makes
     * that defensible. The tightest spacing any dab can ask for is
     * [RoundPen.MIN_SPACING_DOC], half a document pixel, so [MAX_DABS] dabs is
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
