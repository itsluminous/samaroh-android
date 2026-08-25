package com.itsluminous.samaroh.feature.reports.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class OccupancyCalculatorTest {
    private val range = ReportDateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30))

    @Test
    fun `multi-day bookings count every day and spans split across months`() {
        val spanning = Fixtures.booking(startDate = LocalDate.of(2026, 8, 30), endDate = LocalDate.of(2026, 9, 2))

        val months = OccupancyCalculator.calculate(listOf(spanning), range)

        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.bookedDays).isEqualTo(2)
        assertThat(months.first { it.month == YearMonth.of(2026, 9) }.bookedDays).isEqualTo(2)
    }

    @Test
    fun `overlapping bookings on one date count that date once`() {
        val day = LocalDate.of(2026, 8, 15)
        val first = Fixtures.booking(startDate = day)
        val second = Fixtures.booking(startDate = day)
        val cancelled = Fixtures.booking(startDate = day.plusDays(1), status = BookingStatus.CANCELLED)

        val months = OccupancyCalculator.calculate(listOf(first, second, cancelled), range)

        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.bookedDays).isEqualTo(1)
    }

    @Test
    fun `utilization is booked days over days in month`() {
        val bookings =
            (1..15).map { day ->
                Fixtures.booking(startDate = LocalDate.of(2026, 9, day))
            }

        val september = OccupancyCalculator.calculate(bookings, range).first { it.month == YearMonth.of(2026, 9) }

        assertThat(september.bookedDays).isEqualTo(15)
        assertThat(september.daysInMonth).isEqualTo(30)
        assertThat(september.utilizationPercent).isEqualTo(50)
    }
}

class BreakdownCalculatorsTest {
    private val range = ReportDateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))

    @Test
    fun `event types aggregate count and revenue, sorted by revenue`() {
        val weddingA = Fixtures.booking(startDate = LocalDate.of(2026, 8, 5), totalAmountPaise = 2_00_000_00L)
        val weddingB = Fixtures.booking(startDate = LocalDate.of(2026, 8, 12), totalAmountPaise = 1_00_000_00L)
        val birthday =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 8, 20), totalAmountPaise = 5_00_000_00L)
                .copy(eventType = "birthday", eventIcon = "🎂")

        val rows = EventTypeBreakdownCalculator.calculate(listOf(weddingA, weddingB, birthday), range)

        assertThat(rows.map { it.eventType }).containsExactly("birthday", "wedding").inOrder()
        assertThat(rows[1].bookings).isEqualTo(2)
        assertThat(rows[1].revenuePaise).isEqualTo(3_00_000_00L)
    }

    @Test
    fun `custom event labels keep their own group and icon`() {
        val custom =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 8, 5))
                .copy(eventType = "College Fest", eventIcon = "✨")

        val rows = EventTypeBreakdownCalculator.calculate(listOf(custom), range)

        assertThat(rows.single().eventType).isEqualTo("College Fest")
        assertThat(rows.single().eventIcon).isEqualTo("✨")
    }

    @Test
    fun `sources aggregate and bookings without a source group under null`() {
        val phone =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 8, 5), totalAmountPaise = 1_00_000_00L)
                .copy(source = BookingSource.PHONE)
        val unspecified = Fixtures.booking(startDate = LocalDate.of(2026, 8, 6), totalAmountPaise = 3_00_000_00L)

        val rows = BookingSourceBreakdownCalculator.calculate(listOf(phone, unspecified), range)

        assertThat(rows.map { it.source }).containsExactly(null, BookingSource.PHONE).inOrder()
        assertThat(rows[0].revenuePaise).isEqualTo(3_00_000_00L)
        assertThat(rows[1].bookings).isEqualTo(1)
    }

    @Test
    fun `sources exclude bookings outside the range`() {
        val outside =
            Fixtures
                .booking(startDate = LocalDate.of(2026, 9, 5))
                .copy(source = BookingSource.REFERRAL)

        val rows = BookingSourceBreakdownCalculator.calculate(listOf(outside), range)

        assertThat(rows).isEmpty()
    }
}

class ExpenseSummaryCalculatorTest {
    @Test
    fun `nets paid minus received per party and sorts by spend`() {
        val expenses =
            listOf(
                Fixtures.expense(partyId = "caterer", amountPaise = 10_000_00L),
                Fixtures.expense(partyId = "caterer", amountPaise = 2_000_00L, direction = ExpenseDirection.RECEIVED),
                Fixtures.expense(partyId = "decorator", amountPaise = 20_000_00L),
            )
        val names = mapOf("caterer" to "party-a", "decorator" to "party-b")

        val rows = ExpenseSummaryCalculator.calculate(expenses, names, unknownPartyName = "?")

        assertThat(rows.map { it.partyName }).containsExactly("party-b", "party-a").inOrder()
        assertThat(rows[1].spendPaise).isEqualTo(8_000_00L)
    }

    @Test
    fun `expenses of a deleted party fall back to the unknown name`() {
        val orphan = Fixtures.expense(partyId = "gone", amountPaise = 500_00L)

        val rows = ExpenseSummaryCalculator.calculate(listOf(orphan), emptyMap(), unknownPartyName = "unknown!")

        assertThat(rows.single().partyName).isEqualTo("unknown!")
    }

    @Test
    fun `top limits to the requested count`() {
        val rows =
            (1..12).map { PartyExpenseRow(partyId = "p$it", partyName = "party-$it", spendPaise = it * 100L) }

        assertThat(ExpenseSummaryCalculator.top(rows)).hasSize(10)
        assertThat(ExpenseSummaryCalculator.top(rows, 3)).hasSize(3)
    }
}

class ProfitCalculatorTest {
    private val range = ReportDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31))

    @Test
    fun `income is payments by paid-on month, expenses net paid minus received`() {
        val payments =
            listOf(
                Fixtures.payment(bookingId = "b1", amountPaise = 1_00_000_00L, paidOn = LocalDate.of(2026, 7, 10)),
                Fixtures.payment(bookingId = "b2", amountPaise = 50_000_00L, paidOn = LocalDate.of(2026, 8, 2)),
            )
        val expenses =
            listOf(
                Fixtures.expense(partyId = "p1", amountPaise = 30_000_00L, expenseDate = LocalDate.of(2026, 7, 15)),
                Fixtures.expense(
                    partyId = "p1",
                    amountPaise = 5_000_00L,
                    expenseDate = LocalDate.of(2026, 7, 20),
                    direction = ExpenseDirection.RECEIVED,
                ),
            )

        val months = ProfitCalculator.calculate(payments, expenses, range)

        val july = months.first { it.month == YearMonth.of(2026, 7) }
        assertThat(july.incomePaise).isEqualTo(1_00_000_00L)
        assertThat(july.expensePaise).isEqualTo(25_000_00L)
        assertThat(july.netPaise).isEqualTo(75_000_00L)
    }

    @Test
    fun `months without activity report zeros and net can go negative`() {
        val expenses = listOf(Fixtures.expense(partyId = "p1", amountPaise = 10_000_00L, expenseDate = LocalDate.of(2026, 8, 5)))

        val months = ProfitCalculator.calculate(emptyList(), expenses, range)

        val july = months.first { it.month == YearMonth.of(2026, 7) }
        val august = months.first { it.month == YearMonth.of(2026, 8) }
        assertThat(july.netPaise).isEqualTo(0L)
        assertThat(august.netPaise).isEqualTo(-10_000_00L)
    }
}
