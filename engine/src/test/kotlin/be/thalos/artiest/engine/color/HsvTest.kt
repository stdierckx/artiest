package be.thalos.artiest.engine.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversion, checked against colours whose ARGB is not in dispute.
 *
 * The six pure hues are the whole test in one sense: every bug this conversion
 * has is a sector bug, and a sector bug shows up as one of these six being the
 * neighbour of the one it should be.
 */
class HsvTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `the six pure hues pack to their corners of the cube`() {
        assertEquals(argb(255, 0, 0), Hsv(0f, 1f, 1f).toArgb(), "red")
        assertEquals(argb(255, 255, 0), Hsv(60f, 1f, 1f).toArgb(), "yellow")
        assertEquals(argb(0, 255, 0), Hsv(120f, 1f, 1f).toArgb(), "green")
        assertEquals(argb(0, 255, 255), Hsv(180f, 1f, 1f).toArgb(), "cyan")
        assertEquals(argb(0, 0, 255), Hsv(240f, 1f, 1f).toArgb(), "blue")
        assertEquals(argb(255, 0, 255), Hsv(300f, 1f, 1f).toArgb(), "magenta")
    }

    @Test
    fun `360 is 0, and so is 720 and -360`() {
        val red = Hsv(0f, 1f, 1f).toArgb()
        assertEquals(red, Hsv(360f, 1f, 1f).toArgb())
        assertEquals(red, Hsv(720f, 1f, 1f).toArgb())
        assertEquals(red, Hsv(-360f, 1f, 1f).toArgb())
        assertEquals(Hsv(40f, 1f, 1f).toArgb(), Hsv(-320f, 1f, 1f).toArgb())
    }

    @Test
    fun `value zero is black and saturation zero is grey, at every hue`() {
        for (hue in 0 until 360 step 15) {
            val h = hue.toFloat()
            assertEquals(argb(0, 0, 0), Hsv(h, 1f, 0f).toArgb(), "black at $h")
            assertEquals(argb(255, 255, 255), Hsv(h, 0f, 1f).toArgb(), "white at $h")
            assertEquals(argb(128, 128, 128), Hsv(h, 0f, 0.5f).toArgb(), "grey at $h")
        }
    }

    /**
     * The property the value bar rests on: dimming is a multiply. If this ever
     * fails, `ColorDisc.raster`'s "draw it once at full value" is wrong and the
     * bar has to regenerate the wheel on every frame of a drag.
     */
    @Test
    fun `value scales the channels linearly`() {
        for (hue in 0 until 360 step 30) {
            val full = Hsv(hue.toFloat(), 1f, 1f).toArgb()
            val half = Hsv(hue.toFloat(), 1f, 0.5f).toArgb()
            for (shift in intArrayOf(16, 8, 0)) {
                val f = (full shr shift) and 0xFF
                val h = (half shr shift) and 0xFF
                assertTrue(
                    abs(h - (f * 0.5f)) <= 0.51f,
                    "channel $shift at hue $hue: $h is not half of $f",
                )
            }
        }
    }

    @Test
    fun `alpha is carried through untouched`() {
        assertEquals(0x80FF0000.toInt(), Hsv(0f, 1f, 1f).toArgb(alpha = 0x80))
        assertEquals(0x00FF0000, Hsv(0f, 1f, 1f).toArgb(alpha = 0))
    }

    @Test
    fun `out of range saturation and value clamp rather than wrap`() {
        assertEquals(Hsv(0f, 1f, 1f).toArgb(), Hsv(0f, 1.4f, 3f).toArgb())
        assertEquals(argb(0, 0, 0), Hsv(0f, 1f, -0.2f).toArgb())
    }

    @Test
    fun `fromArgb inverts toArgb for every hue on the rim`() {
        for (hue in 0 until 360 step 5) {
            val h = hue.toFloat()
            val back = Hsv.fromArgb(Hsv(h, 1f, 1f).toArgb())
            // 8 bits per channel is a little under 1.5 degrees of hue at full
            // saturation, so this is round-trip fidelity, not slack.
            assertTrue(hueDistance(h, back.hue) < 1.5f, "hue $h came back as ${back.hue}")
            assertEquals(1f, back.saturation, 0.005f)
            assertEquals(1f, back.value, 0.005f)
        }
    }

    @Test
    fun `fromArgb reports no hue and no saturation for greys`() {
        for (level in intArrayOf(0, 1, 64, 128, 254, 255)) {
            val grey = Hsv.fromArgb(argb(level, level, level))
            assertEquals(0f, grey.hue, "grey $level")
            assertEquals(0f, grey.saturation, "grey $level")
            assertEquals(level / 255f, grey.value, 0.002f, "grey $level")
        }
    }

    /** Shortest way round the circle, so 359 and 1 are two apart. */
    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(Hsv.normalizeHue(a) - Hsv.normalizeHue(b))
        return if (d > 180f) 360f - d else d
    }
}
