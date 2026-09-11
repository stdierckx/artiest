package be.thalos.artiest.ink

import android.graphics.Bitmap
import be.thalos.artiest.engine.brush.BrushEntry

/**
 * Rendered swatches, kept, so that a shelf of thirty brushes draws one stroke
 * per brush per session rather than one per frame.
 *
 * ## Why it is here and not in the composable
 *
 * A `remember` in a row dies with the row, and a scrolling list destroys rows
 * constantly — so a cache inside one would re-render every brush each time it
 * came back on screen. What a swatch actually depends on is the brush, the
 * size and the ink, and none of those is a property of a row being on screen.
 *
 * ## Bounded, and never recycled
 *
 * [LIMIT] entries, least-recently-used first out. An evicted bitmap is dropped
 * rather than recycled, on purpose: a `Bitmap` that is still being drawn by a
 * composition that has not been disposed yet throws when it is recycled, and
 * *"the shelf crashed while I was scrolling it"* is a far worse outcome than
 * letting the collector have a 60 KiB bitmap at its own pace.
 *
 * Synchronized, because it is written from whatever background dispatcher the
 * render was launched on and read from the composition. The map is small and
 * the lock is held for a lookup, so there is nothing here to contend over.
 */
object BrushSwatches {

    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > LIMIT
    }

    /** Swatches rendered since the app started. Read by the instruments line. */
    @Volatile
    var rendered: Int = 0
        private set

    /**
     * The key a swatch is stored under: what it draws, how big, and in what.
     *
     * [BrushEntry.stamp] rather than its id, so that saving over a brush shows
     * the new mark — see the KDoc there.
     */
    fun keyOf(entry: BrushEntry, width: Int, height: Int, colorArgb: Int): String =
        "${entry.stamp}/${width}x$height/$colorArgb"

    fun get(key: String): Bitmap? = synchronized(cache) { cache[key] }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) { cache[key] = bitmap }
    }

    /**
     * Render [entry] at this size, or hand back what was rendered before.
     *
     * **Call this off the UI thread and off the render thread.** It draws a real
     * stroke; see [BrushSwatch].
     */
    fun render(entry: BrushEntry, width: Int, height: Int, colorArgb: Int): Bitmap {
        val key = keyOf(entry, width, height, colorArgb)
        get(key)?.let { return it }
        val made = BrushSwatch.render(entry.create(), width, height, colorArgb)
        rendered++
        put(key, made)
        return made
    }

    /** For a test that wants to start from nothing. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        rendered = 0
    }

    /**
     * Two screens' worth of rows at the sizes the shelf uses, which is enough
     * that scrolling a full shelf never re-renders and small enough that the
     * whole cache is under two megabytes.
     */
    private const val LIMIT = 48
}
