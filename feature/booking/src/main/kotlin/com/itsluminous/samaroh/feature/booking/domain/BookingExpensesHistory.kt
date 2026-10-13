package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseDirection

/**
 * The booking card's "Expenses history" section (ADR-106, shared contract §3b): live
 * expenses recorded against the booking (`expenses.booking_id`), newest first, with an
 * informational total. Pure rules; the DAO supplies the rows and their order.
 */
object BookingExpensesHistory {
    /**
     * The rows the card may show: EMPTY without `expenses.view` (hidden, not greyed — the
     * whole section disappears, ADR-038); owners always see them. A null [actor] is the
     * pre-auth owner-mode default used everywhere else on the card.
     */
    fun visibleRows(
        actor: BookingActor?,
        linkedExpenses: List<Expense>,
    ): List<Expense> =
        if (actor == null || actor.isOwner || actor.canViewExpenses) {
            linkedExpenses.filter { it.deletedAt == null }
        } else {
            emptyList()
        }

    /**
     * `booking.card.expenses_total` = Σ`paid` − Σ`received` over the listed rows (paise).
     * Informational only — NEVER subtracted from the booking's due (booking money math is
     * unchanged).
     */
    fun totalPaise(expenses: List<Expense>): Long =
        expenses.sumOf { if (it.direction == ExpenseDirection.PAID) it.amountPaise else -it.amountPaise }
}
