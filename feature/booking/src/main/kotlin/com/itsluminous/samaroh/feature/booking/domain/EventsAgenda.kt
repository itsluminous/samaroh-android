package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure logic of the full agenda ("Events view", §4.1): windowed loading over
 * `bookingsBetween` plus date-grouped rows. The window starts around today and grows in
 * [WINDOW_STEP_MONTHS] steps as the user scrolls toward either edge, clamped to the
 * business's actual booking date bounds — so the list covers every booking without ever
 * loading them all eagerly.
 */
object EventsAgenda {
    /** Months loaded behind/ahead of today on first open. */
    const val INITIAL_PAST_MONTHS = 2L
    const val INITIAL_FUTURE_MONTHS = 4L

    /** Months added per expansion when the user nears the loaded window's edge. */
    const val WINDOW_STEP_MONTHS = 6L

    /** Inclusive date window the agenda currently has loaded. */
    data class Window(
        val from: LocalDate,
        val to: LocalDate,
    )

    /** One date group: header date plus the bookings STARTING on it, list order. */
    data class Day(
        val date: LocalDate,
        val bookings: List<Booking>,
    )

    /** Initial today-centred window; expansion (not this) is clamped to the booking bounds. */
    fun initialWindow(today: LocalDate): Window =
        Window(
            from = YearMonth.from(today).minusMonths(INITIAL_PAST_MONTHS).atDay(1),
            to = YearMonth.from(today).plusMonths(INITIAL_FUTURE_MONTHS).atEndOfMonth(),
        )

    /** Grows [window] one step into the past, clamped to [bounds]; null when exhausted. */
    fun expandPast(
        window: Window,
        bounds: ClosedRange<LocalDate>?,
    ): Window? {
        if (bounds == null || window.from <= bounds.start) return null
        val newFrom = YearMonth.from(window.from).minusMonths(WINDOW_STEP_MONTHS).atDay(1)
        return window.copy(from = maxOf(newFrom, YearMonth.from(bounds.start).atDay(1)))
    }

    /** Grows [window] one step into the future, clamped to [bounds]; null when exhausted. */
    fun expandFuture(
        window: Window,
        bounds: ClosedRange<LocalDate>?,
    ): Window? {
        if (bounds == null || window.to >= bounds.endInclusive) return null
        val newTo = YearMonth.from(window.to).plusMonths(WINDOW_STEP_MONTHS).atEndOfMonth()
        return window.copy(to = minOf(newTo, YearMonth.from(bounds.endInclusive).atEndOfMonth()))
    }

    /** True when bookings older than [window] exist (an upward scroll can load more). */
    fun hasMorePast(
        window: Window,
        bounds: ClosedRange<LocalDate>?,
    ): Boolean = bounds != null && bounds.start < window.from

    /** True when bookings newer than [window] exist. */
    fun hasMoreFuture(
        window: Window,
        bounds: ClosedRange<LocalDate>?,
    ): Boolean = bounds != null && bounds.endInclusive > window.to

    /**
     * Groups [bookings] by START date, ascending — one header per date. Cancelled
     * bookings stay (struck through, month-agenda parity); tombstoned rows never
     * reach here (the DAO filters them).
     */
    fun groupByDate(bookings: List<Booking>): List<Day> =
        bookings
            .sortedWith(compareBy({ it.startDate }, { it.createdAt }))
            .groupBy { it.startDate }
            .map { (date, dayBookings) -> Day(date, dayBookings) }
            .sortedBy { it.date }

    /**
     * Index (in [days]) of the group the list should initially anchor on: the first day
     * on/after [today], else the last past day (list end). -1 when empty.
     */
    fun todayAnchorIndex(
        days: List<Day>,
        today: LocalDate,
    ): Int {
        if (days.isEmpty()) return -1
        val upcoming = days.indexOfFirst { it.date >= today }
        return if (upcoming >= 0) upcoming else days.lastIndex
    }

    /**
     * [todayAnchorIndex] flattened onto the LazyColumn item space where every day
     * contributes one header item followed by its booking rows. -1 when empty.
     */
    fun flatAnchorIndex(
        days: List<Day>,
        today: LocalDate,
    ): Int {
        val dayIndex = todayAnchorIndex(days, today)
        if (dayIndex < 0) return -1
        return days.take(dayIndex).sumOf { 1 + it.bookings.size }
    }
}
