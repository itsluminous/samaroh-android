package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.DueCalculator
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.PaymentReminderPlanner
import com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
import com.itsluminous.samaroh.feature.booking.domain.UpcomingReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the daily reminder pass (§4.1): runs the pure planners against repository
 * state and executes the resulting plan (persist reminders via Room+outbox, post
 * notifications, schedule exact alarms for the full-screen style). Called by
 * [BookingReminderWorker] every day at 09:00 local.
 */
@Singleton
class ReminderEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val eventTypes: EventTypeCatalog,
        private val notifier: BookingNotifier,
        private val prefs: BookingReminderPrefs,
        private val clock: Clock,
    ) {
        suspend fun runDailyPass() {
            val today = LocalDate.now(clock)
            val businesses = businessRepository.businesses().first().filter { it.deletedAt == null }
            for (business in businesses) {
                runPaymentReminders(business.id, today)
                runFollowUpReminders(business.id, today)
                runUpcomingReminders(business.id, today)
            }
        }

        private suspend fun runPaymentReminders(
            businessId: String,
            today: LocalDate,
        ) {
            val ended = bookingRepository.bookingsEndedBefore(businessId, today)
            val dueByBooking =
                ended.associate { it.id to DueCalculator.duePaise(it, bookingRepository.totalPaidPaise(it.id)) }.toMutableMap()
            // Follow-up rows (ADR-020) never participate in payment planning.
            val remindersByBooking =
                ended.associate { booking ->
                    booking.id to
                        bookingRepository.remindersForBooking(booking.id).filter { it.kind == ReminderKind.PAYMENT }
                }

            val plan =
                PaymentReminderPlanner.plan(
                    today = today,
                    endedBookings = ended,
                    duePaiseByBooking = dueByBooking,
                    remindersByBooking = remindersByBooking,
                    newId = { UUID.randomUUID().toString() },
                    now = clock.instant(),
                )

            plan.toCreate.forEach { bookingRepository.saveReminder(it) }

            // Cleanup pass (§4.1 + ADR-024): every due pending reminder — including ones
            // synced from other devices for bookings NOT in this run's ended set — is
            // dismissed when its booking is gone, cancelled or has nothing due.
            val duePending =
                bookingRepository
                    .duePendingRemindersOnce(businessId, today)
                    .filter { it.kind == ReminderKind.PAYMENT }
            val bookingById = mutableMapOf<String, Booking?>()
            ended.forEach { bookingById[it.id] = it }
            for (reminder in duePending) {
                if (reminder.bookingId in bookingById) continue
                val booking = bookingRepository.booking(reminder.bookingId)
                bookingById[reminder.bookingId] = booking
                if (booking != null) {
                    dueByBooking[booking.id] = DueCalculator.duePaise(booking, bookingRepository.totalPaidPaise(booking.id))
                }
            }
            val stale = PaymentReminderPlanner.staleDismissals(duePending, bookingById, dueByBooking)
            (plan.toDismiss + stale).distinctBy { it.id }.forEach { dismiss(it) }

            for (reminder in plan.toNotify) {
                val booking = bookingById[reminder.bookingId] ?: continue
                notifier.postPaymentReminder(
                    reminder = reminder,
                    booking = booking,
                    eventLabel = eventTypes.labelFor(booking.eventType, context::getString),
                    duePaise = dueByBooking[booking.id] ?: reminder.amountDueSnapshotPaise,
                )
            }
        }

        /**
         * Tentative-booking follow-ups (ADR-020): notify the due ones while the booking
         * is still tentative; dismiss those whose booking was confirmed/cancelled/deleted
         * in the meantime (possibly on another device).
         */
        private suspend fun runFollowUpReminders(
            businessId: String,
            today: LocalDate,
        ) {
            val dueFollowUps =
                bookingRepository
                    .duePendingRemindersOnce(businessId, today)
                    .filter { it.kind == ReminderKind.FOLLOW_UP }
            for (reminder in dueFollowUps) {
                val booking = bookingRepository.booking(reminder.bookingId)
                if (TentativeFollowUpPlanner.isObsolete(booking)) {
                    dismiss(reminder)
                    continue
                }
                notifier.postFollowUpReminder(
                    reminder = reminder,
                    booking = checkNotNull(booking),
                    eventLabel = eventTypes.labelFor(booking.eventType, context::getString),
                )
            }
        }

        private suspend fun dismiss(reminder: PaymentReminder) {
            bookingRepository.saveReminder(
                reminder.copy(status = ReminderStatus.DISMISSED, updatedAt = clock.instant()),
            )
            notifier.cancelPaymentReminder(reminder.id)
        }

        private suspend fun runUpcomingReminders(
            businessId: String,
            today: LocalDate,
        ) {
            val settings = prefs.current()
            val dates = UpcomingReminderPlanner.reminderDates(today, settings.leadDays)
            val bookingsByDaysAway =
                dates.mapValues { (_, date) -> bookingRepository.bookingsStartingOn(businessId, date) }
            val reminders = UpcomingReminderPlanner.remindersFor(bookingsByDaysAway)

            for (upcoming in reminders) {
                val booking = upcoming.booking
                val label = eventTypes.labelFor(booking.eventType, context::getString)
                val title = "${booking.displayIcon} $label - ${booking.customerName}"
                when (settings.style) {
                    ReminderStyle.NOTIFICATION -> notifier.postUpcomingReminder(booking, title, upcoming.daysAway)
                    ReminderStyle.FULLSCREEN ->
                        UpcomingReminderAlarmReceiver.scheduleExact(
                            context = context,
                            bookingId = booking.id,
                            title = title,
                            daysAway = upcoming.daysAway,
                            soundUri = settings.soundUri,
                            clock = clock,
                        )
                }
            }
        }
    }
