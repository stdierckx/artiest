package be.thalos.artiest.engine.xform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two fingers into a transform.
 *
 * Most of these check one property, and it is the only one a user can actually
 * see: **the document point under a finger stays under that finger.** Scale
 * ratios and rotation angles are how it is computed, not what it has to be, and
 * a solver can get both of those right and still slide the canvas because the
 * pivot was wrong. `docToView` of the point that was under the finger, compared
 * against where the finger is now, catches every version of that.
 *
 * `a pinch out and back lands exactly where it started` is the control for
 * anchoring. Beside it is the accumulating solver — apply each frame's delta to
 * the transform you have — driven through the same pinch, drifting. That is the
 * implementation anchoring replaced, and without it "no drift" is a claim about
 * nothing.
 */
class GestureSolverTest {

    private val ids = intArrayOf(7, 9)

    private fun solverAt(t: CanvasTransform): GestureSolver =
        GestureSolver().also { it.begin(t) }

    private fun update(
        s: GestureSolver,
        x0: Float, y0: Float, x1: Float, y1: Float,
        pointerIds: IntArray = ids,
    ): CanvasTransform = s.update(pointerIds, floatArrayOf(x0, x1), floatArrayOf(y0, y1), 2)

    /** Where the document point currently under ([xView], [yView]) is. */
    private fun docUnder(t: CanvasTransform, xView: Float, yView: Float): FloatArray {
        val out = FloatArray(2)
        t.viewToDoc(xView, yView, out)
        return out
    }

    private fun viewOf(t: CanvasTransform, doc: FloatArray): FloatArray {
        val out = FloatArray(2)
        t.docToView(doc[0], doc[1], out)
        return out
    }

    private fun assertStaysUnderFinger(
        start: CanvasTransform,
        result: CanvasTransform,
        anchorX: Float, anchorY: Float,
        nowX: Float, nowY: Float,
        tolerance: Float = 0.02f,
        what: String = "finger",
    ) {
        val doc = docUnder(start, anchorX, anchorY)
        val view = viewOf(result, doc)
        assertEquals(nowX, view[0], tolerance, "$what x: the canvas slipped")
        assertEquals(nowY, view[1], tolerance, "$what y: the canvas slipped")
    }

    @Test
    fun `two fingers translating move the canvas and nothing else`() {
        val start = CanvasTransform(scale = 1.5f, txDoc = 40f, tyDoc = -20f)
        val s = solverAt(start)
        update(s, 400f, 500f, 600f, 500f)
        val r = update(s, 460f, 530f, 660f, 530f)

        assertEquals(start.scale, r.scale, 1e-5f, "a pan changed the scale")
        assertEquals(start.rotationRad, r.rotationRad, 1e-5f, "a pan rotated the canvas")
        assertStaysUnderFinger(start, r, 400f, 500f, 460f, 530f)
        assertStaysUnderFinger(start, r, 600f, 500f, 660f, 530f, what = "second finger")
    }

    @Test
    fun `a pinch scales about the fingers, not about the origin`() {
        val start = CanvasTransform(scale = 1f, txDoc = 0f, tyDoc = 0f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f) // 200 px apart, centroid (600, 400)
        val r = update(s, 400f, 400f, 800f, 400f) // 400 px apart, same centroid

        assertEquals(2f, r.scale, 1e-4f)
        // Scaling about the origin would put the centroid at (1200, 800). The
        // whole point is that it does not move.
        assertStaysUnderFinger(start, r, 600f, 400f, 600f, 400f, what = "centroid")
        assertStaysUnderFinger(start, r, 500f, 400f, 400f, 400f)
    }

    @Test
    fun `a pinch that also travels keeps both fingers on their document points`() {
        // The finger pair keeps its direction exactly — (200, 80) becomes
        // (400, 160) — so the twist is zero and the dead zone is not in play.
        // With any incidental twist under 7 degrees the canvas deliberately
        // does *not* follow the fingers exactly; that trade is the dead zone's
        // whole point and `a small twist does not rotate the canvas` is where
        // it is pinned.
        val start = CanvasTransform(scale = 0.75f, rotationRad = 0.3f, txDoc = 120f, tyDoc = 300f)
        val s = solverAt(start)
        update(s, 300f, 300f, 500f, 380f)
        val r = update(s, 250f, 500f, 650f, 660f)

        assertStaysUnderFinger(start, r, 300f, 300f, 250f, 500f)
        assertStaysUnderFinger(start, r, 500f, 380f, 650f, 660f, what = "second finger")
    }

    @Test
    fun `a small twist does not rotate the canvas`() {
        val start = CanvasTransform(scale = 1f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        // 4 degrees, well inside the dead zone: a relaxed two-finger pinch
        // twists 3 to 5 without anyone meaning it to.
        val a = (4.0 * PI / 180.0).toFloat()
        val cx = 600f
        val cy = 400f
        val r = update(
            s,
            cx - 100f * cos(a), cy - 100f * sin(a),
            cx + 100f * cos(a), cy + 100f * sin(a),
        )
        assertEquals(0f, r.rotationRad, 1e-5f, "an incidental twist tilted the canvas")
        assertFalse(s.isRotating)
    }

    @Test
    fun `past the dead zone rotation starts continuously, not with a jump`() {
        val start = CanvasTransform(scale = 1f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        val cx = 600f
        val cy = 400f

        fun twist(degrees: Double): CanvasTransform {
            val a = (degrees * PI / 180.0).toFloat()
            return update(
                s,
                cx - 100f * cos(a), cy - 100f * sin(a),
                cx + 100f * cos(a), cy + 100f * sin(a),
            )
        }

        // Just past 7 degrees: the applied rotation is the excess, so the
        // canvas starts from level rather than snapping 7 degrees over.
        val justPast = twist(8.0)
        assertTrue(s.isRotating)
        val expected = ((8.0 - 7.0) * PI / 180.0).toFloat()
        assertEquals(expected, justPast.rotationRad, 1e-3f)

        // And it keeps that offset, so rotation tracks the fingers from there.
        val more = twist(30.0)
        assertEquals(((30.0 - 7.0) * PI / 180.0).toFloat(), more.rotationRad, 1e-3f)

        // The cost of continuity, stated: the canvas trails the fingers by the
        // dead zone for the rest of the gesture. Twisting back to level leaves
        // the canvas 7 degrees the other way, which is why it can be twisted
        // back past zero at all.
        val back = twist(0.0)
        assertEquals((-7.0 * PI / 180.0).toFloat(), back.rotationRad, 1e-3f)
    }

    @Test
    fun `a pinch out and back lands exactly where it started`() {
        val start = CanvasTransform(scale = 1.25f, txDoc = 60f, tyDoc = 90f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        // Out in twenty steps and back in twenty, which is a slow deliberate
        // pinch at 60 Hz.
        for (i in 1..20) {
            val half = 100f + i * 10f
            update(s, 600f - half, 400f, 600f + half, 400f)
        }
        for (i in 19 downTo 0) {
            val half = 100f + i * 10f
            update(s, 600f - half, 400f, 600f + half, 400f)
        }
        val r = s.current
        assertEquals(start.scale, r.scale, 1e-4f)
        assertEquals(start.txDoc, r.txDoc, 1e-2f)
        assertEquals(start.tyDoc, r.tyDoc, 1e-2f)

        // The control: the accumulating solver. Each frame multiplies the scale
        // by that frame's ratio and pans by that frame's centroid delta, which
        // is the obvious implementation and the one anchoring replaced.
        var acc = start
        var prevHalf = 100f
        val halves = ArrayList<Float>()
        for (i in 1..20) halves.add(100f + i * 10f)
        for (i in 19 downTo 0) halves.add(100f + i * 10f)
        for (half in halves) {
            acc = acc.zoomedAbout(600f, 400f, half / prevHalf)
            prevHalf = half
        }
        // Round-tripping a product of ratios through a clamped float scale does
        // not come back to where it started. The drift is small per frame and
        // it is the reason the solver is written the other way.
        assertTrue(
            abs(acc.scale - start.scale) > abs(r.scale - start.scale),
            "the control did not drift more than the anchored solver, so this proves nothing",
        )
    }

    @Test
    fun `swapping a finger mid-gesture does not move the canvas`() {
        val start = CanvasTransform(scale = 2f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        val afterPan = update(s, 550f, 450f, 750f, 450f)

        // The second finger lifts and another lands somewhere else entirely.
        val swapped = update(s, 550f, 450f, 900f, 700f, pointerIds = intArrayOf(7, 11))
        assertEquals(afterPan.scale, swapped.scale, 1e-5f)
        assertEquals(afterPan.txDoc, swapped.txDoc, 1e-4f)
        assertEquals(afterPan.tyDoc, swapped.tyDoc, 1e-4f)

        // And the gesture keeps working from the new pair.
        val after = update(s, 570f, 470f, 920f, 720f, pointerIds = intArrayOf(7, 11))
        assertStaysUnderFinger(swapped, after, 550f, 450f, 570f, 470f)
    }

    @Test
    fun `fewer than two pointers holds the canvas still`() {
        val start = CanvasTransform(scale = 1f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        val moved = update(s, 520f, 420f, 720f, 420f)
        val held = s.update(intArrayOf(7), floatArrayOf(900f), floatArrayOf(900f), 1)
        assertEquals(moved, held, "a finger lifting moved the canvas")
    }

    @Test
    fun `fingers too close together are ignored rather than believed`() {
        val start = CanvasTransform(scale = 1f)
        val s = solverAt(start)
        update(s, 600f, 400f, 610f, 400f) // 10 px apart: under the span floor
        val r = update(s, 600f, 400f, 640f, 400f)
        assertEquals(start.scale, r.scale, 1e-5f, "a 10 px anchor was believed")
    }

    @Test
    fun `the pivot stays put when the pinch runs into the scale ceiling`() {
        val start = CanvasTransform(scale = 7f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        val r = update(s, 300f, 400f, 900f, 400f) // x3, but 21 clamps to 8

        assertEquals(CanvasTransform.MAX_SCALE, r.scale, 1e-4f)
        // The centroid must still be where the fingers put it. Computing the
        // correction from the requested factor while rendering at the clamped
        // one is the bug where a pinch past the ceiling stops zooming but keeps
        // sliding the canvas out from under the fingers.
        assertStaysUnderFinger(start, r, 600f, 400f, 600f, 400f, what = "centroid")
    }

    @Test
    fun `an ended gesture stops responding to pointers`() {
        val start = CanvasTransform(scale = 1f)
        val s = solverAt(start)
        update(s, 500f, 400f, 700f, 400f)
        val moved = update(s, 550f, 400f, 750f, 400f)
        s.end()
        assertFalse(s.active)
        assertEquals(moved, update(s, 900f, 900f, 1200f, 900f))
    }
}
