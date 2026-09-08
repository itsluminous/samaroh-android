package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.itsluminous.samaroh.feature.booking.domain.UpcomingReminderPlanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Fires the alarm-style full-screen upcoming-event reminder (§4.1 "fullscreen" style):
 * the daily worker schedules an exact alarm via [scheduleExact]
 * (`AlarmManager.setExactAndAllowWhileIdle`); this receiver posts the full-screen-intent
 * notification that launches [FullScreenReminderActivity].
 */
class UpcomingReminderAlarmReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun notifier(): BookingNotifier
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val bookingId = intent.getStringExtra(EXTRA_BOOKING_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val daysAway = intent.getIntExtra(EXTRA_DAYS_AWAY, 1)
        val soundUri = intent.getStringExtra(EXTRA_SOUND_URI)
        // Style travels in the intent like the sound (scheduled the same morning it
        // fires, so drift is negligible); a missing/unknown value decodes tolerantly.
        // NOTIFICATION never schedules this alarm, so anything unknown means "some
        // full-screen style" → default to the plain FULLSCREEN treatment.
        val style =
            ReminderStyle.fromWire(intent.getStringExtra(EXTRA_STYLE)).takeIf { it != ReminderStyle.NOTIFICATION }
                ?: ReminderStyle.FULLSCREEN
        EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
            .notifier()
            .postFullScreenUpcomingReminder(bookingId, title, daysAway, soundUri, style)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_DAYS_AWAY = "days_away"
        const val EXTRA_SOUND_URI = "sound_uri"
        const val EXTRA_STYLE = "style"

        /**
         * Schedules the exact wake-up for the daily pass. The pass runs at 09:00; the
         * popup fires right at the pass's run time (or ~now when the pass ran late).
         */
        fun scheduleExact(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
            style: ReminderStyle,
            clock: Clock,
        ) {
            val triggerAt =
                ZonedDateTime
                    .now(clock)
                    .toLocalDate()
                    .atTime(UpcomingReminderPlanner.DAILY_RUN_TIME)
                    .atZone(clock.zone)
                    .toInstant()
                    .toEpochMilli()
                    .coerceAtLeast(System.currentTimeMillis() + 1_000)
            scheduleExactAt(context, bookingId, title, daysAway, soundUri, style, triggerAt)
        }

        /**
         * Schedules the exact wake-up at an explicit time — the production posting
         * path behind [scheduleExact], also used by the Settings Test button (ADR-045)
         * so the sample popup travels the IDENTICAL AlarmManager → receiver →
         * full-screen-notification pipeline. When exact alarms are not permitted
         * (user can revoke SCHEDULE_EXACT_ALARM on Android 12+), falls back to an
         * inexact alarm — reminders degrade, never crash (§6).
         */
        fun scheduleExactAt(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
            style: ReminderStyle,
            triggerAtMillis: Long,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent =
                Intent(context, UpcomingReminderAlarmReceiver::class.java).apply {
                    putExtra(EXTRA_BOOKING_ID, bookingId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_DAYS_AWAY, daysAway)
                    putExtra(EXTRA_SOUND_URI, soundUri)
                    putExtra(EXTRA_STYLE, style.wire)
                }
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    bookingId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }
    }
}
