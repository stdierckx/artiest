package be.thalos.artiest.engine.brush

/**
 * A brush tip that is a picture: one byte of coverage per pixel, at whatever
 * size it was drawn at.
 *
 * ## Why the engine needs a second kind of nib at all
 *
 * [MaskGenerator] makes a dab out of arithmetic — an ellipse with a soft band —
 * and that is the right nib for a pen, a pencil and a marker, which is what
 * this engine shipped with. It is the wrong nib for a stub of charcoal, a
 * bristle fan or a splatter, because none of those is a shape with a formula.
 * They are *pictures*, and every editor that has them stores them as pictures.
 *
 * Of the 118 presets in the bundle `docs/brushes-plan.md` measures, 35 are
 * ellipses and **47 are pictures**. This type is the whole of what those 47 are
 * waiting on.
 *
 * ## What it deliberately is not
 *
 * **Not a `Bitmap`, and not decoded here.** `:engine` has no Android on its
 * classpath by construction, so a tip arrives as bytes somebody else read out
 * of a PNG or a `.gbr`. That also keeps the format question — which file types
 * a tip may come from — out of the part of the program that draws.
 *
 * **Not colour.** A tip is coverage, exactly as [AlphaMask] is, and for the
 * same reason: one tip serves every ink, and the colour is applied at blit
 * time. A coloured tip would be a stamp rather than a brush and it is a
 * different feature.
 *
 * **Not mutable.** The bytes are copied in. A tip is shared by every dab of
 * every stroke that uses it and by the mask cache's keys; something that can
 * change under those is a bug that only appears after a preset is edited.
 *
 * ## The mip chain, which is the only clever thing here
 *
 * A 600-pixel tip drawn at 9 pixels is a 66:1 minification, and point or even
 * bilinear sampling of that reads 81 texels out of 360,000 — so the dab
 * flickers as the stroke moves, because which 81 changes. The fix is the one
 * every renderer uses: keep halved copies, and sample the one nearest the size
 * actually being drawn. Built once, at registration, and the whole chain costs
 * a third more than the tip alone.
 *
 * See `docs/brushes-plan.md`, Wb5.
 */
class Tip(
    /** What a brush file names to ask for this. Unique within a [TipLibrary]. */
    val id: String,
    width: Int,
    height: Int,
    /** Coverage, row-major, one unsigned byte per pixel. Copied. */
    alpha: ByteArray,
    /**
     * Which of a [MaskCache] key's tip buckets this occupies, 1..[MAX_SLOT].
     *
     * A small integer rather than the id, because the key is one `Long` and a
     * string would mean hashing and boxing on the hottest path in the program.
     * [TipLibrary] hands these out; a hand-made tip in a test can take the
     * default. **Zero means no tip**, so it is not available here.
     */
    val slot: Int = 1,
) {

    private val mips: List<Grey>

    init {
        require(width > 0 && height > 0) { "tip $id was ${width}x$height" }
        require(alpha.size == width * height) {
            "tip $id has ${alpha.size} bytes for ${width}x$height"
        }
        require(slot in 1..MAX_SLOT) { "tip $id took slot $slot" }
        mips = buildMips(Grey(width, height, alpha.copyOf()))
    }

    val width: Int get() = mips[0].width
    val height: Int get() = mips[0].height

    /** The tip's longest side. What a brush's diameter is measured against. */
    val span: Int get() = if (width > height) width else height

    /** Bytes held, the whole chain. For [TipLibrary]'s budget. */
    val byteCount: Int get() = mips.sumOf { it.alpha.size }

    /** How many halvings are kept. One for a single-pixel tip. */
    val levelCount: Int get() = mips.size

    /** Coverage at [x],[y] as 0..255. Outside is 0, not an exception. */
    fun alphaAt(x: Int, y: Int): Int = mips[0].at(x, y)

    /**
     * The level to sample when one destination pixel covers [scale] of a tip
     * pixel, and the factor its own coordinates need.
     *
     * [scale] below 1 is minification. Level *k* is the tip halved *k* times,
     * so it is minified by 2^-k already; the level wanted is the last one that
     * is still at or above the asked-for size, which leaves a residual
     * minification inside one octave for the sampler to handle.
     */
    internal fun levelFor(scale: Float): Int {
        if (!(scale > 0f) || scale >= 1f) return 0
        var level = 0
        var s = scale
        while (level < mips.size - 1 && s * 2f <= 1f) {
            s *= 2f
            level++
        }
        return level
    }

    /**
     * Bilinear coverage at [u],[v] in *level* pixels, 0..255 as a float.
     *
     * Outside the picture is zero and not the edge texel, because a tip's edge
     * is transparent in every brush anyone has ever drawn and clamping would
     * smear its last row across the whole margin.
     */
    internal fun sample(level: Int, u: Float, v: Float): Float {
        val g = mips[level.coerceIn(0, mips.size - 1)]
        val x0 = kotlin.math.floor(u - 0.5f)
        val y0 = kotlin.math.floor(v - 0.5f)
        val fx = u - 0.5f - x0
        val fy = v - 0.5f - y0
        val ix = x0.toInt()
        val iy = y0.toInt()
        val a = g.at(ix, iy)
        val b = g.at(ix + 1, iy)
        val c = g.at(ix, iy + 1)
        val d = g.at(ix + 1, iy + 1)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    /** The width of [level] in its own pixels. */
    internal fun levelWidth(level: Int): Int = mips[level.coerceIn(0, mips.size - 1)].width

    internal fun levelHeight(level: Int): Int = mips[level.coerceIn(0, mips.size - 1)].height

    override fun toString(): String = "Tip($id, ${width}x$height, $levelCount levels)"

    /** One resolution of the chain. */
    private class Grey(val width: Int, val height: Int, val alpha: ByteArray) {
        fun at(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= width || y >= height) return 0
            return alpha[y * width + x].toInt() and 0xFF
        }
    }

    companion object {

        /** Slots are eight bits of the cache key, and zero means "no tip". */
        const val MAX_SLOT: Int = 255

        /**
         * Halve until nothing is left, by a plain box filter.
         *
         * A box filter and not something better: the levels exist to stop a
         * minified dab flickering, and the difference between a box and a
         * Lanczos at that job is invisible once the result is one byte of
         * coverage on a nib a few pixels across. An odd dimension drops its
         * last row or column into the average rather than off the end, so a
         * 601-pixel tip does not lose its right edge on the way down.
         */
        private fun buildMips(base: Grey): List<Grey> {
            val out = ArrayList<Grey>(8)
            out.add(base)
            var g = base
            while (g.width > 1 || g.height > 1) {
                val w = if (g.width > 1) g.width / 2 else 1
                val h = if (g.height > 1) g.height / 2 else 1
                val next = ByteArray(w * h)
                for (y in 0 until h) {
                    val sy = y * 2
                    for (x in 0 until w) {
                        val sx = x * 2
                        var sum = 0
                        var n = 0
                        for (dy in 0..1) {
                            for (dx in 0..1) {
                                val px = sx + dx
                                val py = sy + dy
                                if (px < g.width && py < g.height) {
                                    sum += g.at(px, py)
                                    n++
                                }
                            }
                        }
                        next[y * w + x] = (sum / n).toByte()
                    }
                }
                g = Grey(w, h, next)
                out.add(g)
            }
            return out
        }
    }
}

/**
 * The tips this device has, by id.
 *
 * ## Why a registry and not a field on the brush
 *
 * A [Brush] names its tip with a string, because a brush is a text file and a
 * text file cannot hold a picture. Something has to turn that name into pixels,
 * once, rather than per stroke — and that something also has to be the thing
 * that says *no*: a tip is up to a megabyte and a shelf of forty brushes that
 * each brought their own is a shelf that will not open on a tablet.
 *
 * So this owns two policies and nothing else: **the name-to-pixels map**, and
 * **the budget**. It hands out [Tip.slot]s in registration order so that the
 * mask cache's key can be an integer.
 *
 * ## What it does when it is full
 *
 * Refuses, and says so by returning null. It does not evict. A tip that
 * vanished mid-drawing would change what the brush in the hand draws with, and
 * a brush that quietly becomes a round dab halfway down a stroke is worse than
 * a brush that was never offered — [Brush.tip] naming something absent already
 * falls back to the procedural nib, which is a defined outcome.
 *
 * Not thread-safe, and registered from whichever thread loads brushes. Reads on
 * the render thread are of a map that is written at load and left alone, which
 * is the same arrangement `BrushLibrary` has.
 */
class TipLibrary(
    /** Bytes of tip to hold, mip chains included. 8 MiB. */
    val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {

    private val byId = LinkedHashMap<String, Tip>()
    private var bytes = 0L
    private var nextSlot = 1

    val size: Int get() = byId.size
    val byteCount: Long get() = bytes

    /** Every tip held, in the order they were registered. */
    val tips: List<Tip> get() = byId.values.toList()

    /**
     * Take [alpha] as the tip called [id], or return null if it does not fit.
     *
     * Registering the same id twice returns the tip already held rather than
     * replacing it. Two brushes that name one tip are the normal case — a
     * bundle's charcoals share a stub — and re-reading the pixels for the
     * second would double the memory to hold the same picture.
     */
    fun add(id: String, width: Int, height: Int, alpha: ByteArray): Tip? {
        byId[id]?.let { return it }
        if (nextSlot > Tip.MAX_SLOT) return null
        val tip = runCatching { Tip(id, width, height, alpha, nextSlot) }.getOrNull() ?: return null
        if (bytes + tip.byteCount > budgetBytes && byId.isNotEmpty()) return null
        byId[id] = tip
        bytes += tip.byteCount
        nextSlot++
        return tip
    }

    /** The tip called [id], or null — which a brush reads as "draw an ellipse". */
    fun find(id: String?): Tip? = if (id == null) null else byId[id]

    fun clear() {
        byId.clear()
        bytes = 0L
        nextSlot = 1
    }

    override fun toString(): String = "TipLibrary($size tips, ${bytes / 1024} KiB)"

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 8L * 1024 * 1024

        /** What a build with no tips loaded has. */
        val EMPTY: TipLibrary = TipLibrary()
    }
}
