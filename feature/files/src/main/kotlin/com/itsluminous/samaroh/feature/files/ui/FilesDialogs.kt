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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.WideDialog
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.feature.files.AccessEditorState
import com.itsluminous.samaroh.feature.files.domain.FileNameError
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
    NameDialog(
        titleRes = titleRes,
        labelRes = R.string.files_folder_name_label,
        initialName = initialName,
        errorText = { name -> validate(name)?.let { folderNameErrorText(it) } },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Rename-file dialog (ADR-090): prefilled with the current name incl. extension; no sibling rule (D12). */
@Composable
internal fun FileNameDialog(
    initialName: String,
    validate: (String) -> FileNameError?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NameDialog(
        titleRes = R.string.files_action_rename_file,
        labelRes = R.string.files_file_name_label,
        initialName = initialName,
        errorText = { name -> validate(name)?.let { fileNameErrorText(it) } },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun fileNameErrorText(error: FileNameError): String =
    stringResource(
        when (error) {
            FileNameError.REQUIRED -> R.string.files_file_name_required
            FileNameError.INVALID -> R.string.files_file_name_invalid
        },
    )

/** One-field name dialog shared by folder create/rename and file rename; [errorText] null = valid. */
@Composable
private fun NameDialog(
    titleRes: Int,
    labelRes: Int,
    initialName: String,
    errorText: @Composable (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val error = errorText(name)
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
                label = { Text(stringResource(labelRes)) },
                singleLine = true,
                isError = touched && error != null,
                supportingText =
                    if (touched && error != null) {
                        { Text(error) }
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
 * Destination folder picker (share sheet → Save to Files, design D18; Move to…, ADR-090):
 * a LAZY tree — the top level (`All files`, selectable) and the ROOT folders only; rows
 * with subfolders carry an expand chevron and reveal their children indented one level
 * per depth (A–Z per level). Rendered in the full-width [WideDialog] with compact
 * `body`-sized rows. Restricted folders the member cannot open never reached this device
 * (RLS), so everything listed is a valid destination.
 *
 * "New folder" (ADR-087): a row at the bottom — permission-HIDDEN behind the effective
 * `files.manage_folders` ([canCreateFolder]) and the depth cap under the CURRENT
 * selection — opens the standard name dialog; the created folder becomes the selected
 * destination. Any other choose-a-folder picker must reuse this dialog for parity.
 *
 * @param initialSelection preselected destination (null = top level); its ancestors start expanded.
 * @param excludeFolderId move flow: the folder being moved — it and its subtree are hidden.
 * @param selectionHintRes optional helper line under the list taking the selected path
 *   (`files.move.selected_hint` for the move flow; web parity).
 * @param selectionError inline error for the current selection (null = confirm enabled) —
 *   the move flow's same-place / cycle / depth / duplicate steering.
 * @param validateNewFolderName live sibling validation under the given parent.
 * @param onCreateFolder creates the folder under the given parent and returns its id
 *   (null when refused) — the picker selects it.
 */
@Composable
fun FolderPickerDialog(
    folders: List<Folder>,
    onPick: (folderId: String?) -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = R.string.files_share_target_pick_folder_title,
    confirmRes: Int = R.string.common_action_save,
    initialSelection: String? = null,
    excludeFolderId: String? = null,
    selectionHintRes: Int? = null,
    selectionError: @Composable (String?) -> String? = { null },
    canCreateFolder: Boolean = false,
    validateNewFolderName: (name: String, parentId: String?) -> FolderNameError? = { _, _ -> null },
    onCreateFolder: (name: String, parentId: String?) -> String? = { _, _ -> null },
) {
    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable(saver = expandedSaver) { mutableStateOf(FilesTree.ancestorIds(initialSelection, folders)) }
    val rootLabel = stringResource(R.string.files_home_root_label)
    val rows = remember(folders, expanded, excludeFolderId) { FilesTree.pickerRows(folders, expanded, excludeSubtreeOf = excludeFolderId) }
    val error = selectionError(selected)
    // Permission-hidden (ADR-038) + depth cap (design D13) — evaluated for the selection.
    val showNewFolder = canCreateFolder && FilesTree.canCreateSubfolder(selected, folders)
    WideDialog(
        title = stringResource(titleRes),
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
            TextButton(onClick = { onPick(selected) }, enabled = error == null) { Text(stringResource(confirmRes)) }
        },
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            PickerRowItem(
                label = rootLabel,
                depth = 0,
                selected = selected == null,
                hasChildren = false,
                expanded = false,
                onToggleExpand = {},
                onClick = { selected = null },
            )
            rows.forEach { row ->
                PickerRowItem(
                    label = row.folder.name,
                    depth = row.depth,
                    selected = selected == row.folder.id,
                    hasChildren = row.hasChildren,
                    expanded = row.expanded,
                    onToggleExpand = { expanded = if (row.folder.id in expanded) expanded - row.folder.id else expanded + row.folder.id },
                    onClick = { selected = row.folder.id },
                )
            }
            if (showNewFolder) {
                NewFolderRow(onClick = { creating = true })
            }
        }
        if (selectionHintRes != null) {
            Text(
                stringResource(
                    selectionHintRes,
                    FilesTree.pathLabel(selected, folders, rootLabel, stringResource(R.string.files_breadcrumb_separator)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (creating) {
        val parentId = selected
        FolderNameDialog(
            titleRes = R.string.files_action_new_folder,
            initialName = "",
            validate = { validateNewFolderName(it, parentId) },
            onConfirm = { name ->
                creating = false
                onCreateFolder(name, parentId)?.let { created ->
                    selected = created
                    parentId?.let { expanded = expanded + it }
                }
            },
            onDismiss = { creating = false },
        )
    }
}

/** `rememberSaveable` support for the expanded-id set (saved as a String list). */
private val expandedSaver =
    listSaver<MutableState<Set<String>>, String>(
        save = { it.value.toList() },
        restore = { mutableStateOf(it.toSet()) },
    )

/** The picker's "New folder" affordance: creates a subfolder under the selected row. */
@Composable
private fun NewFolderRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
    ) {
        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.files_action_new_folder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** One compact lazy-tree row: indent → radio → folder icon → name → (chevron when it has children). */
@Composable
private fun PickerRowItem(
    label: String,
    depth: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick)
                .padding(start = (depth * INDENT_DP).dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            Icons.Filled.Folder,
            contentDescription = stringResource(R.string.files_folder_icon_a11y),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (hasChildren) {
            ExplainableIcon(
                icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                explanation = stringResource(if (expanded) R.string.files_picker_collapse else R.string.files_picker_expand, label),
                onClick = onToggleExpand,
            )
        }
    }
}

private const val INDENT_DP = 20
