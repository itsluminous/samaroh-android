package com.itsluminous.samaroh.core.google.calendar

import androidx.work.ListenableWorker.Result
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Failure-verdict contract of [CalendarSyncWorker] (emulator evidence 2026-09-06): an
 * unlinked user's booking mutations must NOT burn retries/stack traces on the
 * permanently-failing calendar push, while genuinely transient faults keep the
 * retry-with-backoff behavior.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarSyncWorkerTest {
    @Test
    fun `not linked is a quiet skip, not a retry`() {
        val result =
            CalendarSyncWorker.resolveFailure(
                DriveNotAvailableException("no google account linked"),
                runAttemptCount = 0,
            )
        assertThat(result).isEqualTo(Result.success())
    }

    @Test
    fun `no silent token on this device is a quiet skip too`() {
        val result =
            CalendarSyncWorker.resolveFailure(
                DriveNotAvailableException("no google access token available"),
                runAttemptCount = 3,
            )
        assertThat(result).isEqualTo(Result.success())
    }

    @Test
    fun `transient failure retries below the attempt cap`() {
        val result = CalendarSyncWorker.resolveFailure(IOException("timeout"), runAttemptCount = 2)
        assertThat(result).isEqualTo(Result.retry())
    }

    @Test
    fun `transient failure gives up at the attempt cap`() {
        val result =
            CalendarSyncWorker.resolveFailure(
                IOException("timeout"),
                runAttemptCount = CalendarSyncWorker.MAX_ATTEMPTS,
            )
        assertThat(result).isEqualTo(Result.failure())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown - never logged or counted as a failed attempt`() {
        // ADR-051: WorkManager cancelling the worker is not a pass failure; mapping it to
        // a WARN + retry produced "attempt 0 failed / JobCancellationException" noise.
        CalendarSyncWorker.resolveFailure(CancellationException("worker replaced"), runAttemptCount = 0)
    }
}
