package be.thalos.artiest.input

import android.view.MotionEvent
import android.view.View
import be.thalos.artiest.engine.input.ExclusivityState
import be.thalos.artiest.engine.input.PenSample
import be.thalos.artiest.engine.input.PointerAction
import be.thalos.artiest.engine.input.ToolType
import be.thalos.artiest.engine.trace.TracePlayer
import be.thalos.artiest.engine.trace.TraceRecorder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The router shell: extraction and delivery order, not policy.
 *
 * The rules themselves are `StrokeExclusivity`'s and are tested exhaustively in
 * `:engine` with no Android at all. What is left to check here is everything
 * that only exists once a real `MotionEvent` is involved — which pointer's
 * samples come out, whether a lift carries its own history, what the sink sees
 * and in what order, and that the trace tap sits before the routing decision
 * rather than after it.
 *
 * Two things below are asserted about defaults rather than behaviour, because
 * Robolectric no-ops `requestUnbufferedDispatch` and never dispatches a real
 * ACTION_HOVER_*. Both remain unverified until they are measured on the tablet,
 * and neither is claimed here to be more than reviewed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputRouterTest {

    private val sink = RecordingSink()
    private val router = InputRouter(sink)
    private val view = View(RuntimeEnvironment.getApplication())

    /**
     * The palm case, end to end. The spike had no branch for
     * ACTION_POINTER_DOWN at all, so nothing in this sequence has ever run on
     * this hardware.
     */
    @Test
    fun `a palm landing mid-stroke changes nothing about the live stroke`() {
        penDown(x = 500f)
        sink.clear()

        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), pen(500f), palm())
        assertEquals(emptyList(), sink.calls)
        assertEquals(ExclusivityState.PEN, router.state)

        touch(MotionEvent.ACTION_MOVE, pen(510f), palm())
        assertEquals(listOf("samples"), sink.calls)
        // Only the pen's coordinates, and it is at index 0 here — the assertion
        // that matters is that the palm's 100f never appears.
        assertEquals(listOf(510f), sink.samples.map { it.x })
    }

    /**
     * The lift carries its own history batch, and it arrives before the end
     * that commits it. No spike view expanded the lifting event: two ignore
     * ACTION_UP's coordinates entirely and DirectSurfaceInkView reads only
     * event.x/y, which is why their strokes look clipped at the end.
     */
    @Test
    fun `the lifting event's samples reach the sink before the stroke ends`() {
        penDown(x = 500f)
        sink.clear()

        // addBatch pushes the event's current sample into history and makes
        // the new one current, so the lift position is the batched one.
        val up = motionEvent(MotionEvent.ACTION_UP, listOf(pen(515f)), eventTimeMs = DOWN_TIME_MS + 9)
        up.addBatch(DOWN_TIME_MS + 10, arrayOf(pointerCoords(pen(520f))), 0)
        router.onTouchEvent(up, view)

        assertEquals(listOf("samples", "end", "presence:false"), sink.calls)
        assertEquals(listOf(515f, 520f), sink.samples.map { it.x })
        assertEquals(ExclusivityState.IDLE, router.state)
    }

    /**
     * A cancel discards the stroke and delivers no final sample: the
     * coordinates on a cancel are stale, and a sample emitted here would be
     * drawn by a stroke that is about to be thrown away.
     */
    @Test
    fun `a cancel discards the stroke without a final sample`() {
        penDown(x = 500f)
        sink.clear()

        touch(MotionEvent.ACTION_CANCEL, pen(999f))
        assertEquals(listOf("cancel", "presence:false"), sink.calls)
        assertEquals(emptyList(), sink.samples)
    }

    /**
     * The anti-stuck-bug case: hand resting, pen lifted to reposition, pen back
     * down. The palm was never a gesture, so nothing is locked out, and the
     * stroke begins from ACTION_POINTER_DOWN rather than ACTION_DOWN — which is
     * the branch the spike does not have.
     */
    @Test
    fun `the pen draws again with a palm still resting on the glass`() {
        penDown(x = 500f)
        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), pen(500f), palm())
        touch(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), pen(505f), palm())
        assertEquals(ExclusivityState.DISOWNED, router.state)
        sink.clear()

        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), palm(), pen(600f))
        assertEquals(listOf("presence:true", "begin:$PEN_ID", "samples"), sink.calls)
        assertEquals(listOf(600f), sink.samples.map { it.x })
        assertEquals(ExclusivityState.PEN, router.state)
    }

    /**
     * The stroke's pointer disappearing from the event stream is normal, and it
     * is the one case where indexing anyway reads a pointer that took over the
     * slot. Nothing is emitted for it and the stroke is discarded, not left
     * live holding an id that will never lift.
     */
    @Test
    fun `a stroke whose pointer vanishes is cancelled rather than left live`() {
        penDown(x = 500f)
        sink.clear()

        touch(MotionEvent.ACTION_MOVE, palm())
        assertEquals(listOf("cancel", "presence:false"), sink.calls)
        assertEquals(emptyList(), sink.samples)
        assertEquals(ExclusivityState.IDLE, router.state)
    }

    /**
     * True for everything, including events the router drops entirely.
     * Returning false hands the gesture to an ancestor view, which can hand it
     * back as ACTION_CANCEL and chop the live stroke in half — the exact
     * failure the exclusivity rule exists to prevent.
     */
    @Test
    fun `every touch event is consumed, including the ones that are dropped`() {
        penDown(x = 500f)
        assertTrue(
            router.onTouchEvent(
                motionEvent(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(pen(500f), palm())),
                view,
            ),
        )
        assertTrue(
            router.onTouchEvent(motionEvent(MotionEvent.ACTION_SCROLL, listOf(pen(500f))), view),
        )
    }

    /**
     * A finger alone is a resting contact, not a pan. Two fingers pan, and both
     * positions come out — the first one having waited, disowned, for the
     * second. Panning on one would lock the pen out for as long as a hand
     * rested on the glass, which is the whole reason the threshold is two.
     */
    @Test
    fun `two fingers begin a gesture and both positions come out`() {
        touch(MotionEvent.ACTION_DOWN, palm())
        assertEquals(emptyList(), sink.calls)
        assertEquals(ExclusivityState.DISOWNED, router.state)

        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), palm(), finger2())
        assertEquals(listOf("gestureBegin", "gesturePointers:2"), sink.calls)
        assertEquals(listOf(100f, 300f), sink.gestureXs)
        assertEquals(emptyList(), sink.samples)
    }

    /**
     * The end-to-end version of the stuck-input bug this work item exists to
     * prevent, through a real MotionEvent stream: hand lands first, pen lands
     * second, and the pen draws. It needs two pointers in one event, so it
     * cannot be written anywhere the SDK is a stub.
     */
    @Test
    fun `a palm that lands before the pen does not stop the pen drawing`() {
        touch(MotionEvent.ACTION_DOWN, palm())
        sink.clear()

        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), palm(), pen(600f))
        assertEquals(listOf("presence:true", "begin:$PEN_ID", "samples"), sink.calls)
        assertEquals(listOf(600f), sink.samples.map { it.x })
        assertEquals(ExclusivityState.PEN, router.state)
    }

    /**
     * Hover is a presence signal and nothing else. It is not expanded into the
     * scratch list, because no stage in Phase 1 reads a hover sample and the
     * distance axis those samples would carry reads zero on this digitizer.
     */
    @Test
    fun `hover reports presence and produces no samples`() {
        assertTrue(router.onHoverEvent(motionEvent(MotionEvent.ACTION_HOVER_ENTER, listOf(pen(10f)))))
        assertEquals(listOf("presence:true"), sink.calls)
        assertEquals(emptyList(), sink.samples)
        assertTrue(router.penInRange)

        sink.clear()
        router.onHoverEvent(motionEvent(MotionEvent.ACTION_HOVER_EXIT, listOf(pen(10f))))
        assertEquals(listOf("presence:false"), sink.calls)
    }

    /** A hovering finger is not a contact and has nothing to be excluded from. */
    @Test
    fun `a hovering finger is dropped`() {
        router.onHoverEvent(motionEvent(MotionEvent.ACTION_HOVER_ENTER, listOf(palm())))
        assertEquals(emptyList(), sink.calls)
        assertFalse(router.penInRange)
    }

    /**
     * The window can go away without an ACTION_CANCEL ever arriving — an
     * activity stopped, a view detached, a dialog taking focus. Without this
     * the machine holds a stale pointer id forever.
     */
    @Test
    fun `abandoning a live stroke cancels it with no event to cancel it with`() {
        penDown(x = 500f)
        sink.clear()

        router.abandon()
        assertEquals(listOf("cancel", "presence:false"), sink.calls)
        assertEquals(ExclusivityState.IDLE, router.state)
    }

    /**
     * Off, against the spike's `= true`. The regression that argued for turning
     * it on was measured against a render path that no longer exists, so the
     * honest state is unverified: Robolectric no-ops the call, and nothing here
     * can say what it does to batching on the tablet.
     */
    @Test
    fun `unbuffered dispatch is off until something measures it`() {
        assertFalse(router.unbufferedDispatch)
    }

    /**
     * Hover is recorded too, on the shared seq counter. Presence is a router
     * output with a sink callback of its own, and a trace that skipped hover
     * would flip it only at DOWN and UP — a different signal from the session
     * the file claims to reproduce, on exactly the behaviour no JVM test can
     * otherwise reach.
     */
    @Test
    fun `hover is recorded so presence is reproducible from a file`() {
        val text = StringBuilder()
        router.recorder = TraceRecorder(text).apply { begin(t0Nanos = DOWN_TIME_MS * 1_000_000L) }

        router.onHoverEvent(motionEvent(MotionEvent.ACTION_HOVER_ENTER, listOf(pen(10f))))
        penDown(x = 500f)
        router.onHoverEvent(motionEvent(MotionEvent.ACTION_HOVER_EXIT, listOf(pen(10f))))
        router.recorder!!.end()

        val trace = TracePlayer.decode(text.toString())
        assertEquals(
            listOf(PointerAction.HOVER_ENTER, PointerAction.DOWN, PointerAction.HOVER_EXIT),
            trace.events.map { it.action },
        )
        // Zero samples on hover: nothing in Phase 1 reads one, and the distance
        // axis they would carry is dead on this digitizer.
        assertEquals(listOf(0, 1, 0), trace.events.map { it.samples.size })
        // Interleaving preserved, which is the only reason to share the counter.
        assertEquals(listOf(0, 1, 2), trace.events.map { it.seq })
    }

    /**
     * FLAG_CANCELED is the single input that decides whether a lift commits the
     * stroke or discards it, so a trace that drops it replays a palm-rejected
     * session as a committed stroke, silently and byte-identically.
     */
    @Test
    fun `the cancel flag reaches the trace`() {
        val text = StringBuilder()
        router.recorder = TraceRecorder(text).apply { begin(t0Nanos = DOWN_TIME_MS * 1_000_000L) }

        penDown(x = 500f)
        router.onTouchEvent(
            motionEvent(
                MotionEvent.ACTION_UP,
                listOf(pen(505f)),
                flags = MotionEvent.FLAG_CANCELED,
            ),
            view,
        )
        router.recorder!!.end()

        assertEquals(listOf(false, true), TracePlayer.decode(text.toString()).events.map { it.canceled })
        // ...and the router discarded the stroke rather than committing it.
        assertTrue("cancel" in sink.calls, sink.calls.toString())
        assertFalse("end" in sink.calls, sink.calls.toString())
    }

    /**
     * The trace tap sits before the routing decision, so a recorded file
     * carries the contacts the router rejected. Recording what the router
     * emitted instead would leave palm rejection permanently outside the
     * regression net and let the machine agree with its own conclusions on
     * replay.
     */
    @Test
    fun `the trace records the palm the router dropped`() {
        val text = StringBuilder()
        router.recorder = TraceRecorder(text).apply { begin(t0Nanos = DOWN_TIME_MS * 1_000_000L) }

        penDown(x = 500f)
        touch(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), pen(500f), palm())
        router.recorder!!.end()

        val trace = TracePlayer.decode(text.toString())
        assertEquals(2, trace.events.size)
        val palmEvent = trace.events[1]
        assertEquals(PALM_ID, palmEvent.pointerId)
        assertEquals(ToolType.FINGER, palmEvent.samples.single().toolType)
        // The router emitted nothing for it, and the trace has it anyway.
        assertEquals(listOf("presence:true", "begin:$PEN_ID", "samples"), sink.calls)
    }

    // ---- driving -----------------------------------------------------------

    private fun penDown(x: Float) {
        touch(MotionEvent.ACTION_DOWN, pen(x))
    }

    private fun touch(action: Int, vararg pointers: TestPointer) {
        router.onTouchEvent(motionEvent(action, pointers.toList()), view)
    }

    private fun pen(x: Float) = TestPointer(id = PEN_ID, toolType = ToolType.STYLUS, x = x, y = 900f)

    private fun palm() = TestPointer(id = PALM_ID, toolType = ToolType.FINGER, x = 100f, y = 1400f)

    /** The second finger, which is what turns a resting contact into a pan. */
    private fun finger2() = TestPointer(id = FINGER2_ID, toolType = ToolType.FINGER, x = 300f, y = 1400f)

    /**
     * Records the sink call sequence, because order is half of what this class
     * gets wrong: a begin delivered before the cancel of the stroke it replaces
     * leaves the consumer holding two live strokes.
     *
     * Copies the samples out. The list it is handed is the router's scratch and
     * the next event refills it — which is the contract every real consumer has
     * to honour too.
     */
    private class RecordingSink : InkInputSink {
        val calls = ArrayList<String>()
        val samples = ArrayList<PenSample>()
        val gestureXs = ArrayList<Float>()

        fun clear() {
            calls.clear()
            samples.clear()
            gestureXs.clear()
        }

        override fun onStrokeBegin(pointerId: Int) {
            calls += "begin:$pointerId"
        }

        override fun onStrokeSamples(samples: ArrayList<PenSample>) {
            calls += "samples"
            for (i in 0 until samples.size) this.samples += samples[i]
        }

        override fun onStrokeEnd() {
            calls += "end"
        }

        override fun onStrokeCancel() {
            calls += "cancel"
        }

        override fun onGestureBegin() {
            calls += "gestureBegin"
        }

        override fun onGesturePointers(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) {
            calls += "gesturePointers:$count"
            for (i in 0 until count) gestureXs += xs[i]
        }

        override fun onGestureEnd() {
            calls += "gestureEnd"
        }

        override fun onGestureCancel() {
            calls += "gestureCancel"
        }

        override fun onPenPresence(inRange: Boolean) {
            calls += "presence:$inRange"
        }
    }

    private companion object {
        const val PEN_ID = 7
        const val PALM_ID = 3
        const val FINGER2_ID = 4
    }
}
