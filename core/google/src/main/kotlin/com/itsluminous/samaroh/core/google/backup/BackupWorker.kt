package com.itsluminous.samaroh.core.google.backup

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

/** Backup frequencies from `business_settings.backup_frequency` (§4.4). */
enum class BackupFrequency(
    val wire: String,
) {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromWire(value: String): BackupFrequency = entries.firstOrNull { it.wire == value } ?: WEEKLY
    }
}

/**
 * WorkManager job running one backup pass. A plain (non-Hilt) worker resolved through an
 * entry point so no custom `Configuration.Provider` is required in :app (which W1-F must
 * not touch).
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupEntryPoint {
        fun backupEngine(): BackupEngine
    }

    override suspend fun doWork(): Result {
        // Not configured → nothing to do; succeed quietly instead of piling up retries.
        if (!GoogleServicesConfig.isConfigured) return Result.success()
        val businessId = inputData.getString(KEY_BUSINESS_ID) ?: return Result.failure()
        val engine = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java).backupEngine()
        return engine.backUpNow(businessId).fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    companion object {
        const val KEY_BUSINESS_ID = "business_id"
        const val MAX_ATTEMPTS = 5

        fun periodicWorkName(businessId: String) = "samaroh-backup-periodic-$businessId"

        fun manualWorkName(businessId: String) = "samaroh-backup-now-$businessId"
    }
}

/** Schedules periodic (daily/weekly/monthly) and manual backups (§4.4). */
@Singleton
class BackupScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Aligns the periodic job with [frequency]; MANUAL cancels the periodic job. */
        fun applyFrequency(
            businessId: String,
            frequency: BackupFrequency,
        ) {
            val workManager = WorkManager.getInstance(context)
            val repeatDays =
                when (frequency) {
                    BackupFrequency.DAILY -> 1L
                    BackupFrequency.WEEKLY -> 7L
                    BackupFrequency.MONTHLY -> 30L
                    BackupFrequency.MANUAL -> {
                        workManager.cancelUniqueWork(BackupWorker.periodicWorkName(businessId))
                        return
                    }
                }
            workManager.enqueueUniquePeriodicWork(
                BackupWorker.periodicWorkName(businessId),
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(repeatDays, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(BackupWorker.KEY_BUSINESS_ID to businessId))
                    .build(),
            )
        }

        /** "Back up now" (§4.4). */
        fun backUpNow(businessId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                BackupWorker.manualWorkName(businessId),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(BackupWorker.KEY_BUSINESS_ID to businessId))
                    .build(),
            )
        }
    }
