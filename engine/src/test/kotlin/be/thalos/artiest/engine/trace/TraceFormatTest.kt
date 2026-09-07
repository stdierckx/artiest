package be.thalos.artiest.engine.trace

import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every way a trace can be wrong, and the assertion that it is refused rather
 * than read.
 *
 * A lenient decoder is the failure mode the header exists to prevent: a
 * best-effort parse of a damaged file produces a *different* stroke, which
 * replays green and rebaselines whatever golden was comparing against it. So
 * the rule is fail loud everywhere, and these are the cases that would
 * otherwise pass quietly.
 */
class TraceFormatTest {

    /**
     * The failure a trace file actually has. A file cut mid-stroke is a
     * shorter, perfectly well-formed stroke, and only the terminator
     * distinguishes it from a stroke that really was that short.
     */
    @Test
    fun `a trace with no end line is refused as truncated`() {
        val full = recorded()
        val truncated = full.lines().dropLast(2).joinToString("\n")
        assertTrue(TraceFormat.END !in truncated)
        val e = assertFailsWith<TraceFormatException> { TracePlayer.decode(truncated) }
        assertTrue("truncated" in e.message!!, e.message!!)
    }

    @Test
    fun `a trace whose totals disagree with its contents is refused`() {
        val tampered = recorded().replace("${TraceFormat.END} 2 3", "${TraceFormat.END} 2 4")
        assertFailsWith<TraceFormatException> { TracePlayer.decode(tampered) }
    }

    @Test
    fun `an event that declares more samples than it carries is refused`() {
        val tampered = recorded().replace("e 1 MOVE 0 2", "e 1 MOVE 0 3")
        assertFailsWith<TraceFormatException> { TracePlayer.decode(tampered) }
    }

    @Test
    fun `a sample line that is not under an event is refused`() {
        val lines = recorded().lines().toMutableList()
        lines.add(1, "s 0 1.0 2.0 0.5 0.25 -1.5 0.0 2 0 C")
        assertFailsWith<TraceFormatException> { TracePlayer.decode(lines.joinToString("\n")) }
    }

    /**
     * A newer file is refused rather than read best-effort, and the way out is
     * a migration, not a re-recording: a re-recorded stroke is a different
     * stroke, so re-recording the corpus after a format change rebaselines
     * every golden while looking like a successful upgrade.
     */
    @Test
    fun `a format version this build does not know is refused`() {
        val newer = recorded().replaceFirst(
            "${TraceFormat.MAGIC} ${TraceFormat.VERSION}",
            "${TraceFormat.MAGIC} ${TraceFormat.VERSION + 1}",
        )
        assertFailsWith<TraceFormatException> { TracePlayer.decode(newer) }
        assertFailsWith<TraceFormatException> { TracePlayer.decode("not-a-trace 1\nend 0 0\n") }
        assertFailsWith<TraceFormatException> { TracePlayer.decode("") }
    }

    @Test
    fun `an unknown directive or action is refused rather than skipped`() {
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode(recorded().replace("t0 ", "tzero "))
        }
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode(recorded().replace("MOVE", "WIGGLE"))
        }
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode(recorded().replace(" C\n", " Q\n"))
        }
    }

    @Test
    fun `an event before the t0 line is refused`() {
        val reordered = recorded().lines().let { l ->
            // Drop the t0 line, leaving events with nothing to be relative to.
            l.filterNot { it.startsWith(TraceFormat.T0) }.joinToString("\n")
        }
        assertFailsWith<TraceFormatException> { TracePlayer.decode(reordered) }
    }

    @Test
    fun `a directive after the first event is refused`() {
        val lines = recorded().lines().toMutableList()
        lines.add(lines.indexOfFirst { it.startsWith("e ") } + 1, "meta late=yes")
        assertFailsWith<TraceFormatException> { TracePlayer.decode(lines.joinToString("\n")) }
    }

    @Test
    fun `content after the end line is refused`() {
        assertFailsWith<TraceFormatException> { TracePlayer.decode(recorded() + "e 9 MOVE 0 0\n") }
    }

    /**
     * NaN cannot come back: the payload bits are lost through the text
     * round-trip, so 0x7fc00001 decodes as 0x7fc00000. It is refused at both
     * ends rather than encoded — a NaN in a sample is upstream corruption, and
     * every comparison downstream of it is false, so it propagates in silence.
     */
    @Test
    fun `a non-finite float is refused when written and when read`() {
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).apply {
                begin(0L)
                record(0, PointerAction.MOVE, 0, listOf(sample(x = Float.NaN)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).apply {
                begin(0L)
                record(0, PointerAction.MOVE, 0, listOf(sample(pressure = Float.POSITIVE_INFINITY)))
            }
        }
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode(recorded().replace(" 1.0 2.0 ", " NaN 2.0 "))
        }
    }

    /**
     * Predicted samples are pipeline output. Replaying them into a pipeline
     * that also produces them double-counts: the recorded prediction goes in
     * and a fresh one comes out of the same samples. The decoder still reads
     * the tag, so a hand-authored file can drive a stage that needs one.
     */
    @Test
    fun `the recorder refuses a predicted sample and the decoder still reads one`() {
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).apply {
                begin(0L)
                record(0, PointerAction.MOVE, 0, listOf(sample(source = PenSample.Source.PREDICTED)))
            }
        }
        val handAuthored = """
            artiest-trace 1
            t0 0
            e 0 MOVE 0 1
            s 0 1.0 2.0 0.5 0.25 -1.5 0.0 2 0 P
            end 1 1
        """.trimIndent()
        assertEquals(
            PenSample.Source.PREDICTED,
            TracePlayer.decode(handAuthored).samples.first().source,
        )
    }

    /**
     * POINTER_LOST is derived by `:app` from `findPointerIndex` returning -1,
     * and it is equally derivable on replay from the pointer ids the events
     * carry. Recording it would let the file assert something its own samples
     * contradict.
     */
    @Test
    fun `the recorder refuses a derived POINTER_LOST`() {
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).apply {
                begin(0L)
                record(0, PointerAction.POINTER_LOST, 0, listOf(sample()))
            }
        }
    }

    @Test
    fun `event sequence numbers may repeat but never go backwards`() {
        val r = TraceRecorder(StringBuilder())
        r.begin(0L)
        r.record(0, PointerAction.DOWN, 0, listOf(sample()))
        r.record(1, PointerAction.MOVE, 0, listOf(sample()))
        r.record(1, PointerAction.MOVE, 1, listOf(sample()))
        assertFailsWith<IllegalArgumentException> { r.record(0, PointerAction.MOVE, 0, listOf(sample())) }
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode("artiest-trace 1\nt0 0\ne 5 MOVE 0 0\ne 4 MOVE 0 0\nend 2 0\n")
        }
    }

    @Test
    fun `the recorder refuses to write out of order`() {
        val r = TraceRecorder(StringBuilder())
        assertFailsWith<IllegalStateException> { r.record(0, PointerAction.DOWN, 0, listOf(sample())) }
        assertFailsWith<IllegalStateException> { r.end() }
        r.begin(0L)
        assertFailsWith<IllegalStateException> { r.begin(0L) }
        r.end()
        assertFailsWith<IllegalStateException> { r.end() }
        assertFailsWith<IllegalStateException> { r.record(0, PointerAction.DOWN, 0, listOf(sample())) }
    }

    /**
     * Metadata is space-separated and never quoted, because a quoting rule is a
     * second grammar to implement in both directions for something nothing
     * parses. The check is at the boundary so the bad value never reaches a
     * file.
     */
    @Test
    fun `metadata that would break the grammar is refused at the boundary`() {
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).begin(0L, meta = mapOf("device" to "Wacom MovinkPad"))
        }
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).begin(0L, meta = mapOf("de vice" to "x"))
        }
        assertFailsWith<IllegalArgumentException> {
            TraceRecorder(StringBuilder()).begin(0L, meta = mapOf("" to "x"))
        }
    }

    /**
     * `FLAG_CANCELED` is the one input that decides whether a pen lift commits
     * a stroke or discards it, so it needs a channel of its own: without one,
     * a session the framework palm-rejected is byte-identical to one that
     * finished cleanly and replays as a committed stroke.
     *
     * A trailing optional token rather than a new column, so every file written
     * before it existed still decodes and the version does not move.
     */
    @Test
    fun `the cancel flag survives a round trip and defaults to absent`() {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(0L)
            record(0, PointerAction.DOWN, 0, listOf(sample()))
            record(1, PointerAction.POINTER_UP, 0, listOf(sample()), canceled = true)
            end()
        }
        assertTrue(out.contains("e 1 POINTER_UP 0 1 !"), out.toString())
        val events = TracePlayer.decode(out.toString()).events
        assertEquals(listOf(false, true), events.map { it.canceled })

        // The v1 files that predate the token: absent means false, not unknown.
        val old = TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 UP 0 0\nend 1 0\n")
        assertEquals(false, old.events.single().canceled)
    }

    @Test
    fun `a trailing token that is not the cancel flag is refused`() {
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 UP 0 0 x\nend 1 0\n")
        }
        assertFailsWith<TraceFormatException> {
            TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 UP 0 0 ! !\nend 1 0\n")
        }
    }

    /**
     * The declared sample count is untrusted data from a file, and it used to
     * be handed straight to `ArrayList(n)` as a capacity. One garbled digit
     * then allocates the heap instead of throwing a parse error — on the tablet
     * that is a process kill, and the file is read there by the same path.
     */
    @Test
    fun `an absurd sample count is a parse error and not an allocation`() {
        val e = assertFailsWith<TraceFormatException> {
            TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 MOVE 0 2000000000\n")
        }
        assertTrue("declared" in e.message!!, e.message!!)
    }

    @Test
    fun `every failure names the line it happened on`() {
        val e = assertFailsWith<TraceFormatException> {
            TracePlayer.decode("artiest-trace 1\nt0 0\ne 0 MOVE 0 1\ns 0 nope\nend 1 1\n")
        }
        assertTrue("line 4" in e.message!!, e.message!!)
    }

    private fun recorded(): String {
        val out = StringBuilder()
        TraceRecorder(out).apply {
            begin(0L)
            record(0, PointerAction.DOWN, 0, listOf(sample(x = 1f, y = 2f)))
            record(1, PointerAction.MOVE, 0, listOf(sample(x = 1f, y = 2f), sample(x = 3f, y = 4f)))
            end()
        }
        return out.toString()
    }
}
