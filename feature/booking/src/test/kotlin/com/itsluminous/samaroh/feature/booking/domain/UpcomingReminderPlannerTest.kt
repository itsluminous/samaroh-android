package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Upcoming-event reminder planning and the daily 09:00 scheduling delay (§4.1). */
class UpcomingReminderPlannerTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun `reminder dates map each configured lead day`() {
        val dates = UpcomingReminderPlanner.reminderDates(today, setOf(1, 3, 7))
        assertThat(dates).containsExactly(
            1,
            today.plusDays(1),
            3,
            today.plusDays(3),
            7,
            today.plusDays(7),
        )
    }

    @Test
    fun `non positive lead days are ignored`() {
        assertThat(UpcomingReminderPlanner.reminderDates(today, setOf(0, -2, 3))).containsExactly(3, today.plusDays(3))
    }

    @Test
    fun `reminders are ordered by days away then start date`() {
        val near = Fixtures.booking(startDate = today.plusDays(1))
        val far = Fixtures.booking(startDate = today.plusDays(7))
        val reminders =
            UpcomingReminderPlanner.remindersFor(
                mapOf(7 to listOf(far), 1 to listOf(near)),
            )
        assertThat(reminders.map { it.booking.id }).containsExactly(near.id, far.id).inOrder()
        assertThat(reminders.first().daysAway).isEqualTo(1)
    }

    @Test
    fun `delay before 9am targets today`() {
        val now = ZonedDateTime.of(2026, 9, 20, 7, 0, 0, 0, zone)
        assertThat(UpcomingReminderPlanner.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `delay after 9am targets tomorrow`() {
        val now = ZonedDateTime.of(2026, 9, 20, 10, 30, 0, 0, zone)
        assertThat(UpcomingReminderPlanner.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(22).plusMinutes(30))
    }

    @Test
    fun `delay at exactly 9am targets tomorrow`() {
        val now = ZonedDateTime.of(2026, 9, 20, 9, 0, 0, 0, zone)
        assertThat(UpcomingReminderPlanner.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(24))
    }
}
