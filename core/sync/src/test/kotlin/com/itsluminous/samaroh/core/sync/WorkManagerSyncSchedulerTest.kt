package com.itsluminous.samaroh.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-036 debounce/uniqueness contract of [WorkManagerSyncScheduler.requestSyncOnLocalChange]:
 * a burst of outbox writes collapses into ONE pending run (unique work + REPLACE), the run
 * waits a short initial delay and a CONNECTED network, and the on-change chain is
 * independent of the expedited "sync now" chain.
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerSyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler(context)
    }

    private fun onChangeInfos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_ON_CHANGE_NAME).get()

    @Test
    fun `a burst of on-change requests collapses into exactly one pending run`() {
        repeat(5) { scheduler.requestSyncOnLocalChange() }

        val pending = onChangeInfos().filterNot { it.state.isFinished }
        assertThat(pending).hasSize(1)
        assertThat(pending.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `on-change run is debounced and connectivity-gated`() {
        scheduler.requestSyncOnLocalChange()

        val info = onChangeInfos().single { !it.state.isFinished }
        // Trailing debounce: the run waits a short delay so burst edits coalesce.
        assertThat(info.initialDelayMillis).isGreaterThan(0L)
        // Offline just stays queued: the CONNECTED constraint holds the run.
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `on-change chain is separate from the expedited sync-now chain`() {
        scheduler.requestSyncOnLocalChange()
        scheduler.requestImmediateSync()

        val immediate = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_IMMEDIATE_NAME).get()
        assertThat(immediate).hasSize(1)
        assertThat(onChangeInfos().filterNot { it.state.isFinished }).hasSize(1)
        // The expedited chain keeps running work (KEEP); the on-change chain replaces —
        // neither cancels the other.
        assertThat(immediate.single().initialDelayMillis).isEqualTo(0L)
    }
}
