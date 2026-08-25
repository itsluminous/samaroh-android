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
        EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
            .notifier()
            .postFullScreenUpcomingReminder(bookingId, title, daysAway, soundUri)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_DAYS_AWAY = "days_away"
        const val EXTRA_SOUND_URI = "sound_uri"

        /**
         * Schedules the exact wake-up. When exact alarms are not permitted (user can
         * revoke SCHEDULE_EXACT_ALARM on Android 12+), falls back to an inexact alarm —
         * reminders degrade, never crash (§6).
         */
        fun scheduleExact(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
            clock: Clock,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent =
                Intent(context, UpcomingReminderAlarmReceiver::class.java).apply {
                    putExtra(EXTRA_BOOKING_ID, bookingId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_DAYS_AWAY, daysAway)
                    putExtra(EXTRA_SOUND_URI, soundUri)
                }
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    bookingId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            // The daily pass runs at 09:00; the popup fires right at the pass's run time.
            val triggerAt =
                ZonedDateTime
                    .now(clock)
                    .toLocalDate()
                    .atTime(UpcomingReminderPlanner.DAILY_RUN_TIME)
                    .atZone(clock.zone)
                    .toInstant()
                    .toEpochMilli()
                    .coerceAtLeast(System.currentTimeMillis() + 1_000)
            val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }
}
