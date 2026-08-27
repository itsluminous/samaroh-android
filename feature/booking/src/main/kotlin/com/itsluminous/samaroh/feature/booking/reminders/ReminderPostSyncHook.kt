package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-sync reminder re-planning (ADR-024, item "reminders must be registered on first
 * install"): whenever a sync pull APPLIES rows — most importantly the very first pull
 * after sign-in on a fresh install — this hook
 * 1. (re)ensures the daily 09:00 reminder worker exists (idempotent KEEP), so
 *    notifications fire even if the user never opens the Booking tab, and
 * 2. runs a full [ReminderEngine] pass immediately, so pulled future payment reminders
 *    and upcoming-event reminders get their notifications/exact alarms planned NOW, and
 *    stale pending reminders whose booking arrived settled are dismissed right away
 *    instead of lingering until the next daily pass.
 */
@Singleton
class ReminderPostSyncHook
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val engine: ReminderEngine,
    ) : PostSyncHook {
        override suspend fun onSyncApplied() {
            runCatching { BookingReminderWorker.ensureScheduled(context) }
            engine.runDailyPass()
        }
    }
