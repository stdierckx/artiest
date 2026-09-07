package be.thalos.artiest.spike

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

    /**
     * The red cursor and the frame counter are instruments for the 240 fps
     * camera, and only two of the three arms can draw them at all — the
     * front-buffered layer accumulates, so it can render neither a transient
     * cursor nor a per-frame count. They are therefore a shared switch, off
     * while the arms are being judged by eye and on only for filming.
     */
    var videoInstruments: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

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

    override fun onHoverEvent(event: MotionEvent): Boolean {
        capture?.onHoverEvent(event, this)
        return super.onHoverEvent(event)
    }

    fun clear() {
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, strokePaint)
        frames++
        if (videoInstruments) {
            if (cursorX >= 0f) canvas.drawCircle(cursorX, cursorY, 18f, cursorPaint)
            InkPaints.drawFrameCounter(canvas, frames, counterPaint)
        }
    }
}
