package be.thalos.artiest.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity

/**
 * The grid, and the pen that draws toolbars on it.
 *
 * The interaction nobody else has, and the reason this app should do it. In
 * arrange mode the whole screen is ruled into cells, the pen paints them, the
 * eraser end takes them away, and what is under the pen when you lift is a
 * toolbar.
 *
 * It used to be a modal board half the size of the screen, aligned to whichever
 * edge the bar being edited was docked to, applied on Done. All three of those
 * were the dock model showing through and all three are gone: there is one
 * board, it is the screen, and an edit lands when the hand lifts.
 *
 * ## Why the preview coalesces on every event
 *
 * The shape under the pen is rebuilt through [CellRegion.ofCells] as it is
 * painted, which is a rasterise and a re-coalesce a few hundred cells wide,
 * several times a second. That is affordable, and it is the reason `ofCells`
 * had to be **stable** rather than minimal: adding one cell changes one
 * rectangle and leaves the others alone, so the outline does not shimmer and
 * re-round itself under a pen that is still moving. A minimal rectangle cover
 * would have looked like a fault.
 *
 * ## The eraser end, and the toggle beside it
 *
 * A stylus with an eraser reports [PointerType.Eraser], and turning it over to
 * rub a cell out is the gesture people already have in their hands. Not every
 * pen has one and no finger does, so there is a Rub out toggle as well — the
 * toggle is the feature and the eraser end is the shortcut, not the other way
 * round.
 *
 * ## What it does not do
 *
 * It answers the pointer **only while [painting]**. In the other half of arrange
 * mode the surfaces themselves are the targets — a chip is dragged, an empty
 * cell is tapped — and a board that swallowed pointers underneath them would
 * make both impossible. Outside arrange mode it is not composed at all, which
 * is the rule the whole chrome is held to: while a stroke is being drawn on the
 * paper, nothing here may run.
 */
@Composable
internal fun GridBoard(
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    gridW: Int,
    gridH: Int,
    /** True while the board takes the pointer. See the KDoc. */
    painting: Boolean,
    /** Rub out rather than paint, for a pen with no eraser end and for a finger. */
    subtract: Boolean,
    modifier: Modifier = Modifier,
) {
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }
    val scheme = MaterialTheme.colorScheme

    var stroke by remember { mutableStateOf<GridPaint.Stroke?>(null) }
    val preview = remember(stroke, layout) {
        stroke?.let { GridPaint.previewOf(layout, it) }.orEmpty()
    }
    val erasing = stroke?.erasing == true

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                // Faint, but not invisible: it is the ruler everything is
                // measured against, and it is what says arrange mode is on.
                val line = scheme.onSurface.copy(alpha = if (painting) 0.16f else 0.07f)
                for (i in 0..gridW) {
                    drawLine(line, Offset(i * slotPx, 0f), Offset(i * slotPx, gridH * slotPx))
                }
                for (i in 0..gridH) {
                    drawLine(line, Offset(0f, i * slotPx), Offset(gridW * slotPx, i * slotPx))
                }
                val ink = if (erasing) scheme.error else scheme.primary
                for (cell in preview) {
                    drawRect(
                        ink.copy(alpha = 0.35f),
                        topLeft = Offset(cell.x * slotPx, cell.y * slotPx),
                        size = Size(slotPx, slotPx),
                    )
                }
            }
            .then(
                if (!painting) Modifier else Modifier.pointerInput(gridW, gridH, subtract, layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val rub = subtract || down.type == PointerType.Eraser
                        val first = DropMath.cellAt(down.position, slotPx, gridW, gridH)
                        if (first != null) stroke = GridPaint.begin(layout, first, rub)
                        drag(down.id) { change ->
                            change.consume()
                            val cell = DropMath.cellAt(change.position, slotPx, gridW, gridH)
                                ?: return@drag
                            val current = stroke
                            stroke = current?.plus(cell) ?: GridPaint.begin(layout, cell, rub)
                        }
                        stroke?.let { onLayout(GridPaint.applyTo(layout, it)) }
                        stroke = null
                    }
                }
            ),
    )
}
