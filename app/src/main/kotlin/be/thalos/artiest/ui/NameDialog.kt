package be.thalos.artiest.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp

/**
 * Ask for one name.
 *
 * Three details, all of them from watching somebody name something on a tablet
 * with a pen in their hand:
 *
 * - **The field takes focus by itself**, so the keyboard is already there. A
 *   dialog that needs a tap to wake up is a dialog where the first tap is
 *   wasted and the second one is in the wrong place.
 * - **The suggested name arrives selected**, so typing replaces it and the
 *   caret does not have to be put anywhere. Somebody who wanted the suggestion
 *   presses the button; somebody who did not just writes.
 * - **Done on the keyboard confirms**, because reaching past an open keyboard
 *   for a button is the reason half-typed names get abandoned.
 *
 * A blank name cannot be confirmed. It is a trim rather than a validator: the
 * only wrong name is no name, and everything else — punctuation, accents,
 * somebody's alphabet — is handled where it matters, in [Workspace.slug].
 */
@Composable
internal fun NameDialog(
    title: String,
    confirm: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotEmpty()) onConfirm(name) },
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotEmpty(), onClick = { onConfirm(name) }) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
