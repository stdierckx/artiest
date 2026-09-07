package be.thalos.artiest.input

import android.view.InputDevice
import android.view.MotionEvent
import be.thalos.artiest.engine.input.ToolType

/**
 * Synthesized events for the tests that need a real `MotionEvent`.
 *
 * Only usable under Robolectric: `MotionEvent.obtain` is a real method, and a
 * plain JVM unit test throws "not mocked" the moment it is called.
 *
 * Pointer order is significant in every test that uses this, so the builder
 * takes a list rather than a map: an event's pointer *index* is its position
 * here, and its *id* is whatever it was given, which is exactly the distinction
 * the pointerId edit exists around.
 */
internal class TestPointer(
    val id: Int,
    val toolType: Int = ToolType.STYLUS,
    val x: Float = 0f,
    val y: Float = 0f,
    val pressure: Float = 0.5f,
    val tilt: Float = 0f,
)

internal const val DOWN_TIME_MS = 2_000L

internal fun motionEvent(
    action: Int,
    pointers: List<TestPointer>,
    eventTimeMs: Long = DOWN_TIME_MS,
    buttonState: Int = 0,
    flags: Int = 0,
): MotionEvent {
    val props = Array(pointers.size) { i ->
        MotionEvent.PointerProperties().apply {
            id = pointers[i].id
            toolType = pointers[i].toolType
        }
    }
    val coords = Array(pointers.size) { i -> pointerCoords(pointers[i]) }
    return MotionEvent.obtain(
        DOWN_TIME_MS, eventTimeMs, action, pointers.size, props, coords,
        0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags,
    )
}

/** The action word for a pointer action, with the index packed into it. */
internal fun pointerAction(action: Int, index: Int): Int =
    action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

internal fun pointerCoords(p: TestPointer): MotionEvent.PointerCoords =
    MotionEvent.PointerCoords().apply {
        x = p.x
        y = p.y
        pressure = p.pressure
        setAxisValue(MotionEvent.AXIS_TILT, p.tilt)
    }
