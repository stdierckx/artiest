package be.thalos.artiest.engine.ink

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.ToolType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The eight strokes every measurement of this engine is taken against.
 *
 * Lifted verbatim out of [DabGoldenTest] at the start of Phase 2, unchanged, so
 * that the goldens and W0's dab-loop bench are looking at the same strokes. That
 * matters more than it sounds: W3 changes dab spacing on purpose, and the only
 * way "the goldens moved by the amount the bench predicted" is a checkable
 * sentence is if both read the same corpus. A bench with its own private
 * fixture would drift from the goldens silently and would then be evidence for
 * nothing.
 *
 * **Deliberately not a recording.** Each entry is hand-reasoned so that when one
 * of them moves it is obvious which stage moved it — a recorded stroke
 * perturbs every stage at once and localises nothing.
 *
 * **Every sample here has `tilt = 0` and `orientation = 0`**, which is correct
 * for a Phase 1 baseline and will not stay correct. W9 adds the tilt cases, and
 * it adds them *then* rather than now on purpose: adding corpus entries
 * regenerates goldens, and W0's whole job is to photograph the engine before
 * anything moves. Adding a stroke here is never free — see [DabGoldenTest].
 */
object StrokeCorpus {

    /** 321.75 Hz, the measured digitizer rate at 90 Hz. */
    const val DT_NANOS: Long = 3_107_855L

    fun sample(x: Float, y: Float, p: Float, i: Int) = PenSample(
        x = x,
        y = y,
        pressure = p,
        tilt = 0f,
        orientation = 0f,
        distance = 0f,
        toolType = ToolType.STYLUS,
        buttonState = 0,
        eventTimeNanos = i * DT_NANOS,
        source = PenSample.Source.CURRENT,
    )

    /**
     * The corpus. Each entry is a hand-reasoned case rather than a recording,
     * so that when one of them moves it is obvious which stage moved it.
     */
    fun all(): Map<String, List<PenSample>> = linkedMapOf(
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
}
