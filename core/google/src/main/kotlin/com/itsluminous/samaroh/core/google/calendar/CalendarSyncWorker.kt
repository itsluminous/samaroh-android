package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager job driving the one-way calendar push (§4.1). Plain (non-Hilt) worker
 * resolved through an entry point so no custom `Configuration.Provider` is needed in :app.
 */
class CalendarSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CalendarSyncEntryPoint {
        fun calendarSyncEngine(): CalendarSyncEngine
    }

    override suspend fun doWork(): Result {
        if (!GoogleServicesConfig.isConfigured) return Result.success()
        val businessId = inputData.getString(KEY_BUSINESS_ID) ?: return Result.failure()
        val engine =
            EntryPointAccessors
                .fromApplication(applicationContext, CalendarSyncEntryPoint::class.java)
                .calendarSyncEngine()
        val result =
            when (inputData.getString(KEY_ACTION)) {
                ACTION_DISABLE_CLEANUP -> engine.disable(businessId, removeEvents = true)
                else -> engine.syncBusiness(businessId)
            }
        return result.fold(
            onSuccess = { Result.success() },
            onFailure = { resolveFailure(it, runAttemptCount) },
        )
    }

    companion object {
        const val KEY_BUSINESS_ID = "business_id"
        const val KEY_ACTION = "action"
        const val ACTION_SYNC = "sync"
        const val ACTION_DISABLE_CLEANUP = "disable_cleanup"
        const val MAX_ATTEMPTS = 5

        /**
         * Maps an engine failure to the worker verdict. [DriveNotAvailableException]
         * (not signed in / no Google account linked / no silent token on this device) is
         * a PERMANENT local state, not a transient fault — retrying can never succeed and
         * every booking mutation would burn [MAX_ATTEMPTS] stack traces. Skip quietly as
         * success; the link flow (Settings §4.4 / the expenses prompt) kicks a fresh sync
         * on success, and the periodic catch-up covers the rest. A [CancellationException]
         * is RETHROWN (ADR-051): a cancelled worker is not a failed pass — logging it as
         * "attempt N failed" and returning retry was pure noise plus a wasted retry.
         * Anything else (network, HTTP 401/5xx) retries with backoff up to [MAX_ATTEMPTS].
         */
        internal fun resolveFailure(
            error: Throwable,
            runAttemptCount: Int,
        ): Result {
            if (error is CancellationException) throw error
            if (error is DriveNotAvailableException) {
                Log.i(CalendarSyncEngine.TAG, "calendar sync skipped: ${error.message}")
                return Result.success()
            }
            Log.w(CalendarSyncEngine.TAG, "calendar sync attempt $runAttemptCount failed", error)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

        fun periodicWorkName(businessId: String) = "samaroh-gcal-periodic-$businessId"

        fun oneShotWorkName(businessId: String) = "samaroh-gcal-now-$businessId"

        /** Debounced booking-mutation trigger (ADR-046) — own name so REPLACE never eats [oneShotWorkName]. */
        fun onChangeWorkName(businessId: String) = "samaroh-gcal-change-$businessId"

        /**
         * Disable-cleanup gets its OWN unique name (ADR-046): it used to share
         * [oneShotWorkName], so a pending cleanup silently swallowed the enable-time
         * bulk push (KEEP policy) — the "enable did nothing" race.
         */
        fun cleanupWorkName(businessId: String) = "samaroh-gcal-cleanup-$businessId"
    }
}

/** Schedules calendar pushes: immediate (enable / booking change) and periodic catch-up. */
@Singleton
class CalendarSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** One-shot push — call on enable (bulk push). Also cancels any pending disable-cleanup. */
        fun requestSync(businessId: String) {
            val workManager = WorkManager.getInstance(context)
            // Re-enabling must beat a still-pending disable-cleanup, or the cleanup
            // would delete the events the enable-time push just (re)created.
            workManager.cancelUniqueWork(CalendarSyncWorker.cleanupWorkName(businessId))
            workManager.enqueueUniqueWork(
                CalendarSyncWorker.oneShotWorkName(businessId),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            CalendarSyncWorker.KEY_BUSINESS_ID to businessId,
                            CalendarSyncWorker.KEY_ACTION to CalendarSyncWorker.ACTION_SYNC,
                        ),
                    ).build(),
            )
        }

        /**
         * Debounced push after a local booking mutation (ADR-046, mirrors the ADR-036
         * data-sync debounce): every booking/payment outbox write lands here, so
         * create/edit/cancel/delete reach the calendar within seconds instead of the
         * 6-hour periodic. The short initial delay collapses an edit burst while the
         * request is still pending; offline, the CONNECTED constraint holds it.
         *
         * APPEND_OR_REPLACE, not REPLACE (ADR-051): REPLACE CANCELLED an in-flight
         * worker mid-pass — noise ("attempt 0 failed"/JobCancellationException before
         * the engine rethrow fix) plus a torn pass. APPEND_OR_REPLACE lets a RUNNING
         * pass complete and chains the new request after it (a pass that finds nothing
         * new plans empty and makes zero HTTP, so appended follow-ups are free); if the
         * prior work failed or was cancelled the new request replaces it instead of
         * inheriting the dead chain.
         */
        fun requestSyncOnLocalChange(businessId: String) {
            Log.i(CalendarSyncEngine.TAG, "debounced calendar push requested for $businessId")
            WorkManager.getInstance(context).enqueueUniqueWork(
                CalendarSyncWorker.onChangeWorkName(businessId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(ON_CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .setInputData(
                        workDataOf(
                            CalendarSyncWorker.KEY_BUSINESS_ID to businessId,
                            CalendarSyncWorker.KEY_ACTION to CalendarSyncWorker.ACTION_SYNC,
                        ),
                    ).build(),
            )
        }

        /** Periodic catch-up so changes made while offline eventually push. */
        fun ensurePeriodicSync(businessId: String) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CalendarSyncWorker.periodicWorkName(businessId),
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            CalendarSyncWorker.KEY_BUSINESS_ID to businessId,
                            CalendarSyncWorker.KEY_ACTION to CalendarSyncWorker.ACTION_SYNC,
                        ),
                    ).build(),
            )
        }

        /** Stops pushing; with [removeEvents] also deletes every synced event (§4.1 disable). */
        fun disable(
            businessId: String,
            removeEvents: Boolean,
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(CalendarSyncWorker.periodicWorkName(businessId))
            workManager.cancelUniqueWork(CalendarSyncWorker.oneShotWorkName(businessId))
            workManager.cancelUniqueWork(CalendarSyncWorker.onChangeWorkName(businessId))
            if (removeEvents) {
                workManager.enqueueUniqueWork(
                    CalendarSyncWorker.cleanupWorkName(businessId),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                        .setConstraints(constraints)
                        .setInputData(
                            workDataOf(
                                CalendarSyncWorker.KEY_BUSINESS_ID to businessId,
                                CalendarSyncWorker.KEY_ACTION to CalendarSyncWorker.ACTION_DISABLE_CLEANUP,
                            ),
                        ).build(),
                )
            }
        }

        private companion object {
            const val ON_CHANGE_DEBOUNCE_SECONDS = 3L
        }
    }
