package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

/** Payment-reminder engine logic (§4.1): day-after-end creation, 7-day re-remind, stop conditions. */
class PaymentReminderPlannerTest {
    private val today = LocalDate.of(2026, 9, 20)
    private var idCounter = 0
    private val newId = { "reminder-${idCounter++}" }

    private fun reminder(
        bookingId: String,
        remindOn: LocalDate,
        status: ReminderStatus = ReminderStatus.PENDING,
    ) = PaymentReminder(
        id = newId(),
        bookingId = bookingId,
        businessId = Fixtures.BUSINESS_ID,
        remindOn = remindOn,
        status = status,
        amountDueSnapshotPaise = 1_00_000_00L,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
    )

    @Test
    fun `creates reminder the day after end date when due is positive`() {
        val booking = Fixtures.booking(startDate = today.minusDays(3), endDate = today.minusDays(1))
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 1_50_000_00L),
                remindersByBooking = emptyMap(),
                newId = newId,
                now = Fixtures.NOW,
            )
        val created = plan.toCreate.single()
        assertThat(created.remindOn).isEqualTo(booking.endDate.plusDays(1))
        assertThat(created.amountDueSnapshotPaise).isEqualTo(1_50_000_00L)
        assertThat(created.status).isEqualTo(ReminderStatus.PENDING)
        // remindOn == today → also notified immediately.
        assertThat(plan.toNotify).containsExactly(created)
    }

    @Test
    fun `no reminder when due is zero and pending ones get dismissed`() {
        val booking = Fixtures.booking(startDate = today.minusDays(5), endDate = today.minusDays(2))
        val stale = reminder(booking.id, today.minusDays(1))
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 0L),
                remindersByBooking = mapOf(booking.id to listOf(stale)),
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toNotify).isEmpty()
        assertThat(plan.toDismiss).containsExactly(stale)
    }

    @Test
    fun `existing pending reminder is reused not duplicated`() {
        val booking = Fixtures.booking(startDate = today.minusDays(10), endDate = today.minusDays(9))
        val pending = reminder(booking.id, today.minusDays(2))
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 5_000_00L),
                remindersByBooking = mapOf(booking.id to listOf(pending)),
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toNotify).containsExactly(pending)
    }

    @Test
    fun `re-reminds 7 days after the last answered reminder`() {
        val booking = Fixtures.booking(startDate = today.minusDays(20), endDate = today.minusDays(15))
        val answered = reminder(booking.id, today.minusDays(8), status = ReminderStatus.SNOOZED)
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 5_000_00L),
                remindersByBooking = mapOf(booking.id to listOf(answered)),
                newId = newId,
                now = Fixtures.NOW,
            )
        val created = plan.toCreate.single()
        assertThat(created.remindOn).isEqualTo(answered.remindOn.plusDays(7))
        assertThat(plan.toNotify).containsExactly(created) // 7 days already elapsed
    }

    @Test
    fun `future reminder is not notified yet`() {
        val booking = Fixtures.booking(startDate = today.minusDays(4), endDate = today.minusDays(1))
        val confirmed = reminder(booking.id, today, status = ReminderStatus.CONFIRMED)
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 5_000_00L),
                remindersByBooking = mapOf(booking.id to listOf(confirmed)),
                newId = newId,
                now = Fixtures.NOW,
            )
        val created = plan.toCreate.single()
        assertThat(created.remindOn).isEqualTo(today.plusDays(7)) // last.remindOn + 7
        assertThat(plan.toNotify).isEmpty()
    }

    @Test
    fun `nextAfterAction chains plus seven days from today`() {
        val current = reminder("booking-1", today.minusDays(1))
        val next =
            PaymentReminderPlanner.nextAfterAction(
                current = current,
                remainingDuePaise = 2_000_00L,
                today = today,
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(next).isNotNull()
        assertThat(next!!.remindOn).isEqualTo(today.plusDays(7))
        assertThat(next.amountDueSnapshotPaise).isEqualTo(2_000_00L)
        assertThat(next.bookingId).isEqualTo("booking-1")
    }

    @Test
    fun `nextAfterAction stops when nothing is due`() {
        val current = reminder("booking-1", today)
        assertThat(
            PaymentReminderPlanner.nextAfterAction(current, remainingDuePaise = 0L, today = today, newId = newId, now = Fixtures.NOW),
        ).isNull()
    }

    @Test
    fun `orphaned reminders of cancelled bookings are dismissed`() {
        val orphan = reminder("cancelled-booking", today.minusDays(1))
        val kept = reminder("live-booking", today.minusDays(1))
        val dismissals =
            PaymentReminderPlanner.orphanDismissals(
                pendingReminders = listOf(orphan, kept),
                liveCandidateBookingIds = setOf("live-booking"),
            )
        assertThat(dismissals).containsExactly(orphan)
    }
}
