package com.itsluminous.samaroh.feature.booking.reminders

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
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
import androidx.core.app.NotificationManagerCompat
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R

/**
 * Alarm-style full-screen upcoming-event reminder (§4.1 "full-screen popup" style).
 * Launched by the full-screen intent of the notification posted by
 * [UpcomingReminderAlarmReceiver]; shows the event card with Dismiss/View actions,
 * over the lock screen if needed (`showWhenLocked`/`turnScreenOn` in the manifest).
 *
 * Repeat-until-acknowledged (ADR-074): while visible, the popup is THE alert — it
 * cancels its paired notification (silencing that notification's insistent sound
 * loop) and plays its own LOOPING reminder sound until the user acts. Dismiss and
 * View acknowledge explicitly; leaving the popup any other way (home/back/screen
 * off) stops the loop too — a full-screen popup the user navigated away from has
 * been seen. Do Not Disturb keeps the popup visual-only ([ReminderRepeatPolicy]).
 */
class FullScreenReminderActivity : ComponentActivity() {
    private var loopPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY)
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
                            // Payment/follow-up popups carry their question as a plain
                            // body (ADR-045); upcoming-event popups keep the "in N days"
                            // plural resolved here so it renders in the app locale.
                            text = body ?: pluralStringResource(R.plurals.booking_reminder_upcoming_days, daysAway, daysAway),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
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

    override fun onStart() {
        super.onStart()
        // Take over as THE alert (ADR-074): silence the paired notification's
        // insistent loop, then own the looping sound while the popup is visible.
        cancelPairedNotification()
        startLoop()
    }

    override fun onStop() {
        stopLoop()
        super.onStop()
    }

    /**
     * Cancels the notification this popup was launched from. The full-screen intent
     * path and the direct-activity path both post one; with FLAG_INSISTENT its sound
     * would keep looping BEHIND the popup — the popup replaces it entirely.
     */
    private fun cancelPairedNotification() {
        val tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG) ?: return
        NotificationManagerCompat
            .from(this)
            .cancel(tag, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    /** Starts the looping reminder sound unless already playing or DND asks for quiet. */
    private fun startLoop() {
        if (loopPlayer != null) return
        val filter =
            getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (!ReminderRepeatPolicy.shouldLoopInPopup(filter)) {
            Log.i(TAG, "reminder loop skipped: DND (filter=$filter)")
            return
        }
        val soundUri = ReminderSoundPolicy.effectiveSoundUri(intent.getStringExtra(EXTRA_SOUND_URI))
        loopPlayer =
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(this@FullScreenReminderActivity, soundUri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onSuccess { Log.i(TAG, "reminder loop started: $soundUri") }
                .onFailure { Log.w(TAG, "reminder loop failed to start", it) }
                .getOrNull()
    }

    /** Stops + releases the looping sound; safe to call repeatedly. */
    private fun stopLoop() {
        loopPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
            Log.i(TAG, "reminder loop stopped")
        }
        loopPlayer = null
    }

    private fun openBooking(bookingId: String?) {
        stopLoop()
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            bookingId?.let { launch.putExtra(EXTRA_BOOKING_ID, it) }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        }
        finish()
    }

    companion object {
        private const val TAG = "FullScreenReminder"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_DAYS_AWAY = "days_away"
        private const val EXTRA_SOUND_URI = "sound_uri"
        private const val EXTRA_NOTIFICATION_TAG = "notification_tag"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** Upcoming-event popup: title + localized "in N days" line. */
        fun intent(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String? = null,
            notificationTag: String? = null,
            notificationId: Int = 0,
        ): Intent =
            Intent(context, FullScreenReminderActivity::class.java).apply {
                putExtra(EXTRA_BOOKING_ID, bookingId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DAYS_AWAY, daysAway)
                putExtra(EXTRA_SOUND_URI, soundUri)
                putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Payment/follow-up popup (ADR-045): title + the reminder question as body. */
        fun intent(
            context: Context,
            bookingId: String,
            title: String,
            body: String,
            soundUri: String? = null,
            notificationTag: String? = null,
            notificationId: Int = 0,
        ): Intent =
            Intent(context, FullScreenReminderActivity::class.java).apply {
                putExtra(EXTRA_BOOKING_ID, bookingId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_SOUND_URI, soundUri)
                putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
