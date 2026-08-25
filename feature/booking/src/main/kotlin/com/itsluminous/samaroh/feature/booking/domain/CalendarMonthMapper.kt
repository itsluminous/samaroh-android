package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Maps a month's bookings and date blocks onto a week-row grid the calendar screen can
 * render directly (§4.1): status pills, multi-day spanning bars, grey-striped blocks,
 * the today outline. Pure and fully unit-tested — no Android types.
 */
object CalendarMonthMapper {
    /** One rendered cell of the month grid. */
    data class Day(
        val date: LocalDate,
        val inMonth: Boolean,
        val isToday: Boolean,
        val isBlocked: Boolean,
    )

    /**
     * A booking's segment within ONE week row. [startCol]/[endCol] are 0-based day
     * columns (inclusive); a single-day booking is a one-column segment. A booking
     * spanning multiple weeks produces one segment per week, chained via
     * [continuesBefore]/[continuesAfter].
     */
    data class Segment(
        val bookingId: String,
        val label: String,
        val status: BookingStatus,
        val startCol: Int,
        val endCol: Int,
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    )

    /** One week row: exactly 7 days plus the booking segments to draw across them. */
    data class Week(
        val days: List<Day>,
        val segments: List<Segment>,
    )

    data class MonthGrid(
        val month: YearMonth,
        val weeks: List<Week>,
    )

    /**
     * Builds the grid. Cancelled bookings are HIDDEN from the calendar (§4.1 — they stay
     * visible, struck through, in the agenda list only). [firstDayOfWeek] defaults to
     * Sunday, the common Indian wall-calendar convention.
     */
    fun map(
        month: YearMonth,
        today: LocalDate,
        bookings: List<Booking>,
        blocks: List<DateBlock>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    ): MonthGrid {
        val visible = bookings.filter { it.status != BookingStatus.CANCELLED && it.deletedAt == null }
        val liveBlocks = blocks.filter { it.deletedAt == null }

        val firstOfMonth = month.atDay(1)
        val gridStart = firstOfMonth.minusDays(((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong())
        val lastOfMonth = month.atEndOfMonth()

        val weeks = mutableListOf<Week>()
        var weekStart = gridStart
        while (weekStart <= lastOfMonth) {
            val weekEnd = weekStart.plusDays(6)
            val days =
                (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    Day(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isBlocked = liveBlocks.any { date in it.startDate..it.endDate },
                    )
                }
            val segments =
                visible
                    .filter { it.startDate <= weekEnd && it.endDate >= weekStart }
                    .sortedWith(compareBy({ it.startDate }, { it.createdAt }))
                    .map { booking ->
                        val segStart = maxOf(booking.startDate, weekStart)
                        val segEnd = minOf(booking.endDate, weekEnd)
                        Segment(
                            bookingId = booking.id,
                            label = "${booking.eventIcon} ${BookingTitleFormatter.firstName(booking.customerName)}",
                            status = booking.status,
                            startCol = weekStart.until(segStart).days,
                            endCol = weekStart.until(segEnd).days,
                            continuesBefore = booking.startDate < weekStart,
                            continuesAfter = booking.endDate > weekEnd,
                        )
                    }
            weeks += Week(days, segments)
            weekStart = weekStart.plusDays(7)
        }
        return MonthGrid(month, weeks)
    }

    /** Live, non-cancelled bookings covering [date] — drives tap-routing and conflicts. */
    fun bookingsOn(
        bookings: List<Booking>,
        date: LocalDate,
    ): List<Booking> =
        bookings.filter {
            it.deletedAt == null && it.status != BookingStatus.CANCELLED && date in it.startDate..it.endDate
        }

    /** Live blocks covering [date]. */
    fun blocksOn(
        blocks: List<DateBlock>,
        date: LocalDate,
    ): List<DateBlock> = blocks.filter { it.deletedAt == null && date in it.startDate..it.endDate }
}
