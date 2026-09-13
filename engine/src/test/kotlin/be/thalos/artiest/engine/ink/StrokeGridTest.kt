package be.thalos.artiest.engine.ink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The index, and the two properties everything above it depends on.
 *
 * **It must never miss.** A stroke the grid fails to return is a stroke a
 * damage rectangle does not repaint — a hole in the drawing — or one a tap
 * cannot select. Over-reporting is merely slower, so every test here that could
 * be written either way is written to allow a false positive and forbid a false
 * negative.
 *
 * **A removed stroke must be gone from every cell.** A long stroke is filed in
 * a dozen cells and a leak in any one of them is a deleted stroke that a tap
 * still finds, weeks later, in a drawing that has been saved a hundred times.
 */
class StrokeGridTest {

    private val page = 3300
    private val tall = 2160

    @Test
    fun `the page divides into the cells the plan counted`() {
        val grid = StrokeGrid(page, tall)
        assertEquals(256, StrokeGrid.DEFAULT_CELL_PX)
        assertEquals(13, grid.columns)
        assertEquals(9, grid.rows)
        // The 117 `docs/inker-plan.md`'s memory table counted.
        assertEquals(117, grid.columns * grid.rows)
    }

    @Test
    fun `a stroke is found in the rectangle it occupies and not elsewhere`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(100f, 100f, 200f, 200f))
        assertContentEquals(longArrayOf(1L), grid.query(Bounds.of(150f, 150f, 160f, 160f)))
        assertContentEquals(longArrayOf(), grid.query(Bounds.of(900f, 900f, 1000f, 1000f)))
    }

    @Test
    fun `a query inside one cell does not return the rest of that cell`() {
        val grid = StrokeGrid(page, tall)
        // Both in cell (0, 0), which is 256 px across.
        grid.add(1L, Bounds.of(10f, 10f, 30f, 30f))
        grid.add(2L, Bounds.of(200f, 200f, 240f, 240f))
        assertContentEquals(longArrayOf(1L), grid.query(Bounds.of(15f, 15f, 16f, 16f)))
    }

    @Test
    fun `a stroke spanning many cells is returned once`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(10f, 10f, 2000f, 1500f))
        val hit = grid.query(Bounds.of(0f, 0f, page.toFloat(), tall.toFloat()))
        assertContentEquals(longArrayOf(1L), hit)
    }

    @Test
    fun `the answer is in ascending id order which is draw order`() {
        val grid = StrokeGrid(page, tall)
        // Filed out of order, and overlapping, so the cells hold them jumbled.
        for (id in listOf(9L, 3L, 7L, 1L, 5L)) {
            grid.add(id, Bounds.of(100f, 100f, 900f, 900f))
        }
        assertContentEquals(
            longArrayOf(1L, 3L, 5L, 7L, 9L),
            grid.query(Bounds.of(400f, 400f, 500f, 500f)),
        )
    }

    @Test
    fun `a removed stroke is gone from every cell it spanned`() {
        val grid = StrokeGrid(page, tall)
        val wide = Bounds.of(10f, 10f, 2000f, 1500f)
        grid.add(1L, wide)
        grid.add(2L, wide)
        assertTrue(grid.remove(1L))
        assertFalse(grid.remove(1L))
        assertNull(grid.boundsOf(1L))
        assertEquals(1, grid.size)
        // Every cell the stroke used to be in, one at a time.
        for (cx in 0 until grid.columns) {
            for (cy in 0 until grid.rows) {
                val x = cx * 256f + 1f
                val y = cy * 256f + 1f
                val hit = grid.query(Bounds.of(x, y, x + 254f, y + 254f))
                assertFalse(hit.contains(1L), "stroke 1 survived in cell ($cx, $cy)")
            }
        }
    }

    /**
     * Ik9 drags a stroke. Re-filing under a new rectangle must move it rather
     * than file it twice, or a stroke that has been moved five times is
     * returned five times and repainted five times.
     */
    @Test
    fun `re-filing a stroke moves it`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(10f, 10f, 60f, 60f))
        grid.add(1L, Bounds.of(2000f, 1500f, 2060f, 1560f))
        assertEquals(1, grid.size)
        assertContentEquals(longArrayOf(), grid.query(Bounds.of(10f, 10f, 60f, 60f)))
        assertContentEquals(longArrayOf(1L), grid.query(Bounds.of(2000f, 1500f, 2060f, 1560f)))
    }

    /**
     * The pen can leave the page mid-stroke, so a record's bounds can lie
     * wholly outside it. Losing that stroke would mean it could not be
     * selected, erased, or dragged back on.
     */
    @Test
    fun `ink off the page is filed in the nearest edge cell and stays findable`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(-500f, -400f, -100f, -50f))
        grid.add(2L, Bounds.of(page + 100f, tall + 100f, page + 300f, tall + 300f))
        assertContentEquals(longArrayOf(1L), grid.query(Bounds.of(-400f, -300f, -200f, -100f)))
        assertContentEquals(
            longArrayOf(2L),
            grid.query(Bounds.of(page + 150f, tall + 150f, page + 200f, tall + 200f)),
        )
    }

    @Test
    fun `a stroke that touches the edge of the query is returned`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(100f, 100f, 200f, 200f))
        assertContentEquals(longArrayOf(1L), grid.query(Bounds.of(200f, 200f, 300f, 300f)))
    }

    @Test
    fun `an empty bounds is filed nowhere and asks for nothing`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.EMPTY)
        assertEquals(1, grid.size)
        assertContentEquals(longArrayOf(), grid.query(Bounds.of(0f, 0f, 3000f, 2000f)))
        assertContentEquals(longArrayOf(), grid.query(Bounds.EMPTY))
        assertTrue(grid.remove(1L))
    }

    @Test
    fun `near is a square of slop around a point`() {
        val grid = StrokeGrid(page, tall)
        grid.add(1L, Bounds.of(100f, 100f, 110f, 110f))
        val out = IdList()
        grid.near(120f, 105f, 4f, out)
        assertEquals(0, out.count)
        grid.near(120f, 105f, 12f, out)
        assertEquals(1, out.count)
        assertEquals(1L, out.id(0))
    }

    @Test
    fun `clear forgets everything`() {
        val grid = StrokeGrid(page, tall)
        for (i in 0 until 100) grid.add(i.toLong(), Bounds.of(10f, 10f, 2000f, 1500f))
        grid.clear()
        assertEquals(0, grid.size)
        assertContentEquals(longArrayOf(), grid.query(Bounds.of(0f, 0f, 3000f, 2000f)))
    }

    /**
     * The reason the index exists, as an arithmetic claim rather than a feeling:
     * a tap on Ik0's 300-stroke page must measure a handful of strokes, not 300.
     */
    @Test
    fun `a tap on a three hundred stroke page narrows to a handful`() {
        val grid = StrokeGrid(page, tall)
        // The bench's own layout: a loose grid of 200-700 px arcs.
        val columns = 18
        for (i in 0 until 300) {
            val cx = (i % columns + 0.5f) * (page.toFloat() / columns)
            val cy = (i / columns + 0.5f) * (tall.toFloat() / 17)
            grid.add(i.toLong(), Bounds.of(cx - 200f, cy - 200f, cx + 200f, cy + 200f))
        }
        val out = IdList()
        var worst = 0
        for (x in 100 until page step 137) {
            for (y in 100 until tall step 149) {
                grid.near(x.toFloat(), y.toFloat(), 8f, out)
                if (out.count > worst) worst = out.count
            }
        }
        assertTrue(worst <= 12, "a tap measured $worst strokes of 300")
    }

    @Test
    fun `the id list sorts and dedupes in place`() {
        val list = IdList(2)
        for (v in longArrayOf(5, 1, 5, 3, 1, 9, 3)) list.add(v)
        list.sortAndDedupe()
        assertContentEquals(longArrayOf(1, 3, 5, 9), list.toArray())
        assertTrue(list.contains(9L))
        assertFalse(list.contains(4L))
        list.clear()
        assertTrue(list.isEmpty)
    }
}
