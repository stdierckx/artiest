package be.thalos.artiest.engine.input

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that decides whether the pen is doing something a predictor can be
 * trusted with.
 *
 * Two of these tests carry controls, and both controls are implementations that
 * were seriously considered rather than strawmen.
 *
 * `the same corner is judged the same at both digitizer rates` drives one
 * physical arc — a fixed radius at a fixed speed — at 246.85 Hz and at
 * 321.75 Hz, the two rates this hardware actually reports, and asserts the
 * verdict holds. Beside it is the plan's original formulation, a threshold on
 * the heading change **per sample**, which flips between the two. That is the
 * whole reason the threshold is a rate.
 *
 * `a corner keeps prediction suppressed past the sample that saw it` does the
 * same for the hold. Without it the gate is correct and useless: samples arrive
 * batched and prediction is asked for once per batch, so a verdict lasting one
 * sample is read only by luck.
 */
class PredictionGateTest {

    private val slowHz = 246.85f
    private val fastHz = 321.75f

    private fun dtNanos(hz: Float): Long = (1e9f / hz).toLong()

    /**
     * Feeds an arc of radius [radiusDoc] traversed at [speedDoc] doc px/s,
     * sampled at [hz]. Returns the gate.
     *
     * An arc rather than two straight legs meeting at a vertex, and the
     * difference matters: an instantaneous corner has unbounded curvature, so
     * the turn it shows up as *does* depend on how often you sample. A real pen
     * turns through a radius, and a radius traversed at a speed has a heading
     * change per second that is a property of the motion alone. That is the
     * quantity this gate is built on.
     */
    private fun arc(
        radiusDoc: Float,
        speedDoc: Float,
        hz: Float,
        samples: Int = 40,
        gate: PredictionGate = PredictionGate(),
    ): PredictionGate {
        val dt = dtNanos(hz)
        val omega = speedDoc / radiusDoc // rad/s, the answer the gate should find
        var t = 0L
        for (i in 0 until samples) {
            val seconds = i * dt * 1e-9f
            val a = omega * seconds
            gate.push(radiusDoc * cos(a), radiusDoc * sin(a), t)
            t += dt
        }
        return gate
    }

    /** The plan's first formulation: a fixed heading change per sample. */
    private fun perSampleGateAllows(
        radiusDoc: Float,
        speedDoc: Float,
        hz: Float,
        maxTurnRad: Float,
    ): Boolean {
        val omega = speedDoc / radiusDoc
        val turnPerSample = omega / hz
        return turnPerSample <= maxTurnRad
    }

    @Test
    fun `a straight line is never a corner`() {
        val gate = PredictionGate()
        var t = 0L
        for (i in 0 until 50) {
            gate.push(100f + i * 4f, 200f, t)
            t += dtNanos(fastHz)
        }
        assertTrue(gate.allows)
        assertTrue(gate.turnRateRadPerSecond < 1e-3f, "a straight line measured curvature")
    }

    @Test
    fun `a corner suppresses prediction`() {
        // 500 doc px/s through a 2 doc px radius is 250 rad/s, comfortably past
        // the 140 rad/s threshold. That is a corner turned inside two pixels.
        val gate = arc(radiusDoc = 2f, speedDoc = 500f, hz = fastHz)
        assertFalse(gate.allows)
        assertTrue(gate.turnRateRadPerSecond > 140f)
    }

    @Test
    fun `an ordinary curve does not`() {
        // 100 mm/s around a 20 mm circle, in doc pixels at roughly 10 px/mm:
        // 1000 doc px/s through a 100 doc px radius is 10 rad/s, fourteen times
        // under the gate. Suppressing here would mean suppressing on handwriting.
        val gate = arc(radiusDoc = 100f, speedDoc = 1000f, hz = fastHz)
        assertTrue(gate.allows)
        assertEquals(10f, gate.turnRateRadPerSecond, 0.5f)
    }

    @Test
    fun `the same corner is judged the same at both digitizer rates`() {
        // 120 rad/s: chosen so a per-sample threshold of 25 degrees lands on
        // opposite sides of it at the two rates, which is what makes the
        // control below able to fail.
        val radius = 5f
        val speed = 600f // 120 rad/s
        assertEquals(120f, speed / radius, 1e-3f)

        val slow = arc(radius, speed, slowHz)
        val fast = arc(radius, speed, fastHz)
        assertTrue(slow.allows, "suppressed at 246.85 Hz")
        assertTrue(fast.allows, "suppressed at 321.75 Hz")
        assertEquals(
            slow.turnRateRadPerSecond,
            fast.turnRateRadPerSecond,
            2f,
            "the measured turn rate followed the sample rate",
        )

        // And it suppresses at both rates when it should.
        val sharpSlow = arc(radius, speed * 2f, slowHz)
        val sharpFast = arc(radius, speed * 2f, fastHz)
        assertFalse(sharpSlow.allows)
        assertFalse(sharpFast.allows)

        // The control: the plan's original per-sample angle. One physical
        // motion, two verdicts. A stabilization value tuned on the 60 Hz panel
        // would stop firing on the 90 Hz one, and nothing about the pen changed.
        val maxTurnRad = (25.0 * PI / 180.0).toFloat()
        val controlSlow = perSampleGateAllows(radius, speed, slowHz, maxTurnRad)
        val controlFast = perSampleGateAllows(radius, speed, fastHz, maxTurnRad)
        assertFalse(controlSlow, "the control allowed at 246.85 Hz")
        assertTrue(controlFast, "the control suppressed at 321.75 Hz")
        assertTrue(
            controlSlow != controlFast,
            "the control agreed with itself, so the rate-invariance claim proves nothing",
        )
    }

    @Test
    fun `the default threshold is the plan's 25 degrees at the measured rate`() {
        val perSample = PredictionGate.turnPerSampleRad(
            PredictionGate.DEFAULT_MAX_TURN_RATE_RAD_PER_S,
            fastHz,
        )
        assertEquals(25.0f, (perSample * 180.0 / PI).toFloat(), 0.1f)
        // And the same threshold is a larger per-sample angle at the slower
        // rate, which is the restatement rather than a discrepancy.
        val slowPerSample = PredictionGate.turnPerSampleRad(
            PredictionGate.DEFAULT_MAX_TURN_RATE_RAD_PER_S,
            slowHz,
        )
        assertEquals(32.5f, (slowPerSample * 180.0 / PI).toFloat(), 0.2f)
    }

    @Test
    fun `a corner keeps prediction suppressed past the sample that saw it`() {
        val dt = dtNanos(fastHz)
        val gate = PredictionGate()
        // Two legs meeting at a right angle: the turn lands on exactly one
        // sample and the heading is straight again immediately after.
        var t = 0L
        for (i in 0 until 6) {
            gate.push(i * 5f, 0f, t)
            t += dt
        }
        // Seven more samples is 21.8 ms past the corner, still inside the
        // 30 ms hold. Without the hold the verdict would have expired on the
        // very next sample, six samples ago.
        for (i in 1 until 8) {
            gate.push(25f, i * 5f, t)
            t += dt
        }
        assertFalse(gate.allows, "the corner was forgotten within the same batch")

        // It recovers once the corner is older than the hold.
        for (i in 8 until 24) {
            gate.push(25f, i * 5f, t)
            t += dt
        }
        assertTrue(gate.allows, "the gate never recovered from one corner")

        // The control: hold = 0, which is a verdict that lasts one sample.
        // Prediction is asked for once per MotionEvent and an event carries
        // three to five samples, so this verdict is read only by luck.
        val noHold = PredictionGate(holdNanos = 0L)
        var u = 0L
        for (i in 0 until 6) {
            noHold.push(i * 5f, 0f, u)
            u += dt
        }
        for (i in 1 until 8) {
            noHold.push(25f, i * 5f, u)
            u += dt
        }
        assertTrue(
            noHold.allows,
            "the control still suppressed, so the hold is not what makes the gate readable",
        )
    }

    @Test
    fun `a dwelling pen does not fabricate a turn`() {
        val gate = PredictionGate()
        val dt = dtNanos(fastHz)
        var t = 0L
        gate.push(100f, 100f, t); t += dt
        gate.push(110f, 100f, t); t += dt
        // Sub-micron jitter in random directions. atan2 is perfectly happy to
        // call two of these a 180 degree turn.
        for (i in 0 until 20) {
            val a = i * 2.3f
            gate.push(110f + 1e-4f * cos(a), 100f + 1e-4f * sin(a), t)
            t += dt
        }
        assertTrue(gate.allows, "a stationary pen was read as a corner")
    }

    @Test
    fun `a repeated timestamp is not a division by zero`() {
        val gate = PredictionGate()
        gate.push(0f, 0f, 1_000L)
        gate.push(10f, 0f, 2_000L)
        gate.push(10f, 10f, 2_000L) // same instant: MotionEvent batches do this
        assertTrue(gate.turnRateRadPerSecond.isFinite())
        assertTrue(gate.allows)
    }

    @Test
    fun `reset returns the gate to its opening verdict`() {
        val gate = arc(radiusDoc = 2f, speedDoc = 500f, hz = fastHz)
        assertFalse(gate.allows)
        gate.reset()
        assertTrue(gate.allows)
        assertEquals(0f, gate.turnRateRadPerSecond)
    }

    @Test
    fun `nonsense settings are refused at construction`() {
        assertFailsWith<IllegalArgumentException> { PredictionGate(maxTurnRateRadPerSecond = 0f) }
        assertFailsWith<IllegalArgumentException> { PredictionGate(holdNanos = -1L) }
    }
}
