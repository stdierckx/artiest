package be.thalos.artiest.canvas

import android.graphics.Matrix
import be.thalos.artiest.engine.xform.CanvasTransform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two implementations of one mapping, checked against each other over 11200
 * point mappings.
 *
 * `:engine` maps points with hand-written float math; `Matrices.kt` builds an
 * `android.graphics.Matrix` that has to agree. Nothing makes them agree except
 * this file. Every way they can part company is quiet — a `pre` where a `post`
 * belongs, a translation left in document units, `setRotate` where `setSinCos`
 * belongs — and all three are exactly equal at rotation 0 and scale 1, which is
 * the state anything gets eyeballed in. What they produce on a tablet is ink
 * that lands somewhere other than the pen, three modules from the mistake.
 *
 * Expected values are written out from the equation rather than read back from
 * either implementation, so a change that moves both together still fails. The
 * sweep is proved able to tell those failures apart rather than assumed to be.
 * Two of them are run through it as controls — the same sweep with the wrong
 * implementation substituted — and both have to come apart by a wide margin for
 * the passing tests to mean anything: `pre` for `post` misses by 52800 px, and
 * a translation left in document units by 23100 px. The third is the `setRotate`
 * snap, which is not a drift but a total loss of the angle, so it gets a test of
 * its own rather than a sweep.
 *
 * `@GraphicsMode(NATIVE)`, and it is load-bearing. Robolectric's default
 * resolves `Matrix` to `ShadowLegacyMatrix`, a Java reimplementation computing
 * in double; NATIVE resolves it to `ShadowNativeMatrix`, which calls into
 * Skia. Both were probed under these exact annotations rather than taken from
 * the annotation's name, because the failure mode is a test that reports
 * agreement with a stand-in:
 *
 * ```
 * shadow class        setRotate(90)[0]   setRotate(0.0139 deg) skew
 * ShadowLegacyMatrix  6.123234E-17       -2.4260076E-4   (angle kept)
 * ShadowNativeMatrix  0.0                -0.0            (angle snapped)
 * ```
 *
 * 6.123234E-17 is the *double* cos(pi/2); Skia returns exactly 0, and float
 * cos would give -4.371139E-8 — three implementations, three answers, so the
 * first column identifies which one is running. So this pins composition order,
 * pre/post placement and the units of every argument against the library the
 * tablet runs, not against a stand-in.
 *
 * The mode is self-enforcing rather than trusted:
 * `a rotation too small for setRotate is not too small to draw` asserts a snap
 * that only Skia performs, so a Robolectric upgrade that quietly drops back to
 * the legacy shadow fails that test instead of going green against double
 * arithmetic. It is the tripwire, not a convenience.
 *
 * The remaining gap is the host: this is Skia built for x86-64, not for the
 * tablet's arm64. Float arithmetic is deterministic across both, but a
 * platform's `sin`/`cos` need not be — which is the second reason the matrix is
 * built from [CanvasTransform.rotationSin]/[CanvasTransform.rotationCos]
 * instead of from an angle. With the trig handed over rather than recomputed,
 * there is no libm left inside the comparison.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MatricesTest {

    /**
     * The one that would have caught every mutation this file was checked
     * against. The engine-against-the-equation half is weaker than it looks —
     * it is the same expression in the same order, so it catches a transcription
     * change in `:engine` and nothing subtler — but the matrix half is a wholly
     * different route to the same number, and that is where drift lives.
     */
    @Test
    fun `the matrix maps every point the engine's float math maps`() {
        val matrix = Matrix()
        val engineOut = FloatArray(2)
        val point = FloatArray(2)
        var worstMatrix = 0f
        var worstMatrixAt = ""
        var worstEngine = 0f
        var worstEngineAt = ""
        sweep { t, s, rot, tx, ty ->
            matrix.setDocToView(t)
            for ((xDoc, yDoc) in DOC_POINTS) {
                val exView = s * (cos(rot) * xDoc - sin(rot) * yDoc + tx)
                val eyView = s * (sin(rot) * xDoc + cos(rot) * yDoc + ty)

                point[0] = xDoc
                point[1] = yDoc
                matrix.mapPoints(point)
                val em = maxOf(abs(point[0] - exView), abs(point[1] - eyView))
                if (em > worstMatrix) {
                    worstMatrix = em
                    worstMatrixAt = "s=$s rot=$rot t=($tx,$ty) doc=($xDoc,$yDoc)"
                }

                t.docToView(xDoc, yDoc, engineOut)
                val ee = maxOf(abs(engineOut[0] - exView), abs(engineOut[1] - eyView))
                if (ee > worstEngine) {
                    worstEngine = ee
                    worstEngineAt = "s=$s rot=$rot t=($tx,$ty) doc=($xDoc,$yDoc)"
                }
            }
        }
        println("FWD matrix vs equation: $worstMatrix px @ $worstMatrixAt")
        // No location when the drift is 0.0: there is no worst case to name,
        // and a bare trailing "@" reads like the tracking broke rather than
        // like the engine reproduced the equation exactly, which it does.
        println("FWD engine vs equation: $worstEngine px${if (worstEngineAt.isEmpty()) "" else " @ $worstEngineAt"}")
        assertTrue(worstMatrix <= FORWARD_TOLERANCE_PX, "matrix drifted $worstMatrix px at $worstMatrixAt")
        assertTrue(worstEngine <= FORWARD_TOLERANCE_PX, "engine drifted $worstEngine px at $worstEngineAt")
    }

    @Test
    fun `the inverted matrix agrees with viewToDoc`() {
        val forward = Matrix()
        val inverse = Matrix()
        val engineOut = FloatArray(2)
        val point = FloatArray(2)
        var worst = 0f
        var worstAt = ""
        sweep { t, s, rot, tx, ty ->
            forward.setDocToView(t)
            assertTrue(forward.invert(inverse), "not invertible at s=$s rot=$rot")
            for ((xDoc, yDoc) in DOC_POINTS) {
                val xView = s * (cos(rot) * xDoc - sin(rot) * yDoc + tx)
                val yView = s * (sin(rot) * xDoc + cos(rot) * yDoc + ty)
                point[0] = xView
                point[1] = yView
                inverse.mapPoints(point)
                t.viewToDoc(xView, yView, engineOut)
                val e = maxOf(abs(point[0] - engineOut[0]), abs(point[1] - engineOut[1]))
                if (e > worst) {
                    worst = e
                    worstAt = "s=$s rot=$rot t=($tx,$ty) doc=($xDoc,$yDoc)"
                }
            }
        }
        println("INV matrix vs engine: $worst doc @ $worstAt")
        assertTrue(worst <= INVERSE_TOLERANCE_DOC, "inverse drifted $worst doc units at $worstAt")
    }

    @Test
    fun `the engine's coefficients build the same matrix`() {
        val built = Matrix()
        val scratch = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        val values = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        var worst = 0f
        var worstAt = ""
        sweep { t, s, rot, tx, ty ->
            built.setDocToView(t)
            t.docToViewCoefficients(scratch)
            built.getValues(values)
            for (i in 0 until CanvasTransform.COEFFICIENT_COUNT) {
                val e = abs(values[i] - scratch[i])
                if (e > worst) {
                    worst = e
                    worstAt = "index $i, s=$s rot=$rot t=($tx,$ty)"
                }
            }
        }
        println("COEFF setSinCos path vs engine coefficients: $worst @ $worstAt")
        assertTrue(worst <= COEFFICIENT_TOLERANCE, "coefficients drifted $worst at $worstAt")
    }

    @Test
    fun `preTranslate builds a different transform than postTranslate`() {
        val right = Matrix()
        val wrong = Matrix()
        val a = FloatArray(2)
        val b = FloatArray(2)
        var worst = 0f
        sweep { t, s, _, _, _ ->
            right.setDocToView(t)
            wrong.setSinCos(t.rotationSin, t.rotationCos)
            wrong.preTranslate(t.txDoc, t.tyDoc)
            wrong.postScale(s, s)
            for ((xDoc, yDoc) in DOC_POINTS) {
                a[0] = xDoc; a[1] = yDoc
                b[0] = xDoc; b[1] = yDoc
                right.mapPoints(a)
                wrong.mapPoints(b)
                worst = maxOf(worst, abs(a[0] - b[0]), abs(a[1] - b[1]))
            }
        }
        println("CONTROL preTranslate vs postTranslate: $worst px")
        assertTrue(worst > 1000f, "the sweep cannot tell the two apart: $worst px")
    }

    /**
     * The second control, for the failure the first one cannot see.
     *
     * `postTranslate` is applied after the scale, so it takes view pixels and
     * the argument has to be `scale * txDoc`. Handing it [CanvasTransform.txDoc]
     * raw is the mistake the units are named to prevent, and it is quieter than
     * a `pre`/`post` swap: this one is exactly right at scale 1, so it survives
     * every check made at 1:1 and every check made on a freshly opened canvas.
     * It goes wrong in proportion to the zoom, which reads as a canvas that
     * slides as you pinch rather than as a units bug.
     */
    @Test
    fun `postTranslate takes view pixels and document units are not close enough`() {
        val right = Matrix()
        val wrong = Matrix()
        val a = FloatArray(2)
        val b = FloatArray(2)
        var worst = 0f
        var worstAt = ""
        var worstAtUnitScale = 0f
        sweep { t, s, _, _, _ ->
            right.setDocToView(t)
            wrong.setSinCos(t.rotationSin, t.rotationCos)
            wrong.postScale(s, s)
            wrong.postTranslate(t.txDoc, t.tyDoc)
            for ((xDoc, yDoc) in DOC_POINTS) {
                a[0] = xDoc; a[1] = yDoc
                b[0] = xDoc; b[1] = yDoc
                right.mapPoints(a)
                wrong.mapPoints(b)
                val e = maxOf(abs(a[0] - b[0]), abs(a[1] - b[1]))
                if (e > worst) {
                    worst = e
                    worstAt = "s=$s t=(${t.txDoc},${t.tyDoc}) doc=($xDoc,$yDoc)"
                }
                if (s == 1f) worstAtUnitScale = maxOf(worstAtUnitScale, e)
            }
        }
        println("CONTROL raw txDoc vs scale*txDoc: $worst px @ $worstAt")
        println("CONTROL raw txDoc at scale 1: $worstAtUnitScale px")
        assertTrue(worst > 1000f, "the sweep cannot tell view pixels from document units: $worst px")
        // The other half of the claim, and the reason the sweep has to carry
        // scales it will never open on. At scale 1 the bug is not small, it is
        // absent, so a suite that only ever built identity-scaled transforms
        // would report this mutation as correct.
        assertEquals(0f, worstAtUnitScale, "the mutation is supposed to be invisible at scale 1")
    }

    /**
     * The stroke snapshot has to be a matrix nobody else holds.
     *
     * [docToViewMatrix] allocates per pen-down, and that allocation is the
     * whole publication argument: a `Matrix` is mutable native state, so the
     * one handed to the render thread is safe only while nothing writes it
     * again. Pooling or caching the instance is the obvious optimization and it
     * costs order 1 Hz to refuse — this is here so a later reader who tries it
     * gets a failing test instead of the race `:spike` recorded at
     * `DirectSurfaceInkView.kt:154`.
     */
    @Test
    fun `each stroke snapshot is a matrix nobody else holds`() {
        val t = CanvasTransform.IDENTITY.zoomedTo(2f).pannedByView(120f, -40f)
        val first = docToViewMatrix(t)
        val second = docToViewMatrix(t)
        assertTrue(first !== second, "docToViewMatrix handed out a shared instance")

        val firstValues = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        val secondValues = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        first.getValues(firstValues)
        second.getValues(secondValues)
        assertTrue(
            firstValues.contentEquals(secondValues),
            "two snapshots of one transform disagree: ${firstValues.toList()} vs ${secondValues.toList()}",
        )

        // Writing one must not reach the other. This is the shape of the race
        // itself: the render thread concats `first` while a second pen-down
        // rebuilds the transform.
        second.postTranslate(17f, 23f)
        first.getValues(firstValues)
        second.getValues(secondValues)
        assertTrue(!firstValues.contentEquals(secondValues), "the two snapshots share state")
    }

    /**
     * Half of this test is about the function the code does not call, which is
     * the only way to state why it does not call it.
     *
     * 2e-4 rad is 0.011 degrees, under Skia's SK_ScalarNearlyZero of 1/4096, so
     * `setRotate` throws it away and reports a level canvas. It is a plausible
     * angle: it is where a twist gesture starts, and a canvas that stays level
     * for the first frames of a rotation while `:engine` maps samples through
     * the real angle puts ink where the artist did not put it.
     */
    @Test
    fun `a rotation too small for setRotate is not too small to draw`() {
        val values = FloatArray(CanvasTransform.COEFFICIENT_COUNT)
        val rad = 2.0e-4f
        Matrix().apply { setRotate(Math.toDegrees(rad.toDouble()).toFloat()) }.getValues(values)
        println("SNAP setRotate(${Math.toDegrees(rad.toDouble())} deg) skew=${values[1]}")
        // Skia stores -0.0, which is `== 0f` but not `equals(0f)`. Compared as
        // a primitive on purpose: the claim is that the angle is gone, not that
        // a particular zero came back.
        assertTrue(values[1] == 0f, "setRotate no longer snaps; the reason for setSinCos has changed")

        val t = CanvasTransform.IDENTITY.rotatedBy(rad)
        Matrix().apply { setDocToView(t) }.getValues(values)
        println("SNAP setDocToView skew=${values[1]} engine sin=${t.rotationSin}")
        assertEquals(-t.rotationSin, values[1])
    }

    @Test
    fun `the fitted portrait document lands on the panel edges`() {
        val t = CanvasTransform.fitTo(PANEL_W, PANEL_H, DOC_W, DOC_H)
        val m = docToViewMatrix(t)
        val corner = floatArrayOf(0f, 0f, DOC_W.toFloat(), DOC_H.toFloat())
        m.mapPoints(corner)
        println("FIT corners=${corner.toList()} scale=${t.scale}")
        assertEquals(0f, corner[0], FORWARD_TOLERANCE_PX)
        assertEquals(0f, corner[1], FORWARD_TOLERANCE_PX)
        assertEquals(PANEL_W.toFloat(), corner[2], FORWARD_TOLERANCE_PX)
        assertEquals(PANEL_H.toFloat(), corner[3], FORWARD_TOLERANCE_PX)
    }

    private inline fun sweep(body: (CanvasTransform, Float, Float, Float, Float) -> Unit) {
        for (s in SCALES) for (rot in ROTATIONS) for (tx in TRANSLATIONS) {
            val ty = -tx * 0.37f
            body(CanvasTransform(s, rot, tx, ty), s, rot, tx, ty)
        }
    }

    private companion object {
        /**
         * Measured, not chosen. Over the sweep below the worst disagreement
         * between the matrix and the equation is 0.00390625 view px, at
         * s=5.5 rot=0.001 t=(-2160,799.2) on the document corner (0,3300),
         * where the view coordinate is 2.25e4 px and one ulp is 0.00195 — so
         * the disagreement is two ulp of the answer, and this leaves 2.5x.
         *
         * Loose in absolute terms and still enormously tight against what it
         * guards: the wrong composition order misses by 52800 px, which the
         * control test measures rather than assumes.
         */
        const val FORWARD_TOLERANCE_PX = 0.01f

        /** Worst measured: 9.765625E-4 document units, at the far corner at s=0.5. */
        const val INVERSE_TOLERANCE_DOC = 0.005f

        /**
         * Exact, and measured exact over all 1120 transforms. Both paths form
         * the same products — `scale * cos`, `scale * txDoc` — out of the same
         * two floats, so bit-identity is structural rather than luck, and a
         * tolerance here would only hide a Skia that stopped agreeing.
         */
        const val COEFFICIENT_TOLERANCE = 0f

        const val DOC_W = 2160
        const val DOC_H = 3300
        const val PANEL_W = 1440
        const val PANEL_H = 2200

        /** Both clamp endpoints included: they are where a gesture spends its extremes. */
        val SCALES = floatArrayOf(0.5f, 0.7071068f, 1f, 1.3333334f, 2f, 3f, 5.5f, 8f)

        /**
         * The small angles are here because that is where `setRotate` would
         * have snapped, and both ends of (-PI, PI] because `-PI` is reachable:
         * the constructor does not normalize, so `TracePlayer` can rebuild a
         * transform holding an angle `rotatedBy` would never have left behind.
         */
        val ROTATIONS = floatArrayOf(
            0f, 1e-7f, 1e-5f, 1e-4f, 1e-3f, 0.01f, 0.017453292f, -1e-4f,
            (PI / 6.0).toFloat(), (PI / 4.0).toFloat(), (PI / 2.0).toFloat(),
            (3.0 * PI / 4.0).toFloat(), 2f, -2f,
            -(PI / 4.0).toFloat(), -(PI / 2.0).toFloat(),
            3.1415925f, PI.toFloat(), -3.1415925f, -PI.toFloat(),
        )

        val TRANSLATIONS = floatArrayOf(0f, -1080f, 3300f, -0.5f, 1234.5f, -3300f, -2160f)

        val DOC_POINTS = arrayOf(
            0f to 0f,
            DOC_W.toFloat() to 0f,
            0f to DOC_H.toFloat(),
            DOC_W.toFloat() to DOC_H.toFloat(),
            (DOC_W / 2).toFloat() to (DOC_H / 2).toFloat(),
            -1f to -1f,
            (DOC_W + 1).toFloat() to (DOC_H + 1).toFloat(),
            0.5f to (DOC_H - 0.5f),
            1f to (DOC_H - 1).toFloat(),
            (DOC_W / 2).toFloat() to 0f,
        )
    }
}
