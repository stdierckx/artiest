package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.canvas.MarqueeShape
import be.thalos.artiest.doc.FloatOp
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp

/**
 * The selection panel, as a button that opens it.
 *
 * Fourteen controls — three shapes, four combine modes, three commands, four
 * ways to move pixels — which is a toolbar's worth on its own, so it is a panel
 * for the reason `LayersPanel` and the colour wheel are: what a bar can usefully
 * show about a selection at a glance is nothing, so it shows a way in.
 *
 * **Nothing here holds selection state.** The shape and the combine mode belong
 * to the view, which is what a marquee gesture reads at pen-down; the region
 * belongs to the document and is changed only through [onOp], which queues.
 * This composable is a set of buttons over other people's state, and the
 * `open` flag is the only thing it remembers.
 *
 * **Choosing a shape turns the marquee on.** Reaching for the ellipse while the
 * pen is still a pencil and finding that nothing happens is the kind of dead
 * control this project has already been bitten by once — the toolbar items that
 * shipped invisible. Picking a shape is an unambiguous statement of intent, so
 * it is taken as one.
 *
 * **It can be kept.** The pin hands the same body to a floating bar of its own
 * — see [ToolItem.SELECTION_PANEL] — which is worth more here than on either of
 * the other two panels: a selection is the one thing in this app you keep
 * adjusting, and every adjustment used to cost a tap to reopen a panel that
 * closes itself as soon as the pen touches the page.
 */
@Composable
fun SelectionButton(
    shape: MarqueeShape,
    mode: SelectMode,
    selecting: Boolean,
    hasSelection: Boolean,
    floating: Boolean,
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onFloatOp: (FloatOp) -> Unit,
    onSelecting: (Boolean) -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    // The grid the fixated card lands on: a cell is Chrome.SLOT, everywhere.
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(38.dp).onGloballyPositioned { here = it.positionInRoot() },
    ) {
        IconToolButton(
            // Not `marquee`: that glyph belongs to the toggle that makes the
            // pen select, and the bar carried the same face twice.
            icon = ToolIcons.selectionPanel,
            label = "Selection",
            onClick = { open = true },
            selected = open,
        )
        if (open) {
            SelectionPanel(
                shape = shape,
                mode = mode,
                selecting = selecting,
                hasSelection = hasSelection,
                floating = floating,
                onShape = {
                    onShape(it)
                    onSelecting(true)
                },
                onMode = onMode,
                onOp = onOp,
                onFloatOp = onFloatOp,
                onDismiss = { open = false },
                onFixate = {
                    open = false
                    onFixate(DropMath.cellBeside(here, view.width, view.height, slotPx))
                },
            )
        }
    }
}

@Composable
private fun SelectionPanel(
    shape: MarqueeShape,
    mode: SelectMode,
    selecting: Boolean,
    hasSelection: Boolean,
    floating: Boolean,
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onFloatOp: (FloatOp) -> Unit,
    onDismiss: () -> Unit,
    onFixate: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }

    Popup(
        popupPositionProvider = remember(gap) { PanelPosition(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 14.dp,
            modifier = Modifier.width(SELECTION_PANEL_WIDTH),
        ) {
            SelectionBody(
                shape = shape,
                mode = mode,
                selecting = selecting,
                hasSelection = hasSelection,
                floating = floating,
                onShape = onShape,
                onMode = onMode,
                onOp = onOp,
                // Lifting closes the panel: the transform box is on the canvas
                // and this card would be sitting over the pixels it moves.
                onFloatOp = { op ->
                    onFloatOp(op)
                    if (op === FloatOp.LiftSelection || op === FloatOp.LiftLayer) onDismiss()
                },
                onFixate = onFixate,
            )
        }
    }
}

/**
 * The same panel, as a control on a bar.
 *
 * It fills the cell the layout gave it rather than sizing itself, because a
 * control that disagrees with its slot is a control that overlaps its
 * neighbour. There is no pin on it: it is already kept, and no close button
 * either — the bar it is on has one.
 *
 * Lifting does **not** close anything here, which is the whole reason the
 * fixated version is worth having: Move, Paste and Cancel stay under the hand
 * for as long as the pixels are in the air.
 */
@Composable
fun SelectionPanelCard(
    shape: MarqueeShape,
    mode: SelectMode,
    selecting: Boolean,
    hasSelection: Boolean,
    floating: Boolean,
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onFloatOp: (FloatOp) -> Unit,
) {
    SelectionBody(
        shape = shape,
        mode = mode,
        selecting = selecting,
        hasSelection = hasSelection,
        floating = floating,
        onShape = onShape,
        onMode = onMode,
        onOp = onOp,
        onFloatOp = onFloatOp,
        onFixate = null,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The panel's contents, with nothing around them.
 *
 * Shared by the popup and by [SelectionPanelCard] for the reason `ColourBody`
 * is shared: fixating changes where the panel is drawn and nothing about what
 * it is, and a second copy would be two selection panels that drift apart.
 */
@Composable
private fun SelectionBody(
    shape: MarqueeShape,
    mode: SelectMode,
    selecting: Boolean,
    hasSelection: Boolean,
    floating: Boolean,
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onFloatOp: (FloatOp) -> Unit,
    onFixate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text(
                "Selection",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (selecting) "pen selects" else "pen draws",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onFixate != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onFixate),
                ) {
                    Icon(
                        ToolIcons.pin,
                        "Keep this panel on screen",
                        Modifier.size(15.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(ToolIcons.marquee, "Rectangle", shape == MarqueeShape.RECTANGLE) {
                onShape(MarqueeShape.RECTANGLE)
            }
            Choice(ToolIcons.marqueeOval, "Ellipse", shape == MarqueeShape.ELLIPSE) {
                onShape(MarqueeShape.ELLIPSE)
            }
            Choice(ToolIcons.marqueeLasso, "Free draw", shape == MarqueeShape.LASSO) {
                onShape(MarqueeShape.LASSO)
            }
        }

        Spacer(Modifier.height(10.dp))
        Label("What a gesture does")
        Spacer(Modifier.height(4.dp))
        // A picture with a caption under it, and it used to be four bare words:
        // New / Add / Take / Both. The pictures are the ones every editor
        // draws, so somebody who has met one before does not have to learn
        // this one; the caption is there because the difference between "add"
        // and "intersect" is still a sentence, and the words are now the ones
        // that sentence would use.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Act(ToolIcons.selectNew, "New", mode == SelectMode.NEW) { onMode(SelectMode.NEW) }
            Act(ToolIcons.selectAdd, "Add", mode == SelectMode.ADD) { onMode(SelectMode.ADD) }
            Act(ToolIcons.selectSubtract, "Subtract", mode == SelectMode.SUBTRACT) {
                onMode(SelectMode.SUBTRACT)
            }
            Act(ToolIcons.selectIntersect, "Overlap", mode == SelectMode.INTERSECT) {
                onMode(SelectMode.INTERSECT)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Hold the pen's side button to subtract.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Act(ToolIcons.selectAll, "All", false) { onOp(SelectOp.All) }
            Act(ToolIcons.selectNone, "None", false, enabled = hasSelection) { onOp(SelectOp.None) }
            Act(ToolIcons.selectInvert, "Invert", false, enabled = hasSelection) {
                onOp(SelectOp.Invert)
            }
        }

        Spacer(Modifier.height(10.dp))
        Label("Move and turn")
        Spacer(Modifier.height(4.dp))
        // Move is one button and its two endings are the other two. Nothing
        // here is a mode the user can be left in by accident: while pixels are
        // in the air the box is on the canvas saying so, and both ways out are
        // in front of them.
        //
        // The words are the user's. "Lift" and "Drop" are what the code calls
        // these operations and they were never what a person calls them: what
        // the buttons do to the drawing is move something and then paste it.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Act(ToolIcons.moveFloat, "Move", false, enabled = hasSelection && !floating) {
                onFloatOp(FloatOp.LiftSelection)
            }
            Act(ToolIcons.moveSheet, "Sheet", false, enabled = !floating) {
                onFloatOp(FloatOp.LiftLayer)
            }
            Act(ToolIcons.dropFloat, "Paste", false, enabled = floating) {
                onFloatOp(FloatOp.Drop)
            }
            // "Cancel" and not "Undo": there is a real Undo on the top bar,
            // and this is not it -- nothing has been written, so there is
            // nothing in the history to walk back.
            Act(ToolIcons.close, "Cancel", false, enabled = floating) {
                onFloatOp(FloatOp.Cancel)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/**
 * A shape button: a glyph and nothing else, because the three shapes are the
 * one row here where the picture *is* the answer.
 */
@Composable
private fun RowScope.Choice(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A glyph with its word under it.
 *
 * [Modifier.weight] rather than a fixed width, so the same row fits the 268dp
 * popup and whatever width the bar gives the fixated card. A row of buttons
 * that kept its width would leave a gutter down one side of a wide panel and
 * spill out of a narrow one.
 */
@Composable
private fun RowScope.Act(
    icon: ImageVector,
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 5.dp),
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = tint,
        )
    }
}

/**
 * Wide enough for four captioned buttons in a row with room to breathe, and the
 * same order of width as the layers panel so two panels opened from the same
 * dock do not read as two different apps.
 */
private val SELECTION_PANEL_WIDTH = 268.dp
