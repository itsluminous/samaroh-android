package com.itsluminous.samaroh.feature.reports.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

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

class ExpenseSummaryByMonthTest {
    private val zone = ZoneOffset.UTC
    private val range = ReportDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31))

    @Test
    fun `inventory purchases bucket by transaction month at quantity times unit price`() {
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 10.0,
                    unitPricePaise = 50_00L,
                    transactionDate = Instant.parse("2026-07-05T09:00:00Z"),
                ),
                Fixtures.inventoryTxn(
                    masterItemId = "oil",
                    quantity = 2.0,
                    unitPricePaise = 150_00L,
                    transactionDate = Instant.parse("2026-08-20T09:00:00Z"),
                ),
            )

        val months = ExpenseSummaryCalculator.byMonth(emptyList(), purchases, range, zone)

        assertThat(months.first { it.month == YearMonth.of(2026, 7) }.inventoryPaise).isEqualTo(500_00L)
        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.inventoryPaise).isEqualTo(300_00L)
    }

    @Test
    fun `mixed ledger and inventory sum into the month total, received entries stay out of the ledger column`() {
        val expenses =
            listOf(
                Fixtures.expense(partyId = "caterer", amountPaise = 10_000_00L, expenseDate = LocalDate.of(2026, 8, 3)),
                Fixtures.expense(
                    partyId = "caterer",
                    amountPaise = 2_000_00L,
                    expenseDate = LocalDate.of(2026, 8, 4),
                    direction = ExpenseDirection.RECEIVED,
                ),
            )
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 4.0,
                    unitPricePaise = 25_000_00L,
                    transactionDate = Instant.parse("2026-08-10T09:00:00Z"),
                ),
            )

        val august = ExpenseSummaryCalculator.byMonth(expenses, purchases, range, zone).first { it.month == YearMonth.of(2026, 8) }

        assertThat(august.ledgerPaise).isEqualTo(10_000_00L)
        assertThat(august.inventoryPaise).isEqualTo(1_00_000_00L)
        assertThat(august.totalPaise).isEqualTo(1_10_000_00L)
    }

    @Test
    fun `months without inventory purchases report zero but stay present`() {
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 1.0,
                    unitPricePaise = 100_00L,
                    transactionDate = Instant.parse("2026-08-01T09:00:00Z"),
                ),
            )

        val months = ExpenseSummaryCalculator.byMonth(emptyList(), purchases, range, zone)

        assertThat(months.map { it.month }).containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8)).inOrder()
        assertThat(months.first { it.month == YearMonth.of(2026, 7) }.inventoryPaise).isEqualTo(0L)
        assertThat(months.first { it.month == YearMonth.of(2026, 7) }.totalPaise).isEqualTo(0L)
    }

    @Test
    fun `fractional quantities round to whole paise per transaction`() {
        // 2.5 kg × ₹10.99 = 2747.5 paise → 2748; twice on one month sums the rounded values.
        val purchases =
            (1..2).map { index ->
                Fixtures.inventoryTxn(
                    masterItemId = "spice-$index",
                    quantity = 2.5,
                    unitPricePaise = 10_99L,
                    transactionDate = Instant.parse("2026-07-0${index}T09:00:00Z"),
                )
            }

        val july = ExpenseSummaryCalculator.byMonth(emptyList(), purchases, range, zone).first { it.month == YearMonth.of(2026, 7) }

        assertThat(july.inventoryPaise).isEqualTo(2748L * 2)
    }

    @Test
    fun `remove transactions, tombstoned rows and out-of-range purchases are ignored`() {
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    type = TxnType.REMOVE,
                    quantity = 5.0,
                    unitPricePaise = 100_00L,
                    transactionDate = Instant.parse("2026-07-05T09:00:00Z"),
                ),
                Fixtures
                    .inventoryTxn(
                        masterItemId = "rice",
                        quantity = 5.0,
                        unitPricePaise = 100_00L,
                        transactionDate = Instant.parse("2026-07-06T09:00:00Z"),
                    ).copy(deletedAt = Instant.parse("2026-07-07T09:00:00Z")),
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 5.0,
                    unitPricePaise = 100_00L,
                    transactionDate = Instant.parse("2026-06-30T09:00:00Z"),
                ),
            )

        val months = ExpenseSummaryCalculator.byMonth(emptyList(), purchases, range, zone)

        assertThat(months.sumOf { it.inventoryPaise }).isEqualTo(0L)
    }
}

class ProfitCalculatorTest {
    private val zone = ZoneOffset.UTC
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

        val months = ProfitCalculator.calculate(payments, expenses, emptyList(), range, zone)

        val july = months.first { it.month == YearMonth.of(2026, 7) }
        assertThat(july.incomePaise).isEqualTo(1_00_000_00L)
        assertThat(july.expensePaise).isEqualTo(25_000_00L)
        assertThat(july.netPaise).isEqualTo(75_000_00L)
    }

    @Test
    fun `months without activity report zeros and net can go negative`() {
        val expenses = listOf(Fixtures.expense(partyId = "p1", amountPaise = 10_000_00L, expenseDate = LocalDate.of(2026, 8, 5)))

        val months = ProfitCalculator.calculate(emptyList(), expenses, emptyList(), range, zone)

        val july = months.first { it.month == YearMonth.of(2026, 7) }
        val august = months.first { it.month == YearMonth.of(2026, 8) }
        assertThat(july.netPaise).isEqualTo(0L)
        assertThat(august.netPaise).isEqualTo(-10_000_00L)
    }

    @Test
    fun `inventory purchases add to the month's expenses and reduce net`() {
        val payments = listOf(Fixtures.payment(bookingId = "b1", amountPaise = 1_00_000_00L, paidOn = LocalDate.of(2026, 7, 10)))
        val expenses = listOf(Fixtures.expense(partyId = "p1", amountPaise = 20_000_00L, expenseDate = LocalDate.of(2026, 7, 15)))
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 10.0,
                    unitPricePaise = 1_000_00L,
                    transactionDate = Instant.parse("2026-07-20T09:00:00Z"),
                ),
            )

        val july = ProfitCalculator.calculate(payments, expenses, purchases, range, zone).first { it.month == YearMonth.of(2026, 7) }

        assertThat(july.expensePaise).isEqualTo(30_000_00L)
        assertThat(july.netPaise).isEqualTo(70_000_00L)
    }

    @Test
    fun `inventory purchases land in their own transaction month only`() {
        val purchases =
            listOf(
                Fixtures.inventoryTxn(
                    masterItemId = "rice",
                    quantity = 1.0,
                    unitPricePaise = 5_000_00L,
                    transactionDate = Instant.parse("2026-08-31T18:00:00Z"),
                ),
            )

        val months = ProfitCalculator.calculate(emptyList(), emptyList(), purchases, range, zone)

        assertThat(months.first { it.month == YearMonth.of(2026, 7) }.expensePaise).isEqualTo(0L)
        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.expensePaise).isEqualTo(5_000_00L)
        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.netPaise).isEqualTo(-5_000_00L)
    }
}
