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
         * for the themed default (ADR-030, rule 5 superseded by ADR-102 /
         * `shared/docs/calendar-day-color.md`). Decided over the FIRM (confirmed/completed)
         * bookings shown on the date — tentative ones never fill — by [fillColorKey]: all
         * the same colour → that colour (two red → red); mixed → the most severe palette
         * colour (lowest `booking-colors.json` index; the themed default ranks last). Never
         * blended. The tentative amber outline + 👤 are unaffected either way.
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
     * [isMarker] flags bookings whose type resolves to a MARKER-kind preset (ADR-041) —
     * callers pass `EventTypeKinds.isMarker`; on a date covered by both real and marker
     * bookings the CELL shows only the real bookings' icons/colour (the day sheet still
     * lists everything via [bookingsOn]); marker-only dates keep the marker's own
     * icon/colour treatment. [paletteOrder] is the palette's key order (severity, ADR-102)
     * — callers pass `BookingColorCatalog.colors.map { it.key }`; keys missing from it rank
     * after every listed key (and still before the themed default).
     */
    fun map(
        month: YearMonth,
        today: LocalDate,
        bookings: List<Booking>,
        blocks: List<DateBlock>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
        isMarker: (Booking) -> Boolean = { false },
        paletteOrder: List<String> = emptyList(),
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
                    // Marker precedence (ADR-041): real bookings own the cell when
                    // present; markers only paint dates nothing real covers.
                    val real = covering.filterNot(isMarker)
                    val shown = real.ifEmpty { covering }
                    Day(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isBlocked = liveBlocks.any { date in it.startDate..it.endDate },
                        eventIcons = shown.map { it.displayIcon },
                        bookingNames = shown.map { BookingTitleFormatter.firstName(it.customerName) },
                        hasFirmBooking = shown.any { it.status != BookingStatus.TENTATIVE },
                        hasTentativeBooking = shown.any { it.status == BookingStatus.TENTATIVE },
                        fillColorKey =
                            fillColorKey(
                                shown.filter { it.status != BookingStatus.TENTATIVE }.map(effectiveColorKey),
                                paletteOrder,
                            ),
                    )
                }
            weeks += Week(days)
            weekStart = weekStart.plusDays(7)
        }
        return MonthGrid(month, weeks)
    }

    /**
     * The ONE colour a day cell shows (ADR-102, shared `calendar-day-color.md`), from the
     * effective colour keys of the FIRM bookings covering it (null = themed default):
     * none → no fill (null); all equal → that key (including all-null → default); mixed →
     * the most severe palette key = lowest index in [paletteOrder] (an unlisted key ranks
     * after every listed one; the default ranks after everything, so a mixed set always
     * yields a palette colour). Never blends, never invents a "multiple" colour.
     */
    fun fillColorKey(
        firmColorKeys: List<String?>,
        paletteOrder: List<String>,
    ): String? {
        if (firmColorKeys.isEmpty()) return null
        val first = firmColorKeys.first()
        if (firmColorKeys.all { it == first }) return first
        return firmColorKeys
            .filterNotNull()
            .minByOrNull { key -> paletteOrder.indexOf(key).let { if (it < 0) paletteOrder.size else it } }
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
