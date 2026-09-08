package be.thalos.artiest

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The startup gate, as arithmetic rather than as a comment.
 *
 * No Robolectric here and none needed: [DeviceReport] is a data class of
 * primitives, and the decision it carries — how big a document this GL
 * implementation can actually hold — is the part that has to be right on a
 * device nobody has. On the MovinkPad the cap is 16383 against a 3300
 * requirement, so every branch below except the first is unreachable on the
 * only hardware in the room. That is precisely why they are tested.
 */
class DeviceReportTest {

    @Test
    fun `a cap well above the document changes nothing`() {
        val (w, h) = report(maxTexture = 16383).documentSizeFor(2160, 3300)
        assertEquals(2160, w)
        assertEquals(3300, h)
    }

    @Test
    fun `a document taller than the cap is clamped and keeps its shape`() {
        val (w, h) = report(maxTexture = 2048).documentSizeFor(2160, 3300)

        assertEquals(2048, h, "the long axis lands exactly on the cap")
        assertTrue(w <= 2048)
        assertAspect(2160f / 3300f, w.toFloat() / h)
    }

    @Test
    fun `a document wider than the cap is clamped on the other axis`() {
        // The same rule from the other side. A version that only ever divided
        // by the height would pass every test above and produce a document
        // wider than the texture it has to live in.
        val (w, h) = report(maxTexture = 2048).documentSizeFor(3300, 2160)

        assertEquals(2048, w)
        assertTrue(h <= 2048)
        assertAspect(3300f / 2160f, w.toFloat() / h)
    }

    @Test
    fun `a failed GL probe does not clamp the document to nothing`() {
        // probeGl returns 0 for the whole GlInfo when it cannot make a pbuffer
        // context, which is a far more likely failure than a genuinely tiny
        // texture cap. Trusting it would turn a working device into a 1x1
        // canvas because an EGL config was unavailable for a moment.
        val (w, h) = report(maxTexture = 0).documentSizeFor(2160, 3300)
        assertEquals(2160, w)
        assertEquals(3300, h)

        // The control: this is what trusting the zero would have produced.
        val naive = 0.toFloat() / 3300
        assertEquals(0, (2160 * naive).toInt(), "a zero cap scales everything to nothing")
    }

    @Test
    fun `supportsFullCanvas is the 4096 line and not a rounding of it`() {
        assertFalse(report(maxTexture = 4095).supportsFullCanvas)
        assertTrue(report(maxTexture = 4096).supportsFullCanvas)
    }

    @Test
    fun `the cap applies to the document actually asked for`() {
        // Not to 4096. supportsFullCanvas is Phase 2's question and answers
        // false at 3300; the gate that matters in Phase 1 has to say yes there.
        val r = report(maxTexture = 3300)
        assertFalse(r.supportsFullCanvas)
        assertEquals(2160 to 3300, r.documentSizeFor(2160, 3300))
    }

    private fun assertAspect(expected: Float, actual: Float) {
        assertTrue(
            abs(expected - actual) / expected < 0.01f,
            "aspect drifted: wanted $expected, got $actual",
        )
    }

    private fun report(maxTexture: Int) = DeviceReport(
        manufacturer = "Wacom",
        model = "DTH-A116",
        device = "movinkpad",
        soc = "MediaTek MT8781V/NA",
        androidRelease = "14",
        sdkInt = 34,
        modes = emptyList(),
        currentRefreshHz = 60f,
        memoryClassMb = 256,
        largeMemoryClassMb = 512,
        totalMemBytes = 8_268_000_000L,
        availMemBytes = 4_724_000_000L,
        lowMemoryThresholdBytes = 0L,
        lowMemory = false,
        glVendor = "ARM",
        glRenderer = "Mali-G57 MC2",
        glVersion = "OpenGL ES 3.2",
        glMaxTextureSize = maxTexture,
        frontBufferSupported = false,
        frontBufferUsageFlags = DeviceProbe.FRONT_BUFFER_USAGE_FLAGS,
        frontBufferBitSupported = false,
    )
}
