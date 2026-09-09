package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaskTest {

    // ---- tolerance -----------------------------------------------------------

    /**
     * Geometric, not fixed-step, and this is the number that justifies it: the
     * whole plausible size range fits in a couple of hundred buckets, so the
     * cache is bounded without the step being coarse anywhere.
     */
    @Test
    fun `the size range fits in a few hundred buckets`() {
        val t = MaskTolerance()
        val span = t.sizeBucket(128f) - t.sizeBucket(1.5f)
        assertTrue(span in 100..250, "1.5..128 px spans $span buckets")
    }

    @Test
    fun `adjacent size buckets differ by the tolerance ratio`() {
        val t = MaskTolerance(sizeRatio = 1.03f)
        val a = t.sizeForBucket(100)
        val b = t.sizeForBucket(101)
        assertEquals(1.03f, b / a, 1e-4f)
    }

    @Test
    fun `nearby sizes share a bucket and distant ones do not`() {
        val t = MaskTolerance()
        assertEquals(t.sizeBucket(24f), t.sizeBucket(24.1f))
        assertNotEquals(t.sizeBucket(24f), t.sizeBucket(30f))
    }

    /**
     * The trap the plan's hit-rate warning describes. A circle looks the same
     * at every angle, so a round dab keyed by travel direction would miss on
     * essentially every dab of a curve while producing identical bitmaps.
     */
    @Test
    fun `a round dab ignores rotation entirely`() {
        val t = MaskTolerance()
        val keys = (0..16).map {
            t.key(MaskSpec(24f, 1f, 1f, it * PI.toFloat() / 16f))
        }.toSet()
        assertEquals(1, keys.size, "a circle produced ${keys.size} distinct keys")
    }

    @Test
    fun `an elliptical dab does distinguish rotation`() {
        val t = MaskTolerance()
        val a = t.key(MaskSpec(24f, 1f, 0.5f, 0f))
        val b = t.key(MaskSpec(24f, 1f, 0.5f, PI.toFloat() / 2f))
        assertNotEquals(a, b)
    }

    /** An ellipse at theta and theta+PI is the same shape. */
    @Test
    fun `rotation folds at PI`() {
        val t = MaskTolerance()
        val a = t.key(MaskSpec(24f, 1f, 0.5f, 0.3f))
        val b = t.key(MaskSpec(24f, 1f, 0.5f, 0.3f + PI.toFloat()))
        assertEquals(a, b)
        assertEquals(
            t.key(MaskSpec(24f, 1f, 0.5f, 0f)),
            t.key(MaskSpec(24f, 1f, 0.5f, PI.toFloat())),
            "PI and 0 are the same angle",
        )
    }

    @Test
    fun `the four dimensions all reach the key`() {
        val t = MaskTolerance()
        val base = MaskSpec(24f, 1f, 0.5f, 0.4f)
        assertNotEquals(t.key(base), t.key(base.copy(diameter = 40f)))
        assertNotEquals(t.key(base), t.key(base.copy(hardness = 0.2f)))
        assertNotEquals(t.key(base), t.key(base.copy(aspect = 0.25f)))
        assertNotEquals(t.key(base), t.key(base.copy(rotationRad = 1.4f)))
    }

    @Test
    fun `a tiny diameter does not produce a negative key`() {
        val t = MaskTolerance()
        assertTrue(t.key(MaskSpec(0.01f, 1f, 1f, 0f)) >= 0L)
        assertTrue(t.key(MaskSpec(0.5f, 1f, 1f, 0f)) >= 0L)
    }

    @Test
    fun `quantize lands on the bucket the key names`() {
        val t = MaskTolerance()
        val q = t.quantize(MaskSpec(23.7f, 0.83f, 0.61f, 1.1f))
        assertEquals(t.key(MaskSpec(23.7f, 0.83f, 0.61f, 1.1f)), t.key(q))
        assertEquals(q, t.quantize(q), "quantizing twice must not move it again")
    }

    @Test
    fun `an impossible spec is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { MaskSpec(0f, 1f, 1f, 0f) }
        assertFailsWith<IllegalArgumentException> { MaskSpec(-1f, 1f, 1f, 0f) }
        assertFailsWith<IllegalArgumentException> { MaskSpec(Float.NaN, 1f, 1f, 0f) }
        assertFailsWith<IllegalArgumentException> { MaskSpec(4f, 1f, 0f, 0f) }
    }

    // ---- generation ----------------------------------------------------------

    @Test
    fun `a hard round dab is opaque at the centre and empty at the corners`() {
        val m = MaskGenerator.generate(MaskSpec.round(24f))
        assertEquals(255, m.alphaAt(m.width / 2, m.height / 2))
        assertEquals(0, m.alphaAt(0, 0), "the corner is outside the disc")
        assertEquals(0, m.alphaAt(m.width - 1, m.height - 1))
    }

    /**
     * The signed-byte trap this type's header names. `alpha[i].toInt()` is
     * negative above half opacity, and the symptom is a hollow-looking dab.
     */
    @Test
    fun `alpha reads back unsigned`() {
        val m = MaskGenerator.generate(MaskSpec.round(24f))
        val raw = m.alpha[m.height / 2 * m.width + m.width / 2].toInt()
        assertTrue(raw < 0, "the raw byte really is negative, which is the trap")
        assertEquals(255, m.alphaAt(m.width / 2, m.height / 2))
    }

    /**
     * Counted over the whole mask, not one row, and that is not fussiness. A
     * 24 px disc centred in a 26 px mask has its edge at exactly x=1 and x=25,
     * so the centre row is genuinely binary however finely it is sampled — the
     * antialiasing lives on the diagonals, where the boundary actually crosses
     * pixels.
     */
    @Test
    fun `the rim is antialiased rather than binary`() {
        val m = MaskGenerator.generate(MaskSpec.round(24f))
        val partial = m.alpha.count { (it.toInt() and 0xFF) in 1..254 }
        assertTrue(partial >= 16, "only $partial partially covered pixels in the whole mask")
    }

    @Test
    fun `a soft dab falls off from the centre and a hard one does not`() {
        val hard = MaskGenerator.generate(MaskSpec.round(32f, hardness = 1f))
        val soft = MaskGenerator.generate(MaskSpec.round(32f, hardness = 0f))
        val cy = hard.height / 2
        val quarter = hard.width / 2 + hard.width / 4 - 1
        assertEquals(255, hard.alphaAt(quarter, cy), "a hard dab is solid to its rim")
        assertTrue(soft.alphaAt(quarter, cy) < 200, "a soft dab should have faded by the rim")
        // Not exactly 255: with hardness 0 the falloff starts at the centre, and
        // the subsamples of the centre pixel are a fraction of a pixel off it.
        assertTrue(soft.alphaAt(soft.width / 2, cy) > 250, "the centre should be near solid")
    }

    @Test
    fun `an ellipse is wider along its major axis`() {
        val m = MaskGenerator.generate(MaskSpec(32f, 1f, 0.25f, 0f))
        val cx = m.width / 2
        val cy = m.height / 2
        val across = (0 until m.width).count { m.alphaAt(it, cy) > 127 }
        val down = (0 until m.height).count { m.alphaAt(cx, it) > 127 }
        assertTrue(across > down * 2, "major $across minor $down")
    }

    @Test
    fun `rotating an ellipse by a right angle swaps its axes`() {
        val flat = MaskGenerator.generate(MaskSpec(32f, 1f, 0.25f, 0f))
        val up = MaskGenerator.generate(MaskSpec(32f, 1f, 0.25f, (PI / 2).toFloat()))
        val cx = flat.width / 2
        val cy = flat.height / 2
        val flatAcross = (0 until flat.width).count { flat.alphaAt(it, cy) > 127 }
        val upDown = (0 until up.height).count { up.alphaAt(cx, it) > 127 }
        assertEquals(flatAcross, upDown)
    }

    @Test
    fun `the mask has a pixel of margin so the rim is never clipped`() {
        val m = MaskGenerator.generate(MaskSpec.round(10f))
        assertEquals(12, m.width)
        for (x in 0 until m.width) {
            assertEquals(0, m.alphaAt(x, 0), "row 0 should be empty margin")
            assertEquals(0, m.alphaAt(x, m.height - 1))
        }
    }

    @Test
    fun `a sub-pixel dab still produces a usable mask`() {
        val m = MaskGenerator.generate(MaskSpec.round(0.4f))
        assertTrue(m.width >= 1)
        assertTrue(m.alpha.any { (it.toInt() and 0xFF) > 0 }, "a dab that marks nothing is not a dab")
    }

    // ---- the cache -----------------------------------------------------------

    @Test
    fun `a repeated spec is a hit and is the same instance`() {
        val c = MaskCache()
        val a = c.get(MaskSpec.round(24f))
        val b = c.get(MaskSpec.round(24f))
        assertSame(a, b)
        assertEquals(1L, c.misses)
        assertEquals(1L, c.hits)
        assertEquals(1L, c.generated)
    }

    @Test
    fun `a spec inside the tolerance is a hit`() {
        val c = MaskCache()
        c.get(MaskSpec.round(24f))
        c.get(MaskSpec.round(24.1f))
        assertEquals(1L, c.generated, "24.1 should have shared 24's bucket")
    }

    @Test
    fun `the mask carries the quantised spec, not the requested one`() {
        val c = MaskCache()
        val m = c.get(MaskSpec.round(23.7f))
        assertEquals(c.tolerance.quantize(MaskSpec.round(23.7f)), m.spec)
    }

    @Test
    fun `the budget is enforced and the newest entry survives it`() {
        val c = MaskCache(budgetBytes = 4096)
        repeat(40) { c.get(MaskSpec.round(20f + it * 3f)) }
        assertTrue(c.byteCount <= 4096 || c.size == 1, "held ${c.byteCount} bytes in ${c.size} masks")
        assertTrue(c.evictions > 0)
        assertTrue(c.size >= 1)
    }

    /**
     * A mask larger than the whole budget must still be returned and held,
     * or every dab of a very large brush is a miss — the exact pathology the
     * cache exists to prevent.
     */
    @Test
    fun `a mask bigger than the budget is still kept`() {
        val c = MaskCache(budgetBytes = 16)
        val m = c.get(MaskSpec.round(64f))
        assertTrue(m.byteCount > 16)
        assertEquals(1, c.size)
        assertTrue(c.holds(MaskSpec.round(64f)))
    }

    @Test
    fun `holds does not count as a lookup or build anything`() {
        val c = MaskCache()
        assertTrue(!c.holds(MaskSpec.round(24f)))
        assertEquals(0L, c.hits)
        assertEquals(0L, c.misses)
        assertEquals(0, c.size)
    }

    @Test
    fun `clear drops the masks and resetStats drops only the counters`() {
        val c = MaskCache()
        c.get(MaskSpec.round(24f))
        c.get(MaskSpec.round(24f))
        c.resetStats()
        assertEquals(0L, c.hits)
        assertEquals(1, c.size, "resetStats must not evict")
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0L, c.byteCount)
    }

    @Test
    fun `hitRate is NaN before the first lookup`() {
        assertTrue(MaskCache().hitRate.isNaN())
    }
}
