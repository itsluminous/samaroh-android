package com.itsluminous.samaroh.feature.reports.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/*
 * ADR-027: personal (non-business-related) parties are EXCLUDED from the money reports
 * (expense summary + profit) and listed exclusively by the Personal-expenses report;
 * the two sides are exact complements. Plus the total-row math of every money table.
 */
class PersonalPartyExclusionTest {
    private val range = ReportDateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30))
    private val zone = ZoneOffset.UTC
    private val personalIds = setOf("p-personal")
    private val names = mapOf("p-business" to "Caterer", "p-personal" to "Family friend")

    private val businessPaid = Fixtures.expense(partyId = "p-business", amountPaise = 700_00L, expenseDate = LocalDate.of(2026, 8, 10))
    private val personalPaid = Fixtures.expense(partyId = "p-personal", amountPaise = 300_00L, expenseDate = LocalDate.of(2026, 8, 12))
    private val personalReceived =
        Fixtures.expense(
            partyId = "p-personal",
            direction = ExpenseDirection.RECEIVED,
            amountPaise = 50_00L,
            expenseDate = LocalDate.of(2026, 9, 3),
        )
    private val all = listOf(businessPaid, personalPaid, personalReceived)

    @Test
    fun `expense summary per-party rows exclude personal parties`() {
        val rows = ExpenseSummaryCalculator.calculate(all, names, "?", personalIds)

        assertThat(rows.map { it.partyId }).containsExactly("p-business")
        assertThat(rows.single().spendPaise).isEqualTo(700_00L)
    }

    @Test
    fun `expense summary monthly ledger excludes personal entries`() {
        val months = ExpenseSummaryCalculator.byMonth(all, emptyList(), range, zone, personalIds)

        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.ledgerPaise).isEqualTo(700_00L)
        assertThat(months.first { it.month == YearMonth.of(2026, 9) }.ledgerPaise).isEqualTo(0L)
    }

    @Test
    fun `profit expenses exclude personal entries both directions`() {
        val months = ProfitCalculator.calculate(emptyList(), all, emptyList(), range, zone, personalIds)

        assertThat(months.first { it.month == YearMonth.of(2026, 8) }.expensePaise).isEqualTo(700_00L)
        // The personal RECEIVED entry must not reduce September expenses either.
        assertThat(months.first { it.month == YearMonth.of(2026, 9) }.expensePaise).isEqualTo(0L)
    }

    @Test
    fun `no personal parties means nothing changes`() {
        val withFlag = ExpenseSummaryCalculator.calculate(all, names, "?", emptySet())
        val without = ExpenseSummaryCalculator.calculate(all, names, "?")

        assertThat(withFlag).isEqualTo(without)
    }
}

class PersonalExpensesCalculatorTest {
    private val range = ReportDateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30))
    private val personalIds = setOf("p-personal")
    private val names = mapOf("p-personal" to "Family friend")

    @Test
    fun `lists only personal parties' entries, netted per month per party`() {
        val expenses =
            listOf(
                Fixtures.expense(partyId = "p-business", amountPaise = 700_00L, expenseDate = LocalDate.of(2026, 8, 10)),
                Fixtures.expense(partyId = "p-personal", amountPaise = 300_00L, expenseDate = LocalDate.of(2026, 8, 12)),
                Fixtures.expense(
                    partyId = "p-personal",
                    direction = ExpenseDirection.RECEIVED,
                    amountPaise = 100_00L,
                    expenseDate = LocalDate.of(2026, 8, 20),
                ),
                Fixtures.expense(partyId = "p-personal", amountPaise = 40_00L, expenseDate = LocalDate.of(2026, 9, 1)),
            )

        val rows = PersonalExpensesCalculator.calculate(expenses, personalIds, names, "?", range)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].month).isEqualTo(YearMonth.of(2026, 8))
        assertThat(rows[0].partyName).isEqualTo("Family friend")
        assertThat(rows[0].netPaise).isEqualTo(200_00L)
        assertThat(rows[1].month).isEqualTo(YearMonth.of(2026, 9))
        assertThat(rows[1].netPaise).isEqualTo(40_00L)
    }

    @Test
    fun `entries outside the range and deleted entries are dropped`() {
        val expenses =
            listOf(
                Fixtures.expense(partyId = "p-personal", amountPaise = 10_00L, expenseDate = LocalDate.of(2026, 7, 31)),
                Fixtures
                    .expense(partyId = "p-personal", amountPaise = 20_00L, expenseDate = LocalDate.of(2026, 8, 5))
                    .copy(deletedAt = java.time.Instant.parse("2026-08-06T00:00:00Z")),
            )

        val rows = PersonalExpensesCalculator.calculate(expenses, personalIds, names, "?", range)

        assertThat(rows).isEmpty()
    }

    @Test
    fun `exclusion and personal report are exact complements`() {
        val expenses =
            listOf(
                Fixtures.expense(partyId = "p-business", amountPaise = 700_00L, expenseDate = LocalDate.of(2026, 8, 10)),
                Fixtures.expense(partyId = "p-personal", amountPaise = 300_00L, expenseDate = LocalDate.of(2026, 8, 12)),
            )

        val businessRows = ExpenseSummaryCalculator.calculate(expenses, emptyMap(), "?", personalIds)
        val personalRows = PersonalExpensesCalculator.calculate(expenses, personalIds, emptyMap(), "?", range)

        val businessTotal = businessRows.sumOf { it.spendPaise }
        val personalTotal = ReportTotals.personalExpensesPaise(personalRows)
        assertThat(businessTotal + personalTotal).isEqualTo(1000_00L)
    }
}

class ReportTotalsTest {
    @Test
    fun `revenue totals sum collected and outstanding across months`() {
        val months =
            listOf(
                RevenueMonth(YearMonth.of(2026, 8), collectedPaise = 100_00L, outstandingPaise = 50_00L),
                RevenueMonth(YearMonth.of(2026, 9), collectedPaise = 200_00L, outstandingPaise = 25_00L),
            )

        val total = ReportTotals.revenue(months)

        assertThat(total.collectedPaise).isEqualTo(300_00L)
        assertThat(total.outstandingPaise).isEqualTo(75_00L)
        assertThat(total.totalPaise).isEqualTo(375_00L)
    }

    @Test
    fun `profit total nets total income against total expense`() {
        val months =
            listOf(
                ProfitMonth(YearMonth.of(2026, 8), incomePaise = 500_00L, expensePaise = 200_00L),
                ProfitMonth(YearMonth.of(2026, 9), incomePaise = 100_00L, expensePaise = 250_00L),
            )

        val total = ReportTotals.profit(months)

        assertThat(total.incomePaise).isEqualTo(600_00L)
        assertThat(total.expensePaise).isEqualTo(450_00L)
        assertThat(total.netPaise).isEqualTo(150_00L)
    }

    @Test
    fun `expense totals sum ledger and inventory columns`() {
        val months =
            listOf(
                ExpenseMonth(YearMonth.of(2026, 8), ledgerPaise = 70_00L, inventoryPaise = 30_00L),
                ExpenseMonth(YearMonth.of(2026, 9), ledgerPaise = 10_00L, inventoryPaise = 0L),
            )

        val total = ReportTotals.expenses(months)

        assertThat(total.ledgerPaise).isEqualTo(80_00L)
        assertThat(total.inventoryPaise).isEqualTo(30_00L)
        assertThat(total.totalPaise).isEqualTo(110_00L)
    }

    @Test
    fun `aging, valuation, breakdown and party-spend totals sum their money columns`() {
        val aging =
            listOf(
                AgingEntry(Fixtures.booking(), duePaise = 40_00L, daysOverdue = 3),
                AgingEntry(Fixtures.booking(), duePaise = 60_00L, daysOverdue = 45),
            )
        assertThat(ReportTotals.agingDuePaise(aging)).isEqualTo(100_00L)

        val valuation =
            listOf(
                ValuationRow("i-1", "Spoon", "pc", 10.0, valuePaise = 55_00L),
                ValuationRow("i-2", "Plate", "pc", 4.0, valuePaise = 45_00L),
            )
        assertThat(ReportTotals.valuationPaise(valuation)).isEqualTo(100_00L)

        val eventRows =
            listOf(
                EventTypeRow("wedding", "💍", bookings = 2, revenuePaise = 700_00L),
                EventTypeRow("birthday", "🎂", bookings = 1, revenuePaise = 300_00L),
            )
        val eventTotal = ReportTotals.eventTypes(eventRows)
        assertThat(eventTotal.bookings).isEqualTo(3)
        assertThat(eventTotal.revenuePaise).isEqualTo(1000_00L)

        val partyRows =
            listOf(
                PartyExpenseRow("p-1", "Caterer", spendPaise = 90_00L),
                PartyExpenseRow("p-2", "Decorator", spendPaise = -10_00L),
            )
        assertThat(ReportTotals.partySpendPaise(partyRows)).isEqualTo(80_00L)
    }
}
