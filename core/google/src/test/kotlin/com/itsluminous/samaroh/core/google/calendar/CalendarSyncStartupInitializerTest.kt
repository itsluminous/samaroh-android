package com.itsluminous.samaroh.core.google.calendar

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADR-066 startup trigger: every process ON_START (cold start AND background→foreground)
 * runs the periodic-calendar reconcile, so a fresh install / new device gets the 6-hour
 * catch-up WITHOUT ever touching the Settings gcal toggle.
 */
class CalendarSyncStartupInitializerTest {
    // onStart never touches the owner; ProcessLifecycleOwner is not needed for the unit.
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = throw UnsupportedOperationException("not used by the observer")
        }

    @Test
    fun `every ON_START runs the periodic-calendar reconcile`() {
        var reconciled = 0
        val observer = calendarReconcileObserver { reconciled++ }

        observer.onStart(owner) // cold start
        observer.onStart(owner) // background → foreground

        assertThat(reconciled).isEqualTo(2)
    }

    @Test
    fun `reconcile failure is swallowed - app start must not crash over a calendar schedule`() {
        val observer = calendarReconcileObserver { error("no hilt component") }

        observer.onStart(owner) // must not throw
    }
}
