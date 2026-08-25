package com.itsluminous.samaroh.core.google.calendar

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
import androidx.work.workDataOf
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
            onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    companion object {
        const val KEY_BUSINESS_ID = "business_id"
        const val KEY_ACTION = "action"
        const val ACTION_SYNC = "sync"
        const val ACTION_DISABLE_CLEANUP = "disable_cleanup"
        const val MAX_ATTEMPTS = 5

        fun periodicWorkName(businessId: String) = "samaroh-gcal-periodic-$businessId"

        fun oneShotWorkName(businessId: String) = "samaroh-gcal-now-$businessId"
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

        /** One-shot push — call on enable (bulk push) and after booking mutations. */
        fun requestSync(businessId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
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
            if (removeEvents) {
                workManager.enqueueUniqueWork(
                    CalendarSyncWorker.oneShotWorkName(businessId),
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
    }
