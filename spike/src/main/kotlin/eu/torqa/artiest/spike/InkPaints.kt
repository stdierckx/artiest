package eu.torqa.artiest.spike

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Shared drawing bits, so the two ink paths differ only in how they get pixels
 * to the screen — never in what they draw. Anything else would make the
 * comparison meaningless.
 */
object InkPaints {

    fun stroke() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Marks the newest raw sample, so video shows ink lag against true position. */
    fun cursor() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun counter() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 64f
        typeface = Typeface.MONOSPACE
    }

    /** Pressure to width. Deliberately linear — Phase 0 measures, it does not flatter. */
    fun widthFor(pressure: Float): Float = 1.5f + pressure * 22f

    /**
     * A frame counter big enough to read off a 240 fps video, plus a blinking
     * block that flips every frame. Count frames between the pen moving and the
     * ink following: at 240 fps each frame is 4.17 ms.
     */
    fun drawFrameCounter(canvas: Canvas, frame: Long, paint: Paint) {
        canvas.drawText("f%06d".format(frame), 24f, 72f, paint)
        if (frame % 2L == 0L) canvas.drawRect(24f, 92f, 88f, 156f, paint)
    }
}
