package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.ink.MutableBounds
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one test that ties `:engine`'s `Bounds` to a real rasterizer.
 *
 * `Bounds` derives, from the half-open pixel convention, that
 * `[floor(c - r), ceil(c + r))` is the *exact* set of pixels an antialiased
 * dab of centre c and radius r can touch — and therefore that padding a
 * layer-raster rectangle "for antialiasing" is superstition. That is a claim
 * about Skia, and `:engine` cannot test it: it compiles against no android.jar,
 * which is the whole reason it is a separate module. So it is tested here, and
 * only here.
 *
 * The failure it exists to catch is one pixel of stale ink along the edge of
 * every redrawn region, which on the tablet is diagnosed by squinting.
 *
 * `@GraphicsMode(NATIVE)` with `@Config(sdk = [34])` is load-bearing and not
 * decoration: Robolectric's default `ShadowLegacyCanvas` *records* draw calls
 * instead of rasterizing them, so every `getPixel` returns 0 and a test of this
 * shape passes because nothing was ever drawn. The partial-coverage assertion
 * at the end is the tripwire for that — only a real rasterizer produces an
 * alpha strictly between 0 and 255 — so a Robolectric upgrade that drops back
 * to the legacy shadows fails here instead of going quietly green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DabFootprintTest {

    private val size = 96
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val pixels = IntArray(size * size)

    // :spike's dab paint, exactly: Paint(Paint.ANTI_ALIAS_FLAG), filled.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /** Stamps the dabs, then returns every pixel with non-zero alpha. */
    private fun paintedPixels(dabs: List<Triple<Float, Float, Float>>): List<Pair<Int, Int>> {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for ((x, y, r) in dabs) canvas.drawCircle(x, y, r, paint)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val hit = ArrayList<Pair<Int, Int>>()
        for (y in 0 until size) {
            for (x in 0 until size) if (pixels[y * size + x] ushr 24 != 0) hit.add(x to y)
        }
        return hit
    }

    private fun boundsRectOf(dabs: List<Triple<Float, Float, Float>>): IntArray {
        val acc = MutableBounds()
        for ((x, y, r) in dabs) acc.add(x, y, r)
        val out = IntArray(4)
        assertTrue(acc.toPixelRect(out, size, size), "nothing to redraw for $dabs")
        return out
    }

    /**
     * The claim, swept: over 600 randomized dabs, no pixel Skia gives non-zero
     * alpha falls outside the rectangle `Bounds` reports. The centres are
     * fractional and the radii run from sub-pixel to 15 px, because an
     * outward-rounding bug hides on integral centres.
     */
    @Test
    fun `no antialiased dab paints outside the rectangle Bounds reports`() {
        val rnd = Random(4242L)
        var exact = 0
        repeat(600) {
            val cx = 20f + rnd.nextFloat() * 56f
            val cy = 20f + rnd.nextFloat() * 56f
            val r = 0.2f + rnd.nextFloat() * 15f
            val dab = listOf(Triple(cx, cy, r))
            val (l, t, right, bottom) = boundsRectOf(dab).toList()
            val hit = paintedPixels(dab)
            assertTrue(hit.isNotEmpty(), "dab ($cx, $cy, r=$r) painted nothing")
            for ((x, y) in hit) {
                assertTrue(
                    x >= l && x < right && y >= t && y < bottom,
                    "dab ($cx, $cy, r=$r): Skia inked ($x, $y), outside Bounds' [$l, $t, $right, $bottom)",
                )
            }
            val hl = hit.minOf { it.first }; val hr = hit.maxOf { it.first } + 1
            val ht = hit.minOf { it.second }; val hb = hit.maxOf { it.second } + 1
            if (hl == l && ht == t && hr == right && hb == bottom) exact++
        }
        // The rectangle is tight, not merely a safe over-estimate: it matches
        // the inked extent on both axes for most dabs, and is at most one row
        // or column larger on the rest. If this collapses toward 0 someone has
        // started padding the layer-raster path.
        assertTrue(exact > 300, "only $exact of 600 dabs had a tight rectangle")
    }

    /** The same over a whole stroke, which is what actually gets committed. */
    @Test
    fun `no stroke paints outside its accumulated bounds`() {
        val rnd = Random(31337L)
        repeat(60) {
            val n = 2 + rnd.nextInt(20)
            val dabs = (0 until n).map {
                Triple(24f + rnd.nextFloat() * 48f, 24f + rnd.nextFloat() * 48f, 0.5f + rnd.nextFloat() * 8f)
            }
            val (l, t, right, bottom) = boundsRectOf(dabs).toList()
            for ((x, y) in paintedPixels(dabs)) {
                assertTrue(
                    x >= l && x < right && y >= t && y < bottom,
                    "stroke of $n dabs: Skia inked ($x, $y), outside Bounds' [$l, $t, $right, $bottom)",
                )
            }
        }
    }

    /**
     * The rasterizer is real. Without this the two tests above would pass on
     * ShadowLegacyCanvas, where nothing is ever inked and "no pixel outside the
     * bounds" is vacuously true.
     */
    @Test
    fun `the rasterizer produces genuine partial coverage`() {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawCircle(48f, 48f, 7f, paint)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val alphas = pixels.map { it ushr 24 }.toSet()
        assertTrue(255 in alphas, "no fully covered pixel: the canvas did not rasterize")
        assertTrue(0 in alphas, "no untouched pixel")
        assertTrue(
            alphas.any { it in 1..254 },
            "no partial coverage: this is ShadowLegacyCanvas, not Skia. Alphas seen: $alphas",
        )
        assertEquals(Color.BLACK, pixels[48 * size + 48], "the dab centre is opaque black")
    }

    // ---- Bn2: where a dab may be snapped to the pixel grid ------------------

    /**
     * `DabRasterizer.SNAP_ABOVE_PX` and `SNAP_SOFT_PX`, found rather than
     * guessed.
     *
     * Snapping a dab's blit to a whole pixel buys 23.8x on the host and 7.9x on
     * the tablet, and it costs up to half a pixel of placement on every dab it
     * applies to. `docs/big-nib-plan.md` Bn2 asks for the rule to be measured
     * and pinned; this is the pin, and it is in this file because this is where
     * a claim about what Skia actually inks belongs.
     *
     * ## What is drawn
     *
     * A **slow shallow diagonal** with the rim under test. Shallow, because a
     * stroke at 45 degrees rounds consistently along its length while one that
     * climbs a pixel in sixty rounds *differently* from dab to dab — and that
     * alternation is the ripple. The number that matters is the count of rim
     * pixels that move by more than an eighth of the channel, per thousand
     * document pixels of stroke; a mean over the whole stroke is diluted by its
     * area, so a large nib would flatter itself simply by being large.
     *
     * ## What it found
     *
     * | diameter | hardness 1.0 | hardness 0.4 |
     * |---|---|---|
     * | 64 | 1367 | **0** |
     * | 128 | 1459 | **0** |
     * | 300 | 1570 | **0** |
     * | 600 | 147 | **0** |
     *
     * **A hard rim ripples at every size and a soft rim never does.** The plan
     * expected a size threshold; there is not one. Size survives as a second
     * condition only because dabs a pixel apart bead when they round onto the
     * same pixel, which is a different defect that softness does not fix.
     */
    private val snapW = 1200
    private val snapH = 400

    private fun snapNib(diameter: Float, hard: Float): Brush = Brush().apply {
        sizeMin = diameter
        sizeMax = diameter
        hardness = hard
        spacing = 0.125f
        stabilization = 0f
        onsetMillis = 0f
        flow = 1f
        flowOption.min = 1f
    }

    /** 900 document pixels long, climbing one pixel in sixty. */
    private fun snapDiagonal(brush: Brush): Stroke {
        val b = StrokeBuilder(brush)
        b.begin(Color.BLACK)
        for (i in 0 until 300) {
            val nanos = 1_000_000_000L + i * 3_108_000L
            b.addTilt(0f, 0f, nanos)
            b.add(60f + i * 3f, 200f + i * 0.05f, 1f, nanos)
        }
        return b.end()
    }

    private fun snapPaint(diameter: Float, hard: Float, snap: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(snapW, snapH, Bitmap.Config.ARGB_8888)
        val brush = snapNib(diameter, hard)
        DabRasterizer(snapW, snapH, StampCache()).also {
            it.mode = DabRasterizer.Mode.STAMP
            it.hardness = brush.hardness
            it.snapLargeDabs = snap
        }.drawDry(Canvas(bmp), snapDiagonal(brush))
        return bmp
    }

    /** `(mean |da| over inked pixels, jumpy pixels per 1000 of stroke, worst)`. */
    private fun snapDrift(diameter: Float, hard: Float): Triple<Double, Double, Int> {
        val snapped = snapPaint(diameter, hard, snap = true)
        val loose = snapPaint(diameter, hard, snap = false)
        var sum = 0L
        var worst = 0
        var inked = 0L
        var jumped = 0L
        for (y in 0 until snapH) {
            for (x in 0 until snapW) {
                val a = Color.alpha(snapped.getPixel(x, y))
                val b = Color.alpha(loose.getPixel(x, y))
                if (a == 0 && b == 0) continue
                val d = abs(a - b)
                sum += d
                inked++
                if (d > 32) jumped++
                if (d > worst) worst = d
            }
        }
        snapped.recycle()
        loose.recycle()
        return Triple(
            if (inked == 0L) 0.0 else sum.toDouble() / inked,
            jumped * 1000.0 / 900.0,
            worst,
        )
    }

    @Test
    fun `a dab is snapped only where the rim can swallow half a pixel`() {
        println("diam  hard   mean |da|   jumpy px per 1000   worst")
        val readings = LinkedHashMap<Pair<Int, Float>, Triple<Double, Double, Int>>()
        for (hard in floatArrayOf(1f, 0.4f)) {
            for (d in intArrayOf(16, 32, 64, 128, 300, 600)) {
                val r = snapDrift(d.toFloat(), hard)
                readings[d to hard] = r
                println("%4d  %4.1f %11.3f %19.1f %7d".format(d, hard, r.first, r.second, r.third))
            }
        }

        // Nothing below the size gate is snapped, whatever its rim. This half
        // proves the rule is gated rather than merely cheap.
        for (hard in floatArrayOf(1f, 0.4f)) {
            for (d in intArrayOf(16, 32)) {
                assertTrue(readings[d to hard]!!.first == 0.0, "a $d px dab was snapped")
            }
        }

        // A hard rim is never snapped, at any size — the finding that chose the
        // rule. Shipping on diameter alone would have put a visible staircase
        // on every large hard nib.
        for (d in intArrayOf(64, 128, 300, 600)) {
            assertTrue(readings[d to 1f]!!.first == 0.0, "a $d px hard-rimmed dab was snapped")
        }

        // And where it is applied there is no ripple at all to count, with the
        // whole-stroke drift a fraction of one level of 255.
        for (d in intArrayOf(64, 128, 300, 600)) {
            val (mean, jumpy, worst) = readings[d to 0.4f]!!
            assertTrue(mean > 0.0, "a $d px soft dab was not snapped")
            assertTrue(mean < 2.0, "a $d px soft dab drifted $mean of 255")
            assertTrue(jumpy == 0.0, "a $d px soft dab moved $jumpy rim pixels per 1000")
            assertTrue(worst <= 16, "a $d px soft dab moved one pixel by $worst")
        }
    }
}
