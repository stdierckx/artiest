package be.thalos.artiest.engine.brush

/**
 * The dab masks currently in memory, keyed by [MaskTolerance]'s quantised
 * buckets.
 *
 * **This is the item the plan calls the phase's first real risk**, and the risk
 * is arithmetic, not design: at 230 dabs an event a cache that misses is a mask
 * generator running 230 times an event, which is slower than the `drawCircle`
 * it replaced and buys nothing. So this counts its own hits and misses and
 * exposes them — [hits], [misses], [generated] — because the plan's stop
 * condition for W4 is a measured hit rate on the ramp strokes of the golden
 * corpus, and a cache that cannot report its hit rate cannot be stopped on it.
 *
 * **Eviction is by insertion order, not by recency.** A stroke sweeps its size
 * range monotonically — a taper walks small to large and back — so the entry
 * least likely to be wanted next is genuinely the oldest, and true LRU would
 * cost a reordering on every hit to answer the same question. The budget is a
 * byte count rather than an entry count because a 128 px mask is 16 KB and a
 * 2 px mask is 16 bytes, and a cap in entries would be four orders of magnitude
 * wrong at one end or the other.
 *
 * Not thread-safe. It is owned by whichever thread rasterizes, which in this
 * app is the render thread; sharing it with the UI thread would need a lock on
 * the hottest path in the program, and there is nothing the UI thread wants
 * from it.
 */
class MaskCache(
    /** How near two dabs must be to share a mask. */
    val tolerance: MaskTolerance = MaskTolerance(),
    /** Bytes of mask to hold before evicting. 4 MiB. */
    val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {

    private val entries = LinkedHashMap<Long, AlphaMask>(256)
    private var bytes = 0L

    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set

    /** Masks built since construction. Equal to [misses] unless something evicts and refetches. */
    var generated: Long = 0L
        private set

    /** Masks dropped to stay inside [budgetBytes]. A high number here is the ramp problem. */
    var evictions: Long = 0L
        private set

    val size: Int get() = entries.size

    val byteCount: Long get() = bytes

    /** Hits over lookups, or `NaN` before the first lookup. */
    val hitRate: Double
        get() = if (hits + misses == 0L) Double.NaN else hits.toDouble() / (hits + misses)

    /**
     * The mask for [spec], built if it is not held.
     *
     * The returned mask's own [AlphaMask.spec] is the *quantised* spec, which
     * is deliberately not the one passed in: a caller that needs to know how far
     * the dab moved can compare the two, and one that blits it needs the
     * quantised size to place it.
     */
    fun get(spec: MaskSpec): AlphaMask {
        val k = tolerance.key(spec)
        val held = entries[k]
        if (held != null) {
            hits++
            return held
        }
        misses++
        val mask = MaskGenerator.generate(tolerance.quantize(spec))
        generated++
        entries[k] = mask
        bytes += mask.byteCount
        evictToBudget()
        return mask
    }

    /** Whether [spec]'s bucket is held, without building it or counting a lookup. */
    fun holds(spec: MaskSpec): Boolean = entries.containsKey(tolerance.key(spec))

    fun clear() {
        entries.clear()
        bytes = 0L
    }

    /** Zero the counters without dropping the masks. For a bench that measures one stroke. */
    fun resetStats() {
        hits = 0
        misses = 0
        generated = 0
        evictions = 0
    }

    private fun evictToBudget() {
        if (bytes <= budgetBytes) return
        val it = entries.entries.iterator()
        // Always keep the entry just inserted, even if it alone exceeds the
        // budget: returning a mask and immediately forgetting it would make
        // every dab of a very large brush a miss, which is the pathology this
        // whole class exists to avoid.
        while (bytes > budgetBytes && entries.size > 1 && it.hasNext()) {
            val e = it.next()
            it.remove()
            bytes -= e.value.byteCount
            evictions++
        }
    }

    override fun toString(): String =
        "MaskCache(${entries.size} masks, ${bytes / 1024} KiB, " +
            "hit ${"%.1f".format(hitRate * 100)}%, $evictions evicted)"

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 4L * 1024 * 1024
    }
}
