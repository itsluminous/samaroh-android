package com.itsluminous.samaroh.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync engine skeleton (§8). Wave 0 ships the outbox writer and this worker shell; the
 * full push/pull pipeline (attachment uploads, Postgrest upserts, incremental pull with
 * `updated_at` cursors, LWW conflict handling) is the W1-E deliverable.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // TODO(W1-E): 1. Drain outbox FIFO (upload attachments, then upsert rows via Postgrest).
        // TODO(W1-E): 2. Pull per-table incremental changes (updated_at > last_pull_cursor per business).
        // TODO(W1-E): 3. Apply LWW conflict resolution; surface rebased/dropped local edits visibly.
        // TODO(W1-E): 4. Mark RLS-rejected ops as errors for the Settings sync-status screen.
        return Result.success()
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "samaroh-sync-periodic"
        const val UNIQUE_IMMEDIATE_NAME = "samaroh-sync-now"
    }
}

/** WorkManager-backed [SyncScheduler] (§8: expedited on demand + periodic 15 min). */
@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncScheduler {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        override fun requestImmediateSync() {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SyncWorker.UNIQUE_IMMEDIATE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
            )
        }

        override fun ensurePeriodicSync() {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SyncWorker.UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
            )
        }
    }
