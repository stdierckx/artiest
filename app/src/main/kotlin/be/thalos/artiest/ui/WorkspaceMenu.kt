package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which program you are in, how to be in a different one, and how to keep the
 * one you have built under a name of your own.
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
 * arranging**, for the reason the grip and the scale handle are: the workspace
 * is not a drawing control, and chrome the user is paying for in screen with
 * nothing to show for it is chrome that should not be there. Arrange mode is
 * where the furniture lives.
 *
 * ## There is nothing to save, and that is why *Save as* exists
 *
 * Every change to the arrangement is already written, to preferences at once
 * and to the workspace's own file behind it. So there is no *Save*: there is
 * nothing that could be lost by not pressing it, and a button that does what
 * has already happened teaches people to press it for luck.
 *
 * What was missing is a way to *keep* the arrangement you have as a thing with
 * a name, so that you can go on changing the one in front of you without
 * spending what you built. That is [onSaveAs]: it writes the current
 * arrangement to a **new** workspace under the name you type, and moves you
 * into it — into the copy, not the original, because the copy is the one you
 * just named and naming something is how people say *this is the one I mean*.
 *
 * ## Rename and delete, and what is protected from both
 *
 * Earlier this file argued that rename and delete were a deliberate stopping
 * point: both need a text field and a confirmation, and both are recoverable
 * through *Duplicate*. Naming makes that argument stale — a list you can add
 * named things to and never take anything out of fills up, and a name you typed
 * with a thumb is a name you will want to fix.
 *
 * Neither is offered for the three that ship. A shipped workspace is the thing
 * *Put back* restores you to, so it has to stay where it is and keep its name;
 * the way to make one yours is *Save as*, which is also the way that leaves the
 * original there for the next time. Delete asks first, and asks with the name
 * in the question, because the one unrecoverable act in this menu should not be
 * one tap away from the one that is used most.
 *
 * ## Delete is on the row, and that is the correction
 *
 * It used to be one item at the bottom of the menu that deleted *the workspace
 * you are in*, which meant throwing away a workspace you were not in took three
 * steps: switch to it, open the menu again, delete, and then switch back to
 * where you were. The tablet found what that feels like:
 *
 * > *"There is no way yet to delete a preset once made."*
 *
 * It was there, and being there is not the same as being findable. A list you
 * can add named things to needs the taking-out next to the thing being taken
 * out — so every row that can be deleted carries its own bin, and the bottom
 * item is gone. Shipped rows carry none, which is also how the list now *says*
 * which ones cannot be deleted rather than leaving you to find out.
 */
@Composable
internal fun WorkspaceMenu(
    current: Workspace,
    entries: List<WorkspaceFiles.Entry>,
    isShipped: (String) -> Boolean,
    onSwitch: (String) -> Unit,
    /** Keep what is on screen as a new workspace under this name, and go there. */
    onSaveAs: (String) -> Unit,
    /** The same workspace under a different name. Never offered for a shipped one. */
    onRename: (String) -> Unit,
    /**
     * Throw this workspace away, by id. Never offered for a shipped one.
     *
     * By id and not "the current one", because the bin is on the row now and
     * the row is usually not the one you are in.
     */
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // Which row's bin was tapped, and therefore what the confirmation asks
    // about. Null is "nothing is being deleted"; the entry is carried rather
    // than the id, because the question has to have the name in it.
    var deleting by remember { mutableStateOf<WorkspaceFiles.Entry?>(null) }
    val shipped = isShipped(current.id)

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
                        trailingIcon = {
                            // Its own clickable inside the row's, which is what
                            // makes the bin a second target rather than a
                            // decoration on the first. A shipped row has none
                            // at all: an icon that is drawn and does nothing is
                            // worse than an icon that is not drawn.
                            if (!isShipped(entry.id)) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .clickable { open = false; deleting = entry },
                                ) {
                                    Icon(
                                        ToolIcons.trash,
                                        "Delete ${entry.name}",
                                        Modifier.size(15.dp),
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = { open = false; onSwitch(entry.id) },
                    )
                }

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text("Save as…", fontSize = 13.sp) },
                    leadingIcon = { Icon(ToolIcons.plus, null, Modifier.size(17.dp)) },
                    onClick = { open = false; saving = true },
                )
                if (!shipped) {
                    DropdownMenuItem(
                        text = { Text("Rename ${current.name}…", fontSize = 13.sp) },
                        leadingIcon = { Icon(ToolIcons.workspace, null, Modifier.size(17.dp)) },
                        onClick = { open = false; renaming = true },
                    )
                }
                // No Delete down here any more. It is on the row; see the
                // header. *Put back* stays, because it is about the workspace
                // you are in and nothing else.
                if (shipped) {
                    DropdownMenuItem(
                        text = { Text("Put ${current.name} back", fontSize = 13.sp) },
                        leadingIcon = { Icon(ToolIcons.tidy, null, Modifier.size(17.dp)) },
                        onClick = { open = false; onReset() },
                    )
                }
            }
        }
    }

    if (saving) {
        NameDialog(
            title = "Save this arrangement as",
            confirm = "Save",
            // Offered rather than empty, because the usual answer is "the one I
            // am in, but mine". Selected, so one keystroke replaces it.
            initial = Workspace.copyName(current.name, entries.map { it.name }),
            onDismiss = { saving = false },
            onConfirm = { saving = false; onSaveAs(it) },
        )
    }

    if (renaming) {
        NameDialog(
            title = "Rename this workspace",
            confirm = "Rename",
            initial = current.name,
            onDismiss = { renaming = false },
            onConfirm = { renaming = false; onRename(it) },
        )
    }

    deleting?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${doomed.name}?", fontSize = 16.sp) },
            text = {
                Text(
                    if (doomed.id == current.id) {
                        "The arrangement goes with it, and you land back in " +
                            "the one the app starts with. Your drawing does not."
                    } else {
                        "The arrangement goes with it. The one you are in, " +
                            "and your drawing, are not touched."
                    },
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(doomed.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep it") }
            },
        )
    }
}
