package com.itsluminous.samaroh.feature.reports.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class RevenueSummaryCalculatorTest {
    private val range = ReportDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))

    @Test
    fun `groups collected and outstanding by the booking's start month`() {
        val july = Fixtures.booking(startDate = LocalDate.of(2026, 7, 10), totalAmountPaise = 2_00_000_00L)
        val september = Fixtures.booking(startDate = LocalDate.of(2026, 9, 5), totalAmountPaise = 1_00_000_00L)
        val payments =
            listOf(
                Fixtures.payment(bookingId = july.id, amountPaise = 50_000_00L),
                Fixtures.payment(bookingId = september.id, amountPaise = 1_00_000_00L),
            )

        val months = RevenueSummaryCalculator.calculate(listOf(july, september), payments, range)

        assertThat(months.map { it.month })
            .containsExactly(
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 8),
                YearMonth.of(2026, 9),
            ).inOrder()
        assertThat(months[0].collectedPaise).isEqualTo(50_000_00L)
        assertThat(months[0].outstandingPaise).isEqualTo(1_50_000_00L)
        assertThat(months[1].totalPaise).isEqualTo(0L)
        assertThat(months[2].collectedPaise).isEqualTo(1_00_000_00L)
        assertThat(months[2].outstandingPaise).isEqualTo(0L)
    }

    @Test
    fun `excludes cancelled bookings and clamps overpayment`() {
        val cancelled =
            Fixtures.booking(
                startDate = LocalDate.of(2026, 7, 10),
                totalAmountPaise = 9_99_999_00L,
                status = BookingStatus.CANCELLED,
            )
        val overpaid = Fixtures.booking(startDate = LocalDate.of(2026, 7, 20), totalAmountPaise = 1_00_000_00L)
        val payments = listOf(Fixtures.payment(bookingId = overpaid.id, amountPaise = 1_20_000_00L))

        val months = RevenueSummaryCalculator.calculate(listOf(cancelled, overpaid), payments, range)

        // Collected caps at the booking total; outstanding never goes negative.
        assertThat(months[0].collectedPaise).isEqualTo(1_00_000_00L)
        assertThat(months[0].outstandingPaise).isEqualTo(0L)
    }

    @Test
    fun `bookings starting outside the range do not contribute`() {
        val outside = Fixtures.booking(startDate = LocalDate.of(2026, 6, 28), endDate = LocalDate.of(2026, 7, 2))

        val months = RevenueSummaryCalculator.calculate(listOf(outside), emptyList(), range)

        assertThat(months.all { it.totalPaise == 0L }).isTrue()
    }
}

class DuesAgingCalculatorTest {
    private val today = LocalDate.of(2026, 8, 25)

    @Test
    fun `buckets by days since event end with inclusive boundaries`() {
        val d7 = Fixtures.booking(id = "b7", startDate = today.minusDays(7), totalAmountPaise = 100_00L)
        val d8 = Fixtures.booking(id = "b8", startDate = today.minusDays(8), totalAmountPaise = 100_00L)
        val d30 = Fixtures.booking(id = "b30", startDate = today.minusDays(30), totalAmountPaise = 100_00L)
        val d31 = Fixtures.booking(id = "b31", startDate = today.minusDays(31), totalAmountPaise = 100_00L)
        val d90 = Fixtures.booking(id = "b90", startDate = today.minusDays(90), totalAmountPaise = 100_00L)
        val d91 = Fixtures.booking(id = "b91", startDate = today.minusDays(91), totalAmountPaise = 100_00L)

        val entries = DuesAgingCalculator.calculate(listOf(d7, d8, d30, d31, d90, d91), emptyList(), today)

        val buckets = entries.associate { it.booking.id to it.bucket }
        assertThat(buckets["b7"]).isEqualTo(AgingBucket.DAYS_0_7)
        assertThat(buckets["b8"]).isEqualTo(AgingBucket.DAYS_8_30)
        assertThat(buckets["b30"]).isEqualTo(AgingBucket.DAYS_8_30)
        assertThat(buckets["b31"]).isEqualTo(AgingBucket.DAYS_31_90)
        assertThat(buckets["b90"]).isEqualTo(AgingBucket.DAYS_31_90)
        assertThat(buckets["b91"]).isEqualTo(AgingBucket.DAYS_90_PLUS)
    }

    @Test
    fun `fully paid and cancelled bookings never appear`() {
        val paid = Fixtures.booking(id = "paid", startDate = today.minusDays(10), totalAmountPaise = 500_00L)
        val cancelled =
            Fixtures.booking(id = "cxl", startDate = today.minusDays(10), totalAmountPaise = 500_00L, status = BookingStatus.CANCELLED)
        val payments = listOf(Fixtures.payment(bookingId = "paid", amountPaise = 500_00L))

        val entries = DuesAgingCalculator.calculate(listOf(paid, cancelled), payments, today)

        assertThat(entries).isEmpty()
    }

    @Test
    fun `future events count as zero days overdue`() {
        val upcoming = Fixtures.booking(startDate = today.plusDays(5), totalAmountPaise = 100_00L)

        val entries = DuesAgingCalculator.calculate(listOf(upcoming), emptyList(), today)

        assertThat(entries.single().daysOverdue).isEqualTo(0L)
        assertThat(entries.single().bucket).isEqualTo(AgingBucket.DAYS_0_7)
    }

    @Test
    fun `bucket totals sum dues and carry all four buckets`() {
        val old = Fixtures.booking(id = "old", startDate = today.minusDays(120), totalAmountPaise = 300_00L)
        val fresh = Fixtures.booking(id = "new", startDate = today.minusDays(2), totalAmountPaise = 200_00L)
        val partial = listOf(Fixtures.payment(bookingId = "new", amountPaise = 50_00L))

        val totals = DuesAgingCalculator.bucketTotals(DuesAgingCalculator.calculate(listOf(old, fresh), partial, today))

        assertThat(totals.keys).containsExactlyElementsIn(AgingBucket.entries)
        assertThat(totals[AgingBucket.DAYS_90_PLUS]).isEqualTo(300_00L)
        assertThat(totals[AgingBucket.DAYS_0_7]).isEqualTo(150_00L)
        assertThat(totals[AgingBucket.DAYS_8_30]).isEqualTo(0L)
    }
}
