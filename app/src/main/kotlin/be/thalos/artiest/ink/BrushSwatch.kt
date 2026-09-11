package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import be.thalos.artiest.doc.Thumbnails
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a brush actually looks like, drawn by drawing with it.
 *
 * ## Why this is a real stroke and not a picture of one
 *
 * A shelf of thirty brushes is only usable if you can tell them apart by
 * looking, and there were three ways to do that. A glyph per brush is free and
 * useless past the three we authored — every imported brush would get the same
 * mark. A cheap approximation, a gradient bar from the size and the opacity, is
 * fast and *lies*: a pencil and a marker at the same size and opacity would
 * look identical, which is precisely the distinction the shelf exists to make.
 *
 * So it is the third: a short S-curve with a pressure ramp and a tilt sweep,
 * built with the real [StrokeBuilder] and stamped by the real [DabRasterizer],
 * through the same scratch buffer and the same grain field a committed stroke
 * goes through. The swatch cannot disagree with the paper, because it is made
 * the same way.
 *
 * There is a second reason, beyond honesty: **this is the renderer the brush
 * import needs later.** A screen showing "what would this Krita brush look like
 * in our engine" is this function called forty times, so building it here makes
 * that item a screen rather than a subsystem.
 *
 * ## The stroke is the same stroke for every brush
 *
 * [DOC_WIDTH] by [DOC_HEIGHT] document pixels, one S-curve, pressure ramping up
 * and easing off, tilt sweeping from upright to nearly flat, and the barrel
 * held at a constant angle the way a hand holds it. Not a line per brush tuned
 * to flatter it: a brush that looked good because its preview stroke was drawn
 * differently would be a shelf that lies in a subtler way than a gradient bar
 * does.
 *
 * Everything is in **document pixels**, which is what makes the comparison
 * mean anything — the marker's 84 px nib is genuinely three and a half times
 * the pen's, and both are reduced by the same amount on the way to the row.
 * Reduced rather than drawn small, by halves, for `Thumbnails`' reason: one big
 * bilinear step drops thin lines entirely and would show a hard pencil as an
 * empty box.
 *
 * ## What it costs, and where it may run
 *
 * A 480x160 page and a scratch buffer of the same order — about 600 KiB of
 * pixels, for the length of one call. Nothing here is retained. It must be
 * called **off the pen's thread**: a shelf that stutters the pen is a worse
 * shelf than no shelf, and there is nothing on this path that needs the render
 * thread's state. [BrushSwatches] is the cache that keeps it to once per brush.
 */
object BrushSwatch {

    /** The page the sample stroke is drawn on, in document pixels. */
    const val DOC_WIDTH = 480

    const val DOC_HEIGHT = 160

    /** Three to one, which is the shape of a row and of the stroke that fits it. */
    const val ASPECT = DOC_WIDTH.toFloat() / DOC_HEIGHT

    /**
     * [brush]'s mark, [width] by [height] pixels, in [colorArgb].
     *
     * Transparent where there is no ink — a swatch is ink on nothing, exactly
     * as a layer is, so the row draws it over whatever ground it wants. See
     * `LayersPanel`'s thumbnail ground for why that ground is a fixed pale one
     * and not a theme colour.
     */
    fun render(brush: Brush, width: Int, height: Int, colorArgb: Int): Bitmap {
        val page = Bitmap.createBitmap(DOC_WIDTH, DOC_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        val stroke = strokeOf(brush, colorArgb)

        val rasterizer = DabRasterizer(DOC_WIDTH, DOC_HEIGHT, StampCache())
        // Always the stamp. The circle path exists as Phase 1's measured A/B
        // control and cannot draw an elliptical or a soft dab at all, so a
        // shelf that used it would show every shaped brush as a round one --
        // which is the failure a swatch is for catching.
        rasterizer.mode = DabRasterizer.Mode.STAMP
        rasterizer.hardness = brush.hardness

        if (!indirectNeeded(brush)) {
            rasterizer.drawDry(canvas, stroke)
            return Thumbnails.reduce(page, width, height)
        }

        // `InkSurfaceView`'s indirect path, minus everything that is about
        // being live: the dabs land on a scratch that starts empty, so the
        // stroke's own overlaps composite against nothing, and the single
        // composite at the end is what carries the opacity and the grain.
        val scratch = ScratchLayer(
            maxWidth = DOC_WIDTH + 2 * ScratchLayer.PAD,
            maxHeight = DOC_HEIGHT + 2 * ScratchLayer.PAD,
        )
        val grain = GrainTexture()
        try {
            scratch.begin(stroke.bounds)
            val sc = scratch.canvasInDocSpace()
            if (sc == null) {
                // The same bargain the live path makes: a beaded stroke is
                // wrong and visible, a missing one is wrong and invisible.
                rasterizer.drawDry(canvas, stroke)
            } else {
                rasterizer.drawDry(sc, stroke)
                scratch.compositeInto(
                    canvas, brush.opacity, grain.shaderFor(brush.grain), false, brush.burnish,
                )
            }
        } finally {
            scratch.release()
            grain.release()
        }
        return Thumbnails.reduce(page, width, height)
    }

    /**
     * `InkSurfaceView.indirectNeeded`, for a brush with no document around it.
     *
     * Deliberately a copy of four clauses rather than a call: the live one also
     * asks about the eraser and the selection, and neither of those is a
     * property of the brush being shown. A swatch of a brush that happens to be
     * erasing should still be a swatch of the brush.
     */
    private fun indirectNeeded(brush: Brush): Boolean =
        brush.opacity < 1f || brush.flow < 1f || brush.hardness < 1f || brush.grain.isActive

    /**
     * The sample stroke: one S-curve, pressure up and off, tilt upright to
     * flat.
     *
     * The numbers are chosen so that every brush has room to show its widest
     * mark — the margin is half the marker's nib — and so that the sweep covers
     * the whole of both sensors rather than the middle of them, because the
     * ends are where two brushes differ most.
     */
    private fun strokeOf(brush: Brush, colorArgb: Int) = StrokeBuilder(brush).run {
        begin(colorArgb)
        val left = MARGIN
        val right = DOC_WIDTH - MARGIN
        val mid = DOC_HEIGHT / 2f
        var t = 0f
        var i = 0
        while (t <= 1f) {
            val x = left + (right - left) * t
            val y = mid + AMPLITUDE * sin(t * 2f * PI.toFloat())
            // Up to full and then off again: the end of a stroke lifting is
            // where a brush with an onset or a taper shows it.
            val pressure = when {
                t < 0.25f -> 0.08f + (t / 0.25f) * 0.72f
                t < 0.7f -> 0.80f + ((t - 0.25f) / 0.45f) * 0.20f
                else -> 1f - ((t - 0.7f) / 0.3f) * 0.85f
            }
            // Upright to nearly flat. `Sensor.TILT` is normalised against
            // PI/2, so this is 0..0.93 of the range.
            val tilt = t * 0.93f * (PI.toFloat() / 2f)
            addTilt(tilt, ORIENTATION, i * SAMPLE_NANOS)
            add(
                PenSample(
                    x = x,
                    y = y,
                    pressure = pressure.coerceIn(0f, 1f),
                    tilt = tilt,
                    orientation = ORIENTATION,
                    distance = 0f,
                    toolType = ToolType.STYLUS,
                    buttonState = 0,
                    eventTimeNanos = i * SAMPLE_NANOS,
                    source = PenSample.Source.CURRENT,
                ),
            )
            t += STEP
            i++
        }
        end()
    }

    /** Half the widest nib the app ships, so nothing is clipped by the page. */
    private const val MARGIN = 46f

    /** Enough of an S to turn the marker's wedge through its whole range. */
    private const val AMPLITUDE = 34f

    /**
     * About a hundred samples, which is what the digitizer delivers over a
     * stroke this long at a normal hand speed. Fewer would let the stabilizer
     * dominate; more only costs time.
     */
    private const val STEP = 0.01f

    /** ~250 Hz, near the DTH-A116's measured 321 Hz and a round number. */
    private const val SAMPLE_NANOS = 4_000_000L

    /**
     * The barrel, held still at 45 degrees.
     *
     * Constant rather than following the direction of travel, because that is
     * what a hand does — you do not roll a marker as you turn a corner. It is
     * also what makes the chisel readable: the S-curve crosses the nib's axis,
     * so the same stroke is a hairline in one place and the full width in
     * another, which is the whole expressive range of a chisel marker.
     */
    private const val ORIENTATION = -PI.toFloat() / 4f

}
