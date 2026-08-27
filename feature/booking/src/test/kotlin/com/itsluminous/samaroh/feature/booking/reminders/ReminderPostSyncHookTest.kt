package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.junit.Before
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
 * Post-sync reminder re-registration (ADR-024): after a pull applies rows — most
 * importantly the FIRST pull on a fresh install — the hook must (1) register the daily
 * reminder worker and (2) run an engine pass so pulled reminders act immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderPostSyncHookTest {
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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun hook(): ReminderPostSyncHook =
        ReminderPostSyncHook(
            context = context,
            engine =
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
                ),
        )

    @Test
    fun `onSyncApplied registers the daily reminder worker - fresh install first sync`() =
        runTest(dispatcher) {
            hook().onSyncApplied()

            val info =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(BookingReminderWorker.UNIQUE_NAME)
                    .get()
                    .single()
            assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        }

    @Test
    fun `onSyncApplied runs an engine pass - pulled stale reminder is dismissed immediately`() =
        runTest(dispatcher) {
            val day = LocalDate.now(clock)
            // A settled past booking arrived via sync together with a still-pending
            // reminder created before the settling payment existed on this device.
            val booking =
                Fixtures.booking(
                    startDate = day.minusDays(30),
                    endDate = day.minusDays(29),
                    totalAmountPaise = 1_00_000_00L,
                )
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 1_00_000_00L))
            bookingRepository.saveReminder(
                PaymentReminder(
                    id = UUID.randomUUID().toString(),
                    bookingId = booking.id,
                    businessId = Fixtures.BUSINESS_ID,
                    remindOn = day.minusDays(1),
                    status = ReminderStatus.PENDING,
                    amountDueSnapshotPaise = 1_00_000_00L,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                ),
            )

            hook().onSyncApplied()

            val reminder = bookingRepository.reminders.value.single()
            assertThat(reminder.status).isEqualTo(ReminderStatus.DISMISSED)
        }
}
