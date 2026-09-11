package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which program you are in, and how to be in a different one.
 *
 * ## Why it is a name and not a settings screen
 *
 * A workspace is a *personality*, not a mode: the same drawing, the same
 * document, the same everything, with a different set of tools in front of it.
 * The workspace plan made that a stop condition — *if switching takes long
 * enough to see, it is a mode change rather than a personality change, and it
 * will not be used* — so this is a name you tap and a list you pick from, with
 * no dialog, no animation and no confirmation between the two.
 *
 * It lives beside the arrange affordance and is visible **only while
 * arranging**, for the reason the grip and the X are: the workspace is not a
 * drawing control, and chrome the user is paying for in screen with nothing to
 * show for it is chrome that should not be there. Arrange mode is where the
 * furniture lives.
 *
 * ## What it offers, and what it does not
 *
 * Switch, duplicate, and put a shipped one back. It does not rename or delete,
 * and that is a deliberate stopping point rather than an oversight: both need a
 * text field and a confirmation, both are recoverable through *Duplicate* and
 * the file, and *"build U1–U10 and stop"* is the trap this feature is most
 * likely to fall into. See `docs/ui-expansion-plan.md`, the wardrobe.
 */
@Composable
internal fun WorkspaceMenu(
    current: Workspace,
    entries: List<WorkspaceFiles.Entry>,
    isShipped: (String) -> Boolean,
    onSwitch: (String) -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA),
                    RoundedCornerShape(14.dp),
                )
                .clickable { open = true }
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                ToolIcons.workspace,
                "Change workspace",
                Modifier.size(15.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                current.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Text(
                    "Workspace",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                )
                for (entry in entries) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(entry.name, fontSize = 13.sp)
                                if (entry.description.isNotEmpty()) {
                                    Text(
                                        entry.description,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            if (entry.id == current.id) {
                                Icon(ToolIcons.check, null, Modifier.size(16.dp))
                            }
                        },
                        onClick = { open = false; onSwitch(entry.id) },
                    )
                }

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text("Duplicate ${current.name}", fontSize = 13.sp) },
                    leadingIcon = { Icon(ToolIcons.plus, null, Modifier.size(17.dp)) },
                    onClick = { open = false; onDuplicate() },
                )
                if (isShipped(current.id)) {
                    DropdownMenuItem(
                        text = { Text("Put ${current.name} back", fontSize = 13.sp) },
                        leadingIcon = { Icon(ToolIcons.tidy, null, Modifier.size(17.dp)) },
                        onClick = { open = false; onReset() },
                    )
                }
            }
        }
    }
}
