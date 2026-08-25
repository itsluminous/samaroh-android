package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.designsystem.component.EmptyStateCompact
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold
import com.itsluminous.samaroh.feature.menu.ui.formatInstant

/** Sync status (§4.4/§4.5): pending count, per-item errors, last sync, "Sync now". */
@Composable
fun SyncStatusScreen(
    onBack: () -> Unit,
    viewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    MenuScreenScaffold(titleRes = R.string.settings_sync_title, onBack = onBack) {
        val current = status ?: return@MenuScreenScaffold
        val allClear = current.pendingCount == 0 && current.conflicts.isEmpty() && current.errors.isEmpty()

        if (allClear) {
            // Friendly all-clear state instead of a bare status line.
            EmptyStateCompact(
                icon = Icons.Filled.CloudDone,
                title = stringResource(R.string.settings_sync_all_synced),
                message = stringResource(R.string.settings_sync_all_synced_message),
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            Text(
                text = pluralStringResource(R.plurals.settings_sync_pending, current.pendingCount, current.pendingCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp).semantics { heading() },
            )
        }
        Text(
            text =
                current.lastSyncAt?.let { stringResource(R.string.settings_sync_last_sync, formatInstant(it)) }
                    ?: stringResource(R.string.settings_sync_not_synced_yet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = viewModel::syncNow,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.settings_sync_sync_now))
        }

        if (current.conflicts.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.sync_notification_conflict_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp).semantics { heading() },
            )
            for (conflict in current.conflicts) {
                val fields = conflict.overriddenFields.joinToString()
                ListItem(
                    headlineContent = { Text(conflict.title) },
                    supportingContent = {
                        Text(
                            when (conflict.resolution) {
                                ConflictResolution.REBASED ->
                                    stringResource(R.string.sync_notification_conflict_rebased, conflict.title, fields)
                                ConflictResolution.DROPPED ->
                                    stringResource(R.string.sync_notification_conflict_dropped, conflict.title, fields)
                            },
                        )
                    },
                    trailingContent =
                        if (conflict.acknowledged) {
                            null
                        } else {
                            {
                                TextButton(onClick = { viewModel.acknowledgeConflict(conflict.id) }) {
                                    Text(stringResource(R.string.common_action_close))
                                }
                            }
                        },
                )
                HorizontalDivider()
            }
        }

        if (current.errors.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.settings_sync_errors_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp).semantics { heading() },
            )
            for (error in current.errors) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_sync_error_entity, error.entityType)) },
                    supportingContent = { Text(error.message) },
                )
                HorizontalDivider()
            }
        }
    }
}
