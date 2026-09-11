package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The arrangement rules, stated as tests.
 *
 * The UI plan made this file a stop condition: *"U4's `DockLayout` cannot be
 * tested on the JVM. Then the generalisation has picked up a rendering
 * dependency and the design is wrong."* Nothing here imports anything from
 * Compose or from `android.*`, which is the condition being met rather than
 * merely claimed — and it still holds now that a surface is a shape at a place
 * on the screen rather than a bar hanging off an edge.
 */
class DockLayoutTest {

    private val empty get() = DockLayout.EMPTY

    /** A plain bar of [length] cells with its corner at [at]. */
    private fun bar(
        id: String,
        at: Cell,
        length: Int,
        axis: Axis = Axis.HORIZONTAL,
    ): Surface = Surface(
        id = id,
        flow = FlowOrder.along(axis),
        slots = SurfaceLayout.empty(CellRegion.strip(length, axis).translated(at.x, at.y)),
    )

    /** Two bars at known places: a vertical one at 0,0 and a flat one at 4,0. */
    private val two: DockLayout
        get() = DockLayout.of(
            listOf(
                bar("a", Cell(0, 0), 12, Axis.VERTICAL),
                bar("b", Cell(4, 0), 12, Axis.HORIZONTAL),
            ),
        )

    private fun DockLayout.on(item: ToolItem): String? = locate(item)?.surface?.id

    private fun DockLayout.cellOf(item: ToolItem): Cell? = locate(item)?.cell

    // ---- footprints ------------------------------------------------------

    private fun sizeIn(axis: Axis, item: ToolItem): Pair<Int, Int> =
        RegionLayout.naturalSize(item, CellRegion.strip(12, axis), Cell(0, 0))

    @Test
    fun `a button is one cell whichever way the bar runs`() {
        assertEquals(1 to 1, sizeIn(Axis.HORIZONTAL, ToolItem.PEN))
        assertEquals(1 to 1, sizeIn(Axis.VERTICAL, ToolItem.PEN))
    }

    @Test
    fun `a slider turns with the shape, so it is always long along it`() {
        assertEquals(4 to 1, sizeIn(Axis.HORIZONTAL, ToolItem.SIZE))
        assertEquals(1 to 4, sizeIn(Axis.VERTICAL, ToolItem.SIZE))
    }

    @Test
    fun `a panel keeps its shape wherever it lands`() {
        // Six by eleven in both cases. This is the whole reason the footprint is
        // in screen terms: a colour wheel that were eleven by six on a flat bar
        // would owe every panel a responsive layout forever.
        assertEquals(6 to 11, sizeIn(Axis.HORIZONTAL, ToolItem.COLOUR_PANEL))
        assertEquals(6 to 11, sizeIn(Axis.VERTICAL, ToolItem.COLOUR_PANEL))
    }

    // ---- placing ---------------------------------------------------------

    @Test
    fun `an item lives in one place, so placing it elsewhere moves it`() {
        val one = two.place("a", ToolItem.PEN, Cell(0, 0))
        val moved = one.place("b", ToolItem.PEN, Cell(7, 0))

        assertTrue(moved.surface("a")!!.isEmpty, "the first surface let go of it")
        assertEquals("b", moved.on(ToolItem.PEN))
        assertEquals(Cell(7, 0), moved.cellOf(ToolItem.PEN))
        assertEquals(1, moved.all().size)
    }

    @Test
    fun `an item does not collide with itself when it moves within its own surface`() {
        val layout = two.place("b", ToolItem.SIZE, Cell(4, 0))
        assertTrue(layout.fits("b", ToolItem.SIZE, Cell(6, 0)))
        val moved = layout.place("b", ToolItem.SIZE, Cell(6, 0))
        assertEquals(1, moved.surface("b")!!.slots.placements.size)
        assertEquals(Cell(6, 0), moved.cellOf(ToolItem.SIZE))
    }

    @Test
    fun `a different item in the way still blocks`() {
        val layout = two
            .place("b", ToolItem.SIZE, Cell(4, 0))
            .place("b", ToolItem.SMOOTHING, Cell(8, 0))
        assertFalse(layout.fits("b", ToolItem.GRAIN, Cell(6, 0)), "6..9 runs into smoothing")
        assertTrue(layout.fits("b", ToolItem.GRAIN, Cell(12, 0)))
        assertFailsWith<IllegalArgumentException> { layout.place("b", ToolItem.GRAIN, Cell(6, 0)) }
    }

    @Test
    fun `a control must be inside the shape, corner to corner`() {
        // Nothing grows any more. A four-cell slider needs four cells of bar,
        // and the last three cells of a twelve-cell bar are not four cells.
        val flat = DockLayout.of(listOf(bar("b", Cell(0, 0), 12, Axis.HORIZONTAL)))
        assertTrue(flat.fits("b", ToolItem.SIZE, Cell(8, 0)))
        assertFalse(flat.fits("b", ToolItem.SIZE, Cell(9, 0)))
        assertFalse(flat.fits("b", ToolItem.SIZE, Cell(0, 1)), "there is no second row")
    }

    // ---- panels hang off --------------------------------------------------

    @Test
    fun `a panel needs one cell and hangs off the rest`() {
        // The user's report, as a test: "the panels are not selectable". They
        // needed a seven-by-eleven hole in the shape to stand in, which two
        // cells of the whole app had.
        val flat = DockLayout.of(listOf(bar("b", Cell(0, 0), 3, Axis.HORIZONTAL)))
        for (cell in listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0))) {
            assertTrue(flat.fits("b", ToolItem.LAYERS_PANEL, cell), "$cell")
            assertTrue(flat.fits("b", ToolItem.COLOUR_PANEL, cell), "$cell")
        }
        assertFalse(flat.fits("b", ToolItem.LAYERS_PANEL, Cell(3, 0)), "and not off the shape")
    }

    @Test
    fun `a panel still reserves the cells it covers on its own surface`() {
        // The overhang is over the drawing, which is nobody's; the part of it
        // that is over this surface is this surface's, and a button cannot be
        // drawn on top of a colour wheel.
        val block = DockLayout.of(
            listOf(
                Surface("b", FlowOrder.RIGHT_THEN_DOWN, SurfaceLayout.empty(CellRegion.block(8, 8))),
            ),
        ).place("b", ToolItem.COLOUR_PANEL, Cell(0, 0))
        assertFalse(block.fits("b", ToolItem.PEN, Cell(3, 3)))
        assertTrue(block.fits("b", ToolItem.PEN, Cell(7, 0)))
    }

    // ---- moving ----------------------------------------------------------

    @Test
    fun `move lands where it is asked to when there is room`() {
        val layout = two.place("b", ToolItem.UNDO, Cell(7, 0))
        val moved = assertNotNull(layout.move("b", Cell(7, 0), "a", Cell(0, 2)))
        assertEquals("a", moved.on(ToolItem.UNDO))
        assertEquals(Cell(0, 2), moved.cellOf(ToolItem.UNDO))
    }

    @Test
    fun `a drop onto an occupied cell slides to the nearest room rather than evicting`() {
        val layout = two
            .place("a", ToolItem.ZOOM_IN, Cell(0, 2))
            .place("b", ToolItem.FIT, Cell(4, 0))
        val moved = assertNotNull(layout.move("b", Cell(4, 0), "a", Cell(0, 2)))
        assertEquals(Cell(0, 0), moved.cellOf(ToolItem.FIT))
        assertEquals(Cell(0, 2), moved.cellOf(ToolItem.ZOOM_IN), "the one already there did not move")
    }

    @Test
    fun `a drop onto a surface with no room is refused rather than thrown`() {
        // A gesture that lands somewhere full is a normal thing for a hand to
        // do, so the answer is null and the caller keeps what it had.
        val tiny = DockLayout.of(
            listOf(bar("a", Cell(0, 0), 12, Axis.VERTICAL), bar("t", Cell(4, 0), 1)),
        ).place("t", ToolItem.STATS, Cell(4, 0))
        assertNull(tiny.move("a", Cell(0, 0), "t"), "nothing in the source cell")
        assertNull(tiny.move("t", Cell(4, 0), "nonesuch"), "no such surface")

        val full = DockLayout.of(listOf(bar("a", Cell(0, 0), 2), bar("b", Cell(6, 0), 1)))
            .place("a", ToolItem.PEN, Cell(0, 0))
            .place("a", ToolItem.PENCIL, Cell(1, 0))
            .place("b", ToolItem.ERASER, Cell(6, 0))
        assertNull(full.move("b", Cell(6, 0), "a"))
    }

    // ---- surfaces come and go --------------------------------------------

    @Test
    fun `fixate is three ordinary operations, and the first makes a surface`() {
        val (next, id) = empty.addSurface(ToolItem.COLOUR_PANEL, Cell(9, 4))
        val surface = assertNotNull(next.surface(id))
        assertEquals(Cell(9, 4), surface.origin)
        assertEquals(Cell(9, 4), next.cellOf(ToolItem.COLOUR_PANEL))
        // A panel is anchored to one cell and hangs off, so the surface under it
        // is that cell plus room to drop something else in beside it.
        assertEquals(3, surface.region.cellCount)
        assertEquals(1, next.surfaces.size)
    }

    @Test
    fun `a drawn surface gets its own name, and a closed one frees its name`() {
        val (a, first) = empty.addSurface(ToolItem.STATS, Cell(0, 0))
        val (b, second) = a.addSurface(ToolItem.CLEAR, Cell(0, 4))
        assertEquals("s1", first)
        assertEquals("s2", second)

        val closed = b.closeSurface(first)
        assertNull(closed.surface(first))
        assertNull(closed.locate(ToolItem.STATS), "what was on it went with it")
        assertNotNull(closed.locate(ToolItem.CLEAR), "the other one is untouched")

        val (c, third) = closed.addSurface(ToolItem.FIT, Cell(0, 8))
        assertEquals("s1", third, "the freed name is reused rather than climbing forever")
        assertEquals(2, c.surfaces.size)
    }

    @Test
    fun `a surface reshaped to nothing is closed rather than left as a ghost`() {
        val layout = two.place("a", ToolItem.PEN, Cell(0, 0))
        val gone = layout.reshape("a", CellRegion.EMPTY)
        assertNull(gone.surface("a"))
        assertNotNull(gone.surface("b"))
    }

    @Test
    fun `an emptied surface is tidied away when it is saved, not as it happens`() {
        val (next, id) = empty.addSurface(ToolItem.STATS, Cell(2, 2))
        val emptied = next.remove(id, Cell(2, 2))
        assertNotNull(emptied.surface(id), "it survives being empty for as long as the drag might")
        assertNull(emptied.tidied().surface(id))
    }

    @Test
    fun `moving a surface takes everything on it`() {
        val layout = two
            .place("a", ToolItem.PEN, Cell(0, 0))
            .place("a", ToolItem.PENCIL, Cell(0, 3))
        val moved = layout.moveSurface("a", Cell(5, 2))
        assertEquals(Cell(5, 2), moved.surface("a")?.origin)
        assertEquals(Cell(5, 2), moved.cellOf(ToolItem.PEN))
        assertEquals(Cell(5, 5), moved.cellOf(ToolItem.PENCIL))
        assertEquals(12, moved.surface("a")!!.region.cellCount, "and the shape is the same shape")
    }

    // ---- panels the user resized -----------------------------------------

    @Test
    fun `resizing a panel changes the placement and nothing else`() {
        val (next, id) = empty.addSurface(ToolItem.COLOUR_PANEL, Cell(0, 0))
        val bigger = next.resizePanel(id, Cell(0, 0), 9, 14)
        val placed = assertNotNull(bigger.surface(id)?.slots?.covering(Cell(0, 0)))
        assertEquals(9, placed.w)
        assertEquals(14, placed.h)
        assertEquals(
            next.surface(id)?.region,
            bigger.surface(id)?.region,
            "the shape it hangs off did not move",
        )
    }

    @Test
    fun `a resize is clamped rather than refused, and only a panel has one`() {
        val (next, id) = empty.addSurface(ToolItem.COLOUR_PANEL, Cell(0, 0))
        assertEquals(2, next.resizePanel(id, Cell(0, 0), -3, 4).locate(ToolItem.COLOUR_PANEL)?.placement?.w)
        assertEquals(24, next.resizePanel(id, Cell(0, 0), 900, 4).locate(ToolItem.COLOUR_PANEL)?.placement?.w)

        val buttons = two.place("b", ToolItem.STATS, Cell(4, 0))
        assertSame(buttons, buttons.resizePanel("b", Cell(4, 0), 6, 6), "a button is a button")
    }

    @Test
    fun `a chosen size follows the control onto a surface that runs the same way`() {
        val (next, id) = empty.addSurface(ToolItem.COLOUR_PANEL, Cell(0, 0))
        val sized = next.resizePanel(id, Cell(0, 0), 8, 9)
        val flat = DockLayout.of(sized.surfaces + bar("b", Cell(0, 6), 12, Axis.HORIZONTAL))
        val moved = flat.place("b", ToolItem.COLOUR_PANEL, Cell(0, 6))
        assertEquals(8, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.w)
        assertEquals(9, moved.locate(ToolItem.COLOUR_PANEL)?.placement?.h)

        // Turning it on its side has nothing sensible to carry, so it goes back
        // to what the catalogue says.
        val upright = DockLayout.of(sized.surfaces + bar("c", Cell(9, 0), 12, Axis.VERTICAL))
        val turned = upright.place("c", ToolItem.COLOUR_PANEL, Cell(9, 0))
        assertEquals(6, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.w)
        assertEquals(11, turned.locate(ToolItem.COLOUR_PANEL)?.placement?.h)
    }

    // ---- normalising ------------------------------------------------------

    @Test
    fun `of keeps the first copy of a duplicated item and drops the rest`() {
        fun withEraser(id: String, at: Cell) = Surface(
            id,
            FlowOrder.RIGHT_THEN_DOWN,
            SurfaceLayout.of(
                CellRegion.strip(4, Axis.HORIZONTAL).translated(at.x, at.y),
                listOf(CellPlacement(ToolItem.ERASER, at.x, at.y, 1, 1)),
            ),
        )
        val layout = DockLayout.of(
            listOf(withEraser("a", Cell(0, 0)), withEraser("b", Cell(0, 4))),
        )
        assertEquals("a", layout.on(ToolItem.ERASER), "the first surface listed wins")
        assertEquals(1, layout.all().size)
    }

    @Test
    fun `of drops a surface whose name is already taken`() {
        val layout = DockLayout.of(
            listOf(bar("a", Cell(0, 0), 4), bar("a", Cell(0, 4), 9)),
        )
        assertEquals(1, layout.surfaces.size)
        assertEquals(4, layout.surface("a")!!.region.cellCount, "the first one won")
    }

    @Test
    fun `contains and locate agree with each other`() {
        val layout = two.place("a", ToolItem.PENCIL, Cell(0, 1))
        assertTrue(ToolItem.PENCIL in layout)
        assertFalse(ToolItem.PEN in layout)
        assertEquals("a", layout.on(ToolItem.PENCIL))
        assertNull(layout.locate(ToolItem.PEN))
    }

    @Test
    fun `surfaceAt names the surface that owns a cell, and the later one wins`() {
        val layout = two
        assertEquals("a", layout.surfaceAt(Cell(0, 5))?.id)
        assertEquals("b", layout.surfaceAt(Cell(9, 0))?.id)
        assertNull(layout.surfaceAt(Cell(20, 9)), "bare paper")
        // 4,0 is on both: the bar at 4,0 runs across it and the upright one
        // does not reach. The overlap case is 0,0 -- a's first cell -- which b
        // does not cover, so build one that does.
        val over = DockLayout.of(layout.surfaces + bar("c", Cell(0, 0), 3))
        assertEquals("c", over.surfaceAt(Cell(0, 0))?.id, "the later one is the one you can see")
    }

    // ---- shapes ----------------------------------------------------------

    @Test
    fun `reshaping keeps what still fits and drops what does not`() {
        val layout = two
            .place("b", ToolItem.SIZE, Cell(4, 0))
            .place("b", ToolItem.SMOOTHING, Cell(8, 0))
            .place("b", ToolItem.GRAIN, Cell(12, 0))
        val shorter = layout.reshape("b", CellRegion.strip(6, Axis.HORIZONTAL).translated(4, 0))

        assertEquals(Cell(4, 0), layout.cellOf(ToolItem.SIZE))
        assertEquals(Cell(4, 0), shorter.cellOf(ToolItem.SIZE))
        assertNull(shorter.locate(ToolItem.SMOOTHING), "8..11 no longer reaches")
        assertNull(shorter.locate(ToolItem.GRAIN))
    }

    @Test
    fun `an L keeps its arm and its foot and fills round the corner`() {
        val l = CellRegion.l(arm = 6, foot = 5)
        var layout = DockLayout.of(
            listOf(Surface("l", FlowOrder.DOWN_THEN_RIGHT, SurfaceLayout.empty(l))),
        )
        for (item in listOf(
            ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.ERASER,
            ToolItem.COLOUR, ToolItem.LAYERS, ToolItem.ZOOM_IN,
        )) {
            layout = layout.place("l", item, assertNotNull(layout.firstFit("l", item)))
        }
        assertEquals(Cell(0, 0), layout.cellOf(ToolItem.PEN))
        assertEquals(Cell(0, 5), layout.cellOf(ToolItem.LAYERS), "the corner")
        assertEquals(Cell(1, 5), layout.cellOf(ToolItem.ZOOM_IN), "and into the foot")
    }

    @Test
    fun `a slider stands up in the arm and lies flat in the foot`() {
        val l = CellRegion.l(arm = 8, foot = 6)
        val surface = Surface("l", FlowOrder.DOWN_THEN_RIGHT, SurfaceLayout.empty(l))
        assertEquals(1 to 4, surface.footprintOf(ToolItem.SIZE, Cell(0, 0)))
        assertEquals(4 to 1, surface.footprintOf(ToolItem.SIZE, Cell(2, 7)))
    }

    @Test
    fun `tidy closes everything up and never loses a control`() {
        val layout = two
            .place("b", ToolItem.UNDO, Cell(6, 0))
            .place("b", ToolItem.REDO, Cell(11, 0))
        val tidied = layout.tidy("b")
        assertEquals(Cell(4, 0), tidied.cellOf(ToolItem.UNDO))
        assertEquals(Cell(5, 0), tidied.cellOf(ToolItem.REDO))
        assertEquals(2, tidied.all().size)
    }

    // ---- anchors ----------------------------------------------------------

    @Test
    fun `an anchored surface is put on the screen once and then forgets`() {
        val layout = DockLayout.of(
            listOf(DockLayout.anchored("s1", Side.RIGHT, 1, 3, ToolItem.FIT to Cell(0, 0))),
        )
        assertTrue(layout.hasAnchors)

        val settled = layout.settled(20, 10)
        assertFalse(settled.hasAnchors, "it does not get put anywhere twice")
        assertEquals(Cell(19, 3), settled.surface("s1")?.origin, "against the right, centred")
        assertEquals(Cell(19, 3), settled.cellOf(ToolItem.FIT))
        assertSame(settled, settled.settled(20, 10), "and settling again is a no-op")
    }

    @Test
    fun `every side goes where its name says`() {
        fun originOf(side: Side) = DockLayout
            .of(listOf(DockLayout.anchored("s1", side, 2, 2)))
            .settled(20, 10)
            .surface("s1")!!.origin

        assertEquals(0, originOf(Side.LEFT).x)
        assertEquals(18, originOf(Side.RIGHT).x)
        assertEquals(0, originOf(Side.TOP).y)
        assertEquals(8, originOf(Side.BOTTOM).y)
    }

    @Test
    fun `a fraction anchor reaches a corner, which a side cannot`() {
        // What Clean needs: an L whose corner points into the corner it is in.
        val corner = DockLayout.of(
            listOf(
                Surface(
                    "s1",
                    FlowOrder.DOWN_THEN_RIGHT,
                    SurfaceLayout.empty(CellRegion.l(arm = 3, foot = 2)),
                    Anchor.Spot(0f, 1f),
                ),
            ),
        ).settled(20, 10)
        assertEquals(Cell(0, 7), corner.surface("s1")?.origin)
    }

    // ---- the default -----------------------------------------------------

    @Test
    fun `the starter layout survives its own normalisation`() {
        val starter = DockLayout.STARTER
        assertEquals(20, starter.all().size)
        assertEquals(starter.all().size, starter.all().map { it.item }.toSet().size)
        assertTrue(starter.hasAnchors, "it has not met a screen yet")

        // The grouping is the feature, so it is pinned rather than left to
        // whatever the constant happens to say next month.
        for (item in listOf(
            ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.ERASER,
            ToolItem.COLOUR, ToolItem.MARQUEE,
        )) {
            assertEquals("s1", starter.on(item), item.id)
        }
        for (item in listOf(
            ToolItem.UNDO, ToolItem.REDO, ToolItem.IMPORT, ToolItem.EXPORT, ToolItem.STATS,
        )) {
            assertEquals("s2", starter.on(item), item.id)
        }
        for (item in listOf(
            ToolItem.ZOOM_IN, ToolItem.ZOOM_OUT, ToolItem.FIT, ToolItem.LAYERS,
            ToolItem.SELECTION,
        )) {
            assertEquals("s3", starter.on(item), item.id)
        }
        for (item in listOf(
            ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN, ToolItem.ERASER_SIZE,
        )) {
            assertEquals("s4", starter.on(item), item.id)
        }
    }

    @Test
    fun `the starter layout leaves a gap between groups`() {
        // The separators are load-bearing: they are the only thing saying that
        // the eraser and the colour are two ideas rather than a run of four.
        val starter = DockLayout.STARTER
        assertNull(starter.surface("s1")!!.slots.covering(Cell(0, 4)))
        assertNull(starter.surface("s2")!!.slots.covering(Cell(2, 0)))
    }

    @Test
    fun `the starter layout does not overlap itself on any screen worth having`() {
        // Two toolbars claiming the same cell is tolerated in a file and looks
        // like a fault on a screen. The default must not do it anywhere.
        for ((w, h) in listOf(12 to 8, 20 to 12, 24 to 12, 28 to 18, 18 to 28)) {
            val settled = DockLayout.STARTER.settled(w, h).fittedTo(w, h).layout
            val claimed = HashSet<Cell>()
            for (surface in settled.surfaces) {
                for (cell in surface.region.cells(FlowOrder.RIGHT_THEN_DOWN)) {
                    assertTrue(claimed.add(cell), "${w}x$h: two toolbars both claim $cell")
                }
            }
        }
    }

    @Test
    fun `the starter layout is whole on a tablet and says what a phone cannot show`() {
        assertTrue(DockLayout.STARTER.settled(24, 12).fittedTo(24, 12).isWhole)
        // Twelve by eight cells is 528 by 352dp, a small phone in landscape. The
        // four-slider bar is sixteen cells, so the last one goes to the chevron
        // rather than being left out of the default everywhere.
        val small = DockLayout.STARTER.settled(12, 8).fittedTo(12, 8)
        assertEquals(listOf(ToolItem.ERASER_SIZE), small.overflow["s4"])
        assertEquals(1, small.overflow.size, "and nothing else falls off")
    }

    @Test
    fun `the starter layout stands sliders up on an upright bar`() {
        val layout = two.place("a", ToolItem.SIZE, Cell(0, 0))
        val p = assertNotNull(layout.locate(ToolItem.SIZE)?.placement)
        assertEquals(1, p.w)
        assertEquals(4, p.h)
    }
}
