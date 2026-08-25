package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R

/**
 * Alarm-style full-screen upcoming-event reminder (§4.1 "full-screen popup" style).
 * Launched by the full-screen intent of the notification posted by
 * [UpcomingReminderAlarmReceiver]; shows the event card with Dismiss/View actions,
 * over the lock screen if needed (`showWhenLocked`/`turnScreenOn` in the manifest).
 */
class FullScreenReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val daysAway = intent.getIntExtra(EXTRA_DAYS_AWAY, 1)
        val bookingId = intent.getStringExtra(EXTRA_BOOKING_ID)

        setContent {
            SamarohTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.booking_reminder_upcoming_days, daysAway, daysAway),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        ) {
                            OutlinedButton(onClick = { finish() }) {
                                Text(stringResource(R.string.booking_reminder_dismiss))
                            }
                            Button(onClick = { openBooking(bookingId) }) {
                                Text(stringResource(R.string.booking_reminder_view))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openBooking(bookingId: String?) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            bookingId?.let { launch.putExtra(EXTRA_BOOKING_ID, it) }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        }
        finish()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DAYS_AWAY = "days_away"

        fun intent(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
        ): Intent =
            Intent(context, FullScreenReminderActivity::class.java).apply {
                putExtra(EXTRA_BOOKING_ID, bookingId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DAYS_AWAY, daysAway)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
