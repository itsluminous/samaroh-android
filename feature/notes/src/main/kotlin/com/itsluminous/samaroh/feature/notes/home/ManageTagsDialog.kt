package com.itsluminous.samaroh.feature.notes.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.NoteTag

/**
 * Manage-tags dialog (feedback batch): opened from the drawer's Tags header edit icon.
 * Each live tag gets an inline RENAME (duplicate-name validation against the live
 * tags) and a DELETE that confirms with the tag's linked-note count before
 * tombstoning the tag plus its links — the notes themselves stay untouched.
 */
@Composable
internal fun ManageTagsDialog(
    manage: ManageTagsState,
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissManageTags,
        title = { Text(stringResource(R.string.notes_tags_manage_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                state.tags.forEach { tag ->
                    if (manage.renameTagId == tag.id) {
                        RenameRow(manage, viewModel)
                    } else {
                        TagRow(tag, viewModel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissManageTags) {
                Text(stringResource(R.string.common_action_close))
            }
        },
    )
    manage.confirmDeleteTagId?.let { tagId ->
        state.tags.firstOrNull { it.id == tagId }?.let { tag ->
            DeleteTagConfirmDialog(
                tag = tag,
                linkedCount = state.tagLinkCounts[tagId] ?: 0,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun TagRow(
    tag: NoteTag,
    viewModel: NotesHomeViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        ExplainableIcon(
            icon = Icons.Filled.Edit,
            explanationRes = R.string.notes_tags_rename,
            onClick = { viewModel.startRenameTag(tag.id) },
        )
        ExplainableIcon(
            icon = Icons.Filled.Delete,
            explanationRes = R.string.notes_tags_delete,
            onClick = { viewModel.requestDeleteTag(tag.id) },
        )
    }
}

@Composable
private fun RenameRow(
    manage: ManageTagsState,
    viewModel: NotesHomeViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = manage.renameValue,
            onValueChange = viewModel::setRenameValue,
            singleLine = true,
            isError = manage.renameDuplicate,
            supportingText =
                if (manage.renameDuplicate) {
                    { Text(stringResource(R.string.notes_tags_rename_duplicate)) }
                } else {
                    null
                },
            modifier = Modifier.weight(1f),
        )
        ExplainableIcon(
            icon = Icons.Filled.Check,
            explanationRes = R.string.common_action_save,
            onClick = viewModel::confirmRenameTag,
        )
        ExplainableIcon(
            icon = Icons.Filled.Close,
            explanationRes = R.string.common_action_cancel,
            onClick = viewModel::cancelRenameTag,
        )
    }
}

@Composable
private fun DeleteTagConfirmDialog(
    tag: NoteTag,
    linkedCount: Int,
    viewModel: NotesHomeViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDeleteTag,
        title = { Text(stringResource(R.string.notes_tags_delete_title, tag.name)) },
        text = { Text(stringResource(R.string.notes_tags_delete_message, linkedCount.toString())) },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDeleteTag) {
                Text(stringResource(R.string.common_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDeleteTag) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}
