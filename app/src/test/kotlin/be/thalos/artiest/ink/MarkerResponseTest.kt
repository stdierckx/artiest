package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.engine.ink.StrokeBuilder
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.test.assertTrue

/**
 * What the marker puts on the paper, measured the way the pencil was.
 *
 * `BrushPresetTest` checks the numbers in the preset; this checks the marks
 * they produce, because the three properties that make a marker a marker are
 * all properties of pixels rather than of parameters:
 *
 * - one pass is **flat** — no build-up along the stroke, no tooth;
 * - a second pass over the first is **darker**, which is what makes a marker
 *   drawing look like one;
 * - the nib is a **wedge turned by the barrel**, so the same stroke is a
 *   hairline or a broad band depending on how the pen is held.
 *
 * The three annotations are the ones `LayerTest`'s header explains: without
 * `@GraphicsMode(NATIVE)` every `getPixel` here returns zero and this file
 * passes while measuring nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkerResponseTest {

    private val docW = 400
    private val docH = 240

    /**
     * Lay one horizontal stroke into [target], composited the way the app
     * composites it.
     *
     * Takes the target rather than returning one, so that a second call draws
     * *over* the first — which is the whole of the overlap test, and cannot be
     * faked by adding two coverages together.
     */
    private fun stroke(
        target: Bitmap,
        brush: Brush,
        pressure: Float,
        orientationRad: Float,
        y: Float = docH / 2f,
    ) {
        val builder = StrokeBuilder(brush)
        builder.begin(Color.BLACK)
        var x = 40f
        var t = 0L
        while (x <= docW - 40f) {
            builder.add(
                PenSample(
                    x = x, y = y, pressure = pressure, tilt = 0f,
                    orientation = orientationRad, distance = 0f,
                    toolType = ToolType.STYLUS, buttonState = 0,
                    eventTimeNanos = t, source = PenSample.Source.CURRENT,
                ),
            )
            x += 2f
            t += 3_107_855L
        }
        val stroke = builder.end()

        val rasterizer = DabRasterizer(docW, docH, StampCache())
        rasterizer.hardness = brush.hardness
        val scratch = ScratchLayer()
        scratch.begin(stroke.bounds)
        rasterizer.drawDry(scratch.canvasInDocSpace()!!, stroke)
        scratch.compositeInto(Canvas(target), brush.opacity, null, burnish = brush.burnish)
    }

    private fun page() = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)

    /** Mean alpha along the spine, as a fraction. */
    private fun coverage(bmp: Bitmap, y: Int = docH / 2): Float {
        var sum = 0L
        var n = 0
        for (x in 120 until docW - 120) {
            sum += Color.alpha(bmp.getPixel(x, y))
            n++
        }
        return sum.toFloat() / n / 255f
    }

    /** Rows the stroke covers at a threshold of 6 of 255, across the middle. */
    private fun width(bmp: Bitmap): Int {
        var rows = 0
        for (y in 0 until docH) {
            if (Color.alpha(bmp.getPixel(docW / 2, y)) > 6) rows++
        }
        return rows
    }

    /**
     * **The property the tool exists for.** Cross a marker stroke with another
     * and the overlap is visibly darker; that stack of overlaps is what a
     * marker drawing looks like.
     */
    @Test
    fun `a second pass over the first is darker`() {
        val brush = BrushPreset.MARKER.create()
        val once = page()
        stroke(once, brush, 0.6f, ACROSS)
        val twice = page()
        stroke(twice, brush, 0.6f, ACROSS)
        stroke(twice, brush, 0.6f, ACROSS)

        val a = coverage(once)
        val b = coverage(twice)
        assertTrue(a > 0.6f, "one pass left only $a")
        assertTrue(a < 0.85f, "one pass is already opaque at $a; overlaps will not show")
        assertTrue(b > a + 0.1f, "two passes left $b against one pass at $a")
    }

    /**
     * And it converges rather than running away: a marker gets to nearly solid
     * and stops, because each pass takes the same fraction of what is left.
     */
    @Test
    fun `passes converge on solid instead of overshooting`() {
        val brush = BrushPreset.MARKER.create()
        val page = page()
        repeat(4) { stroke(page, brush, 0.6f, ACROSS) }
        val c = coverage(page)
        assertTrue(c > 0.95f, "four passes reached only $c")
        assertTrue(c <= 1f)
    }

    /**
     * Flat along its length. A pencil's mark varies with the tooth and with
     * how the hand moved; a marker's does not, and a stroke that darkened
     * where the hand slowed would be a brush pen.
     */
    @Test
    fun `one pass is even along the stroke`() {
        val brush = BrushPreset.MARKER.create()
        val page = page()
        stroke(page, brush, 0.6f, ACROSS)
        var lo = 255
        var hi = 0
        for (x in 120 until docW - 120) {
            val a = Color.alpha(page.getPixel(x, docH / 2))
            if (a < lo) lo = a
            if (a > hi) hi = a
        }
        assertTrue(hi - lo <= 6, "the stroke varied from $lo to $hi along its spine")
    }

    /**
     * **The wedge.** The same stroke, in the same direction, with the barrel
     * turned: across the line of travel it lays the full width of the nib,
     * along it a hairline. Nothing about the hand's path changed.
     */
    @Test
    fun `turning the barrel turns the chisel`() {
        val brush = BrushPreset.MARKER.create()
        val broad = page()
        stroke(broad, brush, 0.6f, ACROSS)
        val thin = page()
        stroke(thin, brush, 0.6f, ALONG)

        val wide = width(broad)
        val narrow = width(thin)
        assertTrue(narrow > 0, "the thin edge left nothing at all")
        assertTrue(wide > narrow * 2.5f, "broad $wide against thin $narrow is not a chisel")
    }

    /**
     * Pressure moves the width a little and the darkness not at all. A felt
     * nib splays under load; it does not release more ink.
     */
    @Test
    fun `pressure widens the nib slightly and does not darken it`() {
        val brush = BrushPreset.MARKER.create()
        val light = page()
        stroke(light, brush, 0.1f, ACROSS)
        val heavy = page()
        stroke(heavy, brush, 1f, ACROSS)

        val wl = width(light)
        val wh = width(heavy)
        assertTrue(wh > wl, "pressure did nothing to the width: $wl then $wh")
        assertTrue(wh < wl * 1.8f, "pressure moved the width by ${wh.toFloat() / wl}x")

        val cl = coverage(light)
        val ch = coverage(heavy)
        assertTrue(ch - cl < 0.08f, "leaning on it darkened the mark from $cl to $ch")
    }

    private companion object {
        /**
         * Barrel angles, in the digitizer's own -PI..PI. `Sensor.ORIENTATION`
         * maps that onto 0..1 and the preset spreads it over half a turn, so 0
         * puts the nib's long axis across a horizontal stroke and -PI puts it
         * along one.
         */
        const val ACROSS = 0f
        val ALONG = -PI.toFloat()
    }
}
