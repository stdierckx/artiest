package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * Draw your toolbar.
 *
 * The interaction nobody else has, and the reason this app should do it. The
 * grid appears where the toolbar is anchored, the pen paints cells, the eraser
 * end takes them away, and what is under the pen when you lift is the shape.
 *
 * ## Why the preview coalesces on every event
 *
 * The shape under the pen is rebuilt through [CellRegion.ofCells] as it is
 * painted, which is a rasterise and a re-coalesce a few hundred cells wide,
 * several times a second. That is affordable, and it is the reason
 * `ofCells` had to be **stable** rather than minimal: adding one cell changes
 * one rectangle and leaves the others alone, so the outline does not shimmer
 * and re-round itself under a pen that is still moving. A minimal rectangle
 * cover would have looked like a fault.
 *
 * ## The eraser end, and the toggle beside it
 *
 * A stylus with an eraser reports `PointerType.Eraser`, and turning it over to
 * rub a cell out is the gesture people already have in their hands. Not every
 * pen has one and no finger does, so there is a Subtract toggle as well — the
 * toggle is the feature and the eraser end is the shortcut, not the other way
 * round.
 *
 * ## Where the shape ends up
 *
 * On an edge, **you choose the shape and the edge chooses where it sits.** The
 * result is taken to its own origin and anchored the way that edge has always
 * been anchored, so a gap you leave between the paint and the screen edge is
 * not kept. That is stated rather than hidden because the alternative — an
 * edge toolbar floating somewhere near its edge — is not an edge toolbar, and
 * a surface that is meant to be anywhere is a floating one.
 *
 * Nothing is applied until Done. Cancel leaves the surface exactly as it was,
 * which matters because this is a destructive edit: a cell taken away is a
 * control with nowhere to stand.
 */
@Composable
internal fun ShapeEditor(
    surface: Surface,
    gridW: Int,
    gridH: Int,
    onCancel: () -> Unit,
    onApply: (CellRegion) -> Unit,
) {
    val density = LocalDensity.current
    val slotPx = with(density) { Chrome.SLOT.toPx() }
    val radiusPx = with(density) { CORNER.toPx() }

    val boardW = boardWidth(surface.dock, gridW)
    val boardH = boardHeight(surface.dock, gridH)

    var painted by remember(surface.id) {
        mutableStateOf(surface.region.atOrigin().clampedTo(boardW, boardH).cellSet())
    }
    var subtract by remember { mutableStateOf(false) }

    val preview = remember(painted) { CellRegion.ofCells(painted) }
    val ground = remember(preview, slotPx, radiusPx) {
        if (preview.isEmpty) null else groundPath(preview.atOrigin(), slotPx, radiusPx)
    }
    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            // A scrim, so the grid reads as a mode and the drawing underneath
            // is still visible enough to shape a toolbar around.
            .background(scheme.scrim.copy(alpha = 0.28f))
            // Swallows anything that misses the board, so a stray touch during
            // shaping does not land on the canvas as a stroke. This is the one
            // place in the chrome where covering the paper is correct.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
    ) {
        val boardModifier = Modifier
            .align(surface.dock.editorAlignment())
            .padding(Chrome.EDGE_INSET)
            .width(Chrome.SLOT * boardW)
            .height(Chrome.SLOT * boardH)

        Box(
            boardModifier
                .drawBehind {
                    // The empty grid first, then the shape over it. Faint, but
                    // not invisible: it is the ruler this is drawn against.
                    val line = scheme.onSurface.copy(alpha = 0.14f)
                    for (i in 0..boardW) {
                        drawLine(line, Offset(i * slotPx, 0f), Offset(i * slotPx, boardH * slotPx))
                    }
                    for (i in 0..boardH) {
                        drawLine(line, Offset(0f, i * slotPx), Offset(boardW * slotPx, i * slotPx))
                    }
                    ground?.let {
                        val b = preview.bounds
                        val path = it.asComposePath()
                        translate(b.x * slotPx, b.y * slotPx) {
                            drawPath(path, scheme.surface.copy(alpha = Chrome.BAR_ALPHA))
                            drawPath(path, scheme.primary, style = Stroke(width = 2f))
                        }
                    }
                }
                .pointerInput(boardW, boardH, subtract) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val erasing = subtract || down.type == PointerType.Eraser
                        painted = painted.paint(down.position, slotPx, boardW, boardH, erasing)
                        drag(down.id) { change ->
                            change.consume()
                            painted =
                                painted.paint(change.position, slotPx, boardW, boardH, erasing)
                        }
                    }
                },
        )

        ShapeEditorBar(
            subtract = subtract,
            onSubtract = { subtract = it },
            cells = painted.size,
            onCancel = onCancel,
            onApply = { onApply(CellRegion.ofCells(painted).atOrigin()) },
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Add or remove the cell under [point]. Outside the board, nothing happens.
 *
 * Internal rather than private so it can be tested: it is the pure half of the
 * gesture, and the half where being wrong is a shape the user did not draw.
 */
internal fun Set<Cell>.paint(
    point: Offset,
    slotPx: Float,
    boardW: Int,
    boardH: Int,
    erasing: Boolean,
): Set<Cell> {
    if (slotPx <= 0f) return this
    val cell = Cell(floor(point.x / slotPx).toInt(), floor(point.y / slotPx).toInt())
    if (cell.x !in 0 until boardW || cell.y !in 0 until boardH) return this
    if (erasing) {
        if (cell !in this) return this
        return this - cell
    }
    if (cell in this) return this
    return this + cell
}

/** Subtract, how many cells there are, and the way out in both directions. */
@Composable
private fun ShapeEditorBar(
    subtract: Boolean,
    onSubtract: (Boolean) -> Unit,
    cells: Int,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA),
                RoundedCornerShape(14.dp),
            )
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            "draw the toolbar · turn the pen over to rub out",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Chip(
            label = "Subtract",
            lit = subtract,
            enabled = true,
            onClick = { onSubtract(!subtract) },
        )
        Chip(label = "Cancel", lit = false, enabled = true, onClick = onCancel)
        Chip(
            label = "Done",
            lit = true,
            // A toolbar with no cells is not a toolbar, and an empty shape
            // would take every control on it with nowhere to go.
            enabled = cells > 0,
            onClick = onApply,
        )
    }
}

@Composable
private fun Chip(label: String, lit: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 11.sp,
        color = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.3f)
            lit -> scheme.onPrimary
            else -> scheme.primary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (lit && enabled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * How big the board is, in cells, for an anchor.
 *
 * Half the screen at most in the direction the toolbar does not run, so that
 * the drawing underneath stays visible and so that a shape cannot be painted
 * across the whole glass by accident. Along its own direction it gets the lot,
 * because that is the dimension a bar is allowed to fill.
 */
private fun boardWidth(dock: Dock, gridW: Int): Int = when (dock) {
    Dock.LEFT, Dock.RIGHT -> (gridW / 2).coerceIn(1, MAX_ACROSS)
    else -> gridW.coerceAtLeast(1)
}

private fun boardHeight(dock: Dock, gridH: Int): Int = when (dock) {
    Dock.TOP, Dock.BOTTOM -> (gridH / 2).coerceIn(1, MAX_ACROSS)
    else -> gridH.coerceAtLeast(1)
}

/** Where the board sits, which is where the toolbar will sit. */
private fun Dock.editorAlignment(): Alignment = when (this) {
    Dock.LEFT -> Alignment.CenterStart
    Dock.RIGHT -> Alignment.CenterEnd
    Dock.TOP -> Alignment.TopCenter
    Dock.BOTTOM -> Alignment.BottomCenter
    Dock.FLOATING -> Alignment.Center
}

/** Ten cells is 440dp. A toolbar deeper than that is a wall. */
private const val MAX_ACROSS = 10

private val CORNER = 20.dp
