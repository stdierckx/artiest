package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ik2, which is the property everything above it rests on.
 *
 * `UndoHistory`'s header refuses stroke-list undo on the ground that *"replay
 * has to be deterministic, and Phase 2's brush will not be"*. That sentence was
 * right about the engine as it stood: `begin` bumped a counter, the per-dab
 * random was the next value of a stream, and Ik0 measured the pencil
 * re-rendering **105 052 pixels per million differently** — one tenth of the
 * page. A vector sheet built on that would let undo, redo, moving a stroke,
 * changing its colour and zooming in each quietly alter the drawing, with
 * nothing to say which of them did it.
 *
 * These tests are what makes the sentence false. Three properties:
 *
 * 1. The same seed and the same input produce the same dabs, exactly.
 * 2. A dab's random depends on **its index**, not on how many draws came
 *    before it — which is what lets Ik8 split a stroke without changing the
 *    grain of the half it did not touch.
 * 3. Different seeds still produce different strokes, because a determinism
 *    fix that made every stroke identical would pass (1) and (2) and ruin the
 *    brush.
 */
class StrokeDeterminismTest {

    private val dtNanos = StrokeCorpus.DT_NANOS

    /**
     * A brush that scatters *and* jitters, which is the case the old stream
     * could not replay: whether a dab drew the jitter value or the scatter
     * value depended on a `> 0f` branch taken per dab.
     */
    private fun restless() = Brush().apply {
        sizeMin = 4f
        sizeMax = 20f
        scatter.min = 0f
        scatter.max = 3f
        sizeJitter.min = 0f
        sizeJitter.max = 0.5f
    }

    private fun sample(x: Float, y: Float, p: Float, i: Int) = PenSample(
        x = x,
        y = y,
        pressure = p,
        tilt = 0f,
        orientation = 0f,
        distance = 0f,
        toolType = ToolType.STYLUS,
        buttonState = 0,
        eventTimeNanos = i * dtNanos,
        source = PenSample.Source.CURRENT,
    )

    private fun line(b: StrokeBuilder, n: Int = 120, step: Float = 3f, pressure: Float = 1f) {
        for (i in 0 until n) b.add(sample(100f + i * step, 400f, pressure, i))
    }

    private fun dabsOf(s: Stroke): FloatArray {
        val out = FloatArray(s.dabCount * Stroke.STRIDE)
        for (i in 0 until s.dabCount) {
            val o = i * Stroke.STRIDE
            out[o] = s.x(i)
            out[o + 1] = s.y(i)
            out[o + 2] = s.radius(i)
            out[o + 3] = s.aspect(i)
            out[o + 4] = s.rotation(i)
            out[o + 5] = s.flow(i)
        }
        return out
    }

    // ------------------------------------------------------- same in, same out

    @Test
    fun `the same seed and the same input draw the same stroke, exactly`() {
        val a = StrokeBuilder(restless())
        val b = StrokeBuilder(restless())
        a.begin(0xFF000000.toInt(), seed = 4242)
        line(a)
        val one = a.end()
        b.begin(0xFF000000.toInt(), seed = 4242)
        line(b)
        val two = b.end()

        assertEquals(one.dabCount, two.dabCount)
        assertTrue(one.dabCount > 100, "only ${one.dabCount} dabs")
        val x = dabsOf(one)
        val y = dabsOf(two)
        // Exactly, float for float. "Close enough" is what
        // `docs/inker-plan.md`'s second stop condition forbids: a re-render
        // that is nearly right is a drawing that changes a little every time it
        // is touched.
        for (i in x.indices) {
            assertEquals(x[i], y[i], "dab float $i differs: ${x[i]} vs ${y[i]}")
        }
    }

    @Test
    fun `one builder replaying a seed draws what it drew the first time`() {
        // The same instance, which is the case the stream version got wrong:
        // its state carried across strokes.
        val b = StrokeBuilder(restless())
        b.begin(0xFF000000.toInt(), seed = 9)
        line(b)
        val first = dabsOf(b.end())
        // Two unrelated strokes in between, to move any state that is left.
        repeat(2) {
            b.begin(0xFF000000.toInt(), seed = 77)
            line(b, n = 40)
            b.end()
        }
        b.begin(0xFF000000.toInt(), seed = 9)
        line(b)
        val again = dabsOf(b.end())
        assertTrue(first.contentEquals(again), "a replayed seed drew a different stroke")
    }

    @Test
    fun `a different seed draws a different stroke`() {
        val b = StrokeBuilder(restless())
        b.begin(0xFF000000.toInt(), seed = 1)
        line(b)
        val one = dabsOf(b.end())
        b.begin(0xFF000000.toInt(), seed = 2)
        line(b)
        val two = dabsOf(b.end())
        assertNotEquals(one.toList(), two.toList())
        // The dab *count* moves too, and that is not an accident: size jitter
        // changes the radius, the radius changes `spacingFor`, and the spacing
        // decides where the next dab lands. A seed reaches the geometry of the
        // stroke and not only its decoration.
        var differing = 0
        val shared = minOf(one.size, two.size)
        for (i in 0 until shared) if (one[i] != two[i]) differing++
        assertTrue(differing > shared / 3, "only $differing of $shared floats moved")
    }

    /**
     * The unseeded `begin` still gives every stroke its own look, which is what
     * a hand drawing without a vector sheet expects. It does it by handing out
     * the next counter value as a seed, so the mechanism is the same one.
     */
    @Test
    fun `the unseeded begin still varies from stroke to stroke`() {
        val b = StrokeBuilder(restless())
        b.begin(0xFF000000.toInt())
        val firstSeed = b.strokeSeed
        line(b, n = 40)
        val one = dabsOf(b.end())
        b.begin(0xFF000000.toInt())
        assertNotEquals(firstSeed, b.strokeSeed)
        line(b, n = 40)
        assertNotEquals(one.toList(), dabsOf(b.end()).toList())
    }

    // ------------------------------------------------------------- the index

    /**
     * Ik8's property, tested at the only place it can be tested precisely.
     *
     * The brush jitters its size and nothing else, the pressure is flat and the
     * line is straight, so every dab would be exactly the same radius but for
     * the jitter. Starting a second run at `dabBase = 5` must therefore give
     * dab *i* the radius dab *i + 5* had — which is the same statement as "the
     * tail of a split stroke keeps the grain it had".
     */
    @Test
    fun `a dab's random follows its index, not its position in the run`() {
        val pen = Brush().apply {
            sizeMin = 4f
            sizeMax = 20f
            sizeJitter.min = 0f
            sizeJitter.max = 0.5f
        }
        val whole = StrokeBuilder(pen).let { b ->
            b.begin(0xFF000000.toInt(), seed = 31)
            line(b, n = 80)
            b.end()
        }
        val offset = 5
        val tail = StrokeBuilder(pen).let { b ->
            b.begin(0xFF000000.toInt(), seed = 31, dabBase = offset)
            line(b, n = 80)
            b.end()
        }
        // The counts need not agree — jitter feeds the spacing, so a run whose
        // draws start five along lays a slightly different number of dabs over
        // the same path. The claim is about the draw each index carries.
        assertTrue(whole.dabCount > offset + 20)
        val shared = minOf(whole.dabCount - offset, tail.dabCount)
        var moved = 0
        for (i in 0 until shared) {
            assertEquals(
                whole.radius(i + offset),
                tail.radius(i),
                "dab $i of the offset run did not carry dab ${i + offset}'s jitter",
            )
            if (whole.radius(i) != tail.radius(i)) moved++
        }
        // And it really did shift: without dabBase the two runs are identical
        // and the assertion above would hold for the wrong reason.
        assertTrue(moved > shared / 3, "only $moved dabs moved")
    }

    @Test
    fun `a negative dabBase is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StrokeBuilder(restless()).begin(0xFF000000.toInt(), seed = 1, dabBase = -1)
        }
    }

    // ------------------------------------------------------ still a brush

    /**
     * A hash that is a poor one passes every test above and ruins the pencil:
     * grain that repeats every few dabs reads as a pattern, and grain with a
     * bias reads as a stroke that is quietly thinner than the slider says.
     *
     * So: 4000 dabs of jitter, checked for mean, spread, and the absence of a
     * short cycle.
     */
    @Test
    fun `the hash spreads its draws the way a random would`() {
        val pen = Brush().apply {
            sizeMin = 0f
            sizeMax = 20f
            sizeJitter.min = 0f
            sizeJitter.max = 1f
        }
        val b = StrokeBuilder(pen)
        b.begin(0xFF000000.toInt(), seed = 12345)
        line(b, n = 1400, step = 3f)
        val s = b.end()
        assertTrue(s.dabCount > 3000, "only ${s.dabCount} dabs")

        // radius = 10 * (1 - r), so r = 1 - radius / 10.
        val draws = FloatArray(s.dabCount) { 1f - s.radius(it) / 10f }
        var sum = 0.0
        for (v in draws) sum += v
        val mean = sum / draws.size
        assertTrue(abs(mean - 0.5) < 0.02, "mean draw was $mean")

        // Ten buckets, each within a fifth of its expected share.
        val buckets = IntArray(10)
        for (v in draws) buckets[(v * 10f).toInt().coerceIn(0, 9)]++
        val expected = draws.size / 10.0
        for (i in buckets.indices) {
            assertTrue(
                abs(buckets[i] - expected) < expected * 0.2,
                "bucket $i held ${buckets[i]} of an expected $expected",
            )
        }

        // No short cycle: a hash that only mixes the low bits of the index
        // repeats, and a repeat is the one artifact an eye picks out of grain.
        for (period in 1..64) {
            var same = 0
            for (i in 0 until draws.size - period) {
                if (draws[i] == draws[i + period]) same++
            }
            assertTrue(same < 4, "$same draws repeat at a period of $period")
        }
    }

    /**
     * The channels have to be independent, or a brush that scatters and jitters
     * throws its ink furthest exactly where the dab is thinnest — a correlation
     * that looks like a deliberate effect and is a bug.
     */
    @Test
    fun `scatter and size jitter do not move together`() {
        val pen = Brush().apply {
            sizeMin = 0f
            sizeMax = 20f
            sizeJitter.min = 0f
            sizeJitter.max = 1f
            scatter.min = 0f
            scatter.max = 10f
        }
        val b = StrokeBuilder(pen)
        b.begin(0xFF000000.toInt(), seed = 808)
        line(b, n = 900, step = 3f, pressure = 1f)
        val s = b.end()

        // The jitter draw is recoverable from the radius; the scatter draw is
        // recoverable from the angle of the offset, which is the only thing the
        // scatter channel decides.
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 1 until s.dabCount - 1) {
            val jitter = 1f - s.radius(i) / 10f
            // The dab sits 10 px from the path at an angle the scatter channel
            // chose; the path here is y = 400, so the offset's sign and size
            // give the angle back through asin.
            val angle = ((s.y(i) - 400f) / 10f).coerceIn(-1f, 1f)
            n++
            sx += jitter
            sy += angle
            sxx += jitter * jitter
            syy += angle * angle
            sxy += jitter * angle
        }
        val cov = sxy / n - (sx / n) * (sy / n)
        val sdx = kotlin.math.sqrt(sxx / n - (sx / n) * (sx / n))
        val sdy = kotlin.math.sqrt(syy / n - (sy / n) * (sy / n))
        val r = cov / (sdx * sdy)
        assertTrue(abs(r) < 0.1, "jitter and scatter correlate at $r over $n dabs")
    }
}
