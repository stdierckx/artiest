package be.thalos.artiest.engine.ink

import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.ulp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial companion to [BoundsTest]. Everything here is either randomized
 * or a characterization of a case `Bounds` accepts without saying so — the two
 * kinds of test a hand-written example suite does not reach.
 *
 * `BoundsTest` proves the design on chosen numbers. This one tries to find a
 * number it was not chosen for.
 */
class BoundsAdversarialTest {

    private val docW = 2160
    private val docH = 3300
    private val rect = IntArray(4)

    // ---------------------------------------------------------------- radius

    /**
     * The stale-rim bug, stated at the exact arithmetic the plan's reviewer
     * asked for. Centre-only accumulation gives (100, 100, 100, 100) and one
     * radius of ink survives every redraw along every edge.
     */
    @Test
    fun `a dab contributes its painted disc and the union covers both discs`() {
        val b = MutableBounds()
        b.add(100f, 100f, 7f)
        assertEquals(93f, b.left); assertEquals(93f, b.top)
        assertEquals(107f, b.right); assertEquals(107f, b.bottom)

        b.add(200f, 100f, 3f)
        // The second disc spans 197..203 in x and 97..103 in y, so only the
        // right edge moves: a union that took the max of the *centres* would
        // report 200 here, and a union that forgot the first dab would report
        // a left edge of 197.
        assertEquals(Bounds.of(93f, 93f, 203f, 107f), b.snapshot())
    }

    /**
     * The same property, randomized, and stated as containment against an
     * expectation computed *independently* of the type under test: each dab's
     * disc is `(x - r, y - r) .. (x + r, y + r)` by definition, and the
     * accumulated rectangle must cover every one of them.
     *
     * The independence matters. An earlier draft of this test derived the
     * per-dab rectangle by feeding the dab to a fresh `MutableBounds`, and a
     * mutant with the radius removed from `add` passed it — both sides shrank
     * together. Written this way the same mutant fails.
     */
    @Test
    fun `the accumulated rectangle contains every dab it was given`() {
        val rnd = Random(1_000_003L)
        repeat(300) {
            val n = 1 + rnd.nextInt(40)
            val dabs = (0 until n).map {
                Triple(rnd.nextFloat() * 2600f - 200f, rnd.nextFloat() * 3700f - 200f, rnd.nextFloat() * 50f)
            }
            val all = MutableBounds().apply { dabs.forEach { add(it.first, it.second, it.third) } }.snapshot()
            for ((x, y, r) in dabs) {
                assertTrue(
                    all.left <= x - r && all.top <= y - r &&
                        all.right >= x + r && all.bottom >= y + r,
                    "dab ($x, $y, r=$r) spans (${x - r}, ${y - r})..(${x + r}, ${y + r}), " +
                        "which escapes the accumulated $all",
                )
            }
        }
    }

    // ------------------------------------------------------- order invariance

    /**
     * min/max over floats are exact operations — each returns one of its two
     * arguments unchanged, so there is no rounding to reorder and the result is
     * the same *bits* whatever order the dabs arrive in. Addition would not be:
     * `(a + b) + c != a + (b + c)` in general, which is why a bounds must never
     * be accumulated as a running mean and a half-extent.
     *
     * Asserted on `toBits`, not on `==`, because `==` on floats would let a
     * -0.0f/0.0f difference through and that is precisely the kind of
     * order-dependent difference this is looking for.
     */
    @Test
    fun `two hundred dabs give a bit-identical bounds in any order`() {
        val rnd = Random(20_260_908L)
        val dabs = (0 until 200).map {
            Triple(rnd.nextFloat() * 3000f - 400f, rnd.nextFloat() * 4000f - 400f, rnd.nextFloat() * 40f)
        }
        fun bitsOf(order: List<Triple<Float, Float, Float>>): List<Int> {
            val b = MutableBounds().apply { order.forEach { add(it.first, it.second, it.third) } }.snapshot()
            return listOf(b.left.toBits(), b.top.toBits(), b.right.toBits(), b.bottom.toBits())
        }
        val forward = bitsOf(dabs)
        assertEquals(forward, bitsOf(dabs.reversed()), "reversed order")
        assertEquals(
            forward,
            bitsOf(dabs.filterIndexed { i, _ -> i % 2 == 0 } + dabs.filterIndexed { i, _ -> i % 2 == 1 }),
            "interleaved order",
        )
        repeat(20) { seed -> assertEquals(forward, bitsOf(dabs.shuffled(Random(seed.toLong()))), "shuffle $seed") }
    }

    // ------------------------------------------------------ integer rounding

    /**
     * The rounding, differentially against an oracle written from the rule
     * rather than from the implementation, over randomized rectangles that
     * straddle every page edge. A one-pixel error here is a one-pixel stale
     * rim, and it is exactly the error that does not show up on chosen values.
     */
    @Test
    fun `the pixel rectangle matches an independent floor-ceil oracle`() {
        val rnd = Random(77L)
        repeat(20_000) {
            val l = rnd.nextFloat() * 2600f - 220f
            val t = rnd.nextFloat() * 3700f - 220f
            val b = Bounds.of(l, t, l + rnd.nextFloat() * 60f, t + rnd.nextFloat() * 60f)
            val pad = if (rnd.nextBoolean()) 0f else Bounds.FILTER_PAD_DOC

            val el = floor(b.left - pad).toInt().coerceIn(0, docW)
            val et = floor(b.top - pad).toInt().coerceIn(0, docH)
            val er = ceil(b.right + pad).toInt().coerceIn(0, docW)
            val eb = ceil(b.bottom + pad).toInt().coerceIn(0, docH)
            val expectVisible = er > el && eb > et

            val stale = intArrayOf(-9, -9, -9, -9)
            assertEquals(expectVisible, b.toPixelRect(stale, docW, docH, pad), "$b pad=$pad")
            if (expectVisible) {
                assertEquals(listOf(el, et, er, eb), stale.toList(), "$b pad=$pad")
            } else {
                // Nothing written, so a caller reusing a scratch array cannot
                // act on a half-overwritten one.
                assertEquals(listOf(-9, -9, -9, -9), stale.toList(), "$b pad=$pad wrote to the out array")
            }
        }
    }

    /**
     * A trap for whoever next writes an "a hair either side" test by hand:
     * `20.000001f` is not a hair above 20, it is exactly `20f.nextUp()`, one
     * whole ulp, because ulp(20f) is 1.9e-6 and the literal rounds. Below 16
     * the ulp is smaller and the decimal literal lands where it looks like it
     * lands. Asserted so the next person reads it instead of rediscovering it.
     */
    @Test
    fun `a decimal literal a hair outside an integer is a whole ulp outside it`() {
        assertEquals(20f.nextUp().toBits(), 20.000001f.toBits())
        assertEquals(10f.nextDown().toBits(), 9.999999f.toBits())
        assertEquals(1.9073486E-6f, 20f.ulp)

        assertTrue(Bounds.of(9.999999f, 9.999999f, 20.000001f, 20.000001f).toPixelRect(rect, docW, docH))
        assertEquals(listOf(9, 9, 21, 21), rect.toList())
    }

    // ------------------------------------------------------------ degenerate

    /**
     * CHARACTERIZATION, not an endorsement. `add` validates its arguments and
     * then does arithmetic that can leave the accumulator holding an infinity
     * anyway: two finite floats near the top of the range sum to +Infinity.
     * `isEmpty` then reads false, `toPixelRect` cheerfully reports the whole
     * page dirty, and the failure only surfaces at `snapshot`, as an
     * `IllegalArgumentException` naming a *bounds* rather than the dab that
     * caused it — which is precisely the "absorbed now, thrown later" failure
     * the validation in `add` exists to prevent.
     *
     * Left as-is rather than fixed because it is unreachable from the pen path
     * — the document is 2160x3300 and a radius is a brush width — and a check
     * on the *result* of the min/max chain would cost four more comparisons per
     * dab to catch nothing. If a future phase ever admits synthesized or
     * imported geometry, this is the hole it comes through.
     */
    @Test
    fun `finite dabs can still overflow the accumulator and the throw lands at snapshot`() {
        val b = MutableBounds()
        b.add(3.0e38f, 0f, 3.0e38f) // both arguments finite, and both accepted
        assertEquals(Float.POSITIVE_INFINITY, b.right)
        assertFalse(b.isEmpty, "an overflowed accumulator does not report itself empty")
        assertTrue(b.toPixelRect(rect, docW, docH), "and it reports the whole page dirty")
        assertEquals(listOf(0, 0, docW, docH), rect.toList())
        val thrown = assertFailsWith<IllegalArgumentException> { b.snapshot() }
        assertTrue(thrown.message!!.contains("not finite"), thrown.message!!)
    }

    /**
     * CHARACTERIZATION of the float-precision floor under this design. A radius
     * smaller than one ulp of the coordinate is swallowed whole: the bounds
     * collapses to a zero-area point and the dab's ink falls outside it.
     *
     * Harmless at the sizes the plan commits to — at the far corner of a
     * 2160x3300 document one ulp is 2.4e-4 px, four decimal digits below the
     * smallest useful radius, and the second assertion pins that margin. It
     * stops being harmless if a later phase grows the document past ~2^23 px on
     * a side or lets an infinite canvas push document coordinates into the
     * millions, at which point the fix is doubles in the accumulator, not a pad.
     */
    @Test
    fun `a radius below one ulp of the coordinate is lost and the document is nowhere near that`() {
        val lost = MutableBounds().apply { add(1e7f, 1e7f, 0.4f) }.snapshot()
        assertEquals(0f, lost.width, "at 1e7 the ulp is 1.0 and a 0.4 radius vanishes")

        val fine = MutableBounds().apply { add(docH.toFloat(), docH.toFloat(), 0.4f) }.snapshot()
        assertEquals(0.8f, fine.width, 1e-3f, "at the far corner of the document the radius survives")
        assertTrue(docH.toFloat().ulp < 1e-3f, "one ulp at the page edge is ${docH.toFloat().ulp}")
    }

    /**
     * CHARACTERIZATION: `Bounds.of` normalizes -0.0f away so equals and
     * hashCode agree, but `MutableBounds`'s four public edges are the raw
     * min/max results and are not normalized, so the two types' public surfaces
     * disagree about the sign of zero. Nothing in `Bounds` is affected —
     * `snapshot` normalizes and `toPixelRect` floors, and floor(-0.0f) is 0 —
     * but W7 must compare or hash a *snapshot*, never the live edges.
     */
    @Test
    fun `a live accumulator edge can be negative zero where a snapshot cannot`() {
        val b = MutableBounds().apply { add(-0.0f, -0.0f, 0f) }
        assertEquals((-0.0f).toBits(), b.left.toBits(), "the raw edge keeps the sign")
        assertEquals(0.0f.toBits(), b.snapshot().left.toBits(), "the snapshot does not")
    }

    /**
     * A dab whose disc lies entirely off the page still reports a dirty
     * rectangle once the filter pad is applied, because the filtered blit does
     * read a texel outside the rectangle it draws. Correct, and stated here
     * because it is the one case where `pad` changes the *return value* and not
     * just the numbers.
     */
    @Test
    fun `the filter pad can pull an off-page rectangle back onto the page`() {
        val off = Bounds.of(-1.5f, 100f, -0.5f, 110f)
        assertFalse(off.toPixelRect(rect, docW, docH))
        assertTrue(off.toPixelRect(rect, docW, docH, pad = Bounds.FILTER_PAD_DOC))
        assertEquals(listOf(0, 99, 1, 111), rect.toList())
    }

    /**
     * CHARACTERIZATION: `Stroke.copyOf`'s buffer-size guard is written as
     * `dabs.size >= dabCount * STRIDE`, and that product overflows Int at
     * 715_827_883 dabs, going negative — so the guard passes for a four-float
     * buffer and the failure arrives from `FloatArray(negative)` instead.
     *
     * Not reachable from a pen stroke, and the throw is still a throw. Recorded
     * because the guard reads as total and is not, and because W7's
     * `StrokeBuilder` is the thing that will one day supply `dabCount` from a
     * computed capacity rather than from a loop counter.
     */
    @Test
    fun `the dab count guard in Stroke overflows rather than rejecting`() {
        assertTrue(715_827_883 * Stroke.STRIDE < 0, "the product is ${715_827_883 * Stroke.STRIDE}")
        assertFailsWith<NegativeArraySizeException> {
            Stroke.copyOf(FloatArray(4), 715_827_883, 0xFF000000.toInt(), true, Bounds.of(0f, 0f, 1f, 1f))
        }
    }

    // ------------------------------------------------------------ allocation

    /**
     * The per-dab path allocates nothing, measured rather than reasoned.
     *
     * The realistic regression it guards is not a new object but a new *string*:
     * `add`'s two `require`s carry message lambdas with `$x`/`$y` in them, and
     * they cost nothing only because `require` is inline and the lambda runs on
     * failure alone. Rewrite either as `require(cond, "msg $x")` — the eager
     * overload — and every dab allocates a StringBuilder and a String. At the
     * plan's sample rate that is garbage on the pen path, which is the one
     * place this project has said it will not have any.
     *
     * `snapshot` is measured too, and is the one allocation the design admits:
     * one `Bounds` per pen-up, and not even that when nothing was accumulated.
     */
    @Suppress("DEPRECATION") // Thread.id; Thread.threadId() is Java 19+ and this module targets 17.
    @Test
    fun `a million dabs allocate nothing and only snapshot allocates`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        if (bean !is com.sun.management.ThreadMXBean || !bean.isThreadAllocatedMemorySupported) {
            println("thread allocation counters unavailable on this JVM; skipped")
            return
        }
        val id = Thread.currentThread().id
        val b = MutableBounds()
        val out = IntArray(4)
        // Warm up so the measurement is of compiled code, not of the interpreter.
        var w = 0
        while (w < 500_000) { b.add(1f + (w % 997), 2f, 3f); b.toPixelRect(out, docW, docH); w++ }

        val a0 = bean.getThreadAllocatedBytes(id)
        var i = 0
        while (i < 1_000_000) { b.add(1f + (i % 997), 2f, 3f); i++ }
        val addBytes = bean.getThreadAllocatedBytes(id) - a0
        // Generous by three orders of magnitude: the measured figure is 0, and
        // the eager-`require` regression this exists for costs ~100 B a call.
        assertTrue(addBytes < 1_000_000, "1e6 add() calls allocated $addBytes bytes")

        val a1 = bean.getThreadAllocatedBytes(id)
        var j = 0
        while (j < 1_000_000) { b.toPixelRect(out, docW, docH); j++ }
        val rectBytes = bean.getThreadAllocatedBytes(id) - a1
        assertTrue(rectBytes < 1_000_000, "1e6 toPixelRect calls allocated $rectBytes bytes")

        val a2 = bean.getThreadAllocatedBytes(id)
        val empty = MutableBounds()
        var k = 0
        var sink = 0f
        while (k < 1_000_000) { sink += empty.snapshot().width; k++ }
        val emptyBytes = bean.getThreadAllocatedBytes(id) - a2
        assertEquals(0f, sink)
        assertTrue(emptyBytes < 1_000_000, "1e6 empty snapshots allocated $emptyBytes bytes")
    }
}
