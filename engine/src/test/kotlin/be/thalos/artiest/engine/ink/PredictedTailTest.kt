package be.thalos.artiest.engine.ink

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The speculative stretch between the last real dab and where the predictor
 * says the pen is.
 *
 * Short and mostly arithmetic, and the arithmetic is worth pinning because the
 * ink it produces cannot be taken back: every dab this emits is on the screen
 * until pen-up. So the tests are about the ways it could emit *more* than it
 * should — a spacing that does not terminate the walk, a cap that does not cap,
 * a non-finite predicted point walked into — rather than about how it looks.
 */
class PredictedTailTest {

    /** Collects dabs and reports a fixed spacing. */
    private class Collector(private val spacing: Float = 3f) : DabEmitter {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val pressures = ArrayList<Float>()
        val radii = ArrayList<Float>()

        override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float {
            xs.add(x)
            ys.add(y)
            pressures.add(pressure)
            radii.add(pressure * 10f)
            return spacing
        }
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `dabs land on the line at the spacing the emitter asked for`() {
        val c = Collector(spacing = 3f)
        val n = PredictedTail(c).emit(
            fromX = 100f, fromY = 200f,
            toX = 130f, toY = 200f,
            fromPressure = 0.5f, toPressure = 0.5f,
            elapsedMillis = 50f,
            firstSpacing = 3f,
        )
        assertEquals(10, n)
        assertEquals(10, c.xs.size)
        for (i in 0 until n) {
            assertEquals(103f + i * 3f, c.xs[i], 1e-3f)
            assertEquals(200f, c.ys[i], 1e-3f)
        }
    }

    @Test
    fun `the first dab is exactly firstSpacing from the last real one`() {
        // The join is the visible part: a first dab closer than the brush would
        // place it reads as a thicker blob where the speculation starts.
        val c = Collector(spacing = 5f)
        PredictedTail(c).emit(
            fromX = 0f, fromY = 0f,
            toX = 30f, toY = 40f, // a 50-unit diagonal, so the walk is not axis-aligned
            fromPressure = 1f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 7f,
        )
        assertTrue(c.xs.isNotEmpty())
        assertEquals(7f, dist(0f, 0f, c.xs[0], c.ys[0]), 1e-3f)
        assertEquals(5f, dist(c.xs[0], c.ys[0], c.xs[1], c.ys[1]), 1e-3f)
    }

    @Test
    fun `a tail shorter than one spacing emits nothing`() {
        val c = Collector()
        val n = PredictedTail(c).emit(
            fromX = 0f, fromY = 0f,
            toX = 1f, toY = 0f,
            fromPressure = 1f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 3f,
        )
        assertEquals(0, n)
        assertTrue(c.xs.isEmpty())
    }

    @Test
    fun `pressure is interpolated so the width does not step at the join`() {
        val c = Collector(spacing = 10f)
        PredictedTail(c).emit(
            fromX = 0f, fromY = 0f,
            toX = 100f, toY = 0f,
            fromPressure = 0f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 10f,
        )
        assertEquals(10, c.pressures.size)
        // Linear in the fraction along the tail: the dab at 10% of the way is
        // at 0.1 pressure.
        for (i in c.pressures.indices) {
            assertEquals((i + 1) * 0.1f, c.pressures[i], 1e-3f)
        }
        // And it is monotone, which is the property that shows up as ink.
        for (i in 1 until c.pressures.size) {
            assertTrue(c.pressures[i] > c.pressures[i - 1])
        }
    }

    @Test
    fun `the cap holds when the predictor says something absurd`() {
        // A bad sample can put the predicted point anywhere. Without the cap
        // this walk emits 33,000 dabs on one frame and the app stops
        // responding; with it, one frame has a short tail.
        val c = Collector(spacing = 3f)
        val n = PredictedTail(c).emit(
            fromX = 0f, fromY = 0f,
            toX = 100_000f, toY = 0f,
            fromPressure = 1f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 3f,
        )
        assertEquals(PredictedTail.DEFAULT_MAX_DABS, n)
        assertEquals(PredictedTail.DEFAULT_MAX_DABS, c.xs.size)
    }

    @Test
    fun `a smaller cap is honoured, because the batch is the real limit`() {
        val c = Collector(spacing = 1f)
        val n = PredictedTail(c).emit(
            fromX = 0f, fromY = 0f,
            toX = 1000f, toY = 0f,
            fromPressure = 1f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 1f,
            maxDabs = 12,
        )
        assertEquals(12, n)
    }

    @Test
    fun `a non-finite predicted point draws nothing rather than something`() {
        val c = Collector()
        val tail = PredictedTail(c)
        assertEquals(0, tail.emit(0f, 0f, Float.NaN, 0f, 1f, 1f, 0f, 3f))
        assertEquals(0, tail.emit(0f, 0f, Float.POSITIVE_INFINITY, 0f, 1f, 1f, 0f, 3f))
        assertEquals(0, tail.emit(Float.NaN, 0f, 10f, 0f, 1f, 1f, 0f, 3f))
        assertTrue(c.xs.isEmpty())
    }

    @Test
    fun `a spacing that would not terminate the walk is refused`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedTail(Collector()).emit(0f, 0f, 10f, 0f, 1f, 1f, 0f, 0f)
        }
        // And an emitter that returns one mid-walk fails loudly rather than
        // spinning: the same guard CatmullRomResampler carries, for the same
        // reason.
        val bad = object : DabEmitter {
            var calls = 0
            override fun emit(x: Float, y: Float, pressure: Float, elapsedMillis: Float): Float {
                calls++
                return if (calls == 1) 3f else 0f
            }
        }
        assertFailsWith<IllegalStateException> {
            PredictedTail(bad).emit(0f, 0f, 100f, 0f, 1f, 1f, 0f, 3f)
        }
    }

    @Test
    fun `the tail is straight, which is what the gate buys`() {
        // Not a spline, and the test says so: every dab is on the segment. A
        // curved tail would need a second predicted point to anchor it, and
        // PredictionGate has already established the pen is not turning much.
        val c = Collector(spacing = 2f)
        PredictedTail(c).emit(
            fromX = 10f, fromY = 10f,
            toX = 40f, toY = 50f,
            fromPressure = 1f, toPressure = 1f,
            elapsedMillis = 0f,
            firstSpacing = 2f,
        )
        assertTrue(c.xs.size > 5)
        for (i in c.xs.indices) {
            // Cross product of (to - from) with (dab - from) is zero on the line.
            val cross = (40f - 10f) * (c.ys[i] - 10f) - (50f - 10f) * (c.xs[i] - 10f)
            assertTrue(abs(cross) < 1e-2f, "dab $i is off the line by $cross")
        }
    }
}
