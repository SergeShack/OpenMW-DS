package org.openmw.ui.page.mod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmw.R
import org.openmw.ui.theme.MwBone
import org.openmw.ui.theme.MwBronzeDark
import org.openmw.ui.theme.MwBronzeLight
import org.openmw.utils.MToast
import org.openmw.utils.ModProfiles

private enum class ProfileDialog { NONE, NEW, RENAME, DELETE_PICK, DELETE_CONFIRM }

/**
 * The mod profile picker: a button with the active profile's name that opens a list of profiles,
 * plus New, Rename and Delete. See [ModProfiles] for what a profile is.
 *
 * [onChanged] is called after the live `openmw.cfg` may have changed, so the caller can re-read
 * its mod list. [compact] draws it as a small text button for a heading row, where a full-width
 * button would take a line the list needs on a short screen.
 */
@Composable
fun ModProfileSelector(
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ModProfiles.State?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(ProfileDialog.NONE) }
    var deleteTarget by remember { mutableStateOf("") }

    suspend fun reload() {
        state = withContext(Dispatchers.IO) { ModProfiles.state() }
    }
    LaunchedEffect(Unit) { reload() }

    // Runs a file action off the main thread, then refreshes the list and tells the caller.
    fun act(action: () -> Boolean, failMessage: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action() }
            if (!ok) MToast(failMessage)
            reload()
            onChanged()
        }
    }

    val current = state ?: return
    val switchFailed = stringResource(R.string.mod_profile_switch_failed)
    val saveFailed = stringResource(R.string.failed_to_save_openmw_config)

    Box(modifier = modifier) {
        if (compact) {
            // A text button that fits in a heading row, so it takes no height of its own.
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = stringResource(R.string.mod_profile_label_short) + current.active,
                    color = MwBronzeLight,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MwBronzeLight)
            }
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                border = BorderStroke(1.dp, MwBronzeDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.mod_profile_label) + current.active,
                    color = MwBone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MwBronzeLight)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            current.names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = {
                        if (name == current.active) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        if (name != current.active) {
                            act({ ModProfiles.switchTo(name) }, switchFailed)
                        }
                    }
                )
            }
            HorizontalDivider(color = MwBronzeDark)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mod_profile_new)) },
                onClick = { expanded = false; dialog = ProfileDialog.NEW }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mod_profile_rename)) },
                onClick = { expanded = false; dialog = ProfileDialog.RENAME }
            )
            if (current.names.size > 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mod_profile_delete)) },
                    onClick = { expanded = false; dialog = ProfileDialog.DELETE_PICK }
                )
            }
        }
    }

    when (dialog) {
        ProfileDialog.NEW -> ProfileNameDialog(
            title = stringResource(R.string.mod_profile_new_title),
            hint = stringResource(R.string.mod_profile_new_tip),
            initial = "",
            onDismiss = { dialog = ProfileDialog.NONE },
            onConfirm = { name ->
                dialog = ProfileDialog.NONE
                act({ ModProfiles.create(name) }, saveFailed)
            }
        )
        ProfileDialog.RENAME -> ProfileNameDialog(
            title = stringResource(R.string.mod_profile_rename_title),
            hint = null,
            initial = current.active,
            onDismiss = { dialog = ProfileDialog.NONE },
            onConfirm = { name ->
                dialog = ProfileDialog.NONE
                act({ ModProfiles.rename(current.active, name) }, saveFailed)
            }
        )
        ProfileDialog.DELETE_PICK -> AlertDialog(
            onDismissRequest = { dialog = ProfileDialog.NONE },
            title = { Text(stringResource(R.string.mod_profile_delete_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.mod_profile_delete_tip),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    current.names.filter { it != current.active }.forEach { name ->
                        TextButton(
                            onClick = { deleteTarget = name; dialog = ProfileDialog.DELETE_CONFIRM },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dialog = ProfileDialog.NONE }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
        ProfileDialog.DELETE_CONFIRM -> AlertDialog(
            onDismissRequest = { dialog = ProfileDialog.NONE },
            title = { Text(stringResource(R.string.mod_profile_delete_confirm, deleteTarget)) },
            text = { Text(stringResource(R.string.mod_profile_delete_confirm_tip)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = ProfileDialog.NONE
                    val name = deleteTarget
                    act({ ModProfiles.delete(name) }, saveFailed)
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = ProfileDialog.NONE }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
        ProfileDialog.NONE -> Unit
    }
}

/** Asks for a profile name and shows why it cannot be used, if it cannot. */
@Composable
private fun ProfileNameDialog(
    title: String,
    hint: String?,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    // "" until the first check is done: no message, but Confirm stays off.
    var problem by remember { mutableStateOf<String?>("") }
    // Checked off the main thread, since it lists the profile folder.
    LaunchedEffect(name) {
        problem = if (name.trim() == initial && initial.isNotEmpty()) {
            "" // unchanged: nothing to say, but nothing to do either
        } else if (name.trim().equals(initial, ignoreCase = true) && initial.isNotEmpty()) {
            null // only the case changes
        } else {
            withContext(Dispatchers.IO) { ModProfiles.nameProblem(name) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ModProfiles.MAX_NAME_LENGTH) },
                    singleLine = true,
                    // The on-screen keyboard can cover the Confirm button, so Done confirms too.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (problem == null) onConfirm(name.trim())
                    }),
                    isError = !problem.isNullOrEmpty() && name.isNotEmpty(),
                    supportingText = {
                        val p = problem
                        if (!p.isNullOrEmpty() && name.isNotEmpty()) Text(p)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onConfirm(name.trim()) }
            ) { Text(stringResource(R.string.btn_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
