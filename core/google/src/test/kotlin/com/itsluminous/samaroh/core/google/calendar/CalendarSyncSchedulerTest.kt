package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val BIZ = Fixtures.BUSINESS_ID

/**
 * ADR-051 on-change policy: a new debounced request must never CANCEL earlier calendar
 * work (the old REPLACE killed an in-flight pass mid-run — a torn pass plus a
 * JobCancellationException WARN). APPEND_OR_REPLACE chains the new request after the
 * existing one, so an in-flight pass completes and the follow-up still runs.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarSyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun onChangeWork(): List<WorkInfo> =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(CalendarSyncWorker.onChangeWorkName(BIZ))
            .get()

    @Test
    fun `a second on-change request appends - it never cancels the earlier one`() {
        val scheduler = CalendarSyncScheduler(context)

        scheduler.requestSyncOnLocalChange(BIZ)
        scheduler.requestSyncOnLocalChange(BIZ)

        val work = onChangeWork()
        // REPLACE would leave the first request CANCELLED; APPEND_OR_REPLACE chains both.
        assertThat(work).hasSize(2)
        assertThat(work.map { it.state }).doesNotContain(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `disable still cancels pending on-change work`() {
        val scheduler = CalendarSyncScheduler(context)
        scheduler.requestSyncOnLocalChange(BIZ)

        scheduler.disable(BIZ, removeEvents = false)

        assertThat(onChangeWork().map { it.state }).containsExactly(WorkInfo.State.CANCELLED)
    }
}
