package com.itsluminous.samaroh.feature.booking

import com.itsluminous.samaroh.core.data.invoice.InvoiceGenerator
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.EventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

// In-memory fakes for ViewModel/logic tests — no Room, no network, no Android.

class FakeBookingRepository : BookingRepository {
    val bookings = MutableStateFlow<List<Booking>>(emptyList())
    val payments = MutableStateFlow<List<BookingPayment>>(emptyList())
    val blocks = MutableStateFlow<List<DateBlock>>(emptyList())
    val reminders = MutableStateFlow<List<PaymentReminder>>(emptyList())

    /** Fixed per-date conflict counts for conflict-detection tests; falls back to live data. */
    var conflictCounts: Map<LocalDate, Int>? = null

    override fun bookingsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<Booking>> =
        bookings.map { list ->
            list.filter {
                it.businessId == businessId && it.startDate <= to && it.endDate >= from && it.deletedAt == null
            }
        }

    override suspend fun booking(id: String): Booking? = bookings.value.firstOrNull { it.id == id }

    override suspend fun saveBooking(booking: Booking) {
        bookings.value = bookings.value.filterNot { it.id == booking.id } + booking
    }

    override suspend fun deleteBooking(id: String) {
        bookings.value = bookings.value.filterNot { it.id == id }
    }

    override suspend fun countBookingsOn(
        businessId: String,
        date: LocalDate,
    ): Int =
        conflictCounts?.get(date)
            ?: bookings.value.count {
                it.businessId == businessId &&
                    date in it.startDate..it.endDate &&
                    it.status != BookingStatus.CANCELLED &&
                    it.deletedAt == null
            }

    override fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>> =
        payments.map { list -> list.filter { it.bookingId == bookingId && it.deletedAt == null } }

    override fun paymentsForBookings(bookingIds: List<String>): Flow<List<BookingPayment>> =
        payments.map { list -> list.filter { it.bookingId in bookingIds && it.deletedAt == null } }

    override suspend fun recordPayment(payment: BookingPayment) {
        require(payment.amountPaise > 0)
        payments.value = payments.value + payment
    }

    override suspend fun totalPaidPaise(bookingId: String): Long =
        payments.value.filter { it.bookingId == bookingId && it.deletedAt == null }.sumOf { it.amountPaise }

    override fun dateBlocksBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DateBlock>> =
        blocks.map { list ->
            list.filter { it.businessId == businessId && it.startDate <= to && it.endDate >= from && it.deletedAt == null }
        }

    override suspend fun saveDateBlock(block: DateBlock) {
        blocks.value = blocks.value.filterNot { it.id == block.id } + block
    }

    override suspend fun deleteDateBlock(id: String) {
        blocks.value = blocks.value.filterNot { it.id == id }
    }

    override fun duePendingReminders(
        businessId: String,
        onOrBefore: LocalDate,
    ): Flow<List<PaymentReminder>> =
        reminders.map { list ->
            list.filter {
                it.businessId == businessId &&
                    it.status == ReminderStatus.PENDING &&
                    !it.remindOn.isAfter(onOrBefore) &&
                    it.deletedAt == null
            }
        }

    override suspend fun duePendingRemindersOnce(
        businessId: String,
        onOrBefore: LocalDate,
    ): List<PaymentReminder> =
        reminders.value.filter {
            it.businessId == businessId &&
                it.status == ReminderStatus.PENDING &&
                !it.remindOn.isAfter(onOrBefore) &&
                it.deletedAt == null
        }

    override suspend fun remindersForBooking(bookingId: String): List<PaymentReminder> =
        reminders.value.filter { it.bookingId == bookingId && it.deletedAt == null }

    override suspend fun reminder(id: String): PaymentReminder? = reminders.value.firstOrNull { it.id == id }

    override suspend fun saveReminder(reminder: PaymentReminder) {
        reminders.value = reminders.value.filterNot { it.id == reminder.id } + reminder
    }

    override suspend fun bookingsEndedBefore(
        businessId: String,
        date: LocalDate,
    ): List<Booking> =
        bookings.value.filter {
            it.businessId == businessId && it.endDate < date && it.status != BookingStatus.CANCELLED && it.deletedAt == null
        }

    override suspend fun bookingsStartingOn(
        businessId: String,
        date: LocalDate,
    ): List<Booking> =
        bookings.value.filter {
            it.businessId == businessId && it.startDate == date && it.status != BookingStatus.CANCELLED && it.deletedAt == null
        }
}

class FakeBusinessRepository(
    initial: List<Business>,
) : BusinessRepository {
    val data = MutableStateFlow(initial)

    override fun businesses(): Flow<List<Business>> = data

    override suspend fun business(id: String): Business? = data.value.firstOrNull { it.id == id }

    override suspend fun saveBusiness(business: Business) {
        data.value = data.value.filterNot { it.id == business.id } + business
    }

    override fun settings(businessId: String): Flow<BusinessSettings?> = MutableStateFlow(null)

    override suspend fun saveSettings(settings: BusinessSettings) = Unit
}

class FakeMemberRepository : MemberRepository {
    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> = MutableStateFlow(emptyList())

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = null

    override suspend fun saveMember(member: BusinessMember) = Unit
}

class FakeActorProvider(
    var actor: BookingActor =
        BookingActor(
            userId = "test-user",
            displayName = "test-owner",
            isOwner = true,
            permissions =
                BookingPermissions(
                    view = true,
                    create = true,
                    edit = true,
                    delete = true,
                    recordPayment = true,
                    generateInvoice = true,
                ),
        ),
) : BookingActorProvider {
    override suspend fun actorFor(business: Business): BookingActor = actor
}

/** Fake of the frozen InvoiceGenerator contract (ADR-006) — real impl is W1-E. */
class FakeInvoiceGenerator : InvoiceGenerator {
    var pdfResult: Result<String> = Result.success("/tmp/invoice-test.pdf")
    var text: String = "invoice-text"
    val pdfRequests = mutableListOf<String>()
    val textRequests = mutableListOf<String>()

    override suspend fun generateInvoicePdf(bookingId: String): Result<String> {
        pdfRequests += bookingId
        return pdfResult
    }

    override suspend fun buildInvoiceText(bookingId: String): String {
        textRequests += bookingId
        return text
    }
}

class RecordingSyncScheduler : SyncScheduler {
    var immediateSyncs = 0
    var periodicEnsured = 0

    override fun requestImmediateSync() {
        immediateSyncs++
    }

    override fun ensurePeriodicSync() {
        periodicEnsured++
    }
}

/** Static event types mirroring shared/event-types.json, without asset loading. */
class FakeEventTypeCatalog : EventTypeCatalog {
    override val eventTypes: List<EventType> =
        listOf(
            EventType(key = "wedding", emoji = "\uD83D\uDC92", labelRes = 101),
            EventType(key = "birthday", emoji = "\uD83C\uDF82", labelRes = 102),
            EventType(key = EventType.CUSTOM_KEY, emoji = "\u2728", labelRes = 103),
        )
}
