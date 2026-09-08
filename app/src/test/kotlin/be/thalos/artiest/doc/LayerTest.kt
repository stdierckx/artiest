package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The alpha-carrying invariant and the lock discipline, as tests rather than as
 * comments.
 *
 * `@RunWith(RobolectricTestRunner)` **and** `@Config(sdk = [34])` **and**
 * `@GraphicsMode(NATIVE)`. All three, and the middle one is the trap: with
 * `@GraphicsMode(NATIVE)` alone Robolectric runs this class at SDK 21, where
 * native graphics is unavailable and it falls back to `ShadowLegacyBitmap` and
 * `ShadowLegacyCanvas` with no warning, no error and no log line. Measured:
 *
 * ```
 * NATIVE + no @Config      sdk=21  ShadowLegacyBitmap
 * NATIVE + @Config(sdk=34) sdk=34  ShadowNativeBitmap
 * default + @Config(sdk=34) sdk=34 ShadowLegacyBitmap
 * ```
 *
 * In the legacy shadow a `Canvas` *records* draw calls instead of rasterizing
 * them, so every `getPixel` returns 0x00000000 — which means the whole of this
 * file passes vacuously, reporting a transparent layer because nothing was ever
 * drawn into it. Every invariant here is therefore stated two-sided: the pixel
 * that must be transparent is asserted alongside a pixel that must be opaque
 * ink, and a stroke that never happened fails the second half.
 *
 * `an antialiased dab leaves a partial-coverage rim` is the tripwire on top of
 * that. Partial coverage is something only a real rasterizer produces, so a
 * Robolectric upgrade that quietly drops back to the legacy shadow fails there
 * rather than going green. Same idea as `MatricesTest`'s `setRotate` snap.
 *
 * Every `Layer` here is built with `enforceOffMainThread = false`, because
 * Robolectric runs test bodies on the main looper. The two tests that care about
 * the check turn it back on.
 *
 * What this file cannot reach: the device. Robolectric's Skia is a CPU build for
 * x86-64, so this is strong evidence about pixels and none at all about the
 * render path, `GL_MAX_TEXTURE_SIZE`, or memory pressure against the tablet's
 * 4.4 GiB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerTest {

    @Test
    fun `a layer is an ARGB_8888 bitmap at document size that carries alpha`() {
        val layer = Layer(Document.DEFAULT_WIDTH_PX, Document.DEFAULT_HEIGHT_PX, false)
        assertTrue(
            layer.read { bmp ->
                assertEquals(Document.DEFAULT_WIDTH_PX, bmp.width)
                assertEquals(Document.DEFAULT_HEIGHT_PX, bmp.height)
                assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
                // 2160 * 3300 * 4. Asserted rather than trusted because it is
                // the number the whole memory argument rests on, and a config
                // that silently downgraded would still draw correctly here.
                assertEquals(28_512_000, bmp.byteCount)
                assertTrue(bmp.hasAlpha(), "layer must carry alpha")
                assertTrue(bmp.isMutable, "layer must be a Canvas target")
            },
            "a fresh layer must be open",
        )
        layer.close()
    }

    @Test
    fun `a fresh layer is transparent everywhere`() {
        val layer = Layer(64, 64, false)
        layer.read { bmp ->
            assertEquals(0, bmp.getPixel(0, 0))
            assertEquals(0, bmp.getPixel(32, 32))
            assertEquals(0, bmp.getPixel(63, 63))
        }
        layer.close()
    }

    /**
     * The invariant, and the reason this class exists. Ink where the dab is,
     * alpha 0 everywhere it is not — no paper white anywhere in the pixels.
     */
    @Test
    fun `ink leaves every pixel it did not touch fully transparent`() {
        val layer = Layer(64, 64, false)
        layer.write { canvas -> canvas.drawCircle(20f, 20f, 8f, blackDab()) }
        layer.read { bmp ->
            // Both halves, always. The first alone is what a canvas that draws
            // nothing also satisfies.
            assertEquals(OPAQUE_BLACK, bmp.getPixel(20, 20), "the dab must actually be ink")
            assertEquals(0, Color.alpha(bmp.getPixel(60, 60)), "untouched pixels must stay alpha 0")
            assertEquals(0, Color.alpha(bmp.getPixel(0, 0)))
        }
        layer.close()
    }

    /**
     * The other half of the invariant, stated the way the renderer and W14's
     * export actually see it: the same coordinate that is transparent in the
     * layer is paper white once paper is drawn under it. Both facts about one
     * pixel is what "alpha-carrying" means.
     */
    @Test
    fun `paper white belongs to the composite and never to the layer`() {
        val layer = Layer(64, 64, false)
        layer.write { canvas -> canvas.drawCircle(20f, 20f, 8f, blackDab()) }

        val composite = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val into = Canvas(composite)
        into.drawColor(Color.WHITE)
        layer.read { bmp -> into.drawBitmap(bmp, 0f, 0f, filterPaint()) }

        assertEquals(0, Color.alpha(layer.pixel(60, 60)), "layer stays transparent")
        assertEquals(OPAQUE_WHITE, composite.getPixel(60, 60), "composite is paper there")
        assertEquals(OPAQUE_BLACK, composite.getPixel(20, 20), "composite is ink under the dab")
        layer.close()
    }

    /**
     * The tripwire. Analytic antialiasing produces alphas strictly between 0 and
     * 255 along a circle's edge; a shadow that records draw calls produces
     * nothing at all, and a non-antialiased fill produces only 0 and 255.
     */
    @Test
    fun `an antialiased dab leaves a partial-coverage rim`() {
        val layer = Layer(64, 64, false)
        layer.write { canvas -> canvas.drawCircle(32f, 32f, 10.5f, blackDab()) }
        var partial = 0
        for (x in 38..46) {
            val a = Color.alpha(layer.pixel(x, 32))
            if (a in 1..254) partial++
        }
        assertTrue(partial > 0, "no partially covered pixel on the rim; this is not real Skia")
        layer.close()
    }

    /**
     * Blanking clears to transparent, not to paper. The mutation this catches —
     * `drawColor(paperWhite)` at the clear site — renders identically on screen
     * and is invisible until Phase 3's stack or Phase 4's `.ora`.
     */
    @Test
    fun `blanking clears to transparent and not to paper white`() {
        val layer = Layer(64, 64, false)
        layer.write { canvas -> canvas.drawCircle(20f, 20f, 8f, blackDab()) }
        assertEquals(OPAQUE_BLACK, layer.pixel(20, 20))

        assertTrue(layer.blank())

        assertEquals(0, layer.pixel(20, 20), "cleared pixels must be alpha 0, not paper")
        assertEquals(0, layer.pixel(60, 60))
        layer.close()
    }

    @Test
    fun `a closed layer runs no block and a second close is a no-op`() {
        val layer = Layer(64, 64, false)
        layer.close()

        var ran = false
        assertFalse(layer.write { ran = true })
        assertFalse(layer.read { ran = true })
        assertFalse(layer.blank())
        assertFalse(ran, "a closed layer must never hand out its pixels")
        assertFalse(layer.isOpen)

        layer.close()
        assertFalse(layer.isOpen)
    }

    /**
     * The lifecycle guarantee, stated as the failure it prevents: `:spike`
     * recorded a frame callback outliving a bounded join, reading a recycled
     * `Bitmap` and killing the process from the render thread with no usable
     * stack. Here the recycle waits on the same lock the read holds, so a reader
     * cannot observe a recycled bitmap however the teardown is ordered.
     *
     * The same mechanism is the second deadlock, which is why the wait is pinned
     * here rather than only described in `Layer`'s KDoc: `close` parks on the
     * monitor with no bound, so a render thread wedged inside `read` blocks the
     * UI thread's teardown for as long as it stays wedged. W8 owes the bounded
     * join that keeps that from happening; nothing in this class can.
     */
    @Test
    fun `a reader never sees a recycled bitmap and close parks behind it unbounded`() {
        val layer = Layer(64, 64)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var recycledUnderReader = true
        var closedWhileInside = true

        val reader = Thread {
            layer.read { bmp ->
                inside.countDown()
                release.await()
                recycledUnderReader = bmp.isRecycled
            }
        }
        reader.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val closer = Thread {
            layer.close()
            closedWhileInside = false
        }
        closer.start()
        // Long enough for close() to have reached the monitor and blocked. It
        // cannot prove it got there, which is why the assertions are about what
        // was observed rather than about timing: this one can only fail if close
        // ran to completion under a live reader, which is the bug.
        Thread.sleep(50)
        assertTrue(closedWhileInside, "close completed while a reader held the lock")

        release.countDown()
        reader.join(5000)
        closer.join(5000)

        assertFalse(recycledUnderReader, "close recycled the bitmap under a live reader")
        assertFalse(closedWhileInside, "close never completed")
        // Not a read() here: this body is the main looper, the check is on, and
        // `a closed layer runs no block` covers the returned false already.
        assertFalse(layer.isOpen)
    }

    /**
     * Mutual exclusion, behaviourally. Verified able to fail rather than assumed
     * to be: with the `synchronized` dropped from `write` this reports all eight
     * threads inside at once instead of one.
     */
    @Test
    fun `only one thread is inside the layer at a time`() {
        val layer = Layer(16, 16)
        val inside = AtomicInteger()
        val worst = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map {
            Thread {
                start.await()
                repeat(200) {
                    layer.write {
                        val n = inside.incrementAndGet()
                        worst.getAndUpdate { w -> maxOf(w, n) }
                        Thread.yield()
                        inside.decrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(1, worst.get(), "two threads were inside the layer at once")
        layer.close()
    }

    /**
     * The detector, checked in both directions so it cannot be a no-op that
     * nobody notices.
     */
    @Test
    fun `the layer refuses the main thread and accepts a render thread`() {
        val layer = Layer(16, 16)
        assertFailsWith<IllegalStateException> { layer.write { } }
        assertFailsWith<IllegalStateException> { layer.read { } }

        var ran = false
        val render = Thread { ran = layer.write { } }
        render.start()
        render.join(5000)
        assertTrue(ran, "an off-main thread must be allowed in")
        layer.close()
    }

    /**
     * The gap in the scoped accessors, pinned as an invariant rather than left
     * to be discovered.
     *
     * `block` is not `crossinline` and Kotlin has no lifetime on a parameter, so
     * one line lifts the `Bitmap` out of the critical section and writes it with
     * no lock held. `Layer` does not claim otherwise; this is the test that
     * keeps it from starting to. The floor under the gap is Skia's, not this
     * class's: after [Layer.close] the escaped reference is a recycled `Bitmap`,
     * which throws rather than touching freed memory.
     */
    @Test
    fun `the bitmap escapes read and the lock does not stop it`() {
        val layer = Layer(16, 16, false)
        var escaped: Bitmap? = null
        assertTrue(layer.read { escaped = it })

        val bmp = escaped!!
        assertFalse(bmp.isRecycled, "read handed out a live bitmap")
        // No lock held here, and nothing prevents the write.
        bmp.setPixel(1, 1, Color.RED)
        assertEquals(Color.RED, bmp.getPixel(1, 1))

        layer.close()
        assertTrue(bmp.isRecycled)
        assertFailsWith<IllegalStateException> { bmp.getPixel(1, 1) }
    }

    /**
     * The monitor is reentrant, so without the guard in [Layer.close] a `write`
     * block could recycle the bitmap its live `Canvas` points at and carry on
     * drawing into freed pixels — with the lock held the whole time and `alive`
     * already checked, which is a use-after-recycle straight through the public
     * API. Verified able to fail: with the `check(depth == 0)` removed, the
     * `drawCircle` after `close()` runs against a recycled bitmap.
     */
    @Test
    fun `close from inside a write or read block is refused`() {
        val layer = Layer(16, 16, false)
        assertFailsWith<IllegalStateException> { layer.write { layer.close() } }
        assertFailsWith<IllegalStateException> { layer.read { layer.close() } }
        assertTrue(layer.isOpen, "a refused close must not have recycled anything")

        // The depth counter unwinds, so an ordinary close still works.
        layer.close()
        assertFalse(layer.isOpen)
    }

    /**
     * The one property no other kind of test can state: there is no *named* way
     * to the pixels that does not pass through the lock.
     *
     * Named is the whole claim. The pixels do escape — see
     * `the bitmap escapes read and the lock does not stop it` — so this is about
     * the class's surface, not about reachability.
     *
     * Synthetic members are skipped because `write` and `read` are inline, and
     * an inline body compiled into a caller reaches the private `Bitmap` through
     * a compiler-generated synthetic bridge. That is not a leak in Kotlin
     * source, and the exclusion is proved not to be an excuse by [LeakyLayer],
     * whose ordinary `val bitmap` produces a non-synthetic `getBitmap()` and
     * fails the same check.
     */
    @Test
    fun `no member of Layer hands out the bitmap`() {
        assertEquals(emptyList(), bitmapSurfaceOf(Layer::class.java))
        assertEquals(listOf("getBitmap()"), bitmapSurfaceOf(LeakyLayer::class.java))
    }

    private class LeakyLayer(val bitmap: Bitmap)

    private fun bitmapSurfaceOf(k: Class<*>): List<String> {
        val fields = k.fields
            .filter { !it.isSynthetic && Bitmap::class.java.isAssignableFrom(it.type) }
            .map { it.name }
        val methods = k.methods
            .filter { !it.isSynthetic && Bitmap::class.java.isAssignableFrom(it.returnType) }
            .map { "${it.name}()" }
        return (fields + methods).sorted()
    }

    /**
     * Premultiplication, measured rather than reasoned about, because the whole
     * "alpha-carrying" argument rests on the layer being able to hand its own
     * pixels back unchanged.
     *
     * Black is the fixed point of premultiplication — `c * a / 255 == 0` for
     * every alpha — so Phase 1's ink survives exactly, at all 256 alphas. That
     * is asserted here rather than asserted in a comment.
     *
     * Coloured ink at low alpha does not, and this pins the exact size of the
     * loss so that Phase 2's brushes and Phase 4's `.ora` export inherit a
     * number instead of a worry: at alpha 1 the stored premultiplied channel is
     * `round(c / 255)`, which is 0 or 1, and unpremultiplying it gives 0 or 255.
     * The worst case is 127, and it is reached.
     */
    @Test
    fun `premultiplication is exact for black ink and loses 127 for colour at alpha 1`() {
        val layer = Layer(16, 16, false)
        layer.read { bmp ->
            assertTrue(bmp.isPremultiplied, "createBitmap must give a premultiplied bitmap")

            for (a in 0..255) {
                bmp.setPixel(0, 0, Color.argb(a, 0, 0, 0))
                val back = bmp.getPixel(0, 0)
                assertEquals(a, Color.alpha(back), "alpha changed at a=$a")
                assertEquals(0, Color.red(back), "black gained colour at a=$a")
            }

            var worst = 0
            for (a in 1..255) for (c in 0..255) {
                bmp.setPixel(1, 1, Color.argb(a, c, c, c))
                worst = maxOf(worst, Math.abs(Color.red(bmp.getPixel(1, 1)) - c))
            }
            assertEquals(127, worst, "the coloured-ink round-trip error moved")

            bmp.setPixel(2, 2, Color.argb(1, 128, 128, 128))
            assertEquals(0x01FFFFFF, bmp.getPixel(2, 2), "the stated worst case")
        }
        layer.close()
    }

    /**
     * The layer survives the real PNG codec, both as an alpha-carrying layer
     * (Phase 4's `.ora`) and flattened onto paper (W14's export). Robolectric's
     * native graphics does run the actual encoder and decoder, so this is a
     * real round trip and not a shadow of one — the decoded bytes start with
     * the PNG signature and every sampled pixel matches.
     *
     * The transparent-corner assertion is the one that matters: an exporter
     * that ever baked paper into the layer would still produce a correct-looking
     * flattened PNG, and would only be caught here, on the layer's own PNG.
     */
    @Test
    fun `the layer round trips through the real PNG codec both alpha-carrying and flattened`() {
        val layer = Layer(64, 64, false)
        layer.write { canvas -> canvas.drawCircle(32f, 32f, 12f, blackDab()) }

        // 1. the alpha-carrying layer itself
        val layerPng = java.io.ByteArrayOutputStream()
        assertTrue(layer.read { bmp -> assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, layerPng)) })
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            layerPng.toByteArray(), 0, layerPng.size(),
        )
        assertEquals(0x89.toByte(), layerPng.toByteArray()[0], "not a PNG")
        assertEquals(0, Color.alpha(decoded.getPixel(60, 60)), "transparency did not survive the PNG")
        assertEquals(OPAQUE_BLACK, decoded.getPixel(32, 32), "the ink did not survive the PNG")
        layer.read { bmp ->
            for (y in 0 until 64) for (x in 0 until 64) {
                assertEquals(bmp.getPixel(x, y), decoded.getPixel(x, y), "pixel ($x,$y) changed")
            }
        }

        // 2. W14's shape: paper drawn under it at export time, never into it
        val export = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val into = Canvas(export)
        into.drawColor(Color.WHITE)
        layer.read { bmp -> into.drawBitmap(bmp, 0f, 0f, filterPaint()) }
        val exportPng = java.io.ByteArrayOutputStream()
        assertTrue(export.compress(Bitmap.CompressFormat.PNG, 100, exportPng))
        val exported = android.graphics.BitmapFactory.decodeByteArray(
            exportPng.toByteArray(), 0, exportPng.size(),
        )
        assertEquals(OPAQUE_WHITE, exported.getPixel(60, 60), "exported paper is not white")
        assertEquals(OPAQUE_BLACK, exported.getPixel(32, 32), "exported ink is not black")

        layer.close()
    }

    /** Reads one pixel through the accessor, for the assertions that want a value back. */
    private fun Layer.pixel(x: Int, y: Int): Int {
        var px = 0
        check(read { bmp -> px = bmp.getPixel(x, y) }) { "layer is closed" }
        return px
    }

    private fun blackDab() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** W14's and the render body's blit paint: filtered, not antialiased. */
    private fun filterPaint() = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    }
}
