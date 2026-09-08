package be.thalos.artiest

import android.app.ActivityManager
import android.content.Context
import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.view.Display

/**
 * One display mode, as data rather than as a formatted string.
 *
 * `:spike`'s probe flattened the mode list to `"1440x2200 @ 90.0Hz"` at the
 * point of measurement, which cost two things W15 needs back. The refresh
 * toggle has to *choose* a mode, and it cannot choose from prose; and the
 * formatting it used was `"%.1f".format(…)`, which follows the default locale
 * — nl-BE on this machine — so the probe's own output changed shape with the
 * user's language. Numbers are kept as numbers and formatted where they are
 * shown.
 */
data class ModeInfo(
    val modeId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val refreshHz: Float,
)

/**
 * What the device is, measured rather than assumed.
 *
 * Ported from `:spike`'s `DeviceReport`, which answered the "unknown SoC / GPU"
 * risk for Phase 0. It is still the same questions — Wacom does not publish the
 * MovinkPad's chipset, and fill rate decides the canvas defaults — but two of
 * the answers now have consumers in the app rather than in a report: the
 * texture cap gates the document size at startup, and the mode list is what the
 * refresh toggle chooses from.
 */
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val device: String,
    val soc: String,
    val androidRelease: String,
    val sdkInt: Int,
    val modes: List<ModeInfo>,
    val currentRefreshHz: Float,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val totalMemBytes: Long,
    val availMemBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val glVendor: String,
    val glRenderer: String,
    val glVersion: String,
    val glMaxTextureSize: Int,
    /**
     * Three-valued on purpose. `null` is "graphics-core never asked" — below
     * API 33 it does not request the flag at all — which is a different fact
     * from `false`, "gralloc refused", and only `false` triggers the stop
     * condition.
     */
    val frontBufferSupported: Boolean?,
    /** The exact usage set [frontBufferSupported] answers. See [DeviceProbe.FRONT_BUFFER_USAGE_FLAGS]. */
    val frontBufferUsageFlags: Long,
    val frontBufferBitSupported: Boolean?,
) {

    /**
     * Whether a 4096x4096 layer is representable. Phase 2's option, kept
     * because the number it rests on is measured here and nowhere else.
     */
    val supportsFullCanvas: Boolean get() = glMaxTextureSize >= 4096

    /**
     * The document size to actually allocate, given the one that was asked for.
     *
     * The gate the plan asks for, at the size Phase 1 actually uses. The layer
     * is blitted every frame as a texture, so a document larger than
     * `GL_MAX_TEXTURE_SIZE` on either axis is not a slow canvas but a blank one:
     * the upload fails and the blit draws nothing, with no exception on any
     * thread the app owns. Clamping to the cap keeps the aspect ratio, because
     * a document that is silently the wrong shape is worse than one that is
     * silently smaller.
     *
     * This device measures 16383, a factor of 4.96 over the 3300 it needs, so
     * on this hardware the gate never fires. That is the reason to write it as
     * a function with a test rather than as a `check` in `onCreate`: it is not
     * reachable here, and an unreachable branch with no test is a branch nobody
     * has run. A probe that fails outright reports `0`, which must not be
     * treated as "clamp everything to nothing" — see [glMaxTextureSize].
     */
    fun documentSizeFor(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val cap = glMaxTextureSize
        // A failed GL probe reports 0. Trusting it would clamp a working device
        // down to nothing because a pbuffer context could not be created, which
        // is a far more likely failure than a genuinely tiny texture cap.
        if (cap <= 0) return widthPx to heightPx
        val longest = maxOf(widthPx, heightPx)
        if (longest <= cap) return widthPx to heightPx
        // The long axis is set to the cap exactly rather than computed, because
        // `(3300 * (2048f/3300)).toInt()` is 2047 or 2048 depending on how the
        // float rounds, and a document one pixel under the cap for arithmetic
        // reasons is a thing nobody can explain later.
        val scale = cap.toFloat() / longest
        return if (widthPx >= heightPx) {
            cap to maxOf(1, kotlin.math.round(heightPx * scale).toInt())
        } else {
            maxOf(1, kotlin.math.round(widthPx * scale).toInt()) to cap
        }
    }
}

object DeviceProbe {

    /**
     * The question androidx.graphics:graphics-core actually puts to gralloc, as
     * a literal because `USAGE_FRONT_BUFFER` and `USAGE_COMPOSER_OVERLAY` are
     * API 33 fields and this constant is read on API 29. It is
     * `FrontBufferUtils.BaseFlags` (COMPOSER_OVERLAY 2048 | GPU_COLOR_OUTPUT
     * 512 | GPU_SAMPLED_IMAGE 256) or USAGE_FRONT_BUFFER (1L shl 32), verified
     * by javap against 1.0.4's `UsageFlagsVerificationHelper`. A graphics-core
     * upgrade that changes BaseFlags has to change this too, or the probe
     * answers a question nobody asks.
     */
    const val FRONT_BUFFER_USAGE_FLAGS = 4294970112L

    /** HardwareBuffer.USAGE_FRONT_BUFFER on its own, again as a literal. */
    private const val USAGE_FRONT_BUFFER = 4294967296L

    fun run(context: Context, display: Display?): DeviceReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gl = probeGl()
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)

        val modes = display?.supportedModes?.map {
            ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }.orEmpty()

        return DeviceReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            soc = if (Build.VERSION.SDK_INT >= 31) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            } else "unknown (needs API 31+)",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            modes = modes,
            currentRefreshHz = display?.refreshRate ?: 0f,
            memoryClassMb = am.memoryClass,
            largeMemoryClassMb = am.largeMemoryClass,
            // memoryClass is the per-app heap the framework will grant, which
            // says nothing about how large a bitmap the device can hold: the
            // canvas layers live in native allocations, so the budget has to be
            // argued against real RAM.
            totalMemBytes = mem.totalMem,
            availMemBytes = mem.availMem,
            lowMemoryThresholdBytes = mem.threshold,
            lowMemory = mem.lowMemory,
            glVendor = gl.vendor,
            glRenderer = gl.renderer,
            glVersion = gl.version,
            glMaxTextureSize = gl.maxTextureSize,
            frontBufferSupported = probeFrontBuffer(FRONT_BUFFER_USAGE_FLAGS),
            frontBufferUsageFlags = FRONT_BUFFER_USAGE_FLAGS,
            frontBufferBitSupported = probeFrontBuffer(USAGE_FRONT_BUFFER),
        )
    }

    private class GlInfo(
        val vendor: String,
        val renderer: String,
        val version: String,
        val maxTextureSize: Int,
    )

    /**
     * Queries GL strings from a 1x1 pbuffer context, so the probe does not need
     * a view or a running render loop.
     *
     * Ported unchanged, including the `EGLDisplay` handling. The plan lists
     * "the two `EGLDisplay` leaks on the pre-`try` early returns" as work this
     * item owes; they were already fixed in `:spike` during W0, and the comment
     * below is that fix's own explanation. Nothing was left to do here, which is
     * worth recording rather than quietly ticking off.
     */
    private fun probeGl(): GlInfo {
        val unknown = GlInfo("?", "?", "?", 0)
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        // Nothing acquired on this path — eglGetDisplay failed — and
        // eglTerminate(EGL_NO_DISPLAY) would only raise EGL_BAD_DISPLAY.
        if (display == EGL14.EGL_NO_DISPLAY) return unknown

        // Both early returns below sit outside the try, so the finally's
        // eglTerminate does not cover them. The default display is
        // process-wide and reference counted: leaving it initialised here
        // leaves it initialised for the life of the app, and terminating one
        // that never came up is a documented no-op.
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            EGL14.eglTerminate(display)
            return unknown
        }

        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, configCount, 0) ||
                configCount[0] == 0
            ) return unknown

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return unknown

            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return unknown

            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            return GlInfo(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
                renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
                version = GLES20.glGetString(GLES20.GL_VERSION) ?: "?",
                maxTextureSize = maxTexture[0],
            )
        } catch (t: Throwable) {
            return unknown
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /**
     * The measurement Phase 1 rests on. graphics-core asks gralloc this exact
     * question and, when the answer is no, quietly lowers its own request to
     * `BaseFlags or USAGE_CPU_WRITE_OFTEN` and carries on: same
     * `FrontBufferedLayer` SurfaceControl, same hardcoded 1000 Hz frame-rate
     * vote, no exception, no log line, and no API to ask afterwards which set
     * it got. Asking the same question ourselves is the only way to know.
     *
     * The flag set is not a judgement call — a narrower one (FRONT_BUFFER or
     * GPU_COLOR_OUTPUT, say) can answer true where the library's answers false,
     * because COMPOSER_OVERLAY combined with FRONT_BUFFER is precisely the
     * pairing an implementation refuses. Both the width and the height are 1
     * and the format is RGBA_8888 for the same reason: that is what the library
     * probes, whatever the renderer is later configured with.
     *
     * Returns null below API 33, where graphics-core never requests the flag at
     * all — "no front buffer by construction" rather than "gralloc refused".
     */
    private fun probeFrontBuffer(usage: Long): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return HardwareBuffer.isSupported(1, 1, HardwareBuffer.RGBA_8888, 1, usage)
    }
}
