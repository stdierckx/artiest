package be.thalos.artiest.engine.brush

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rasterized dab: one byte of coverage per pixel, and nothing else.
 *
 * **Alpha only, and no colour anywhere in this type.** The colour is applied at
 * blit time by a colour filter, which is what makes the cache worth having: one
 * mask serves every colour, and a palette change costs nothing. It is also what
 * keeps this in `:engine` — a coloured dab would want a `Bitmap`, and `:engine`
 * has no Android on its classpath by construction.
 *
 * Alpha is stored unsigned in a `ByteArray`; read it through [alphaAt] rather
 * than indexing, because Kotlin's `Byte` is signed and `mask.alpha[i].toInt()`
 * is negative for everything above half opacity — a bug that looks like a
 * hollow dab.
 */
class AlphaMask(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
    /** The quantised spec this was built for, not the one that asked for it. */
    val spec: MaskSpec,
) {

    init {
        require(width > 0 && height > 0) { "mask was ${width}x$height" }
        require(alpha.size == width * height) {
            "alpha is ${alpha.size} for a ${width}x$height mask"
        }
    }

    /** Bytes held, for the cache's budget. */
    val byteCount: Int get() = alpha.size

    /**
     * A rasterizer's uploaded form of this mask, if it has one.
     *
     * `Any?` because `:engine` has no Android on its classpath and must not
     * acquire one; the app stores a `Bitmap` here and casts it back. It lives
     * on the mask rather than in a second map keyed the same way so that the
     * two cannot disagree about lifetime — when [MaskCache] evicts a mask, the
     * uploaded bitmap goes with it, with no second eviction policy to keep in
     * step. Getting that wrong is a leak that only shows up after a long
     * drawing session, which is the worst kind to look for.
     */
    var attachment: Any? = null

    /**
     * Where the dab's centre sits inside the bitmap, in pixels from its
     * top-left. Always the exact middle: the mask is built with an odd or even
     * extent as the diameter demands and the centre is `width / 2f`, so a
     * caller blits at `x - hotspotX`.
     */
    val hotspotX: Float get() = width * 0.5f
    val hotspotY: Float get() = height * 0.5f

    /** Coverage at [x],[y] as 0..255. Out of bounds is 0, not an exception. */
    fun alphaAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return alpha[y * width + x].toInt() and 0xFF
    }

    override fun toString(): String = "AlphaMask(${width}x$height, $spec)"
}

/**
 * Builds [AlphaMask]es from a [MaskSpec], procedurally.
 *
 * Procedural and not a shipped image, which is a licensing consequence as much
 * as a technical one: the project is Apache-2.0 and reimplements from
 * description rather than borrowing assets, so the dab shapes have to be
 * generated. W8's grain is the one place an image is wanted, and it has its own
 * note in the plan about being photographed or generated rather than taken.
 *
 * **Supersampled rather than analytically antialiased.** An exact coverage
 * formula for a rotated ellipse is fiddly and easy to get subtly wrong at the
 * poles; averaging [SUB]x[SUB] subsamples is obviously correct, works for every
 * shape without a special case, and costs nothing that matters because the
 * result is cached. A 24 px dab is 26x26x9 = 6084 evaluations, once, and then
 * reused for every dab in its tolerance bucket.
 */
object MaskGenerator {

    /**
     * Subsamples per axis. 4 gives 16 levels of edge coverage.
     *
     * 3 was tried first and is visibly too coarse in one specific way: at 9
     * levels a rim whose geometry happens to align with the pixel grid comes
     * out fully binary. A 24 px dab centred in a 26 px mask has its edge at
     * exactly x=1 and x=25, so every subsample of every pixel on the centre row
     * falls cleanly inside or outside and the row has no partial coverage at
     * all. More levels do not remove that alignment, but they make it rare
     * enough not to matter, and the cost is paid once per bucket.
     */
    const val SUB: Int = 4

    /** One pixel of margin each side, so the antialiased rim is never clipped. */
    const val PAD: Int = 1

    fun generate(spec: MaskSpec): AlphaMask {
        val extent = ceil(spec.diameter).toInt() + 2 * PAD
        val w = if (extent < 1) 1 else extent
        val out = ByteArray(w * w)

        val a = spec.diameter * 0.5f                 // major semi-axis, px
        val b = a * spec.aspect                      // minor semi-axis, px
        val cx = w * 0.5f
        val cy = w * 0.5f
        val cosR = cos(spec.rotationRad)
        val sinR = sin(spec.rotationRad)
        val inner = spec.hardness.coerceIn(0f, 1f)   // solid out to this fraction
        val band = 1f - inner
        val step = 1f / SUB
        val half = step * 0.5f
        val norm = 1f / (SUB * SUB)

        var i = 0
        for (py in 0 until w) {
            for (px in 0 until w) {
                var acc = 0f
                for (sy in 0 until SUB) {
                    val fy = py + sy * step + half - cy
                    for (sx in 0 until SUB) {
                        val fx = px + sx * step + half - cx
                        // Into the ellipse's own frame, then to a 0..1 radius.
                        val u = fx * cosR + fy * sinR
                        val v = -fx * sinR + fy * cosR
                        val nu = u / a
                        val nv = if (b > 0f) v / b else Float.MAX_VALUE
                        val d = sqrt(nu * nu + nv * nv)
                        acc += when {
                            d >= 1f -> 0f
                            band <= 0f -> 1f
                            d <= inner -> 1f
                            else -> {
                                // Smoothstep across the soft band, so the
                                // falloff has no visible ring where a linear
                                // ramp's derivative jumps.
                                val t = 1f - (d - inner) / band
                                t * t * (3f - 2f * t)
                            }
                        }
                    }
                }
                val v = (acc * norm * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i++] = v.toByte()
            }
        }
        return AlphaMask(w, w, out, spec)
    }
}
