package com.itsluminous.samaroh.feature.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.feature.files.AccessEditorState
import com.itsluminous.samaroh.feature.files.domain.FilesTree
import com.itsluminous.samaroh.feature.files.domain.FolderNameError

/** New-folder / rename dialog: one text field, live validation against LIVE siblings (design §6). */
@Composable
internal fun FolderNameDialog(
    titleRes: Int,
    initialName: String,
    validate: (String) -> FolderNameError?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val error = validate(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    touched = true
                },
                label = { Text(stringResource(R.string.files_folder_name_label)) },
                singleLine = true,
                isError = touched && error != null,
                supportingText =
                    if (touched && error != null) {
                        { Text(folderNameErrorText(error)) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    touched = true
                    if (error == null) onConfirm(name.trim())
                },
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
private fun folderNameErrorText(error: FolderNameError): String =
    stringResource(
        when (error) {
            FolderNameError.REQUIRED -> R.string.files_folder_name_required
            FolderNameError.INVALID -> R.string.files_folder_name_invalid
            FolderNameError.DUPLICATE -> R.string.files_folder_duplicate
        },
    )

/** Delete-folder confirmation: the recursive live count drives the body (design §6). */
@Composable
internal fun DeleteFolderDialog(
    subtreeCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_folder_delete_confirm_title)) },
        text = {
            Text(
                if (subtreeCount > 0) {
                    pluralStringResource(R.plurals.files_folder_delete_confirm_message, subtreeCount, subtreeCount)
                } else {
                    stringResource(R.string.files_folder_delete_confirm_message_empty)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
internal fun DeleteFileDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_file_delete_confirm_title)) },
        text = { Text(stringResource(R.string.files_file_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

/** Connect Google Drive prompt for unlinked uploaders (design D8). */
@Composable
internal fun LinkGoogleDialog(
    onConnect: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.files_upload_link_google_title)) },
        text = { Text(stringResource(R.string.files_upload_link_google_message)) },
        confirmButton = {
            TextButton(onClick = onConnect) { Text(stringResource(R.string.files_upload_link_google_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.files_upload_link_google_later)) }
        },
    )
}

/** Owner-only restricted-folder allow-list editor (design D6/§6). */
@Composable
internal fun AccessEditorDialog(
    state: AccessEditorState,
    onRestrictedChange: (Boolean) -> Unit,
    onToggleMember: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_access_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AccessRadioRow(
                    selected = !state.restricted,
                    label = stringResource(R.string.files_access_everyone),
                    onClick = { onRestrictedChange(false) },
                )
                AccessRadioRow(
                    selected = state.restricted,
                    label = stringResource(R.string.files_access_only_selected),
                    onClick = { onRestrictedChange(true) },
                )
                if (state.restricted) {
                    if (state.members.isEmpty()) {
                        Text(
                            stringResource(R.string.files_access_no_members),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    state.members.forEach { member ->
                        val checked = member.id in state.selectedMemberIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleMember(member.id) }
                                    .padding(vertical = 4.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggleMember(member.id) })
                            Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Text(
                    stringResource(R.string.files_access_subfolders_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.files_access_owner_always),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
private fun AccessRadioRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Destination folder picker (share sheet → Save to Files, design D18): the whole
 * accessible tree indented by depth, top level preselected. Restricted folders the
 * member cannot open never reached this device (RLS), so everything listed is valid.
 *
 * "New folder" (ADR-087): a row at the bottom — permission-HIDDEN behind the effective
 * `files.manage_folders` ([canCreateFolder]) and the depth cap under the CURRENT
 * selection — opens the standard name dialog; the created folder becomes the selected
 * destination. Any other choose-a-folder picker must reuse this dialog for parity.
 *
 * @param validateNewFolderName live sibling validation under the given parent.
 * @param onCreateFolder creates the folder under the given parent and returns its id
 *   (null when refused) — the picker selects it.
 */
@Composable
fun FolderPickerDialog(
    folders: List<Folder>,
    onPick: (folderId: String?) -> Unit,
    onDismiss: () -> Unit,
    canCreateFolder: Boolean = false,
    validateNewFolderName: (name: String, parentId: String?) -> FolderNameError? = { _, _ -> null },
    onCreateFolder: (name: String, parentId: String?) -> String? = { _, _ -> null },
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val rootLabel = stringResource(R.string.files_home_root_label)
    val ordered = remember(folders) { flattenTree(folders) }
    // Permission-hidden (ADR-038) + depth cap (design D13) — evaluated for the selection.
    val showNewFolder = canCreateFolder && FilesTree.canCreateSubfolder(selected, folders)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_share_target_pick_folder_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PickerRow(label = rootLabel, depth = 0, selected = selected == null, onClick = { selected = null })
                ordered.forEach { (folder, depth) ->
                    PickerRow(label = folder.name, depth = depth, selected = selected == folder.id, onClick = { selected = folder.id })
                }
                if (showNewFolder) {
                    NewFolderRow(onClick = { creating = true })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
    if (creating) {
        val parentId = selected
        FolderNameDialog(
            titleRes = R.string.files_action_new_folder,
            initialName = "",
            validate = { validateNewFolderName(it, parentId) },
            onConfirm = { name ->
                creating = false
                onCreateFolder(name, parentId)?.let { selected = it }
            },
            onDismiss = { creating = false },
        )
    }
}

/** The picker's "New folder" affordance: creates a subfolder under the selected row. */
@Composable
private fun NewFolderRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.files_action_new_folder), color = MaterialTheme.colorScheme.primary) },
        leadingContent = {
            Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PickerRow(
    label: String,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Row {
                androidx.compose.foundation.layout
                    .Spacer(Modifier.padding(start = (depth * 16).dp))
                RadioButton(selected = selected, onClick = onClick)
                Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.files_folder_icon_a11y))
            }
        },
        modifier = Modifier.selectable(selected = selected, onClick = onClick),
    )
}

/** Depth-first (A–Z at each level) flattening of the folder tree with each row's depth. */
internal fun flattenTree(folders: List<Folder>): List<Pair<Folder, Int>> {
    val childrenOf = folders.groupBy { it.parentId }.mapValues { (_, v) -> v.sortedBy { it.name.lowercase() } }
    val out = mutableListOf<Pair<Folder, Int>>()

    fun visit(
        parentId: String?,
        depth: Int,
    ) {
        if (depth > FileItem.MAX_FOLDER_DEPTH * 2) return
        childrenOf[parentId].orEmpty().forEach {
            out += it to depth + 1
            visit(it.id, depth + 1)
        }
    }
    visit(null, 0)
    return out
}
