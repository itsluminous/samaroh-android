package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
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
    fun `duplicate pending reminders are deduped - earliest kept, rest dismissed`() {
        // Two devices each planned a reminder before syncing (ADR-024): exactly one survives.
        val booking = Fixtures.booking(startDate = today.minusDays(10), endDate = today.minusDays(9))
        val earliest = reminder(booking.id, today.minusDays(3))
        val duplicate = reminder(booking.id, today.minusDays(2))
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to 5_000_00L),
                remindersByBooking = mapOf(booking.id to listOf(duplicate, earliest)),
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toDismiss).containsExactly(duplicate)
        assertThat(plan.toNotify).containsExactly(earliest)
    }

    @Test
    fun `booking with unknown total and payments produces no reminder - due clamps to zero`() {
        // Imported history: total_amount 0 (unknown) but advances recorded. due = max(0-paid, 0) = 0.
        val booking = Fixtures.booking(startDate = today.minusDays(30), endDate = today.minusDays(29))
        val due = DueCalculator.duePaise(totalAmountPaise = 0L, paidPaise = 5_000_00L)
        val stale = reminder(booking.id, today.minusDays(1))
        val plan =
            PaymentReminderPlanner.plan(
                today = today,
                endedBookings = listOf(booking),
                duePaiseByBooking = mapOf(booking.id to due),
                remindersByBooking = mapOf(booking.id to listOf(stale)),
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toNotify).isEmpty()
        assertThat(plan.toDismiss).containsExactly(stale)
    }

    // ---- staleDismissals: the cleanup pass over ALL due pending reminders (ADR-024) ----

    @Test
    fun `stale reminders of missing cancelled or deleted bookings are dismissed`() {
        val orphan = reminder("gone-booking", today.minusDays(1))
        val cancelled = Fixtures.booking(startDate = today.minusDays(6), endDate = today.minusDays(5))
        val cancelledReminder = reminder(cancelled.id, today.minusDays(1))
        val live = Fixtures.booking(id = "live-booking", startDate = today.minusDays(4), endDate = today.minusDays(3))
        val kept = reminder(live.id, today.minusDays(1))
        val dismissals =
            PaymentReminderPlanner.staleDismissals(
                duePendingReminders = listOf(orphan, cancelledReminder, kept),
                bookingById =
                    mapOf(
                        "gone-booking" to null,
                        cancelled.id to cancelled.copy(status = BookingStatus.CANCELLED),
                        live.id to live,
                    ),
                duePaiseByBooking = mapOf(live.id to 5_000_00L),
            )
        assertThat(dismissals).containsExactly(orphan, cancelledReminder)
    }

    @Test
    fun `stale reminder of a settled booking is dismissed even when the booking has not ended locally`() {
        // A reminder synced from another device for a booking settled here (due <= 0)
        // must not linger on the pending-confirmations card.
        val settled = Fixtures.booking(startDate = today.plusDays(2), endDate = today.plusDays(3))
        val syncedReminder = reminder(settled.id, today.minusDays(1))
        val dismissals =
            PaymentReminderPlanner.staleDismissals(
                duePendingReminders = listOf(syncedReminder),
                bookingById = mapOf(settled.id to settled),
                duePaiseByBooking = mapOf(settled.id to 0L),
            )
        assertThat(dismissals).containsExactly(syncedReminder)
    }

    @Test
    fun `stale pass keeps a due reminder of a live not-yet-ended booking`() {
        // Regression guard: the old orphan pass dismissed by "not in the ended set",
        // which would nuke a legitimate reminder of a booking ending today.
        val endsToday = Fixtures.booking(startDate = today.minusDays(1), endDate = today)
        val legit = reminder(endsToday.id, today)
        val dismissals =
            PaymentReminderPlanner.staleDismissals(
                duePendingReminders = listOf(legit),
                bookingById = mapOf(endsToday.id to endsToday),
                duePaiseByBooking = mapOf(endsToday.id to 5_000_00L),
            )
        assertThat(dismissals).isEmpty()
    }
}
