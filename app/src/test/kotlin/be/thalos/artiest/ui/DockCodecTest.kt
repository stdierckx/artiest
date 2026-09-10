package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the surfaces survive on their way to disk and back.
 *
 * Most of these are not round trips. They are the strings a *different build*
 * writes — one control newer, one dock older, a slot count from a different
 * screen — and the assertion in every case is that the app still starts with
 * usable toolbars. See [DockCodec]'s KDoc for why that is a requirement rather
 * than politeness.
 *
 * There are now three older formats rather than two, and the newest of them —
 * `v3`, the one every install in the world is on — is the interesting one: a
 * bar of *n* slots becomes a strip *n* cells long along its dock's own axis,
 * which is the shape it always drew. Every `v3` case below asserts the shape as
 * well as the contents, because the shape is what the migration invents.
 */
class DockCodecTest {

    private fun Surface.at(slot: Int): CellPlacement? = slots.covering(cellAt(slot))

    @Test
    fun `the starter layout survives the round trip`() {
        val there = DockCodec.encode(DockLayout.STARTER)
        assertEquals(DockLayout.STARTER, DockCodec.decode(there))
    }

    @Test
    fun `a floating surface survives the round trip, with its position`() {
        val (layout, id) = DockLayout.STARTER.addFloating(
            ToolItem.COLOUR_PANEL, BarSpot(0.25f, 0.75f),
        )
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))
        assertEquals(BarSpot(0.25f, 0.75f), back.surface(id)?.spot)
        assertEquals(ToolItem.COLOUR_PANEL, back.surface(id)?.at(0)?.item)
        assertEquals(layout, back)
    }

    @Test
    fun `a shape survives the round trip`() {
        // The reason the format gained a version. A `v3` string has nowhere to
        // put an L, so writing one would have silently flattened it back into a
        // bar the first time the app was closed.
        val l = CellRegion.l(arm = 6, foot = 5)
        val layout = DockLayout.EMPTY
            .reshape("left", l)
            .reflow("left", FlowOrder.DOWN_THEN_RIGHT)
            .place("left", ToolItem.PEN, Cell(0, 0))
            .place("left", ToolItem.ZOOM_IN, Cell(3, 5))
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))

        assertEquals(l, back.edge(Dock.LEFT).region)
        assertEquals(FlowOrder.DOWN_THEN_RIGHT, back.edge(Dock.LEFT).flow)
        assertEquals(Cell(3, 5), back.locate(ToolItem.ZOOM_IN)?.cell)
        assertEquals(layout, back)
    }

    @Test
    fun `the encoding is the one written down in the KDoc`() {
        val layout = DockLayout.of(
            listOf(
                surfaceOf(Dock.LEFT, ToolItem.PEN to Cell(0, 0)),
                surfaceOf(Dock.BOTTOM, ToolItem.SIZE to Cell(4, 0)),
                floatingOf(BarSpot(0.32f, 0.45f), 8, ToolItem.COLOUR_PANEL to Cell(0, 0)),
            ),
        )
        assertEquals(
            "v4|left:R0,0,1,12:down_right:0,0=pen" +
                "|top:R0,0,24,1:right_down:" +
                "|right:R0,0,1,12:down_right:" +
                "|bottom:R0,0,24,1:right_down:4,0=size" +
                "|f1@0.32,0.45:R0,0,8,1:right_down:0,0=colour_panel@6x11",
            DockCodec.encode(layout),
        )
    }

    @Test
    fun `a size the user chose survives, and a bad one falls back to the catalogue`() {
        val kept = assertNotNull(
            DockCodec.decode("v4|f1@0.2,0.2:R0,0,12,1:right_down:0,0=colour_panel@9x4")
        )
        assertEquals(9, kept.floating.single().at(0)?.w)
        assertEquals(4, kept.floating.single().at(0)?.h)

        // A control at the wrong size is recoverable; one that is gone is not.
        val fallback = assertNotNull(
            DockCodec.decode("v4|f1@0.2,0.2:R0,0,12,1:right_down:0,0=colour_panel@nope")
        )
        assertEquals(6, fallback.floating.single().at(0)?.w)
        assertEquals(11, fallback.floating.single().at(0)?.h)
    }

    @Test
    fun `footprints are recomputed rather than stored`() {
        // The string says only which item is where. A release that resizes a
        // panel must not leave every saved layout laid out to the old number.
        val text = "v4|left:R0,0,1,12:down_right:0,0=colour_panel|top:R0,0,24,1:right_down:0,0=colour"
        val back = assertNotNull(DockCodec.decode(text))
        // Six by eleven on both, because a panel keeps its shape.
        assertEquals(6 to 11, back.edge(Dock.LEFT).at(0)?.let { it.w to it.h })
        assertEquals(1 to 1, back.edge(Dock.TOP).at(0)?.let { it.w to it.h })
    }

    @Test
    fun `an empty layout survives the round trip`() {
        assertEquals(DockLayout.EMPTY, DockCodec.decode(DockCodec.encode(DockLayout.EMPTY)))
    }

    // ---- the three older formats -----------------------------------------

    @Test
    fun `a slot count becomes the strip it always drew`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=pen,1=pencil|top:24:0=undo"))

        assertEquals(CellRegion.strip(12, Axis.VERTICAL), layout.edge(Dock.LEFT).region)
        assertEquals(CellRegion.strip(24, Axis.HORIZONTAL), layout.edge(Dock.TOP).region)
        assertEquals(Cell(0, 0), layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), layout.locate(ToolItem.PENCIL)?.cell, "down the left edge")
        assertEquals(Cell(0, 0), layout.locate(ToolItem.UNDO)?.cell)
    }

    @Test
    fun `a v3 slider on a side edge comes back standing up`() {
        // The one number the migration can get inverted, so it is pinned. In v3
        // a slider on the left edge was four slots *along* the bar and one deep;
        // in cells that is one wide and four tall, not the other way round.
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=size"))
        val p = assertNotNull(layout.locate(ToolItem.SIZE)?.placement)
        assertEquals(1, p.w)
        assertEquals(4, p.h)
    }

    @Test
    fun `a v3 panel size comes back the right way round`() {
        // v3 wrote along-by-across. On a vertical bar the two numbers swap on
        // the way into a width and a height.
        val layout = assertNotNull(DockCodec.decode("v3|f1@0.2,0.2:12:0=colour_panel@9x4"))
        val flat = assertNotNull(layout.floating.single().at(0))
        assertEquals(9, flat.w, "a floating bar ran horizontally")
        assertEquals(4, flat.h)

        val side = assertNotNull(DockCodec.decode("v3|left:12:0=colour_panel@9x4"))
        val upright = assertNotNull(side.edge(Dock.LEFT).at(0))
        assertEquals(4, upright.w, "on the left edge, across was the width")
        assertEquals(9, upright.h)
    }

    @Test
    fun `a bar saved before docking existed becomes the top edge`() {
        val layout = assertNotNull(DockCodec.decode("v1|24|0=pen,2=pencil,8=colour,12=size"))
        assertEquals("top", layout.locate(ToolItem.PEN)?.surface?.id)
        assertEquals(
            Cell(12, 0),
            layout.locate(ToolItem.SIZE)?.cell,
            "cells are absolute and did not move",
        )
        assertEquals(4, layout.locate(ToolItem.SIZE)?.placement?.w)
        for (dock in Dock.EDGES - Dock.TOP) {
            assertTrue(layout.edge(dock).isEmpty, dock.id)
        }
        assertTrue(layout.floating.isEmpty())
    }

    @Test
    fun `a short pre-docking bar is widened rather than kept full`() {
        val layout = assertNotNull(DockCodec.decode("v1|2|0=pen,1=pencil"))
        assertEquals(Dock.TOP.defaultSlots, layout.edge(Dock.TOP).slotCount)
        assertNotNull(layout.firstFit("top", ToolItem.ERASER))
    }

    @Test
    fun `the five-dock format becomes four edges and at most one floating surface`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|left:12:0=pen|top:24:0=undo|float:8:0=stats"),
        )
        assertEquals("left", layout.locate(ToolItem.PEN)?.surface?.id)
        assertEquals(1, layout.floating.size)
        assertEquals("f1", layout.floating.single().id)
        assertEquals(ToolItem.STATS, layout.floating.single().at(0)?.item)
        assertEquals(CellRegion.strip(8, Axis.HORIZONTAL), layout.floating.single().region)
    }

    @Test
    fun `an empty five-dock floating dock does not become a surface`() {
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
        val left = layout.edge(Dock.LEFT)
        assertEquals(ToolItem.PEN, left.at(0)?.item)
        assertNull(left.at(1))
        assertEquals(ToolItem.ERASER, left.at(2)?.item)
    }

    @Test
    fun `a surface this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|left:12:0=pen|shelf:12:0=eraser|top:24:0=undo"),
        )
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).at(0)?.item)
        assertEquals(ToolItem.UNDO, layout.edge(Dock.TOP).at(0)?.item)
        assertNull(layout.locate(ToolItem.ERASER))
    }

    @Test
    fun `an unmentioned edge is an empty one, not a failure`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).at(0)?.item)
        assertTrue(layout.edge(Dock.BOTTOM).isEmpty)
        assertEquals(Dock.BOTTOM.defaultSlots, layout.edge(Dock.BOTTOM).slotCount)
    }

    @Test
    fun `the same surface twice keeps the first`() {
        val layout = assertNotNull(DockCodec.decode("v3|top:24:0=undo|top:24:0=redo"))
        assertEquals(ToolItem.UNDO, layout.edge(Dock.TOP).at(0)?.item)
        assertNull(layout.locate(ToolItem.REDO))
    }

    @Test
    fun `the same item on two surfaces keeps the first, so no control is in two places`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=eraser|top:24:4=eraser"))
        assertEquals("left", layout.locate(ToolItem.ERASER)?.surface?.id)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `an edge that claims a position keeps its contents and loses the position`() {
        // An edge's position is not the user's to choose, so the two numbers are
        // ignored rather than treated as a corrupt surface.
        val layout = assertNotNull(DockCodec.decode("v3|left@0.1,0.2:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).at(0)?.item)
        assertNull(layout.edge(Dock.LEFT).spot)
    }

    @Test
    fun `a floating surface with an unreadable position still lands somewhere`() {
        val layout = assertNotNull(DockCodec.decode("v3|f1@nope,0.5:8:0=stats"))
        assertEquals(1, layout.floating.size)
        assertNotNull(layout.floating.single().spot)
    }

    @Test
    fun `entries that cannot be placed are dropped, not fatal`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|bottom:12:9=size,0=smoothing,1=fit,10=stats"),
        )
        // size at 9 would run to 13, past the end of a 12-cell bar; fit at 1
        // overlaps smoothing, which holds 0..3.
        assertEquals(
            listOf(
                CellPlacement(ToolItem.SMOOTHING, 0, 0, 4, 1),
                CellPlacement(ToolItem.STATS, 10, 0, 1, 1),
            ),
            layout.edge(Dock.BOTTOM).slots.placements,
        )
    }

    @Test
    fun `malformed entries are dropped one by one`() {
        val layout = assertNotNull(DockCodec.decode("v3|top:24:=pen,3=,4pen,x=fit,,7=clear"))
        assertEquals(
            listOf(CellPlacement(ToolItem.CLEAR, 7, 0, 1, 1)),
            layout.edge(Dock.TOP).slots.placements,
        )
        val four = assertNotNull(
            DockCodec.decode("v4|top:R0,0,24,1:right_down:=pen,3,=,4pen,x,0=fit,,7,0=clear")
        )
        assertEquals(
            listOf(CellPlacement(ToolItem.CLEAR, 7, 0, 1, 1)),
            four.edge(Dock.TOP).slots.placements,
        )
    }

    @Test
    fun `a rectangle off the grid is dropped and the shape keeps the rest`() {
        val layout = assertNotNull(
            DockCodec.decode("v4|left:R0,0,1,12+-4,0,2,2+0,0,999,999:down_right:0,0=pen")
        )
        assertEquals(CellRegion.strip(12, Axis.VERTICAL), layout.edge(Dock.LEFT).region)
        assertEquals(ToolItem.PEN, layout.edge(Dock.LEFT).at(0)?.item)
    }

    @Test
    fun `an unreadable flow falls back to the anchor's own`() {
        val layout = assertNotNull(
            DockCodec.decode("v4|left:R0,0,1,12:sideways:0,0=pen")
        )
        assertEquals(FlowOrder.DOWN_THEN_RIGHT, layout.edge(Dock.LEFT).flow)
    }

    @Test
    fun `strings that are not a layout at all decode to null`() {
        for (bad in listOf(
            null,
            "",
            "nonsense",
            "v4",                        // no surfaces at all
            "v5|top:R0,0,24,1:right_down:0,0=undo",  // a format from the future
            "v4|top|0,0=undo",           // the wrong separator inside a surface
            "v4|shelf:R0,0,12,1:right_down:0,0=pen", // not one readable surface
            "v4|top:24:right_down:0,0=undo",         // a slot count where a shape goes
            "v4|top:R:right_down:",                  // a shape with no rectangles
            "v4|top:R0,0,0,4:right_down:",           // a rectangle with no cells
            "v4|top:R0,0,99999,99999:right_down:",   // the ceiling, so a corrupt file
            "v3",                        // and the same, one format older
            "v3|top:x:0=undo",
            "v3|top:0:",
            "v3|top:-4:",
            "v3|top:100000:",
            "v1|nonsense",               // claims to be the oldest format and is not
            "v2|nonsense",
        )) {
            assertNull(DockCodec.decode(bad), "decoded <$bad>")
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun surfaceOf(dock: Dock, vararg at: Pair<ToolItem, Cell>): Surface =
        stripOf(dock.id, dock, null, dock.defaultSlots, dock.axis, at)

    private fun floatingOf(spot: BarSpot, length: Int, vararg at: Pair<ToolItem, Cell>): Surface =
        stripOf("f1", Dock.FLOATING, spot, length, Dock.FLOATING.axis, at)

    private fun stripOf(
        id: String,
        dock: Dock,
        spot: BarSpot?,
        length: Int,
        axis: Axis,
        at: Array<out Pair<ToolItem, Cell>>,
    ): Surface {
        val region = CellRegion.strip(length, axis)
        return Surface(
            id, dock, spot, dock.defaultFlow(),
            SurfaceLayout.of(
                region,
                at.map { (item, cell) ->
                    val (w, h) = RegionLayout.naturalSize(item, region, cell)
                    CellPlacement(item, cell.x, cell.y, w, h)
                },
            ),
        )
    }
}
