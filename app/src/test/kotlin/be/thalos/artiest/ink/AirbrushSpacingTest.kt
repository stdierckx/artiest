package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.Color
import be.thalos.artiest.canvas.VectorStress
import be.thalos.artiest.doc.Layer
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.ink.Stroke
import be.thalos.artiest.engine.ink.StrokeBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Bn5: whether the airbrush can lay half as many dabs and draw the same mark.
 *
 * `spacing 0.1` on a 600 px nib is a dab every 60 document pixels, so ten dabs
 * overlap at every point and the dab loop does ten times the work the stroke
 * visibly covers. Doubling the spacing halves that — and costs nothing in
 * density, because `StrokeBuilder.dabAlphaFor` already inverts the overlap:
 * `flow` means *the coverage of one pass*, and the per-dab alpha is solved from
 * it and the spacing. The engine compensates; the question is only whether the
 * discreteness shows.
 *
 * **The plan's fifth stop condition is that if the mark changes at all, the
 * change does not ship.** Performance is not a reason to make a brush draw
 * differently, so this measures the mark rather than asking somebody to squint
 * at two swatches.
 *
 * ## It changed, so it did not ship
 *
 * Mean alpha difference **6.6 of 255 over 170,000 inked pixels, worst 50** — a
 * fifth of the channel where the rim overlaps. Visible, so `spacing 0.1` stays.
 *
 * The reason is worth keeping, because it is not what the plan expected. The
 * compensation in `dabAlphaFor` has one exit that skips it entirely:
 *
 * ```kotlin
 * if (flow >= 1f) return 1f
 * ```
 *
 * — correct, since a fully opaque dab cannot be made more opaque. But the
 * airbrush's flow curve **reaches 1 at 0.859 pressure**, so the whole firm half
 * of every stroke draws with no overlap compensation at all, and there halving
 * the dabs halves the build-up in the soft rim. The engine compensates exactly
 * where the brush is faint and not at all where it is dark.
 *
 * So the assertion below is inverted on purpose: it pins the *refusal*. If
 * somebody later makes the compensation reach the opaque end, this test fails
 * and says so, which is the moment to try the spacing again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AirbrushSpacingTest {

    private val w = 1400
    private val h = 900

    private fun airbrush(spacing: Float): Brush =
        BrushCodec.decode(File("src/main/assets/brushes/airbrush-soft.brush").readText())!!
            .also { it.spacing = spacing }

    /** One long stroke with a pressure ramp, so every flow the curve reaches is drawn. */
    private fun strokeOf(brush: Brush): Stroke {
        val b = StrokeBuilder(brush)
        b.begin(Color.BLACK)
        val samples = 260
        for (i in 0 until samples) {
            val t = i / (samples - 1f)
            val nanos = 1_000_000_000L + (i * 3_108_000L)
            b.addTilt(0.3f, 0f, nanos)
            b.add(
                200f + t * 1000f,
                450f + kotlin.math.sin(t * 3.0).toFloat() * 180f,
                // A full ramp up and down: the flow curve is driven by pressure
                // and a flat press would test one point on it.
                (if (t < 0.5f) t * 2f else (1f - t) * 2f).coerceIn(0.05f, 1f),
                nanos,
            )
        }
        return b.end()
    }

    private fun paint(brush: Brush): Bitmap {
        val layer = Layer(w, h, enforceOffMainThread = false)
        val rast = DabRasterizer(w, h, StampCache()).also {
            it.mode = DabRasterizer.Mode.STAMP
            it.hardness = brush.hardness
        }
        val scratch = ScratchLayer(maxWidth = w, maxHeight = h)
        val stroke = strokeOf(brush)
        scratch.begin(stroke.bounds)
        rast.drawDry(scratch.canvasInDocSpace()!!, stroke)
        layer.write { scratch.compositeInto(it, brush.opacity) }
        // Copied while the lock is held: `read` hands out the live bitmap and
        // `close` recycles it.
        var copy: Bitmap? = null
        layer.read { src -> copy = src.copy(Bitmap.Config.ARGB_8888, false) }
        layer.close()
        return copy!!
    }

    @Test
    fun `doubling the spacing changes the mark, so the spacing stays`() {
        val tight = paint(airbrush(0.1f))
        val loose = paint(airbrush(0.2f))
        var worst = 0
        var sum = 0L
        var inked = 0L
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val a = Color.alpha(tight.getPixel(x, y))
                val b = Color.alpha(loose.getPixel(x, y))
                val d = abs(a - b)
                if (d > worst) worst = d
                sum += d
                if (a > 0 || b > 0) inked++
            }
        }
        val mean = if (inked == 0L) 0.0 else sum.toDouble() / inked
        println("airbrush spacing 0.1 vs 0.2: mean |da| %.3f, worst %d, over %d inked".format(mean, worst, inked))
        assertTrue(inked > 10_000, "the stroke barely painted: $inked")
        // Inverted on purpose. See the note above: this records that Bn5 was
        // tried and refused, and it is the tripwire for trying it again.
        assertTrue(
            mean > 2.0,
            "doubling the spacing now costs only $mean of 255 — Bn5 is worth revisiting",
        )
    }

    /** The preset ships at the spacing the test above defends. */
    @Test
    fun `the shipped airbrush lays a dab every tenth of its width`() {
        val text = File("src/main/assets/brushes/airbrush-soft.brush").readText()
        assertTrue("spacing 0.1" in text, "the airbrush's spacing moved")
    }
}
