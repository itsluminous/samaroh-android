package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.ReplicaIntegrity
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.FakeEventTypeRepository
import com.itsluminous.samaroh.feature.booking.domain.BuiltInEventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The replica-consistency gate (ADR-060, the mass-bogus-reminders incident): while the
 * local replica may be a PARTIAL snapshot (initial sync in flight / aborted / a table
 * rejected), the engine must neither CREATE payment reminders (payments may be missing —
 * every settled past booking would look unpaid) nor DISMISS them (the booking row may be
 * missing — a legitimate synced reminder would be killed). The pull that restores
 * consistency re-runs the engine via [ReminderPostSyncHook], so nothing is lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderEngineGatingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Fixtures.NOW, ZoneId.of("UTC"))
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())
    private val emptyCatalog =
        object : EventTypeCatalog {
            override val eventTypes: List<BuiltInEventType> = emptyList()
        }

    private val bookingRepository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))

    private fun engine(consistent: Boolean): ReminderEngine =
        ReminderEngine(
            context = context,
            bookingRepository = bookingRepository,
            businessRepository = businessRepository,
            eventTypeRepository = FakeEventTypeRepository(seededPresetFixtures()),
            eventTypes = emptyCatalog,
            notifier = BookingNotifier(context),
            prefs =
                BookingReminderPrefs(
                    PreferenceDataStoreFactory.create(scope = storeScope) {
                        File(tmp.root, "settings.preferences_pb")
                    },
                ),
            replicaIntegrity = ReplicaIntegrity { consistent },
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    @Test
    fun `inconsistent replica - unpaid past booking creates NO payment reminder`() =
        runTest(dispatcher) {
            // The incident shape: the booking pulled, its settling payments not (yet).
            val booking =
                Fixtures.booking(
                    startDate = today().minusDays(30),
                    endDate = today().minusDays(29),
                    totalAmountPaise = 2_00_000_00L,
                )
            bookingRepository.saveBooking(booking)

            engine(consistent = false).runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).isEmpty()
        }

    @Test
    fun `inconsistent replica - pending reminder of a not-yet-pulled booking is NOT dismissed`() =
        runTest(dispatcher) {
            // A reminder row synced ahead of its booking must survive the partial window.
            val reminder =
                PaymentReminder(
                    id = UUID.randomUUID().toString(),
                    bookingId = "booking-not-pulled-yet",
                    businessId = Fixtures.BUSINESS_ID,
                    remindOn = today().minusDays(1),
                    status = ReminderStatus.PENDING,
                    amountDueSnapshotPaise = 50_000_00L,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                )
            bookingRepository.saveReminder(reminder)

            engine(consistent = false).runDailyPass()

            val kept = bookingRepository.remindersForBooking(reminder.bookingId).single()
            assertThat(kept.status).isEqualTo(ReminderStatus.PENDING)
        }

    @Test
    fun `consistent replica - the same state plans normally`() =
        runTest(dispatcher) {
            val booking =
                Fixtures.booking(
                    startDate = today().minusDays(30),
                    endDate = today().minusDays(29),
                    totalAmountPaise = 2_00_000_00L,
                )
            bookingRepository.saveBooking(booking)

            engine(consistent = true).runDailyPass()

            val created = bookingRepository.remindersForBooking(booking.id).single()
            assertThat(created.status).isEqualTo(ReminderStatus.PENDING)
            assertThat(created.amountDueSnapshotPaise).isEqualTo(2_00_000_00L)
        }
}
