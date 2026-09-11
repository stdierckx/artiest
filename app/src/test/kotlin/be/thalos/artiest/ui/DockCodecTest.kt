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
 * There are four older formats now, and every one of them had docks. All four
 * come back **anchored**: the edge they named is remembered, honoured the first
 * time the layout is on a screen, and then forgotten. So the migration cases
 * below assert the anchor and the shape, and one of them follows the whole
 * thing through [DockLayout.settled] to real cells.
 */
class DockCodecTest {

    private fun Surface.at(cell: Cell): CellPlacement? = slots.covering(cell)

    private fun strip(
        id: String,
        at: Cell,
        length: Int,
        axis: Axis,
        vararg items: Pair<ToolItem, Cell>,
    ): Surface {
        val region = CellRegion.strip(length, axis).translated(at.x, at.y)
        return Surface(
            id = id,
            flow = FlowOrder.along(axis),
            slots = SurfaceLayout.of(
                region,
                items.map { (item, cell) ->
                    val (w, h) = RegionLayout.naturalSize(item, region, cell)
                    CellPlacement(item, cell.x, cell.y, w, h)
                },
            ),
        )
    }

    // ---- v5 ---------------------------------------------------------------

    @Test
    fun `the starter layout survives the round trip, anchors and all`() {
        val there = DockCodec.encode(DockLayout.STARTER)
        assertEquals(DockLayout.STARTER, DockCodec.decode(there))
        assertTrue(assertNotNull(DockCodec.decode(there)).hasAnchors)
    }

    @Test
    fun `a settled layout survives the round trip, and stays settled`() {
        val settled = DockLayout.STARTER.settled(28, 18)
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(settled)))
        assertEquals(settled, back)
        assertTrue(!back.hasAnchors, "it has been on a screen and is not put anywhere again")
    }

    @Test
    fun `a shape survives the round trip, where it was drawn`() {
        // The reason the format gained a version twice: a `v3` string has
        // nowhere to put an L, and a `v4` one has nowhere to put its corner.
        val l = CellRegion.l(arm = 6, foot = 5).translated(3, 9)
        val layout = DockLayout.of(
            listOf(
                Surface(
                    "s1",
                    FlowOrder.DOWN_THEN_RIGHT,
                    SurfaceLayout.of(
                        l,
                        listOf(
                            CellPlacement(ToolItem.PEN, 3, 9, 1, 1),
                            CellPlacement(ToolItem.ZOOM_IN, 6, 14, 1, 1),
                        ),
                    ),
                ),
            ),
        )
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))

        assertEquals(l, back.surface("s1")?.region)
        assertEquals(FlowOrder.DOWN_THEN_RIGHT, back.surface("s1")?.flow)
        assertEquals(Cell(6, 14), back.locate(ToolItem.ZOOM_IN)?.cell)
        assertEquals(layout, back)
    }

    @Test
    fun `the encoding is the one written down in the KDoc`() {
        val layout = DockLayout.of(
            listOf(
                strip("s1", Cell(0, 0), 12, Axis.VERTICAL, ToolItem.PEN to Cell(0, 0)),
                strip("s2", Cell(0, 17), 24, Axis.HORIZONTAL, ToolItem.SIZE to Cell(4, 17)),
                strip(
                    "s3", Cell(10, 5), 8, Axis.HORIZONTAL,
                    ToolItem.COLOUR_PANEL to Cell(10, 5),
                ),
            ),
        )
        assertEquals(
            "v5|s1:R0,0,1,12:down_right:0,0=pen" +
                "|s2:R0,17,24,1:right_down:4,17=size" +
                "|s3:R10,5,8,1:right_down:10,5=colour_panel@6x11",
            DockCodec.encode(layout),
        )
    }

    @Test
    fun `an anchor is written and read back`() {
        val anchored = DockLayout.of(
            listOf(
                DockLayout.anchored("s1", Side.BOTTOM, 4, 1, ToolItem.SIZE to Cell(0, 0)),
                Surface(
                    "s2",
                    FlowOrder.RIGHT_THEN_DOWN,
                    SurfaceLayout.empty(CellRegion.strip(2, Axis.HORIZONTAL)),
                    Anchor.Spot(0.32f, 0.45f),
                ),
            ),
        )
        assertEquals(
            "v5|s1^bottom:R0,0,4,1:right_down:0,0=size" +
                "|s2^@0.32,0.45:R0,0,2,1:right_down:",
            DockCodec.encode(anchored),
        )
        assertEquals(anchored, DockCodec.decode(DockCodec.encode(anchored)))
    }

    @Test
    fun `a size the user chose survives, and a bad one falls back to the catalogue`() {
        val kept = assertNotNull(
            DockCodec.decode("v5|s1:R0,0,12,1:right_down:0,0=colour_panel@9x4")
        )
        assertEquals(9, kept.surface("s1")?.at(Cell(0, 0))?.w)
        assertEquals(4, kept.surface("s1")?.at(Cell(0, 0))?.h)

        // A control at the wrong size is recoverable; one that is gone is not.
        val fallback = assertNotNull(
            DockCodec.decode("v5|s1:R0,0,12,1:right_down:0,0=colour_panel@nope")
        )
        assertEquals(6, fallback.surface("s1")?.at(Cell(0, 0))?.w)
        assertEquals(11, fallback.surface("s1")?.at(Cell(0, 0))?.h)
    }

    @Test
    fun `footprints are recomputed rather than stored`() {
        // The string says only which item is where. A release that resizes a
        // panel must not leave every saved layout laid out to the old number.
        val back = assertNotNull(
            DockCodec.decode(
                "v5|s1:R0,0,1,12:down_right:0,0=colour_panel" +
                    "|s2:R2,0,24,1:right_down:2,0=colour"
            )
        )
        // Six by eleven, because a panel keeps its shape wherever it is.
        assertEquals(6 to 11, back.surface("s1")?.at(Cell(0, 0))?.let { it.w to it.h })
        assertEquals(1 to 1, back.surface("s2")?.at(Cell(2, 0))?.let { it.w to it.h })
    }

    @Test
    fun `a layout with nothing on it survives the round trip`() {
        // Every toolbar rubbed out is a choice, not a corrupt string, and the
        // one user who has most deliberately got rid of them is the one this
        // must not hand the starter layout back to.
        assertEquals(DockLayout.EMPTY, DockCodec.decode(DockCodec.encode(DockLayout.EMPTY)))
    }

    @Test
    fun `the string a real tablet wrote after migrating reads back whole`() {
        // Copied off the DTH-A116 the first time the grid build opened a v4
        // preference. It is here because the failure mode is silent: a string
        // that will not decode is replaced by the starter layout, and the only
        // sign is that somebody's toolbars are suddenly the factory ones.
        val stored =
            "v5|left:R0,5,1,7:down_right:0,5=pen,0,6=pencil,0,7=marker,0,9=eraser,0,11=colour" +
                "|top:R13,0,2,1:right_down:13,0=undo,14,0=redo" +
                "|right:R27,7,1,2:down_right:27,7=layers,27,8=fit" +
                "|bottom:R7,16,14,1:right_down:7,16=size,12,16=smoothing,17,16=eraser_size" +
                "|f1:R24,8,4,1:right_down:24,8=layers_panel@4x12"
        val layout = assertNotNull(DockCodec.decode(stored), "it fell back to the starter")

        assertEquals(5, layout.surfaces.size)
        assertEquals(13, layout.all().size)
        assertEquals(Cell(0, 5), layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(24, 8), layout.locate(ToolItem.LAYERS_PANEL)?.cell)
        assertEquals(4 to 12, layout.locate(ToolItem.LAYERS_PANEL)?.placement?.let { it.w to it.h })
        assertTrue(!layout.hasAnchors, "it had already been on a screen")
        assertEquals(stored, DockCodec.encode(layout), "and it goes back out unchanged")
    }

    // ---- the four older formats -------------------------------------------

    @Test
    fun `a v4 shape comes back anchored to the edge it was docked to`() {
        val layout = assertNotNull(
            DockCodec.decode("v4|left:R0,0,1,12:down_right:0,0=pen,0,1=pencil")
        )
        val left = assertNotNull(layout.surface("left"))
        assertEquals(Anchor.Edge(Side.LEFT), left.anchor)
        // Two cells, not twelve: a bar was as long as its dock said, and the
        // renderer of the day drew only as far as the last control on it.
        assertEquals(CellRegion.strip(2, Axis.VERTICAL), left.region)
        assertEquals(Cell(0, 1), layout.locate(ToolItem.PENCIL)?.cell)

        // And the whole way through: on a twenty-by-ten screen it is against the
        // left, centred down it, and it is cells from then on.
        val settled = layout.settled(20, 10)
        assertEquals(Cell(0, 4), assertNotNull(settled.surface("left")).origin)
    }

    @Test
    fun `a v4 floating surface keeps its fraction as an anchor`() {
        val layout = assertNotNull(
            DockCodec.decode("v4|f1@0.5,0.25:R0,0,8,1:right_down:0,0=stats")
        )
        assertEquals(Anchor.Spot(0.5f, 0.25f), layout.surface("f1")?.anchor)
        val settled = layout.settled(20, 10)
        assertEquals(Cell(10, 2), assertNotNull(settled.surface("f1")).origin)
    }

    @Test
    fun `a slot count becomes the strip it always drew`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=pen,1=pencil|top:24:0=undo"))

        assertEquals(CellRegion.strip(2, Axis.VERTICAL), layout.surface("left")?.region)
        assertEquals(CellRegion.strip(1, Axis.HORIZONTAL), layout.surface("top")?.region)
        assertEquals(Anchor.Edge(Side.TOP), layout.surface("top")?.anchor)
        assertEquals(Cell(0, 0), layout.locate(ToolItem.PEN)?.cell)
        assertEquals(Cell(0, 1), layout.locate(ToolItem.PENCIL)?.cell, "down the left edge")
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
        val flat = assertNotNull(DockCodec.decode("v3|f1@0.2,0.2:12:0=colour_panel@9x4"))
        assertEquals(9, flat.surface("f1")?.at(Cell(0, 0))?.w, "a floating bar ran horizontally")
        assertEquals(4, flat.surface("f1")?.at(Cell(0, 0))?.h)

        val side = assertNotNull(DockCodec.decode("v3|left:12:0=colour_panel@9x4"))
        assertEquals(4, side.surface("left")?.at(Cell(0, 0))?.w, "across was the width")
        assertEquals(9, side.surface("left")?.at(Cell(0, 0))?.h)
    }

    @Test
    fun `a bar saved before docking existed becomes a bar across the top`() {
        val layout = assertNotNull(DockCodec.decode("v1|24|0=pen,2=pencil,8=colour,12=size"))
        assertEquals("top", layout.locate(ToolItem.PEN)?.surface?.id)
        assertEquals(Anchor.Edge(Side.TOP), layout.surface("top")?.anchor)
        assertEquals(
            Cell(12, 0),
            layout.locate(ToolItem.SIZE)?.cell,
            "cells are relative to the bar's own corner, and did not move",
        )
        assertEquals(4, layout.locate(ToolItem.SIZE)?.placement?.w)
        assertEquals(1, layout.surfaces.size)
    }

    @Test
    fun `the five-dock format becomes one surface per dock that had something on it`() {
        val layout = assertNotNull(
            DockCodec.decode("v2|left:12:0=pen|top:24:0=undo|float:8:0=stats"),
        )
        assertEquals("left", layout.locate(ToolItem.PEN)?.surface?.id)
        assertEquals(3, layout.surfaces.size)
        assertEquals(ToolItem.STATS, layout.surface("f1")?.at(Cell(0, 0))?.item)
        assertEquals(CellRegion.strip(1, Axis.HORIZONTAL), layout.surface("f1")?.region)
    }

    @Test
    fun `an empty five-dock floating dock does not become a surface`() {
        // In v2 the floating dock always existed whether or not anything was on
        // it. An empty one is not a bar somebody made, so it is not carried over.
        val layout = assertNotNull(DockCodec.decode("v2|left:12:0=pen|float:8:"))
        assertNull(layout.surface("f1"))
    }

    // ---- best effort -----------------------------------------------------

    @Test
    fun `an id this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|left:12:0=pen,1=perspective_grid,2=eraser"),
        )
        val left = assertNotNull(layout.surface("left"))
        assertEquals(ToolItem.PEN, left.at(Cell(0, 0))?.item)
        assertNull(left.at(Cell(0, 1)))
        assertEquals(ToolItem.ERASER, left.at(Cell(0, 2))?.item)
    }

    @Test
    fun `a dock this build has never heard of is dropped and the rest is kept`() {
        val layout = assertNotNull(
            DockCodec.decode("v3|left:12:0=pen|shelf:12:0=eraser|top:24:0=undo"),
        )
        assertEquals(ToolItem.PEN, layout.surface("left")?.at(Cell(0, 0))?.item)
        assertEquals(ToolItem.UNDO, layout.surface("top")?.at(Cell(0, 0))?.item)
        assertNull(layout.locate(ToolItem.ERASER))
    }

    @Test
    fun `a surface nobody mentioned simply is not there, and nor is an empty one`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=pen|right:12:"))
        assertEquals(1, layout.surfaces.size)
        assertNull(layout.surface("bottom"))
        assertNull(layout.surface("right"), "an empty dock was a dock, not a toolbar")
    }

    @Test
    fun `the same surface twice keeps the first`() {
        val layout = assertNotNull(DockCodec.decode("v5|s1:R0,0,24,1:right_down:0,0=undo|s1:R0,4,24,1:right_down:0,4=redo"))
        assertEquals(ToolItem.UNDO, layout.surface("s1")?.at(Cell(0, 0))?.item)
        assertNull(layout.locate(ToolItem.REDO))
    }

    @Test
    fun `the same item on two surfaces keeps the first, so no control is in two places`() {
        val layout = assertNotNull(DockCodec.decode("v3|left:12:0=eraser|top:24:4=eraser"))
        assertEquals("left", layout.locate(ToolItem.ERASER)?.surface?.id)
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `an edge that claims a position keeps its contents and is anchored to the edge`() {
        // An edge's position was never the user's to choose, so the two numbers
        // are ignored rather than treated as a corrupt surface.
        val layout = assertNotNull(DockCodec.decode("v3|left@0.1,0.2:12:0=pen"))
        assertEquals(ToolItem.PEN, layout.surface("left")?.at(Cell(0, 0))?.item)
        assertEquals(Anchor.Edge(Side.LEFT), layout.surface("left")?.anchor)
    }

    @Test
    fun `a floating surface with an unreadable position still lands somewhere`() {
        val layout = assertNotNull(DockCodec.decode("v3|f1@nope,0.5:8:0=stats"))
        assertNotNull(layout.surface("f1")?.anchor)
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
            layout.surface("bottom")?.slots?.placements,
        )
    }

    @Test
    fun `malformed entries are dropped one by one`() {
        val layout = assertNotNull(DockCodec.decode("v3|top:24:=pen,3=,4pen,x=fit,,7=clear"))
        assertEquals(
            listOf(CellPlacement(ToolItem.CLEAR, 7, 0, 1, 1)),
            layout.surface("top")?.slots?.placements,
        )
        val five = assertNotNull(
            DockCodec.decode("v5|s1:R0,0,24,1:right_down:=pen,3,=,4pen,x,0=fit,,7,0=clear")
        )
        assertEquals(
            listOf(CellPlacement(ToolItem.CLEAR, 7, 0, 1, 1)),
            five.surface("s1")?.slots?.placements,
        )
    }

    @Test
    fun `a rectangle off the grid is dropped and the shape keeps the rest`() {
        val layout = assertNotNull(
            DockCodec.decode("v5|s1:R0,0,1,12+-4,0,2,2+0,0,999,999:down_right:0,0=pen")
        )
        assertEquals(CellRegion.strip(12, Axis.VERTICAL), layout.surface("s1")?.region)
        assertEquals(ToolItem.PEN, layout.surface("s1")?.at(Cell(0, 0))?.item)
    }

    @Test
    fun `an unreadable flow falls back to the way the shape runs`() {
        val layout = assertNotNull(DockCodec.decode("v5|s1:R0,0,1,12:sideways:0,0=pen"))
        assertEquals(FlowOrder.DOWN_THEN_RIGHT, layout.surface("s1")?.flow)
    }

    @Test
    fun `an unreadable anchor drops the anchor and keeps the toolbar`() {
        val layout = assertNotNull(DockCodec.decode("v5|s1^sideways:R0,0,1,12:down_right:0,0=pen"))
        assertNull(layout.surface("s1")?.anchor)
        assertEquals(ToolItem.PEN, layout.surface("s1")?.at(Cell(0, 0))?.item)
    }

    @Test
    fun `strings that are not a layout at all decode to null`() {
        for (bad in listOf(
            null,
            "",
            "nonsense",
            "v6|s1:R0,0,24,1:right_down:0,0=undo",  // a format from the future
            "v5|s1|0,0=undo",            // the wrong separator inside a surface
            "v5|:R0,0,12,1:right_down:", // a surface with no name
            "v5|s1:24:right_down:0,0=undo",         // a slot count where a shape goes
            "v5|s1:R:right_down:",                  // a shape with no rectangles
            "v5|s1:R0,0,0,4:right_down:",           // a rectangle with no cells
            "v5|s1:R0,0,99999,99999:right_down:",   // the ceiling, so a corrupt file
            "v4",                        // and the same, one format older
            "v4|shelf:R0,0,12,1:right_down:0,0=pen",
            "v3",
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
}
