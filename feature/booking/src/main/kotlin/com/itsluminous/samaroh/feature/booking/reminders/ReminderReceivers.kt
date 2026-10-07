package com.itsluminous.samaroh.feature.booking.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Shared Hilt entry point for the reminder receivers (no `:app` wiring needed). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderReceiverDependencies {
    fun engine(): ReminderEngine

    fun ledger(): ReminderLedger

    fun snoozer(): ReminderSnoozer

    fun notifier(): BookingNotifier
}

/**
 * Resolves the Hilt graph, or null when no component exists yet. Production always has
 * one (the application creates it in `onCreate`, before any receiver runs), but the
 * instrumented suite's `HiltTestApplication` only builds it inside `HiltAndroidRule` —
 * and installing the test APK fires `MY_PACKAGE_REPLACED` into [ReminderBootReceiver]
 * first, which crashed the test process before a single test started (nightly CI since
 * v0.20.0). A receiver with no graph has nothing to do and simply returns.
 */
internal fun reminderDeps(context: Context): ReminderReceiverDependencies? =
    runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, ReminderReceiverDependencies::class.java)
    }.getOrNull()

/** Runs [block] on IO with the receiver kept alive via `goAsync` (ADR-094 receivers all persist state). */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val result = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            runCatching { block() }
        } finally {
            result.finish()
        }
    }
}

/**
 * Snooze re-fire (ADR-094): the one-shot alarm armed by [ReminderSnoozer] lands here;
 * [ReminderEngine.refire] re-validates the booking and posts through the normal style
 * pipeline (or drops the reminder when the booking is paid/cancelled/deleted — ADR-064).
 */
class ReminderSnoozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val key = ReminderLedgerKey.decode(intent.getStringExtra(EXTRA_LEDGER_KEY)) ?: return
        val deps = reminderDeps(context) ?: return
        runAsync { deps.engine().refire(key) }
    }

    companion object {
        const val ACTION_REFIRE = "com.itsluminous.samaroh.booking.action.REMINDER_SNOOZE_REFIRE"
    }
}

/**
 * Swipe-dismiss / clear-all / tap-to-open acknowledgement (ADR-094): every reminder
 * notification carries this as its `deleteIntent`, so the OS tells us when the USER
 * removed it and the ledger marks the key ACKED — the planner never re-posts it.
 * (`NotificationManager.cancel` by our own code does NOT deliver a deleteIntent, which
 * is exactly right: an app-side cancel is not an acknowledgement.)
 */
class ReminderAckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val key = ReminderLedgerKey.decode(intent.getStringExtra(EXTRA_LEDGER_KEY)) ?: return
        val deps = reminderDeps(context) ?: return
        runAsync {
            deps.snoozer().cancel(key)
            deps.ledger().ack(key, fallbackKeepUntil = LocalDate.now().plusDays(ReminderLedger.ROW_KEEP_DAYS))
        }
    }

    companion object {
        const val ACTION_ACK = "com.itsluminous.samaroh.booking.action.REMINDER_ACK"
    }
}

/**
 * Reboot recovery (ADR-094): Android drops every armed alarm and posted notification
 * at boot. Re-arm pending snoozes, forget FIRED-but-unacknowledged deliveries (the
 * system lost them, the user never dismissed them) and run a planning pass so today's
 * upcoming alarms are re-scheduled. ACKED entries stay acked — a dismissed reminder
 * stays dismissed across reboots. An app UPDATE only loses alarms (posted notifications
 * survive it), so it re-arms and re-plans without forgetting FIRED entries.
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val deps = reminderDeps(context) ?: return
        runAsync {
            val ledger = deps.ledger()
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) ledger.resetFiredForBoot()
            ledger
                .all()
                .filter { (_, entry) -> entry.state == ReminderFireState.SNOOZED && entry.snoozedUntilMillis != null }
                .forEach { (key, entry) -> deps.snoozer().arm(key, checkNotNull(entry.snoozedUntilMillis)) }
            deps.engine().runDailyPass()
        }
    }
}
