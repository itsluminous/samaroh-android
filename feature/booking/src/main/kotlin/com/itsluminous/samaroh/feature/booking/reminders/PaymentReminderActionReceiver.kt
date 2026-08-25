package com.itsluminous.samaroh.feature.booking.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.feature.booking.domain.DueCalculator
import com.itsluminous.samaroh.feature.booking.domain.PaymentReminderPlanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Handles the payment-reminder notification actions (§4.1):
 * - **Yes, full** — records a payment of the entire outstanding due (dated today) and
 *   confirms the reminder;
 * - **Not yet** — snoozes the reminder and chains the next one at +7 days.
 * (The **Partial…** action opens the app instead — see [BookingNotifier].)
 */
class PaymentReminderActionReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun bookingRepository(): BookingRepository

        fun notifier(): BookingNotifier

        fun syncScheduler(): SyncScheduler

        fun clock(): Clock
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val action = intent.action ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(context.applicationContext, deps, action, reminderId)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        deps: Dependencies,
        action: String,
        reminderId: String,
    ) {
        val repository = deps.bookingRepository()
        val clock = deps.clock()
        val reminder = repository.reminder(reminderId) ?: return
        if (reminder.status != ReminderStatus.PENDING) return
        val booking = repository.booking(reminder.bookingId) ?: return
        val today = LocalDate.now(clock)
        val now = clock.instant()

        when (action) {
            ACTION_YES_FULL -> {
                val due = DueCalculator.duePaise(booking, repository.totalPaidPaise(booking.id))
                if (due > 0) {
                    repository.recordPayment(
                        BookingPayment(
                            id = UUID.randomUUID().toString(),
                            bookingId = booking.id,
                            businessId = booking.businessId,
                            amountPaise = due,
                            paidOn = today,
                            method = PaymentMethod.OTHER,
                            createdBy = booking.createdBy,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
                repository.saveReminder(reminder.copy(status = ReminderStatus.CONFIRMED, updatedAt = now))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.booking_payment_recorded), Toast.LENGTH_SHORT).show()
                }
            }

            ACTION_NOT_YET -> {
                repository.saveReminder(reminder.copy(status = ReminderStatus.SNOOZED, updatedAt = now))
                val due = DueCalculator.duePaise(booking, repository.totalPaidPaise(booking.id))
                PaymentReminderPlanner
                    .nextAfterAction(reminder, due, today, { UUID.randomUUID().toString() }, now)
                    ?.let { repository.saveReminder(it) }
            }

            else -> return
        }

        deps.notifier().cancelPaymentReminder(reminderId)
        deps.syncScheduler().requestImmediateSync()
    }

    companion object {
        const val ACTION_YES_FULL = "com.itsluminous.samaroh.booking.action.REMINDER_YES_FULL"
        const val ACTION_NOT_YET = "com.itsluminous.samaroh.booking.action.REMINDER_NOT_YET"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_BOOKING_ID = "booking_id"
    }
}
