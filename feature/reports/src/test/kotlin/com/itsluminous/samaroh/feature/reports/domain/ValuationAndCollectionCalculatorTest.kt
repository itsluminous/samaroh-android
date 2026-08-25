package com.itsluminous.samaroh.feature.reports.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class InventoryValuationCalculatorTest {
    private fun line(
        id: String,
        quantity: Double,
        valuePaise: Long,
    ) = CurrentInventoryLine(
        masterItemId = id,
        name = "item-$id",
        unit = "pcs",
        imagePath = null,
        currentQuantity = quantity,
        totalValuePaise = valuePaise,
        lastTransactionAt = Fixtures.NOW,
    )

    @Test
    fun `sorts by value descending`() {
        val rows =
            InventoryValuationCalculator.calculate(
                listOf(line("a", 5.0, 700_00L), line("b", 2.0, 5_000_00L), line("c", 9.0, 40_00L)),
            )

        assertThat(rows.map { it.masterItemId }).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun `items with no stock and no value are dropped`() {
        val rows =
            InventoryValuationCalculator.calculate(
                listOf(line("empty", 0.0, 0L), line("held", 3.0, 300_00L)),
            )

        assertThat(rows.map { it.masterItemId }).containsExactly("held")
    }
}

class CollectionEfficiencyCalculatorTest {
    @Test
    fun `average uses the payment date that completed the total`() {
        val booking =
            Fixtures.booking(
                id = "b1",
                startDate = LocalDate.of(2026, 8, 1),
                endDate = LocalDate.of(2026, 8, 2),
                totalAmountPaise = 1_000_00L,
            )
        val payments =
            listOf(
                Fixtures.payment(bookingId = "b1", amountPaise = 400_00L, paidOn = LocalDate.of(2026, 8, 1)),
                Fixtures.payment(bookingId = "b1", amountPaise = 600_00L, paidOn = LocalDate.of(2026, 8, 10)),
            )

        val result = CollectionEfficiencyCalculator.calculate(listOf(booking), payments)

        val entry = result.entries.single()
        assertThat(entry.fullyPaidOn).isEqualTo(LocalDate.of(2026, 8, 10))
        assertThat(entry.daysToFullPayment).isEqualTo(8L)
        assertThat(result.averageDays).isEqualTo(8.0)
        assertThat(result.monthly.single().month).isEqualTo(YearMonth.of(2026, 8))
    }

    @Test
    fun `unpaid, cancelled and zero-total bookings are excluded`() {
        val unpaid = Fixtures.booking(id = "unpaid", totalAmountPaise = 1_000_00L)
        val cancelled = Fixtures.booking(id = "cxl", totalAmountPaise = 1_000_00L, status = BookingStatus.CANCELLED)
        val zeroTotal = Fixtures.booking(id = "zero", totalAmountPaise = 0L)
        val payments =
            listOf(
                Fixtures.payment(bookingId = "unpaid", amountPaise = 100_00L),
                Fixtures.payment(bookingId = "cxl", amountPaise = 1_000_00L),
            )

        val result = CollectionEfficiencyCalculator.calculate(listOf(unpaid, cancelled, zeroTotal), payments)

        assertThat(result.entries).isEmpty()
        assertThat(result.averageDays).isNull()
    }

    @Test
    fun `payment before the event end counts as zero days`() {
        val booking =
            Fixtures.booking(
                id = "early",
                startDate = LocalDate.of(2026, 9, 10),
                endDate = LocalDate.of(2026, 9, 11),
                totalAmountPaise = 500_00L,
            )
        val payments = listOf(Fixtures.payment(bookingId = "early", amountPaise = 500_00L, paidOn = LocalDate.of(2026, 9, 1)))

        val result = CollectionEfficiencyCalculator.calculate(listOf(booking), payments)

        assertThat(result.entries.single().daysToFullPayment).isEqualTo(0L)
    }
}

class DateRangesTest {
    private val today = LocalDate.of(2026, 8, 25)

    @Test
    fun `this month spans the whole current calendar month`() {
        val range = DateRanges.forPreset(RangePreset.THIS_MONTH, today)

        assertThat(range.start).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(range.end).isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `last 12 months starts at the first of the month 11 months back`() {
        val range = DateRanges.forPreset(RangePreset.LAST_12_MONTHS, today)

        assertThat(range.start).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(range.end).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(range.months()).hasSize(12)
    }
}
