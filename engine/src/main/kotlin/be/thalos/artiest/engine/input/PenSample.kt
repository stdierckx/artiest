package be.thalos.artiest.engine.input

/**
 * One digitizer sample, normalised away from `MotionEvent`.
 *
 * This is the only shape the rest of the pipeline sees. Nothing downstream of
 * capture touches a MotionEvent — that boundary is what lets the same engine be
 * driven by the Pro Pen 3, a finger, or a recorded trace replayed in a JVM test
 * with no device attached.
 *
 * Ten fields, none of them defaulted, and that is deliberate: `TraceRecorder`
 * writes one column per field, so a default would let a trace file with a
 * missing column deserialize into a plausible-looking sample instead of
 * failing at the line that is actually wrong.
 *
 * Immutable, and **not** pooled. One instance per digitizer sample is ~56 bytes
 * at the measured 321.75 Hz — about 18 KB/s, which ART's bump allocator absorbs.
 * The reason not to pool is structural rather than arithmetic: `TraceRecorder`
 * retains samples, and a recorder holding pooled instances records aliases that
 * the next event overwrites. If the sample stage ever shows up in W9's
 * allocation trace, the fix is a parallel primitive-array path, not mutating
 * this type.
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
    /**
     * Always 0.0 on the DTH-A116: 400 hover samples were captured through a
     * demonstrably correct `onHoverEvent`/`getAxisValue` path in Phase 0 and
     * every one of them reported zero. The field survives because it is one of
     * the eight the pipeline is specified around and because dropping a column
     * would change the trace format, but **nothing may branch on it** — palm
     * rejection is by tool type and stroke exclusivity, not by hover height.
     */
    val distance: Float,
    val toolType: Int,
    /**
     * Read from the event's *current* state and stamped onto every historical
     * sample in the same batch, because Android exposes no historical
     * `buttonState` or `getToolType` variant. Unfixable, and it matters here
     * rather than at the capture site: `TraceRecorder` serializes these two as
     * if they were per-sample truth, so a barrel-button press replays backdated
     * across a whole batch — up to ~4 samples at 246 Hz against a 60 Hz panel.
     */
    val buttonState: Int,
    val eventTimeNanos: Long,
    val source: Source,
) {
    /**
     * [PREDICTED] has no producer until W11's `Predictor`. It exists in the
     * enum now so `MotionEvents.sampleAt` can take an explicit source parameter
     * instead of deriving it from whether the sample came out of the history
     * buffer, which is what made the value unreachable in the spike.
     */
    enum class Source { CURRENT, HISTORICAL, PREDICTED }

    val isStylus: Boolean
        get() = toolType == ToolType.STYLUS || toolType == ToolType.ERASER

    /**
     * Never true on this hardware, and kept only so the next person to look for
     * flip-to-erase finds the answer here rather than building on it: the pen
     * has no eraser end, and its back reports [ToolType.FINGER]. Routing uses
     * [toolClassOf]; nothing reads this.
     */
    val isEraserEnd: Boolean
        get() = toolType == ToolType.ERASER
}
