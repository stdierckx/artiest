package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the reference pane holds a picture, and how it holds a model.
 *
 * `PaneView` exists because the panel it belongs to leaves the composition
 * whenever the toolbars are rearranged — so everything worth keeping lives
 * here, and everything here is a plain number a test can read. The rules worth
 * pinning down are the ones a hand would notice if they broke: a model cannot
 * be tumbled past upright, it cannot be dollied away to nothing, and picking a
 * different reference starts clean.
 */
class PaneViewTest {

    @Test
    fun `a drag turns the model and not the light`() {
        val pane = PaneView()
        val spin = pane.spin
        val tilt = pane.tilt
        val azimuth = pane.lightAzimuth
        pane.dragged(100f, 20f)
        assertTrue(pane.spin != spin, "a drag across should have turned it")
        assertTrue(pane.tilt != tilt, "a drag down should have tilted it")
        assertEquals(azimuth, pane.lightAzimuth, "the light must not have moved")
    }

    @Test
    fun `in light mode a drag moves the light and not the model`() {
        val pane = PaneView()
        pane.lighting = true
        val spin = pane.spin
        val tilt = pane.tilt
        pane.dragged(100f, 20f)
        assertEquals(spin, pane.spin)
        assertEquals(tilt, pane.tilt)
        assertTrue(pane.lightAzimuth != -35f)
    }

    /**
     * Past the pole the model would be upside down and the drag would reverse,
     * which feels like the model fighting the hand. It stops instead.
     */
    @Test
    fun `tilt stops short of straight above and straight below`() {
        val pane = PaneView()
        pane.dragged(0f, 100_000f)
        assertTrue(pane.tilt <= 85f, "tilt ran to ${pane.tilt}")
        pane.dragged(0f, -200_000f)
        assertTrue(pane.tilt >= -85f, "tilt ran to ${pane.tilt}")
    }

    /**
     * The model is not allowed past its poles and the light is, and that is the
     * difference between a thing standing on a table and a lamp on a stand.
     * `LightBallTest` is where the light's own arithmetic is pinned down.
     */
    @Test
    fun `the light has no poles to stop at`() {
        val pane = PaneView()
        pane.lighting = true
        repeat(40) { pane.dragged(0f, -90f) }
        assertTrue(pane.lightElevation.isFinite())
        assertTrue(pane.lightAzimuth.isFinite())
    }

    @Test
    fun `spin keeps running round and does not stop anywhere`() {
        val pane = PaneView()
        val start = pane.spin
        pane.dragged(-10_000f, 0f)
        assertTrue(pane.spin - start > 1000f, "spin stopped at ${pane.spin}")
    }

    @Test
    fun `a pinch cannot lose the model`() {
        val pane = PaneView()
        repeat(60) { pane.pinched(2f) }
        assertTrue(pane.dolly <= 14f, "dolly ran to ${pane.dolly}")
        repeat(120) { pane.pinched(0.5f) }
        assertTrue(pane.dolly >= 0.4f, "dolly ran to ${pane.dolly}")
    }

    /**
     * Fit is the button that puts the reference back the way it arrived, and it
     * has to do that for both kinds — it was a picture-only button before a
     * model could be in the pane, which is exactly the sort of thing that
     * quietly stops working.
     */
    @Test
    fun `fit puts a model back as well as a picture`() {
        val pane = PaneView()
        pane.scale = 4f
        pane.turn = 30f
        pane.dragged(200f, 50f)
        pane.pinched(3f)
        pane.fit()
        assertEquals(1f, pane.scale)
        assertEquals(0f, pane.turn)
        assertEquals(24f, pane.spin)
        assertEquals(8f, pane.tilt)
        assertEquals(1f, pane.dolly)
    }

    /**
     * Fit deliberately leaves the switches alone: flipped, grey, stone and the
     * contour lines are how the artist has asked to see the thing, not where
     * they have got to in it.
     */
    @Test
    fun `fit leaves the way of seeing alone`() {
        val pane = PaneView()
        pane.flipped = true
        pane.grey = true
        pane.stone = true
        pane.contour = true
        pane.lighting = true
        pane.fit()
        assertTrue(pane.flipped)
        assertTrue(pane.grey)
        assertTrue(pane.stone)
        assertTrue(pane.contour)
        assertTrue(pane.lighting)
    }

    /**
     * The line count is part of the pose, not of the app: it survives fitting
     * (you have not asked for a different grid by reframing) and it is cleared
     * by a new reference (the last model's grid is nobody's idea of this one's).
     */
    @Test
    fun `how many lines is kept by fit and cleared by reset`() {
        val pane = PaneView()
        assertEquals(PaneView.DEFAULT_SLICES, pane.slices)
        pane.slices = 64f
        pane.fit()
        assertEquals(64f, pane.slices)
        pane.reset()
        assertEquals(PaneView.DEFAULT_SLICES, pane.slices)
    }

    /** A different reference starts clean; that is what `reset` is for. */
    @Test
    fun `reset clears everything a previous reference left`() {
        val pane = PaneView()
        pane.scale = 4f
        pane.flipped = true
        pane.grey = true
        pane.stone = true
        pane.contour = true
        pane.lighting = true
        pane.dragged(300f, 40f)
        pane.pinched(4f)
        pane.reset()
        assertEquals(1f, pane.scale)
        assertEquals(24f, pane.spin)
        assertEquals(8f, pane.tilt)
        assertEquals(1f, pane.dolly)
        assertEquals(-35f, pane.lightAzimuth)
        assertEquals(34f, pane.lightElevation)
        assertFalse(pane.flipped)
        assertFalse(pane.grey)
        assertFalse(pane.stone)
        assertFalse(pane.contour)
        assertFalse(pane.lighting)
    }
}
