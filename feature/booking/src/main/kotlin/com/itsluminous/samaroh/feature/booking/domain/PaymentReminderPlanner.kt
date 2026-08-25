package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import java.time.Instant
import java.time.LocalDate

/**
 * The payment-reminder engine's pure planning core (§4.1 — "the core fix for 'he never
 * updates dues'"). The daily worker feeds it state; it decides what to create, dismiss
 * and notify. Fully unit-tested; the worker only executes the returned plan.
 *
 * Lifecycle per booking:
 * - the day after `end_date`, if due > 0 → one pending reminder (remind_on = end + 1);
 * - "Not yet" / partial payment → the ACTION snoozes/confirms the current reminder and
 *   chains the next one at +7 days ([nextAfterAction]);
 * - the planner also re-chains defensively (+7 from the last reminder) so a missed
 *   action never stops the loop;
 * - due == 0 or booking cancelled → pending reminders are dismissed.
 */
object PaymentReminderPlanner {
    const val REREMIND_DAYS = 7L

    data class Plan(
        /** New reminders to persist (Room + outbox). */
        val toCreate: List<PaymentReminder>,
        /** Pending reminders that are obsolete (due settled / booking gone) → DISMISSED. */
        val toDismiss: List<PaymentReminder>,
        /** Pending reminders due today or earlier → fire a notification / show on the card. */
        val toNotify: List<PaymentReminder>,
    )

    /**
     * @param today the worker's local date.
     * @param endedBookings live, non-cancelled bookings with `end_date < today`.
     * @param duePaiseByBooking computed due per booking id (total − Σ payments).
     * @param remindersByBooking ALL live reminders per booking id (any status).
     * @param newId id factory for created reminders (client UUIDs, §2).
     * @param now timestamp for created rows.
     */
    fun plan(
        today: LocalDate,
        endedBookings: List<Booking>,
        duePaiseByBooking: Map<String, Long>,
        remindersByBooking: Map<String, List<PaymentReminder>>,
        newId: () -> String,
        now: Instant,
    ): Plan {
        val toCreate = mutableListOf<PaymentReminder>()
        val toDismiss = mutableListOf<PaymentReminder>()
        val toNotify = mutableListOf<PaymentReminder>()

        for (booking in endedBookings) {
            val due = duePaiseByBooking[booking.id] ?: 0L
            val reminders = remindersByBooking[booking.id].orEmpty().filter { it.deletedAt == null }
            val pending = reminders.filter { it.status == ReminderStatus.PENDING }

            if (due <= 0L) {
                toDismiss += pending
                continue
            }

            val active =
                pending.minByOrNull { it.remindOn } ?: run {
                    val last = reminders.maxByOrNull { it.remindOn }
                    val remindOn =
                        if (last == null) {
                            booking.endDate.plusDays(1)
                        } else {
                            maxOf(last.remindOn.plusDays(REREMIND_DAYS), booking.endDate.plusDays(1))
                        }
                    PaymentReminder(
                        id = newId(),
                        bookingId = booking.id,
                        businessId = booking.businessId,
                        remindOn = remindOn,
                        status = ReminderStatus.PENDING,
                        amountDueSnapshotPaise = due,
                        createdAt = now,
                        updatedAt = now,
                    ).also { toCreate += it }
                }

            if (!active.remindOn.isAfter(today)) toNotify += active
        }

        // Pending reminders whose booking is no longer a candidate (cancelled/deleted
        // after the reminder was created) are dismissed by the caller via [orphanDismissals].
        return Plan(toCreate, toDismiss, toNotify)
    }

    /**
     * Pending reminders whose booking is cancelled/deleted → DISMISSED (§4.1: reminders
     * stop when the booking is cancelled).
     */
    fun orphanDismissals(
        pendingReminders: List<PaymentReminder>,
        liveCandidateBookingIds: Set<String>,
    ): List<PaymentReminder> = pendingReminders.filter { it.bookingId !in liveCandidateBookingIds }

    /**
     * The follow-up reminder chained by a user action ("Not yet" snooze, or a partial
     * payment that leaves due > 0): re-remind [REREMIND_DAYS] from today.
     * Returns null when nothing is due anymore.
     */
    fun nextAfterAction(
        current: PaymentReminder,
        remainingDuePaise: Long,
        today: LocalDate,
        newId: () -> String,
        now: Instant,
    ): PaymentReminder? {
        if (remainingDuePaise <= 0L) return null
        return PaymentReminder(
            id = newId(),
            bookingId = current.bookingId,
            businessId = current.businessId,
            remindOn = today.plusDays(REREMIND_DAYS),
            status = ReminderStatus.PENDING,
            amountDueSnapshotPaise = remainingDuePaise,
            createdAt = now,
            updatedAt = now,
        )
    }
}
