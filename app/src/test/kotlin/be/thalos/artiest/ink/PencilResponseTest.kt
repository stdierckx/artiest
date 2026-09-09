package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.engine.brush.Sensor
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * What the pencil actually puts on the paper, measured the way the reference
 * sheet was measured.
 *
 * **Why a pixel test and not arithmetic.** `GraphiteReferenceTest` asks the
 * brush what flow it would like for a given pressure, which is a question about
 * a preset and not about a mark. Between that answer and a pixel sit two
 * multiplications nobody had written down:
 *
 * - Dabs are spaced an eighth of a *diameter* apart, so a pixel on the spine of
 *   the stroke is painted by about eight of them and its alpha is
 *   `1 - (1 - flow)^8`, not `flow`. A flow of 0.2 arrives as a coverage of
 *   0.83. That compression is why the pencil had almost no dynamic range: over
 *   most of the pressure range the spine was already saturated, and pressing
 *   harder could only make the stroke wider.
 * - Flow was being applied **twice** — once per dab, from the sensor, and again
 *   as `flowOverride = pen.flow` at the call site, which was correct before
 *   dabs carried their own flow and was never revisited when they did.
 *
 * So the response is measured end to end: a real stroke through
 * `StrokeBuilder`, real dabs through `DabRasterizer`, the real
 * `ScratchLayer` composite with the real grain, and then a scanline across it —
 * the same scanline measurement that produced the reference table in
 * `GraphiteReferenceTest`.
 *
 * The three annotations are the ones `LayerTest` explains: without
 * `@GraphicsMode(NATIVE)` every `getPixel` here returns zero and this file
 * passes while measuring nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PencilResponseTest {

    private val docW = 400
    private val docH = 200

    /** One straight horizontal stroke at a fixed pressure and tilt. */
    private fun draw(
        brush: Brush,
        pressure: Float,
        tiltFraction: Float,
        grain: Boolean = true,
    ): Bitmap {
        val builder = StrokeBuilder(brush)
        builder.begin(Color.BLACK)
        val y = docH / 2f
        var t = 0L
        var x = 40f
        while (x <= docW - 40f) {
            builder.add(
                PenSample(
                    x = x,
                    y = y,
                    pressure = pressure,
                    // Square to the direction of travel, which is how a pencil
                    // is held to hatch and the posture that makes the flat of
                    // the lead widest across the line.
                    tilt = tiltFraction * Sensor.TILT_MAX_RAD,
                    orientation = 0f,
                    distance = 0f,
                    toolType = be.thalos.artiest.engine.input.ToolType.STYLUS,
                    buttonState = 0,
                    eventTimeNanos = t,
                    source = PenSample.Source.CURRENT,
                ),
            )
            x += 2f
            t += 3_107_855L
        }
        val stroke = builder.end()

        val layer = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
        val stamps = StampCache()
        val rasterizer = DabRasterizer(docW, docH, stamps)
        rasterizer.hardness = brush.hardness
        val scratch = ScratchLayer()
        scratch.begin(stroke.bounds)
        val sc = scratch.canvasInDocSpace() ?: error("the scratch would not open")
        rasterizer.drawDry(sc, stroke)
        val shader = if (grain) GrainTexture().shaderFor(brush.grain) else null
        scratch.compositeInto(Canvas(layer), brush.opacity, shader, burnish = brush.burnish)
        return layer
    }

    /**
     * Peak coverage and width, off one vertical scanline through the stroke.
     *
     * Vertical because the stroke runs horizontally; the reference sheet's
     * strokes ran diagonally and were cut horizontally, which is the same
     * measurement turned ninety degrees. Coverage is alpha rather than
     * luminance because the layer carries no paper — `Document`'s invariant —
     * so alpha *is* how dark the mark will be on white.
     */
    /**
     * Coverage, width and grain, off two cuts through the stroke.
     *
     * Coverage is the **mean along the spine**, not the peak of one scanline.
     * The peak of a grained stroke is whichever pixel the noise happened to
     * leave alone, so it moves by a tenth between two presses that differ by
     * nothing — a measurement too noisy to tune against, which is what it was
     * first written as. Width is off a cut across, at the same 6-of-255
     * threshold used to scan the reference sheet. Grain is the spread along the
     * spine relative to its mean, which is the thing an eye reads as texture.
     *
     * Alpha rather than luminance because the layer carries no paper —
     * `Document`'s invariant — so alpha *is* how dark the mark will be on it.
     */
    private data class Mark(val coverage: Float, val width: Int, val grain: Float)

    private fun measure(pressure: Float, tilt: Float = 0f): Mark {
        val bmp = draw(BrushPreset.PENCIL.create(), pressure, tilt)
        var width = 0
        for (y in 0 until docH) {
            if (Color.alpha(bmp.getPixel(docW / 2, y)) > 6) width++
        }
        var lo = 255
        var hi = 0
        var sum = 0L
        var n = 0
        for (x in 120 until docW - 120) {
            val a = Color.alpha(bmp.getPixel(x, docH / 2))
            if (a < lo) lo = a
            if (a > hi) hi = a
            sum += a
            n++
        }
        val mean = sum.toFloat() / n
        return Mark(mean / 255f, width, if (mean <= 0f) 0f else (hi - lo) / mean)
    }

    private fun coverage(pressure: Float, tilt: Float = 0f) = measure(pressure, tilt).coverage

    private fun width(pressure: Float, tilt: Float = 0f) = measure(pressure, tilt).width

    /**
     * **The complaint this file exists for.** A very light press has to leave a
     * mark you can barely see — that is what the first compositional lines of a
     * drawing are made of, and a tool that cannot make them cannot be used to
     * start a drawing.
     */
    @Test
    fun `a very light press leaves a mark that is barely there`() {
        val faint = coverage(0.10f)
        assertTrue(faint < 0.06f, "the lightest press left $faint coverage")
        assertTrue(faint > 0.004f, "it left nothing at all: $faint")
    }

    /** And leaning on it has to go nearly black, or there is no range. */
    @Test
    fun `a hard press goes nearly black`() {
        assertTrue(coverage(1f) > 0.78f, "a full press left only ${coverage(1f)}")
    }

    /**
     * The range itself, stated as the ratio the artist actually asked for. A
     * factor of two is a tool with one tone; this asks for more than ten.
     */
    @Test
    fun `the pencil has a wide dynamic range`() {
        val ratio = coverage(1f) / coverage(0.10f)
        assertTrue(ratio > 20f, "the whole pressure range spanned only ${ratio}x")
    }

    /**
     * Monotone all the way up. A curve that flattens in the middle is a pencil
     * that stops responding exactly where most drawing happens.
     */
    @Test
    fun `every increase in pressure darkens the mark`() {
        val steps = listOf(0.1f, 0.25f, 0.4f, 0.55f, 0.7f, 0.85f, 1f).map { it to coverage(it) }
        for (i in 1 until steps.size) {
            val (p, c) = steps[i]
            val (pPrev, cPrev) = steps[i - 1]
            assertTrue(c > cPrev, "at p=$p coverage was $c, but $cPrev at p=$pPrev")
        }
        // And it has to *use* the middle: half pressure sitting at 95% of the
        // range is a switch, not a curve.
        val mid = steps.first { it.first == 0.55f }.second
        assertTrue(mid in 0.12f..0.70f, "half pressure left $mid, which is an on/off pedal")
    }

    /**
     * Laying the pencil over widens the mark by about the ratio of the lead's
     * side to its point.
     *
     * The user's number, and it is a physical one: a sharpened pencil has a
     * cone about 4 mm long, so the mark it makes on its side is 3 to 4 mm
     * against well under a millimetre on its point. That is a factor of four to
     * five, not the 2.7 measured off the reference sheet — which was drawn with
     * another program's brush and is evidence about that brush, not about
     * pencils.
     */
    @Test
    fun `laid over, the pencil makes a mark several times wider`() {
        val point = width(1f, 0f)
        val side = width(0.7f, 1f)
        assertTrue(side > point * 3f, "on its side $side px against $point on its point")
    }

    /** And the wide mark is a pale one: the same graphite, more paper. */
    @Test
    fun `laid over, the mark is paler than the same press upright`() {
        assertTrue(
            coverage(1f, 1f) < coverage(1f, 0f) * 0.55f,
            "laid over ${coverage(1f, 1f)} against upright ${coverage(1f, 0f)}",
        )
    }

    /**
     * The grain has to be doing something, and it has to be doing less of it
     * where the graphite is thick — leaning on a pencil crushes the tooth flat.
     *
     * Measured as the spread of coverage along the spine of the stroke: with
     * grain the spine is mottled, without it the spine is a constant. The
     * mottling is what has to shrink, relative to the mark's own darkness, as
     * pressure rises.
     */
    @Test
    fun `grain is visible everywhere and weakest under a hard press`() {
        val light = measure(0.35f).grain
        val heavy = measure(1f).grain
        assertTrue(light > 0.4f, "a light stroke had no visible grain at all: $light")
        assertTrue(
            heavy < light * 0.5f,
            "grain was as strong under a hard press ($heavy) as a light one ($light)",
        )
    }
}
