package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the docks survive on their way to disk and back.
 *
 * Most of these are not round trips. They are the strings a *different build*
 * writes — one control newer, one dock older, a slot count from a different
 * screen — and the assertion in every case is that the app still starts with
 * usable toolbars. See [DockCodec]'s KDoc for why that is a requirement rather
 * than politeness.
 */
class DockCodecTest {

    @Test
    fun `the starter layout survives the round trip`() {
        val there = DockCodec.encode(DockLayout.STARTER)
        assertEquals(DockLayout.STARTER, DockCodec.decode(there))
    }

    @Test
    fun `the encoding is the one written down in the KDoc`() {
        val layout = DockLayout.of(
            mapOf(
                Dock.LEFT to ToolbarLayout.of(12, listOf(Placement(ToolItem.PEN, 0))),
                Dock.BOTTOM to ToolbarLayout.of(24, listOf(Placement(ToolItem.SIZE, 4))),
            ),
        )
        assertEquals(
            "v2|left:12:0=pen|top:24:|right:12:|bottom:24:4=size|float:8:",
            DockCodec.encode(layout),
        )
    }

    @Test
    fun `an empty layout survives the round trip`() {
        val there = DockCodec.encode(DockLayout.EMPTY)
        assertEquals(DockLayout.EMPTY, DockCodec.decode(there))
    }

    @Test
    fun `a bar saved before docking existed becomes the top dock`() {
        // The migration that had to exist. Reading this as unreadable would be
        // the easy thing and would silently empty the toolbar of everyone who
        // had already arranged one.
        val layout = assertNotNull(DockCodec.decode("v1|24|0=pen,2=pencil,8=colour,12=size"))
        assertEquals(Dock.TOP, layout.locate(ToolItem.PEN)?.dock)
        assertEquals(Dock.TOP, layout.locate(ToolItem.SIZE)?.dock)
        assertEquals(12, layout.locate(ToolItem.SIZE)?.slot, "slots are absolute and did not move")
        for (dock in Dock.entries - Dock.TOP) {
            assertTrue(layout.bar(dock).isEmpty, dock.id)
        }
    }

    @Test
    fun `a short pre-docking bar is widened rather than kept full`() {
        // A bar that is exactly full migrates to a dock that is exactly full,
        // and nothing new could ever be added to it again.
        val layout = assertNotNull(DockCodec.decode("v1|2|0=pen,1=pencil"))
        assertEquals(Dock.TOP.defaultSlots, layout.bar(Dock.TOP).slotCount)
        assertNotNull(layout.firstFit(Dock.TOP, ToolItem.ERASER))
    }

    @Test
    fun `an id this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|left:12:0=pen,1=perspective_grid,2=eraser"),
        )
        assertEquals(ToolItem.PEN, layout.bar(Dock.LEFT).covering(0)?.item)
        assertNull(layout.bar(Dock.LEFT).covering(1))
        assertEquals(ToolItem.ERASER, layout.bar(Dock.LEFT).covering(2)?.item)
    }

    @Test
    fun `a dock this build has never heard of is dropped and the rest is kept`() {
        // The same event as an unknown id, one level up: a build that grew a
        // sixth place to put things.
        val layout = assertNotNull(
            DockCodec.decode("v2|left:12:0=pen|shelf:12:0=eraser|top:24:0=undo"),
        )
        assertEquals(ToolItem.PEN, layout.bar(Dock.LEFT).covering(0)?.item)
        assertEquals(ToolItem.UNDO, layout.bar(Dock.TOP).covering(0)?.item)
        assertNull(layout.locate(ToolItem.ERASER))
    }

    @Test
    fun `an unmentioned dock is an empty one, not a failure`() {
        val layout = assertNotNull(DockCodec.decode("v2|left:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.bar(Dock.LEFT).covering(0)?.item)
        assertTrue(layout.bar(Dock.BOTTOM).isEmpty)
        assertEquals(Dock.BOTTOM.defaultSlots, layout.bar(Dock.BOTTOM).slotCount)
    }

    @Test
    fun `the same dock twice keeps the first`() {
        val layout = assertNotNull(DockCodec.decode("v2|top:24:0=undo|top:24:0=redo"))
        assertEquals(ToolItem.UNDO, layout.bar(Dock.TOP).covering(0)?.item)
        assertNull(layout.locate(ToolItem.REDO))
    }

    @Test
    fun `the same item in two docks keeps the first, so no control is in two places`() {
        val layout = assertNotNull(DockCodec.decode("v2|left:12:0=eraser|top:24:4=eraser"))
        assertEquals(Dock.LEFT, layout.locate(ToolItem.ERASER)?.dock)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `entries that cannot be placed are dropped, not fatal`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|bottom:12:9=size,0=smoothing,1=fit,10=stats"),
        )
        // size at 9 would run to 13, past the end of a 12-slot dock; fit at 1
        // overlaps smoothing, which holds 0..3.
        assertEquals(
            listOf(Placement(ToolItem.SMOOTHING, 0), Placement(ToolItem.STATS, 10)),
            layout.bar(Dock.BOTTOM).placements,
        )
    }

    @Test
    fun `malformed entries are dropped one by one`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|top:24:=pen,3=,4pen,x=fit,,7=clear"),
        )
        assertEquals(listOf(Placement(ToolItem.CLEAR, 7)), layout.bar(Dock.TOP).placements)
    }

    @Test
    fun `strings that are not a layout at all decode to null`() {
        for (bad in listOf(
            null,
            "",
            "nonsense",
            "v2",                       // no docks at all
            "v3|top:24:0=undo",         // a format from the future
            "v2|top|0=undo",            // the wrong separator inside a dock
            "v2|shelf:12:0=pen",        // not one readable dock
            "v2|top:x:0=undo",          // the slot count is not a number
            "v2|top:0:",                // a dock with no slots
            "v2|top:-4:",
            "v2|top:100000:",           // the ceiling, so a corrupt file is not an allocation
            "v1|nonsense",              // claims to be the old format and is not
        )) {
            assertNull(DockCodec.decode(bad), "decoded <$bad>")
        }
    }
}
