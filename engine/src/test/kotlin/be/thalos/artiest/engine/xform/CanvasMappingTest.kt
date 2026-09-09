package be.thalos.artiest.engine.xform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The half of the transform that actually goes wrong.
 *
 * Clamping and normalization are checked next door in `CanvasTransformTest`,
 * and they fail loudly. The mapping does not: every plausible composition order
 * agrees at scale 1, rotation 0, translation 0, so an implementation that puts
 * the translation on the wrong side of the rotation passes every casual test
 * and then, on a tablet, produces a pan that drifts sideways once the canvas is
 * twisted — a symptom that reads as a gesture bug and is looked for in the
 * wrong module for a day.
 *
 * So every sweep here crosses scale, rotation and translation together, and
 * every invariant is stated as a number of view pixels rather than as a shape.
 * The margin is large: a transposed composition order misses by tens of
 * thousands of pixels, not by a tolerance.
 */
class CanvasMappingTest {

    /**
     * Both clamp endpoints, 1.0, and an irrational in between so nothing rides
     * on a power of two.
     */
    private val scales = floatArrayOf(0.5f, 0.6666667f, 1f, 1.4142135f, 2f, 3.7f, 5.5f, 8f)

    /** Spans (-PI, PI], both ends of it, and angles too small to be snapped. */
    private val rotations = floatArrayOf(
        -PI_F + 1e-4f, -2.5f, -PI_F / 2f, -0.7f, -1e-4f, 0f, 1e-4f, 0.7f, PI_F / 2f, 2.5f, PI_F,
    )

    private val translations = arrayOf(
        0f to 0f,
        10f to -4f,
        -1080f to -1650f,
        3300f to -1221f,
        12345.5f to -9876.25f,
    )

    /** Document corners, centre, origin, negatives, sub-pixel, and far off-page. */
    private val docPoints = arrayOf(
        0f to 0f,
        DOC_W to DOC_H,
        DOC_W to 0f,
        0f to DOC_H,
        DOC_W / 2f to DOC_H / 2f,
        -1f to -1f,
        0.5f to 0.25f,
        -20000f to 20000f,
        50000f to -50000f,
    )

    private val out = FloatArray(2)
    private val back = FloatArray(2)
    private val coefficients = FloatArray(CanvasTransform.COEFFICIENT_COUNT)

    private fun sweep(body: (CanvasTransform) -> Unit) {
        for (s in scales) {
            for (r in rotations) {
                for ((tx, ty) in translations) body(CanvasTransform(s, r, tx, ty))
            }
        }
    }

    /**
     * The test the work item exists for. An order or sign error in either
     * direction shows up here and essentially nowhere else, because the two
     * directions are written independently and only agree if both are right.
     */
    @Test
    fun `a document point survives the trip to view pixels and back`() {
        sweep { t ->
            for ((px, py) in docPoints) {
                t.docToView(px, py, out)
                t.viewToDoc(out[0], out[1], back)
                val tolerance = if (abs(px) > DOC_W || abs(py) > DOC_H) FAR_FIELD else ON_PAGE
                assertEquals(px, back[0], tolerance, "x at $t from ($px, $py)")
                assertEquals(py, back[1], tolerance, "y at $t from ($px, $py)")
            }
        }
    }

    /**
     * Hand-computed rather than derived from the class, because a round trip
     * through two functions that share a wrong convention is self-consistent.
     * These two are worked out from `view = scale * (R * doc + t)` on paper.
     */
    @Test
    fun `the mapping agrees with the composition written out by hand`() {
        // Level: 2 * (100 + 10, 50 - 5) = (220, 90).
        CanvasTransform(scale = 2f, txDoc = 10f, tyDoc = -5f).docToView(100f, 50f, out)
        assertEquals(220f, out[0])
        assertEquals(90f, out[1])

        // A quarter turn takes (100, 0) to (0, 100); then + (10, -5), then * 2.
        CanvasTransform(2f, PI_F / 2f, 10f, -5f).docToView(100f, 0f, out)
        assertEquals(20f, out[0], 1e-3f)
        assertEquals(190f, out[1], 1e-3f)
    }

    /**
     * The offset is the whole reason these take an array instead of returning
     * a point: a caller packing a stroke writes each sample at 2 * i. An
     * implementation that assigns `out[0]`/`out[1]` regardless passes every
     * other test in this file, because every other test leaves the offset at 0.
     */
    @Test
    fun `a mapped point lands at the offset it was asked for`() {
        val packed = FloatArray(6) { Float.NaN }
        val t = CanvasTransform(2f, 0.7f, 10f, -4f)

        t.docToView(DOC_W, DOC_H, packed, 2)
        t.docToView(DOC_W, DOC_H, out)
        assertEquals(out[0], packed[2])
        assertEquals(out[1], packed[3])
        assertTrue(
            packed[0].isNaN() && packed[1].isNaN() && packed[4].isNaN() && packed[5].isNaN(),
            "wrote outside its two slots: ${packed.toList()}",
        )

        t.viewToDoc(out[0], out[1], packed, 4)
        assertEquals(DOC_W, packed[4], ON_PAGE)
        assertEquals(DOC_H, packed[5], ON_PAGE)
    }

    /**
     * Catches a transposed MTRANS/MSKEW index, which is the coefficient
     * failure that renders as a skew and is invisible at rotation 0 — the one
     * mistake `:app`'s Matrix build can make that the round trip above cannot
     * see, because the coefficients are not on the round trip's path.
     */
    @Test
    fun `the coefficient array maps the same points the mapping does`() {
        sweep { t ->
            t.docToViewCoefficients(coefficients)
            for ((px, py) in docPoints) {
                t.docToView(px, py, out)
                val cx = coefficients[0] * px + coefficients[1] * py + coefficients[2]
                val cy = coefficients[3] * px + coefficients[4] * py + coefficients[5]
                assertEquals(out[0], cx, COEFFICIENT_PX, "forward x at $t")
                assertEquals(out[1], cy, COEFFICIENT_PX, "forward y at $t")
            }
            t.viewToDocCoefficients(coefficients)
            for ((vx, vy) in docPoints) {
                t.viewToDoc(vx, vy, back)
                val cx = coefficients[0] * vx + coefficients[1] * vy + coefficients[2]
                val cy = coefficients[3] * vx + coefficients[4] * vy + coefficients[5]
                assertEquals(back[0], cx, COEFFICIENT_PX, "inverse x at $t")
                assertEquals(back[1], cy, COEFFICIENT_PX, "inverse y at $t")
            }
        }
    }

    @Test
    fun `the two coefficient arrays multiply out to the identity`() {
        val forward = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        sweep { t ->
            t.docToViewCoefficients(forward)
            t.viewToDocCoefficients(coefficients)
            for (row in 0..2) {
                for (col in 0..2) {
                    var acc = 0f
                    for (k in 0..2) acc += coefficients[row * 3 + k] * forward[k * 3 + col]
                    val expected = if (row == col) 1f else 0f
                    assertEquals(expected, acc, ON_PAGE, "entry $row,$col at $t")
                }
            }
        }
    }

    @Test
    fun `a coefficient array too small to hold a transform is refused`() {
        assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.docToViewCoefficients(FloatArray(8))
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.viewToDocCoefficients(FloatArray(8))
        }
    }

    /**
     * The bug the class KDoc names, stated as a number: drag 137 view pixels
     * and the canvas moves 137 view pixels, at 0.5x and at 8x alike. An
     * implementation that pans in document units without dividing by scale
     * moves eight times too far at 8x, which feels like an oversensitive
     * gesture rather than like a units error.
     */
    @Test
    fun `a view-pixel pan moves the canvas that many view pixels at every scale`() {
        val moved = FloatArray(2)
        for (s in floatArrayOf(CanvasTransform.MIN_SCALE, 1f, CanvasTransform.MAX_SCALE)) {
            val t = CanvasTransform(scale = s)
            for ((dx, dy) in listOf(10f to 0f, 0f to -10f, 137.5f to -42.25f)) {
                val panned = t.pannedByView(dx, dy)
                for ((px, py) in docPoints) {
                    t.docToView(px, py, out)
                    panned.docToView(px, py, moved)
                    assertEquals(dx, moved[0] - out[0], PAN_PX, "dx at scale $s from ($px, $py)")
                    assertEquals(dy, moved[1] - out[1], PAN_PX, "dy at scale $s from ($px, $py)")
                }
            }
        }
    }

    /**
     * The order test. Under the other placement of the translation — inside
     * the rotation rather than after it — this is the first thing to fail: a
     * rightward drag would move the canvas along the canvas's own rotated axis
     * instead of along the finger, and by an amount that stays plausible, so
     * nothing crashes and the canvas simply refuses to follow the hand.
     */
    @Test
    fun `a view-pixel pan follows the finger with the canvas rotated`() {
        val moved = FloatArray(2)
        sweep { t ->
            for ((dx, dy) in listOf(10f to 0f, 0f to -10f, 137.5f to -42.25f)) {
                val panned = t.pannedByView(dx, dy)
                for ((px, py) in docPoints) {
                    t.docToView(px, py, out)
                    panned.docToView(px, py, moved)
                    assertEquals(dx, moved[0] - out[0], PAN_PX, "dx at $t from ($px, $py)")
                    assertEquals(dy, moved[1] - out[1], PAN_PX, "dy at $t from ($px, $py)")
                }
            }
        }
    }

    /**
     * What a pinch is. Zoom about the document origin instead and the content
     * between the fingers slides away from them, which on a 2160x3300 page at
     * 4x means the thing being zoomed into leaves the screen entirely.
     */
    @Test
    fun `zooming about a pivot leaves the document under the pivot where it was`() {
        sweep { t ->
            for ((qx, qy) in PIVOTS) {
                t.viewToDoc(qx, qy, back)
                for (factor in floatArrayOf(0.3f, 0.9f, 1.1f, 3f, 100f)) {
                    val zoomed = t.zoomedAbout(qx, qy, factor)
                    zoomed.docToView(back[0], back[1], out)
                    assertEquals(qx, out[0], PIVOT_PX, "pivot x at $t by $factor")
                    assertEquals(qy, out[1], PIVOT_PX, "pivot y at $t by $factor")
                }
            }
        }
    }

    /**
     * The case that separates a correct pivot correction from one computed off
     * the requested factor. Both agree everywhere the clamp does not bite;
     * where it does, the wrong one keeps sliding the canvas while the zoom has
     * already stopped, so pinching harder at the ceiling drags the page away.
     */
    @Test
    fun `a pinch past the clamp stops zooming without sliding the canvas`() {
        val cases = listOf(
            CanvasTransform(6f, 0.7f, 40f, -25f) to 4f,
            CanvasTransform(CanvasTransform.MAX_SCALE, -1.2f, 12f, 9f) to 2f,
            CanvasTransform(0.7f, 2.4f, -300f, 800f) to 0.1f,
            CanvasTransform(CanvasTransform.MIN_SCALE, 0f, 0f, 0f) to 0.5f,
        )
        for ((t, factor) in cases) {
            val expected = CanvasTransform.clampScale(t.scale * factor)
            for ((qx, qy) in PIVOTS) {
                t.viewToDoc(qx, qy, back)
                val zoomed = t.zoomedAbout(qx, qy, factor)
                assertEquals(expected, zoomed.scale, "clamped scale at $t by $factor")
                zoomed.docToView(back[0], back[1], out)
                assertEquals(qx, out[0], PIVOT_PX, "pivot x at $t by $factor")
                assertEquals(qy, out[1], PIVOT_PX, "pivot y at $t by $factor")
            }
        }
    }

    @Test
    fun `rotating about a pivot leaves the document under the pivot where it was`() {
        sweep { t ->
            for ((qx, qy) in PIVOTS) {
                t.viewToDoc(qx, qy, back)
                for (delta in floatArrayOf(0.1f, 1f, -2.5f, 3f, PI_F)) {
                    val rotated = t.rotatedAbout(qx, qy, delta)
                    rotated.docToView(back[0], back[1], out)
                    assertEquals(qx, out[0], PIVOT_PX, "pivot x at $t by $delta")
                    assertEquals(qy, out[1], PIVOT_PX, "pivot y at $t by $delta")
                    assertTrue(
                        rotated.rotationRad > -PI_F && rotated.rotationRad <= PI_F,
                        "rotation escaped (-PI, PI]: ${rotated.rotationRad}",
                    )
                }
            }
        }
    }

    /**
     * A pivot is a centroid, so an empty pointer set gives 0/0 and a NaN pivot,
     * and a pinch whose fingers meet reports a zero factor. Left to the
     * constructor both still throw, but they throw naming a translation the
     * caller never wrote, several frames of arithmetic away from the gesture
     * that produced it.
     *
     * A NaN *angle* is deliberately not guarded here: it reaches the
     * constructor as `rotationRad`, which is the right thing to be blamed.
     * Asserted below so that stays a decision rather than a gap.
     */
    @Test
    fun `a pivot that is not a number is refused where it enters`() {
        val message = assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.zoomedAbout(Float.NaN, 0f, 2f)
        }.message
        assertTrue(message != null && message.contains("pivot"), "blamed: $message")
        assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.zoomedAbout(0f, Float.POSITIVE_INFINITY, 2f)
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.rotatedAbout(Float.NaN, 0f, 0.5f)
        }
        // A non-finite angle is refused too, but by the constructor, and the
        // blame lands on rotationRad rather than on a pivot that was fine.
        val angleMessage = assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.rotatedAbout(100f, 100f, Float.NaN)
        }.message
        assertTrue(
            angleMessage != null && angleMessage.contains("rotationRad"),
            "blamed: $angleMessage",
        )
        assertFailsWith<IllegalArgumentException> {
            CanvasTransform.IDENTITY.zoomedAbout(100f, 100f, 0f)
        }
    }

    /**
     * The real device in its natural orientation. The panel is 1440x2200 (all
     * five Phase 0 probe dumps agree) and the document is 2160x3300 (`DOC_W`
     * and `DOC_H` in `:spike`), which is exactly 1.5x on both axes, so the page
     * fits edge to edge with no
     * letterbox on either axis and the centring translation is exactly zero —
     * in float32, not only in real arithmetic. Asserted exactly for that
     * reason: a rewritten formula that reintroduces rounding would still be
     * within any tolerance and would no longer be this.
     *
     * The exactness is a property of the document size, and the plan's open
     * question 2 floats replacing 2160x3300 with A4 at 300 dpi (2480x3508).
     * That change keeps the width exact — txDoc stays 0 — makes portrait fit
     * 0.580645, and letterboxes 163 px of height, 81.5 above the page and 81.5
     * below, so tyDoc becomes 140.4.
     */
    @Test
    fun `the document fits the tablet panel edge to edge in portrait`() {
        val t = CanvasTransform.fitTo(1440, 2200, DOC_W.toInt(), DOC_H.toInt())
        assertEquals(1440f / 2160f, t.scale)
        assertEquals(0f, t.txDoc)
        assertEquals(0f, t.tyDoc)
        assertEquals(0f, t.rotationRad)

        t.docToView(0f, 0f, out)
        assertEquals(0f, out[0])
        assertEquals(0f, out[1])
        t.docToView(DOC_W, DOC_H, out)
        assertEquals(1440f, out[0], ON_PAGE)
        assertEquals(2200f, out[1], ON_PAGE)
    }

    /**
     * Neither manifest locks orientation and `:spike` ran landscape throughout,
     * so this is the case the device is actually held in as often as the one
     * above, not a hypothetical.
     *
     * **This test asserted the opposite until 2026-09-09, and the thing it
     * asserted was a bug.** Landscape wants 0.436 and the floor was 0.5, so the
     * page could not fit: 3300 rows at 0.5 is 1650 pixels in a 1440-pixel
     * viewport, and the test checked that the 210 px of overflow was split
     * evenly rather than hanging off one edge. Splitting it evenly was the right
     * thing to do *given* the clamp. What nobody checked was whether the clamp
     * should have been biting at the opening view at all — and because it was,
     * zoom out did nothing from the moment the app started. Reported by the
     * user as "it is not possible to zoom out more than the begin zoom level".
     *
     * With `MIN_SCALE` at 0.25 the fit is honoured and the page fits. The
     * clamp is still tested, on a viewport small enough to deserve it.
     */
    @Test
    fun `landscape fit fits, now that the floor is below it`() {
        val t = CanvasTransform.fitTo(2200, 1440, DOC_W.toInt(), DOC_H.toInt())

        // Height binds: 1440/3300 is the smaller of the two ratios.
        assertEquals(1440f / DOC_H, t.scale, 1e-6f)
        assertTrue(
            t.scale > CanvasTransform.MIN_SCALE,
            "the opening view must not sit on the floor: scale ${t.scale}",
        )

        t.docToView(0f, 0f, out)
        val x0 = out[0]
        val y0 = out[1]
        t.docToView(DOC_W, DOC_H, out)
        val x1 = out[0]
        val y1 = out[1]

        // Exactly top to bottom, so there is no overflow left to split.
        assertEquals(0f, y0, ON_PAGE)
        assertEquals(1440f, y1, ON_PAGE)
        // Centred left to right: equal margins either side.
        assertEquals(2200f - x1, x0, ON_PAGE)
        assertTrue(x0 > 0f, "the page should sit inside the viewport, not overflow it")
    }

    /**
     * Centred means centred, and it is the clamped scale that has to do the
     * centring. Using the raw ratio puts the document centre at (1260, 825) in
     * landscape where (1100, 720) is wanted — 160 px out in x and 105 in y —
     * and puts it nowhere at all in portrait, where the clamp never bites.
     */
    @Test
    fun `fit puts the centre of the document at the centre of the viewport`() {
        for ((w, h) in listOf(1440 to 2200, 2200 to 1440, 1440 to 2050, 3840 to 2160, 1 to 1)) {
            val t = CanvasTransform.fitTo(w, h, DOC_W.toInt(), DOC_H.toInt())
            t.docToView(DOC_W / 2f, DOC_H / 2f, out)
            assertEquals(w / 2f, out[0], ON_PAGE, "centre x in ${w}x$h")
            assertEquals(h / 2f, out[1], ON_PAGE, "centre y in ${w}x$h")
        }
    }

    /**
     * A View measured before layout reports 0x0. That is a lifecycle moment,
     * not a bug, but a factory has no previous transform to keep — so the
     * contract is that the caller guards, and the failure is loud. A zero
     * viewport does not produce NaN; it produces a finite, plausible transform
     * that renders as a page mysteriously off to one side.
     */
    @Test
    fun `a viewport with no area is refused rather than fitted`() {
        assertFailsWith<IllegalArgumentException> { CanvasTransform.fitTo(0, 2200, 2160, 3300) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.fitTo(1440, 0, 2160, 3300) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.fitTo(-1440, 2200, 2160, 3300) }
    }

    /**
     * The one hole in the clamp: `1440f / 0f` is +Infinity, `coerceIn` returns
     * MAX_SCALE for it without complaint, and the constructor accepts 8.0. A
     * zero-size document would ship as an unexplained maximum zoom rather than
     * as an exception, so it is caught before the division.
     */
    @Test
    fun `a document with no area is refused before it becomes a maximum zoom`() {
        assertEquals(CanvasTransform.MAX_SCALE, CanvasTransform.clampScale(Float.POSITIVE_INFINITY))
        assertFailsWith<IllegalArgumentException> { CanvasTransform.fitTo(1440, 2200, 0, 3300) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.fitTo(1440, 2200, 2160, 0) }
    }

    /**
     * Both ends of what an Int viewport can say. A 1x1 viewport clamps to the
     * floor rather than throwing — it is a legal, if useless, transform — and
     * Int.MAX_VALUE clamps to the ceiling with a translation of 1.3e8, well
     * inside float range. Neither escapes as NaN or infinity, which matters
     * because `clampScale` would pass a NaN straight through.
     */
    @Test
    fun `fit stays finite at both extremes of an Int viewport`() {
        for (t in listOf(
            CanvasTransform.fitTo(1, 1, 2160, 3300),
            CanvasTransform.fitTo(Int.MAX_VALUE, Int.MAX_VALUE, 2160, 3300),
            CanvasTransform.fitTo(1440, 2200, Int.MAX_VALUE, Int.MAX_VALUE),
        )) {
            assertTrue(t.scale.isFinite() && t.txDoc.isFinite() && t.tyDoc.isFinite(), "escaped: $t")
        }
        assertEquals(CanvasTransform.MIN_SCALE, CanvasTransform.fitTo(1, 1, 2160, 3300).scale)
        assertEquals(
            CanvasTransform.MAX_SCALE,
            CanvasTransform.fitTo(Int.MAX_VALUE, Int.MAX_VALUE, 2160, 3300).scale,
        )
    }

    /**
     * The trace file format writes exactly four floats and `TracePlayer`
     * rebuilds the transform positionally behind a five-token check, so the
     * cached trig and the derived operations must stay out of the primary
     * constructor. Equality on the four floats is what that rests on.
     */
    @Test
    fun `derived state stays out of the value the trace format serializes`() {
        val a = CanvasTransform(2f, 0.7f, 10f, -4f)
        val b = CanvasTransform(2f, 0.7f, 10f, -4f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a.zoomedAbout(100f, 100f, 4f).zoomedAbout(100f, 100f, 0.25f))
    }

    private companion object {
        val PI_F = PI.toFloat()

        /** The document, from DOC_W/DOC_H in `:spike`'s DirectSurfaceInkView. */
        const val DOC_W = 2160f
        const val DOC_H = 3300f

        /** Viewport corners, centre, and one off-screen — a pivot may be either. */
        val PIVOTS = arrayOf(0f to 0f, 720f to 1100f, 1440f to 2200f, -300f to 900f)

        // Every tolerance below is the measured worst case over this file's own
        // sweep — 8 scales x 11 rotations x 5 translations x 9 points — rounded
        // up to leave roughly a factor of two of headroom. They are loose in
        // absolute terms and still enormously tight against the failure they
        // guard: over this sweep a transposed composition order misses by up to
        // 225173 view pixels — s*(R*t - t) at 8x on the largest translation —
        // not by a thousandth of one.

        /** Round trip inside the document. Measured worst 0.00195 doc units. */
        const val ON_PAGE = 0.004f

        /**
         * Round trip 50000 units off-page. Measured worst 0.0117 doc units —
         * larger only because float error tracks magnitude.
         */
        const val FAR_FIELD = 0.03f

        /** Coefficient path against the point path. Measured worst 0.03125 px. */
        const val COEFFICIENT_PX = 0.07f

        /** Pan drift. Measured worst 0.03125 px, at 3.7x and 5.5x on the point 50000 units off-page. */
        const val PAN_PX = 0.07f

        /** Pivot drift. Measured worst 0.03125 px, rotating by PI at 8x about (720, 1100). */
        const val PIVOT_PX = 0.07f
    }
    /**
     * A round trip through zero must not produce a transform that is unequal to
     * the one it started as. `%` keeps the dividend's sign, so the naive
     * normalization returns -0.0f here, and a data class compares it through
     * java.lang.Float, which treats the two zeroes as different values.
     */
    @Test
    fun `rotating there and back is equal to never having rotated`() {
        val there = CanvasTransform.IDENTITY.rotatedBy(-0.5f).rotatedBy(0.5f)
        assertEquals(CanvasTransform.IDENTITY, there, "geometrically identical but unequal")
        assertEquals(CanvasTransform.IDENTITY.hashCode(), there.hashCode())
        assertEquals(0f, there.rotationRad)
    }

}
