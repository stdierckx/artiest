package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.canvas.MarqueeShape
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp

/**
 * The selection panel, as a button that opens it.
 *
 * Ten controls — three shapes, four combine modes, three commands — which is a
 * toolbar's worth on its own, so it is a panel for the reason `LayersPanel` and
 * the colour wheel are: what a bar can usefully show about a selection at a
 * glance is nothing, so it shows a way in.
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
 */
@Composable
fun SelectionButton(
    shape: MarqueeShape,
    mode: SelectMode,
    selecting: Boolean,
    hasSelection: Boolean,
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onSelecting: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
        IconToolButton(
            icon = ToolIcons.marquee,
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
                onShape = {
                    onShape(it)
                    onSelecting(true)
                },
                onMode = onMode,
                onOp = onOp,
                onDismiss = { open = false },
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
    onShape: (MarqueeShape) -> Unit,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }

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
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Header("Selection", if (selecting) "pen selects" else "pen draws")

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

                Spacer(Modifier.height(12.dp))
                Label("What a gesture does")
                Spacer(Modifier.height(6.dp))
                // Written out rather than drawn as four more glyphs. The
                // difference between "add" and "intersect" is a sentence, and
                // four boxes with corners shaded differently is a puzzle -- the
                // one place in this app where a word beats a picture.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Word("New", mode == SelectMode.NEW) { onMode(SelectMode.NEW) }
                    Word("Add", mode == SelectMode.ADD) { onMode(SelectMode.ADD) }
                    Word("Take", mode == SelectMode.SUBTRACT) { onMode(SelectMode.SUBTRACT) }
                    Word("Both", mode == SelectMode.INTERSECT) { onMode(SelectMode.INTERSECT) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hold the pen's side button to take away.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Word("All", false) { onOp(SelectOp.All) }
                    Word("None", false, enabled = hasSelection) { onOp(SelectOp.None) }
                    Word("Flip", false, enabled = hasSelection) { onOp(SelectOp.Invert) }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, note: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    ) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Choice(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 78.dp, height = 44.dp)
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

@Composable
private fun Word(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 58.dp, height = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                selected -> colors.onPrimaryContainer
                else -> colors.onSurfaceVariant
            },
        )
    }
}

/**
 * Wide enough for three shape buttons in a row with room to breathe, and the
 * same order of width as the layers panel so two panels opened from the same
 * dock do not read as two different apps.
 */
private val SELECTION_PANEL_WIDTH = 268.dp
