package com.itsluminous.samaroh.feature.booking.reminders

import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-out hook for reminder delivery state (ADR-095, on the ADR-040 contract): reminder
 * acknowledgements, scheduled alarms and snoozes belong to the signed-in user on THIS
 * device. On sign-out every pending snooze alarm is disarmed and the whole
 * [ReminderLedger] (every scope + the remembered scope) is wiped, so the next account on
 * a shared device starts from a clean slate — nothing it was never shown counts as
 * "already dismissed". Reminder ROWS need no hook: they live in Room, which ADR-040
 * already clears.
 */
@Singleton
class ReminderSessionStore
    @Inject
    constructor(
        private val ledger: ReminderLedger,
        private val snoozer: ReminderSnoozer,
    ) : SessionScopedStore {
        override suspend fun clearForSignOut() {
            ledger
                .all()
                .filter { (_, entry) -> entry.state == ReminderFireState.SNOOZED }
                .keys
                .forEach(snoozer::cancel)
            ledger.clearAll()
        }
    }
