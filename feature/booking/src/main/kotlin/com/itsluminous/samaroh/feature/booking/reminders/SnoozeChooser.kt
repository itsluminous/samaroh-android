package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localized label for a snooze preset; "tomorrow" carries the formatted local morning time. */
@Composable
fun snoozePresetLabel(preset: SnoozePreset): String =
    when (preset) {
        SnoozePreset.TEN_MINUTES -> stringResource(R.string.booking_reminder_snooze_10m)
        SnoozePreset.THIRTY_MINUTES -> stringResource(R.string.booking_reminder_snooze_30m)
        SnoozePreset.ONE_HOUR -> stringResource(R.string.booking_reminder_snooze_1h)
        SnoozePreset.THREE_HOURS -> stringResource(R.string.booking_reminder_snooze_3h)
        SnoozePreset.TOMORROW_MORNING ->
            stringResource(
                R.string.booking_reminder_snooze_tomorrow,
                SnoozePresets.MORNING.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())),
            )
    }

/**
 * The snooze preset chooser (ADR-094) — shared by the full-screen popup (in-activity
 * dialog) and the notification action ([SnoozeChooserActivity]). Tapping a row picks it.
 */
@Composable
fun SnoozeChooserDialog(
    onPick: (SnoozePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.booking_reminder_snooze_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SnoozePreset.entries.forEach { preset ->
                    Text(
                        text = snoozePresetLabel(preset),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(preset) }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

/** "Snoozed until {time}" — time only when it rings today, date + time otherwise. */
object SnoozeFormatting {
    fun untilLabel(
        context: Context,
        fireAt: Instant,
        now: ZonedDateTime,
        zone: ZoneId = now.zone,
    ): String {
        val at = fireAt.atZone(zone)
        val locale = Locale.getDefault()
        val formatter =
            if (at.toLocalDate() == now.toLocalDate()) {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            } else {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            }
        return context.getString(R.string.booking_reminder_snoozed_until, at.format(formatter.withLocale(locale)))
    }
}

/**
 * Lightweight dialog-themed activity behind the notification "Snooze" action
 * (ADR-094): shows [SnoozeChooserDialog] over a transparent window, snoozes the
 * reminder via [ReminderSnoozer] (acked now + one-shot re-fire), cancels the
 * notification it came from and finishes. A toast confirms the re-fire time — the
 * activity has no scaffold to host a snackbar and is gone the next instant.
 */
class SnoozeChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = ReminderLedgerKey.decode(intent.getStringExtra(EXTRA_LEDGER_KEY))
        if (key == null) {
            finish()
            return
        }
        val deps = reminderDeps(this)
        if (deps == null) {
            finish()
            return
        }
        setContent {
            SamarohTheme {
                SnoozeChooserDialog(
                    onPick = { preset ->
                        lifecycleScope.launch {
                            val fireAt = deps.snoozer().snooze(key, preset, keepUntil = defaultKeepUntil(key))
                            intent.getStringExtra(EXTRA_NOTIFICATION_TAG)?.let { tag ->
                                NotificationManagerCompat
                                    .from(
                                        this@SnoozeChooserActivity,
                                    ).cancel(tag, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                            }
                            Toast
                                .makeText(
                                    this@SnoozeChooserActivity,
                                    SnoozeFormatting.untilLabel(this@SnoozeChooserActivity, fireAt, ZonedDateTime.now()),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            finish()
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    companion object {
        const val ACTION_CHOOSE = "com.itsluminous.samaroh.booking.action.REMINDER_SNOOZE_CHOOSE"
        private const val EXTRA_NOTIFICATION_TAG = "notification_tag"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(
            context: Context,
            key: ReminderLedgerKey,
            notificationTag: String,
            notificationId: Int,
        ): Intent =
            Intent(context, SnoozeChooserActivity::class.java).apply {
                action = ACTION_CHOOSE
                putExtra(EXTRA_LEDGER_KEY, key.encode())
                putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
    }
}

/** Ledger relevance horizon when snoozing: the event day for upcoming keys, the row horizon otherwise. */
internal fun defaultKeepUntil(key: ReminderLedgerKey) =
    when (key) {
        is ReminderLedgerKey.Upcoming -> key.startDate
        is ReminderLedgerKey.Row ->
            java.time.LocalDate
                .now()
                .plusDays(ReminderLedger.ROW_KEEP_DAYS)
    }
