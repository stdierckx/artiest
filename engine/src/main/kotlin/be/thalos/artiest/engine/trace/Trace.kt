package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.xform.CanvasTransform

/**
 * A trace file said something the decoder cannot act on.
 *
 * Every malformed input throws rather than being skipped or best-guessed. A
 * lenient parser turns a corrupt trace into a *different* stroke, which then
 * replays green and rebaselines whatever golden was comparing against it — the
 * exact failure the version header exists to prevent.
 */
class TraceFormatException(message: String) : RuntimeException(message)

/**
 * One MotionEvent's worth of samples for one pointer.
 *
 * [seq] identifies the event, so two pointers extracted from the same
 * MotionEvent share it. That grouping is free to keep and unrecoverable once
 * flattened, and three things need it: prediction fires once per event rather
 * than once per sample, one event maps to one dab batch (which is what W9
 * validates the batch pool's slot count against), and `requestUnbufferedDispatch`
 * exists precisely to change batching, so a flattened trace cannot A/B it.
 *
 * [samples] is oldest-first, and may be empty: a CANCEL carries stale
 * coordinates and the router deliberately emits no sample with it.
 *
 * [canceled] is `MotionEvent.FLAG_CANCELED`, and it has to be here because it
 * is the single input that decides whether a pen lift commits a stroke or
 * discards it. Without it, a session where the framework's own palm rejection
 * cancelled a stroke replays as a committed stroke — silently, with no error
 * anywhere, because the two produce byte-identical files.
 */
class TraceEvent(
    val seq: Int,
    val action: PointerAction,
    val pointerId: Int,
    val samples: List<PenSample>,
    val canceled: Boolean = false,
)

/**
 * Everything the file says before the first event.
 *
 * [transform] is not input, and that is why it has to be here. The pipeline's
 * `toDoc` stage consumes a [CanvasTransform] that is app state, so replaying a
 * stroke recorded at 3x zoom against an identity transform draws a visibly
 * different line — and the difference gets misdiagnosed as a stabilizer or
 * resampler regression. One header line prevents that. Null means the recording
 * did not capture one.
 */
class TraceHeader(
    val version: Int,
    val t0Nanos: Long,
    val transform: CanvasTransform?,
    val meta: Map<String, String>,
)

/**
 * A recorded input stream, decoded.
 *
 * This is the only reproducible regression test the ink pipeline has: it turns
 * "does it feel good" from a subjective re-draw into the same stroke replayed
 * after every tuning change.
 *
 * There is no scheduler here and no clock. Replaying deterministically is
 * `for (e in trace.events) router.feed(e)` — as fast as the JVM will go, which
 * is the mode every test wants. Real-time playback is a pump in `:app` driven
 * off these timestamps, and it belongs with the view in W8.
 */
class Trace(val header: TraceHeader, val events: List<TraceEvent>) {

    /** Flattened, for the stages that legitimately do not care about batching. */
    val samples: Sequence<PenSample>
        get() = events.asSequence().flatMap { it.samples.asSequence() }

    val sampleCount: Int = events.sumOf { it.samples.size }

    private val firstSampleTimeNanos: Long? =
        events.firstOrNull { it.samples.isNotEmpty() }?.samples?.first()?.eventTimeNanos

    /**
     * First sample time to last. Zero for a trace that recorded no samples.
     *
     * Computed once rather than on every read: a property that walks every
     * sample of a five-minute recording is not something a caller expects to
     * pay for twice, and this type is immutable so the answer cannot change.
     */
    val durationNanos: Long = run {
        val first = firstSampleTimeNanos ?: return@run 0L
        var last = first
        for (e in events) for (s in e.samples) if (s.eventTimeNanos > last) last = s.eventTimeNanos
        last - first
    }

    /**
     * The same trace with its first sample landing at [nowNanos].
     *
     * Needed because the recorded times are a boot clock from another session.
     * Any stage that compares a sample time against the current clock — W11's
     * prediction horizon will — reads a stale base as an unbounded gap and
     * behaves nothing like it did when the stroke was recorded.
     *
     * Pure Long arithmetic, so intervals are preserved exactly.
     */
    fun rebasedTo(nowNanos: Long): Trace {
        val delta = nowNanos - (firstSampleTimeNanos ?: header.t0Nanos)
        if (delta == 0L) return this
        return Trace(
            TraceHeader(header.version, header.t0Nanos + delta, header.transform, header.meta),
            events.map { e ->
                TraceEvent(
                    e.seq,
                    e.action,
                    e.pointerId,
                    e.samples.map { it.copy(eventTimeNanos = it.eventTimeNanos + delta) },
                    e.canceled,
                )
            },
        )
    }
}

/**
 * The wire format, in one place so the recorder and the player cannot disagree
 * about it.
 *
 * Line-oriented text, single spaces, `\n`. Text beats a binary encoding on
 * every axis that matters here and loses on none: every finite float the
 * recorder will accept survives `Float.toString` -> `String.toFloat` bit for
 * bit — denormals, negative zero, both extremes and the measured DOWN pressure
 * are pinned in `TraceAdversarialTest`, at zero ULP — the corpus is checked
 * into git and stays diffable, a trace pastes into a bug report, and — the one
 * that pays off soonest — a pathological stroke can be hand-authored to
 * exercise a threshold that is awkward to draw by hand.
 *
 * The non-finite half is not encoded at all: NaN's payload bits cannot survive
 * a decimal round-trip, so `TraceRecorder` and `TracePlayer` both refuse them.
 *
 * Sizes are not a reason to reconsider: 76 bytes a line as measured by
 * TraceRoundTripTest, so ~240 KB for a ten second stroke at the 321.75 Hz this
 * digitizer reports at 90 Hz. If that number moves, it moves in that test
 * first — do not restate it here from memory.
 */
object TraceFormat {

    /** Line 1 is `artiest-trace <version>`, exactly, or nothing is read. */
    const val MAGIC = "artiest-trace"

    const val VERSION = 1

    const val EVENT = "e"
    const val SAMPLE = "s"
    const val T0 = "t0"
    const val XFORM = "xform"
    const val META = "meta"
    const val END = "end"
    const val COMMENT = '#'

    /**
     * Optional trailing token on an event line: `MotionEvent.FLAG_CANCELED` was
     * set. Absent means false, so every v1 file written before the flag had a
     * channel still decodes unchanged and the version does not move.
     */
    const val CANCELED = "!"

    /**
     * One char per [PenSample.Source]. Single letters because this column
     * repeats on every line of the file and `HISTORICAL` spelled out is a
     * quarter of the payload.
     */
    fun sourceToken(source: PenSample.Source): Char = when (source) {
        PenSample.Source.CURRENT -> 'C'
        PenSample.Source.HISTORICAL -> 'H'
        PenSample.Source.PREDICTED -> 'P'
    }

    fun sourceOf(token: String): PenSample.Source? = when (token) {
        "C" -> PenSample.Source.CURRENT
        "H" -> PenSample.Source.HISTORICAL
        "P" -> PenSample.Source.PREDICTED
        else -> null
    }
}
