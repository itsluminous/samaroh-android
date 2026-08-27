package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.displayIcon
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Maps a month's bookings and date blocks onto a week-row grid the calendar screen can
 * render directly (§4.1): event icons + status treatment in the day cells,
 * grey-striped blocks, the today outline. Pure and fully unit-tested — no Android types.
 */
object CalendarMonthMapper {
    /** One rendered cell of the month grid. */
    data class Day(
        val date: LocalDate,
        val inMonth: Boolean,
        val isToday: Boolean,
        val isBlocked: Boolean,
        /**
         * Display icons of the live bookings covering this date (booking order:
         * start date, then creation). Non-empty ⇒ the cell renders these icons as a
         * translucent watermark BEHIND the date number; tentative bookings
         * contribute 👤 ([Booking.displayIcon]).
         */
        val eventIcons: List<String> = emptyList(),
        /**
         * Customer first names of the same bookings, in the same order — used ONLY for
         * the TalkBack day-cell announcement. Names are never rendered in the grid;
         * the agenda list below the calendar carries them visually.
         */
        val bookingNames: List<String> = emptyList(),
        /**
         * True when any firm (confirmed/completed) booking covers this date — the cell
         * gets a filled container background behind the icons.
         */
        val hasFirmBooking: Boolean = false,
        /**
         * True when any tentative booking covers this date — the cell gets an amber
         * outline. Both flags can be true on a mixed date.
         */
        val hasTentativeBooking: Boolean = false,
        /**
         * Palette key (`shared/booking-colors.json`) painting the cell's FILL, or null
         * for the default treatment (ADR-030). Set only when EXACTLY ONE live booking
         * covers this date, that booking is firm (confirmed/completed — never
         * tentative), and a colour RESOLVES for it via the fallback chain (ADR-031:
         * explicit `bookings.color` → event-type default → null). Multi-booking days
         * keep the default fill; the tentative amber outline + 👤 are unaffected
         * either way.
         */
        val fillColorKey: String? = null,
    )

    /** One week row: exactly 7 days. */
    data class Week(
        val days: List<Day>,
    )

    data class MonthGrid(
        val month: YearMonth,
        val weeks: List<Week>,
    )

    /**
     * Builds the grid. Cancelled bookings are HIDDEN from the calendar (§4.1 — they stay
     * visible, struck through, in the agenda list only). [firstDayOfWeek] defaults to
     * Sunday, the common Indian wall-calendar convention. [effectiveColorKey] resolves a
     * booking's cell-fill palette key — callers pass [BookingColorFallback.effectiveKey]
     * so type defaults apply (ADR-031); the parameter keeps the mapper pure.
     */
    fun map(
        month: YearMonth,
        today: LocalDate,
        bookings: List<Booking>,
        blocks: List<DateBlock>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
        effectiveColorKey: (Booking) -> String? = { it.color },
    ): MonthGrid {
        val visible = bookings.filter { it.status != BookingStatus.CANCELLED && it.deletedAt == null }
        val liveBlocks = blocks.filter { it.deletedAt == null }

        val firstOfMonth = month.atDay(1)
        val gridStart = firstOfMonth.minusDays(((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong())
        val lastOfMonth = month.atEndOfMonth()

        val weeks = mutableListOf<Week>()
        var weekStart = gridStart
        while (weekStart <= lastOfMonth) {
            val days =
                (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    val covering =
                        visible
                            .filter { date in it.startDate..it.endDate }
                            .sortedWith(compareBy({ it.startDate }, { it.createdAt }))
                    Day(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isBlocked = liveBlocks.any { date in it.startDate..it.endDate },
                        eventIcons = covering.map { it.displayIcon },
                        bookingNames = covering.map { BookingTitleFormatter.firstName(it.customerName) },
                        hasFirmBooking = covering.any { it.status != BookingStatus.TENTATIVE },
                        hasTentativeBooking = covering.any { it.status == BookingStatus.TENTATIVE },
                        fillColorKey =
                            covering
                                .singleOrNull()
                                ?.takeIf { it.status != BookingStatus.TENTATIVE }
                                ?.let(effectiveColorKey),
                    )
                }
            weeks += Week(days)
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
