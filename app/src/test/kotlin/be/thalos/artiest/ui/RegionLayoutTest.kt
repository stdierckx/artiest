package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shape, and filling it. The nine rules U1 of `docs/ui-expansion-plan.md`
 * names, one test each, plus the arithmetic they lean on.
 *
 * Test 1 is the one that matters most: **a strip packs exactly the way
 * `ToolbarLayout` does today.** It is the evidence that the new engine is the
 * old engine plus a dimension, and it is what licenses U2 to delete a file
 * rather than keep two answers to "does this fit".
 */
class RegionLayoutTest {

    private fun cells(vararg xy: Pair<Int, Int>) = xy.map { Cell(it.first, it.second) }

    private fun flow(vararg items: ToolItem) = items.map { RegionLayout.Footprint.of(it) }

    // ---- 1. the equivalence that licenses the deletion ----------------------

    @Test
    fun `a strip packs exactly as ToolbarLayout does`() {
        val items = listOf(
            ToolItem.PEN, ToolItem.SIZE, ToolItem.PENCIL,
            ToolItem.SMOOTHING, ToolItem.ERASER, ToolItem.GRAIN,
        )

        for (axis in Axis.entries) {
            // What the old engine does: first fit, in order, along one line.
            var old = ToolbarLayout.empty(12)
            val oldSlots = LinkedHashMap<ToolItem, Int>()
            val oldOverflow = ArrayList<ToolItem>()
            for (item in items) {
                val span = item.slotsIn(axis)
                val slot = old.firstFit(span)
                if (slot == null) {
                    oldOverflow += item
                    continue
                }
                old = old.place(Placement(item, slot, span, item.depthIn(axis)))
                oldSlots[item] = slot
            }

            val fill = RegionLayout.pack(
                region = CellRegion.strip(12, axis),
                flow = FlowOrder.along(axis),
                flowing = flow(*items.toTypedArray()),
            )

            assertEquals(oldOverflow, fill.overflow, "$axis: the same things do not fit")
            assertEquals(oldSlots.size, fill.placements.size, "$axis")
            for (p in fill.placements) {
                val slot = if (axis == Axis.HORIZONTAL) p.x else p.y
                assertEquals(oldSlots[p.item], slot, "$axis: ${p.item.id}")
                assertEquals(p.item.slotsIn(axis), if (axis == Axis.HORIZONTAL) p.w else p.h)
                assertEquals(p.item.depthIn(axis), if (axis == Axis.HORIZONTAL) p.h else p.w)
            }
        }
    }

    // ---- 2. an L fills round its corner -------------------------------------

    @Test
    fun `an L fills the arm then the foot with no gap at the corner`() {
        // Eight tall, six wide, one thick: thirteen cells with the corner shared.
        val l = CellRegion.l(arm = 8, foot = 6)
        assertEquals(13, l.cellCount)

        val fill = RegionLayout.pack(
            region = l,
            flow = FlowOrder.DOWN_THEN_RIGHT,
            flowing = flow(
                ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.ERASER,
                ToolItem.COLOUR, ToolItem.UNDO, ToolItem.REDO, ToolItem.LAYERS,
                ToolItem.ZOOM_IN, ToolItem.ZOOM_OUT,
            ),
        )

        assertTrue(fill.isComplete)
        // Eight down the arm, including the corner, then two along the foot.
        val expected = cells(
            0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5, 0 to 6, 0 to 7,
            1 to 7, 2 to 7,
        )
        assertEquals(expected, fill.placements.map { it.cell }.sortedWith(compareBy({ it.x }, { it.y })))
    }

    // ---- 3. a slider does not turn a corner ---------------------------------

    @Test
    fun `a four cell slider will not turn the corner`() {
        val l = CellRegion.l(arm = 8, foot = 6)
        val fill = RegionLayout.pack(l, FlowOrder.DOWN_THEN_RIGHT, flowing = flow(ToolItem.SIZE))
        val p = fill.placements.single()
        // Upright in the arm: one wide, four tall, starting at the top.
        assertEquals(CellPlacement(ToolItem.SIZE, 0, 0, 1, 4), p)
        // And never straddling the bend: every cell it covers is in the arm.
        for (y in p.y until p.bottom) assertTrue(l.contains(p.x, y))
    }

    @Test
    fun `a slider in the foot lies flat`() {
        // Fill the arm with buttons so the slider has to go round the corner.
        val l = CellRegion.l(arm = 4, foot = 6)
        val fill = RegionLayout.pack(
            region = l,
            flow = FlowOrder.DOWN_THEN_RIGHT,
            flowing = flow(
                ToolItem.PEN, ToolItem.PENCIL, ToolItem.MARKER, ToolItem.ERASER, ToolItem.SIZE,
            ),
        )
        val slider = fill.placements.single { it.item == ToolItem.SIZE }
        assertEquals(4, slider.w, "flat along the foot")
        assertEquals(1, slider.h)
        assertEquals(3, slider.y, "on the bottom row")
    }

    // ---- 4. overflow does not stop what comes after -------------------------

    @Test
    fun `an item one cell too wide overflows and the rest still place`() {
        val fill = RegionLayout.pack(
            region = CellRegion.strip(3, Axis.HORIZONTAL),
            flow = FlowOrder.RIGHT_THEN_DOWN,
            flowing = flow(ToolItem.PEN, ToolItem.SIZE, ToolItem.PENCIL, ToolItem.ERASER),
        )
        assertEquals(listOf(ToolItem.SIZE), fill.overflow)
        assertEquals(
            listOf(ToolItem.PEN, ToolItem.PENCIL, ToolItem.ERASER),
            fill.placements.sortedBy { it.x }.map { it.item },
        )
    }

    @Test
    fun `overflow is in input order`() {
        val fill = RegionLayout.pack(
            region = CellRegion.strip(1, Axis.HORIZONTAL),
            flow = FlowOrder.RIGHT_THEN_DOWN,
            flowing = flow(ToolItem.SIZE, ToolItem.PEN, ToolItem.SMOOTHING, ToolItem.GRAIN),
        )
        assertEquals(
            listOf(ToolItem.SIZE, ToolItem.SMOOTHING, ToolItem.GRAIN),
            fill.overflow,
        )
    }

    // ---- 5. nothing is ever placed across a hole ----------------------------

    @Test
    fun `a C never places across its opening`() {
        // Three rectangles making a C: two cells wide, with the middle row open.
        val c = CellRegion.of(
            listOf(CellRect(0, 0, 4, 1), CellRect(0, 0, 1, 3), CellRect(0, 2, 4, 1))
        )
        assertFalse(c.contains(2, 1), "the mouth of the C is a hole")

        val fill = RegionLayout.pack(
            region = c,
            flow = FlowOrder.RIGHT_THEN_DOWN,
            flowing = flow(ToolItem.SIZE),
        )
        // A four-cell slider fits the top row and the bottom row, never the middle.
        val p = fill.placements.single()
        assertTrue(p.y == 0 || p.y == 2)
        for (cx in p.x until p.right) for (cy in p.y until p.bottom) {
            assertTrue(c.contains(cx, cy), "$cx,$cy is outside the C")
        }
    }

    @Test
    fun `a fixed placement outside the shape drops to overflow`() {
        val fill = RegionLayout.pack(
            region = CellRegion.strip(4, Axis.HORIZONTAL),
            flow = FlowOrder.RIGHT_THEN_DOWN,
            fixed = listOf(
                CellPlacement(ToolItem.PEN, 0, 0, 1, 1),
                CellPlacement(ToolItem.PENCIL, 9, 0, 1, 1),  // off the end
                CellPlacement(ToolItem.ERASER, 0, 0, 1, 1),  // on top of the pen
            ),
        )
        assertEquals(listOf(ToolItem.PEN), fill.placements.map { it.item })
        assertEquals(listOf(ToolItem.PENCIL, ToolItem.ERASER), fill.overflow)
    }

    @Test
    fun `a fixed placement is honoured and the flow goes round it`() {
        val fill = RegionLayout.pack(
            region = CellRegion.strip(6, Axis.HORIZONTAL),
            flow = FlowOrder.RIGHT_THEN_DOWN,
            fixed = listOf(CellPlacement(ToolItem.EXPORT, 5, 0, 1, 1)),
            flowing = flow(ToolItem.SIZE, ToolItem.PEN),
        )
        assertTrue(fill.isComplete)
        assertEquals(0, fill.placements.single { it.item == ToolItem.SIZE }.x)
        assertEquals(4, fill.placements.single { it.item == ToolItem.PEN }.x)
        assertEquals(5, fill.placements.single { it.item == ToolItem.EXPORT }.x)
    }

    // ---- 6 and 7. coalescing ------------------------------------------------

    @Test
    fun `a hand drawn L coalesces to exactly two rectangles`() {
        val drawn = cells(
            0 to 0, 0 to 1, 0 to 2, 0 to 3,
            1 to 3, 2 to 3, 3 to 3,
        )
        val region = CellRegion.ofCells(drawn)
        assertEquals(listOf(CellRect(0, 0, 1, 3), CellRect(0, 3, 4, 1)), region.rects)
        assertEquals(7, region.cellCount)
    }

    @Test
    fun `coalescing is stable when one cell is added`() {
        val block = CellRegion.of(CellRect(0, 0, 3, 3))
        assertEquals(listOf(CellRect(0, 0, 3, 3)), block.rects)

        val grown = block.plus(cells(1 to 3))
        assertEquals(
            listOf(CellRect(0, 0, 3, 3), CellRect(1, 3, 1, 1)),
            grown.rects,
            "the block it grew from is untouched",
        )
    }

    @Test
    fun `two ways of writing one shape are equal`() {
        val a = CellRegion.of(listOf(CellRect(0, 0, 4, 1), CellRect(0, 0, 1, 4)))
        val b = CellRegion.of(listOf(CellRect(0, 0, 1, 4), CellRect(1, 0, 3, 1)))
        assertEquals(a, b)
        assertEquals(a.rects, b.rects, "and both are stored the same way")
    }

    @Test
    fun `an overlapping pile of rectangles is one solid block`() {
        val piled = CellRegion.of(
            listOf(CellRect(0, 0, 3, 2), CellRect(1, 0, 3, 2), CellRect(2, 0, 2, 2))
        )
        assertEquals(listOf(CellRect(0, 0, 4, 2)), piled.rects)
        assertEquals(8, piled.cellCount)
    }

    // ---- 8. clamping --------------------------------------------------------

    @Test
    fun `clamping keeps every cell that exists and no others`() {
        val wide = CellRegion.strip(24, Axis.HORIZONTAL)
        val phone = wide.clampedTo(12, 8)
        assertEquals(listOf(CellRect(0, 0, 12, 1)), phone.rects)
        assertEquals(wide, wide.clampedTo(40, 8), "a shape that fits is not touched")
        assertTrue(wide.clampedTo(0, 8).isEmpty)
    }

    @Test
    fun `clamping an L keeps its corner`() {
        val l = CellRegion.l(arm = 12, foot = 8)
        val small = l.clampedTo(4, 12)
        assertTrue(small.contains(0, 11), "the corner is still there")
        assertFalse(small.contains(4, 11), "and the foot is shorter")
        assertEquals(15, small.cellCount)
    }

    // ---- 9. it is a function ------------------------------------------------

    @Test
    fun `packing twice gives the same answer`() {
        val region = CellRegion.u(width = 6, height = 5)
        val items = flow(
            ToolItem.PEN, ToolItem.SIZE, ToolItem.PENCIL, ToolItem.SMOOTHING,
            ToolItem.ERASER, ToolItem.COLOUR, ToolItem.LAYERS, ToolItem.GRAIN,
        )
        val once = RegionLayout.pack(region, FlowOrder.DOWN_THEN_RIGHT, flowing = items)
        val twice = RegionLayout.pack(region, FlowOrder.DOWN_THEN_RIGHT, flowing = items)
        assertEquals(once, twice)
    }

    @Test
    fun `an empty region places nothing and overflows everything`() {
        val fill = RegionLayout.pack(
            region = CellRegion.EMPTY,
            flow = FlowOrder.RIGHT_THEN_DOWN,
            fixed = listOf(CellPlacement(ToolItem.PEN, 0, 0, 1, 1)),
            flowing = flow(ToolItem.PENCIL),
        )
        assertTrue(fill.placements.isEmpty())
        assertEquals(listOf(ToolItem.PEN, ToolItem.PENCIL), fill.overflow)
    }

    // ---- which way a control lies -------------------------------------------

    @Test
    fun `a strip's local axis is the way it runs`() {
        assertEquals(Axis.VERTICAL, CellRegion.strip(12, Axis.VERTICAL).localAxis(0, 5))
        assertEquals(Axis.HORIZONTAL, CellRegion.strip(24, Axis.HORIZONTAL).localAxis(9, 0))
    }

    @Test
    fun `an L is upright in the arm and flat along the foot`() {
        val l = CellRegion.l(arm = 8, foot = 6)
        assertEquals(Axis.VERTICAL, l.localAxis(0, 2), "up the arm")
        assertEquals(Axis.HORIZONTAL, l.localAxis(4, 7), "along the foot")
    }

    @Test
    fun `a cell outside the shape has no opinion`() {
        assertEquals(Axis.HORIZONTAL, CellRegion.l(4, 4).localAxis(3, 0))
    }

    // ---- the bar that grows, and the shape that does not --------------------

    @Test
    fun `a panel deeper than a bar still goes on the bar`() {
        val left = CellRegion.strip(12, Axis.VERTICAL)
        assertTrue(
            left.accepts(0, 0, 6, 11),
            "a bar is as thick as the thickest thing on it — that is what a bar is",
        )
        assertFalse(left.accepts(0, 2, 6, 11), "but it is only twelve cells long")
        assertFalse(left.accepts(1, 0, 6, 11), "and it starts where it starts")
    }

    @Test
    fun `a shape does not grow to hold a panel`() {
        val l = CellRegion.l(arm = 12, foot = 8)
        assertFalse(l.accepts(0, 0, 6, 11), "the L is one cell thick and stays that way")
        assertTrue(l.accepts(0, 0, 1, 11))
    }

    // ---- the shapes themselves ----------------------------------------------

    @Test
    fun `the presets are the shapes they are named after`() {
        assertEquals(listOf(CellRect(0, 0, 8, 1)), CellRegion.strip(8, Axis.HORIZONTAL).rects)
        assertEquals(listOf(CellRect(0, 0, 1, 8)), CellRegion.strip(8, Axis.VERTICAL).rects)
        assertEquals(12, CellRegion.block(4, 3).cellCount)
        assertEquals(6 + 5 + 5 - 2, CellRegion.u(width = 6, height = 5).cellCount)
        assertEquals(5 + 4 - 1, CellRegion.t(width = 5, height = 4).cellCount)
    }

    @Test
    fun `a degenerate rectangle is refused rather than raised`() {
        assertEquals(null, CellRect.of(0, 0, 0, 4))
        assertEquals(null, CellRect.of(-1, 0, 4, 4))
        assertEquals(null, CellRect.of(0, 0, 4, 9999))
        assertEquals(CellRect(1, 2, 3, 4), CellRect.of(1, 2, 3, 4))
    }

    @Test
    fun `a shape wider than any screen is dropped, not raised`() {
        assertTrue(CellRegion.of(listOf(CellRect(0, 0, 1, 1), CellRect(300, 300, 1, 1))).cellCount <= 2)
        assertEquals(CellRegion.EMPTY, CellRegion.of(emptyList()))
    }

    // ---- flow order ---------------------------------------------------------

    @Test
    fun `flow order decides which end is the start`() {
        val block = CellRegion.block(3, 2)
        assertEquals(
            cells(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1),
            block.cells(FlowOrder.RIGHT_THEN_DOWN).toList(),
        )
        assertEquals(
            cells(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1),
            block.cells(FlowOrder.DOWN_THEN_RIGHT).toList(),
        )
        assertEquals(
            cells(2 to 1, 1 to 1, 0 to 1, 2 to 0, 1 to 0, 0 to 0),
            block.cells(FlowOrder.LEFT_THEN_UP).toList(),
        )
    }

    @Test
    fun `every flow order has a name that survives a rename`() {
        for (f in FlowOrder.entries) assertEquals(f, FlowOrder.byId(f.id))
        assertEquals(null, FlowOrder.byId("sideways"))
    }
}
