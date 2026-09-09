package be.thalos.artiest.engine.color

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radians to degrees, and back one line below.
 *
 * Written out rather than reached for in `java.lang.Math`, to keep this module
 * on the Kotlin stdlib alone — the build file's standing instruction, and the
 * one that keeps `:engine` honest about never quietly acquiring a JVM-only
 * dependency that dexes fine and then throws on the tablet.
 */
private const val DEGREES_PER_RADIAN = (180.0 / PI).toFloat()
private const val RADIANS_PER_DEGREE = (PI / 180.0).toFloat()

/** A point on the unit disc, in the wheel's own coordinates. See [ColorDisc]. */
data class DiscPoint(val dx: Float, val dy: Float)

/**
 * The wheel, as geometry: where a hue lives on a disc, and which hue a finger
 * landed on.
 *
 * **Why this is in `:engine` and not next to the composable.** Everything the
 * wheel gets wrong is arithmetic — the puck a sector away from the colour under
 * it, hue running backwards, a drag off the rim snapping to red — and all of it
 * is decidable on the JVM. The composable that remains is a `drawImage`, a
 * `drawCircle` and a pointer handler, which is the part that genuinely needs a
 * screen. Splitting there is the same trade the module boundary was drawn for.
 *
 * ## Coordinates
 *
 * The disc is the unit circle centred on the origin. `dx` runs right, `dy` runs
 * **down**, matching every screen coordinate system this will meet, so a caller
 * converts with `(px - centre) / radius` and nothing else.
 *
 * Hue 0 (red) sits at 3 o'clock and increases anticlockwise, which puts red,
 * yellow, green, cyan, blue, magenta around in that order — the order they come
 * in on a spectrum, and the order every artist's wheel and every other picker
 * uses. The `-dy` in [hueAt] is what buys that against a downward y.
 *
 * Saturation is the radius, clamped at the rim.
 */
object ColorDisc {

    /**
     * The hue at a point, in degrees.
     *
     * [fallbackHue] is returned at the exact centre, and it is not a nicety.
     * The centre is white, where hue is genuinely undefined; `atan2(0, 0)` is
     * defined to be 0, so without this a finger dragged through the middle of
     * the wheel would flick to red on the way past and the puck would jump. The
     * caller passes the hue it already has, and dragging through the centre
     * changes only saturation, which is what the picture promises.
     */
    fun hueAt(dx: Float, dy: Float, fallbackHue: Float): Float {
        if (dx == 0f && dy == 0f) return Hsv.normalizeHue(fallbackHue)
        return Hsv.normalizeHue(atan2(-dy, dx) * DEGREES_PER_RADIAN)
    }

    /**
     * The saturation at a point: the distance from the centre, clamped to 1.
     *
     * Clamped rather than rejected, deliberately. A finger that leaves the disc
     * mid-drag should keep steering the hue at full saturation, the way a
     * slider keeps tracking when your thumb slides off the end of it. Dropping
     * the sample instead makes the puck stick, and the user's next move is to
     * press harder.
     */
    fun saturationAt(dx: Float, dy: Float): Float {
        val r = sqrt(dx * dx + dy * dy)
        return if (r > 1f) 1f else r
    }

    /**
     * The colour a touch at ([dx], [dy]) selects, keeping [value] and falling
     * back to [fallbackHue] at the centre.
     */
    fun sample(dx: Float, dy: Float, value: Float, fallbackHue: Float): Hsv =
        Hsv(
            hue = hueAt(dx, dy, fallbackHue),
            saturation = saturationAt(dx, dy),
            value = value,
        )

    /**
     * Where the puck goes for a colour. The inverse of [sample], and exact:
     * `sample(position(c)) == c` for any `c` whose saturation is in range.
     *
     * [Hsv.value] is not consulted, because the disc does not show value — a
     * dark red and a bright red are the same point under the same puck, and the
     * bar next to the wheel is what tells them apart.
     */
    fun positionOf(hsv: Hsv): DiscPoint {
        val radians = Hsv.normalizeHue(hsv.hue) * RADIANS_PER_DEGREE
        val r = hsv.saturation.coerceIn(0f, 1f)
        return DiscPoint(dx = r * cos(radians), dy = -r * sin(radians))
    }

    /**
     * The wheel as pixels: a [size] x [size] square of packed ARGB, the disc
     * inscribed in it and everything outside transparent.
     *
     * **Everything here is drawn at full value.** Not a simplification — an
     * exactness. HSV's value is a scalar multiple of the RGB triple, so the
     * whole wheel at value `v` is the wheel at value 1 with every channel
     * multiplied by `v`. The renderer gets that for free from a multiply blend
     * and the raster never has to be rebuilt while the value bar is dragged,
     * which is the one interaction that would otherwise regenerate a quarter of
     * a million pixels per frame.
     *
     * The rim is antialiased by coverage in the alpha channel rather than left
     * hard: this is drawn as a bitmap, so there is no path for the rasterizer
     * to smooth, and an aliased circle at this size looks like a mistake.
     *
     * [out] may be supplied to avoid the allocation; it must be `size * size`.
     * The array is returned either way.
     */
    fun raster(size: Int, out: IntArray = IntArray(size * size)): IntArray {
        require(size > 0) { "wheel raster must have a positive size, was $size" }
        require(out.size == size * size) {
            "raster buffer is ${out.size}, need ${size * size} for a ${size}px wheel"
        }

        val radius = size / 2f
        var i = 0
        for (py in 0 until size) {
            // Pixel centres, not corners: half a pixel out here is a wheel
            // whose puck sits half a pixel off its colour at every radius.
            val dy = (py + 0.5f - radius) / radius
            for (px in 0 until size) {
                val dx = (px + 0.5f - radius) / radius
                val r = sqrt(dx * dx + dy * dy)

                // Coverage of one pixel by the disc edge, linearised over the
                // one pixel straddling r == 1.
                val coverage = ((1f - r) * radius + 0.5f).coerceIn(0f, 1f)
                out[i++] = if (coverage <= 0f) {
                    0
                } else {
                    Hsv.pack(
                        hue = hueAt(dx, dy, fallbackHue = 0f),
                        saturation = if (r > 1f) 1f else r,
                        value = 1f,
                        alpha = (coverage * 255f).roundToInt(),
                    )
                }
            }
        }
        return out
    }
}
