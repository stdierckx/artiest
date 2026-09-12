package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two of the same catalogue entry on one bar.
 *
 * `ToolItem`'s rule 3 — an item lives in exactly one place — is the one thing
 * this feature bends, and it bends it precisely: what lives in one place is an
 * *item*, and what stands on a bar is an item **plus what it is for**. Two
 * pencils tuned differently, side by side, is the thing that could not be
 * expressed before, so these tests are all about the two not collapsing into
 * one.
 *
 * No Compose and no `android.*`, which is the same stop condition
 * `DockLayoutTest` states: if this needed a renderer, the argument would have
 * been put in the wrong layer.
 */
class BrushButtonLayoutTest {

    private val bar: DockLayout
        get() = DockLayout.of(
            listOf(
                Surface(
                    "a",
                    FlowOrder.RIGHT_THEN_DOWN,
                    SurfaceLayout.empty(CellRegion.strip(6, Axis.HORIZONTAL)),
                ),
            ),
        )

    @Test
    fun `two brush buttons live side by side`() {
        val layout = bar
            .place("a", ToolItem.BRUSH, Cell(0, 0), "soft-2b")
            .place("a", ToolItem.BRUSH, Cell(1, 0), "hard-2h")

        assertEquals(2, layout.all().size, "placing the second must not move the first")
        assertEquals("soft-2b", layout.surface("a")!!.slots.covering(Cell(0, 0))?.arg)
        assertEquals("hard-2h", layout.surface("a")!!.slots.covering(Cell(1, 0))?.arg)
    }

    @Test
    fun `the same brush twice is still one button`() {
        // The rule has not gone away, it has only learned what an item is. The
        // *same* brush placed again is the same control and moves.
        val layout = bar
            .place("a", ToolItem.BRUSH, Cell(0, 0), "soft-2b")
            .place("a", ToolItem.BRUSH, Cell(3, 0), "soft-2b")

        assertEquals(1, layout.all().size)
        assertNull(layout.surface("a")!!.slots.covering(Cell(0, 0)))
        assertEquals("soft-2b", layout.surface("a")!!.slots.covering(Cell(3, 0))?.arg)
    }

    @Test
    fun `an item with no argument behaves exactly as it did`() {
        val layout = bar
            .place("a", ToolItem.PEN, Cell(0, 0))
            .place("a", ToolItem.PEN, Cell(2, 0))
        assertEquals(1, layout.all().size, "one pen, moved")
        assertEquals(Cell(2, 0), layout.locate(ToolItem.PEN)?.cell)
    }

    @Test
    fun `dragging a brush button keeps the brush`() {
        val two = DockLayout.of(
            listOf(
                Surface(
                    "a",
                    FlowOrder.RIGHT_THEN_DOWN,
                    SurfaceLayout.empty(CellRegion.strip(4, Axis.HORIZONTAL)),
                ),
                Surface(
                    "b",
                    FlowOrder.RIGHT_THEN_DOWN,
                    SurfaceLayout.empty(
                        CellRegion.strip(4, Axis.HORIZONTAL).translated(0, 4),
                    ),
                ),
            ),
        ).place("a", ToolItem.BRUSH, Cell(0, 0), "soft-2b")

        val moved = assertNotNull(two.move("a", Cell(0, 0), "b", Cell(1, 4)))
        assertEquals("soft-2b", moved.surface("b")!!.slots.covering(Cell(1, 4))?.arg)
        assertTrue(moved.surface("a")!!.isEmpty)
    }

    @Test
    fun `the argument survives being written down and read back`() {
        val layout = bar
            .place("a", ToolItem.BRUSH, Cell(0, 0), "soft-2b")
            .place("a", ToolItem.BRUSH, Cell(1, 0), "hard-2h")
            .place("a", ToolItem.PENCIL, Cell(2, 0))

        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))
        assertEquals(
            listOf(ToolItem.BRUSH to "soft-2b", ToolItem.BRUSH to "hard-2h", ToolItem.PENCIL to null),
            back.surface("a")!!.slots.placements.map { it.identity },
        )
    }

    @Test
    fun `a layout written before brush buttons existed still reads`() {
        // The version did not move, on purpose: `~arg` is a suffix a v5 string
        // never carries, so everything ever saved parses unchanged. This is
        // that claim as a test, with a string taken from a real device.
        val old = "v5|s1:R0,5,1,5:down_right:0,5=pen,0,6=pencil,0,7=marker,0,8=brushes,0,9=eraser"
        val back = assertNotNull(DockCodec.decode(old))
        assertEquals(5, back.all().size)
        assertTrue(back.all().all { it.placement.arg == null })
    }

    @Test
    fun `an argument on something that cannot use one is carried, not dropped`() {
        // Nothing in the catalogue but BRUSH reads its argument today, and the
        // layout deliberately has no opinion about which do — it carries the
        // string and hands it back. A reader that discarded it would make
        // adding the next argument-taking control a format change.
        val layout = bar.place("a", ToolItem.PENCIL, Cell(0, 0), "unexpected")
        val back = assertNotNull(DockCodec.decode(DockCodec.encode(layout)))
        assertEquals("unexpected", back.surface("a")!!.slots.covering(Cell(0, 0))?.arg)
    }
}
