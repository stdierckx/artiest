package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Color
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushLibrary
import be.thalos.artiest.engine.brush.BrushPreset
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shelf's swatches, measured rather than looked at.
 *
 * The claim the shelf rests on is *"you can tell the brushes apart by
 * looking"*, and that is a claim about pixels — so it is checked on pixels. The
 * three that ship differ in three different ways, and each of those is one
 * assertion here:
 *
 * - the **marker** leaves a much wider band than the pen;
 * - the **pencil** leaves a speckled one, because graphite catches on tooth;
 * - the **pen** leaves a solid one, and its darkest ink is fully opaque.
 *
 * NATIVE graphics, for `MarkerResponseTest`'s reason: under the default shadow
 * every `getPixel` returns zero and this file would pass while measuring
 * nothing at all. A blank swatch is exactly the failure being guarded against,
 * so a test that cannot see one is worse than none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrushSwatchTest {

    /** The size a docked shelf row's swatch is, near enough, on the tablet. */
    private val w = 210
    private val h = 70

    @After
    fun cleanUp() {
        BrushSwatches.clear()
    }

    private fun swatchOf(id: String): Bitmap {
        val entry = assertNotNull(BrushLibrary.DEFAULT.find(id), id)
        return BrushSwatch.render(entry.create(), w, h, Color.BLACK)
    }

    /** How many pixels carry any ink at all. */
    private fun inked(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height) {
            for (x in 0 until b.width) if (Color.alpha(b.getPixel(x, y)) > 8) n++
        }
        return n
    }

    /** The tallest run of ink in any column: the width of the mark, turned. */
    private fun thickest(b: Bitmap): Int {
        var best = 0
        for (x in 0 until b.width) {
            var n = 0
            for (y in 0 until b.height) if (Color.alpha(b.getPixel(x, y)) > 8) n++
            if (n > best) best = n
        }
        return best
    }

    /**
     * How broken up the ink is: inked pixels that touch a bare one, over inked
     * pixels. Tooth makes holes; flat ink does not.
     */
    private fun speckle(b: Bitmap): Float {
        var ink = 0
        var edge = 0
        for (y in 1 until b.height - 1) {
            for (x in 1 until b.width - 1) {
                if (Color.alpha(b.getPixel(x, y)) <= 8) continue
                ink++
                val bare = Color.alpha(b.getPixel(x - 1, y)) <= 8 ||
                    Color.alpha(b.getPixel(x + 1, y)) <= 8 ||
                    Color.alpha(b.getPixel(x, y - 1)) <= 8 ||
                    Color.alpha(b.getPixel(x, y + 1)) <= 8
                if (bare) edge++
            }
        }
        return if (ink == 0) 0f else edge.toFloat() / ink
    }

    @Test
    fun `every shipped brush leaves a mark`() {
        for (preset in BrushPreset.entries) {
            val swatch = swatchOf(preset.id)
            assertEquals(w, swatch.width)
            assertEquals(h, swatch.height)
            val ink = inked(swatch)
            // A twentieth of the row. The pen is the thinnest of the three and
            // covers about a twelfth; a swatch whose stroke was lost in the
            // reduction covers none at all, which is the failure being caught.
            assertTrue(ink > w * h / 20, "${preset.label} covered $ink of ${w * h}")
        }
    }

    @Test
    fun `the marker is visibly broader than the pen`() {
        val pen = thickest(swatchOf("pen"))
        val marker = thickest(swatchOf("marker"))
        assertTrue(marker > pen * 2, "pen $pen, marker $marker")
    }

    @Test
    fun `the pencil is speckled and the marker is not`() {
        val pencil = speckle(swatchOf("pencil"))
        val marker = speckle(swatchOf("marker"))
        // Graphite sits on the tooth and skips the pits; marker ink floods
        // them. That is the difference the swatch has to show, and a
        // gradient-bar preview could not.
        assertTrue(pencil > marker * 1.5f, "pencil $pencil, marker $marker")
    }

    @Test
    fun `the pen reaches full opacity and the marker does not`() {
        fun darkest(b: Bitmap): Int {
            var a = 0
            for (y in 0 until b.height) {
                for (x in 0 until b.width) a = maxOf(a, Color.alpha(b.getPixel(x, y)))
            }
            return a
        }
        assertTrue(darkest(swatchOf("pen")) > 250, "the pen is opaque ink")
        // One pass of a marker is 72% covered. See BrushPreset.MARKER.
        assertTrue(darkest(swatchOf("marker")) < 230, "one marker pass is not solid")
    }

    @Test
    fun `the ink colour is the ink colour`() {
        val entry = assertNotNull(BrushLibrary.DEFAULT.find("pen"))
        val swatch = BrushSwatch.render(entry.create(), w, h, Color.RED)
        var sawRed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = swatch.getPixel(x, y)
                if (Color.alpha(p) > 250) {
                    // The channels, not the packed value: the reduction leaves
                    // the darkest pixels at alpha 251 to 255 and a swatch is
                    // about what colour the ink is, not how opaque it got.
                    assertEquals(0xFF0000, p and 0xFFFFFF)
                    sawRed = true
                }
            }
        }
        assertTrue(sawRed)
    }

    @Test
    fun `a swatch is rendered once and then handed back`() {
        val entry = assertNotNull(BrushLibrary.DEFAULT.find("pencil"))
        val first = BrushSwatches.render(entry, w, h, Color.BLACK)
        val again = BrushSwatches.render(entry, w, h, Color.BLACK)
        assertSame(first, again)
        assertEquals(1, BrushSwatches.rendered)
    }

    @Test
    fun `saving over a brush changes the picture it is cached under`() {
        val soft = BrushEntry.fromText(
            "mine", "Mine",
            be.thalos.artiest.engine.brush.BrushCodec.encode(BrushPreset.PENCIL.create()),
        )
        val wide = BrushEntry.fromText(
            "mine", "Mine",
            be.thalos.artiest.engine.brush.BrushCodec.encode(
                BrushPreset.PENCIL.create().also { it.sizeMax = 96f },
            ),
        )
        BrushSwatches.render(soft, w, h, Color.BLACK)
        BrushSwatches.render(wide, w, h, Color.BLACK)
        assertEquals(2, BrushSwatches.rendered, "the same id, a different mark")
    }

    /**
     * An eraser's swatch is a hole, not a band.
     *
     * The shelf showed the hard eraser as a fat black stroke on its first
     * build, which is a picture of a marker. Every other swatch is honest
     * because it is the mark; an eraser's mark is an absence, and an absence
     * drawn on an empty page is nothing at all. So the page is washed and the
     * stroke is punched out of it — see `BrushSwatch.rubbedOut`.
     *
     * The claim under test is the one a user reads off the row: **most of the
     * swatch is tone and the stroke is where the tone is gone**, which is the
     * exact opposite of every other row.
     */
    @Test
    fun `an eraser shows the ink it takes away`() {
        val rubber = swatchOf("hard_eraser")
        val marker = swatchOf("marker")

        // The corner is the tell. No sample stroke reaches it, so in every
        // other swatch it is bare page; in an eraser's it is the wash the
        // stroke was cut out of.
        assertTrue(Color.alpha(rubber.getPixel(2, 2)) > 128, "no tone to rub out")
        assertTrue(Color.alpha(marker.getPixel(2, 2)) < 8, "and no wash anywhere else")

        // Most of the page carries tone, which is the opposite of every other
        // row — a mark covers a fraction of its swatch, a hole leaves the rest.
        assertTrue(
            inked(rubber) > inked(marker),
            "${inked(rubber)} of ${w * h} against the marker's ${inked(marker)}",
        )

        // And the gap is real: a run of the middle row is clear through.
        val centre = (0 until w).count { Color.alpha(rubber.getPixel(it, h / 2)) < 8 }
        assertTrue(centre > w / 4, "the stroke did not come out: $centre columns clear")
    }

    /**
     * The soft eraser leaves some of the tone behind and the hard one does not.
     *
     * This is the whole reason there are two, and it is the one thing that
     * could silently stop being true: `InkSurfaceView.compositeAlpha` used to
     * force every erasing stroke to full strength, which would make these two
     * swatches differ only at the rim.
     */
    @Test
    fun `the soft eraser fades where the hard one cuts`() {
        val hard = swatchOf("hard_eraser")
        val soft = swatchOf("soft_eraser")
        assertTrue(
            partial(soft) > partial(hard),
            "soft ${partial(soft)} is not softer than hard ${partial(hard)}",
        )
    }

    /** Pixels the eraser took *some* of, but not all: the fade. */
    private fun partial(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height) {
            for (x in 0 until b.width) {
                val a = Color.alpha(b.getPixel(x, y))
                if (a in 24..180) n++
            }
        }
        return n
    }
}
