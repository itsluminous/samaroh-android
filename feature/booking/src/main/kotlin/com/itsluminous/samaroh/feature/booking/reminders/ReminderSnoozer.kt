package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact-alarm scheduling shared by every reminder alarm (upcoming 09:00 popup, snooze
 * re-fire): exact + allow-while-idle when the user has not revoked
 * SCHEDULE_EXACT_ALARM (Android 12+), otherwise an inexact alarm — reminders degrade,
 * never crash (§6).
 */
internal fun AlarmManager.setExactOrInexact(
    triggerAtMillis: Long,
    operation: PendingIntent,
) {
    val canExact = Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()
    if (canExact) {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    } else {
        set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }
}

/**
 * Pure "is this reminder still worth ringing?" checks run when a snoozed reminder
 * re-fires (ADR-094) — mirroring the ADR-064 auto-dismissal rule so a snooze never
 * resurrects a reminder for a booking that was paid off, cancelled or deleted in the
 * meantime (possibly on another device).
 */
object SnoozePolicy {
    /** Upcoming-event reminder: the booking must still exist, be live and not have started. */
    fun upcomingStillRelevant(
        booking: Booking?,
        today: LocalDate,
    ): Boolean =
        booking != null &&
            booking.deletedAt == null &&
            booking.status != BookingStatus.CANCELLED &&
            !booking.startDate.isBefore(today)

    /**
     * Payment / follow-up row: still PENDING on a live booking; a payment reminder also
     * stops once the booking is TRULY paid (`total > 0 && due <= 0`, ADR-064) and a
     * follow-up once the booking is no longer tentative.
     */
    fun rowStillRelevant(
        reminder: PaymentReminder?,
        booking: Booking?,
        duePaise: Long,
        followUp: Boolean,
    ): Boolean {
        if (reminder == null || reminder.deletedAt != null || reminder.status != ReminderStatus.PENDING) return false
        if (booking == null || booking.deletedAt != null || booking.status == BookingStatus.CANCELLED) return false
        return if (followUp) {
            !TentativeFollowUpPlanner.isObsolete(booking)
        } else {
            !(booking.totalAmountPaise > 0L && duePaise <= 0L)
        }
    }
}

/**
 * Snooze = acknowledged now + ONE one-shot re-fire later (ADR-094). The re-fire is a
 * device-local exact alarm into [ReminderSnoozeAlarmReceiver]; the pending snooze is
 * recorded in the [ReminderLedger] (state SNOOZED + due time) so the daily / post-sync
 * passes leave the reminder alone and [ReminderBootReceiver] can re-arm it after a
 * reboot. At fire time [ReminderEngine.refire] re-validates the booking (ADR-064) and
 * posts through the SAME style pipeline as a first-time reminder.
 */
@Singleton
class ReminderSnoozer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val ledger: ReminderLedger,
        private val clock: Clock,
    ) {
        /** Snoozes [key] with [preset]; returns the instant it will ring again. */
        suspend fun snooze(
            key: ReminderLedgerKey,
            preset: SnoozePreset,
            keepUntil: LocalDate,
        ): Instant {
            // Wall-clock presets ("tomorrow 09:00") are in the DEVICE zone — the injected
            // Clock is UTC (DataModule), so its zone must not drive local-time math.
            val zone = ZoneId.systemDefault()
            val fireAt = SnoozePresets.fireAt(preset, clock.instant().atZone(zone))
            ledger.mark(
                key,
                ReminderFireState.SNOOZED,
                keepUntil = maxOf(keepUntil, fireAt.atZone(zone).toLocalDate()),
                snoozedUntilMillis = fireAt.toEpochMilli(),
            )
            arm(key, fireAt.toEpochMilli())
            return fireAt
        }

        /** Re-arms the alarm for an already-recorded snooze (boot pass); past-due snoozes ring right away. */
        fun arm(
            key: ReminderLedgerKey,
            fireAtMillis: Long,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.setExactOrInexact(fireAtMillis.coerceAtLeast(clock.millis() + 1_000), pendingIntent(key))
        }

        /** Drops any armed snooze for [key] (booking paid/cancelled/deleted, or user acted). */
        fun cancel(key: ReminderLedgerKey) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(key))
        }

        private fun pendingIntent(key: ReminderLedgerKey): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                key.encode().hashCode(),
                Intent(context, ReminderSnoozeAlarmReceiver::class.java).apply {
                    action = ReminderSnoozeAlarmReceiver.ACTION_REFIRE
                    putExtra(EXTRA_LEDGER_KEY, key.encode())
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

/** Intent extra carrying a [ReminderLedgerKey.encode] wire string across alarms, actions and activities. */
const val EXTRA_LEDGER_KEY = "com.itsluminous.samaroh.booking.EXTRA_LEDGER_KEY"
