package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.domain.EventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
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
 * ReminderEngine cleanup pass against realistic imported-history state (ADR-024, the
 * "so many reminders" bug): stale pending reminders whose booking is settled — or has no
 * known total — must be auto-dismissed by the daily/post-sync pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderEngineCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Fixtures.NOW, ZoneId.of("UTC"))
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())
    private val emptyCatalog =
        object : EventTypeCatalog {
            override val eventTypes: List<EventType> = emptyList()
        }

    private val bookingRepository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))

    private fun engine(): ReminderEngine =
        ReminderEngine(
            context = context,
            bookingRepository = bookingRepository,
            businessRepository = businessRepository,
            eventTypes = emptyCatalog, // labelFor falls back to the raw key — no resource ids in JVM tests
            notifier = BookingNotifier(context),
            prefs =
                BookingReminderPrefs(
                    PreferenceDataStoreFactory.create(scope = storeScope) {
                        File(tmp.root, "settings.preferences_pb")
                    },
                ),
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun pendingReminder(
        bookingId: String,
        remindOn: LocalDate,
    ) = PaymentReminder(
        id = UUID.randomUUID().toString(),
        bookingId = bookingId,
        businessId = Fixtures.BUSINESS_ID,
        remindOn = remindOn,
        status = ReminderStatus.PENDING,
        amountDueSnapshotPaise = 1_00_000_00L,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
    )

    private fun engineToday(): LocalDate = LocalDate.now(clock)

    @Test
    fun `settled past booking - stale pending reminders are dismissed, none created`() =
        runTest(dispatcher) {
            val day = engineToday()
            val booking =
                Fixtures.booking(
                    startDate = day.minusDays(400),
                    endDate = day.minusDays(399),
                    totalAmountPaise = 2_00_000_00L,
                )
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 2_00_000_00L))
            val stale1 = pendingReminder(booking.id, day.minusDays(2))
            val stale2 = pendingReminder(booking.id, day.minusDays(2)) // two-device duplicate
            bookingRepository.saveReminder(stale1)
            bookingRepository.saveReminder(stale2)

            engine().runDailyPass()

            val after = bookingRepository.reminders.value
            assertThat(after.filter { it.status == ReminderStatus.PENDING }).isEmpty()
            assertThat(after.filter { it.status == ReminderStatus.DISMISSED }).hasSize(2)
        }

    @Test
    fun `past booking with unknown total and recorded advances gets no reminder`() =
        runTest(dispatcher) {
            val day = engineToday()
            val booking =
                Fixtures.booking(
                    startDate = day.minusDays(300),
                    endDate = day.minusDays(299),
                    totalAmountPaise = 0L, // total unknown in the imported history
                )
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 50_000_00L))

            engine().runDailyPass()

            assertThat(bookingRepository.reminders.value).isEmpty()
        }

    @Test
    fun `genuinely due past booking still gets its reminder`() =
        runTest(dispatcher) {
            val day = engineToday()
            val booking =
                Fixtures.booking(
                    startDate = day.minusDays(3),
                    endDate = day.minusDays(2),
                    totalAmountPaise = 2_00_000_00L,
                )
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 50_000_00L))

            engine().runDailyPass()

            val created = bookingRepository.reminders.value.single()
            assertThat(created.status).isEqualTo(ReminderStatus.PENDING)
            assertThat(created.remindOn).isEqualTo(booking.endDate.plusDays(1))
            assertThat(created.amountDueSnapshotPaise).isEqualTo(1_50_000_00L)
        }
}
