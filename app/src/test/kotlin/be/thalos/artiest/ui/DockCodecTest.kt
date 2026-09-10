package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the bars survive on their way to disk and back.
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
    fun `a floating bar survives the round trip, with its position`() {
        val (layout, id) = DockLayout.STARTER.addFloating(
            ToolItem.COLOUR_PANEL, BarSpot(0.25f, 0.75f),
        )
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))
        assertEquals(BarSpot(0.25f, 0.75f), back.bar(id)?.spot)
        assertEquals(ToolItem.COLOUR_PANEL, back.bar(id)?.slots?.covering(0)?.item)
        assertEquals(layout, back)
    }

    @Test
    fun `the encoding is the one written down in the KDoc`() {
        val layout = DockLayout.of(
            listOf(
                Bar("left", Dock.LEFT, null,
                    ToolbarLayout.of(12, listOf(Placement(ToolItem.PEN, 0, 1)))),
                Bar("bottom", Dock.BOTTOM, null,
                    ToolbarLayout.of(24, listOf(Placement(ToolItem.SIZE, 4, 4)))),
                Bar("f1", Dock.FLOATING, BarSpot(0.32f, 0.45f),
                    ToolbarLayout.of(8, listOf(Placement(ToolItem.COLOUR_PANEL, 0, 6)))),
            ),
        )
        assertEquals(
            "v3|left:12:0=pen|top:24:|right:12:|bottom:24:4=size|f1@0.32,0.45:8:0=colour_panel",
            DockCodec.encode(layout),
        )
    }

    @Test
    fun `spans are recomputed rather than stored`() {
        // The string says only which item is where. A release that resizes a
        // panel must not leave every saved layout laid out to the old number.
        val text = "v3|left:12:0=colour_panel|top:24:0=colour"
        val back = assertNotNull(DockCodec.decode(text))
        // Eleven on a vertical edge, because that is the panel's height.
        assertEquals(11, back.edge(Dock.LEFT).slots.covering(0)?.span)
        assertEquals(1, back.edge(Dock.TOP).slots.covering(0)?.span)
    }

    @Test
    fun `an empty layout survives the round trip`() {
        assertEquals(DockLayout.EMPTY, DockCodec.decode(DockCodec.encode(DockLayout.EMPTY)))
    }

    // ---- the two older formats -------------------------------------------

    @Test
    fun `a bar saved before docking existed becomes the top edge`() {
        val layout = assertNotNull(DockCodec.decode("v1|24|0=pen,2=pencil,8=colour,12=size"))
        assertEquals("top", layout.locate(ToolItem.PEN)?.bar?.id)
        assertEquals(12, layout.locate(ToolItem.SIZE)?.slot, "slots are absolute and did not move")
        assertEquals(4, layout.locate(ToolItem.SIZE)?.placement?.span)
        for (dock in Dock.EDGES - Dock.TOP) {
            assertTrue(layout.edge(dock).isEmpty, dock.id)
        }
        assertTrue(layout.floating.isEmpty())
    }

    @Test
    fun `a short pre-docking bar is widened rather than kept full`() {
        val layout = assertNotNull(DockCodec.decode("v1|2|0=pen,1=pencil"))
        assertEquals(Dock.TOP.defaultSlots, layout.edge(Dock.TOP).slots.slotCount)
        assertNotNull(layout.firstFit("top", ToolItem.ERASER))
    }

    @Test
    fun `the five-dock format becomes four edges and at most one floating bar`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|left:12:0=pen|top:24:0=undo|float:8:0=stats"),
        )
        assertEquals("left", layout.locate(ToolItem.PEN)?.bar?.id)
        assertEquals(1, layout.floating.size)
        assertEquals("f1", layout.floating.single().id)
        assertEquals(ToolItem.STATS, layout.floating.single().slots.covering(0)?.item)
    }

    @Test
    fun `an empty five-dock floating dock does not become a bar`() {
        // In v2 the floating dock always existed whether or not anything was on
        // it. An empty one is not a bar somebody made, so it is not carried over.
        val layout = assertNotNull(DockCodec.decode("v2|left:12:0=pen|float:8:"))
        assertTrue(layout.floating.isEmpty())
    }

    // ---- best effort -----------------------------------------------------

    @Test
    fun `an id this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|left:12:0=pen,1=perspective_grid,2=eraser"),
        )
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).slots.covering(0)?.item)
        assertNull(layout.edge(Dock.LEFT).slots.covering(1))
        assertEquals(ToolItem.ERASER, layout.edge(Dock.LEFT).slots.covering(2)?.item)
    }

    @Test
    fun `a bar this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|left:12:0=pen|shelf:12:0=eraser|top:24:0=undo"),
        )
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).slots.covering(0)?.item)
        assertEquals(ToolItem.UNDO, layout.edge(Dock.TOP).slots.covering(0)?.item)
        assertNull(layout.locate(ToolItem.ERASER))
    }

    @Test
    fun `an unmentioned edge is an empty one, not a failure`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).slots.covering(0)?.item)
        assertTrue(layout.edge(Dock.BOTTOM).isEmpty)
        assertEquals(Dock.BOTTOM.defaultSlots, layout.edge(Dock.BOTTOM).slots.slotCount)
    }

    @Test
    fun `the same bar twice keeps the first`() {
        val layout = assertNotNull(DockCodec.decode("v3|top:24:0=undo|top:24:0=redo"))
        assertEquals(ToolItem.UNDO, layout.edge(Dock.TOP).slots.covering(0)?.item)
        assertNull(layout.locate(ToolItem.REDO))
    }

    @Test
    fun `the same item on two bars keeps the first, so no control is in two places`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=eraser|top:24:4=eraser"))
        assertEquals("left", layout.locate(ToolItem.ERASER)?.bar?.id)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `an edge that claims a position keeps its contents and loses the position`() {
        // An edge's position is not the user's to choose, so the two numbers are
        // ignored rather than treated as a corrupt bar.
        val layout = assertNotNull(DockCodec.decode("v3|left@0.1,0.2:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).slots.covering(0)?.item)
        assertNull(layout.edge(Dock.LEFT).spot)
    }

    @Test
    fun `a floating bar with an unreadable position still lands somewhere`() {
        val layout = assertNotNull(DockCodec.decode("v3|f1@nope,0.5:8:0=stats"))
        assertEquals(1, layout.floating.size)
        assertNotNull(layout.floating.single().spot)
    }

    @Test
    fun `entries that cannot be placed are dropped, not fatal`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|bottom:12:9=size,0=smoothing,1=fit,10=stats"),
        )
        // size at 9 would run to 13, past the end of a 12-slot bar; fit at 1
        // overlaps smoothing, which holds 0..3.
        assertEquals(
            listOf(Placement(ToolItem.SMOOTHING, 0, 4), Placement(ToolItem.STATS, 10, 1)),
            layout.edge(Dock.BOTTOM).slots.placements,
        )
    }

    @Test
    fun `malformed entries are dropped one by one`() {
        val layout = assertNotNull(DockCodec.decode("v3|top:24:=pen,3=,4pen,x=fit,,7=clear"))
        assertEquals(
            listOf(Placement(ToolItem.CLEAR, 7, 1)),
            layout.edge(Dock.TOP).slots.placements,
        )
    }

    @Test
    fun `strings that are not a layout at all decode to null`() {
        for (bad in listOf(
            null,
            "",
            "nonsense",
            "v3",                       // no bars at all
            "v4|top:24:0=undo",         // a format from the future
            "v3|top|0=undo",            // the wrong separator inside a bar
            "v3|shelf:12:0=pen",        // not one readable bar
            "v3|top:x:0=undo",          // the slot count is not a number
            "v3|top:0:",                // a bar with no slots
            "v3|top:-4:",
            "v3|top:100000:",           // the ceiling, so a corrupt file is not an allocation
            "v1|nonsense",              // claims to be the oldest format and is not
            "v2|nonsense",
        )) {
            assertNull(DockCodec.decode(bad), "decoded <$bad>")
        }
    }
}
