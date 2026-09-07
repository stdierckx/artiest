package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.xform.CanvasTransform

/**
 * Reads a trace file back into the exact [PenSample] stream that was recorded.
 *
 * Pulls from a `Sequence<String>`, which `:app` gets from
 * `reader.buffered().use { it.lineSequence() }` and a test gets from
 * `text.lineSequence()` — the same decoder either way, with no file API in this
 * module.
 *
 * Eager and total: the whole file is validated before anything is returned, so
 * a malformed trace fails at the line that is wrong rather than several stages
 * downstream with a stroke that is subtly short. Every deviation throws
 * [TraceFormatException]; nothing is skipped and nothing is guessed.
 *
 * There is no playback clock here. Deterministic replay is iterating
 * [Trace.events] as fast as the machine will run them, which is what every test
 * wants; real-time playback is a pump in `:app`, driven off the timestamps this
 * decoder restores.
 */
object TracePlayer {

    fun decode(text: String): Trace = decode(text.lineSequence())

    fun decode(lines: Sequence<String>): Trace {
        var lineNo = 0
        var version = -1
        var t0: Long? = null
        var transform: CanvasTransform? = null
        val meta = LinkedHashMap<String, String>()
        val events = ArrayList<TraceEvent>()

        var pending: TraceEvent? = null
        var pendingSamples: MutableList<PenSample>? = null
        var pendingWanted = 0
        var terminator: IntArray? = null

        for (raw in lines) {
            lineNo++
            // trim also takes the '\r' off a trace that went through an
            // editor that rewrote the line endings, which would otherwise
            // present as an unparseable source token on every single line.
            val line = raw.trim()
            if (line.isEmpty() || line[0] == TraceFormat.COMMENT) continue
            if (terminator != null) fail(lineNo, "content after '${TraceFormat.END}'")
            val t = line.split(' ')

            if (version < 0) {
                if (t.size != 2 || t[0] != TraceFormat.MAGIC) {
                    fail(lineNo, "expected '${TraceFormat.MAGIC} <version>', got '$line'")
                }
                version = t[1].toIntOrNull() ?: fail(lineNo, "version '${t[1]}' is not an integer")
                // A file from a future version is refused rather than read
                // best-effort. Re-recording the corpus is not a migration: a
                // re-recorded stroke is a different stroke, and it would
                // rebaseline every golden that compares against it while
                // looking like a successful format upgrade.
                if (version != TraceFormat.VERSION) {
                    fail(lineNo, "format version $version, this build reads ${TraceFormat.VERSION}")
                }
                continue
            }

            // Sample lines are only legal directly under the event line that
            // declared how many of them there are.
            val open = pendingSamples
            if (open != null) {
                if (t[0] != TraceFormat.SAMPLE) {
                    fail(lineNo, "expected ${pendingWanted - open.size} more sample lines, got '${t[0]}'")
                }
                open += parseSample(lineNo, t, t0 ?: fail(lineNo, "no '${TraceFormat.T0}'"))
                if (open.size == pendingWanted) {
                    val e = pending!!
                    events += TraceEvent(e.seq, e.action, e.pointerId, open, e.canceled)
                    pending = null
                    pendingSamples = null
                }
                continue
            }

            when (t[0]) {
                TraceFormat.T0 -> {
                    if (t0 != null) fail(lineNo, "duplicate '${TraceFormat.T0}'")
                    if (events.isNotEmpty()) fail(lineNo, "'${TraceFormat.T0}' after the first event")
                    if (t.size != 2) fail(lineNo, "expected '${TraceFormat.T0} <nanos>'")
                    t0 = t[1].toLongOrNull() ?: fail(lineNo, "t0 '${t[1]}' is not a long")
                }

                TraceFormat.XFORM -> {
                    if (transform != null) fail(lineNo, "duplicate '${TraceFormat.XFORM}'")
                    if (events.isNotEmpty()) fail(lineNo, "'${TraceFormat.XFORM}' after the first event")
                    if (t.size != 5) fail(lineNo, "expected '${TraceFormat.XFORM} <scale> <rot> <tx> <ty>'")
                    transform = try {
                        CanvasTransform(
                            float(lineNo, t[1], "scale"),
                            float(lineNo, t[2], "rotationRad"),
                            float(lineNo, t[3], "txDoc"),
                            float(lineNo, t[4], "tyDoc"),
                        )
                    } catch (e: IllegalArgumentException) {
                        // CanvasTransform's own invariants, surfaced with the
                        // line rather than as a bare require() from three
                        // frames away.
                        fail(lineNo, "invalid transform: ${e.message}")
                    }
                }

                TraceFormat.META -> {
                    if (events.isNotEmpty()) fail(lineNo, "'${TraceFormat.META}' after the first event")
                    for (i in 1 until t.size) {
                        val eq = t[i].indexOf('=')
                        if (eq <= 0) fail(lineNo, "meta entry '${t[i]}' is not key=value")
                        val k = t[i].substring(0, eq)
                        if (meta.put(k, t[i].substring(eq + 1)) != null) {
                            fail(lineNo, "duplicate meta key '$k'")
                        }
                    }
                }

                TraceFormat.EVENT -> {
                    if (t0 == null) fail(lineNo, "event before '${TraceFormat.T0}'")
                    if (t.size != 5 && t.size != 6) {
                        fail(
                            lineNo,
                            "expected '${TraceFormat.EVENT} <seq> <action> <pointerId> <n> " +
                                "[${TraceFormat.CANCELED}]'",
                        )
                    }
                    val canceled = t.size == 6
                    if (canceled && t[5] != TraceFormat.CANCELED) {
                        fail(lineNo, "trailing token '${t[5]}' is not '${TraceFormat.CANCELED}'")
                    }
                    val seq = t[1].toIntOrNull() ?: fail(lineNo, "seq '${t[1]}' is not an integer")
                    val action = try {
                        PointerAction.valueOf(t[2])
                    } catch (e: IllegalArgumentException) {
                        fail(lineNo, "unknown action '${t[2]}'")
                    }
                    val id = t[3].toIntOrNull() ?: fail(lineNo, "pointerId '${t[3]}' is not an integer")
                    val n = t[4].toIntOrNull() ?: fail(lineNo, "sample count '${t[4]}' is not an integer")
                    if (n < 0) fail(lineNo, "negative sample count $n")
                    if (events.isNotEmpty() && seq < events.last().seq) {
                        fail(lineNo, "seq $seq went backwards after ${events.last().seq}")
                    }
                    if (n == 0) {
                        // A CANCEL has no sample to carry: its coordinates are
                        // stale and the router emits none with it.
                        events += TraceEvent(seq, action, id, emptyList(), canceled)
                    } else {
                        pending = TraceEvent(seq, action, id, emptyList(), canceled)
                        // Deliberately unsized. `n` is untrusted data from a
                        // file, and one garbled digit as a capacity hint is an
                        // OutOfMemoryError — on the tablet, a process kill —
                        // where the "declared more samples than it carries"
                        // check at EOF would have thrown a legible parse error.
                        // Batches are 1-6 samples here, so presizing buys
                        // nothing measurable anyway.
                        pendingSamples = ArrayList()
                        pendingWanted = n
                    }
                }

                TraceFormat.END -> {
                    if (t.size != 3) fail(lineNo, "expected '${TraceFormat.END} <events> <samples>'")
                    val e = t[1].toIntOrNull() ?: fail(lineNo, "event total '${t[1]}' is not an integer")
                    val s = t[2].toIntOrNull() ?: fail(lineNo, "sample total '${t[2]}' is not an integer")
                    terminator = intArrayOf(e, s)
                }

                else -> fail(lineNo, "unknown directive '${t[0]}'")
            }
        }

        if (pendingSamples != null) {
            fail(lineNo, "event ${pending!!.seq} declared $pendingWanted samples, file ended after ${pendingSamples.size}")
        }
        // Absent terminator means the file was cut short — the process died, or
        // a stream was closed before end() ran. Without this check a truncated
        // trace is just a shorter stroke, which replays green.
        val totals = terminator ?: fail(lineNo, "no '${TraceFormat.END}' line; the trace is truncated")
        val sampleTotal = events.sumOf { it.samples.size }
        if (totals[0] != events.size || totals[1] != sampleTotal) {
            fail(
                lineNo,
                "'${TraceFormat.END} ${totals[0]} ${totals[1]}' disagrees with " +
                    "${events.size} events and $sampleTotal samples read",
            )
        }
        if (t0 == null) fail(lineNo, "no '${TraceFormat.T0}' line")
        return Trace(TraceHeader(version, t0, transform, meta), events)
    }

    private fun parseSample(lineNo: Int, t: List<String>, t0: Long): PenSample {
        if (t.size != 11) {
            fail(
                lineNo,
                "expected 11 fields on a '${TraceFormat.SAMPLE}' line " +
                    "(dt x y pressure tilt orientation distance tool buttons src), got ${t.size}",
            )
        }
        val dt = t[1].toLongOrNull() ?: fail(lineNo, "dt '${t[1]}' is not a long")
        return PenSample(
            x = float(lineNo, t[2], "x"),
            y = float(lineNo, t[3], "y"),
            pressure = float(lineNo, t[4], "pressure"),
            tilt = float(lineNo, t[5], "tilt"),
            orientation = float(lineNo, t[6], "orientation"),
            distance = float(lineNo, t[7], "distance"),
            toolType = t[8].toIntOrNull() ?: fail(lineNo, "toolType '${t[8]}' is not an integer"),
            buttonState = t[9].toIntOrNull() ?: fail(lineNo, "buttonState '${t[9]}' is not an integer"),
            eventTimeNanos = t0 + dt,
            source = TraceFormat.sourceOf(t[10]) ?: fail(lineNo, "unknown source '${t[10]}'"),
        )
    }

    private fun float(lineNo: Int, token: String, name: String): Float {
        val f = token.toFloatOrNull() ?: fail(lineNo, "$name '$token' is not a float")
        // Refused on the way in for the same reason the recorder refuses it on
        // the way out: NaN loses its payload through the round-trip, and every
        // comparison downstream of it is false, so it propagates silently.
        if (!f.isFinite()) fail(lineNo, "$name was $f")
        return f
    }

    private fun fail(lineNo: Int, message: String): Nothing =
        throw TraceFormatException("trace line $lineNo: $message")
}
