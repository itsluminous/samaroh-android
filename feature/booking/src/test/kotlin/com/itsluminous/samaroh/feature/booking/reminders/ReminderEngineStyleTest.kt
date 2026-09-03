package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
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
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Style-at-fire-time resolution for EVERY reminder kind (ADR-045, the "full-screen
 * popup shows a normal notification" bug): payment, follow-up AND upcoming reminders
 * must all honor the selected `booking_reminder_style` when the daily pass fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderEngineStyleTest {
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
    private val eventTypeRepository = FakeEventTypeRepository(seededPresetFixtures())

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }

    private fun engine(): ReminderEngine =
        ReminderEngine(
            context = context,
            bookingRepository = bookingRepository,
            businessRepository = businessRepository,
            eventTypeRepository = eventTypeRepository,
            eventTypes = emptyCatalog,
            notifier = BookingNotifier(context),
            prefs = BookingReminderPrefs(dataStore),
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private suspend fun selectStyle(style: ReminderStyle) {
        dataStore.edit { it[stringPreferencesKey("booking_reminder_style")] = style.wire }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun postedNotifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun nextAlarm() = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm

    // ---- payment reminders ----

    private suspend fun seedDuePaymentBooking() {
        val booking =
            Fixtures.booking(
                startDate = today().minusDays(3),
                endDate = today().minusDays(2),
                totalAmountPaise = 2_00_000_00L,
            )
        bookingRepository.saveBooking(booking)
        bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 50_000_00L))
    }

    @Test
    fun `payment reminder with notification style posts WITHOUT a full-screen intent`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            seedDuePaymentBooking()

            engine().runDailyPass()

            val posted = postedNotifications().single()
            assertThat(posted.fullScreenIntent).isNull()
        }

    @Test
    fun `payment reminder with fullscreen style posts WITH a full-screen intent`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.FULLSCREEN)
            seedDuePaymentBooking()

            engine().runDailyPass()

            val posted = postedNotifications().single()
            assertThat(posted.fullScreenIntent).isNotNull()
        }

    // ---- follow-up reminders ----

    private suspend fun seedDueFollowUp() {
        val tentative =
            Fixtures.booking(
                startDate = today().plusDays(10),
                endDate = today().plusDays(10),
                status = BookingStatus.TENTATIVE,
                totalAmountPaise = 0L,
            )
        bookingRepository.saveBooking(tentative)
        bookingRepository.saveReminder(
            PaymentReminder(
                id = UUID.randomUUID().toString(),
                bookingId = tentative.id,
                businessId = Fixtures.BUSINESS_ID,
                remindOn = today(),
                status = ReminderStatus.PENDING,
                amountDueSnapshotPaise = 0L,
                createdAt = Fixtures.NOW,
                updatedAt = Fixtures.NOW,
                kind = ReminderKind.FOLLOW_UP,
            ),
        )
    }

    @Test
    fun `follow-up reminder with notification style posts WITHOUT a full-screen intent`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            seedDueFollowUp()

            engine().runDailyPass()

            val posted = postedNotifications().single()
            assertThat(posted.fullScreenIntent).isNull()
        }

    @Test
    fun `follow-up reminder with fullscreen style posts WITH a full-screen intent`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.FULLSCREEN)
            seedDueFollowUp()

            engine().runDailyPass()

            val posted = postedNotifications().single()
            assertThat(posted.fullScreenIntent).isNotNull()
        }

    // ---- upcoming-event reminders ----

    private suspend fun seedUpcomingBooking() {
        bookingRepository.saveBooking(
            Fixtures.booking(
                startDate = today().plusDays(1),
                endDate = today().plusDays(1),
            ),
        )
    }

    @Test
    fun `upcoming reminder with notification style posts directly and schedules no alarm`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            seedUpcomingBooking()

            engine().runDailyPass()

            assertThat(postedNotifications()).hasSize(1)
            assertThat(nextAlarm()).isNull()
        }

    @Test
    fun `upcoming reminder with fullscreen style schedules the exact alarm instead of posting`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.FULLSCREEN)
            seedUpcomingBooking()

            engine().runDailyPass()

            assertThat(postedNotifications()).isEmpty()
            assertThat(nextAlarm()).isNotNull()
        }
}
