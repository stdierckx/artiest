package be.thalos.artiest.ui

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arcball, as arithmetic.
 *
 * The defect this replaced was reported as *"the movement of the light is still
 * hard to understand and sometimes does not match the icon"*, and its cause was
 * a frame mismatch: the drag turned the lamp about the world's up axis while
 * the marker was drawn in the camera's. Nothing about that shows up in a
 * screenshot of one camera angle — it only appears once the model has been spun
 * — which is exactly what makes it worth a test rather than an eye.
 *
 * So the property under test is the one the hand actually cares about: **the
 * sun goes where the finger goes, at every camera angle.**
 */
class LightBallTest {

    private fun screen(azimuth: Float, elevation: Float, spin: Float, tilt: Float): Pair<Float, Float> {
        val b = basisFor(spin, tilt)
        val (x, y, z) = lightVector(azimuth, elevation)
        val (px, py, _) = project(x, y, z, b)
        return px to py
    }

    private fun depth(azimuth: Float, elevation: Float, spin: Float, tilt: Float): Float {
        val b = basisFor(spin, tilt)
        val (x, y, z) = lightVector(azimuth, elevation)
        return project(x, y, z, b).third
    }

    /**
     * The property the hand cares about, and the one the old arithmetic broke.
     *
     * A lamp on the near side of the ball follows the finger. A lamp on the far
     * side goes the other way — and that is right, not a bug: it is what the
     * far side of a trackball does under your thumb, and it is why the ball is
     * drawn as a ball with a near half and a far half rather than as a flat
     * disc. What was wrong before was that the sun went the wrong way on the
     * *near* side too, once the model had been spun, because the drag turned
     * the lamp about the world's up axis and the marker was drawn in the
     * camera's frame.
     */
    @Test
    fun `on the near side the sun follows the finger, at every camera angle`() {
        for (spin in listOf(0f, 47f, 90f, 143f, 180f, 251f, -95f)) {
            for (tilt in listOf(-40f, 0f, 25f)) {
                // Put the lamp where the camera is, so it is certainly in front.
                val azimuth = spin
                val elevation = tilt
                assertTrue(depth(azimuth, elevation, spin, tilt) > 0.5f, "not in front at $spin/$tilt")

                val before = screen(azimuth, elevation, spin, tilt)
                val right = turnedLight(azimuth, elevation, 60f, 0f, spin, tilt)
                val down = turnedLight(azimuth, elevation, 0f, 60f, spin, tilt)
                val afterRight = screen(right.first, right.second, spin, tilt)
                val afterDown = screen(down.first, down.second, spin, tilt)

                assertTrue(
                    afterRight.first > before.first + 0.05f,
                    "at spin $spin tilt $tilt, right took it from ${before.first} to ${afterRight.first}",
                )
                assertTrue(
                    afterDown.second < before.second - 0.05f,
                    "at spin $spin tilt $tilt, down took it from ${before.second} to ${afterDown.second}",
                )
            }
        }
    }

    /** And the far side goes the other way, the way a trackball's far side does. */
    @Test
    fun `on the far side the sun goes the other way`() {
        for (spin in listOf(0f, 90f, 200f)) {
            val azimuth = spin + 180f
            assertTrue(depth(azimuth, 0f, spin, 0f) < -0.5f, "not behind at $spin")
            val before = screen(azimuth, 0f, spin, 0f)
            val right = turnedLight(azimuth, 0f, 60f, 0f, spin, 0f)
            val after = screen(right.first, right.second, spin, 0f)
            assertTrue(after.first < before.first, "at spin $spin it went ${before.first} to ${after.first}")
        }
    }

    /**
     * The old arithmetic could not do this at all: it clamped the elevation,
     * so the lamp could never get over the top, and it turned about the world
     * axis, so from behind the model a drag went the wrong way.
     */
    @Test
    fun `keeping dragging takes the lamp over the top and round the back`() {
        var azimuth = 0f
        var elevation = 0f
        assertTrue(depth(azimuth, elevation, 0f, 0f) > 0f, "it should start in front")
        var wentBehind = false
        repeat(24) {
            val next = turnedLight(azimuth, elevation, 0f, -60f, 0f, 0f)
            azimuth = next.first
            elevation = next.second
            if (depth(azimuth, elevation, 0f, 0f) < -0.5f) wentBehind = true
        }
        assertTrue(wentBehind, "twenty-four drags upward never reached the far side")
    }

    /** A lamp is a direction, and a direction stays one however far it is turned. */
    @Test
    fun `the lamp stays on the sphere`() {
        var azimuth = 12f
        var elevation = -7f
        repeat(200) { step ->
            val next = turnedLight(azimuth, elevation, 37f, -23f, step * 7f, 20f)
            azimuth = next.first
            elevation = next.second
            val (x, y, z) = lightVector(azimuth, elevation)
            val length = sqrt(x * x + y * y + z * z)
            assertTrue(abs(length - 1f) < 1e-3f, "length drifted to $length at step $step")
            assertTrue(azimuth.isFinite() && elevation.isFinite(), "went to $azimuth / $elevation")
        }
    }

    @Test
    fun `a finger that has not moved does not move the light`() {
        val (a, e) = turnedLight(-35f, 34f, 0f, 0f, 61f, 12f)
        assertEquals(-35f, a)
        assertEquals(34f, e)
    }

    /**
     * The camera basis has to be a right-handed frame or everything drawn on it
     * is mirrored — the sort of thing that looks almost right and is never
     * right.
     */
    @Test
    fun `the camera axes are a proper frame`() {
        for (spin in listOf(0f, 33f, 120f, -80f)) {
            for (tilt in listOf(-55f, 0f, 70f)) {
                val b = basisFor(spin, tilt)
                fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) =
                    ax * bx + ay * by + az * bz
                assertTrue(abs(dot(b.rx, b.ry, b.rz, b.ux, b.uy, b.uz)) < 1e-4f, "right and up at $spin/$tilt")
                assertTrue(abs(dot(b.rx, b.ry, b.rz, b.ex, b.ey, b.ez)) < 1e-4f, "right and eye at $spin/$tilt")
                assertTrue(abs(dot(b.ux, b.uy, b.uz, b.ex, b.ey, b.ez)) < 1e-4f, "up and eye at $spin/$tilt")
                for (v in listOf(
                    Triple(b.rx, b.ry, b.rz), Triple(b.ux, b.uy, b.uz), Triple(b.ex, b.ey, b.ez),
                )) {
                    val length = sqrt(v.first * v.first + v.second * v.second + v.third * v.third)
                    assertTrue(abs(length - 1f) < 1e-4f, "length $length at $spin/$tilt")
                }
            }
        }
    }
}
