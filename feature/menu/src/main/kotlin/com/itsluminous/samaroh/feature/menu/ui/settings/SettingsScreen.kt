package com.itsluminous.samaroh.feature.menu.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.CalendarDayCrossfade
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.backup.BackupFrequency
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
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

        // Booking form fields (ADR-020): choose which optional fields the form shows.
        SettingsSectionHeader(R.string.settings_booking_form_title)
        Text(
            text = stringResource(R.string.settings_booking_form_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_booking_form_show_deposit)) },
            trailingContent = {
                Switch(
                    checked = state.device?.bookingFormShowDeposit == true,
                    onCheckedChange = viewModel::setBookingFormShowDeposit,
                )
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_booking_form_show_source)) },
            trailingContent = {
                Switch(
                    checked = state.device?.bookingFormShowSource != false,
                    onCheckedChange = viewModel::setBookingFormShowSource,
                )
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_booking_form_show_times)) },
            trailingContent = {
                Switch(
                    checked = state.device?.bookingFormShowTimes != false,
                    onCheckedChange = viewModel::setBookingFormShowTimes,
                )
            },
        )
        HorizontalDivider()

        // Booking calendar (owner feedback): the day-cell icon-watermark opacity is
        // configurable; the sample cell next to the slider previews the level live.
        SettingsSectionHeader(R.string.settings_booking_calendar_title)
        Text(
            text = stringResource(R.string.settings_booking_calendar_icon_alpha_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        IconAlphaSliderRow(
            persisted =
                state.device?.bookingCalendarIconAlpha
                    ?: SettingsPreferencesDataSource.DEFAULT_CALENDAR_ICON_ALPHA,
            onCommit = viewModel::setBookingCalendarIconAlpha,
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

/**
 * Label + slider for the calendar icon-watermark opacity. The value shown while
 * dragging is local; it persists on release and clears once the persisted value
 * catches up, so external changes still flow in without flicker.
 */
@Composable
private fun IconAlphaSliderRow(
    persisted: Float,
    onCommit: (Float) -> Unit,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(persisted) { dragValue = null }
    val shown = dragValue ?: persisted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SampleDayCell(alpha = shown)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = stringResource(R.string.settings_booking_calendar_icon_alpha_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = shown,
                onValueChange = { dragValue = it },
                onValueChangeFinished = { dragValue?.let(onCommit) },
                valueRange = SettingsPreferencesDataSource.CALENDAR_ICON_ALPHA_MIN..SettingsPreferencesDataSource.CALENDAR_ICON_ALPHA_MAX,
            )
        }
    }
}

/**
 * A sample booked day cell mirroring the booking calendar's DayCell, previewing the
 * chosen slider value live as a date ↔ icon CROSSFADE ([CalendarDayCrossfade]): the
 * icon strengthens with the slider while the date number stays fully opaque up to
 * the midpoint, then fades out — at the far right only the icon shows.
 */
@Composable
private fun SampleDayCell(alpha: Float) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = PREVIEW_WATERMARK_ICON,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.alpha(CalendarDayCrossfade.iconAlpha(alpha)),
        )
        Text(
            text = PREVIEW_DAY_NUMBER.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.alpha(CalendarDayCrossfade.dateAlpha(alpha)),
        )
    }
}

/** Locale-neutral glyphs for the preview cell: a wedding icon and a sample date. */
private const val PREVIEW_WATERMARK_ICON = "\uD83D\uDC92"
private const val PREVIEW_DAY_NUMBER = 18

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
