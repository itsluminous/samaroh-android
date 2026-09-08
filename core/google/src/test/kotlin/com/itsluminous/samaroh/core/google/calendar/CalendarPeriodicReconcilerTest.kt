package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
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
 * ADR-066: the 6-hour periodic catch-up must exist whenever a business has gcal sync
 * enabled AND a Google account is linked — regardless of whether THIS install ever saw
 * the Settings toggle (reinstall / new device / sync-delivered enable). Disabled
 * businesses must end up with NO periodic job; the reconcile is idempotent (KEEP).
 */
@RunWith(RobolectricTestRunner::class)
class CalendarPeriodicReconcilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeBusinessRepository(
        private val gcalEnabled: Boolean,
    ) : BusinessRepository {
        override fun businesses(): Flow<List<Business>> = flowOf(listOf(Fixtures.business()))

        override suspend fun business(id: String): Business? = null

        override suspend fun saveBusiness(business: Business) = error("unused")

        override fun settings(businessId: String): Flow<BusinessSettings?> =
            flowOf(BusinessSettings(businessId = businessId, gcalSyncEnabled = gcalEnabled, updatedAt = Instant.EPOCH))

        override suspend fun saveSettings(settings: BusinessSettings) = error("unused")
    }

    private class FakeLinkDao(
        private val linked: Boolean,
    ) : GoogleAccountLinkDao {
        override suspend fun upsert(link: GoogleAccountLinkEntity) = error("unused")

        override fun linkForUser(userId: String): Flow<GoogleAccountLinkEntity?> = error("unused")

        override suspend fun byId(userId: String): GoogleAccountLinkEntity? = error("unused")

        override suspend fun hasAnyLink(): Boolean = linked

        override suspend fun unlink(userId: String) = error("unused")
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun periodicWork(): List<WorkInfo> =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(CalendarSyncWorker.periodicWorkName(BIZ))
            .get()

    private fun pendingPeriodicWork(): Int = periodicWork().count { !it.state.isFinished }

    private fun reconciler(
        enabled: Boolean = true,
        linked: Boolean = true,
    ) = CalendarPeriodicReconciler(
        businessRepository = FakeBusinessRepository(enabled),
        linkDao = FakeLinkDao(linked),
        scheduler = CalendarSyncScheduler(context),
    )

    @Test
    fun `enabled and linked - reconcile schedules the periodic catch-up`() =
        runTest {
            reconciler().reconcile()
            assertThat(pendingPeriodicWork()).isEqualTo(1)
        }

    @Test
    fun `disabled - reconcile leaves no periodic job`() =
        runTest {
            reconciler(enabled = false).reconcile()
            assertThat(pendingPeriodicWork()).isEqualTo(0)
        }

    @Test
    fun `disabled - reconcile cancels a previously scheduled periodic job`() =
        runTest {
            // A sync-delivered disable: the toggle path scheduled it earlier on this device.
            CalendarSyncScheduler(context).ensurePeriodicSync(BIZ)
            reconciler(enabled = false).reconcile()
            assertThat(pendingPeriodicWork()).isEqualTo(0)
        }

    @Test
    fun `enabled but not linked - reconcile schedules nothing`() =
        runTest {
            reconciler(linked = false).reconcile()
            assertThat(periodicWork()).isEmpty()
        }

    @Test
    fun `reconcile is idempotent - a second pass keeps the single WorkSpec (KEEP)`() =
        runTest {
            val reconciler = reconciler()
            reconciler.reconcile()
            val firstId = periodicWork().single().id

            reconciler.reconcile()

            val work = periodicWork()
            assertThat(work).hasSize(1)
            // KEEP must preserve the original request — no cancel/replace churn.
            assertThat(work.single().id).isEqualTo(firstId)
        }

    @Test
    fun `applied business_settings rows trigger the reconcile - a remote enable schedules without app restart`() =
        runTest {
            reconciler().onRemoteChangesApplied(mapOf("business_settings" to setOf(BIZ)))
            assertThat(pendingPeriodicWork()).isEqualTo(1)
        }

    @Test
    fun `other applied tables do not trigger the reconcile`() =
        runTest {
            reconciler().onRemoteChangesApplied(mapOf("bookings" to setOf(BIZ)))
            assertThat(periodicWork()).isEmpty()
        }
}
