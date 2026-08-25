package com.itsluminous.samaroh.feature.expenses.domain

import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseDirection

/** A ledger entry paired with the party's running balance after it (§4.2 balance-after chip). */
data class LedgerRow(
    val expense: Expense,
    /**
     * Net balance in paise after this entry, over the entries at or before it in time:
     * Σ(paid) − Σ(received). Positive = the business has given more than it got (red);
     * negative = it received more (green) — same sign convention as
     * [com.itsluminous.samaroh.core.data.repository.PartyWithNetBalance].
     */
    val balanceAfterPaise: Long,
)

/** Pure running-balance computation for the person ledger (newest-first, §4.2). */
object RunningBalanceCalculator {
    /**
     * Annotates [entriesNewestFirst] (the exact order the ledger renders) with the balance
     * after each entry. The newest row's balance-after equals the party's net balance; each
     * older row shows the balance as of that entry.
     */
    fun withRunningBalance(entriesNewestFirst: List<Expense>): List<LedgerRow> {
        var balance = entriesNewestFirst.sumOf { it.signedAmountPaise }
        return entriesNewestFirst.map { expense ->
            LedgerRow(expense = expense, balanceAfterPaise = balance).also {
                balance -= expense.signedAmountPaise
            }
        }
    }

    /** 'paid' increases the net balance ("you gave"); 'received' decreases it. */
    val Expense.signedAmountPaise: Long
        get() = if (direction == ExpenseDirection.PAID) amountPaise else -amountPaise
}
