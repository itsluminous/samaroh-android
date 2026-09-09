package com.itsluminous.samaroh.feature.menu.ui.settings

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
import com.itsluminous.samaroh.feature.menu.domain.ReminderPermissionsStatus
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

private val PRESET_LEAD_DAYS = listOf(1, 3, 7)

/** Booking-reminder preferences (§4.4): lead times (1/3/7/custom), style, sound. */
@Composable
fun ReminderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReminderSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    var showFsiBlockedDialog by rememberSaveable { mutableStateOf(false) }
    var showOverlayDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // System permission states (ADR-043): re-read on resume so returning from the
    // deep-linked system-settings screens refreshes the rows immediately.
    var permissionRefresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsEnabled =
        remember(permissionRefresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val canUseFullScreenIntent =
        remember(permissionRefresh) {
            Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        }
    val canScheduleExactAlarms =
        remember(permissionRefresh) {
            Build.VERSION.SDK_INT < 31 ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }
    // "Display over other apps" — the ALWAYS full-screen style's launch exemption
    // (ADR-072). Re-read on resume like the rest.
    val canDrawOverlays = remember(permissionRefresh) { Settings.canDrawOverlays(context) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
    // Contextual request (spec §6, ADR-043): opening the reminder settings IS the
    // reminder-relevant moment — fire the system dialog once per screen entry while
    // not granted. Denial changes nothing: the app stays fully usable, and the status
    // row below keeps offering the fix.
    LaunchedEffect(Unit) {
        if (ReminderPermissionsStatus.shouldRequestNotifications(Build.VERSION.SDK_INT, notificationsEnabled)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val ringtoneLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                // The picker's "Default" entry maps to the UNSET preference (ADR-073):
                // unset means "system default notification sound", so an explicit pick
                // of the default collapses to the canonical unset representation.
                viewModel.setSoundUri(
                    uri?.toString().takeUnless { it == Settings.System.DEFAULT_NOTIFICATION_URI.toString() },
                )
            }
        }

    MenuScreenScaffold(titleRes = R.string.settings_reminders_title, onBack = onBack) {
        val current = settings ?: return@MenuScreenScaffold

        Text(
            text = stringResource(R.string.settings_reminders_lead_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        ChipRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
            val presetAndSelected = (PRESET_LEAD_DAYS + current.reminderLeadDays).distinct().sorted()
            for (day in presetAndSelected) {
                FilterChip(
                    selected = day in current.reminderLeadDays,
                    onClick = { viewModel.toggleLeadDay(day) },
                    label = { Text(pluralStringResource(R.plurals.settings_reminders_lead_option, day, day)) },
                )
            }
            FilterChip(
                selected = false,
                onClick = { showCustomDialog = true },
                label = { Text(stringResource(R.string.settings_reminders_custom)) },
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        // Style selector + Test (ADR-045): the button fires a sample reminder through
        // the REAL pipeline with the chosen style + sound. If the full-screen style is
        // selected but the Android 14+ grant is off, the fix-it dialog appears instead —
        // never a silently demoted test.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_reminders_style_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    when {
                        // ADR-072: the overlay grant is the ALWAYS style's defining
                        // permission — its fix-it prompt wins over the FSI one.
                        ReminderPermissionsStatus.blocksAlwaysFullScreenTest(
                            style = current.reminderStyle,
                            canDrawOverlays = canDrawOverlays,
                        ) -> showOverlayDialog = true
                        ReminderPermissionsStatus.blocksFullScreenTest(
                            sdkInt = Build.VERSION.SDK_INT,
                            style = current.reminderStyle,
                            canUseFullScreenIntent = canUseFullScreenIntent,
                        ) -> showFsiBlockedDialog = true
                        else -> viewModel.fireTestReminder()
                    }
                },
            ) {
                Text(stringResource(R.string.settings_reminders_test_button))
            }
        }
        // Three clearly-worded styles (ADR-072), each with its own behaviour line —
        // "Full screen when locked" is today's popup renamed for what it actually
        // does; "Always full screen" is the new takeover-anywhere mode.
        StyleRow(
            labelRes = R.string.settings_reminders_style_notification,
            descRes = R.string.settings_reminders_style_notification_desc,
            selected = current.reminderStyle == ReminderStyle.NOTIFICATION,
        ) { viewModel.setStyle(ReminderStyle.NOTIFICATION) }
        StyleRow(
            labelRes = R.string.settings_reminders_style_fullscreen,
            descRes = R.string.settings_reminders_style_fullscreen_desc,
            selected = current.reminderStyle == ReminderStyle.FULLSCREEN,
        ) { viewModel.setStyle(ReminderStyle.FULLSCREEN) }
        StyleRow(
            labelRes = R.string.settings_reminders_style_fullscreen_always,
            descRes = R.string.settings_reminders_style_fullscreen_always_desc,
            selected = current.reminderStyle == ReminderStyle.FULLSCREEN_ALWAYS,
        ) {
            viewModel.setStyle(ReminderStyle.FULLSCREEN_ALWAYS)
            // Contextual ask (spec §6, ADR-072): picking the mode that NEEDS the
            // "display over other apps" grant is the moment to explain + deep-link.
            // Denial changes nothing — the style stays selected and degrades to the
            // "when locked" behaviour; the status row below keeps offering the fix.
            if (!canDrawOverlays) showOverlayDialog = true
        }
        HorizontalDivider()

        // Sound picker — only meaningful for the full-screen style (§4.1), always reachable.
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_reminders_sound_title)) },
            supportingContent = {
                Text(soundLabel(current.reminderSoundUri))
            },
            modifier =
                Modifier.clickable {
                    val intent =
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            // "Default" is the system default NOTIFICATION sound — the
                            // same sound an unset preference resolves to (ADR-073).
                            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
                            // Unset never means silence (ADR-073), so silence is not offered.
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                current.reminderSoundUri?.let(Uri::parse) ?: Settings.System.DEFAULT_NOTIFICATION_URI,
                            )
                        }
                    ringtoneLauncher.launch(intent)
                },
        )
        HorizontalDivider()

        // Permission status (ADR-043): what the OS will actually let reminders do,
        // with fix-it actions deep-linking to the matching system-settings screen.
        Text(
            text = stringResource(R.string.settings_reminders_permissions_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        ReminderPermissionsStatus
            .rows(
                sdkInt = Build.VERSION.SDK_INT,
                notificationsEnabled = notificationsEnabled,
                style = current.reminderStyle,
                canUseFullScreenIntent = canUseFullScreenIntent,
                canScheduleExactAlarms = canScheduleExactAlarms,
                canDrawOverlays = canDrawOverlays,
            ).forEach { rowState ->
                PermissionStatusRow(
                    state = rowState,
                    onFix = {
                        when (rowState.row) {
                            ReminderPermissionsStatus.Row.NOTIFICATIONS ->
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            ReminderPermissionsStatus.Row.FULL_SCREEN ->
                                if (Build.VERSION.SDK_INT >= 34) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                            .setData(Uri.fromParts("package", context.packageName, null)),
                                    )
                                }
                            ReminderPermissionsStatus.Row.EXACT_ALARM ->
                                if (Build.VERSION.SDK_INT >= 31) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(Uri.fromParts("package", context.packageName, null)),
                                    )
                                }
                            ReminderPermissionsStatus.Row.OVERLAY ->
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        .setData(Uri.fromParts("package", context.packageName, null)),
                                )
                        }
                    },
                )
            }
    }

    // Contextual overlay ask (ADR-072): the ALWAYS style was picked (or its Test was
    // tapped) without the "display over other apps" grant — explain what the grant is
    // for and deep-link to the system screen that gives it.
    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = { Text(stringResource(R.string.settings_reminders_overlay_needed_title)) },
            text = { Text(stringResource(R.string.settings_reminders_overlay_needed_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayDialog = false
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_reminders_permission_fix))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            },
        )
    }

    // Fix-it prompt (ADR-045): the full-screen style is selected but the Android 14+
    // grant is off — instead of firing a test the OS would silently demote, deep-link
    // straight to the system screen that fixes it.
    if (showFsiBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showFsiBlockedDialog = false },
            title = { Text(stringResource(R.string.settings_reminders_test_fullscreen_blocked_title)) },
            text = { Text(stringResource(R.string.settings_reminders_test_fullscreen_blocked_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFsiBlockedDialog = false
                        if (Build.VERSION.SDK_INT >= 34) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_reminders_permission_fix))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFsiBlockedDialog = false }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            },
        )
    }

    if (showCustomDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(stringResource(R.string.settings_reminders_custom)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.settings_reminders_custom_days_label)) },
                    keyboardOptions =
                        androidx.compose.foundation.text
                            .KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        text.toIntOrNull()?.let(viewModel::addCustomLeadDay)
                        showCustomDialog = false
                    },
                ) {
                    Text(stringResource(R.string.common_action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StyleRow(
    labelRes: Int,
    descRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        // Per-option behaviour line (ADR-072) — replaces the former single hint, which
        // could no longer describe three styles at once.
        supportingContent = { Text(stringResource(descRes)) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

/**
 * One permission-status row (ADR-043): grant name + state. Everything stays usable
 * either way — this is information, not a gate — but a DENIED row is loud (ADR-045):
 * error-container background, warning icon and a filled Allow button, because a
 * quietly demoted full-screen reminder was being read as "the app is broken".
 */
@Composable
private fun PermissionStatusRow(
    state: ReminderPermissionsStatus.RowState,
    onFix: () -> Unit,
) {
    val nameRes =
        when (state.row) {
            ReminderPermissionsStatus.Row.NOTIFICATIONS -> R.string.settings_reminders_permission_notifications
            ReminderPermissionsStatus.Row.FULL_SCREEN -> R.string.settings_reminders_permission_fullscreen
            ReminderPermissionsStatus.Row.EXACT_ALARM -> R.string.settings_reminders_permission_exact_alarm
            ReminderPermissionsStatus.Row.OVERLAY -> R.string.settings_reminders_permission_overlay
        }
    val stateRes =
        if (state.granted) {
            R.string.settings_reminders_permission_granted
        } else {
            when (state.row) {
                ReminderPermissionsStatus.Row.NOTIFICATIONS -> R.string.settings_reminders_permission_notifications_denied
                ReminderPermissionsStatus.Row.FULL_SCREEN -> R.string.settings_reminders_permission_fullscreen_denied
                ReminderPermissionsStatus.Row.EXACT_ALARM -> R.string.settings_reminders_permission_exact_alarm_denied
                ReminderPermissionsStatus.Row.OVERLAY -> R.string.settings_reminders_permission_overlay_denied
            }
        }
    ListItem(
        colors =
            if (state.granted) {
                ListItemDefaults.colors()
            } else {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer)
            },
        leadingContent =
            if (state.granted) {
                null
            } else {
                {
                    // Decorative — the denied text next to it carries the meaning.
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        headlineContent = { Text(stringResource(nameRes)) },
        supportingContent = {
            Text(
                text = stringResource(stateRes),
                color =
                    if (state.granted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
            )
        },
        trailingContent =
            if (state.granted) {
                null
            } else {
                {
                    Button(onClick = onFix) {
                        Text(stringResource(R.string.settings_reminders_permission_fix))
                    }
                }
            },
    )
}

@Composable
private fun soundLabel(uri: String?): String {
    if (uri == null) return stringResource(R.string.settings_reminders_sound_default)
    val context = LocalContext.current
    val title =
        remember(uri) {
            runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull()
        }
    return title ?: stringResource(R.string.settings_reminders_sound_default)
}
