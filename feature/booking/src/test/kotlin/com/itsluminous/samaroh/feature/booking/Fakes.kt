package com.itsluminous.samaroh.feature.booking

import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.invoice.InvoiceGenerator
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
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
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.BuiltInEventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.ui.calendar.BookingCalendarPrefs
import com.itsluminous.samaroh.feature.booking.ui.calendar.DataStoreBookingCalendarPrefs
import com.itsluminous.samaroh.feature.booking.ui.form.BookingFormFieldPrefs
import com.itsluminous.samaroh.feature.booking.ui.form.BookingFormFieldVisibility
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

    override suspend fun bookingDateBounds(businessId: String): ClosedRange<LocalDate>? {
        val live = bookings.value.filter { it.businessId == businessId && it.deletedAt == null }
        if (live.isEmpty()) return null
        return live.minOf { it.startDate }..live.maxOf { it.startDate }
    }

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

    override suspend fun invoiceNumberExists(
        businessId: String,
        invoiceNumber: String,
        excludingBookingId: String?,
    ): Boolean =
        bookings.value.any {
            it.businessId == businessId &&
                it.invoiceNumber == invoiceNumber &&
                it.id != excludingBookingId &&
                it.deletedAt == null
        }
}

/** In-memory booking-form field-visibility prefs (ADR-020). */
class FakeFormFieldPrefs(
    initial: BookingFormFieldVisibility = BookingFormFieldVisibility(),
) : BookingFormFieldPrefs {
    val state = MutableStateFlow(initial)
    override val visibility: Flow<BookingFormFieldVisibility> = state
}

/** In-memory booking-calendar appearance prefs. */
class FakeBookingCalendarPrefs(
    initial: Float = DataStoreBookingCalendarPrefs.DEFAULT_ICON_WATERMARK_ALPHA,
    eventsViewInitial: Boolean = false,
) : BookingCalendarPrefs {
    val alpha = MutableStateFlow(initial)
    override val iconWatermarkAlpha: Flow<Float> = alpha

    val eventsViewState = MutableStateFlow(eventsViewInitial)
    override val eventsView: Flow<Boolean> = eventsViewState

    override suspend fun setEventsView(enabled: Boolean) {
        eventsViewState.value = enabled
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

class FakeMemberRepository(
    initial: List<BusinessMember> = emptyList(),
) : MemberRepository {
    val members = MutableStateFlow(initial)

    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> = members

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = members.value.firstOrNull { it.businessId == businessId && it.userId == userId }

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

/** Static built-in event types mirroring shared/event-types.json, without asset loading. */
class FakeEventTypeCatalog : EventTypeCatalog {
    override val eventTypes: List<BuiltInEventType> =
        listOf(
            BuiltInEventType(key = "wedding", emoji = "\uD83D\uDC92", labelRes = 101),
            BuiltInEventType(key = "birthday", emoji = "\uD83C\uDF82", labelRes = 102),
            BuiltInEventType(key = BuiltInEventType.CUSTOM_KEY, emoji = "\u2728", labelRes = 103),
        )
}

/** Static palette mirroring shared/booking-colors.json (ADR-030), without asset loading. */
class FakeBookingColorCatalog : BookingColorCatalog {
    override val colors: List<BookingColor> =
        listOf(
            BookingColor(key = "tomato", hex = "#C62828", onHex = "#FFFFFF", labelRes = 201),
            BookingColor(key = "sky", hex = "#4FC3F7", onHex = "#212121", labelRes = 202),
            BookingColor(key = "grape", hex = "#8E24AA", onHex = "#FFFFFF", labelRes = 203),
        )
}

/** Builder for `event_types` preset rows (ADR-032). */
fun presetFixture(
    label: String,
    icon: String = "\u2728",
    color: String? = null,
    sortOrder: Int = 0,
    businessId: String = Fixtures.BUSINESS_ID,
    id: String = "preset-$label",
    deletedAt: java.time.Instant? = null,
): EventType =
    EventType(
        id = id,
        businessId = businessId,
        label = label,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        deletedAt = deletedAt,
    )

/**
 * The preset rows migration 006 / client seeding produce for the fake business:
 * Wedding → tomato (in the fake palette), Birthday → banana (NOT in the fake palette,
 * exercising the fall-through), Custom → grape.
 */
fun seededPresetFixtures(businessId: String = Fixtures.BUSINESS_ID): List<EventType> =
    listOf(
        presetFixture("Wedding", "\uD83D\uDC92", color = "tomato", sortOrder = 0, businessId = businessId),
        presetFixture("Room Booking", "\uD83C\uDFE8", color = "sky", sortOrder = 1, businessId = businessId),
        presetFixture("Birthday", "\uD83C\uDF82", color = "banana", sortOrder = 2, businessId = businessId),
        presetFixture("Custom", "\u2728", color = "grape", sortOrder = 3, businessId = businessId),
    )

/** In-memory [EventTypeRepository] driving the preset-backed picker and colours (ADR-032). */
class FakeEventTypeRepository(
    initial: List<EventType> = emptyList(),
) : EventTypeRepository {
    val presetsFlow = kotlinx.coroutines.flow.MutableStateFlow(initial)
    var seededBusinessIds = mutableListOf<String>()

    override fun presets(businessId: String): kotlinx.coroutines.flow.Flow<List<EventType>> =
        kotlinx.coroutines.flow.flow {
            presetsFlow.collect { list ->
                emit(
                    list
                        .filter { it.businessId == businessId && it.deletedAt == null }
                        .sortedWith(compareBy({ it.sortOrder }, { it.label.lowercase() })),
                )
            }
        }

    override suspend fun presetsOnce(businessId: String) =
        presetsFlow.value.filter { it.businessId == businessId && it.deletedAt == null }.sortedBy { it.sortOrder }

    override suspend fun preset(id: String) = presetsFlow.value.firstOrNull { it.id == id }

    override suspend fun savePreset(preset: EventType) {
        presetsFlow.value = presetsFlow.value.filterNot { it.id == preset.id } + preset
    }

    override suspend fun deletePreset(id: String) {
        presetsFlow.value =
            presetsFlow.value.map {
                if (it.id == id) it.copy(deletedAt = java.time.Instant.parse("2026-02-01T00:00:00Z")) else it
            }
    }

    override suspend fun labelInUse(
        businessId: String,
        label: String,
        excludingId: String?,
    ) = presetsFlow.value.any {
        it.businessId == businessId &&
            it.deletedAt == null &&
            it.id != excludingId &&
            it.label.equals(label.trim(), ignoreCase = true)
    }

    override suspend fun seedDefaults(businessId: String) {
        seededBusinessIds += businessId
    }
}
