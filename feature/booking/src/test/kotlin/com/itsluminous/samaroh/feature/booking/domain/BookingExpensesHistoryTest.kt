package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test

/** The booking card's "Expenses history" rules (ADR-106 §3b). */
class BookingExpensesHistoryTest {
    private val paid = Fixtures.expense(partyId = "p", amountPaise = 500_00).copy(bookingId = "b-1")
    private val received =
        Fixtures
            .expense(
                partyId = "p",
                direction = ExpenseDirection.RECEIVED,
                amountPaise = 120_00,
            ).copy(bookingId = "b-1")
    private val dead = Fixtures.expense(partyId = "p", amountPaise = 999_00).copy(bookingId = "b-1", deletedAt = Fixtures.NOW)

    private fun member(canViewExpenses: Boolean) =
        BookingActor(
            userId = "m",
            displayName = "m",
            isOwner = false,
            permissions = BookingPermissions(view = true),
            canViewExpenses = canViewExpenses,
        )

    @Test
    fun `total is paid minus received over the listed rows`() {
        assertThat(BookingExpensesHistory.totalPaise(emptyList())).isEqualTo(0)
        assertThat(BookingExpensesHistory.totalPaise(listOf(paid))).isEqualTo(500_00)
        assertThat(BookingExpensesHistory.totalPaise(listOf(paid, received))).isEqualTo(380_00)
        assertThat(BookingExpensesHistory.totalPaise(listOf(received))).isEqualTo(-120_00)
    }

    @Test
    fun `rows are hidden without expenses view, shown for owners and view holders, tombstones dropped`() {
        val rows = listOf(paid, dead, received)
        assertThat(BookingExpensesHistory.visibleRows(member(canViewExpenses = false), rows)).isEmpty()
        assertThat(BookingExpensesHistory.visibleRows(member(canViewExpenses = true), rows)).containsExactly(paid, received).inOrder()
        assertThat(BookingExpensesHistory.visibleRows(member(false).copy(isOwner = true), rows)).containsExactly(paid, received).inOrder()
        assertThat(BookingExpensesHistory.visibleRows(null, rows)).containsExactly(paid, received).inOrder() // owner-mode default
    }

    @Test
    fun `expense line is date, with notes collapsed to one line only when present`() {
        assertThat(BookingCardNotes.expenseLine("20 Oct 2026", null)).isEqualTo("20 Oct 2026")
        assertThat(BookingCardNotes.expenseLine("20 Oct 2026", "  ")).isEqualTo("20 Oct 2026")
        assertThat(BookingCardNotes.expenseLine("20 Oct 2026", " flowers ")).isEqualTo("20 Oct 2026 \u00B7 flowers")
        assertThat(BookingCardNotes.expenseLine("20 Oct 2026", "flowers\nand DJ")).isEqualTo("20 Oct 2026 \u00B7 flowers and DJ")
    }
}
