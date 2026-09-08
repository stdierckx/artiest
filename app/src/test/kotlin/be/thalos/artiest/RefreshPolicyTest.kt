package be.thalos.artiest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which mode the app asks for, and — the part `:spike` gets wrong — which one it
 * must not.
 */
class RefreshPolicyTest {

    /** This tablet: two rates, one resolution. Nothing here can go wrong. */
    private val movinkpad = listOf(
        ModeInfo(1, 1440, 2200, 60.000004f),
        ModeInfo(2, 1440, 2200, 90f),
    )

    /**
     * A panel that trades resolution for rate, which plenty of them do. The
     * fastest mode is not the one this app wants at any price.
     */
    private val tradesResolution = listOf(
        ModeInfo(1, 1440, 2200, 60f),
        ModeInfo(2, 1440, 2200, 90f),
        ModeInfo(3, 720, 1100, 120f),
    )

    @Test
    fun `highest picks the fastest mode`() {
        assertEquals(2, RefreshPolicy.chooseModeId(movinkpad, 1, RefreshPolicy.HIGHEST))
    }

    @Test
    fun `highest never changes the panel resolution to get there`() {
        val chosen = RefreshPolicy.chooseModeId(tradesResolution, 1, RefreshPolicy.HIGHEST)

        assertEquals(2, chosen, "90 Hz at the current size, not 120 Hz at a smaller one")

        // `:spike`'s line, longhand. It is one call and it reads as obviously
        // correct, which is why this control is here: a Display.Mode carries a
        // physical size as well as a rate, so asking for the fastest one asks
        // the compositor to rescale the panel. On a drawing app that is the pen
        // and the ink landing in different places, and nothing in the call says
        // so.
        val spike = tradesResolution.maxByOrNull { it.refreshHz }!!.modeId
        assertEquals(3, spike)
        assertNotEquals(spike, chosen)
    }

    @Test
    fun `sixty picks the sixty hertz mode`() {
        // 60.000004 is what this panel actually reports. A strict `<= 60f`
        // would reject its own 60 Hz mode and leave W16 with no control.
        assertEquals(1, RefreshPolicy.chooseModeId(movinkpad, 2, RefreshPolicy.SIXTY))
    }

    @Test
    fun `sixty takes the highest mode at or below sixty`() {
        val modes = listOf(
            ModeInfo(1, 1440, 2200, 30f),
            ModeInfo(2, 1440, 2200, 48f),
            ModeInfo(3, 1440, 2200, 90f),
        )
        assertEquals(2, RefreshPolicy.chooseModeId(modes, 3, RefreshPolicy.SIXTY))
    }

    @Test
    fun `sixty asks for nothing rather than for something faster`() {
        // A panel whose slowest mode is 90 has no 60 Hz control to offer.
        // Falling back to "the nearest mode" would hand W16 a 90 Hz run
        // labelled 60, which is worse than no control at all.
        val modes = listOf(
            ModeInfo(1, 1440, 2200, 90f),
            ModeInfo(2, 1440, 2200, 120f),
        )
        assertEquals(
            RefreshPolicy.NO_PREFERENCE,
            RefreshPolicy.chooseModeId(modes, 1, RefreshPolicy.SIXTY),
        )
    }

    @Test
    fun `unspecified asks for nothing, which is the point of having it`() {
        assertEquals(
            RefreshPolicy.NO_PREFERENCE,
            RefreshPolicy.chooseModeId(movinkpad, 1, RefreshPolicy.UNSPECIFIED),
        )
    }

    @Test
    fun `a current mode that is not in the list asks for nothing`() {
        // "The current resolution" is what filters the candidates, so without a
        // current mode there is nothing to filter by. Guessing — taking the
        // fastest, or the first — would be the resolution-changing bug wearing
        // a different hat.
        assertEquals(
            RefreshPolicy.NO_PREFERENCE,
            RefreshPolicy.chooseModeId(movinkpad, 99, RefreshPolicy.HIGHEST),
        )
        assertEquals(
            RefreshPolicy.NO_PREFERENCE,
            RefreshPolicy.chooseModeId(emptyList(), 1, RefreshPolicy.HIGHEST),
        )
    }
}
