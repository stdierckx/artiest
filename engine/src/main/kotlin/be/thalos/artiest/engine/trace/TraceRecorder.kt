package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.xform.CanvasTransform

/**
 * Writes a [PenSample] stream out in the format [TracePlayer] reads back.
 *
 * Pushes to an [Appendable] and never opens or closes anything. That keeps the
 * file API on the `:app` side where it belongs, and it makes the JVM test path
 * and the device path the same code: a `StringBuilder` in a test, a
 * `BufferedWriter` or a MediaStore stream on the tablet, with no untested I/O
 * branch between them. It also never closes what it was handed — closing a
 * `Writer` you did not open would truncate the caller's stream before [end]
 * lands, and a trace missing its `end` line decodes as truncated.
 *
 * Recording appends to whatever the caller is buffering into, on the input
 * thread. That is the pattern the render path deliberately does not have, so
 * `:app` keeps this behind an off-by-default toggle: the zero-allocation budget
 * for the input path is only assertable with recording off.
 *
 * Not thread-safe, and not intended to be. One recorder, one input thread.
 */
class TraceRecorder(private val out: Appendable) {

    private var begun = false
    private var ended = false
    private var t0Nanos = 0L
    private var lastSeq = Int.MIN_VALUE
    private var eventCount = 0
    private var sampleCount = 0

    /**
     * Writes the header. [t0Nanos] is the base every sample time is stored
     * relative to.
     *
     * Relative to a single base, not chained from the previous sample: chaining
     * means one edited or corrupt line silently shifts every timestamp after
     * it, and hand-authoring a trace becomes an exercise in arithmetic. Deltas
     * from a base are exact Long subtraction and typically seven or eight
     * digits against the fifteen an absolute boot clock costs on every line.
     */
    fun begin(
        t0Nanos: Long,
        transform: CanvasTransform? = null,
        meta: Map<String, String> = emptyMap(),
    ) {
        check(!begun) { "begin() called twice" }
        begun = true
        this.t0Nanos = t0Nanos

        out.append(TraceFormat.MAGIC).append(' ').append(TraceFormat.VERSION.toString()).append('\n')
        out.append(TraceFormat.T0).append(' ').append(t0Nanos.toString()).append('\n')
        if (transform != null) {
            out.append(TraceFormat.XFORM)
            appendFloat(transform.scale)
            appendFloat(transform.rotationRad)
            appendFloat(transform.txDoc)
            appendFloat(transform.tyDoc)
            out.append('\n')
        }
        if (meta.isNotEmpty()) {
            out.append(TraceFormat.META)
            for ((k, v) in meta) {
                // Tokens are space-separated and never quoted, because a
                // quoting rule is a second grammar to get right in both
                // directions for metadata nothing parses.
                require(k.isNotEmpty() && k.none { it.isWhitespace() || it == '=' }) {
                    "meta key must be non-empty with no whitespace or '=': '$k'"
                }
                require(v.none { it.isWhitespace() }) {
                    "meta value must not contain whitespace (use '_'): '$v'"
                }
                out.append(' ').append(k).append('=').append(v)
            }
            out.append('\n')
        }
    }

    /**
     * One event, for one pointer.
     *
     * [seq] is the caller's per-MotionEvent counter: two pointers extracted
     * from the same event are recorded with the same [seq], and it must not go
     * backwards. The recorder cannot assign it, because only the caller knows
     * where one event ends.
     *
     * [canceled] is `event.flags and MotionEvent.FLAG_CANCELED != 0`, and it
     * is recorded because nothing else in the file can reconstruct it: it is
     * what turns a pen lift into a discard instead of a commit, so a session
     * the framework palm-rejected would otherwise replay as a committed stroke.
     *
     * What goes in here is the **raw** action, before the routing decision.
     * Recording the router's own output would put palm rejection and pen/gesture
     * exclusion — the highest-risk logic in the input path — permanently
     * outside the regression net, and recording its stroke and gesture
     * boundaries would be worse still: the router would then be tested against
     * its own conclusions and could never be shown wrong. Boundaries fall out
     * of the raw actions on replay.
     */
    fun record(
        seq: Int,
        action: PointerAction,
        pointerId: Int,
        samples: List<PenSample>,
        canceled: Boolean = false,
    ) {
        check(begun) { "record() before begin()" }
        check(!ended) { "record() after end()" }
        require(seq >= lastSeq) { "seq went backwards: $seq after $lastSeq" }
        // POINTER_LOST is derived by :app from findPointerIndex returning -1,
        // and it is equally derivable on replay from the pointer ids the event
        // carries. Recording a derived signal lets the file assert something
        // the samples contradict.
        require(action != PointerAction.POINTER_LOST) {
            "POINTER_LOST is derived on replay, not recorded"
        }
        lastSeq = seq

        out.append(TraceFormat.EVENT)
            .append(' ').append(seq.toString())
            .append(' ').append(action.name)
            .append(' ').append(pointerId.toString())
            .append(' ').append(samples.size.toString())
        // Trailing and optional, so the absence of the token is the common case
        // and costs no bytes on the ~250 events a second that are not cancels.
        if (canceled) out.append(' ').append(TraceFormat.CANCELED)
        out.append('\n')
        for (i in samples.indices) appendSample(samples[i])
        eventCount++
        sampleCount += samples.size
    }

    /**
     * Writes the `end` line, which carries the event and sample totals.
     *
     * Truncation is the failure a trace file actually has — a process killed,
     * a stream closed early — and it is invisible without a terminator: a file
     * cut mid-stroke is a shorter, perfectly well-formed stroke.
     */
    fun end() {
        check(begun) { "end() before begin()" }
        check(!ended) { "end() called twice" }
        ended = true
        out.append(TraceFormat.END)
            .append(' ').append(eventCount.toString())
            .append(' ').append(sampleCount.toString())
            .append('\n')
    }

    private fun appendSample(s: PenSample) {
        // A recorded input trace is what the digitizer produced. Source.PREDICTED
        // is pipeline output, so replaying it would double-count: the replay
        // feeds recorded predictions in and generates fresh ones from the same
        // samples. The decoder still reads the tag, so a hand-authored file can
        // drive a downstream stage that needs one.
        require(s.source != PenSample.Source.PREDICTED) {
            "Source.PREDICTED is pipeline output and must not appear in an input trace"
        }
        // Rejected at the boundary rather than encoded, because NaN cannot come
        // back: the payload bits are lost through the text round-trip and
        // 0x7fc00001 decodes as 0x7fc00000. A NaN in a sample is upstream
        // corruption anyway, and this is where it stops being invisible.
        requireFinite(s.x, "x")
        requireFinite(s.y, "y")
        requireFinite(s.pressure, "pressure")
        requireFinite(s.tilt, "tilt")
        requireFinite(s.orientation, "orientation")
        requireFinite(s.distance, "distance")

        out.append(TraceFormat.SAMPLE)
            .append(' ').append((s.eventTimeNanos - t0Nanos).toString())
        appendFloat(s.x)
        appendFloat(s.y)
        appendFloat(s.pressure)
        appendFloat(s.tilt)
        appendFloat(s.orientation)
        appendFloat(s.distance)
        out.append(' ').append(s.toolType.toString())
            .append(' ').append(s.buttonState.toString())
            .append(' ').append(TraceFormat.sourceToken(s.source))
            .append('\n')
    }

    // Float.toString, and never String.format or DecimalFormat. The formatter
    // family is locale-sensitive: "%f" on this machine's own nl-BE locale emits
    // "0,300000", which is an extra space-separated token per float and a file
    // the parser mis-splits. Float.toString is locale-independent and exact.
    //
    // '\n' literal for the same class of reason: System.lineSeparator() makes
    // the file bytes and the git diff depend on which host wrote them.
    private fun appendFloat(f: Float) {
        out.append(' ').append(f.toString())
    }

    private fun requireFinite(f: Float, name: String) {
        require(f.isFinite()) { "$name was $f; a trace cannot round-trip a non-finite float" }
    }
}
