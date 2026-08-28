package com.itsluminous.samaroh.core.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.sync.engine.SyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync engine worker (§8). Triggers:
 * - connectivity: every request carries a CONNECTED constraint, so queued work fires the
 *   moment the network returns;
 * - periodic ~15 min ([WorkManagerSyncScheduler.ensurePeriodicSync]);
 * - app launch / foreground resume: [SyncStartupInitializer] requests an EXPEDITED
 *   one-shot so web-side edits appear right away (§8 "cold start triggers an immediate
 *   push AND pull");
 * - outbox enqueue (ADR-036): every queued local mutation requests a DEBOUNCED one-shot
 *   ([WorkManagerSyncScheduler.requestSyncOnLocalChange]) — online edits push within
 *   seconds instead of waiting for the next foreground/periodic trigger.
 *
 * Transport failures return [Result.retry] — exponential backoff per the request's
 * backoff criteria. Per-item failures (RLS) never fail the run; they surface via
 * `SyncStatus.itemErrors`.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun syncEngine(): SyncEngine

        fun syncRunState(): SyncRunState
    }

    override suspend fun doWork(): Result {
        val entryPoint =
            EntryPointAccessors
                .fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
        val engine = entryPoint.syncEngine()
        val runState = entryPoint.syncRunState()
        Log.i(TAG, "sync run started")
        runState.setRunning(true)
        val outcome =
            try {
                engine.runSync()
            } finally {
                runState.setRunning(false)
            }
        Log.i(
            TAG,
            "sync run finished: configured=${outcome.configured} pushed=${outcome.pushedCount} " +
                "pulled=${outcome.pulledCount} conflicts=${outcome.conflictCount} " +
                "itemErrors=${outcome.itemErrorCount} networkFailed=${outcome.networkFailed}",
        )
        return if (outcome.networkFailed) Result.retry() else Result.success()
    }

    /** Expedited work runs as a foreground task before Android S — a quiet localized notice. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        SyncNotifications.ensureChannel(applicationContext)
        val notification =
            NotificationCompat
                .Builder(applicationContext, SyncNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(applicationContext.getString(R.string.sync_notification_syncing))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotifications.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "samaroh-sync-periodic"
        const val UNIQUE_IMMEDIATE_NAME = "samaroh-sync-now"
        const val UNIQUE_ON_CHANGE_NAME = "samaroh-sync-on-change"

        /** Logcat tag for sync-trigger/run diagnostics (grep `SamarohSync`). */
        const val TAG = "SamarohSync"
    }
}

/** WorkManager-backed [SyncScheduler] (§8: expedited on demand + periodic 15 min, both connectivity-gated). */
@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncScheduler {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        override fun requestImmediateSync() {
            Log.i(SyncWorker.TAG, "expedited sync requested")
            WorkManager.getInstance(context).enqueueUniqueWork(
                SyncWorker.UNIQUE_IMMEDIATE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        override fun ensurePeriodicSync() {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SyncWorker.UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * Trailing debounce (ADR-036): each outbox write REPLACEs the unique on-change
         * request, restarting the short initial delay — a burst of edits collapses into
         * ONE run a few seconds after the LAST write. REPLACE (not KEEP) so a write that
         * lands while a change-sync is already RUNNING cancels it and reschedules —
         * nothing is ever silently dropped; the sync engine is cancellation-safe
         * (idempotent upserts, outbox rows removed only after a successful push). No
         * `setExpedited`: WorkManager forbids expedited work with an initial delay, and
         * a plain request runs promptly while the app is foregrounded (which it is —
         * the user just edited). Offline, the CONNECTED constraint holds the run until
         * the network returns.
         */
        override fun requestSyncOnLocalChange() {
            Log.i(SyncWorker.TAG, "debounced on-change sync requested")
            WorkManager.getInstance(context).enqueueUniqueWork(
                SyncWorker.UNIQUE_ON_CHANGE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(ON_CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        private companion object {
            const val PERIODIC_MINUTES = 15L
            const val BACKOFF_SECONDS = 30L
            const val ON_CHANGE_DEBOUNCE_SECONDS = 3L
        }
    }
