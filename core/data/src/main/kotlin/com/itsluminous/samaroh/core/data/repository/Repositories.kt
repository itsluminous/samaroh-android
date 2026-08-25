package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentReminder
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * Repository contracts (FROZEN in Wave 0 — docs/decisions.md ADR-001). All reads come from
 * Room; all writes go to Room plus the outbox (§4.5). Implementations must never touch the
 * network directly.
 */

interface BookingRepository {
    /** Bookings overlapping [from]..[to] — drives the calendar month view. */
    fun bookingsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<Booking>>

    suspend fun booking(id: String): Booking?

    /** Upserts locally and enqueues an outbox push. */
    suspend fun saveBooking(booking: Booking)

    /** Tombstones locally and enqueues a delete push. */
    suspend fun deleteBooking(id: String)

    /** Non-blocking conflict warning input (§4.1): live non-cancelled bookings on [date]. */
    suspend fun countBookingsOn(
        businessId: String,
        date: LocalDate,
    ): Int

    fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>>

    /** Live payments of several bookings — month summary card input. Additive W1-A extension (ADR-007). */
    fun paymentsForBookings(bookingIds: List<String>): Flow<List<BookingPayment>>

    suspend fun recordPayment(payment: BookingPayment)

    /** due = booking.totalAmountPaise − this value; always computed, never stored (§2). */
    suspend fun totalPaidPaise(bookingId: String): Long

    fun dateBlocksBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DateBlock>>

    suspend fun saveDateBlock(block: DateBlock)

    suspend fun deleteDateBlock(id: String)

    /*
     * Payment-reminder persistence + reminder-engine queries (§4.1) — additive W1-A
     * extension of the frozen contract, recorded in docs/decisions.md ADR-007.
     */

    /** Live pending reminders due on or before [onOrBefore] — the in-app confirmations card. */
    fun duePendingReminders(
        businessId: String,
        onOrBefore: LocalDate,
    ): Flow<List<PaymentReminder>>

    /** One-shot variant of [duePendingReminders] for the daily reminder worker. */
    suspend fun duePendingRemindersOnce(
        businessId: String,
        onOrBefore: LocalDate,
    ): List<PaymentReminder>

    /** All live reminders of one booking, newest remind-on first. */
    suspend fun remindersForBooking(bookingId: String): List<PaymentReminder>

    suspend fun reminder(id: String): PaymentReminder?

    /** Upserts locally and enqueues an outbox push. */
    suspend fun saveReminder(reminder: PaymentReminder)

    /** Non-cancelled live bookings that ended strictly before [date] — reminder candidates. */
    suspend fun bookingsEndedBefore(
        businessId: String,
        date: LocalDate,
    ): List<Booking>

    /** Non-cancelled live bookings starting exactly on [date] — upcoming-event reminders. */
    suspend fun bookingsStartingOn(
        businessId: String,
        date: LocalDate,
    ): List<Booking>

    /**
     * Whether another live booking of the business already carries [invoiceNumber] —
     * validates the manual invoice-number field before a save (additive; ADR-020).
     * [excludingBookingId] is the booking being edited (never counts as its own duplicate).
     */
    suspend fun invoiceNumberExists(
        businessId: String,
        invoiceNumber: String,
        excludingBookingId: String? = null,
    ): Boolean
}

/** A party with its computed running balance for the Expenses home list. */
data class PartyWithNetBalance(
    val party: Party,
    /** Σ(paid) − Σ(received) in paise. Positive = "You gave" more (rendered red). */
    val netBalancePaise: Long,
)

interface ExpensesRepository {
    fun partiesWithBalance(businessId: String): Flow<List<PartyWithNetBalance>>

    /** Type-ahead suggestion source (UI debounces ~300 ms). */
    suspend fun searchParties(
        businessId: String,
        query: String,
    ): List<Party>

    suspend fun saveParty(party: Party)

    suspend fun deleteParty(id: String)

    fun entriesForParty(partyId: String): Flow<List<Expense>>

    suspend fun saveExpense(expense: Expense)

    suspend fun deleteExpense(id: String)
}

interface InventoryRepository {
    fun masterItems(businessId: String): Flow<List<MasterItem>>

    /** Type-ahead suggestion source (UI debounces ~300 ms). */
    suspend fun searchMasterItems(
        businessId: String,
        query: String,
    ): List<MasterItem>

    suspend fun saveMasterItem(item: MasterItem)

    suspend fun deleteMasterItem(id: String)

    fun transactionsForItem(
        businessId: String,
        masterItemId: String,
    ): Flow<List<InventoryTransaction>>

    /**
     * Records an add/remove transaction. FIFO lot consumption for removes (decrementing
     * older lots' remaining quantity) is implemented by the inventory feature wave; this
     * contract only guarantees persistence + outbox.
     */
    suspend fun recordTransaction(txn: InventoryTransaction)

    /** Open `add` lots, oldest first — FIFO consumption order and stock-value input. */
    suspend fun openAddLotsFifo(
        businessId: String,
        masterItemId: String,
    ): List<InventoryTransaction>

    suspend fun currentStock(
        businessId: String,
        masterItemId: String,
    ): Double
}

interface BusinessRepository {
    fun businesses(): Flow<List<Business>>

    suspend fun business(id: String): Business?

    suspend fun saveBusiness(business: Business)

    fun settings(businessId: String): Flow<BusinessSettings?>

    suspend fun saveSettings(settings: BusinessSettings)
}

interface MemberRepository {
    fun membersForBusiness(businessId: String): Flow<List<BusinessMember>>

    suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember?

    suspend fun saveMember(member: BusinessMember)
}
