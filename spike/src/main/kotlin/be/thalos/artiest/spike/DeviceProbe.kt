package be.thalos.artiest.spike

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.view.Display

/**
 * Answers the "unknown SoC / GPU" risk. Wacom does not publish the MovinkPad's
 * chipset, and fill rate is what decides the v1 canvas-size defaults.
 */
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val device: String,
    val soc: String,
    val androidRelease: String,
    val sdkInt: Int,
    val displayModes: List<String>,
    val currentRefreshHz: Float,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val glVendor: String,
    val glRenderer: String,
    val glVersion: String,
    val glMaxTextureSize: Int,
) {
    /**
     * A 4096x4096 RGBA8 layer is 64 MiB. If GL_MAX_TEXTURE_SIZE comes back
     * below 4096 the hard canvas cap has to come down with it.
     */
    val supportsFullCanvas: Boolean get() = glMaxTextureSize >= 4096
}

object DeviceProbe {

    fun run(context: Context, display: Display?): DeviceReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gl = probeGl()

        val modes = display?.supportedModes?.map {
            "${it.physicalWidth}x${it.physicalHeight} @ ${"%.1f".format(it.refreshRate)}Hz"
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
            displayModes = modes,
            currentRefreshHz = display?.refreshRate ?: 0f,
            memoryClassMb = am.memoryClass,
            largeMemoryClassMb = am.largeMemoryClass,
            glVendor = gl.vendor,
            glRenderer = gl.renderer,
            glVersion = gl.version,
            glMaxTextureSize = gl.maxTextureSize,
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
     */
    private fun probeGl(): GlInfo {
        val unknown = GlInfo("?", "?", "?", 0)
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return unknown

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return unknown

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
}
