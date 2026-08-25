package com.itsluminous.samaroh.feature.expenses.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

class RunningBalanceCalculatorTest {
    private val partyId = "party-1"

    @Test
    fun `empty ledger has no rows`() {
        assertThat(RunningBalanceCalculator.withRunningBalance(emptyList())).isEmpty()
    }

    @Test
    fun `single paid entry balance equals its amount`() {
        val entry = Fixtures.expense(partyId = partyId, amountPaise = 500_00L, direction = ExpenseDirection.PAID)
        val rows = RunningBalanceCalculator.withRunningBalance(listOf(entry))
        assertThat(rows.single().balanceAfterPaise).isEqualTo(500_00L)
    }

    @Test
    fun `single received entry balance is negative`() {
        val entry = Fixtures.expense(partyId = partyId, amountPaise = 300_00L, direction = ExpenseDirection.RECEIVED)
        val rows = RunningBalanceCalculator.withRunningBalance(listOf(entry))
        assertThat(rows.single().balanceAfterPaise).isEqualTo(-300_00L)
    }

    @Test
    fun `newest-first rows carry the balance after each entry`() {
        // Chronological: gave 1000, got 400, gave 250 → running: 1000, 600, 850.
        val oldest =
            Fixtures.expense(
                partyId = partyId,
                amountPaise = 1_000_00L,
                direction = ExpenseDirection.PAID,
                expenseDate = LocalDate.of(2026, 8, 1),
            )
        val middle =
            Fixtures.expense(
                partyId = partyId,
                amountPaise = 400_00L,
                direction = ExpenseDirection.RECEIVED,
                expenseDate = LocalDate.of(2026, 8, 10),
            )
        val newest =
            Fixtures.expense(
                partyId = partyId,
                amountPaise = 250_00L,
                direction = ExpenseDirection.PAID,
                expenseDate = LocalDate.of(2026, 8, 20),
            )

        val rows = RunningBalanceCalculator.withRunningBalance(listOf(newest, middle, oldest))

        assertThat(rows.map { it.balanceAfterPaise }).containsExactly(850_00L, 600_00L, 1_000_00L).inOrder()
        assertThat(rows.map { it.expense.id }).containsExactly(newest.id, middle.id, oldest.id).inOrder()
    }

    @Test
    fun `newest row balance equals the party net balance`() {
        val entries =
            listOf(
                Fixtures.expense(partyId = partyId, amountPaise = 100_00L, direction = ExpenseDirection.RECEIVED),
                Fixtures.expense(partyId = partyId, amountPaise = 700_00L, direction = ExpenseDirection.PAID),
            )
        val rows = RunningBalanceCalculator.withRunningBalance(entries)
        assertThat(rows.first().balanceAfterPaise).isEqualTo(600_00L)
    }

    @Test
    fun `large amounts do not overflow Long paise`() {
        // ₹99,99,99,999.99 twice — far past Int range, exact under Long (ADR-002 rationale).
        val big = 99_99_99_999_99L
        val entries =
            listOf(
                Fixtures.expense(partyId = partyId, amountPaise = big, direction = ExpenseDirection.PAID),
                Fixtures.expense(partyId = partyId, amountPaise = big, direction = ExpenseDirection.PAID),
            )
        val rows = RunningBalanceCalculator.withRunningBalance(entries)
        assertThat(rows.first().balanceAfterPaise).isEqualTo(big * 2)
    }
}
