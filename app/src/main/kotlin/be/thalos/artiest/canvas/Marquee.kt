package be.thalos.artiest.canvas

import android.graphics.Path

/** What a marquee gesture draws. */
enum class MarqueeShape {
    RECTANGLE,
    ELLIPSE,

    /** Free-hand: the pen's own trail, closed at the end. */
    LASSO,
}

/**
 * A selection shape, under construction, in document coordinates.
 *
 * ## Why this is on the UI thread and stays there
 *
 * A marquee is not ink. Nothing about it touches a sheet, so none of the
 * render-thread discipline `Stroke` lives under applies: the pen samples arrive
 * on the UI thread, this builds a `Path` on the UI thread, the chrome draws
 * that same `Path` on the UI thread, and only at pen-up does a **copy** cross
 * into the commit queue as a `SelectOp.Shape`. There is no publication and no
 * volatile, because there is no second thread.
 *
 * That is also why the live outline costs nothing: the overlay draws this
 * object directly rather than a snapshot of it, so a gesture at 200 Hz
 * allocates no paths at all.
 *
 * ## Why the lasso is simplified on the way in
 *
 * The digitizer reports up to 320 samples a second, so a three-second lasso is
 * close to a thousand points. Every one of them is transformed and stroked on
 * every frame of the marching ants' animation, and every one of them is work
 * for `Path.op` when the shape is combined with what is already selected. Two
 * document pixels between points is below what the eye can find on a 230 dpi
 * panel and is roughly a tenth of the raw rate. `Stabilizer` already
 * establishes that the raw sample stream is not the thing to keep.
 *
 * Rectangles and ellipses are rebuilt from their two corners on every sample
 * instead, which is two `rewind`s and an `addRect` — cheaper than tracking what
 * changed, and it means dragging back past the origin is handled by
 * normalisation rather than by cases.
 */
class Marquee {

    /**
     * The shape so far, in document coordinates.
     *
     * Handed out live and not copied. See the class header: one thread owns it,
     * and the one place it crosses to another — `SelectOp.Shape` — copies.
     */
    val path = Path()

    var isOpen: Boolean = false
        private set

    var shape: MarqueeShape = MarqueeShape.RECTANGLE
        private set

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Points actually kept, which for a lasso is not the number sampled. */
    private var kept = 0

    fun begin(shape: MarqueeShape, xDoc: Float, yDoc: Float) {
        this.shape = shape
        path.rewind()
        startX = xDoc
        startY = yDoc
        lastX = xDoc
        lastY = yDoc
        kept = 1
        isOpen = true
        if (shape == MarqueeShape.LASSO) path.moveTo(xDoc, yDoc)
    }

    /**
     * Take one more sample. Returns true if [path] changed, so the caller can
     * decide whether a redraw is worth asking for.
     */
    fun extend(xDoc: Float, yDoc: Float): Boolean {
        if (!isOpen) return false
        return when (shape) {
            MarqueeShape.LASSO -> {
                val dx = xDoc - lastX
                val dy = yDoc - lastY
                if (dx * dx + dy * dy < MIN_STEP_DOC_PX * MIN_STEP_DOC_PX) return false
                path.lineTo(xDoc, yDoc)
                lastX = xDoc
                lastY = yDoc
                kept++
                true
            }

            else -> {
                if (xDoc == lastX && yDoc == lastY) return false
                lastX = xDoc
                lastY = yDoc
                rebuildBox()
                true
            }
        }
    }

    /**
     * Close the shape and hand it over. **The caller must copy it** — or let
     * `SelectOp.Shape` do that, which is what it is for.
     *
     * A gesture that never went anywhere leaves an empty path, and that is
     * deliberate rather than an oversight: `Selection` treats a shape with no
     * area as a deselect, so a tap with the marquee in hand clears the
     * selection, which is what every editor does and what the hand expects.
     */
    fun end(): Path {
        if (isOpen && shape == MarqueeShape.LASSO) {
            // Two points are a line and a line has no area, so a flick that
            // never curved back deselects rather than selecting a hair.
            if (kept > 2) path.close() else path.rewind()
        }
        isOpen = false
        return path
    }

    fun cancel() {
        isOpen = false
        path.rewind()
    }

    /**
     * The dragged rectangle, normalised so that dragging up and to the left
     * works as well as down and to the right.
     */
    private fun rebuildBox() {
        val left = minOf(startX, lastX)
        val top = minOf(startY, lastY)
        val right = maxOf(startX, lastX)
        val bottom = maxOf(startY, lastY)
        path.rewind()
        if (right - left <= 0f || bottom - top <= 0f) return
        when (shape) {
            MarqueeShape.ELLIPSE -> path.addOval(left, top, right, bottom, Path.Direction.CW)
            else -> path.addRect(left, top, right, bottom, Path.Direction.CW)
        }
    }

    companion object {
        /** See the class header. Two document pixels is about 0.15 mm at 230 dpi. */
        const val MIN_STEP_DOC_PX = 2f
    }
}
