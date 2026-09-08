package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Dab-list goldens: the serialized `(x, y, radius)` output of the whole W7
 * pipeline over a fixed corpus of synthetic strokes, diffed on the JVM.
 *
 * **This is what Phase 1 ships instead of pixel goldens**, and the plan says so
 * for two reasons that are both worth restating where the files live. Pixel
 * goldens need a device or an emulator, which puts them out of reach of every
 * CI run and most local ones; and they invalidate on every antialiasing tweak,
 * which trains people to regenerate them without reading the diff. A dab list
 * is the last thing the engine decides before `android.graphics` gets involved,
 * so it is the furthest downstream a JVM test can look — and it changes only
 * when the stabilizer, the spline, the arc walk or the pressure curve changes,
 * which is exactly when someone should be made to look.
 *
 * **Bit-exact floats, via `Float.toString`.** The same discipline `TraceFormat`
 * uses and for the same reason: `String.format` and `DecimalFormat` are
 * locale-sensitive, and `TraceFormatTest` already pins that under nl-BE, where
 * a decimal comma would turn every golden into a parse error on one developer's
 * machine and nowhere else. `Float.toString` round-trips exactly and does not
 * consult a locale.
 *
 * **Regenerating.** Run with `-Dartiest.golden.write=true` and the files are
 * rewritten in place from the current code. Read the `git diff` before
 * committing it — that diff is the entire value of this test. A golden that
 * moved because someone tuned the pressure curve is a fact worth recording in
 * a commit message; a golden that moved because the spline started overshooting
 * is a bug that this file is the only thing standing in front of.
 */
class DabGoldenTest {

    /** 321.75 Hz, the measured digitizer rate at 90 Hz. */
    private val dtNanos = 3_107_855L

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

    /**
     * The corpus. Each entry is a hand-reasoned case rather than a recording,
     * so that when one of them moves it is obvious which stage moved it.
     */
    private fun corpus(): Map<String, List<PenSample>> = linkedMapOf(
        // A plain drag at constant speed and pressure. Everything else is a
        // perturbation of this, so if only this one moves, something is wrong
        // with the arc walk itself.
        "straight" to (0 until 48).map { sample(200f + it * 9f, 600f, 0.6f, it) },

        // A quarter circle. Curvature with no speed change: the case the
        // spline exists for and the arc walk has to keep even.
        "arc" to (0 until 72).map {
            val a = it / 71f * (Math.PI.toFloat() / 2f)
            sample(1000f + 300f * cos(a), 1500f + 300f * sin(a), 0.55f, it)
        },

        // Pressure climbing from the measured minimum to ordinary drawing
        // force over ~20 ms. This is the stroke that pins the onset ramp; if
        // RoundPen's lift or its release shape changes, it moves and nothing
        // else does.
        "onset" to (0 until 40).map {
            sample(400f + it * 3f, 900f, min(0.45f, 0.00208f + 0.022f * it), it)
        },

        // Accelerating away, ending with 60 px between samples. Without
        // resampling this is a dotted line, so it is the case that proves the
        // stage is doing something.
        "flick" to buildList {
            var x = 300f
            for (i in 0 until 36) {
                add(sample(x, 2000f, 0.7f, i))
                x += 2f + i * 1.8f
            }
        },

        // Pressure up and back down over a straight drag: a taper at both ends,
        // which is what the radius column is for.
        "taper" to (0 until 60).map {
            val t = it / 59f
            sample(500f + it * 7f, 2500f, 0.05f + 0.9f * sin(t * Math.PI.toFloat()), it)
        },

        // Move, dwell for 20 samples, move again. The dwell must contribute no
        // dabs and must not disturb the spline on either side of it.
        "dwell" to buildList {
            var i = 0
            repeat(16) { add(sample(700f + it * 10f, 300f, 0.5f, i++)) }
            repeat(20) { add(sample(850f, 300f, 0.5f, i++)) }
            repeat(16) { add(sample(850f + it * 10f, 300f, 0.5f, i++)) }
        },

        // A hard corner at a speed change — the uniform-Catmull-Rom hook case,
        // recorded so a change of parameterization cannot slip through.
        "corner" to buildList {
            var i = 0
            repeat(20) { add(sample(100f + it * 20f, 100f, 0.65f, i++)) }
            repeat(4) { add(sample(500f + it * 5f, 100f, 0.65f, i++)) }
            repeat(20) { add(sample(520f, 100f + it * 20f, 0.65f, i++)) }
        },

        // One sample. One dab. The degenerate case that every stage has to
        // survive without special-casing at the call site.
        "tap" to listOf(sample(1234f, 5678f, 0.4f, 0)),
    )

    private fun render(samples: List<PenSample>, pen: RoundPen = RoundPen()): Stroke {
        val b = StrokeBuilder(pen)
        b.begin(0xFF000000.toInt())
        for (s in samples) b.add(s)
        return b.end()
    }

    /**
     * The serialized form. A header naming every brush parameter that can move
     * the numbers, so a diff says *why* before it says what — and then one line
     * per dab, `Float.toString` throughout.
     */
    private fun serialize(name: String, pen: RoundPen, samples: Int, s: Stroke): String {
        val sb = StringBuilder()
        sb.append("# artiest dab golden v1\n")
        sb.append("# stroke=").append(name).append(" samples=").append(samples).append('\n')
        sb.append("# sizeMin=").append(pen.sizeMin)
            .append(" sizeMax=").append(pen.sizeMax)
            .append(" spacing=").append(pen.spacing)
            .append(" curve=").append(pen.pressureCurve)
            .append(" onsetMillis=").append(pen.onsetMillis)
            .append(" onsetPressure=").append(pen.onsetPressure)
            .append(" stabilization=").append(pen.stabilization)
            .append('\n')
        sb.append("# bounds=").append(s.bounds.left).append(' ').append(s.bounds.top)
            .append(' ').append(s.bounds.right).append(' ').append(s.bounds.bottom).append('\n')
        sb.append("# dabs=").append(s.dabCount).append('\n')
        for (i in 0 until s.dabCount) {
            sb.append(s.x(i)).append(' ').append(s.y(i)).append(' ').append(s.radius(i)).append('\n')
        }
        return sb.toString()
    }

    private fun goldenFile(name: String) = File("src/test/resources/golden/$name.dabs")

    @Test
    fun `the corpus matches its goldens`() {
        val write = System.getProperty("artiest.golden.write") == "true"
        val missing = ArrayList<String>()
        for ((name, samples) in corpus()) {
            val pen = RoundPen()
            val text = serialize(name, pen, samples.size, render(samples, pen))
            val file = goldenFile(name)
            if (write) {
                file.parentFile.mkdirs()
                file.writeText(text)
                continue
            }
            val resource = javaClass.getResourceAsStream("/golden/$name.dabs")
            if (resource == null) {
                missing += name
                continue
            }
            val expected = resource.bufferedReader().readText()
            if (expected != text) {
                // Report the first differing line rather than the whole file:
                // a 900-dab diff in an assertion message is unreadable, and
                // the first divergence is where the cause is.
                val e = expected.lines()
                val a = text.lines()
                val at = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: a.size
                assertEquals(
                    e.getOrNull(at),
                    a.getOrNull(at),
                    "golden '$name' diverges at line ${at + 1} " +
                        "(${e.size} lines expected, ${a.size} produced)",
                )
            }
        }
        assertTrue(
            missing.isEmpty(),
            "no golden for ${missing}; run with -Dartiest.golden.write=true and read the diff",
        )
    }

    /**
     * A golden suite is only worth its disk if it fails when the thing it
     * covers changes. Each perturbation below is a plausible edit to one stage,
     * and each has to move at least one file — otherwise that stage has no
     * coverage here and the green is meaningless.
     */
    @Test
    fun `every stage the goldens are supposed to cover actually moves them`() {
        val strokes = corpus()
        fun renderAll(pen: () -> RoundPen): String =
            strokes.entries.joinToString("\n") { (n, s) ->
                val p = pen()
                serialize(n, p, s.size, render(s, p))
            }

        val baseline = renderAll { RoundPen() }

        assertNotEquals(baseline, renderAll { RoundPen().apply { spacing = 0.13f } }, "arc walk")
        assertNotEquals(baseline, renderAll { RoundPen().apply { sizeMax = 24.5f } }, "size curve")
        assertNotEquals(baseline, renderAll { RoundPen().apply { pressureCurve = 2.9f } }, "pressure curve")
        assertNotEquals(baseline, renderAll { RoundPen().apply { onsetMillis = 11f } }, "onset ramp")
        assertNotEquals(baseline, renderAll { RoundPen().apply { onsetPressure = 0.26f } }, "onset lift")
        assertNotEquals(baseline, renderAll { RoundPen().apply { stabilization = 0.16f } }, "stabilizer")
    }

    @Test
    fun `the corpus exercises the cases it claims to`() {
        val rendered = corpus().mapValues { (_, s) -> render(s) }
        assertEquals(1, rendered.getValue("tap").dabCount, "the tap should be one dab")
        for ((name, s) in rendered) {
            if (name == "tap") continue
            assertTrue(s.dabCount > 20, "'$name' produced only ${s.dabCount} dabs")
            assertTrue(!s.bounds.isEmpty, "'$name' carries an empty bounds")
            for (i in 0 until s.dabCount) {
                assertTrue(s.radius(i) > 0f, "'$name' dab $i has radius ${s.radius(i)}")
                assertTrue(s.x(i).isFinite() && s.y(i).isFinite(), "'$name' dab $i is not finite")
            }
        }
        // The flick is the case that justifies resampling at all: without it,
        // 36 samples would be 36 dabs.
        assertTrue(
            rendered.getValue("flick").dabCount > 300,
            "the flick gave ${rendered.getValue("flick").dabCount} dabs; it is not a resampling case",
        )
    }
}
