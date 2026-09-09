package be.thalos.artiest.engine.xform

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs on the JVM with no device, no emulator and no Android SDK — that is the
 * property the `:engine` module was created to buy, and this is the test that
 * demonstrates it holds rather than assuming it.
 *
 * The assertions are the transform's stated invariants, not smoke: the zoom
 * clamp at both ends, the angle staying bounded across many turns, and the
 * constructor refusing what the clamp would have caught. Each corresponds to a
 * failure that is expensive to see on a tablet — a shimmering zoomed-out
 * document, a twist that quantizes after a long session, a transform that is
 * wrong somewhere far from where it was built.
 */
class CanvasTransformTest {

    @Test
    fun `identity is the untransformed document`() {
        val t = CanvasTransform.IDENTITY
        assertEquals(1f, t.scale)
        assertEquals(0f, t.rotationRad)
        assertEquals(0f, t.txDoc)
        assertEquals(0f, t.tyDoc)
    }

    @Test
    fun `zoom clamps at both ends rather than throwing`() {
        assertEquals(CanvasTransform.MIN_SCALE, CanvasTransform.IDENTITY.zoomedTo(CanvasTransform.MIN_SCALE / 2f).scale)
        assertEquals(CanvasTransform.MAX_SCALE, CanvasTransform.IDENTITY.zoomedTo(CanvasTransform.MAX_SCALE + 1f).scale)
        assertEquals(2f, CanvasTransform.IDENTITY.zoomedTo(2f).scale)
        // Stated against the constant rather than a literal, because the floor
        // moved once already and a literal is what made that a test failure
        // rather than a decision.
        assertEquals(0.3f, CanvasTransform.IDENTITY.zoomedTo(0.3f).scale)
    }

    /**
     * A pinch reports a factor, and the clamp has to survive composition. This
     * is the case that a naive `scale = clamp(scale) * factor` gets wrong.
     */
    @Test
    fun `relative zoom composes and clamps once`() {
        assertEquals(4f, CanvasTransform.IDENTITY.zoomedBy(2f).zoomedBy(2f).scale)
        assertEquals(CanvasTransform.MAX_SCALE, CanvasTransform.IDENTITY.zoomedBy(2f).zoomedBy(100f).scale)
        assertEquals(CanvasTransform.MIN_SCALE, CanvasTransform.IDENTITY.zoomedBy(0.01f).scale)
    }

    @Test
    fun `a zoom factor of zero or less is rejected, not clamped`() {
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.zoomedBy(0f) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.zoomedBy(-1f) }
    }

    /**
     * NaN passes every `<`/`>` comparison a clamp is built from, so it survives
     * `coerceIn` untouched. The constructor's `require` is what stops it, and a
     * NaN scale reaching the renderer blanks the canvas with no error.
     */
    @Test
    fun `NaN scale is refused`() {
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.zoomedTo(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform(scale = Float.NaN) }
    }

    @Test
    fun `out-of-range construction fails where it happens`() {
        assertFailsWith<IllegalArgumentException> { CanvasTransform(scale = 0f) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.copy(scale = 9f) }
    }

    /**
     * The rotate gesture's own arithmetic produces this one: atan2 of the
     * vector between two pointers, with both pointers reported at the same
     * coordinate, is atan2(0, 0) — NaN. Left unchecked it reaches sin/cos and
     * every subsequent frame renders nothing, with no exception anywhere near
     * the gesture that caused it.
     */
    @Test
    fun `NaN rotation and translation are refused`() {
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.rotatedBy(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform(rotationRad = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform.IDENTITY.pannedBy(Float.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { CanvasTransform(tyDoc = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `pan accumulates in document units`() {
        val t = CanvasTransform.IDENTITY.pannedBy(10f, -4f).pannedBy(-3f, 1f)
        assertEquals(7f, t.txDoc)
        assertEquals(-3f, t.tyDoc)
    }

    @Test
    fun `rotation stays bounded no matter how far it is twisted`() {
        val quarterTurn = (PI / 2.0).toFloat()
        var t = CanvasTransform.IDENTITY
        repeat(1000) { t = t.rotatedBy(quarterTurn) }
        assertTrue(
            t.rotationRad > -PI.toFloat() && t.rotationRad <= PI.toFloat(),
            "rotation escaped (-PI, PI]: ${t.rotationRad}",
        )
    }

    @Test
    fun `rotation wraps to the short way round`() {
        val threeQuarters = (1.5 * PI).toFloat()
        assertEquals(-PI.toFloat() / 2f, CanvasTransform.IDENTITY.rotatedBy(threeQuarters).rotationRad, 1e-5f)
        // Half a turn is the boundary case, and it belongs to the positive end.
        assertEquals(PI.toFloat(), CanvasTransform.IDENTITY.rotatedBy(PI.toFloat()).rotationRad, 1e-5f)
    }
}
