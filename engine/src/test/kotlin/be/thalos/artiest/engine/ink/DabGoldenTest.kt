package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.input.PenSample
import java.io.File
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

    /**
     * The corpus and its sample factory now live in [StrokeCorpus], because W0's
     * dab-loop bench measures the same strokes these goldens pin. They were
     * moved verbatim; if this file's goldens had shifted during that move, the
     * move was not verbatim.
     */
    private fun sample(x: Float, y: Float, p: Float, i: Int) =
        StrokeCorpus.sample(x, y, p, i)

    private fun corpus(): Map<String, List<PenSample>> = StrokeCorpus.all()

    private fun render(samples: List<PenSample>, pen: Brush = Brush()): Stroke {
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
    private fun serialize(name: String, pen: Brush, samples: Int, s: Stroke): String {
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
            val pen = Brush()
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
        fun renderAll(pen: () -> Brush): String =
            strokes.entries.joinToString("\n") { (n, s) ->
                val p = pen()
                serialize(n, p, s.size, render(s, p))
            }

        val baseline = renderAll { Brush() }

        assertNotEquals(baseline, renderAll { Brush().apply { spacing = 0.13f } }, "arc walk")
        assertNotEquals(baseline, renderAll { Brush().apply { sizeMax = 24.5f } }, "size curve")
        assertNotEquals(baseline, renderAll { Brush().apply { pressureCurve = 2.9f } }, "pressure curve")
        assertNotEquals(baseline, renderAll { Brush().apply { onsetMillis = 11f } }, "onset ramp")
        assertNotEquals(baseline, renderAll { Brush().apply { onsetPressure = 0.26f } }, "onset lift")
        assertNotEquals(baseline, renderAll { Brush().apply { stabilization = 0.16f } }, "stabilizer")
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
