package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Pure logic for upcoming-event reminders (§4.1): which bookings to remind about today
 * given the configured lead days, and when the daily 09:00 job should next run.
 */
object UpcomingReminderPlanner {
    /** The daily reminder job's local fire time (§4.1: WorkManager daily at 09:00). */
    val DAILY_RUN_TIME: LocalTime = LocalTime.of(9, 0)

    data class UpcomingReminder(
        val booking: Booking,
        /** How many days away the event start is (equals the matched lead-day value). */
        val daysAway: Int,
    )

    /**
     * Pairs each lead day with the date whose bookings should be reminded about today
     * (start_date == today + lead).
     */
    fun reminderDates(
        today: LocalDate,
        leadDays: Set<Int>,
    ): Map<Int, LocalDate> = leadDays.filter { it > 0 }.associateWith { today.plusDays(it.toLong()) }

    /** Builds the reminder list from bookings already fetched per lead-day date. */
    fun remindersFor(bookingsByDaysAway: Map<Int, List<Booking>>): List<UpcomingReminder> =
        bookingsByDaysAway
            .flatMap { (daysAway, bookings) -> bookings.map { UpcomingReminder(it, daysAway) } }
            .sortedWith(compareBy({ it.daysAway }, { it.booking.startDate }))

    /**
     * Delay from [now] until the next local 09:00 — the periodic worker's initial delay.
     * At exactly 09:00 the job runs the following day (a run is assumed to be in flight).
     */
    fun delayUntilNextRun(now: ZonedDateTime): Duration {
        val todayRun = now.toLocalDate().atTime(DAILY_RUN_TIME).atZone(now.zone)
        val next = if (now.isBefore(todayRun)) todayRun else todayRun.plusDays(1)
        return Duration.between(now, next)
    }
}
