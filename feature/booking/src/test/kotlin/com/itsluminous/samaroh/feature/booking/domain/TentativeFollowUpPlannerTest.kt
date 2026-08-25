package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

/** Tentative-booking follow-up planning (ADR-020). */
class TentativeFollowUpPlannerTest {
    private val today = LocalDate.of(2026, 8, 25)
    private var idCounter = 0
    private val newId = { "follow-up-${++idCounter}" }

    @Test
    fun `create schedules a pending FOLLOW_UP at today plus N days`() {
        val booking = Fixtures.booking(status = BookingStatus.TENTATIVE)

        val reminder = TentativeFollowUpPlanner.create(booking, daysFromNow = 3, today = today, newId = newId, now = Fixtures.NOW)

        assertThat(reminder.kind).isEqualTo(ReminderKind.FOLLOW_UP)
        assertThat(reminder.status).isEqualTo(ReminderStatus.PENDING)
        assertThat(reminder.remindOn).isEqualTo(today.plusDays(3))
        assertThat(reminder.bookingId).isEqualTo(booking.id)
        assertThat(reminder.businessId).isEqualTo(booking.businessId)
        assertThat(reminder.amountDueSnapshotPaise).isEqualTo(0L)
    }

    @Test
    fun `create clamps a non-positive day count to one day`() {
        val reminder =
            TentativeFollowUpPlanner.create(
                Fixtures.booking(status = BookingStatus.TENTATIVE),
                daysFromNow = 0,
                today = today,
                newId = newId,
                now = Fixtures.NOW,
            )
        assertThat(reminder.remindOn).isEqualTo(today.plusDays(1))
    }

    @Test
    fun `snooze chains the next follow-up a week out`() {
        val booking = Fixtures.booking(status = BookingStatus.TENTATIVE)
        val current = TentativeFollowUpPlanner.create(booking, 1, today.minusDays(1), newId, Fixtures.NOW)

        val next = TentativeFollowUpPlanner.nextAfterSnooze(current, today, newId, Fixtures.NOW)

        assertThat(next.kind).isEqualTo(ReminderKind.FOLLOW_UP)
        assertThat(next.remindOn).isEqualTo(today.plusDays(TentativeFollowUpPlanner.SNOOZE_DAYS))
        assertThat(next.bookingId).isEqualTo(current.bookingId)
        assertThat(next.id).isNotEqualTo(current.id)
    }

    @Test
    fun `follow-ups become obsolete once the booking is no longer tentative`() {
        assertThat(TentativeFollowUpPlanner.isObsolete(Fixtures.booking(status = BookingStatus.TENTATIVE))).isFalse()
        assertThat(TentativeFollowUpPlanner.isObsolete(Fixtures.booking(status = BookingStatus.CONFIRMED))).isTrue()
        assertThat(TentativeFollowUpPlanner.isObsolete(Fixtures.booking(status = BookingStatus.CANCELLED))).isTrue()
        assertThat(TentativeFollowUpPlanner.isObsolete(null)).isTrue()
        assertThat(
            TentativeFollowUpPlanner.isObsolete(
                Fixtures.booking(status = BookingStatus.TENTATIVE).copy(deletedAt = Fixtures.NOW),
            ),
        ).isTrue()
    }
}
