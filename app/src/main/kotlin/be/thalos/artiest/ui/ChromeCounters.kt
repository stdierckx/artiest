package be.thalos.artiest.ui

import java.util.concurrent.atomic.AtomicInteger

/**
 * How hard the chrome is working, so that "it does not cost anything" is a
 * reading rather than an opinion.
 *
 * ## The one number that decides whether the workspace system shipped
 *
 * `docs/ui-expansion-plan.md`'s last item is a gate: **a dropped frame while
 * drawing is the thing this project does not tolerate.** The chrome sits over
 * the ink path, and the failure it can cause is specific — a toolbar that
 * recomposes while the pen is down is work on the UI thread in the frames that
 * matter most.
 *
 * So the instrument is not a frame-time histogram, which would measure
 * everything and blame nothing. It is a count of **chrome recompositions**, and
 * the pass condition is one you can read at a glance with the pen still on the
 * glass:
 *
 * > While a stroke is being drawn, `in the last second` must be **0**.
 *
 * Not "small". Zero. Nothing about a toolbar changes while a line is being
 * drawn, so any number at all is something reading state it should not be
 * reading, and the fix is known and boring: hoist whatever it is into a child.
 *
 * ## Why it is not Compose state
 *
 * For the same reason `InputStats` is not: a counter that recomposed the
 * readout would be a counter that changed the number it was reporting. These
 * are plain atomics, read by the instruments overlay when it happens to
 * redraw, and written from composition through `SideEffect`.
 *
 * The cost when the overlay is off is one atomic increment per surface per
 * recomposition — which, if the gate above is met, is a handful per session.
 */
object ChromeCounters {

    private val total = AtomicInteger()
    private val window = AtomicInteger()

    @Volatile
    private var windowStartMs = 0L

    /** How many toolbars are on screen. Three is what the plan measures with. */
    @Volatile
    var surfaces: Int = 0

    /** How many controls are visible across all of them. Twenty, in the plan. */
    @Volatile
    var cells: Int = 0

    /** Every recomposition of a surface, ever. */
    val composes: Int get() = total.get()

    /**
     * Recompositions in the last second.
     *
     * The window is rolled forward lazily, on read and on write, so an idle
     * chrome costs nothing to keep counting and a reading taken a minute after
     * the last one is not a minute's worth of arithmetic.
     */
    fun composesPerSecond(): Int {
        roll()
        return window.get()
    }

    /** Called from composition, through `SideEffect`. */
    fun composed() {
        roll()
        total.incrementAndGet()
        window.incrementAndGet()
    }

    /** Start again. What the instruments overlay's reset does. */
    fun reset() {
        total.set(0)
        window.set(0)
        windowStartMs = now()
    }

    private fun roll() {
        val now = now()
        if (now - windowStartMs >= WINDOW_MS) {
            windowStartMs = now
            window.set(0)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private const val WINDOW_MS = 1_000L

    /** The instruments line. Read the middle number with the pen down. */
    fun readout(): String =
        "chrome   $surfaces surfaces   $cells controls   " +
            "recompose ${composesPerSecond()}/s   $composes total"
}
