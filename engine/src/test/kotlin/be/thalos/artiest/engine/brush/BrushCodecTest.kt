package be.thalos.artiest.engine.brush

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrushCodecTest {

    private fun assertSame(a: Brush, b: Brush) {
        assertEquals(a.sizeMin, b.sizeMin, "sizeMin")
        assertEquals(a.sizeMax, b.sizeMax, "sizeMax")
        assertEquals(a.sizeCurve, b.sizeCurve, "sizeCurve")
        assertEquals(a.spacing, b.spacing, "spacing")
        assertEquals(a.isotropicSpacing, b.isotropicSpacing, "isotropic")
        assertEquals(a.hardness, b.hardness, "hardness")
        assertEquals(a.opacity, b.opacity, "opacity")
        assertEquals(a.flow, b.flow, "flow")
        assertEquals(a.stabilization, b.stabilization, "stabilization")
        assertEquals(a.antiAlias, b.antiAlias, "antiAlias")
        assertEquals(a.erase, b.erase, "erase")
        assertEquals(a.onsetMillis, b.onsetMillis, "onsetMillis")
        assertEquals(a.onsetPressure, b.onsetPressure, "onsetPressure")
        assertEquals(a.grain, b.grain, "grain")
        for ((name, pair) in mapOf(
            "aspect" to (a.aspect to b.aspect),
            "rotation" to (a.rotation to b.rotation),
            "scatter" to (a.scatter to b.scatter),
            "sizeJitter" to (a.sizeJitter to b.sizeJitter),
        )) {
            val (x, y) = pair
            assertEquals(x.min, y.min, "$name min")
            assertEquals(x.max, y.max, "$name max")
            assertEquals(x.combine, y.combine, "$name combine")
            assertEquals(x.inputCount, y.inputCount, "$name inputs")
            for (i in 0 until x.inputCount) {
                assertEquals(x.sensorAt(i), y.sensorAt(i), "$name sensor $i")
                assertEquals(x.curveAt(i), y.curveAt(i), "$name curve $i")
            }
        }
    }

    @Test
    fun `both presets survive the round trip`() {
        for (preset in BrushPreset.entries) {
            val brush = preset.create()
            val back = assertNotNull(BrushCodec.decode(BrushCodec.encode(brush)), preset.label)
            assertSame(brush, back)
        }
    }

    @Test
    fun `a brush with a table curve survives the round trip`() {
        val brush = Brush()
        brush.sizeCurve = ResponseCurve.of(0f to 0.1f, 0.4f to 0.5f, 1f to 0.95f)
        brush.sizeJitter.min = 0.1f
        brush.sizeJitter.max = 0.4f
        brush.sizeJitter.combine = CurveOption.Combine.MAXIMUM
        brush.sizeJitter.drive(Sensor.SPEED, ResponseCurve.power(2f))
        brush.sizeJitter.drive(Sensor.RANDOM_STROKE)
        val back = assertNotNull(BrushCodec.decode(BrushCodec.encode(brush)))
        assertSame(brush, back)
    }

    /**
     * The nl-BE trap `TraceFormat` and the dab goldens both guard against: a
     * locale with a decimal comma turns every number into a parse error on one
     * developer's machine and nowhere else.
     */
    @Test
    fun `encoding does not consult the locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("nl-BE"))
            val brush = BrushPreset.PENCIL.create()
            val text = BrushCodec.encode(brush)
            assertFalse(text.contains(','), "a decimal comma reached the file:\n$text")
            assertSame(brush, assertNotNull(BrushCodec.decode(text)))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `something that is not a brush decodes to null rather than throwing`() {
        assertNull(BrushCodec.decode(null))
        assertNull(BrushCodec.decode(""))
        assertNull(BrushCodec.decode("hello"))
        assertNull(BrushCodec.decode("artiest-toolbar 1\nsize 1 2"))
        assertNull(BrushCodec.decode("artiest-brush\nsize 1 2"), "no version")
    }

    /**
     * The forward-compatibility rule. A preset written by a later build has to
     * load in this one with the parameters it understands, or adding a brush
     * parameter invalidates every file anyone has saved.
     */
    @Test
    fun `a line from a later build is skipped and the rest is kept`() {
        val text = """
            artiest-brush 99
            size 2.0 40.0
            bristles 12 0.5
            flow 0.4
            # a comment, and a blank line follow

            wetness 0.9
        """.trimIndent()
        val brush = assertNotNull(BrushCodec.decode(text))
        assertEquals(2f, brush.sizeMin)
        assertEquals(40f, brush.sizeMax)
        assertEquals(0.4f, brush.flow)
    }

    /** A file is exactly where an impossible value comes from. */
    @Test
    fun `an impossible value leaves the default rather than throwing`() {
        val brush = assertNotNull(
            BrushCodec.decode(
                "artiest-brush 1\n" +
                    "grain 0 5 0.9 0.1 3\n" +      // every field out of range
                    "sizeCurve pow -4\n" +
                    "size nonsense 40.0\n" +
                    "flow 0.4\n",
            ),
        )
        assertFalse(brush.grain.isActive, "an impossible grain was accepted")
        assertEquals(ResponseCurve.CUBIC, brush.sizeCurve, "a negative exponent was accepted")
        assertEquals(1.5f, brush.sizeMin, "an unparseable number was accepted")
        assertEquals(40f, brush.sizeMax, "the readable half of the line was dropped")
        assertEquals(0.4f, brush.flow, "a later line was lost after a bad one")
    }

    @Test
    fun `an unknown sensor or curve does not attach a wrong one`() {
        val brush = assertNotNull(
            BrushCodec.decode(
                "artiest-brush 1\naspect 1.0 0.3 MULTIPLY\naspect.drive HUMIDITY pow 1.0\n",
            ),
        )
        assertEquals(0, brush.aspect.inputCount, "an unknown sensor was attached anyway")
        assertEquals(0.3f, brush.aspect.max, "the option's own line was lost with it")
    }

    @Test
    fun `the encoded form is diffable, one parameter to a line`() {
        val a = BrushCodec.encode(BrushPreset.PENCIL.create())
        val brush = BrushPreset.PENCIL.create().also { it.flow = 0.9f }
        val b = BrushCodec.encode(brush)
        val differing = a.lines().zip(b.lines()).count { it.first != it.second }
        assertEquals(1, differing, "changing one parameter moved $differing lines")
    }

    @Test
    fun `decoding twice from one text gives two independent brushes`() {
        val text = BrushCodec.encode(BrushPreset.PENCIL.create())
        val x = assertNotNull(BrushCodec.decode(text))
        val y = assertNotNull(BrushCodec.decode(text))
        x.aspect.drive(Sensor.SPEED)
        assertTrue(y.aspect.inputCount < x.aspect.inputCount)
    }

    /**
     * **The bug that made "tilt does not widen the stroke" survive its own
     * fix.**
     *
     * The preset drives `size` from TILT and `flow` from PRESSURE, and the
     * format wrote neither: `size` was two numbers and `flow` was one, and the
     * sensors behind them were dropped on the way to disk. So the pencil worked
     * for as long as the app stayed open and came back after a restart as a
     * brush with a tilt-shaped nib, no tilt-driven width and no pressure-driven
     * darkness — which is exactly what was reported, twice, against two
     * different builds that both had the fix in them.
     *
     * Round-tripping the count is not enough here: a format that wrote the
     * sensor and lost the curve would pass that and still give a straight-line
     * response where the preset asked for `p^1.6`. So the response itself is
     * compared, at both ends and in the middle.
     */
    /**
     * The eraser's width is a slider like the brush's, so it has to survive a
     * restart like the brush's. Left out of the format it would come back at
     * the default every time the app started, which is the same defect the
     * missing `flowMin` line was.
     */
    @Test
    fun `the eraser's own width survives a round trip`() {
        val before = BrushPreset.PENCIL.create()
        before.eraseSizeMax = 173.5f
        val after = assertNotNull(BrushCodec.decode(BrushCodec.encode(before)))
        assertEquals(173.5f, after.eraseSizeMax)
    }

    @Test
    fun `a saved pencil keeps its tilt-to-width and pressure-to-darkness`() {
        val before = BrushPreset.PENCIL.create()
        val after = assertNotNull(BrushCodec.decode(BrushCodec.encode(before)))
        for ((name, pair) in mapOf(
            "size" to (before.size to after.size),
            "flow" to (before.flowOption to after.flowOption),
        )) {
            val (from, to) = pair
            assertEquals(from.inputCount, to.inputCount, "$name lost its sensors")
            assertEquals(from.combine, to.combine, "$name lost its combine")
            for (i in 0 until from.inputCount) {
                assertEquals(from.sensorAt(i), to.sensorAt(i), "$name sensor $i")
                assertEquals(from.curveAt(i), to.curveAt(i), "$name curve $i")
            }
        }
        for (tilt in listOf(0f, 0.5f, 1f)) {
            for (p in listOf(0f, 0.35f, 1f)) {
                val c = DabContext().also {
                    it.pressure = p
                    it.tiltRad = tilt * Sensor.TILT_MAX_RAD
                }
                assertEquals(before.sizeFor(c), after.sizeFor(c), 1e-5f, "width at p=$p tilt=$tilt")
                assertEquals(
                    before.flowOption.valueFor(c), after.flowOption.valueFor(c), 1e-5f,
                    "flow at p=$p tilt=$tilt",
                )
            }
        }
    }

    /**
     * The other half of the same defence: a file written by the build that had
     * no `sizeOpt` line at all.
     *
     * That is what was actually in the user's preferences, and it decodes
     * without complaint into a brush that has no tilt wiring. `applyToShapeOnly`
     * is what puts it back, and this pins that it does — without touching the
     * two numbers the toolbar's sliders own.
     */
    @Test
    fun `an older save gets its wiring back without losing the sliders`() {
        val old = assertNotNull(
            BrushCodec.decode(
                "artiest-brush 1\nsize 1.5 18.0\nopacity 0.9\nflow 0.6\n",
            ),
        )
        assertEquals(0, old.size.inputCount, "the premise: an old file has no size wiring")
        BrushPreset.PENCIL.applyToShapeOnly(old)
        assertTrue(old.size.inputCount > 0, "the pencil's tilt did not come back")
        assertTrue(old.flowOption.inputCount > 0, "the pencil's pressure did not come back")
        assertEquals(18f, old.sizeMax, "the size slider was overwritten by the preset")
        assertEquals(0.6f, old.flow, "the flow slider was overwritten by the preset")
    }

    /** And a brush that already has its own wiring is left entirely alone. */
    @Test
    fun `applyToShapeOnly does not stack a second copy of the wiring`() {
        val brush = BrushPreset.PENCIL.create()
        val inputs = brush.size.inputCount
        BrushPreset.PENCIL.applyToShapeOnly(brush)
        assertEquals(inputs, brush.size.inputCount)
    }
}
