package be.thalos.artiest.engine.brush

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wb5: the nib that is a picture.
 *
 * The tests that matter here are the ones about *asymmetry*, because that is
 * the whole reason a tip exists. An ellipse is the same upside down and the
 * cache exploits it; a picture is not, and every assumption the cache made
 * about rotation has to be re-checked with a tip in hand.
 */
class TipTest {

    // ---- fixtures ------------------------------------------------------------

    /** A tip whose left half paints and whose right half does not. */
    private fun halfTip(id: String = "half", slot: Int = 1, size: Int = 32): Tip {
        val a = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size / 2) a[y * size + x] = 255.toByte()
        }
        return Tip(id, size, size, a, slot)
    }

    /** Alternating opaque and empty columns, one pixel wide. */
    private fun combTip(size: Int = 64): Tip {
        val a = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) if (x % 2 == 0) a[y * size + x] = 255.toByte()
        }
        return Tip("comb", size, size, a)
    }

    // ---- the picture itself --------------------------------------------------

    @Test
    fun `a tip keeps halving until nothing is left`() {
        val tip = halfTip(size = 32)
        // 32, 16, 8, 4, 2, 1
        assertEquals(6, tip.levelCount)
    }

    @Test
    fun `a tip that is not square still halves both ways`() {
        val tip = Tip("wide", 8, 2, ByteArray(16) { 255.toByte() })
        // 8x2, 4x1, 2x1, 1x1
        assertEquals(4, tip.levelCount)
        assertEquals(8, tip.span)
    }

    @Test
    fun `the level chosen gets coarser as the dab gets smaller`() {
        val tip = halfTip(size = 64)
        assertEquals(0, tip.levelFor(1f))
        assertTrue(tip.levelFor(0.25f) > tip.levelFor(0.75f))
        assertTrue(tip.levelFor(0.01f) >= 4)
    }

    /**
     * The reason the mip chain is here rather than a bilinear read.
     *
     * A one-pixel comb minified eightfold is the classic aliasing case: point
     * or bilinear sampling reads whichever phase it lands on and the dab
     * flickers between solid and empty as the stroke moves. Averaged down it is
     * half grey everywhere, which is what the mark actually looks like.
     */
    @Test
    fun `a fine pattern averages down instead of flickering`() {
        val tip = combTip(64)
        val mask = MaskGenerator.generate(MaskSpec(8f, 1f, 1f, 0f, tip))
        val middle = mask.height / 2
        var lowest = 255
        var highest = 0
        for (x in 2 until mask.width - 2) {
            val v = mask.alphaAt(x, middle)
            if (v < lowest) lowest = v
            if (v > highest) highest = v
        }
        assertTrue(lowest > 60, "the comb went dark somewhere: $lowest")
        assertTrue(highest < 200, "the comb went solid somewhere: $highest")
    }

    // ---- the dab -------------------------------------------------------------

    @Test
    fun `a tipped dab is the picture and not an ellipse`() {
        val mask = MaskGenerator.generate(MaskSpec(32f, 1f, 1f, 0f, halfTip()))
        val middle = mask.height / 2
        assertTrue(mask.alphaAt(5, middle) > 200, "the left half did not paint")
        assertTrue(mask.alphaAt(mask.width - 5, middle) < 20, "the right half painted")
    }

    /**
     * Half a turn is the difference between a mark and its mirror for a
     * picture, and no difference at all for an ellipse. Both halves are
     * asserted, because the ellipse's symmetry is what the cache spends and it
     * must not be spent for the tip.
     */
    @Test
    fun `half a turn mirrors a tip and does nothing to an ellipse`() {
        val turned = MaskGenerator.generate(MaskSpec(32f, 1f, 1f, PI.toFloat(), halfTip()))
        val middle = turned.height / 2
        assertTrue(turned.alphaAt(5, middle) < 20, "the mark did not turn over")
        assertTrue(turned.alphaAt(turned.width - 5, middle) > 200, "the mark did not turn over")

        val t = MaskTolerance()
        val flat = MaskSpec(32f, 1f, 0.5f, 0f)
        assertEquals(t.key(flat), t.key(flat.copy(rotationRad = PI.toFloat())))
    }

    @Test
    fun `a tip is measured across its longest side`() {
        val tip = Tip("tall", 16, 64, ByteArray(16 * 64) { 255.toByte() })
        val mask = MaskGenerator.generate(MaskSpec(40f, 1f, 1f, 0f, tip))
        // 64 tall becomes 40; 16 wide becomes 10. Plus the pad, in a square.
        assertEquals(42, mask.width)
        val middle = mask.height / 2
        assertTrue(mask.alphaAt(mask.width / 2, middle) > 200)
        assertTrue(mask.alphaAt(3, middle) < 20, "the narrow axis was stretched")
    }

    /**
     * The mask stays square — the ellipse path's own shape, so the hotspot
     * arithmetic is the same for both — so what shrinks is the painted band
     * inside it, and that is what this counts.
     */
    @Test
    fun `aspect squashes a tip the way tilt squashes an ellipse`() {
        val tip = Tip("box", 32, 32, ByteArray(32 * 32) { 255.toByte() })
        val round = MaskGenerator.generate(MaskSpec(32f, 1f, 1f, 0f, tip))
        val flat = MaskGenerator.generate(MaskSpec(32f, 1f, 0.25f, 0f, tip))
        assertTrue(inkedRows(flat) < inkedRows(round) / 2, "the squash did nothing")
        assertTrue(flat.alphaAt(flat.width / 2, flat.height / 2) > 200)
    }

    private fun inkedRows(mask: AlphaMask): Int {
        var rows = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask.alphaAt(x, y) > 8) {
                    rows++
                    break
                }
            }
        }
        return rows
    }

    @Test
    fun `a tip nobody can see leaves an empty dab rather than an exception`() {
        val mask = MaskGenerator.generate(MaskSpec(24f, 1f, 0.0001f, 0f, halfTip()))
        for (i in mask.alpha.indices) assertEquals(0, mask.alpha[i].toInt() and 0xFF)
    }

    // ---- what the cache keys on ----------------------------------------------

    @Test
    fun `two tips are two masks and no tip is a third`() {
        val t = MaskTolerance()
        val spec = MaskSpec(24f, 1f, 1f, 0f)
        val a = t.key(spec.copy(tip = halfTip("a", slot = 1)))
        val b = t.key(spec.copy(tip = halfTip("b", slot = 2)))
        assertNotEquals(a, b)
        assertNotEquals(a, t.key(spec))
    }

    /**
     * A round tip still faces a direction. The collapse that saves 64 buckets
     * for a circular *ellipse* describes the squash, not the mark, and applying
     * it to a picture would draw every bristle fan pointing the same way.
     */
    @Test
    fun `a round tip keeps its rotation buckets`() {
        val t = MaskTolerance()
        val spec = MaskSpec(24f, 1f, 1f, 0f, halfTip())
        assertNotEquals(t.key(spec), t.key(spec.copy(rotationRad = 1f)))
        // The same dab without the picture is a circle, and a circle does not.
        val plain = spec.copy(tip = null)
        assertEquals(t.key(plain), t.key(plain.copy(rotationRad = 1f)))
    }

    /**
     * Hardness has no meaning over a picture, so letting it into the key would
     * build the same bitmap sixteen times for a brush whose hardness happens to
     * move with pressure.
     */
    @Test
    fun `hardness does not split a tip's cache`() {
        val t = MaskTolerance()
        val spec = MaskSpec(24f, 1f, 1f, 0f, halfTip())
        assertEquals(t.key(spec), t.key(spec.copy(hardness = 0.3f)))
    }

    @Test
    fun `a quantised tipped spec lands in its own bucket`() {
        val t = MaskTolerance()
        val spec = MaskSpec(23.7f, 1f, 0.83f, 2.4f, halfTip())
        val q = t.quantize(spec)
        assertSame(spec.tip, q.tip)
        assertEquals(t.key(spec), t.key(q), "quantising moved the dab to another bucket")
    }

    @Test
    fun `a cached tipped dab is built once`() {
        val cache = MaskCache()
        val spec = MaskSpec(24f, 1f, 1f, 0f, halfTip())
        cache.get(spec)
        cache.get(spec.copy(diameter = 24.1f))
        assertEquals(1L, cache.generated)
    }

    // ---- the library ---------------------------------------------------------

    @Test
    fun `the same tip twice is one tip`() {
        val lib = TipLibrary()
        val first = lib.add("stub", 4, 4, ByteArray(16))
        val second = lib.add("stub", 4, 4, ByteArray(16))
        assertSame(first, second)
        assertEquals(1, lib.size)
    }

    @Test
    fun `every tip gets its own slot`() {
        val lib = TipLibrary()
        val a = lib.add("a", 4, 4, ByteArray(16))!!
        val b = lib.add("b", 4, 4, ByteArray(16))!!
        assertNotEquals(a.slot, b.slot)
    }

    /**
     * Refuses rather than evicting. A tip that vanished mid-drawing would
     * change what the brush in the hand draws with, halfway down a stroke.
     */
    @Test
    fun `a library that is full says no`() {
        val lib = TipLibrary(budgetBytes = 4096)
        assertTrue(lib.add("big", 64, 64, ByteArray(64 * 64)) != null)
        assertNull(lib.add("bigger", 128, 128, ByteArray(128 * 128)))
        assertEquals(1, lib.size)
    }

    @Test
    fun `a name nothing answers to is not an error`() {
        assertNull(TipLibrary().find("nothing"))
        assertNull(TipLibrary().find(null))
    }

    // ---- the brush -----------------------------------------------------------

    @Test
    fun `a tip survives the round trip through text`() {
        val brush = Brush()
        brush.tip = "charcoal-stub"
        val back = BrushCodec.decode(BrushCodec.encode(brush))!!
        assertEquals("charcoal-stub", back.tip)
    }

    @Test
    fun `a brush with no tip writes no line about one`() {
        assertTrue("tip " !in BrushCodec.encode(Brush()))
        assertNull(BrushCodec.decode(BrushCodec.encode(Brush()))!!.tip)
    }

    @Test
    fun `picking a brush brings its tip with it`() {
        val from = Brush()
        from.tip = "fan"
        val to = Brush()
        to.tip = "stub"
        adoptBrush(from, to)
        assertEquals("fan", to.tip)
    }
}
