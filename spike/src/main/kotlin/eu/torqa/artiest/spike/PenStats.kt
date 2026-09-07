package eu.torqa.artiest.spike

import android.view.MotionEvent
import java.util.TreeSet
import kotlin.math.roundToInt

/**
 * Accumulates everything Phase 0 is trying to find out about the digitizer.
 *
 * Kept allocation-light on the hot path: [onSample] runs for every sample the
 * pen produces, and the whole point of the exercise is to avoid perturbing the
 * thing being measured.
 */
class PenStats {

    var events = 0L; private set
    var samples = 0L; private set
    var historicalSamples = 0L; private set
    var canceledEvents = 0L; private set
    var flaggedCanceledPointers = 0L; private set

    var pressureMin = Float.MAX_VALUE; private set
    var pressureMax = -Float.MAX_VALUE; private set
    var tiltMin = Float.MAX_VALUE; private set
    var tiltMax = -Float.MAX_VALUE; private set
    var orientationMin = Float.MAX_VALUE; private set
    var orientationMax = -Float.MAX_VALUE; private set
    var distanceMax = -Float.MAX_VALUE; private set

    val toolTypesSeen = LinkedHashSet<Int>()
    val buttonStatesSeen = LinkedHashSet<Int>()
    val historyBatchSizes = HashMap<Int, Int>()

    /**
     * Distinct pressure values, capped. The gap between adjacent values is what
     * reveals quantisation: a pen advertising 8192 levels that Android has
     * quantised to 256 is invisible in the min/max range but obvious here.
     */
    private val pressureValues = TreeSet<Float>()
    private var pressureSaturated = false

    private val intervals = LongArray(INTERVAL_WINDOW)
    private var intervalCount = 0
    private var intervalCursor = 0
    private var lastSampleNanos = 0L

    fun onEvent(event: MotionEvent) {
        events++
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> canceledEvents++
        }
        // Android 13+ marks individual pointers as accidental (palm) without
        // canceling the whole gesture.
        if (event.flags and MotionEvent.FLAG_CANCELED != 0) flaggedCanceledPointers++

        val h = event.historySize
        historyBatchSizes[h] = (historyBatchSizes[h] ?: 0) + 1
        buttonStatesSeen += event.buttonState
    }

    fun onSample(s: PenSample) {
        samples++
        if (s.source == PenSample.Source.HISTORICAL) historicalSamples++
        toolTypesSeen += s.toolType

        if (s.pressure < pressureMin) pressureMin = s.pressure
        if (s.pressure > pressureMax) pressureMax = s.pressure
        if (s.tilt < tiltMin) tiltMin = s.tilt
        if (s.tilt > tiltMax) tiltMax = s.tilt
        if (s.orientation < orientationMin) orientationMin = s.orientation
        if (s.orientation > orientationMax) orientationMax = s.orientation
        if (s.distance > distanceMax) distanceMax = s.distance

        if (!pressureSaturated && s.pressure > 0f) {
            pressureValues += s.pressure
            if (pressureValues.size >= PRESSURE_CAP) pressureSaturated = true
        }

        if (lastSampleNanos != 0L) {
            val d = s.eventTimeNanos - lastSampleNanos
            // Discard gaps between strokes; we want the in-stroke report rate.
            if (d in 1..MAX_INTERVAL_NANOS) {
                intervals[intervalCursor] = d
                intervalCursor = (intervalCursor + 1) % INTERVAL_WINDOW
                if (intervalCount < INTERVAL_WINDOW) intervalCount++
            }
        }
        lastSampleNanos = s.eventTimeNanos
    }

    /** Median is used rather than mean so one scheduling hiccup can't skew it. */
    fun sampleRateHz(): Float {
        if (intervalCount < MIN_INTERVALS) return 0f
        val sorted = intervals.copyOf(intervalCount)
        sorted.sort()
        val median = sorted[sorted.size / 2]
        return if (median > 0L) 1e9f / median else 0f
    }

    /**
     * Estimated pressure levels, derived from the smallest gap between distinct
     * observed values. Needs a slow, firm stroke sweeping light to heavy to be
     * meaningful — a few quick scribbles will under-report.
     */
    fun estimatedPressureLevels(): Int {
        if (pressureValues.size < MIN_PRESSURE_SAMPLES) return 0
        var minGap = Float.MAX_VALUE
        var prev = Float.NaN
        for (v in pressureValues) {
            if (!prev.isNaN()) {
                val gap = v - prev
                if (gap > EPSILON && gap < minGap) minGap = gap
            }
            prev = v
        }
        return if (minGap == Float.MAX_VALUE) 0 else (1f / minGap).roundToInt()
    }

    fun distinctPressureValues(): Int = pressureValues.size

    /** Samples per event. Well above 1 means the history buffer is carrying real data. */
    fun samplesPerEvent(): Float = if (events == 0L) 0f else samples.toFloat() / events

    fun reset() {
        events = 0; samples = 0; historicalSamples = 0
        canceledEvents = 0; flaggedCanceledPointers = 0
        pressureMin = Float.MAX_VALUE; pressureMax = -Float.MAX_VALUE
        tiltMin = Float.MAX_VALUE; tiltMax = -Float.MAX_VALUE
        orientationMin = Float.MAX_VALUE; orientationMax = -Float.MAX_VALUE
        distanceMax = -Float.MAX_VALUE
        toolTypesSeen.clear(); buttonStatesSeen.clear(); historyBatchSizes.clear()
        pressureValues.clear(); pressureSaturated = false
        intervalCount = 0; intervalCursor = 0; lastSampleNanos = 0L
    }

    private companion object {
        const val INTERVAL_WINDOW = 512
        const val MIN_INTERVALS = 16
        const val PRESSURE_CAP = 20_000
        const val MIN_PRESSURE_SAMPLES = 32
        const val MAX_INTERVAL_NANOS = 100_000_000L // 100 ms
        const val EPSILON = 1e-7f
    }
}
