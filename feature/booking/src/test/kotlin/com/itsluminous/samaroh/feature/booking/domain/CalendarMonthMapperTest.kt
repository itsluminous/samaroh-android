package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.TENTATIVE_ICON
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Calendar month mapping (§4.1): grid shape, pills, spanning bars, blocks, today. */
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
    fun `single day booking maps to a one column segment with first name label`() {
        val booking = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10)) // Thursday
        val grid = CalendarMonthMapper.map(month, today, listOf(booking.copy(customerName = "Asha Devi")), emptyList())
        val segment = grid.weeks.flatMap { it.segments }.single()
        assertThat(segment.startCol).isEqualTo(4) // Sun=0 … Thu=4
        assertThat(segment.endCol).isEqualTo(4)
        assertThat(segment.continuesBefore).isFalse()
        assertThat(segment.continuesAfter).isFalse()
        assertThat(segment.label).isEqualTo("${booking.eventIcon} Asha")
    }

    @Test
    fun `multi day booking spans within a week`() {
        val booking =
            Fixtures.booking(
                startDate = LocalDate.of(2026, 9, 8), // Tuesday
                endDate = LocalDate.of(2026, 9, 10), // Thursday
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(booking), emptyList())
        val segment = grid.weeks.flatMap { it.segments }.single()
        assertThat(segment.startCol).isEqualTo(2)
        assertThat(segment.endCol).isEqualTo(4)
    }

    @Test
    fun `booking spanning a week boundary produces chained segments`() {
        val booking =
            Fixtures.booking(
                startDate = LocalDate.of(2026, 9, 11), // Friday of week 2
                endDate = LocalDate.of(2026, 9, 14), // Monday of week 3
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(booking), emptyList())
        val segments = grid.weeks.flatMap { it.segments }
        assertThat(segments).hasSize(2)
        val (first, second) = segments
        assertThat(first.startCol).isEqualTo(5) // Friday
        assertThat(first.endCol).isEqualTo(6) // Saturday
        assertThat(first.continuesBefore).isFalse()
        assertThat(first.continuesAfter).isTrue()
        assertThat(second.startCol).isEqualTo(0) // Sunday
        assertThat(second.endCol).isEqualTo(1) // Monday
        assertThat(second.continuesBefore).isTrue()
        assertThat(second.continuesAfter).isFalse()
    }

    @Test
    fun `cancelled bookings are hidden from the grid`() {
        val cancelled = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.CANCELLED)
        val grid = CalendarMonthMapper.map(month, today, listOf(cancelled), emptyList())
        assertThat(grid.weeks.flatMap { it.segments }).isEmpty()
    }

    @Test
    fun `tentative bookings keep their status on the segment`() {
        val tentative = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.TENTATIVE)
        val grid = CalendarMonthMapper.map(month, today, listOf(tentative), emptyList())
        assertThat(
            grid.weeks
                .flatMap { it.segments }
                .single()
                .status,
        ).isEqualTo(BookingStatus.TENTATIVE)
    }

    @Test
    fun `booked dates carry event icons instead of only the date number`() {
        val single = Fixtures.booking(id = "b1", startDate = LocalDate.of(2026, 9, 10))
        val spanning =
            Fixtures.booking(
                id = "b2",
                startDate = LocalDate.of(2026, 9, 9),
                endDate = LocalDate.of(2026, 9, 11),
            )
        val grid = CalendarMonthMapper.map(month, today, listOf(single, spanning), emptyList())
        val days = grid.weeks.flatMap { it.days }.associateBy { it.date }
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
    fun `tentative bookings render the tentative icon in day cells and segment labels`() {
        val tentative =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 9, 10), status = BookingStatus.TENTATIVE)
                .copy(customerName = "Asha Devi")
        val grid = CalendarMonthMapper.map(month, today, listOf(tentative), emptyList())
        val day = grid.weeks.flatMap { it.days }.first { it.date == LocalDate.of(2026, 9, 10) }
        assertThat(day.eventIcons).containsExactly(TENTATIVE_ICON)
        assertThat(
            grid.weeks
                .flatMap { it.segments }
                .single()
                .label,
        ).isEqualTo("$TENTATIVE_ICON Asha")
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
