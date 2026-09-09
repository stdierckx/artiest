package be.thalos.artiest.engine.brush

import kotlin.math.abs
import kotlin.math.floor

/**
 * The paper grain, generated rather than photographed or borrowed.
 *
 * **Generated is a licence decision before it is a technical one.** artiest is
 * Apache-2.0 and its rule for prior art is read-and-reimplement: technique may
 * be learned from descriptions, and no source, assets or brush data are
 * vendored. A grain image lifted from another program is exactly the asset that
 * rule forbids, and a photographed one still has to come from somewhere and be
 * licensed. Value noise costs a few dozen lines, ships as arithmetic, and can
 * be tuned by a slider instead of by finding a different photograph.
 *
 * **What it has to do to earn its place**, which is the phase's whole thesis:
 * graphite does not lay down evenly. It catches on the tooth of the paper and
 * skips the pits, so a light pass is a scatter of dark specks rather than a
 * uniform grey, and the edges of a stroke are ragged rather than clean. Opacity
 * alone cannot make that — it makes a uniformly fainter stroke, which reads as
 * ink at low alpha and not as pencil. This is the other half of the bar.
 *
 * The output is a **tileable** alpha field: [tile] wraps in both axes, so the
 * app can repeat one small bitmap across the page instead of holding a
 * document-sized texture, and the seams do not show.
 */
object GrainField {

    /**
     * A tileable grain tile, [size] square, as unsigned alpha bytes.
     *
     * [size] must be a multiple of every octave's lattice period for the wrap
     * to be seamless, which is why it is required to be a power of two and the
     * lattices are derived from it by halving.
     */
    fun tile(spec: GrainSpec, size: Int = DEFAULT_TILE): ByteArray {
        require(size >= 8 && (size and (size - 1)) == 0) { "tile size must be a power of two, was $size" }
        val out = ByteArray(size * size)
        // Three octaves, weighted toward the *finest*, which is the correction
        // that made this read as graphite rather than as noise.
        //
        // The first version put 0.6 of the weight on a 32-cell lattice, so at
        // any sensible tile scale the dominant feature was several document
        // pixels across. Real graphite tooth is a fraction of a millimetre —
        // on this page, one or two pixels — and a speck you can pick out
        // individually reads as dirt on the paper, not as pencil. The fine
        // octave now leads and the coarse ones only modulate it, which is what
        // gives the patchiness a real page has without making the patches
        // visible as objects.
        val lattices = intArrayOf(size / 2, size / 4, size / 8)
        val weights = floatArrayOf(0.55f, 0.30f, 0.15f)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                var v = 0f
                for (o in lattices.indices) {
                    val n = maxOf(lattices[o], 2)
                    v += weights[o] * valueNoise(
                        x.toFloat() / size * n,
                        y.toFloat() / size * n,
                        n,
                        spec.seed + o * 7919,
                    )
                }
                out[i++] = (shape(v, spec) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * Contrast and depth, applied after the noise and before the byte.
     *
     * [GrainSpec.cutoffLow] and [cutoffHigh] window the noise — everything
     * below the low end is fully dark and above the high end fully clear —
     * which is what turns a soft cloud into a *tooth*: paper either catches the
     * graphite or it does not, and a smooth gradient reads as fog. [strength]
     * then blends the whole thing back toward 1, so a strength of 0 is no
     * texture at all and the brush is exactly what it was without this.
     */
    private fun shape(noise: Float, spec: GrainSpec): Float {
        val lo = spec.cutoffLow
        val hi = spec.cutoffHigh
        val t = if (hi - lo <= 1e-6f) {
            if (noise >= hi) 1f else 0f
        } else {
            ((noise - lo) / (hi - lo)).coerceIn(0f, 1f)
        }
        // Smoothstep so the surviving edge is not a hard threshold, which
        // aliases badly when the tile is scaled up.
        val s = t * t * (3f - 2f * t)
        return 1f - spec.strength * (1f - s)
    }

    /**
     * Value noise on an [period]-periodic lattice, bilinear with a smoothstep
     * fade. Periodic in both axes, which is what makes [tile] wrap.
     */
    private fun valueNoise(x: Float, y: Float, period: Int, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = lattice(x0, y0, period, seed)
        val b = lattice(x0 + 1, y0, period, seed)
        val c = lattice(x0, y0 + 1, period, seed)
        val d = lattice(x0 + 1, y0 + 1, period, seed)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    /** A stable pseudo-random 0..1 for a lattice point, wrapped to [period]. */
    private fun lattice(x: Int, y: Int, period: Int, seed: Int): Float {
        val xi = Math.floorMod(x, period)
        val yi = Math.floorMod(y, period)
        var h = xi * 374761393 + yi * 668265263 + seed * 1274126177
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return abs(h % 65536) / 65535f
    }

    /**
     * 256 px, raised from 128 together with the octave weights above.
     *
     * The two go together: a finer dominant octave needs more tile pixels to
     * carry it, or the noise lands at the tile's own resolution and aliases.
     * 256 with a 128-cell fine lattice puts two tile pixels in each cell, and
     * at the default scale that is about two document pixels a cell — paper
     * tooth rather than gravel. Generation is a few milliseconds, once per
     * grain setting, and the repeat is 256 document pixels, which is far enough
     * apart not to read as a pattern.
     */
    const val DEFAULT_TILE: Int = 256
}

/**
 * The grain's parameters. See [GrainField].
 *
 * A separate type from [Brush] so the texture can be cached on its own key: the
 * tile is expensive relative to a dab and depends on none of the brush's
 * dynamics.
 */
data class GrainSpec(
    /**
     * How many document pixels one tile covers. Larger is coarser paper.
     *
     * **Independent of dab size, and that is the point rather than an
     * incidental property.** In the world a fatter pencil leaves a wider mark
     * with grain of exactly the same size, because the grain belongs to the
     * paper and the paper has not changed. A texture that scaled with the nib
     * would make a big brush look like a close-up photograph of a small one.
     * Nothing here reads the brush's size, so the size slider cannot move it.
     *
     * It is also why the grain is anchored to the document rather than to the
     * dab: grain locked to the dab swims along with the stroke and reads as a
     * dirty brush rather than as a rough surface.
     */
    val scaleDocPx: Float = 256f,
    /** 0 is no texture and the brush is exactly what it was. 1 is full depth. */
    val strength: Float = 0f,
    /** Noise below this is fully dark. */
    val cutoffLow: Float = 0.35f,
    /** Noise above this is fully clear. */
    val cutoffHigh: Float = 0.65f,
    val seed: Int = 1,
) {
    init {
        require(scaleDocPx > 0f && scaleDocPx.isFinite()) { "scale was $scaleDocPx" }
        require(strength in 0f..1f) { "strength was $strength" }
        require(cutoffLow.isFinite() && cutoffHigh.isFinite()) { "cutoffs were $cutoffLow..$cutoffHigh" }
        require(cutoffHigh >= cutoffLow) { "cutoffHigh $cutoffHigh below cutoffLow $cutoffLow" }
    }

    /** Whether this spec does anything at all. */
    val isActive: Boolean get() = strength > 0f
}
