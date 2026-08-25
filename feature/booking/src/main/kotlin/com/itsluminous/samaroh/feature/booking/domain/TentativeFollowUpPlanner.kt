package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Pure planning core of the tentative-booking follow-up loop (ADR-020): when a booking
 * is saved as Tentative, the form schedules a "Follow up with {customer}" reminder after
 * the chosen number of days. Follow-ups reuse the `payment_reminders` row with the
 * local-only [ReminderKind.FOLLOW_UP] discriminator, so they ride the same daily worker,
 * in-app pending card and Room+outbox write path as payment reminders.
 *
 * Lifecycle:
 * - save with status=Tentative → any pending follow-up is superseded (dismissed) and one
 *   follow-up is created at `today + N`;
 * - save with any other status, cancel, or confirm → pending follow-ups are dismissed;
 * - Snooze on the card/notification chains the next follow-up at +[SNOOZE_DAYS];
 * - the daily engine dismisses follow-ups whose booking is no longer live-tentative
 *   ([isObsolete]) so a confirmation on another device also stops the loop.
 */
object TentativeFollowUpPlanner {
    /** The quick-pick chips on the booking form. */
    val PRESET_DAYS: List<Int> = listOf(1, 3, 7)

    /** Default selection of the form's follow-up selector. */
    const val DEFAULT_DAYS: Int = 3

    /** "Snooze" re-reminds after a week, mirroring the payment-reminder chain. */
    const val SNOOZE_DAYS: Long = 7L

    /** The follow-up reminder scheduled [daysFromNow] days from [today]. */
    fun create(
        booking: Booking,
        daysFromNow: Int,
        today: LocalDate,
        newId: () -> String,
        now: Instant,
    ): PaymentReminder =
        PaymentReminder(
            id = newId(),
            bookingId = booking.id,
            businessId = booking.businessId,
            remindOn = today.plusDays(daysFromNow.coerceAtLeast(1).toLong()),
            status = ReminderStatus.PENDING,
            amountDueSnapshotPaise = 0L,
            kind = ReminderKind.FOLLOW_UP,
            createdAt = now,
            updatedAt = now,
        )

    /** The next follow-up chained by a Snooze action, [SNOOZE_DAYS] from [today]. */
    fun nextAfterSnooze(
        current: PaymentReminder,
        today: LocalDate,
        newId: () -> String,
        now: Instant,
    ): PaymentReminder =
        PaymentReminder(
            id = newId(),
            bookingId = current.bookingId,
            businessId = current.businessId,
            remindOn = today.plusDays(SNOOZE_DAYS),
            status = ReminderStatus.PENDING,
            amountDueSnapshotPaise = 0L,
            kind = ReminderKind.FOLLOW_UP,
            createdAt = now,
            updatedAt = now,
        )

    /**
     * A follow-up is obsolete once its booking is gone, tombstoned, or no longer
     * tentative (confirmed / completed / cancelled) — the engine dismisses it.
     */
    fun isObsolete(booking: Booking?): Boolean = booking == null || booking.deletedAt != null || booking.status != BookingStatus.TENTATIVE
}
