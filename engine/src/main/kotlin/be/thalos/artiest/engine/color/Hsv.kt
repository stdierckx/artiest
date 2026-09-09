package be.thalos.artiest.engine.color

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A colour as hue, saturation and value — the model a wheel is a picture of.
 *
 * **Why this type exists rather than passing packed ARGB around.** The app's
 * ink is an ARGB `Int` and stays one; nothing downstream of the picker wants
 * anything else. But ARGB is a lossy container for *what the user is
 * pointing at*. Drag the value bar to zero and every hue packs to
 * `0xFF000000`; convert that back and the wheel's puck jumps to red, because
 * black remembers no hue. The same happens at zero saturation, where every hue
 * is the same grey. So the picker's state is an [Hsv] and ARGB is only ever an
 * output. That is the single design decision in this file, and it is the one
 * that keeps the puck under the finger.
 *
 * Fields are held as given and clamped where it matters ([toArgb], [pack])
 * rather than rejected in a constructor. This type is produced by a finger
 * drag: a `require` here would turn a fingertip a pixel outside the disc into a
 * crash, and the honest fix for out-of-range input at that boundary is
 * saturation, not an exception. [ColorDisc.sample] already returns values in
 * range.
 *
 * @param hue degrees. Canonically `[0, 360)`; [normalizeHue] is applied on the
 *   way out, so 400f and -320f both mean 40f without ever comparing unequal
 *   here — see [equals] caveat: this is a `data class`, so `Hsv(360f, 1f, 1f)`
 *   and `Hsv(0f, 1f, 1f)` are different objects that pack to the same colour.
 * @param saturation `0f` grey to `1f` pure.
 * @param value `0f` black to `1f` full.
 */
data class Hsv(val hue: Float, val saturation: Float, val value: Float) {

    /** The colour as packed ARGB, with [alpha] in `0..255`. */
    fun toArgb(alpha: Int = 0xFF): Int = pack(hue, saturation, value, alpha)

    companion object {

        /** Hue folded into `[0, 360)`, for any finite input including negatives. */
        fun normalizeHue(hue: Float): Float {
            val h = hue % 360f
            return if (h < 0f) h + 360f else h
        }

        /**
         * HSV to packed ARGB, without allocating an [Hsv] to do it.
         *
         * Split out from [toArgb] for one caller: [ColorDisc.raster] runs this
         * once per pixel, a quarter of a million times per wheel, and a
         * short-lived object per pixel there is the kind of garbage that shows
         * up as a stutter on the first frame the picker opens.
         *
         * The conversion is the standard one. `c` is the chroma, `x` the
         * second-largest component, `m` the lift that takes the pair from
         * chroma space up to the requested value.
         */
        fun pack(hue: Float, saturation: Float, value: Float, alpha: Int = 0xFF): Int {
            val s = saturation.coerceIn(0f, 1f)
            val v = value.coerceIn(0f, 1f)
            val sector = normalizeHue(hue) / 60f

            val c = v * s
            val x = c * (1f - abs(sector.mod(2f) - 1f))
            val m = v - c

            var r = 0f
            var g = 0f
            var b = 0f
            when (sector.toInt()) {
                0 -> { r = c; g = x }
                1 -> { r = x; g = c }
                2 -> { g = c; b = x }
                3 -> { g = x; b = c }
                4 -> { r = x; b = c }
                // 5, and 6 only if `sector` lands exactly on 360/60 despite the
                // normalize above. Both are the red-to-magenta sector.
                else -> { r = c; b = x }
            }

            return (alpha.coerceIn(0, 255) shl 24) or
                (byteOf(r + m) shl 16) or
                (byteOf(g + m) shl 8) or
                byteOf(b + m)
        }

        /**
         * Packed ARGB to HSV. Alpha is dropped: this model has no room for it.
         *
         * Use it to seed a picker from a stored colour, once. Do not use it to
         * round-trip picker state every recomposition — see the class KDoc for
         * what that costs.
         */
        fun fromArgb(argb: Int): Hsv {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val chroma = max - min

            val hue = when {
                chroma == 0f -> 0f
                max == r -> 60f * ((g - b) / chroma).mod(6f)
                max == g -> 60f * (((b - r) / chroma) + 2f)
                else -> 60f * (((r - g) / chroma) + 4f)
            }

            return Hsv(
                hue = normalizeHue(hue),
                saturation = if (max == 0f) 0f else chroma / max,
                value = max,
            )
        }

        /** One channel, `0f..1f` to `0..255`, rounded rather than truncated. */
        private fun byteOf(channel: Float): Int =
            (channel * 255f).roundToInt().coerceIn(0, 255)
    }
}
