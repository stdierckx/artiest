package be.thalos.artiest.project

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What keeping the drawing costs, so that "you cannot feel it" is a reading
 * rather than an opinion.
 *
 * ## The number that decides whether this shipped
 *
 * `docs/projects-plan.md`'s gate is the same one the workspace system had, in a
 * different place: **a dropped frame while drawing is the thing this project
 * does not tolerate.** A save is a 27 MiB copy and a 200 ms encode happening
 * every few seconds while somebody is drawing, so the question is not whether
 * it is fast but whether any of it is on the path the pen is on.
 *
 * By construction almost none of it is — every step is on `Dispatchers.IO` and
 * the encode happens off-lock. Exactly one part of a save can block the render
 * thread, and that is [onLock]: the single blit that copies a sheet out from
 * under `Layer`'s lock. So that is the number to read.
 *
 * > **`on lock` is the longest a commit could have waited.** `PngExporter`
 * > measured the same copy at 13.6–14.8 ms on this tablet for a full page. One
 * > frame is 16.7 ms at 60 Hz and 11.1 at 90, so a save can cost at most one
 * > frame and only if a pen-up lands inside it.
 *
 * Everything else here is context for that one: how long the whole save took,
 * how many sheets it had to write (which should be one), and what an open
 * costs, which is the other moment this feature does real work.
 *
 * ## Why these are plain atomics
 *
 * `ChromeCounters`' reason: a counter that recomposed the readout would be a
 * counter that changed the number it was reporting. They are written from the
 * IO thread and read by the instruments overlay when it happens to redraw.
 */
object ProjectCounters {

    private val saveMs = AtomicLong(-1)
    private val saveSheets = AtomicInteger()
    private val lockMs = AtomicLong(-1)
    private val worstLockMs = AtomicLong()
    private val thumbMs = AtomicLong(-1)
    private val openMs = AtomicLong(-1)
    private val openSheets = AtomicInteger()
    private val openPeakKiB = AtomicLong()

    /** Milliseconds the last save took, end to end, or -1 for none yet. */
    val save: Long get() = saveMs.get()

    /** Sheets that last save had to encode. One is the design; eight is a bug. */
    val sheets: Int get() = saveSheets.get()

    /** The longest single blit under `Layer`'s lock in the last save. */
    val onLock: Long get() = lockMs.get()

    /** The worst one this session. The number the gate is about. */
    val worstOnLock: Long get() = worstLockMs.get()

    /**
     * How much of the last save was the picture for the gallery.
     *
     * Here because it is the surprising half: one changed sheet is one encode,
     * but the thumbnail is a composite of *every* sheet and a second encode, so
     * an eight-sheet drawing pays for eight blits to redraw a card nobody is
     * looking at. It is all off the pen's thread, which is why it is a number
     * to know rather than a bug to fix.
     */
    val thumbnail: Long get() = thumbMs.get()

    val open: Long get() = openMs.get()

    val opened: Int get() = openSheets.get()

    /** Native heap high-water mark seen while the last open was running, in MiB. */
    val openPeak: Long get() = openPeakKiB.get() / 1024

    fun saved(ms: Long, sheets: Int) {
        saveMs.set(ms)
        saveSheets.set(sheets)
    }

    fun copied(ms: Long) {
        lockMs.set(ms)
        // Not a running maximum of the last save's copies -- the worst one the
        // app has ever done. A gate that only remembered the most recent save
        // would be a gate that forgets the frame it dropped.
        worstLockMs.getAndUpdate { maxOf(it, ms) }
    }

    fun drewThumbnail(ms: Long) {
        thumbMs.set(ms)
    }

    fun openedProject(ms: Long, sheets: Int, peakBytes: Long) {
        openMs.set(ms)
        openSheets.set(sheets)
        openPeakKiB.set(peakBytes / 1024)
    }

    /** For a test that wants to start from nothing. */
    fun reset() {
        saveMs.set(-1)
        saveSheets.set(0)
        lockMs.set(-1)
        worstLockMs.set(0)
        thumbMs.set(-1)
        openMs.set(-1)
        openSheets.set(0)
        openPeakKiB.set(0)
    }
}
