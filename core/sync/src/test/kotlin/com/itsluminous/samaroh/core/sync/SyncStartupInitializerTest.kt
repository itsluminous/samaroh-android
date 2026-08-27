package com.itsluminous.samaroh.core.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import org.junit.Test

/** §8 foreground trigger: every process ON_START (cold start AND background→foreground). */
class SyncStartupInitializerTest {
    private class RecordingScheduler : SyncScheduler {
        var immediate = 0
        var periodic = 0

        override fun requestImmediateSync() {
            immediate++
        }

        override fun ensurePeriodicSync() {
            periodic++
        }
    }

    // onStart never touches the owner; ProcessLifecycleOwner is not needed for the unit.
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = throw UnsupportedOperationException("not used by the observer")
        }

    @Test
    fun `every ON_START requests an expedited sync and re-ensures the periodic schedule`() {
        val scheduler = RecordingScheduler()
        val observer = foregroundSyncObserver { scheduler }

        observer.onStart(owner) // cold start
        observer.onStart(owner) // background → foreground

        assertThat(scheduler.immediate).isEqualTo(2)
        assertThat(scheduler.periodic).isEqualTo(2)
    }

    @Test
    fun `scheduler resolution failure is swallowed - non-hilt hosts must not crash`() {
        val observer = foregroundSyncObserver { error("no hilt component") }

        observer.onStart(owner) // must not throw
    }
}
