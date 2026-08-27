package com.itsluminous.samaroh.feature.booking.reminders

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * First-launch registration trigger (ADR-024): every process ON_START re-ensures the
 * daily reminder worker, so a fresh install gets reminders WITHOUT ever opening the
 * Booking tab.
 */
class BookingReminderStartupInitializerTest {
    // onStart never touches the owner; ProcessLifecycleOwner is not needed for the unit.
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = throw UnsupportedOperationException("not used by the observer")
        }

    @Test
    fun `every ON_START re-ensures the daily reminder schedule`() {
        var ensured = 0
        val observer = reminderScheduleObserver { ensured++ }

        observer.onStart(owner) // cold start
        observer.onStart(owner) // background → foreground

        assertThat(ensured).isEqualTo(2)
    }

    @Test
    fun `scheduling failure is swallowed - app start must not crash over a reminder job`() {
        val observer = reminderScheduleObserver { error("workmanager not initialized") }

        observer.onStart(owner) // must not throw
    }
}
