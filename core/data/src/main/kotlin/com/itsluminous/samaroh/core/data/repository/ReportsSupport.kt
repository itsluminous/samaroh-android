package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Expense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Read-side reports support — ADDITIVE W2-A extension of the Wave-0 contracts, recorded in
 * docs/decisions.md ADR-019 (same pattern as the W1-B `ExpensesLedgerRepository`: a NEW
 * interface in its own file so no existing contract or test fake changes).
 *
 * The report set (§4.4) needs two cross-entity range queries the frozen repositories do
 * not carry: payments by `paid_on` (cash-basis income regardless of the booking's dates)
 * and expenses across ALL parties in a date window. Everything else the reports consume
 * comes from the existing contracts (`BookingRepository.bookingsBetween`/
 * `paymentsForBookings`, `ExpensesRepository.partiesWithBalance`,
 * `InventoryOverviewRepository.currentInventory`).
 */
interface ReportsRepository {
    /** Live payments received in [from]..[to] (by paid-on date), any booking. */
    fun paymentsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<BookingPayment>>

    /** Live expense entries of every party dated in [from]..[to]. */
    fun expensesBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<Expense>>
}

@Singleton
class RoomReportsRepository
    @Inject
    constructor(
        private val paymentDao: BookingPaymentDao,
        private val expenseDao: ExpenseDao,
    ) : ReportsRepository {
        override fun paymentsBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<BookingPayment>> = paymentDao.paymentsBetween(businessId, from, to).map { list -> list.map { it.toModel() } }

        override fun expensesBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<Expense>> = expenseDao.expensesBetween(businessId, from, to).map { list -> list.map { it.toModel() } }
    }
