package com.itsluminous.samaroh.core.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * App-launch / foreground-resume sync trigger (§8): every ON_START of the process
 * lifecycle requests an expedited sync (push local offline edits AND pull web-side
 * changes so the calendar is fresh) and (re)ensures the periodic 15-minute schedule.
 *
 * Registered via androidx.startup from this module's manifest — no `:app` wiring needed.
 * The scheduler is resolved lazily inside ON_START (after `Application.onCreate`) so the
 * Hilt component exists; resolution failures (non-Hilt test hosts) are ignored.
 */
class SyncStartupInitializer : Initializer<Unit> {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncTriggerEntryPoint {
        fun syncScheduler(): SyncScheduler
    }

    override fun create(context: Context) {
        val appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching {
                        val scheduler =
                            EntryPointAccessors
                                .fromApplication(appContext, SyncTriggerEntryPoint::class.java)
                                .syncScheduler()
                        scheduler.ensurePeriodicSync()
                        scheduler.requestImmediateSync()
                    }
                }
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}
