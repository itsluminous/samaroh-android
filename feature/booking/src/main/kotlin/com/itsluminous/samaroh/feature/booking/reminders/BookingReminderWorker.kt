package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.samaroh.feature.booking.domain.UpcomingReminderPlanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily 09:00 reminder job (§4.1): payment confirmations ("Did {customer} pay ₹{due}?")
 * and upcoming-event reminders. Dependencies come through a Hilt entry point so the
 * worker needs no custom WorkerFactory wiring in `:app`.
 */
class BookingReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun reminderEngine(): ReminderEngine
    }

    override suspend fun doWork(): Result {
        val engine =
            EntryPointAccessors
                .fromApplication(applicationContext, Dependencies::class.java)
                .reminderEngine()
        return runCatching { engine.runDailyPass() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
            )
    }

    companion object {
        const val UNIQUE_NAME = "booking-daily-reminders"

        /**
         * Ensures the periodic job exists, first firing at the next local 09:00.
         * Idempotent (KEEP) — safe to call from screen entry.
         */
        fun ensureScheduled(
            context: Context,
            clock: Clock = Clock.systemDefaultZone(),
        ) {
            val delay = UpcomingReminderPlanner.delayUntilNextRun(ZonedDateTime.now(clock))
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BookingReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
