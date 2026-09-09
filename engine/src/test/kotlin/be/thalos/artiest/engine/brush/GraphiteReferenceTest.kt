package be.thalos.artiest.engine.brush

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pencil's *shape*, against a measured reference and against a pencil.
 *
 * **Where the reference comes from.** The user drew a page of strokes in
 * another drawing app and handed it over: four circles at varying pressure,
 * four upright lines, and eight made with the pen laid over. Three horizontal
 * scanlines across the line band give a width and a peak darkness for each:
 *
 * | posture          | width, px | peak darkness, of 255 |
 * |------------------|-----------|-----------------------|
 * | upright, light   | 3 – 4     | 26 – 38               |
 * | upright, heavy   | 7 – 8     | 194 – 220             |
 * | laid over, light | 14 – 27   | 19 – 33               |
 * | laid over, heavy | 12 – 20   | 76 – 109              |
 *
 * Three facts fall out. Width is almost all tilt's: upright it moves by a
 * factor of two across the whole pressure range. Darkness is almost all
 * pressure's. And laying the pen over *pales* the mark as well as widening it —
 * the same graphite over more paper.
 *
 * **What this file does not test, and why.** Not how dark the mark comes out.
 * Between a brush's answer and a pixel sit the dab overlap, the grain and the
 * burnish pass, and a test that multiplies two brush fields together and calls
 * the product "darkness" is asserting a model rather than a mark — which is
 * exactly how this file's first version passed while the tool had almost no
 * usable range. Coverage belongs to `PencilResponseTest` in `:app`, which draws
 * the stroke and reads the pixels. What is left here is the geometry, which is
 * arithmetic and belongs beside the preset.
 *
 * **The one number taken from a pencil rather than from the sheet** is how much
 * tilt widens the mark. The sheet says 2.7; a sharpened pencil is a cone about
 * 4 mm long and marks well under a millimetre on its point, which is four to
 * five. The sheet is evidence about the brush that drew it, and the cone is
 * evidence about pencils.
 */
class GraphiteReferenceTest {

    /** Pressure at which the reference's "light" strokes were probably drawn. */
    private val light = 0.35f

    private fun context(pressure: Float, tiltFraction: Float) = DabContext().also {
        it.pressure = pressure
        it.tiltRad = tiltFraction * Sensor.TILT_MAX_RAD
    }

    private fun width(p: Brush, pressure: Float, tilt: Float) = p.sizeFor(context(pressure, tilt))

    /** One pass's coverage, as the preset asks for it. Not what lands: see the header. */
    private fun pass(p: Brush, pressure: Float, tilt: Float) =
        p.flowOption.valueFor(context(pressure, tilt))

    @Test
    fun `laying the pencil over makes a mark several times broader`() {
        val p = BrushPreset.PENCIL.create()
        val upright = width(p, 1f, 0f)
        val over = width(p, 1f, 1f)
        assertTrue(
            over > upright * 3.5f,
            "laid over the mark was $over, upright it was $upright -- a 4 mm cone is 4 to 5x",
        )
    }

    /**
     * The half that widening alone gets wrong. A wide *black* band is not a
     * mark a pencil can make, and nothing on the reference sheet is one.
     */
    @Test
    fun `laying the pencil over also pales the mark`() {
        val p = BrushPreset.PENCIL.create()
        val upright = pass(p, 1f, 0f)
        val over = pass(p, 1f, 1f)
        assertTrue(over < upright * 0.6f, "laid over asks for $over, upright $upright")
    }

    /**
     * Pressure moves the width by a factor of two, not by a factor of sixteen.
     *
     * The cubic that was here before took it over sixteen, which is a felt-tip
     * responding to pressure rather than a pencil: the lead's contact patch is
     * what it is, and leaning harder darkens the mark rather than broadening it.
     */
    @Test
    fun `pressure barely moves the width of an upright stroke`() {
        val p = BrushPreset.PENCIL.create()
        val ratio = width(p, 1f, 0f) / width(p, light, 0f)
        assertTrue(ratio in 1.3f..2.6f, "pressure moved the upright width by ${ratio}x")
    }

    /** And pressure is where the whole darkness range is asked for. */
    @Test
    fun `pressure asks for a very wide range of coverage`() {
        val p = BrushPreset.PENCIL.create()
        val heavy = pass(p, 1f, 0f)
        val soft = pass(p, 0.1f, 0f)
        assertTrue(heavy > 0.85f, "leant on, one pass asks for only $heavy")
        assertTrue(soft < 0.12f, "a feather touch asks for $soft")
    }

    /**
     * Widths as fractions of the size slider, so the shape survives the slider
     * moving.
     *
     * The reference's absolute pixel widths cannot be asserted — they were
     * drawn at whatever size that app's brush was — but their *ratios* to the
     * widest mark on the page can, and those are what the size curve's control
     * points were chosen to reproduce.
     *
     * **Not down to the smallest slider setting, and that is deliberate.**
     * `sizeMin` is 1.5 document pixels and it is a floor rather than a
     * proportion: below about a pixel there is no mark to have proportions
     * about. At a slider of 8 the floor is a fifth of the whole range and takes
     * the light end over, so the proportions hold from about 16 up and the
     * pencil becomes a fine even line below that. That is the right behaviour,
     * so the test says where it starts rather than pretending it does not.
     */
    @Test
    fun `widths keep the reference's proportions whatever the slider says`() {
        for (sizeMax in listOf(16f, 48f, 90f)) {
            val p = BrushPreset.PENCIL.create().also { it.sizeMax = sizeMax }
            val widest = width(p, 1f, 1f)
            val heavy = width(p, 1f, 0f) / widest
            val soft = width(p, light, 0f) / widest
            assertTrue(
                heavy in 0.14f..0.32f,
                "at $sizeMax an upright heavy dab was $heavy of the widest",
            )
            assertTrue(
                soft in 0.05f..0.20f,
                "at $sizeMax an upright light dab was $soft of the widest",
            )
        }
    }
}
