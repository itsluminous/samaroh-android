package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-launch periodic-calendar registration (ADR-066): every process ON_START (cold
 * start and each background→foreground transition) runs
 * [CalendarPeriodicReconciler.reconcile], so a reinstall or a sign-in on a new device
 * gets the 6-hour catch-up job without ever visiting the Settings gcal toggle — the
 * calendar twin of [com.itsluminous.samaroh.core.sync.SyncStartupInitializer] (§8) and
 * the reminder registration (ADR-024).
 *
 * Registered via androidx.startup from this module's manifest — no `:app` wiring needed.
 * The reconciler is resolved lazily inside ON_START (after `Application.onCreate`) so
 * the Hilt component exists; resolution failures (non-Hilt test hosts) are ignored. The
 * reconcile itself is suspend (local Room reads only) and runs on a background scope —
 * failures are logged, never crash app start.
 */
class CalendarSyncStartupInitializer : Initializer<Unit> {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReconcilerEntryPoint {
        fun calendarPeriodicReconciler(): CalendarPeriodicReconciler
    }

    override fun create(context: Context) {
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            calendarReconcileObserver {
                val reconciler =
                    runCatching {
                        EntryPointAccessors
                            .fromApplication(appContext, ReconcilerEntryPoint::class.java)
                            .calendarPeriodicReconciler()
                    }.getOrNull() ?: return@calendarReconcileObserver
                scope.launch {
                    runCatching { reconciler.reconcile() }
                        .onFailure { Log.w(CalendarSyncEngine.TAG, "periodic calendar reconcile failed", it) }
                }
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}

/**
 * The foreground reconcile trigger, isolated for unit tests: every ON_START invokes
 * [reconcile]; failures (no Hilt component in bare test hosts) are swallowed so app
 * start never crashes over a calendar schedule.
 */
internal fun calendarReconcileObserver(reconcile: () -> Unit): DefaultLifecycleObserver =
    object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            runCatching(reconcile)
        }
    }
