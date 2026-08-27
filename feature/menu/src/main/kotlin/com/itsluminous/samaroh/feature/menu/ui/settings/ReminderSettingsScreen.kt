package com.itsluminous.samaroh.feature.menu.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
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
    val context = LocalContext.current

    val ringtoneLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                viewModel.setSoundUri(uri?.toString())
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

        Text(
            text = stringResource(R.string.settings_reminders_style_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        StyleRow(
            labelRes = R.string.settings_reminders_style_notification,
            selected = current.reminderStyle == ReminderStyle.NOTIFICATION,
        ) { viewModel.setStyle(ReminderStyle.NOTIFICATION) }
        StyleRow(
            labelRes = R.string.settings_reminders_style_fullscreen,
            selected = current.reminderStyle == ReminderStyle.FULLSCREEN,
        ) { viewModel.setStyle(ReminderStyle.FULLSCREEN) }
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
                            current.reminderSoundUri?.let {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                            }
                        }
                    ringtoneLauncher.launch(intent)
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
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.clickable(onClick = onSelect),
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
