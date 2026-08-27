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
            foregroundSyncObserver {
                EntryPointAccessors
                    .fromApplication(appContext, SyncTriggerEntryPoint::class.java)
                    .syncScheduler()
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}

/**
 * The foreground-resume trigger, isolated for unit tests: EVERY ON_START (cold start and
 * every background→foreground transition — ProcessLifecycleOwner emits ON_START on each)
 * requests an expedited sync and re-ensures the periodic schedule. [resolveScheduler] is
 * invoked lazily per event (after `Application.onCreate`, so the Hilt component exists);
 * resolution failures (non-Hilt test hosts) are swallowed.
 */
internal fun foregroundSyncObserver(resolveScheduler: () -> SyncScheduler): DefaultLifecycleObserver =
    object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val scheduler = runCatching(resolveScheduler).getOrNull() ?: return
            scheduler.ensurePeriodicSync()
            scheduler.requestImmediateSync()
        }
    }
