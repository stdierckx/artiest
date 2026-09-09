package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the toolbar survives on its way to disk and back.
 *
 * Most of these are not round trips. They are the strings a *different build*
 * writes — one control newer, one control older, a slot count from a different
 * screen — and the assertion in every case is that the app still starts with a
 * usable bar. See [ToolbarCodec]'s KDoc for why that is the requirement.
 */
class ToolbarCodecTest {

    /**
     * A bar of the shape the single toolbar used to ship with. Written out here
     * rather than taken from a constant because this file is about what an
     * *older build* wrote, and the constant is free to move.
     */
    private val starter = ToolbarLayout.of(
        24,
        listOf(
            Placement(ToolItem.PEN, 0),
            Placement(ToolItem.PENCIL, 2),
            Placement(ToolItem.UNDO, 4),
            Placement(ToolItem.REDO, 6),
            Placement(ToolItem.COLOUR, 8),
            Placement(ToolItem.SIZE, 12),
            Placement(ToolItem.ZOOM_OUT, 16),
            Placement(ToolItem.ZOOM_IN, 17),
            Placement(ToolItem.FIT, 18),
            Placement(ToolItem.STATS, 20),
        ),
    )

    @Test
    fun `a layout survives the round trip`() {
        val there = ToolbarCodec.encode(starter)
        assertEquals(
            "v1|24|0=pen,2=pencil,4=undo,6=redo,8=colour,12=size,16=zoom_out,17=zoom_in,18=fit,20=stats",
            there,
        )
        assertEquals(starter, ToolbarCodec.decode(there))
    }

    @Test
    fun `an empty bar survives the round trip`() {
        val bar = ToolbarLayout.of(24, emptyList())
        val there = ToolbarCodec.encode(bar)
        assertEquals("v1|24|", there)
        assertEquals(bar, ToolbarCodec.decode(there))
    }

    @Test
    fun `an id this build has never heard of is dropped and the rest is kept`() {
        // Written by a build that has a tool this one does not. It used to say
        // "flow", which W7 then shipped -- so the example had to become
        // something no build has, which is the risk of naming a real future
        // feature in a test about unknown names.
        val bar = assertNotNull(ToolbarCodec.decode("v1|16|0=colour,4=perspective_grid,13=stats"))
        assertEquals(ToolItem.COLOUR, bar.covering(0)?.item)
        assertNull(bar.covering(4))
        assertEquals(ToolItem.STATS, bar.covering(13)?.item)
    }

    @Test
    fun `entries that cannot be placed are dropped, not fatal`() {
        val bar = assertNotNull(ToolbarCodec.decode("v1|12|9=size,0=smoothing,1=fit,10=stats"))
        // size at 9 would run to 13, past the end of a 12-slot bar; fit at 1
        // overlaps smoothing, which holds 0..3.
        assertEquals(
            listOf(Placement(ToolItem.SMOOTHING, 0), Placement(ToolItem.STATS, 10)),
            bar.placements,
        )
    }

    @Test
    fun `malformed entries are dropped one by one`() {
        val bar = assertNotNull(
            ToolbarCodec.decode("v1|12|=colour,3=,4colour,x=fit,,7=clear"),
        )
        assertEquals(listOf(Placement(ToolItem.CLEAR, 7)), bar.placements)
    }

    @Test
    fun `a different slot count is honoured`() {
        val bar = assertNotNull(ToolbarCodec.decode("v1|20|16=export"))
        assertEquals(20, bar.slotCount)
        assertEquals(ToolItem.EXPORT, bar.covering(16)?.item)
    }

    @Test
    fun `strings that are not a layout at all decode to null`() {
        for (bad in listOf(
            null,
            "",
            "nonsense",
            "v2|12|0=colour",     // a format from the future
            "v1|12",              // truncated
            "v1|12|0=smoothing|junk", // one field too many
            "v1|x|0=colour",      // slot count is not a number
            "v1|0|",              // a bar with no slots
            "v1|-4|",
            "v1|100000|",         // the ceiling, so a corrupt file is not an allocation
        )) {
            assertNull(ToolbarCodec.decode(bad), "decoded <$bad>")
        }
    }
}
