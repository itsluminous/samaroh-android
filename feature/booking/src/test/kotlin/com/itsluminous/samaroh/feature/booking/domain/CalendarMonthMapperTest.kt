package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.TENTATIVE_ICON
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Calendar month mapping (§4.1): grid shape, day-cell icons + status flags, blocks, today. */
class CalendarMonthMapperTest {
    private val month = YearMonth.of(2026, 9) // Sep 2026: 1st is a Tuesday
    private val today = LocalDate.of(2026, 9, 10)

    private fun block(
        start: LocalDate,
        end: LocalDate = start,
    ) = DateBlock(
        id = "block-1",
        businessId = Fixtures.BUSINESS_ID,
        startDate = start,
        endDate = end,
        reason = null,
        createdBy = Fixtures.USER_ID,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
    )

    private fun days(grid: CalendarMonthMapper.MonthGrid) = grid.weeks.flatMap { it.days }.associateBy { it.date }

    @Test
    fun `grid starts on Sunday and covers the whole month`() {
        val grid = CalendarMonthMapper.map(month, today, emptyList(), emptyList())
        assertThat(
            grid.weeks
                .first()
                .days
                .first()
                .date,
        ).isEqualTo(LocalDate.of(2026, 8, 30))
        assertThat(grid.weeks.flatMap { it.days }.count { it.inMonth }).isEqualTo(30)
        grid.weeks.forEach { assertThat(it.days).hasSize(7) }
    }

    @Test
    fun `today is outlined only on the matching date`() {
        val grid = CalendarMonthMapper.map(month, today, emptyList(), emptyList())
        val todayCells = grid.weeks.flatMap { it.days }.filter { it.isToday }
        assertThat(todayCells).hasSize(1)
        assertThat(todayCells.single().date).isEqualTo(today)
    }

    @Test
    fun `single day booking marks only its date with icon name and firm status`() {
        val booking = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10)) // Thursday
        val grid = CalendarMonthMapper.map(month, today, listOf(booking.copy(customerName = "Asha Devi")), emptyList())
        val day = days(grid).getValue(LocalDate.of(2026, 9, 10))
        assertThat(day.eventIcons).containsExactly(booking.eventIcon)
        assertThat(day.bookingNames).containsExactly("Asha")
        assertThat(day.hasFirmBooking).isTrue()
        assertThat(day.hasTentativeBooking).isFalse()
        // No other date carries the booking.
        val marked = grid.weeks.flatMap { it.days }.filter { it.eventIcons.isNotEmpty() }
        assertThat(marked.map { it.date }).containsExactly(LocalDate.of(2026, 9, 10))
    }

    @Test
    fun `multi day booking marks every covered date`() {
        val booking =
            Fixtures.booking(
                startDate = LocalDate.of(2026, 9, 8), // Tuesday
                endDate = LocalDate.of(2026, 9, 10), // Thursday
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(booking), emptyList())
        val marked = grid.weeks.flatMap { it.days }.filter { it.eventIcons.isNotEmpty() }
        assertThat(marked.map { it.date })
            .containsExactly(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
            .inOrder()
        marked.forEach { assertThat(it.hasFirmBooking).isTrue() }
    }

    @Test
    fun `booking spanning a week boundary marks dates in both weeks`() {
        val booking =
            Fixtures.booking(
                startDate = LocalDate.of(2026, 9, 11), // Friday of week 2
                endDate = LocalDate.of(2026, 9, 14), // Monday of week 3
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(booking), emptyList())
        val markedByWeek =
            grid.weeks
                .map { week -> week.days.filter { it.eventIcons.isNotEmpty() }.map { it.date } }
                .filter { it.isNotEmpty() }
        assertThat(markedByWeek).hasSize(2)
        assertThat(markedByWeek[0]).containsExactly(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12))
        assertThat(markedByWeek[1]).containsExactly(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 14))
    }

    @Test
    fun `cancelled bookings are hidden from the grid`() {
        val cancelled = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.CANCELLED)
        val grid = CalendarMonthMapper.map(month, today, listOf(cancelled), emptyList())
        grid.weeks.flatMap { it.days }.forEach { day ->
            assertThat(day.eventIcons).isEmpty()
            assertThat(day.bookingNames).isEmpty()
            assertThat(day.hasFirmBooking).isFalse()
            assertThat(day.hasTentativeBooking).isFalse()
        }
    }

    @Test
    fun `tentative bookings flag the day tentative not firm`() {
        val tentative = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.TENTATIVE)
        val grid = CalendarMonthMapper.map(month, today, listOf(tentative), emptyList())
        val day = days(grid).getValue(LocalDate.of(2026, 9, 10))
        assertThat(day.hasTentativeBooking).isTrue()
        assertThat(day.hasFirmBooking).isFalse()
    }

    @Test
    fun `mixed status date carries both firm and tentative flags`() {
        val confirmed = Fixtures.booking(id = "b1", startDate = LocalDate.of(2026, 9, 10))
        val tentative =
            Fixtures.booking(id = "b2", startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.TENTATIVE)
        val grid = CalendarMonthMapper.map(month, today, listOf(confirmed, tentative), emptyList())
        val day = days(grid).getValue(LocalDate.of(2026, 9, 10))
        assertThat(day.hasFirmBooking).isTrue()
        assertThat(day.hasTentativeBooking).isTrue()
        assertThat(day.eventIcons).hasSize(2)
    }

    @Test
    fun `booked dates carry event icons as a watermark behind the date number`() {
        val single = Fixtures.booking(id = "b1", startDate = LocalDate.of(2026, 9, 10))
        val spanning =
            Fixtures.booking(
                id = "b2",
                startDate = LocalDate.of(2026, 9, 9),
                endDate = LocalDate.of(2026, 9, 11),
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(single, spanning), emptyList())
        val days = days(grid)
        // The 10th is covered by BOTH bookings → two icons (spanning starts earlier, so first).
        assertThat(days.getValue(LocalDate.of(2026, 9, 10)).eventIcons)
            .containsExactly(spanning.eventIcon, single.eventIcon)
            .inOrder()
        // The 9th and 11th are covered by the spanning booking only.
        assertThat(days.getValue(LocalDate.of(2026, 9, 9)).eventIcons).containsExactly(spanning.eventIcon)
        // Empty dates keep no icons (the cell renders the date number).
        assertThat(days.getValue(LocalDate.of(2026, 9, 20)).eventIcons).isEmpty()
    }

    @Test
    fun `cancelled bookings contribute no day icons`() {
        val cancelled = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.CANCELLED)
        val grid = CalendarMonthMapper.map(month, today, listOf(cancelled), emptyList())
        assertThat(grid.weeks.flatMap { it.days }.flatMap { it.eventIcons }).isEmpty()
    }

    @Test
    fun `tentative bookings render the tentative icon and carry the first name for a11y`() {
        val tentative =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.TENTATIVE)
                .copy(customerName = "Asha Devi")
        val grid = CalendarMonthMapper.map(month, today, listOf(tentative), emptyList())
        val day = days(grid).getValue(LocalDate.of(2026, 9, 10))
        assertThat(day.eventIcons).containsExactly(TENTATIVE_ICON)
        // The name is announcement-only data — nothing in the grid renders it.
        assertThat(day.bookingNames).containsExactly("Asha")
    }

    @Test
    fun `confirming reverts the icon to the event icon`() {
        val confirmed = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.CONFIRMED)
        val grid = CalendarMonthMapper.map(month, today, listOf(confirmed), emptyList())
        val day = grid.weeks.flatMap { it.days }.first { it.date == LocalDate.of(2026, 9, 10) }
        assertThat(day.eventIcons).containsExactly(confirmed.eventIcon)
    }

    @Test
    fun `date blocks stripe every covered in-month day`() {
        val grid =
            CalendarMonthMapper.map(
                month,
                today,
                emptyList(),
                listOf(block(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 17))),
            )
        val blockedDates =
            grid.weeks
                .flatMap { it.days }
                .filter { it.isBlocked }
                .map { it.date }
        assertThat(blockedDates)
            .containsExactly(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 17))
    }

    @Test
    fun `bookingsOn and blocksOn route taps`() {
        val date = LocalDate.of(2026, 9, 20)
        val booking = Fixtures.booking(startDate = date.minusDays(1), endDate = date.plusDays(1))
        val cancelled = Fixtures.booking(startDate = date, status = BookingStatus.CANCELLED)
        assertThat(CalendarMonthMapper.bookingsOn(listOf(booking, cancelled), date)).containsExactly(booking)
        assertThat(CalendarMonthMapper.blocksOn(listOf(block(date)), date)).hasSize(1)
        assertThat(CalendarMonthMapper.bookingsOn(listOf(booking), date.plusDays(5))).isEmpty()
    }
}
