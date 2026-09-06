package com.itsluminous.samaroh.core.google.calendar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.auth.Session
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val BIZ = Fixtures.BUSINESS_ID
private const val USER = Fixtures.USER_ID
private const val SCOPE_EVENTS = "https://www.googleapis.com/auth/calendar.events"
private const val SCOPE_APP_CREATED = "https://www.googleapis.com/auth/calendar.app.created"

/**
 * ADR-046 engine behavior: dedicated-calendar find-or-create, primary→dedicated
 * migration (move + fallback + stray cleanup), adopted-event updates (edit must PATCH,
 * never insert), 404-recreate, and the duplicate repair pass. Configuration gating
 * lives in [CalendarSyncWorker], so the engine stays testable without a BuildConfig id.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarSyncEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC)

    private class FakeCalendarService : CalendarService {
        val calendars = mutableMapOf<String, String>() // id → summary
        val events = mutableMapOf<String, MutableMap<String, GcalEvent>>() // calendarId → (eventId → event)
        var nextEventId = 1
        var nextCalendarId = 1
        var rejectMoves = false
        val moves = mutableListOf<Triple<String, String, String>>()
        val updates = mutableListOf<Pair<String, String>>() // calendarId to eventId
        val inserts = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<Pair<String, String>>()

        fun seedEvent(
            calendarId: String,
            eventId: String,
            event: GcalEvent,
        ) {
            events.getOrPut(calendarId) { mutableMapOf() }[eventId] = event
        }

        override suspend fun insertEvent(
            calendarId: String,
            event: GcalEvent,
        ): String {
            val id = "ev-${nextEventId++}"
            seedEvent(calendarId, id, event)
            inserts += calendarId to id
            return id
        }

        override suspend fun updateEvent(
            calendarId: String,
            eventId: String,
            event: GcalEvent,
        ) {
            val calendar = events.getOrPut(calendarId) { mutableMapOf() }
            if (eventId !in calendar) throw GoogleApiException(404, "not found")
            calendar[eventId] = event
            updates += calendarId to eventId
        }

        override suspend fun deleteEvent(
            calendarId: String,
            eventId: String,
        ) {
            events[calendarId]?.remove(eventId)
            deletes += calendarId to eventId
        }

        override suspend fun calendarExists(calendarId: String): Boolean = calendarId in calendars

        override suspend fun createCalendar(summary: String): String {
            val id = "cal-${nextCalendarId++}"
            calendars[id] = summary
            return id
        }

        override suspend fun listEvents(
            calendarId: String,
            privateExtendedProperty: String?,
            timeMin: String?,
            timeMax: String?,
        ): List<GcalEventRef> =
            events[calendarId].orEmpty().mapNotNull { (id, event) ->
                val ref =
                    GcalEventRef(
                        id = id,
                        summary = event.summary,
                        description = event.description,
                        privateProperties = event.privateProperties,
                    )
                if (privateExtendedProperty != null) {
                    val (key, value) = privateExtendedProperty.split("=", limit = 2)
                    if (event.privateProperties[key] != value) return@mapNotNull null
                }
                ref
            }

        override suspend fun moveEvent(
            sourceCalendarId: String,
            eventId: String,
            destinationCalendarId: String,
        ) {
            if (rejectMoves) throw GoogleApiException(403, "move forbidden")
            val event = events[sourceCalendarId]?.remove(eventId) ?: throw GoogleApiException(404, "gone")
            seedEvent(destinationCalendarId, eventId, event)
            moves += Triple(sourceCalendarId, eventId, destinationCalendarId)
        }
    }

    private class FakeBookingRepository(
        bookings: List<Booking>,
    ) : BookingRepository {
        val bookingsById = bookings.associateBy { it.id }.toMutableMap()
        val saved = mutableListOf<Booking>()

        override fun bookingsBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<Booking>> = flowOf(bookingsById.values.filter { it.businessId == businessId })

        override suspend fun bookingDateBounds(businessId: String): ClosedRange<LocalDate>? = null

        override suspend fun booking(id: String): Booking? = bookingsById[id]

        override suspend fun saveBooking(booking: Booking) {
            bookingsById[booking.id] = booking
            saved += booking
        }

        override suspend fun deleteBooking(id: String) = error("unused")

        override suspend fun countBookingsOn(
            businessId: String,
            date: LocalDate,
        ): Int = 0

        override fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>> = flowOf(emptyList())

        override fun paymentsForBookings(bookingIds: List<String>): Flow<List<BookingPayment>> = flowOf(emptyList())

        override suspend fun recordPayment(payment: BookingPayment) = error("unused")

        override suspend fun totalPaidPaise(bookingId: String): Long = 0

        override fun dateBlocksBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<DateBlock>> = flowOf(emptyList())

        override suspend fun saveDateBlock(block: DateBlock) = error("unused")

        override suspend fun deleteDateBlock(id: String) = error("unused")

        override fun duePendingReminders(
            businessId: String,
            onOrBefore: LocalDate,
        ): Flow<List<PaymentReminder>> = flowOf(emptyList())

        override suspend fun duePendingRemindersOnce(
            businessId: String,
            onOrBefore: LocalDate,
        ): List<PaymentReminder> = emptyList()

        override suspend fun remindersForBooking(bookingId: String): List<PaymentReminder> = emptyList()

        override suspend fun reminder(id: String): PaymentReminder? = null

        override suspend fun saveReminder(reminder: PaymentReminder) = error("unused")

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

    private class FakeBusinessRepository(
        gcalEnabled: Boolean = true,
    ) : BusinessRepository {
        private val settings =
            MutableStateFlow<BusinessSettings?>(
                BusinessSettings(businessId = BIZ, gcalSyncEnabled = gcalEnabled, updatedAt = Instant.EPOCH),
            )

        override fun businesses(): Flow<List<Business>> = flowOf(emptyList())

        override suspend fun business(id: String): Business? = null

        override suspend fun saveBusiness(business: Business) = error("unused")

        override fun settings(businessId: String): Flow<BusinessSettings?> = settings

        override suspend fun saveSettings(settings: BusinessSettings) = error("unused")
    }

    private class FakeLinkDao(
        initial: GoogleAccountLinkEntity?,
    ) : GoogleAccountLinkDao {
        val link = MutableStateFlow(initial)

        override suspend fun upsert(link: GoogleAccountLinkEntity) {
            this.link.value = link
        }

        override fun linkForUser(userId: String): Flow<GoogleAccountLinkEntity?> = link

        override suspend fun unlink(userId: String) {
            link.value = null
        }
    }

    private class FakeSessionHolder : SessionHolder {
        override val session: Flow<Session?> = flowOf(Session(userId = USER, email = "owner@example.com"))

        override suspend fun signOut() = Unit
    }

    private class RecordingOutboxWriter : OutboxWriter {
        val enqueued = mutableListOf<Pair<String, String>>() // entityType to entityId

        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            enqueued += entityType to entityId
        }
    }

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateFile = File.createTempFile("gcal_engine_test", ".preferences_pb")
    private val stateStore =
        GcalSyncStateStore(PreferenceDataStoreFactory.create(scope = storeScope) { stateFile })

    @After
    fun tearDown() {
        storeScope.cancel()
        stateFile.delete()
    }

    private fun link(
        scopes: List<String>,
        calendarId: String?,
    ) = GoogleAccountLinkEntity(
        userId = USER,
        email = "owner@example.com",
        scopes = scopes,
        calendarId = calendarId,
        updatedAt = Instant.EPOCH,
    )

    private fun engine(
        service: FakeCalendarService,
        bookings: FakeBookingRepository,
        linkDao: FakeLinkDao,
        outbox: RecordingOutboxWriter = RecordingOutboxWriter(),
        business: FakeBusinessRepository = FakeBusinessRepository(),
    ) = CalendarSyncEngine(
        context = ApplicationProvider.getApplicationContext(),
        bookingRepository = bookings,
        businessRepository = business,
        sessionHolder = FakeSessionHolder(),
        linkDao = linkDao,
        calendarService = service,
        stateStore = stateStore,
        outboxWriter = outbox,
        clock = clock,
    )

    // --- find-or-create ---

    @Test
    fun `first pass with app-created scope creates the dedicated calendar and stores its id`() =
        runTest {
            val service = FakeCalendarService()
            val bookings = FakeBookingRepository(listOf(Fixtures.booking(id = "b-1")))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = null))
            val outbox = RecordingOutboxWriter()

            engine(service, bookings, linkDao, outbox).syncBusiness(BIZ).getOrThrow()

            assertThat(service.calendars).hasSize(1)
            val calendarId = service.calendars.keys.single()
            assertThat(linkDao.link.value?.calendarId).isEqualTo(calendarId)
            // The id change syncs to other devices via the google_accounts outbox row.
            assertThat(outbox.enqueued).contains("google_accounts" to USER)
            // The booking's event landed on the dedicated calendar, not primary.
            assertThat(service.events[calendarId].orEmpty()).hasSize(1)
            assertThat(service.events["primary"].orEmpty()).isEmpty()
        }

    @Test
    fun `cached dedicated calendar is reused when it still exists`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-cached"] = "Samaroh"
            val bookings = FakeBookingRepository(listOf(Fixtures.booking(id = "b-1")))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-cached"))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.calendars.keys).containsExactly("cal-cached")
            assertThat(service.events["cal-cached"].orEmpty()).hasSize(1)
        }

    @Test
    fun `deleted dedicated calendar is recreated and events repushed`() =
        runTest {
            val service = FakeCalendarService() // cal-gone does NOT exist remotely
            val booking = Fixtures.booking(id = "b-1")
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-gone"))
            // A pending change makes the pass do work (a fully-idle pass stays offline by
            // design); target verification then notices the calendar is gone.
            stateStore.write(BIZ, mapOf("b-1" to SyncedEventState(eventId = "ev-old", fingerprint = "stale")))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            val newCalendar = service.calendars.keys.single()
            assertThat(newCalendar).isNotEqualTo("cal-gone")
            assertThat(service.events[newCalendar].orEmpty()).hasSize(1)
            assertThat(stateStore.read(BIZ).keys).containsExactly("b-1")
        }

    @Test
    fun `without the app-created scope events keep landing on primary`() =
        runTest {
            val service = FakeCalendarService()
            val bookings = FakeBookingRepository(listOf(Fixtures.booking(id = "b-1")))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS), calendarId = null))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.calendars).isEmpty()
            assertThat(service.events["primary"].orEmpty()).hasSize(1)
            assertThat(linkDao.link.value?.calendarId).isEqualTo("primary")
        }

    // --- migration ---

    @Test
    fun `first pass after scope grant migrates recorded primary events and deletes strays`() =
        runTest {
            val service = FakeCalendarService()
            val booking = Fixtures.booking(id = "b-1")
            val fingerprint = GcalEventMapper.fingerprint(booking, 0)
            val managedDescription = "Total … \nManaged by Samaroh"
            service.seedEvent("primary", "ev-real", GcalEvent(summary = "real", description = managedDescription))
            // A stray duplicate created by the old bug — same managed marker, not recorded.
            service.seedEvent("primary", "ev-stray", GcalEvent(summary = "dup", description = managedDescription))
            // An unrelated personal event that must NEVER be touched.
            service.seedEvent("primary", "ev-personal", GcalEvent(summary = "dentist", description = "checkup"))
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "primary"))
            stateStore.write(BIZ, mapOf("b-1" to SyncedEventState(eventId = "ev-real", fingerprint = fingerprint)))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            val newCalendar = service.calendars.keys.single()
            // Recorded event moved (same id), stray deleted, personal event untouched.
            assertThat(service.moves).containsExactly(Triple("primary", "ev-real", newCalendar))
            assertThat(service.events[newCalendar].orEmpty().keys).containsExactly("ev-real")
            assertThat(service.events["primary"].orEmpty().keys).containsExactly("ev-personal")
        }

    @Test
    fun `migration falls back to delete+recreate when move is rejected`() =
        runTest {
            val service = FakeCalendarService()
            service.rejectMoves = true
            val booking = Fixtures.booking(id = "b-1")
            val fingerprint = GcalEventMapper.fingerprint(booking, 0)
            service.seedEvent("primary", "ev-real", GcalEvent(summary = "real", description = "Managed by Samaroh"))
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "primary"))
            stateStore.write(BIZ, mapOf("b-1" to SyncedEventState(eventId = "ev-real", fingerprint = fingerprint)))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            val newCalendar = service.calendars.keys.single()
            assertThat(service.moves).isEmpty()
            assertThat(service.events[newCalendar].orEmpty()).hasSize(1)
            assertThat(service.events["primary"].orEmpty()).isEmpty()
            val newEventId = service.events[newCalendar]!!.keys.single()
            assertThat(stateStore.read(BIZ)["b-1"]?.eventId).isEqualTo(newEventId)
            // The synced booking row records the replacement id.
            assertThat(bookings.bookingsById["b-1"]?.gcalEventId).isEqualTo(newEventId)
        }

    // --- edits must PATCH, never insert ---

    @Test
    fun `edited booking updates the existing event - event count unchanged`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-1"] = "Samaroh"
            val original = Fixtures.booking(id = "b-1")
            val edited = original.copy(customerName = "renamed")
            service.seedEvent("cal-1", "ev-1", GcalEvent(summary = "old", description = ""))
            val bookings = FakeBookingRepository(listOf(edited))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-1"))
            stateStore.write(
                BIZ,
                mapOf("b-1" to SyncedEventState(eventId = "ev-1", fingerprint = GcalEventMapper.fingerprint(original, 0))),
            )

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.updates).containsExactly("cal-1" to "ev-1")
            assertThat(service.inserts).isEmpty()
            assertThat(service.events["cal-1"].orEmpty()).hasSize(1)
        }

    @Test
    fun `state miss with a recorded gcalEventId is adopted as an update, not a create`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-1"] = "Samaroh"
            service.seedEvent("cal-1", "ev-adopted", GcalEvent(summary = "old", description = ""))
            val booking = Fixtures.booking(id = "b-1").copy(gcalEventId = "ev-adopted")
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-1"))
            // Device-local state is EMPTY (reinstall / sign-out wipe / second device).

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.updates).containsExactly("cal-1" to "ev-adopted")
            assertThat(service.events["cal-1"].orEmpty()).hasSize(1)
            assertThat(stateStore.read(BIZ)["b-1"]?.eventId).isEqualTo("ev-adopted")
        }

    @Test
    fun `update of a vanished event recreates it instead of failing`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-1"] = "Samaroh"
            // NO event seeded — update will 404.
            val original = Fixtures.booking(id = "b-1")
            val edited = original.copy(customerName = "renamed")
            val bookings = FakeBookingRepository(listOf(edited))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-1"))
            stateStore.write(
                BIZ,
                mapOf("b-1" to SyncedEventState(eventId = "ev-gone", fingerprint = GcalEventMapper.fingerprint(original, 0))),
            )

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.events["cal-1"].orEmpty()).hasSize(1)
            val newId = service.events["cal-1"]!!.keys.single()
            assertThat(stateStore.read(BIZ)["b-1"]?.eventId).isEqualTo(newId)
        }

    // --- dedupe repair pass ---

    @Test
    fun `dedupe deletes a second managed event claiming the same booking`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-1"] = "Samaroh"
            val props =
                mapOf(
                    GcalEventMapper.PROP_BOOKING_ID to "b-1",
                    GcalEventMapper.PROP_MANAGED to GcalEventMapper.PROP_MANAGED_VALUE,
                )
            service.seedEvent("cal-1", "ev-stray", GcalEvent(summary = "dup", description = "", privateProperties = props))
            val booking = Fixtures.booking(id = "b-1") // new booking → create path → dedupe runs
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-1"))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            val state = stateStore.read(BIZ)
            val recorded = state["b-1"]?.eventId
            assertThat(recorded).isNotNull()
            assertThat(service.events["cal-1"].orEmpty().keys).containsExactly(recorded)
            assertThat(service.deletes).contains("cal-1" to "ev-stray")
        }

    @Test
    fun `cancelled booking with only a synced gcalEventId still deletes its event`() =
        runTest {
            val service = FakeCalendarService()
            service.calendars["cal-1"] = "Samaroh"
            service.seedEvent("cal-1", "ev-1", GcalEvent(summary = "old", description = ""))
            val booking = Fixtures.booking(id = "b-1", status = BookingStatus.CANCELLED).copy(gcalEventId = "ev-1")
            val bookings = FakeBookingRepository(listOf(booking))
            val linkDao = FakeLinkDao(link(scopes = listOf(SCOPE_EVENTS, SCOPE_APP_CREATED), calendarId = "cal-1"))

            engine(service, bookings, linkDao).syncBusiness(BIZ).getOrThrow()

            assertThat(service.events["cal-1"].orEmpty()).isEmpty()
            assertThat(bookings.bookingsById["b-1"]?.gcalEventId).isNull()
        }
}
