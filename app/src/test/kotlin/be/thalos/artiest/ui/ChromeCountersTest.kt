package be.thalos.artiest.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instrument, not the measurement.
 *
 * What is tested here is that the counter counts. **The measurement it exists
 * for has not been taken** — it needs a pen, a tablet and sixty seconds, and
 * `docs/ui-expansion-plan.md` is explicit that it is a gate rather than a
 * nice-to-have. See the U10 commit for what is owed.
 */
class ChromeCountersTest {

    @BeforeTest
    fun clear() {
        ChromeCounters.reset()
    }

    @Test
    fun `a fresh counter has counted nothing`() {
        assertEquals(0, ChromeCounters.composes)
        assertEquals(0, ChromeCounters.composesPerSecond())
    }

    @Test
    fun `every recomposition is counted, in the window and in the total`() {
        repeat(7) { ChromeCounters.composed() }
        assertEquals(7, ChromeCounters.composes)
        assertEquals(7, ChromeCounters.composesPerSecond())
    }

    @Test
    fun `the readout says what is on screen and how hard it is working`() {
        ChromeCounters.surfaces = 3
        ChromeCounters.cells = 20
        repeat(4) { ChromeCounters.composed() }
        val line = ChromeCounters.readout()
        assertTrue("3 surfaces" in line, line)
        assertTrue("20 controls" in line, line)
        assertTrue("recompose 4/s" in line, line)
        assertTrue("4 total" in line, line)
    }

    @Test
    fun `reset starts again`() {
        repeat(3) { ChromeCounters.composed() }
        ChromeCounters.reset()
        assertEquals(0, ChromeCounters.composes)
        assertEquals(0, ChromeCounters.composesPerSecond())
    }

    @Test
    fun `reading it costs nothing when nothing is happening`() {
        // The window is rolled forward lazily, so an idle chrome is not doing
        // arithmetic to keep reporting zero, and a reading taken a minute after
        // the last one is not a minute's worth of catching up.
        repeat(1000) { assertEquals(0, ChromeCounters.composesPerSecond()) }
        assertEquals(0, ChromeCounters.composes)
    }
}
