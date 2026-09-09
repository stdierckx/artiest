package be.thalos.artiest.engine.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry, which is where a colour wheel actually goes wrong.
 *
 * Three failures are worth naming because each one is invisible in a
 * screenshot and obvious under a finger: the wheel running clockwise (the puck
 * follows, so it looks fine until you try to find green), the centre flicking
 * to red as a drag passes through it, and a drag off the rim losing the hue.
 * There is a test for each.
 */
class ColorDiscTest {

    /**
     * Hue equality the short way round the circle, so 359.04 and 0 are within
     * a degree of each other rather than 359 apart. [tolerance] is generous
     * only where a pixel is being probed: a pixel on the middle row of an
     * even-sized raster sits half a pixel off the centre line, which is a real
     * fraction of a degree at the rim and not an error.
     */
    private fun assertHue(expected: Float, actual: Float, message: String, tolerance: Float = 0.01f) {
        val d = abs(Hsv.normalizeHue(expected) - Hsv.normalizeHue(actual))
        val shortest = if (d > 180f) 360f - d else d
        assertTrue(shortest < tolerance, "$message: expected $expected, was $actual")
    }

    @Test
    fun `hue runs anticlockwise from red at three o'clock`() {
        // y is down, so "up the screen" is negative dy.
        assertHue(0f, ColorDisc.hueAt(1f, 0f, 0f), "right is red")
        assertHue(90f, ColorDisc.hueAt(0f, -1f, 0f), "up is 90, chartreuse")
        assertHue(180f, ColorDisc.hueAt(-1f, 0f, 0f), "left is cyan")
        assertHue(270f, ColorDisc.hueAt(0f, 1f, 0f), "down is 270, violet")
    }

    @Test
    fun `the spectrum comes round in spectrum order`() {
        // Sampled anticlockwise from red; each should be strictly further round
        // than the last, which is the property a clockwise wheel fails.
        val seen = (0 until 350 step 10).map { deg ->
            val p = ColorDisc.positionOf(Hsv(deg.toFloat(), 1f, 1f))
            ColorDisc.hueAt(p.dx, p.dy, 0f)
        }
        for (i in 1 until seen.size) {
            assertTrue(seen[i] > seen[i - 1], "hue went backwards at index $i: $seen")
        }
    }

    @Test
    fun `the centre keeps the hue it was given`() {
        assertEquals(210f, ColorDisc.hueAt(0f, 0f, 210f))
        // And normalizes it, so a caller that has been accumulating drags does
        // not get 730 back out.
        assertEquals(10f, ColorDisc.hueAt(0f, 0f, 730f))
    }

    @Test
    fun `a drag through the centre changes saturation, not hue`() {
        // Straight across the disc along the horizontal: hue is 180 on the left
        // half and 0 on the right, and exactly at the centre it must hold
        // whatever it had rather than snapping.
        val held = 200f
        assertHue(180f, ColorDisc.sample(-0.4f, 0f, 1f, held).hue, "left of centre")
        assertHue(held, ColorDisc.sample(0f, 0f, 1f, held).hue, "at the centre")
        assertEquals(0f, ColorDisc.sample(0f, 0f, 1f, held).saturation, "centre is grey")
    }

    @Test
    fun `saturation is the radius and clamps at the rim`() {
        assertEquals(0f, ColorDisc.saturationAt(0f, 0f))
        assertEquals(0.5f, ColorDisc.saturationAt(0.5f, 0f))
        assertEquals(0.5f, ColorDisc.saturationAt(0f, -0.5f))
        assertEquals(1f, ColorDisc.saturationAt(1f, 0f))
        assertEquals(1f, ColorDisc.saturationAt(4f, -3f), "well outside the disc")
    }

    @Test
    fun `a finger outside the disc still steers the hue`() {
        // The gesture that motivates the clamp: dragging past the rim to swing
        // round the wheel quickly. Hue must keep tracking; only saturation pins.
        val far = ColorDisc.sample(-3f, -3f, 1f, fallbackHue = 0f)
        assertHue(135f, far.hue, "up and left, outside")
        assertEquals(1f, far.saturation)
    }

    @Test
    fun `positionOf and sample are inverses`() {
        for (hue in 0 until 360 step 7) {
            for (sat in intArrayOf(0, 1, 25, 50, 99, 100)) {
                val original = Hsv(hue.toFloat(), sat / 100f, 0.7f)
                val p = ColorDisc.positionOf(original)
                val back = ColorDisc.sample(p.dx, p.dy, original.value, fallbackHue = original.hue)
                assertEquals(original.saturation, back.saturation, 1e-5f, "saturation of $original")
                assertHue(original.hue, back.hue, "hue of $original")
                assertEquals(original.value, back.value, "value of $original")
            }
        }
    }

    @Test
    fun `positionOf ignores value and clamps saturation`() {
        val bright = ColorDisc.positionOf(Hsv(90f, 1f, 1f))
        val dark = ColorDisc.positionOf(Hsv(90f, 1f, 0.1f))
        assertEquals(bright, dark, "the disc does not show value")

        val over = ColorDisc.positionOf(Hsv(0f, 3f, 1f))
        assertEquals(1f, over.dx, 1e-6f)
    }

    @Test
    fun `the raster is a disc of the right colours in a transparent square`() {
        val size = 64
        val px = ColorDisc.raster(size)
        assertEquals(size * size, px.size)

        fun at(x: Int, y: Int) = px[y * size + x]
        fun alpha(argb: Int) = (argb ushr 24) and 0xFF

        assertEquals(0, at(0, 0), "top-left corner is outside the disc")
        assertEquals(0, at(size - 1, size - 1), "bottom-right corner too")

        // The centre pixel is off-centre by half a pixel at even sizes, so it is
        // very nearly white rather than exactly white.
        val centre = at(size / 2, size / 2)
        assertEquals(255, alpha(centre))
        assertTrue(Hsv.fromArgb(centre).saturation < 0.05f, "centre is white-ish")

        // Rightmost pixel on the middle row: full saturation, hue 0.
        val rim = at(size - 1, size / 2)
        val rimHsv = Hsv.fromArgb(rim)
        assertTrue(rimHsv.saturation > 0.9f, "the rim is saturated, was $rimHsv")
        assertHue(0f, rimHsv.hue, "the rim at three o'clock is red", tolerance = 2f)
    }

    @Test
    fun `the raster is drawn at full value, so the bar can dim it by multiplying`() {
        val px = ColorDisc.raster(48)
        for (argb in px) {
            if ((argb ushr 24) and 0xFF == 0) continue
            val v = Hsv.fromArgb(argb).value
            assertTrue(v > 0.99f, "a wheel pixel came out at value $v")
        }
    }

    @Test
    fun `the rim is antialiased rather than stepped`() {
        val size = 64
        val px = ColorDisc.raster(size)
        val alphas = px.map { (it ushr 24) and 0xFF }
        assertTrue(alphas.any { it == 0 }, "some pixels are outside")
        assertTrue(alphas.any { it == 255 }, "some pixels are fully inside")
        assertTrue(alphas.any { it in 1..254 }, "the edge has partial coverage")
    }

    @Test
    fun `raster fills a supplied buffer and rejects a wrong-sized one`() {
        val buffer = IntArray(16 * 16)
        assertTrue(ColorDisc.raster(16, buffer) === buffer)

        val wrong = runCatching { ColorDisc.raster(16, IntArray(10)) }.exceptionOrNull()
        assertTrue(wrong is IllegalArgumentException, "wrong buffer size threw $wrong")

        val zero = runCatching { ColorDisc.raster(0) }.exceptionOrNull()
        assertTrue(zero is IllegalArgumentException, "zero size threw $zero")
    }
}
