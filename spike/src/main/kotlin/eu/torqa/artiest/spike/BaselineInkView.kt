package eu.torqa.artiest.spike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Path A — the control.
 *
 * An ordinary View invalidating on every event. No front buffer, no prediction,
 * no unbuffered dispatch. This is the latency you get for free, and the number
 * every optimisation has to be measured against; without it, "feels fast" is
 * just an opinion.
 *
 * Uses only APIs that have been stable for a decade, so it is the fallback if
 * anything in [LowLatencyInkView] misbehaves.
 */
class BaselineInkView(context: Context) : View(context) {

    var capture: PenCapture? = null

    private val path = Path()
    private val strokePaint = InkPaints.stroke().apply { strokeWidth = 6f }
    private val cursorPaint = InkPaints.cursor()
    private val counterPaint = InkPaints.counter()

    private var frames = 0L
    private var cursorX = -1f
    private var cursorY = -1f

    init {
        setBackgroundColor(Color.WHITE)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        capture?.onMotionEvent(event, this)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                // Walk history as well as the current point, so the control
                // path is not handicapped by discarding samples.
                for (h in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                path.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
        cursorX = event.x
        cursorY = event.y
        invalidate()
        return true
    }

    fun clear() {
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, strokePaint)
        if (cursorX >= 0f) canvas.drawCircle(cursorX, cursorY, 18f, cursorPaint)
        frames++
        InkPaints.drawFrameCounter(canvas, frames, counterPaint)
    }
}
