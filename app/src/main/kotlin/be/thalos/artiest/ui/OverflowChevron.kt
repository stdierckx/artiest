package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What is not on the bar, and the promise that it is still there.
 *
 * `ToolItem`'s rule 1 is that **only controls that work appear**, and the whole
 * catalogue is built on it. The rule this composable exists for is its other
 * half: *a control the user asked for either appears on the bar or appears
 * here, and never simply stops existing.* A workspace made on a tablet and
 * opened on a phone loses cells, and the tools that were standing on them have
 * to go somewhere the user can see.
 *
 * ## Why the menu holds live controls rather than names
 *
 * The plan said the entries should be *tappable to use*, and this file knows
 * what a control is called and nothing about what it does — the same contract
 * `DockHost` has always had. So it does not try: it is handed `slotContent`
 * and calls it, exactly as a cell does, and the menu holds the real size
 * slider and the real colour swatch at their real size. Nothing about "use it"
 * had to be invented, and a control in the overflow behaves as a control.
 *
 * The count is on the face because the number is the information. One tool
 * missing is a shrug; six is a workspace that does not fit this screen, and the
 * difference should be readable without opening anything.
 */
@Composable
internal fun OverflowChevron(
    items: List<ToolItem>,
    axis: Axis,
    modifier: Modifier = Modifier,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }

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
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    CircleShape,
                )
                .clickable { open = true },
        ) {
            Text(
                items.size.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }

        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Text(
                    if (items.size == 1) "1 control does not fit this screen"
                    else "${items.size} controls do not fit this screen",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
                )
                for (item in items) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .width(Chrome.SLOT * item.cellsWide)
                                .height(Chrome.SLOT),
                        ) {
                            SlotSurface { slotContent(item, axis) }
                        }
                        Text(
                            item.label,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp, end = 8.dp),
                        )
                    }
                }
                Text(
                    "they come back when there is room for them",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
                )
            }
        }
    }
}

/** The same size as the shape button. It is furniture, not a control. */
private val HANDLE = 22.dp
