package eu.torqa.artiest.spike

import android.os.Build
import android.view.MotionEvent

/**
 * One digitizer sample, normalised away from [MotionEvent].
 *
 * From Phase 1 onward this is the only shape the rest of the pipeline sees.
 * Nothing downstream of capture should touch a MotionEvent — that boundary is
 * what lets the same engine be driven by the Pro Pen 3, a finger, a mouse, or
 * a recorded trace played back in a test.
 */
data class PenSample(
    val x: Float,
    val y: Float,
    /** Normalised 0..1. The Pro Pen 3's 8192 levels arrive as a float. */
    val pressure: Float,
    /** Radians, 0 (perpendicular) .. PI/2 (flat to the glass). */
    val tilt: Float,
    /** Radians, -PI..PI. Which way the tilt is pointing. */
    val orientation: Float,
    /** Hover distance. 0 while touching; > 0 means pen in range but lifted. */
    val distance: Float,
    val toolType: Int,
    val buttonState: Int,
    val eventTimeNanos: Long,
    val source: Source,
) {
    enum class Source { CURRENT, HISTORICAL, PREDICTED }

    val isStylus: Boolean
        get() = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == MotionEvent.TOOL_TYPE_ERASER

    val isEraserEnd: Boolean
        get() = toolType == MotionEvent.TOOL_TYPE_ERASER
}

/**
 * Expands a MotionEvent into every sample it carries, oldest first.
 *
 * The digitizer reports far faster than the panel refreshes, so one ACTION_MOVE
 * routinely carries several samples in its history buffer. Reading only
 * [MotionEvent.getX] throws most of the pen's resolution away and produces
 * visibly faceted curves — measuring how much is one of Phase 0's jobs.
 */
fun MotionEvent.collectSamples(into: MutableList<PenSample>, pointerIndex: Int = 0) {
    for (h in 0 until historySize) into += sampleAt(pointerIndex, h)
    into += sampleAt(pointerIndex, CURRENT)
}

private const val CURRENT = -1

private fun MotionEvent.sampleAt(pi: Int, h: Int): PenSample {
    val historical = h != CURRENT
    return PenSample(
        x = if (historical) getHistoricalX(pi, h) else getX(pi),
        y = if (historical) getHistoricalY(pi, h) else getY(pi),
        pressure = if (historical) getHistoricalPressure(pi, h) else getPressure(pi),
        tilt = if (historical) getHistoricalAxisValue(MotionEvent.AXIS_TILT, pi, h)
               else getAxisValue(MotionEvent.AXIS_TILT, pi),
        orientation = if (historical) getHistoricalOrientation(pi, h) else getOrientation(pi),
        distance = if (historical) getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, pi, h)
                   else getAxisValue(MotionEvent.AXIS_DISTANCE, pi),
        toolType = getToolType(pi),
        buttonState = buttonState,
        eventTimeNanos = if (historical) historicalTimeNanos(h) else timeNanos(),
        source = if (historical) PenSample.Source.HISTORICAL else PenSample.Source.CURRENT,
    )
}

/**
 * Nanosecond event times landed in API 34. The MovinkPad runs 14, so it takes
 * the precise path; the millisecond fallback only exists so the spike still
 * runs on an older phone if you want a comparison device.
 */
private fun MotionEvent.timeNanos(): Long =
    if (Build.VERSION.SDK_INT >= 34) eventTimeNanos else eventTime * 1_000_000L

private fun MotionEvent.historicalTimeNanos(h: Int): Long =
    if (Build.VERSION.SDK_INT >= 34) getHistoricalEventTimeNanos(h)
    else getHistoricalEventTime(h) * 1_000_000L

fun toolTypeName(toolType: Int): String = when (toolType) {
    MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
    MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
    MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
    MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
    MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
    else -> "TOOL_$toolType"
}
