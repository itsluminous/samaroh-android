package com.itsluminous.samaroh.feature.reports.fakes

import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.PartyWithNetBalance
import com.itsluminous.samaroh.core.data.repository.ReportsRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.reports.export.ExportedReport
import com.itsluminous.samaroh.feature.reports.export.ReportExportFormat
import com.itsluminous.samaroh.feature.reports.export.ReportExporter
import com.itsluminous.samaroh.feature.reports.export.ReportTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class FakeActiveBusinessProvider(
    initial: Business?,
) : ActiveBusinessProvider {
    val businessFlow = MutableStateFlow(initial)
    override val activeBusiness: Flow<Business?> get() = businessFlow
}

/** Signed-in by default so tests exercise the permission path; set null for owner-mode. */
class FakeCurrentUserProvider(
    initial: String? = "user-reports-test",
) : CurrentUserProvider {
    val userIdFlow = MutableStateFlow(initial)
    override val currentUserId: Flow<String?> get() = userIdFlow
}

class FakePermissionGuard(
    initial: MemberPermissions = MemberPermissions.viewer(),
) : PermissionGuard {
    val permissionsFlow = MutableStateFlow(initial)
    val ownerFlow = MutableStateFlow(false)

    override fun permissions(businessId: String): Flow<MemberPermissions> = permissionsFlow

    override fun isOwner(businessId: String): Flow<Boolean> = ownerFlow
}

/** Read-side fake: only the queries the reports consume are live; writes are unsupported. */
class FakeBookingRepository : BookingRepository {
    val bookingsFlow = MutableStateFlow<List<Booking>>(emptyList())
    val paymentsFlow = MutableStateFlow<List<BookingPayment>>(emptyList())

    override fun bookingsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<Booking>> = bookingsFlow.map { list -> list.filter { it.startDate <= to && it.endDate >= from } }

    override fun paymentsForBookings(bookingIds: List<String>): Flow<List<BookingPayment>> =
        paymentsFlow.map { list -> list.filter { it.bookingId in bookingIds } }

    override suspend fun booking(id: String): Booking? = bookingsFlow.value.firstOrNull { it.id == id }

    override suspend fun saveBooking(booking: Booking) = throw UnsupportedOperationException()

    override suspend fun deleteBooking(id: String) = throw UnsupportedOperationException()

    override suspend fun countBookingsOn(
        businessId: String,
        date: LocalDate,
    ): Int = 0

    override fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>> =
        paymentsFlow.map { list -> list.filter { it.bookingId == bookingId } }

    override suspend fun recordPayment(payment: BookingPayment) = throw UnsupportedOperationException()

    override suspend fun totalPaidPaise(bookingId: String): Long = 0L

    override fun dateBlocksBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DateBlock>> = MutableStateFlow(emptyList())

    override suspend fun saveDateBlock(block: DateBlock) = throw UnsupportedOperationException()

    override suspend fun deleteDateBlock(id: String) = throw UnsupportedOperationException()

    override fun duePendingReminders(
        businessId: String,
        onOrBefore: LocalDate,
    ): Flow<List<PaymentReminder>> = MutableStateFlow(emptyList())

    override suspend fun duePendingRemindersOnce(
        businessId: String,
        onOrBefore: LocalDate,
    ): List<PaymentReminder> = emptyList()

    override suspend fun remindersForBooking(bookingId: String): List<PaymentReminder> = emptyList()

    override suspend fun reminder(id: String): PaymentReminder? = null

    override suspend fun saveReminder(reminder: PaymentReminder) = throw UnsupportedOperationException()

    override suspend fun bookingsEndedBefore(
        businessId: String,
        date: LocalDate,
    ): List<Booking> = emptyList()

    override suspend fun bookingsStartingOn(
        businessId: String,
        date: LocalDate,
    ): List<Booking> = emptyList()

    override suspend fun invoiceNumberExists(
        businessId: String,
        invoiceNumber: String,
        excludingBookingId: String?,
    ): Boolean = false
}

class FakeExpensesRepository : ExpensesRepository {
    val partiesFlow = MutableStateFlow<List<PartyWithNetBalance>>(emptyList())

    override fun partiesWithBalance(businessId: String): Flow<List<PartyWithNetBalance>> = partiesFlow

    override suspend fun searchParties(
        businessId: String,
        query: String,
    ): List<Party> = emptyList()

    override suspend fun saveParty(party: Party) = throw UnsupportedOperationException()

    override suspend fun deleteParty(id: String) = throw UnsupportedOperationException()

    override fun entriesForParty(partyId: String): Flow<List<Expense>> = MutableStateFlow(emptyList())

    override suspend fun saveExpense(expense: Expense) = throw UnsupportedOperationException()

    override suspend fun deleteExpense(id: String) = throw UnsupportedOperationException()
}

class FakeReportsRepository : ReportsRepository {
    val paymentsFlow = MutableStateFlow<List<BookingPayment>>(emptyList())
    val expensesFlow = MutableStateFlow<List<Expense>>(emptyList())
    val purchasesFlow = MutableStateFlow<List<InventoryTransaction>>(emptyList())

    override fun paymentsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<BookingPayment>> = paymentsFlow.map { list -> list.filter { it.paidOn in from..to } }

    override fun expensesBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<Expense>> = expensesFlow.map { list -> list.filter { it.expenseDate in from..to } }

    override fun inventoryPurchasesBetween(
        businessId: String,
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Flow<List<InventoryTransaction>> =
        purchasesFlow.map { list ->
            list.filter {
                it.transactionType == TxnType.ADD &&
                    it.deletedAt == null &&
                    !it.transactionDate.isBefore(fromInclusive) &&
                    it.transactionDate < toExclusive
            }
        }
}

class FakeInventoryOverviewRepository : InventoryOverviewRepository {
    val linesFlow = MutableStateFlow<List<CurrentInventoryLine>>(emptyList())

    override fun currentInventory(businessId: String): Flow<List<CurrentInventoryLine>> = linesFlow

    override suspend fun canDeleteMasterItem(id: String): Boolean = true

    override suspend fun recordTransactionForValue(txn: com.itsluminous.samaroh.core.model.InventoryTransaction): Long = 0L
}

class FakeReportExporter : ReportExporter {
    var failNext = false
    var lastTable: ReportTable? = null
    var lastFormat: ReportExportFormat? = null

    override suspend fun export(
        fileBaseName: String,
        table: ReportTable,
        format: ReportExportFormat,
    ): Result<ExportedReport> {
        lastTable = table
        lastFormat = format
        return if (failNext) {
            Result.failure(IllegalStateException("fake export failure"))
        } else {
            Result.success(ExportedReport(absolutePath = "/tmp/$fileBaseName.fake", mimeType = "application/octet-stream"))
        }
    }
}
