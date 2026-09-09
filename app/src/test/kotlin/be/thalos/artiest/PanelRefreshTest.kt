package be.thalos.artiest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelRefreshTest {

    @Test
    fun `a panel exactly where it was asked to be counts as settled`() {
        assertTrue(PanelRefresh.settled(90f, 90f))
        assertTrue(PanelRefresh.settled(60f, 60f))
    }

    /** This panel's 60 Hz mode reports 60.000004, so exact equality is wrong. */
    @Test
    fun `the slop covers the rates real panels report`() {
        assertTrue(PanelRefresh.settled(60.000004f, 60f))
        assertTrue(PanelRefresh.settled(89.7f, 90f))
    }

    /**
     * The assertion the whole button exists for: 60 must never read as 90. A
     * take filmed on a false pass is a take that has to be shot again, and run
     * C proved nobody notices for a day.
     */
    @Test
    fun `sixty never reads as ninety`() {
        assertFalse(PanelRefresh.settled(60f, 90f))
        assertFalse(PanelRefresh.settled(60.000004f, 90f))
    }

    /**
     * An idle app can be given a render rate of 45 while the panel holds 90.
     * The caller passes the mode's rate for that reason, and if it ever passes
     * the render rate instead, this is the line that fails.
     */
    @Test
    fun `an idle render rate is not a settled panel`() {
        assertFalse(PanelRefresh.settled(45f, 90f))
    }
}
