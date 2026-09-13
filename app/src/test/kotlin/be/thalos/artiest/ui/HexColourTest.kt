package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The colour code you can now type.
 *
 * > *"You cant input a color code."*
 *
 * The field was a `Text`, so the whole feature is this function plus a caret.
 * What is worth pinning is not that `#FF0000` is red — it is **what happens to
 * everything that is not a colour yet**, because the field commits on every
 * keystroke: a parser that said yes too early would walk the ink through every
 * prefix of what somebody is typing.
 */
class HexColourTest {

    @Test
    fun `six digits, with or without the hash`() {
        assertEquals(0xFFD32F2F.toInt(), parseHex("#D32F2F"))
        assertEquals(0xFFD32F2F.toInt(), parseHex("d32f2f"), "lower case, no hash")
        assertEquals(0xFFD32F2F.toInt(), parseHex("  #D32F2F  "), "and pasted with spaces")
    }

    @Test
    fun `the three digit shorthand is refused, and that is the point`() {
        // `#D32` is a valid CSS colour *and* a prefix of `#D32F2F`. Accepting
        // it would set the ink to `#DD3322` halfway through somebody typing a
        // red, because the field commits on every keystroke. Committing early
        // and accepting prefixes are two halves of one bad idea; this is the
        // half worth keeping.
        assertNull(parseHex("#fff"))
        assertNull(parseHex("#08f"))
    }

    @Test
    fun `alpha is always full`() {
        // The app's ink is opaque by construction — see `Brush`. A code that
        // could set alpha would be offering something nothing downstream can
        // hold, so black is 0xFF000000 and not 0x00000000.
        assertEquals(0xFF000000.toInt(), parseHex("#000000"))
    }

    @Test
    fun `a half typed code is not a colour`() {
        // The whole reason this is a function with a null in it. Typing
        // "#D32F2F" one character at a time must set the ink once, at the end,
        // and not six times on the way there.
        val steps = listOf("#", "#D", "#D3", "#D32", "#D32F", "#D32F2")
        for (step in steps) assertNull(parseHex(step), "committed early on '$step'")
        assertEquals(0xFFD32F2F.toInt(), parseHex("#D32F2F"))
    }

    @Test
    fun `nothing else is a colour`() {
        assertNull(parseHex(""))
        assertNull(parseHex("#"))
        assertNull(parseHex("#GGGGGG"), "six characters, none of them hex")
        assertNull(parseHex("#D32F2F2"), "seven")
        assertNull(parseHex("red"))
    }
}
