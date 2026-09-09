package be.thalos.artiest.engine.brush

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrainFieldTest {

    private fun tile(spec: GrainSpec, n: Int = 64) = GrainField.tile(spec, n)

    private fun at(t: ByteArray, n: Int, x: Int, y: Int) = t[y * n + x].toInt() and 0xFF

    /**
     * Strength 0 must be *exactly* nothing, not nearly nothing. It is the
     * default, so every brush that has not asked for texture goes through this
     * path, and a field of 254s would quietly dim every stroke in the app.
     */
    @Test
    fun `strength zero is a field of ones`() {
        val t = tile(GrainSpec(strength = 0f))
        assertTrue(t.all { (it.toInt() and 0xFF) == 255 }, "strength 0 changed the alpha")
        assertFalse(GrainSpec(strength = 0f).isActive)
    }

    /**
     * The property that lets one small bitmap cover the page: opposite edges
     * have to meet, or the repeat shows as a grid of seams.
     */
    @Test
    fun `the tile wraps in both axes`() {
        val n = 64
        val t = tile(GrainSpec(strength = 1f), n)
        // A seam would make the wrap-around neighbours much less alike than
        // ordinary neighbours are. Compare the two directly.
        var seam = 0.0
        var inner = 0.0
        for (i in 0 until n) {
            seam += abs(at(t, n, 0, i) - at(t, n, n - 1, i)).toDouble()
            seam += abs(at(t, n, i, 0) - at(t, n, i, n - 1)).toDouble()
            inner += abs(at(t, n, 1, i) - at(t, n, 2, i)).toDouble()
            inner += abs(at(t, n, i, 1) - at(t, n, i, 2)).toDouble()
        }
        assertTrue(
            seam <= inner * 2.0,
            "the wrap-around edges differ by $seam against $inner inside: that is a seam",
        )
    }

    @Test
    fun `a stronger grain spans a wider range`() {
        val n = 64
        val weak = tile(GrainSpec(strength = 0.2f), n)
        val strong = tile(GrainSpec(strength = 1f), n)
        fun spread(t: ByteArray): Int {
            val v = t.map { it.toInt() and 0xFF }
            return v.max() - v.min()
        }
        assertTrue(
            spread(strong) > spread(weak),
            "strong spread ${spread(strong)} was not wider than weak ${spread(weak)}",
        )
    }

    /**
     * The cutoffs are what make it a *tooth* rather than fog: paper either
     * catches the graphite or it does not. A narrow window should push most
     * pixels to one end or the other.
     */
    @Test
    fun `a narrow cutoff window polarises the field`() {
        val n = 64
        val soft = tile(GrainSpec(strength = 1f, cutoffLow = 0f, cutoffHigh = 1f), n)
        val hard = tile(GrainSpec(strength = 1f, cutoffLow = 0.45f, cutoffHigh = 0.55f), n)
        fun extremes(t: ByteArray) = t.count {
            val v = it.toInt() and 0xFF
            v < 24 || v > 231
        }
        assertTrue(
            extremes(hard) > extremes(soft),
            "hard had ${extremes(hard)} extreme pixels, soft ${extremes(soft)}",
        )
    }

    @Test
    fun `the same seed gives the same field and a different one does not`() {
        assertTrue(
            tile(GrainSpec(strength = 1f, seed = 5)).contentEquals(
                tile(GrainSpec(strength = 1f, seed = 5)),
            ),
        )
        assertFalse(
            tile(GrainSpec(strength = 1f, seed = 5)).contentEquals(
                tile(GrainSpec(strength = 1f, seed = 6)),
            ),
        )
    }

    @Test
    fun `the field is neither flat nor binary`() {
        val t = tile(GrainSpec(strength = 1f))
        val distinct = t.map { it.toInt() and 0xFF }.toSet()
        assertTrue(distinct.size > 32, "only ${distinct.size} distinct levels: that is not a grain")
    }

    @Test
    fun `an impossible spec is refused`() {
        assertFailsWith<IllegalArgumentException> { GrainSpec(scaleDocPx = 0f) }
        assertFailsWith<IllegalArgumentException> { GrainSpec(strength = 2f) }
        assertFailsWith<IllegalArgumentException> { GrainSpec(cutoffLow = 0.8f, cutoffHigh = 0.2f) }
        assertFailsWith<IllegalArgumentException> { GrainField.tile(GrainSpec(strength = 1f), 100) }
        assertFailsWith<IllegalArgumentException> { GrainField.tile(GrainSpec(strength = 1f), 4) }
    }

    @Test
    fun `generating a tile is cheap enough to do on a slider drag`() {
        val t0 = System.nanoTime()
        repeat(10) { GrainField.tile(GrainSpec(strength = 1f, seed = it)) }
        val msEach = (System.nanoTime() - t0) / 1e6 / 10
        assertTrue(msEach < 25.0, "a 128px tile took $msEach ms")
    }

    /**
     * The complaint that produced the retune, as a test: "graphite grains are
     * very small in the real world; in our drawing app they are way too big".
     *
     * Measured as the average run of same-signed pixels along a row — the
     * width of a feature. At the default scale a feature has to be a couple of
     * document pixels, not a dozen.
     */
    @Test
    fun `a feature is a couple of pixels across, not a dozen`() {
        // The real tile size, not the small one the other tests use: the
        // feature size is cells-per-tile against document-pixels-per-tile, so
        // measuring a 64 px tile would report a feature four times too coarse
        // and fail an implementation that is correct.
        val n = GrainField.DEFAULT_TILE
        val spec = GrainSpec(strength = 1f)
        val t = tile(spec, n)
        val docPxPerTilePx = spec.scaleDocPx / n
        var runs = 0
        var total = 0
        for (y in 0 until n) {
            val mean = (0 until n).sumOf { at(t, n, it, y) } / n
            var run = 0
            var sign = 0
            for (x in 0 until n) {
                val sx = if (at(t, n, x, y) >= mean) 1 else -1
                if (sx == sign) {
                    run++
                } else {
                    if (run > 0) { runs++; total += run }
                    sign = sx; run = 1
                }
            }
            if (run > 0) { runs++; total += run }
        }
        val featureDocPx = (total.toFloat() / runs) * docPxPerTilePx
        assertTrue(
            featureDocPx < 6f,
            "the average feature is ${"%.1f".format(featureDocPx)} document pixels across",
        )
    }

    /**
     * A fatter pencil leaves a wider mark with the same grain, because the
     * grain belongs to the paper. Nothing in this file reads the brush's size,
     * and this is the assertion that keeps it that way.
     */
    @Test
    fun `the grain does not depend on the brush at all`() {
        // GrainSpec has no size, hardness or pressure field, so there is
        // nothing a brush could pass in even by accident; what this pins is
        // that the *same* spec is the same field every time, which is what
        // makes the tile cacheable across brush changes.
        val a = tile(GrainSpec(strength = 0.5f, scaleDocPx = 256f))
        val b = tile(GrainSpec(strength = 0.5f, scaleDocPx = 256f))
        assertTrue(a.contentEquals(b))
        // Only its own scale moves it, and that is a property of the paper.
        assertFalse(
            GrainSpec(strength = 0.5f, scaleDocPx = 256f) ==
                GrainSpec(strength = 0.5f, scaleDocPx = 120f),
        )
    }
}
