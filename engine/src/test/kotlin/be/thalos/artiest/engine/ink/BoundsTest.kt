package be.thalos.artiest.engine.ink

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runs on the JVM with no device — which is most of why `Bounds` is in
 * `:engine` at all. Its failures are a stale rim of ink along a redrawn edge
 * and an undo that restores a neighbour's pixels: both are one pixel wide, both
 * appear only after a specific gesture, and neither is something anyone
 * reproduces reliably with a hand on glass.
 *
 * The two invariants worth stating up front, because most of what follows is
 * one of them: a dab contributes its *painted* extent and not its centre, and
 * the integer conversion rounds outward on both edges. Getting either wrong
 * costs exactly one pixel, which is precisely the amount nobody notices in a
 * screenshot.
 */
class BoundsTest {

    // The document, from :spike's DirectSurfaceInkView DOC_W/DOC_H. The clip is
    // a parameter, so these are only what the callers will pass.
    private val docW = 2160
    private val docH = 3300

    private val rect = IntArray(4)

    @Test
    fun `nothing accumulated is empty`() {
        val b = MutableBounds()
        assertTrue(b.isEmpty)
        assertTrue(b.snapshot().isEmpty)
        assertTrue(Bounds.EMPTY.isEmpty)
    }

    /**
     * The distinction the infinite sentinel exists to keep: a zero-radius dab
     * drew something infinitesimal, an untouched accumulator drew nothing, and
     * a redraw has to take different branches for the two.
     */
    @Test
    fun `an empty bounds and a zero-area point are different things`() {
        val point = MutableBounds().apply { add(10f, 10f, 0f) }
        assertFalse(point.isEmpty)
        assertTrue(MutableBounds().isEmpty)

        val snap = point.snapshot()
        assertFalse(snap.isEmpty)
        assertEquals(Bounds.of(10f, 10f, 10f, 10f), snap)
    }

    @Test
    fun `neither the empty bounds nor a point has a negative extent`() {
        assertEquals(0f, Bounds.EMPTY.width)
        assertEquals(0f, Bounds.EMPTY.height)
        val point = MutableBounds().apply { add(10f, 10f, 0f) }.snapshot()
        assertEquals(0f, point.width)
        assertEquals(0f, point.height)
    }

    /**
     * The one that matters. Accumulate the centre and the bounds is one radius
     * short on all four sides, which is the stale-rim bug.
     */
    @Test
    fun `a dab inflates the bounds by its radius on all four sides`() {
        val b = MutableBounds()
        b.add(100f, 200f, 7f)
        assertEquals(93f, b.left)
        assertEquals(193f, b.top)
        assertEquals(107f, b.right)
        assertEquals(207f, b.bottom)
    }

    /** The infinite sentinels are the identities of min and max, so there is no first-dab branch to get wrong. */
    @Test
    fun `the first dab needs no special case`() {
        val fresh = MutableBounds().apply { add(50f, 60f, 2f) }
        val reused = MutableBounds().apply { add(999f, 999f, 1f); reset(); add(50f, 60f, 2f) }
        assertEquals(fresh.snapshot(), reused.snapshot())
    }

    @Test
    fun `a wider dab grows the bounds and a narrower one does not shrink it`() {
        val b = MutableBounds()
        b.add(100f, 100f, 10f)
        b.add(100f, 100f, 3f)
        assertEquals(Bounds.of(90f, 90f, 110f, 110f), b.snapshot())
    }

    @Test
    fun `accumulation is order independent`() {
        val dabs = listOf(
            Triple(10f, 20f, 3f),
            Triple(-5f, 40f, 1.5f),
            Triple(300f, 12f, 8f),
            Triple(120f, 400f, 0.25f),
        )
        val forward = MutableBounds().apply { dabs.forEach { add(it.first, it.second, it.third) } }
        val backward = MutableBounds().apply { dabs.reversed().forEach { add(it.first, it.second, it.third) } }
        val shuffled = MutableBounds().apply {
            listOf(dabs[2], dabs[0], dabs[3], dabs[1]).forEach { add(it.first, it.second, it.third) }
        }
        assertEquals(forward.snapshot(), backward.snapshot())
        assertEquals(forward.snapshot(), shuffled.snapshot())
        // min and max over floats are exact, so this is equality, not tolerance.
        assertEquals(Bounds.of(-6.5f, 4f, 308f, 400.25f), forward.snapshot())
    }

    @Test
    fun `union is commutative and associative`() {
        val a = Bounds.of(0f, 0f, 10f, 10f)
        val b = Bounds.of(-4f, 6f, 3f, 20f)
        val c = Bounds.of(100f, -100f, 101f, -99f)
        assertEquals(a.unionWith(b), b.unionWith(a))
        assertEquals(a.unionWith(b).unionWith(c), a.unionWith(b.unionWith(c)))
        assertEquals(Bounds.of(-4f, -100f, 101f, 20f), a.unionWith(b).unionWith(c))
    }

    @Test
    fun `the empty bounds is the identity of union on both sides`() {
        val a = Bounds.of(1f, 2f, 3f, 4f)
        assertEquals(a, a.unionWith(Bounds.EMPTY))
        assertEquals(a, Bounds.EMPTY.unionWith(a))
        assertEquals(Bounds.EMPTY, Bounds.EMPTY.unionWith(Bounds.EMPTY))
    }

    /** Same identity, through the accumulator, where it holds without a branch. */
    @Test
    fun `unioning an empty bounds into an accumulator changes nothing`() {
        val b = MutableBounds().apply { add(10f, 10f, 1f) }
        val before = b.snapshot()
        b.addBounds(Bounds.EMPTY)
        assertEquals(before, b.snapshot())

        val fresh = MutableBounds()
        fresh.addBounds(Bounds.EMPTY)
        assertTrue(fresh.isEmpty)
        fresh.addBounds(before)
        assertEquals(before, fresh.snapshot())
    }

    /**
     * The failure this catches is silent: without a reset each stroke's bounds
     * covers every stroke before it, the redraw still looks right, and Phase 3's
     * undo over-restores months later.
     */
    @Test
    fun `reset returns the accumulator to empty`() {
        val b = MutableBounds().apply { add(500f, 500f, 40f) }
        b.reset()
        assertTrue(b.isEmpty)
        assertSame(Bounds.EMPTY, b.snapshot())
    }

    @Test
    fun `an empty accumulator snapshots to the shared instance rather than allocating`() {
        assertSame(Bounds.EMPTY, MutableBounds().snapshot())
    }

    /**
     * `-0.0f == 0.0f` as a primitive but not as bits, and a dab at x = 0 with
     * radius 0 produces exactly that. Left alone it makes two geometrically
     * identical bounds hash to different buckets, which surfaces as a W7 golden
     * that fails for no visible reason.
     */
    @Test
    fun `negative zero is normalized away`() {
        val negative = Bounds.of(-0.0f, -0.0f, 10f, 10f)
        val positive = Bounds.of(0.0f, 0.0f, 10f, 10f)
        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertEquals(0.0f.toBits(), negative.left.toBits())

        val accumulated = MutableBounds().apply { add(0f, 0f, 0f) }.snapshot()
        assertEquals(Bounds.of(0f, 0f, 0f, 0f), accumulated)
        assertEquals(Bounds.of(0f, 0f, 0f, 0f).hashCode(), accumulated.hashCode())
    }

    @Test
    fun `the pixel rectangle floors the near edges and ceils the far ones`() {
        // Chosen so every naive rounding is visibly wrong here: round() gives
        // 11 and 20, truncation gives 10 and 20, and both drop a painted column.
        assertTrue(Bounds.of(10.6f, 10.6f, 20.4f, 20.4f).toPixelRect(rect, docW, docH))
        assertEquals(listOf(10, 10, 21, 21), rect.toList())
    }

    @Test
    fun `integer edges convert to themselves and a hair either side does not`() {
        assertTrue(Bounds.of(10f, 10f, 20f, 20f).toPixelRect(rect, docW, docH))
        assertEquals(listOf(10, 10, 20, 20), rect.toList())

        // One ulp outside on each far edge is one more pixel of coverage. round()
        // gives 10 and 20 for both of these, which is the sliver.
        assertTrue(
            Bounds.of(10f.nextDown(), 10f.nextDown(), 20f.nextUp(), 20f.nextUp())
                .toPixelRect(rect, docW, docH),
        )
        assertEquals(listOf(9, 9, 21, 21), rect.toList())

        // One ulp *inside* changes nothing on the near edges and adds a pixel on
        // the far ones, because the far edges are exclusive.
        assertTrue(
            Bounds.of(10f.nextUp(), 10f.nextUp(), 20f.nextDown(), 20f.nextDown())
                .toPixelRect(rect, docW, docH),
        )
        assertEquals(listOf(10, 10, 20, 20), rect.toList())
    }

    /**
     * The exact touched-pixel set for one antialiased dab, hand-computed from
     * `[floor(c - r), ceil(c + r))`. Integral centre first, then fractional,
     * because the fractional case is where an outward-rounding bug hides.
     */
    @Test
    fun `one dab covers exactly the pixels it paints`() {
        val integral = MutableBounds().apply { add(10f, 10f, 2f) }
        assertTrue(integral.toPixelRect(rect, docW, docH))
        // Centre 10, radius 2: columns 8, 9, 10, 11.
        assertEquals(listOf(8, 8, 12, 12), rect.toList())

        val fractional = MutableBounds().apply { add(10.5f, 10.5f, 2f) }
        assertTrue(fractional.toPixelRect(rect, docW, docH))
        // 8.5..12.5 meets columns 8 through 12, one more than the integral case.
        assertEquals(listOf(8, 8, 13, 13), rect.toList())
    }

    @Test
    fun `the pixel rectangle is half open so width is right minus left`() {
        val b = MutableBounds().apply { add(100f, 200f, 5f) }
        assertTrue(b.toPixelRect(rect, docW, docH))
        assertEquals(10, rect[2] - rect[0])
        assertEquals(10, rect[3] - rect[1])
    }

    /**
     * A radius-0 dab paints nothing, so there is nothing to redraw — but only
     * when its centre is integral. Off an integer it still covers the pixel it
     * sits inside.
     */
    @Test
    fun `a zero radius dab covers a pixel only when it is off an integer centre`() {
        assertFalse(MutableBounds().apply { add(10f, 10f, 0f) }.toPixelRect(rect, docW, docH))
        assertTrue(MutableBounds().apply { add(10.5f, 10.5f, 0f) }.toPixelRect(rect, docW, docH))
        assertEquals(listOf(10, 10, 11, 11), rect.toList())
    }

    @Test
    fun `an empty bounds writes nothing and says so`() {
        val untouched = intArrayOf(-1, -1, -1, -1)
        assertFalse(Bounds.EMPTY.toPixelRect(untouched, docW, docH))
        assertEquals(listOf(-1, -1, -1, -1), untouched.toList())
        assertFalse(MutableBounds().toPixelRect(untouched, docW, docH))
        assertEquals(listOf(-1, -1, -1, -1), untouched.toList())
    }

    /**
     * Off-page returns false rather than an inverted or zero-area rectangle, so
     * a caller reusing a scratch array cannot act on stale contents.
     */
    @Test
    fun `a dab entirely off the page has nothing to redraw`() {
        val stale = intArrayOf(7, 7, 7, 7)
        assertFalse(MutableBounds().apply { add(-50f, 100f, 3f) }.toPixelRect(stale, docW, docH))
        assertEquals(listOf(7, 7, 7, 7), stale.toList())
        assertFalse(MutableBounds().apply { add(100f, 5000f, 3f) }.toPixelRect(stale, docW, docH))
    }

    /**
     * The bounds keeps the true extent — it is Phase 3's undo record — and only
     * the conversion clips.
     */
    @Test
    fun `a dab straddling the page edge keeps its extent and clips its rectangle`() {
        val b = MutableBounds().apply { add(2159.5f, 100f, 4f) }
        assertEquals(2163.5f, b.right)
        assertTrue(b.toPixelRect(rect, docW, docH))
        assertEquals(listOf(2155, 96, 2160, 104), rect.toList())

        val negative = MutableBounds().apply { add(2f, 100f, 6f) }
        assertEquals(-4f, negative.left)
        assertTrue(negative.toPixelRect(rect, docW, docH))
        assertEquals(0, rect[0])
    }

    /**
     * The pad belongs to the filtered blit and nothing else; see
     * `Bounds.FILTER_PAD_DOC`. The layer-raster caller takes the default 0.
     */
    @Test
    fun `the filter pad grows the rectangle by one pixel on every side`() {
        val b = Bounds.of(100f, 100f, 110f, 110f)
        assertTrue(b.toPixelRect(rect, docW, docH))
        assertEquals(listOf(100, 100, 110, 110), rect.toList())
        assertTrue(b.toPixelRect(rect, docW, docH, pad = Bounds.FILTER_PAD_DOC))
        assertEquals(listOf(99, 99, 111, 111), rect.toList())
    }

    @Test
    fun `the rectangle is written at the requested offset`() {
        val out = IntArray(6) { -1 }
        assertTrue(Bounds.of(1f, 2f, 3f, 4f).toPixelRect(out, docW, docH, offset = 2))
        assertEquals(listOf(-1, -1, 1, 2, 3, 4), out.toList())
    }

    /**
     * `Float.toInt()` saturates to Int.MAX_VALUE rather than wrapping negative,
     * and `coerceIn` right after is what makes that safe. If it wrapped, this
     * would clamp to 0 and report nothing to redraw.
     */
    @Test
    fun `an absurd but finite coordinate saturates rather than wrapping`() {
        assertTrue(Bounds.of(100f, 100f, 1e30f, 1e30f).toPixelRect(rect, docW, docH))
        assertEquals(listOf(100, 100, docW, docH), rect.toList())
    }

    /**
     * `add` validates although it runs at dab rate, unlike
     * `CanvasTransform.viewToDoc`, which declines to. The difference is that
     * this accumulates: a NaN absorbed here is invisible — min and max
     * propagate it and `left > right` reads false for it — and surfaces at a
     * redraw with nothing pointing back at the dab.
     */
    @Test
    fun `a non-finite dab is refused where it happens`() {
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(Float.NaN, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(0f, Float.NaN, 1f) }
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(0f, 0f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(Float.POSITIVE_INFINITY, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(0f, Float.NEGATIVE_INFINITY, 1f) }
        // Infinite radius specifically, because `radius >= 0f` alone admits it,
        // and an infinite extent is indistinguishable from Bounds.EMPTY.
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(0f, 0f, Float.POSITIVE_INFINITY) }
    }

    /** A negative radius shrinks the box for that dab: the rim bug, written deliberately. */
    @Test
    fun `a negative radius is refused`() {
        assertFailsWith<IllegalArgumentException> { MutableBounds().add(10f, 10f, -1f) }
    }

    @Test
    fun `a rejected dab leaves the accumulator untouched`() {
        val b = MutableBounds().apply { add(10f, 10f, 1f) }
        val before = b.snapshot()
        assertFailsWith<IllegalArgumentException> { b.add(Float.NaN, 5f, 1f) }
        assertEquals(before, b.snapshot())
    }

    @Test
    fun `a non-finite or inverted bounds cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Bounds.of(Float.NaN, 0f, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> {
            Bounds.of(Float.NEGATIVE_INFINITY, 0f, 1f, 1f)
        }
        assertFailsWith<IllegalArgumentException> { Bounds.of(0f, 0f, Float.POSITIVE_INFINITY, 1f) }
        assertFailsWith<IllegalArgumentException> { Bounds.of(10f, 0f, 5f, 1f) }
        assertFailsWith<IllegalArgumentException> { Bounds.of(0f, 10f, 1f, 5f) }
    }

    @Test
    fun `the pixel conversion refuses a buffer or a clip it cannot use`() {
        val b = Bounds.of(0f, 0f, 10f, 10f)
        assertFailsWith<IllegalArgumentException> { b.toPixelRect(IntArray(3), docW, docH) }
        assertFailsWith<IllegalArgumentException> { b.toPixelRect(IntArray(4), docW, docH, offset = 1) }
        assertFailsWith<IllegalArgumentException> { b.toPixelRect(rect, -1, docH) }
        // A negative pad shrinks the rectangle, which is the rim bug again; NaN
        // fails the same `>= 0f` comparison.
        assertFailsWith<IllegalArgumentException> { b.toPixelRect(rect, docW, docH, pad = -1f) }
        assertFailsWith<IllegalArgumentException> { b.toPixelRect(rect, docW, docH, pad = Float.NaN) }
    }
}
