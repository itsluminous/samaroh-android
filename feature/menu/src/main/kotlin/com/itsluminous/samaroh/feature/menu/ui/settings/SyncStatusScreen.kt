package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.designsystem.component.EmptyStateCompact
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold
import com.itsluminous.samaroh.feature.menu.ui.formatInstant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sync status (§4.4/§4.5): human-readable pending list ("Add booking — Sharma"),
 * per-item errors, conflict log, last sync, "Sync now". Technical row detail
 * (table · op · id) stays available on tap/expand (ADR-022).
 */
@Composable
fun SyncStatusScreen(
    onBack: () -> Unit,
    viewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    MenuScreenScaffold(titleRes = R.string.settings_sync_title, onBack = onBack) {
        val current = status ?: return@MenuScreenScaffold
        val allClear = current.pending.isEmpty() && current.conflicts.isEmpty() && current.errors.isEmpty()

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

        if (current.pending.isNotEmpty()) {
            var expandedIds by remember { mutableStateOf(emptySet<Long>()) }
            HorizontalDivider()
            for (row in current.pending) {
                val expanded = row.outboxId in expandedIds
                ListItem(
                    modifier =
                        Modifier.clickable {
                            expandedIds = if (expanded) expandedIds - row.outboxId else expandedIds + row.outboxId
                        },
                    headlineContent = { Text(row.display.line()) },
                    supportingContent =
                        if (expanded) {
                            {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.settings_sync_technical_detail,
                                            row.entityType,
                                            row.operationWire,
                                            row.entityId,
                                        ),
                                    )
                                    Text(stringResource(R.string.settings_sync_queued_at, formatInstant(row.queuedAt)))
                                }
                            }
                        } else {
                            null
                        },
                    trailingContent = {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    },
                )
                HorizontalDivider()
            }
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
            var expandedErrorIds by remember { mutableStateOf(emptySet<Long>()) }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.settings_sync_errors_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp).semantics { heading() },
            )
            for (error in current.errors) {
                val expanded = error.outboxId in expandedErrorIds
                ListItem(
                    modifier =
                        Modifier.clickable {
                            expandedErrorIds =
                                if (expanded) expandedErrorIds - error.outboxId else expandedErrorIds + error.outboxId
                        },
                    headlineContent = { Text(error.display.line()) },
                    supportingContent = {
                        Column {
                            // Error rows keep their sanitized message visible at all times.
                            Text(error.message)
                            if (expanded) {
                                Text(
                                    stringResource(
                                        R.string.settings_sync_technical_detail,
                                        error.entityType,
                                        error.operationWire,
                                        error.entityId,
                                    ),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Builds the localized "Add booking — Sharma" line from a resolved [SyncEntryDisplay]. */
@Composable
private fun SyncEntryDisplay.line(): String {
    val phrase = stringResource(R.string.settings_sync_op_phrase, stringResource(verbRes), stringResource(nounRes))
    val detailText =
        when (val d = detail) {
            is SyncEntryDetail.Text -> d.value
            is SyncEntryDetail.Date -> formatSyncDate(d.date)
            is SyncEntryDetail.Amount -> AmountFormatter.format(d.amountPaise)
            is SyncEntryDetail.AmountForName ->
                stringResource(R.string.settings_sync_payment_detail, AmountFormatter.format(d.amountPaise), d.name)
            SyncEntryDetail.None -> null
        }
    return detailText?.let { stringResource(R.string.settings_sync_op_line, phrase, it) } ?: phrase
}

/** Locale-formatted date identifier, e.g. "28 Jan 2027" / "28 जन॰ 2027". */
@Composable
private fun formatSyncDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}
