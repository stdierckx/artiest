package be.thalos.artiest.doc

import android.graphics.Color
import android.graphics.Path
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stencil's rules, stated as tests.
 *
 * The plan made a stop condition of this file: *"S2's `Selection` cannot be
 * tested without a device. Then the model has picked up a rendering dependency
 * it should not have."* It is met, with one honest correction to the wording.
 * The plan expected the path, the boolean ops and the bounds to be decidable in
 * a plain JVM test, with only the mask needing Robolectric. They are not, and
 * they cannot be: `Path.op` is Skia, and the alternative is writing a polygon
 * clipper — a worse trade by a wide margin than depending on the same
 * Robolectric-with-native-graphics setup `ScratchLayerTest` already uses.
 *
 * What the stop condition was actually protecting is intact: **nothing here
 * needs a device, and nothing here imports the renderer.** No `SurfaceView`, no
 * `Canvas` from graphics-core, no view at all.
 *
 * The three annotations are load-bearing for the reason `DabRasterizerTest`'s
 * header gives. Without NATIVE graphics at SDK 34 a `Path` is a shadow that
 * computes nothing, every `op` is a no-op and every assertion below passes
 * against a class that does nothing at all. `a rectangle selects its inside and
 * not its outside` is the tripwire: it reads coverage out of the mask, and a
 * shadow `Path` fills nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionTest {

    private val w = 100
    private val h = 60

    private var sel: Selection? = null

    @After
    fun tearDown() {
        sel?.close()
    }

    private fun selection(): Selection = Selection(w, h).also { sel = it }

    private fun rect(l: Float, t: Float, r: Float, b: Float): Path =
        Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun shape(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        mode: SelectMode = SelectMode.NEW,
    ) = SelectOp.Shape(rect(l, t, r, b), mode)

    /** The mask's coverage at a document pixel, 0 when nothing is selected. */
    private fun Selection.coverageAt(x: Int, y: Int): Int {
        val mask = maskBitmap() ?: return 0
        return Color.alpha(mask.getPixel(x, y))
    }

    // --- nothing selected ----------------------------------------------------

    @Test
    fun `a new selection selects nothing`() {
        val s = selection()
        assertFalse(s.active)
        assertNull(s.snapshot.path)
        assertNull(s.maskBitmap(), "no mask is allocated until something is selected")
    }

    @Test
    fun `deselecting nothing changes nothing`() {
        // False is not an error: it is what stops a redraw being asked for.
        assertFalse(selection().apply(SelectOp.None))
    }

    // --- the shapes ----------------------------------------------------------

    @Test
    fun `a rectangle selects its inside and not its outside`() {
        val s = selection()
        assertTrue(s.apply(shape(10f, 10f, 40f, 40f)))
        assertTrue(s.active)
        assertEquals(255, s.coverageAt(25, 25), "inside")
        assertEquals(0, s.coverageAt(60, 25), "outside")
    }

    @Test
    fun `the bounds are the region, clipped to the page`() {
        val s = selection()
        // Deliberately hanging off two edges: a marquee dragged past the paper
        // is an ordinary gesture and the bounds have to stay usable as a
        // rectangle to snapshot for undo.
        s.apply(shape(-30f, -30f, 40f, 40f))
        assertEquals(0, s.bounds.left)
        assertEquals(0, s.bounds.top)
        assertEquals(40, s.bounds.right)
        assertEquals(40, s.bounds.bottom)
    }

    @Test
    fun `an ellipse is soft at its edge and solid in the middle`() {
        // Antialiasing is the reason the mask exists rather than a clipPath;
        // a mask with hard edges would be a clip with extra steps.
        val s = selection()
        val oval = Path().apply { addOval(20f, 10f, 80f, 50f, Path.Direction.CW) }
        s.apply(SelectOp.Shape(oval, SelectMode.NEW))
        assertEquals(255, s.coverageAt(50, 30), "the middle")
        assertEquals(0, s.coverageAt(2, 2), "the corner")
        // Searched over the whole mask rather than along a chosen line: where a
        // soft pixel lands is a property of Skia's sampling, and pinning a
        // coordinate would make this a test of that instead of a test of
        // antialiasing.
        var soft = 0
        for (y in 0 until h) {
            for (x in 0 until w) if (s.coverageAt(x, y) in 1..254) soft++
        }
        assertTrue(soft > 0, "no partly covered pixel anywhere along the rim")
    }

    // --- the boolean operations ----------------------------------------------

    @Test
    fun `add is a union`() {
        val s = selection()
        s.apply(shape(10f, 10f, 30f, 30f))
        s.apply(shape(40f, 10f, 60f, 30f, SelectMode.ADD))
        assertEquals(255, s.coverageAt(20, 20), "the first")
        assertEquals(255, s.coverageAt(50, 20), "the second")
        assertEquals(0, s.coverageAt(35, 20), "the gap between them")
        assertEquals(10, s.bounds.left)
        assertEquals(60, s.bounds.right)
    }

    @Test
    fun `subtract takes the new shape away from the old one`() {
        // DIFFERENCE and not REVERSE_DIFFERENCE. The two are exactly the kind
        // of pair that is read past, and getting it backwards would leave the
        // user with only the part they meant to remove.
        val s = selection()
        s.apply(shape(10f, 10f, 60f, 50f))
        s.apply(shape(30f, 10f, 60f, 50f, SelectMode.SUBTRACT))
        assertEquals(255, s.coverageAt(20, 30), "what was left alone")
        assertEquals(0, s.coverageAt(45, 30), "what was taken away")
    }

    @Test
    fun `intersect keeps only the overlap`() {
        val s = selection()
        s.apply(shape(10f, 10f, 50f, 50f))
        s.apply(shape(30f, 10f, 90f, 50f, SelectMode.INTERSECT))
        assertEquals(0, s.coverageAt(20, 30), "only in the first")
        assertEquals(255, s.coverageAt(40, 30), "in both")
        assertEquals(0, s.coverageAt(70, 30), "only in the second")
    }

    @Test
    fun `adding to nothing is the shape itself`() {
        val s = selection()
        assertTrue(s.apply(shape(10f, 10f, 30f, 30f, SelectMode.ADD)))
        assertEquals(255, s.coverageAt(20, 20))
    }

    @Test
    fun `intersecting with nothing is the shape itself`() {
        val s = selection()
        assertTrue(s.apply(shape(10f, 10f, 30f, 30f, SelectMode.INTERSECT)))
        assertEquals(255, s.coverageAt(20, 20))
    }

    @Test
    fun `subtracting from nothing stays nothing`() {
        // The other reading -- "everything except this" -- would make a stray
        // subtract gesture select the whole page, which is the opposite of what
        // the hand meant.
        val s = selection()
        assertFalse(s.apply(shape(10f, 10f, 30f, 30f, SelectMode.SUBTRACT)))
        assertFalse(s.active)
    }

    // --- all, none, invert ---------------------------------------------------

    @Test
    fun `select all is the whole page`() {
        val s = selection()
        assertTrue(s.apply(SelectOp.All))
        assertEquals(255, s.coverageAt(0, 0))
        assertEquals(255, s.coverageAt(w - 1, h - 1))
        assertEquals(0, s.bounds.left)
        assertEquals(w, s.bounds.right)
    }

    @Test
    fun `invert swaps the inside for the outside`() {
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        assertTrue(s.apply(SelectOp.Invert))
        assertEquals(0, s.coverageAt(25, 25), "what was selected")
        assertEquals(255, s.coverageAt(70, 25), "what was not")
    }

    @Test
    fun `inverting nothing selects everything`() {
        val s = selection()
        assertTrue(s.apply(SelectOp.Invert))
        assertEquals(255, s.coverageAt(50, 30))
    }

    @Test
    fun `deselecting drops the region and keeps the mask allocation`() {
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        assertTrue(s.apply(SelectOp.None))
        assertFalse(s.active)
        assertNull(s.maskBitmap(), "no mask is offered while nothing is selected")
        assertNull(s.snapshot.path)
        // And it comes back without a second allocation being visible to the
        // caller, which is the point of keeping the bitmap.
        s.apply(shape(10f, 10f, 40f, 40f))
        assertEquals(255, s.coverageAt(20, 20))
    }

    // --- the empty region ----------------------------------------------------

    @Test
    fun `subtracting everything deselects rather than blocking the pen`() {
        // The departure from the plan, and the reason is in `Selection`'s
        // header: an active selection with no area is the state where the pen
        // silently does nothing and there are no marching ants to say why.
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        assertTrue(s.apply(shape(0f, 0f, w.toFloat(), h.toFloat(), SelectMode.SUBTRACT)))
        assertFalse(s.active, "an empty region is no selection at all")
        assertNull(s.maskBitmap())
    }

    @Test
    fun `inverting the whole page deselects`() {
        val s = selection()
        s.apply(SelectOp.All)
        assertTrue(s.apply(SelectOp.Invert))
        assertFalse(s.active)
    }

    @Test
    fun `two shapes that do not meet still select both`() {
        // The guard against an over-eager "empty means deselect": a selection
        // in two pieces is a normal thing to build and must not be mistaken for
        // an empty one.
        val s = selection()
        s.apply(shape(10f, 10f, 20f, 20f))
        s.apply(shape(80f, 40f, 90f, 50f, SelectMode.ADD))
        assertTrue(s.active)
        assertEquals(255, s.coverageAt(15, 15))
        assertEquals(255, s.coverageAt(85, 45))
    }

    // --- the crossing to the UI ----------------------------------------------

    @Test
    fun `the published path is a copy the render thread will not touch again`() {
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        val published = assertNotNull(s.snapshot.path)

        s.apply(shape(60f, 10f, 90f, 40f, SelectMode.ADD))

        // The first snapshot still describes the first selection. Without the
        // copy this would have grown underneath whoever was drawing it -- which
        // is the marching ants, on every frame of their animation.
        val bounds = android.graphics.RectF()
        published.computeBounds(bounds, true)
        assertEquals(40f, bounds.right, "the old snapshot did not grow")
        assertEquals(90, s.bounds.right, "while the selection itself did")
    }

    @Test
    fun `an operation that changes nothing does not republish`() {
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        val first = s.snapshot
        assertFalse(s.apply(shape(10f, 10f, 40f, 40f)), "the same rectangle again")
        assertTrue(first === s.snapshot, "and the same snapshot object")
    }

    @Test
    fun `a shape op copies the path it is given`() {
        // The UI thread builds it and the render thread consumes it. A path the
        // UI went on editing after enqueueing would be a region changing while
        // it is being filled.
        val s = selection()
        val building = rect(10f, 10f, 40f, 40f)
        val op = SelectOp.Shape(building, SelectMode.NEW)
        building.addRect(60f, 10f, 90f, 40f, Path.Direction.CW)
        s.apply(op)
        assertEquals(40, s.bounds.right, "the enqueued shape, not the edited one")
    }

    // --- teardown ------------------------------------------------------------

    @Test
    fun `close releases the mask and is idempotent`() {
        val s = selection()
        s.apply(shape(10f, 10f, 40f, 40f))
        s.close()
        s.close()
    }
}
