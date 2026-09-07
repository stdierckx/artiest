package be.thalos.artiest.engine.input

/**
 * What a pointer just did, as the router and the trace format both see it.
 *
 * One enum rather than one per consumer. The alternative — a routing enum and a
 * trace enum with six shared members — is two lists that have to be edited
 * together forever, and the first time they drift the trace decodes into a
 * routing decision that was never recorded.
 *
 * The names track `MotionEvent`'s masked actions, but the mapping lives in
 * `:app`'s `MotionEvents.kt`, which is the same statement as "MotionEvent dies
 * at InputRouter": no Android action constant appears in this module, and no
 * trace file contains one, so a trace stays readable without an SDK.
 *
 * One member is not a MotionEvent action at all:
 *
 * [POINTER_LOST] is synthesized by `:app` when `findPointerIndex` returns -1
 * for the pointer that owns the live stroke — a normal occurrence when a
 * pointer leaves mid-gesture, and the one case where indexing into the event
 * would read whatever pointer took over that slot. It is derivable on replay
 * (the tracked id is absent from the event's pointer set), so `TraceRecorder`
 * rejects it: recording a derived signal makes the router agree with itself.
 *
 * The three `HOVER_*` members are MotionEvent actions — `hoverActionOf` maps
 * them one to one — but they never reach [StrokeExclusivity.route]. They arrive
 * through `View.onHoverEvent`, which the framework uses for hover and
 * `onTouchEvent` for touch — a routing detail that silently makes working
 * hardware look like it cannot hover if you only override the latter.
 */
enum class PointerAction {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
    POINTER_LOST,
    HOVER_ENTER,
    HOVER_MOVE,
    HOVER_EXIT;

    val isHover: Boolean
        get() = this == HOVER_ENTER || this == HOVER_MOVE || this == HOVER_EXIT
}
