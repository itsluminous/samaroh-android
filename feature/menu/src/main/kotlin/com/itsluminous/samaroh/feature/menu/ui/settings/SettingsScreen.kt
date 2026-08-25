package com.itsluminous.samaroh.feature.menu.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.backup.BackupFrequency
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.data.ThemeMode
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold
import com.itsluminous.samaroh.feature.menu.ui.formatInstant

/** Settings screen (§4.4). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLanguagePicker: () -> Unit,
    onOpenReminderSettings: () -> Unit,
    onOpenSyncStatus: () -> Unit,
    onOpenBusinessProfile: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val consentIntent by viewModel.consentIntent.collectAsStateWithLifecycle()
    val showRemoveEventsDialog by viewModel.showRemoveEventsDialog.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.completeGoogleConsent(result.data)
            } else {
                viewModel.completeGoogleConsent(null)
            }
        }
    LaunchedEffect(consentIntent) {
        consentIntent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }

    MenuScreenScaffold(
        titleRes = R.string.settings_title,
        onBack = onBack,
        messageRes = message,
        onMessageShown = viewModel::onMessageShown,
    ) {
        // Language (§4.4: full-screen picker).
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_language_title)) },
            supportingContent = { Text(currentLanguageName()) },
            modifier = Modifier.clickable(onClick = onOpenLanguagePicker),
        )
        HorizontalDivider()

        // Theme: System / Light / Dark + dynamic color (§4.4).
        SettingsSectionHeader(R.string.settings_theme_title)
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            ThemeChip(R.string.settings_theme_system, state.device?.themeMode == ThemeMode.SYSTEM) {
                viewModel.setThemeMode(ThemeMode.SYSTEM)
            }
            ThemeChip(R.string.settings_theme_light, state.device?.themeMode == ThemeMode.LIGHT) {
                viewModel.setThemeMode(ThemeMode.LIGHT)
            }
            ThemeChip(R.string.settings_theme_dark, state.device?.themeMode == ThemeMode.DARK) {
                viewModel.setThemeMode(ThemeMode.DARK)
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme_dynamic_color)) },
            trailingContent = {
                Switch(
                    checked = state.device?.dynamicColor == true,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            },
        )
        HorizontalDivider()

        // Booking reminders (§4.4) — details on their own screen.
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_reminders_title)) },
            supportingContent = { Text(stringResource(R.string.settings_reminders_subtitle)) },
            modifier = Modifier.clickable(onClick = onOpenReminderSettings),
        )
        HorizontalDivider()

        // Google account link/unlink with connected email (§4.4).
        SettingsSectionHeader(R.string.settings_google_title)
        when (val link = state.linkState) {
            GoogleLinkState.NotConfigured ->
                ListItem(supportingContent = { Text(stringResource(R.string.settings_google_not_configured)) }, headlineContent = {})
            is GoogleLinkState.Linked -> {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_google_linked_as, link.email)) },
                    trailingContent = {
                        TextButton(onClick = viewModel::unlinkGoogle) {
                            Text(stringResource(R.string.settings_google_unlink))
                        }
                    },
                )
            }
            GoogleLinkState.NotLinked ->
                OutlinedButton(
                    onClick = { viewModel.linkGoogle(context) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_google_link))
                }
        }

        // Google Calendar sync toggle — gated by the gcal_sync permission (§4.4).
        if (state.canToggleGcalSync && state.linkState !is GoogleLinkState.NotConfigured) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_gcal_title)) },
                supportingContent = { Text(stringResource(R.string.settings_gcal_subtitle)) },
                trailingContent = {
                    Switch(
                        checked = state.gcalSyncEnabled,
                        onCheckedChange = viewModel::setGcalSyncEnabled,
                        enabled = state.linkState is GoogleLinkState.Linked || state.gcalSyncEnabled,
                    )
                },
            )
        }
        HorizontalDivider()

        // Backup — OWNER ONLY: the section is hidden for employees (§4.4).
        if (state.isOwner) {
            SettingsSectionHeader(R.string.settings_backup_title)
            Text(
                text = stringResource(R.string.settings_backup_frequency_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                BackupFrequencyChip(R.string.settings_backup_freq_daily, state.backupFrequency == BackupFrequency.DAILY) {
                    viewModel.setBackupFrequency(BackupFrequency.DAILY)
                }
                BackupFrequencyChip(R.string.settings_backup_freq_weekly, state.backupFrequency == BackupFrequency.WEEKLY) {
                    viewModel.setBackupFrequency(BackupFrequency.WEEKLY)
                }
                BackupFrequencyChip(R.string.settings_backup_freq_monthly, state.backupFrequency == BackupFrequency.MONTHLY) {
                    viewModel.setBackupFrequency(BackupFrequency.MONTHLY)
                }
                BackupFrequencyChip(R.string.settings_backup_freq_manual, state.backupFrequency == BackupFrequency.MANUAL) {
                    viewModel.setBackupFrequency(BackupFrequency.MANUAL)
                }
            }
            ListItem(
                headlineContent = {
                    val lastBackup = state.lastBackupAt
                    Text(
                        if (lastBackup != null) {
                            stringResource(R.string.settings_backup_last_backup, formatInstant(lastBackup))
                        } else {
                            stringResource(R.string.settings_backup_never)
                        },
                    )
                },
                trailingContent = {
                    TextButton(
                        onClick = viewModel::backUpNow,
                        enabled = state.linkState is GoogleLinkState.Linked,
                    ) {
                        Text(stringResource(R.string.settings_backup_backup_now))
                    }
                },
            )
            HorizontalDivider()
        }

        // Sync status (§4.4/§4.5).
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_sync_title)) },
            modifier = Modifier.clickable(onClick = onOpenSyncStatus),
        )
        HorizontalDivider()

        // Business profile editor (§4.4).
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_business_title)) },
            supportingContent = { state.businessName?.let { Text(it) } },
            modifier = Modifier.clickable(onClick = onOpenBusinessProfile),
        )
    }

    if (showRemoveEventsDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onRemoveEventsChoice(removeEvents = false) },
            title = { Text(stringResource(R.string.settings_gcal_remove_events_title)) },
            text = { Text(stringResource(R.string.settings_gcal_remove_events_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.onRemoveEventsChoice(removeEvents = true) }) {
                    Text(stringResource(R.string.settings_gcal_remove_events_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onRemoveEventsChoice(removeEvents = false) }) {
                    Text(stringResource(R.string.settings_gcal_remove_events_keep))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    @StringRes titleRes: Int,
) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ThemeChip(
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun BackupFrequencyChip(
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** The current app language rendered in its own script (never translated). */
@Composable
private fun currentLanguageName(): String {
    val currentTag =
        com.itsluminous.samaroh.core.i18n.LocaleManager
            .currentAppLocale()
    return when {
        currentTag == null -> stringResource(R.string.settings_language_system)
        currentTag.startsWith("hi") -> stringResource(R.string.settings_language_name_hi)
        else -> stringResource(R.string.settings_language_name_en)
    }
}
