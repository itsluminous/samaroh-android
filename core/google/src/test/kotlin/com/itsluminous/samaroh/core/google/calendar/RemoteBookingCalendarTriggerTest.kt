package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

private const val BIZ = Fixtures.BUSINESS_ID

/**
 * ADR-047: a sync pull that applied bookings/booking_payments of a gcal-enabled
 * business must enqueue the debounced calendar one-shot — a remote member's edit
 * reaches the calendar within one sync cycle; anything else must not.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteBookingCalendarTriggerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeBusinessRepository(
        private val enabled: Boolean,
    ) : BusinessRepository {
        override fun businesses(): Flow<List<Business>> = flowOf(emptyList())

        override suspend fun business(id: String): Business? = null

        override suspend fun saveBusiness(business: Business) = error("unused")

        override fun settings(businessId: String): Flow<BusinessSettings?> =
            flowOf(BusinessSettings(businessId = businessId, gcalSyncEnabled = enabled, updatedAt = Instant.EPOCH))

        override suspend fun saveSettings(settings: BusinessSettings) = error("unused")
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun pendingOnChangeWork(businessId: String = BIZ): Int =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(CalendarSyncWorker.onChangeWorkName(businessId))
            .get()
            .count { !it.state.isFinished }

    private fun trigger(enabled: Boolean = true) =
        RemoteBookingCalendarTrigger(
            businessRepository = FakeBusinessRepository(enabled),
            scheduler = CalendarSyncScheduler(context),
        )

    @Test
    fun `applied bookings of a gcal-enabled business enqueue the calendar push`() =
        runTest {
            trigger().onRemoteChangesApplied(mapOf("bookings" to setOf(BIZ)))
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `applied booking payments also enqueue - amounts are part of the event description`() =
        runTest {
            trigger().onRemoteChangesApplied(mapOf("booking_payments" to setOf(BIZ)))
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `one debounced request per business even when both tables applied`() =
        runTest {
            trigger().onRemoteChangesApplied(mapOf("bookings" to setOf(BIZ), "booking_payments" to setOf(BIZ)))
            // REPLACE on the unique work name — a burst collapses into one pending run.
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `irrelevant tables do not enqueue`() =
        runTest {
            trigger().onRemoteChangesApplied(mapOf("expenses" to setOf(BIZ), "master_items" to setOf(BIZ)))
            assertThat(pendingOnChangeWork()).isEqualTo(0)
        }

    @Test
    fun `gcal-disabled business does not enqueue`() =
        runTest {
            trigger(enabled = false).onRemoteChangesApplied(mapOf("bookings" to setOf(BIZ)))
            assertThat(pendingOnChangeWork()).isEqualTo(0)
        }
}
