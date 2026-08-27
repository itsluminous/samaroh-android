package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer

/**
 * App-launch reminder-worker registration (ADR-024): every process ON_START (cold start
 * and each background→foreground transition) re-ensures the daily 09:00
 * [BookingReminderWorker] schedule. Before this, the worker was only scheduled on
 * Booking-tab entry — a fresh install whose user never opened the tab (or an OEM that
 * wiped WorkManager jobs) got NO reminder notifications at all.
 *
 * Registered via androidx.startup from this module's manifest — no `:app` wiring needed.
 * [BookingReminderWorker.ensureScheduled] is idempotent (KEEP policy) and cheap.
 */
class BookingReminderStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            reminderScheduleObserver { BookingReminderWorker.ensureScheduled(appContext) },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}

/**
 * The foreground registration trigger, isolated for unit tests: every ON_START invokes
 * [ensureScheduled]; failures (WorkManager not initialized in bare test hosts) are
 * swallowed so app start never crashes over a reminder schedule.
 */
internal fun reminderScheduleObserver(ensureScheduled: () -> Unit): DefaultLifecycleObserver =
    object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            runCatching(ensureScheduled)
        }
    }
