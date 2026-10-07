package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itsluminous.samaroh.feature.booking.domain.UpcomingReminderPlanner
import java.time.Clock
import java.time.ZoneId

/**
 * Fires the alarm-style full-screen upcoming-event reminder (§4.1 "fullscreen" style):
 * the daily worker schedules an exact alarm via [scheduleExact]
 * (`AlarmManager.setExactAndAllowWhileIdle`); this receiver posts the full-screen-intent
 * notification that launches [FullScreenReminderActivity].
 *
 * Delivery is gated on the [ReminderLedger] (ADR-094): an alarm whose key was already
 * FIRED/ACKED/SNOOZED (style switched to plain notification before 09:00, user acted on
 * an earlier delivery) is swallowed, and a real fire records FIRED so the next planning
 * pass does not re-arm it. The Settings Test sample carries no key and always rings.
 */
class UpcomingReminderAlarmReceiver : BroadcastReceiver() {
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
        val key = ReminderLedgerKey.decode(intent.getStringExtra(EXTRA_LEDGER_KEY)) as? ReminderLedgerKey.Upcoming
        val deps = reminderDeps(context) ?: return
        runAsync {
            if (key != null) {
                val entry = deps.ledger().entry(key)
                if (entry != null && entry.blocksPlanning) return@runAsync
            }
            deps.notifier().postFullScreenUpcomingReminder(bookingId, title, daysAway, soundUri, style, key)
            if (key != null) deps.ledger().mark(key, ReminderFireState.FIRED, keepUntil = key.startDate)
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_DAYS_AWAY = "days_away"
        const val EXTRA_SOUND_URI = "sound_uri"
        const val EXTRA_STYLE = "style"

        /**
         * Schedules the exact wake-up for the daily pass. The pass runs at 09:00; the
         * popup fires right at the pass's run time (or ~now when the pass ran late).
         * 09:00 is DEVICE local time: the injected Clock is UTC (DataModule), so the
         * wall-clock date/time is taken in the system zone, not the clock's.
         */
        fun scheduleExact(
            context: Context,
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
            style: ReminderStyle,
            clock: Clock,
            ledgerKey: ReminderLedgerKey.Upcoming? = null,
        ) {
            val zone = ZoneId.systemDefault()
            val triggerAt =
                clock
                    .instant()
                    .atZone(zone)
                    .toLocalDate()
                    .atTime(UpcomingReminderPlanner.DAILY_RUN_TIME)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                    .coerceAtLeast(System.currentTimeMillis() + 1_000)
            scheduleExactAt(context, bookingId, title, daysAway, soundUri, style, triggerAt, ledgerKey)
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
            ledgerKey: ReminderLedgerKey.Upcoming? = null,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent =
                Intent(context, UpcomingReminderAlarmReceiver::class.java).apply {
                    putExtra(EXTRA_BOOKING_ID, bookingId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_DAYS_AWAY, daysAway)
                    putExtra(EXTRA_SOUND_URI, soundUri)
                    putExtra(EXTRA_STYLE, style.wire)
                    putExtra(EXTRA_LEDGER_KEY, ledgerKey?.encode())
                }
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    bookingId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            alarmManager.setExactOrInexact(triggerAtMillis, pending)
        }
    }
}
