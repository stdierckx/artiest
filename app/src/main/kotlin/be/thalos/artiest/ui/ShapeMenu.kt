package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What shape this toolbar is, and how to change it.
 *
 * One button per surface, visible only while arranging, and the only way into
 * every shaping gesture there is. It sits at the surface's leading end rather
 * than on top of it, because a control that covers a cell is a control you
 * cannot drop into — and arrange mode is when dropping happens.
 *
 * **Applying a preset is undoable by picking the one you had.** That is why
 * the menu ticks the current shape rather than closing on the first tap and
 * leaving you to guess: the list is the state, so backing out is one tap in the
 * same place, which is cheaper than any undo stack this would otherwise need.
 *
 * What a preset does not do is move anything that still fits.
 * [DockLayout.reshape] keeps every placement the new shape can hold and drops
 * the rest, so going Bar → L → Bar leaves the tools that were in the arm
 * exactly where they started.
 */
@Composable
internal fun ShapeButton(
    surface: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    onDraw: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = remember(surface.region, surface.dock) {
        SurfaceShape.of(surface.region, surface.dock)
    }

    Box(modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(2.dp)
                .size(HANDLE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    CircleShape,
                )
                .clickable { open = true },
        ) {
            Icon(
                glyphOf(current),
                "Change this toolbar's shape",
                Modifier.size(13.dp),
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }

        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Text(
                    "Shape",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                )
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    for (shape in SurfaceShape.entries) {
                        ShapeTarget(
                            shape = shape,
                            chosen = shape == current,
                            onClick = {
                                open = false
                                onLayout(layout.reshape(surface.id, shape.regionFor(surface.dock)))
                            },
                        )
                    }
                }

                DropdownMenuItem(
                    text = { Text("Draw the shape", fontSize = 13.sp) },
                    leadingIcon = { Icon(ToolIcons.shapeDraw, null, Modifier.size(17.dp)) },
                    onClick = { open = false; onDraw() },
                )

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text("Tidy", fontSize = 13.sp) },
                    leadingIcon = { Icon(ToolIcons.tidy, null, Modifier.size(17.dp)) },
                    enabled = !surface.isEmpty,
                    onClick = { open = false; onLayout(layout.tidy(surface.id)) },
                )
            }
        }
    }
}

/** One preset in the row, drawn as itself inside a screen. See [ToolIcons.shapeBar]. */
@Composable
private fun ShapeTarget(shape: SurfaceShape, chosen: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(2.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (chosen) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            glyphOf(shape),
            shape.label,
            Modifier.size(19.dp),
            MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A shape somebody drew has no preset glyph, so it borrows the pen's. */
private fun glyphOf(shape: SurfaceShape?): ImageVector = when (shape) {
    SurfaceShape.BAR -> ToolIcons.shapeBar
    SurfaceShape.L -> ToolIcons.shapeL
    SurfaceShape.T -> ToolIcons.shapeT
    SurfaceShape.U -> ToolIcons.shapeU
    SurfaceShape.BLOCK -> ToolIcons.shapeBlock
    null -> ToolIcons.shapeDraw
}

/** Smaller than a cell, because it is furniture and not a control. */
private val HANDLE = 22.dp
