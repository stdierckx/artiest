package be.thalos.artiest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the light stands, as a ball you can turn it around on.
 *
 * ## The two things that were wrong before
 *
 * The first version drew a sun on a flat dotted circle and moved the light by
 * adding degrees to an azimuth and an elevation. Both halves were reported from
 * the tablet as confusing, and both deserved it:
 *
 * > *"the movement of the light is still hard to understand and sometimes does
 * > not match the icon of the light"*
 *
 * **It did not match because the drag was in the model's frame and the marker
 * was in the camera's.** Adding to an azimuth turns the lamp around the world's
 * up axis, which is a different direction on the screen depending on where the
 * camera is standing — so with the model turned half round, dragging right
 * moved the sun left. The fix is that a drag now rotates the light *about the
 * axis the finger is turning it about*: the sun goes where the finger goes,
 * always, whatever the model has been spun to. That is an arcball, and it is
 * what every 3D program uses for exactly this reason.
 *
 * **And a flat disc cannot say which side.** A lamp in front and a lamp behind
 * at mirrored heights land on the same spot in a flat projection, and there is
 * no drawing that distinguishes them. So the ball is drawn as a ball: three
 * great circles, the near half of each solid and the far half faint, and the
 * sun filled when it is on the near side and hollow when it is behind.
 *
 * > *"I have the feeling i want to have 3 axis to move the light on..."*
 *
 * Three rings is what that instinct is asking for, and they are the three you
 * would expect — the model's own horizontal, vertical and front-to-back planes.
 * What the arcball adds is that you do not have to pick one first: a drag in
 * any direction walks the lamp along the ring that direction implies, all the
 * way over the top and round the back if you keep going.
 */

/** The camera's three axes, from the two angles the pane keeps. */
internal data class Basis(
    val rx: Float, val ry: Float, val rz: Float,
    val ux: Float, val uy: Float, val uz: Float,
    val ex: Float, val ey: Float, val ez: Float,
)

/**
 * Right, up, and the direction the eye is standing in.
 *
 * Derived rather than stored, because the camera *is* [PaneView.spin] and
 * [PaneView.tilt] and a second copy of it would be a second thing to keep in
 * step. The camera never rolls, so crossing the eye direction with world up is
 * the whole derivation; tilt is why that cross product cannot be zero.
 */
internal fun basisFor(spin: Float, tilt: Float): Basis {
    val s = spin * DEG
    val t = tilt * DEG
    val ex = cos(t) * sin(s)
    val ey = sin(t)
    val ez = cos(t) * cos(s)
    return Basis(
        rx = cos(s), ry = 0f, rz = -sin(s),
        ux = -sin(s) * sin(t), uy = cos(t), uz = -cos(s) * sin(t),
        ex = ex, ey = ey, ez = ez,
    )
}

/** Where a unit vector lands: across, up, and towards the eye. */
internal fun project(x: Float, y: Float, z: Float, b: Basis): Triple<Float, Float, Float> =
    Triple(
        x * b.rx + y * b.ry + z * b.rz,
        x * b.ux + y * b.uy + z * b.uz,
        x * b.ex + y * b.ey + z * b.ez,
    )

/** The lamp's direction from the model, as a unit vector. */
internal fun lightVector(azimuth: Float, elevation: Float): Triple<Float, Float, Float> {
    val a = azimuth * DEG
    val e = elevation * DEG
    return Triple(cos(e) * sin(a), sin(e), cos(e) * cos(a))
}

/**
 * Turn the lamp by a drag of [dx], [dy] pane pixels, and answer the new angles.
 *
 * The arcball. The finger's movement is a direction in the plane of the screen,
 * `m = dx·right − dy·up`, and the lamp is turned about the axis `m × forward` —
 * which lies in the plane of the screen at right angles to the drag, so the
 * lamp travels along the drag.
 *
 * ## Why the axis comes from the finger and not from the lamp
 *
 * The obvious axis is `L × m`: it is the one whose rotation moves `L` exactly
 * along `m`, and it was the first version. It has a hole in it, and the hole is
 * at the pole. `L × m` points one way while `L` is on one side of `m` and the
 * other way once it has passed, so a lamp dragged straight up climbs to the top
 * of the sphere and then bounces back down, over and over, however far the
 * finger keeps going. Measured rather than reasoned about: the trace stuck at
 * 80 degrees and flipped between azimuth 0 and 180 for the next seventeen
 * steps.
 *
 * An axis that depends only on the drag has no such hole. It is the same axis
 * wherever the lamp happens to be, so a finger that keeps moving keeps turning
 * the lamp the same way — over the top, down the back, and round again.
 */
internal fun turnedLight(
    azimuth: Float,
    elevation: Float,
    dx: Float,
    dy: Float,
    spin: Float,
    tilt: Float,
): Pair<Float, Float> {
    val reach = sqrt(dx * dx + dy * dy)
    if (reach < 1e-4f) return azimuth to elevation
    val b = basisFor(spin, tilt)
    val (lx, ly, lz) = lightVector(azimuth, elevation)

    // The finger's direction, put back into the world.
    val mx = dx * b.rx - dy * b.ux
    val my = dx * b.ry - dy * b.uy
    val mz = dx * b.rz - dy * b.uz

    // Forward is the eye direction reversed: from the camera towards the model.
    val fx = -b.ex
    val fy = -b.ey
    val fz = -b.ez

    var ax = my * fz - mz * fy
    var ay = mz * fx - mx * fz
    var az = mx * fy - my * fx
    val axisLength = sqrt(ax * ax + ay * ay + az * az)
    if (axisLength < 1e-6f) return azimuth to elevation
    ax /= axisLength
    ay /= axisLength
    az /= axisLength

    val angle = reach * RADIANS_PER_PX
    val c = cos(angle)
    val s = sin(angle)
    val dot = ax * lx + ay * ly + az * lz
    val nx = lx * c + (ay * lz - az * ly) * s + ax * dot * (1 - c)
    val ny = ly * c + (az * lx - ax * lz) * s + ay * dot * (1 - c)
    val nz = lz * c + (ax * ly - ay * lx) * s + az * dot * (1 - c)

    val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
    return Pair(
        atan2(nx / length, nz / length) / DEG,
        asin((ny / length).coerceIn(-1f, 1f)) / DEG,
    )
}

/**
 * The ball, drawn over the model while the light is being moved.
 *
 * Nothing is added to the 3D scene: it is all arithmetic on the four angles the
 * pane already holds, so the marker cannot get out of step with the light it is
 * drawing, and it cannot cost a rendered frame.
 */
@Composable
fun LightBall(pane: PaneView, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val sun = scheme.primary
    val near = scheme.onSurfaceVariant.copy(alpha = 0.62f)
    val far = scheme.onSurfaceVariant.copy(alpha = 0.22f)
    // The lamp behind the model is still the lamp, so it keeps its colour and
    // loses its fill rather than losing both. A grey outline read as part of
    // the wireframe on the tablet.
    val dimSun = sun.copy(alpha = 0.55f)
    Canvas(modifier) {
        val radius = minOf(size.width, size.height) * 0.35f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val b = basisFor(pane.spin, pane.tilt)

        // The three rings: the model's own level, upright and front-to-back
        // planes. Named that way round on purpose — they are the planes a
        // draughtsman already thinks in, not the renderer's x, y and z.
        ring(centre, radius, b, 0, near, far)
        ring(centre, radius, b, 1, near, far)
        ring(centre, radius, b, 2, near, far)

        val (lx, ly, lz) = lightVector(pane.lightAzimuth, pane.lightElevation)
        val (px, py, depth) = project(lx, ly, lz, b)
        val here = centre + Offset(px * radius, -py * radius)
        val front = depth >= 0f

        // The stand: the lamp is somewhere on a sphere around the model, and
        // this is the line from one to the other. It shortens as the lamp goes
        // behind, which is the cue that tells the eye what the flat picture
        // cannot.
        drawLine(
            color = if (front) near else far,
            start = centre,
            end = here,
            strokeWidth = 1.6f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f)),
        )

        val glyph = 11f
        for (i in 0 until 8) {
            val angle = i * (Math.PI.toFloat() / 4f)
            val step = Offset(cos(angle), sin(angle))
            drawLine(
                color = if (front) sun else dimSun,
                start = here + step * (glyph * 1.45f),
                end = here + step * (glyph * 2.05f),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        }
        if (front) {
            drawCircle(sun, glyph, here)
        } else {
            drawCircle(dimSun, glyph, here, style = Stroke(width = 2.6f))
        }
    }
}

/**
 * One great circle of the ball, its near half solid and its far half faint.
 *
 * Drawn as a polyline of sampled points rather than as an ellipse, which is
 * what a projected circle actually is: the ellipse would need its axes and its
 * rotation worked out for every camera angle, and the half that is in front
 * would still have to be found. Seventy-two points is smooth at any size this
 * is drawn and is a rounding error next to one rendered frame.
 */
private fun DrawScope.ring(
    centre: Offset,
    radius: Float,
    b: Basis,
    axis: Int,
    near: Color,
    far: Color,
) {
    var last: Offset? = null
    var lastFront = true
    for (i in 0..RING_POINTS) {
        val a = i * (2f * Math.PI.toFloat() / RING_POINTS)
        val c = cos(a)
        val s = sin(a)
        val x: Float
        val y: Float
        val z: Float
        when (axis) {
            0 -> { x = c; y = 0f; z = s }   // level
            1 -> { x = 0f; y = c; z = s }   // upright, front to back
            else -> { x = c; y = s; z = 0f } // upright, side to side
        }
        val (px, py, depth) = project(x, y, z, b)
        val at = centre + Offset(px * radius, -py * radius)
        val front = depth >= 0f
        val previous = last
        if (previous != null) {
            drawLine(
                color = if (front && lastFront) near else far,
                start = previous,
                end = at,
                strokeWidth = if (front && lastFront) 1.4f else 1.1f,
            )
        }
        last = at
        lastFront = front
    }
}

/**
 * How far a drag turns the lamp.
 *
 * A drag of about eight hundred pixels — the width of the pane on the tablet —
 * takes the lamp half way round, which is far enough to get behind the model in
 * one movement and slow enough to place a rim light on a cheekbone.
 */
private const val RADIANS_PER_PX = 0.0039f

private const val RING_POINTS = 72

private const val DEG = (Math.PI / 180.0).toFloat()
