package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.sync.ReplicaIntegrity
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.EventTypeKinds
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.DueCalculator
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.PaymentReminderPlanner
import com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
import com.itsluminous.samaroh.feature.booking.domain.UpcomingReminderPlanner
import com.itsluminous.samaroh.feature.booking.domain.canReceiveFollowUps
import com.itsluminous.samaroh.feature.booking.domain.canReceivePaymentReminders
import com.itsluminous.samaroh.feature.booking.domain.canReceiveUpcomingReminders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the daily reminder pass (§4.1): runs the pure planners against repository
 * state and executes the resulting plan (persist reminders via Room+outbox, post
 * notifications, schedule exact alarms for the full-screen style). Called by
 * [BookingReminderWorker] every day at 09:00 local, by [ReminderPostSyncHook] after
 * every completed sync pull and by [ReminderBootReceiver] after a reboot.
 *
 * MUTATING passes are gated on replica consistency (ADR-060): payment planning derives
 * `due = total − Σpayments`, so running it while a pull is in flight or after a partial
 * pull (bookings landed, payments not) fabricates a reminder for every settled past
 * booking — and pushes them to the server. When the replica is inconsistent the pass
 * skips payment and follow-up planning entirely (creation AND dismissal — a missing
 * booking row must not dismiss a synced reminder either) and still posts the read-only
 * upcoming-event reminders. The pull that restores consistency re-runs the engine via
 * [ReminderPostSyncHook], so deferred planning happens within the same sync cycle.
 *
 * DELIVERY is idempotent per device (ADR-094): the pass runs many times a day (every
 * sync pull), so every post/alarm is gated on the [ReminderLedger] — a reminder key is
 * delivered at most once, and once the user dismissed/acted on it (ACKED) or snoozed it
 * (SNOOZED) the planner never touches it again. Only a changed key (edited booking
 * dates, changed lead-day setting, a chained successor row) fires anew.
 *
 * Reminders follow the signed-in member's §3 permissions (ADR-095/ADR-097) — the same
 * gates as the in-app surfaces they lead to: payment reminders ("Did X pay?") only for
 * an owner or a member holding BOTH `booking.record_payment` AND `booking.view_amounts`
 * ([canReceivePaymentReminders] — a nudge to act on a due is for people who can see and
 * record it; there is no masked variant); tentative follow-ups only with `booking.edit`
 * (confirming a booking needs it); upcoming-event reminders with `booking.view`. A
 * member without the permission gets no row, no notification and no alarm — reminder
 * rows are device-local since ADR-095, so nothing is synced either way. When a payment
 * grant is REVOKED, the next planning pass retracts what this device already delivered
 * ([retractPaymentReminders]): posted payment notifications are cancelled, armed snoozes
 * disarmed and their ledger entries dropped, so nothing lingers in the shade or rings.
 */
@Singleton
class ReminderEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val eventTypeRepository: EventTypeRepository,
        private val eventTypes: EventTypeCatalog,
        private val notifier: BookingNotifier,
        private val prefs: BookingReminderPrefs,
        private val replicaIntegrity: ReplicaIntegrity,
        private val ledger: ReminderLedger,
        private val snoozer: ReminderSnoozer,
        private val actorProvider: BookingActorProvider,
        private val clock: Clock,
    ) {
        suspend fun runDailyPass() {
            val today = LocalDate.now(clock)
            ledger.prune(today)
            // Style/sound resolved ONCE at fire time and honored by EVERY reminder kind
            // (ADR-045) — payment, follow-up and upcoming alike.
            val settings = prefs.current()
            val planningSafe = replicaIntegrity.isReplicaConsistent()
            val businesses = businessRepository.businesses().first().filter { it.deletedAt == null }
            for (business in businesses) {
                val actor = actorProvider.actorFor(business)
                if (planningSafe) {
                    if (actor.canReceivePaymentReminders) {
                        runPaymentReminders(business.id, today, settings)
                    } else {
                        retractPaymentReminders(business.id)
                    }
                    if (actor.canReceiveFollowUps) runFollowUpReminders(business.id, today, settings)
                }
                if (actor.canReceiveUpcomingReminders) runUpcomingReminders(business.id, today, settings)
            }
        }

        /**
         * Snooze re-fire (ADR-094): re-validate the reminder against CURRENT state — a
         * booking paid off, cancelled or deleted while snoozed never rings again
         * (ADR-064 rules) — then post through the same style pipeline as a first
         * delivery and record FIRED so the next pass leaves it alone.
         */
        suspend fun refire(key: ReminderLedgerKey) {
            val today = LocalDate.now(clock)
            val settings = prefs.current()
            // Permissions are re-checked at fire time: a member whose grant was revoked
            // while a reminder was snoozed does not get it back (ADR-095).
            val actor = bookingRepository.booking(key.bookingId)?.let { actorFor(it.businessId) }
            when (key) {
                is ReminderLedgerKey.Upcoming -> {
                    val booking = bookingRepository.booking(key.bookingId)
                    if (!SnoozePolicy.upcomingStillRelevant(booking, today) || actor?.canReceiveUpcomingReminders != true) {
                        ledger.remove(key)
                        return
                    }
                    checkNotNull(booking)
                    val daysAway = ChronoUnit.DAYS.between(today, booking.startDate).toInt()
                    val title = upcomingTitle(booking)
                    when (settings.style) {
                        ReminderStyle.NOTIFICATION -> notifier.postUpcomingReminder(booking.id, title, daysAway, key)
                        ReminderStyle.FULLSCREEN, ReminderStyle.FULLSCREEN_ALWAYS ->
                            notifier.postFullScreenUpcomingReminder(booking.id, title, daysAway, settings.soundUri, settings.style, key)
                    }
                    ledger.mark(key, ReminderFireState.FIRED, keepUntil = booking.startDate)
                }

                is ReminderLedgerKey.Row -> {
                    val reminder = bookingRepository.reminder(key.reminderId)
                    val booking = reminder?.let { bookingRepository.booking(it.bookingId) }
                    val due = booking?.let { DueCalculator.duePaise(it, bookingRepository.totalPaidPaise(it.id)) } ?: 0L
                    val permitted =
                        if (key.followUp) actor?.canReceiveFollowUps == true else actor?.canReceivePaymentReminders == true
                    if (!SnoozePolicy.rowStillRelevant(reminder, booking, due, key.followUp) || !permitted) {
                        ledger.remove(key)
                        return
                    }
                    checkNotNull(reminder)
                    checkNotNull(booking)
                    if (key.followUp) {
                        postFollowUp(reminder, booking, settings)
                    } else {
                        postPayment(reminder, booking, due, settings)
                    }
                }
            }
        }

        private suspend fun actorFor(businessId: String): BookingActor? =
            businessRepository
                .businesses()
                .first()
                .firstOrNull { it.id == businessId && it.deletedAt == null }
                ?.let { actorProvider.actorFor(it) }

        private suspend fun runPaymentReminders(
            businessId: String,
            today: LocalDate,
            settings: UpcomingReminderPrefs,
        ) {
            // Marker bookings (ADR-041/ADR-044) never enter payment planning: they have
            // no money by construction (the form forces 0 amounts), and even an edge-case
            // amount (preset flipped to marker later, row synced from an older client)
            // must not produce a payment reminder. Upcoming-event reminders still fire —
            // a marker IS an event worth being reminded of.
            val presets = eventTypeRepository.presetsOnce(businessId)
            val isMarker = { booking: Booking -> EventTypeKinds.isMarker(presets, booking.eventType) }
            val ended = bookingRepository.bookingsEndedBefore(businessId, today).filterNot(isMarker)
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

            // Cleanup pass (§4.1 + ADR-024, narrowed by ADR-064): every due pending
            // reminder — including ones synced from other devices for bookings NOT in
            // this run's ended set — is dismissed ONLY when its booking is truly paid
            // (total > 0, due <= 0), cancelled or deleted. Nothing else, ever.
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
                val key = rowKey(reminder)
                if (ledger.entry(key)?.blocksPlanning == true) continue
                postPayment(reminder, booking, dueByBooking[booking.id] ?: reminder.amountDueSnapshotPaise, settings)
            }
        }

        /**
         * Permission revoked (ADR-097): the member no longer qualifies for payment
         * reminders, so everything this device already delivered for the business is
         * taken back — every payment-row ledger entry is walked, its notification
         * cancelled (an app-side cancel, deliberately NOT an ACK), its armed snooze
         * disarmed and the entry dropped. The device-local rows themselves are left
         * untouched (the card filters them, ADR-097 §3); if the grant comes back the
         * next pass delivers them afresh. Follow-up and upcoming entries are not touched.
         */
        private suspend fun retractPaymentReminders(businessId: String) {
            for ((key, _) in ledger.all()) {
                if (key !is ReminderLedgerKey.Row || key.followUp) continue
                val reminder = bookingRepository.reminder(key.reminderId) ?: continue
                if (reminder.businessId != businessId) continue
                notifier.cancelPaymentReminder(reminder.id)
                snoozer.cancel(key)
                ledger.remove(key)
            }
        }

        private suspend fun postPayment(
            reminder: PaymentReminder,
            booking: Booking,
            duePaise: Long,
            settings: UpcomingReminderPrefs,
        ) {
            val key = rowKey(reminder)
            notifier.postPaymentReminder(
                reminder = reminder,
                booking = booking,
                eventLabel = eventTypes.labelFor(booking.eventType, context::getString),
                duePaise = duePaise,
                style = settings.style,
                soundUri = settings.soundUri,
                ledgerKey = key,
            )
            ledger.mark(key, ReminderFireState.FIRED, keepUntil = rowKeepUntil())
        }

        /**
         * Tentative-booking follow-ups (ADR-020): notify the due ones while the booking
         * is still tentative; dismiss those whose booking was confirmed/cancelled/deleted
         * in the meantime (possibly on another device).
         */
        private suspend fun runFollowUpReminders(
            businessId: String,
            today: LocalDate,
            settings: UpcomingReminderPrefs,
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
                if (ledger.entry(rowKey(reminder))?.blocksPlanning == true) continue
                postFollowUp(reminder, checkNotNull(booking), settings)
            }
        }

        private suspend fun postFollowUp(
            reminder: PaymentReminder,
            booking: Booking,
            settings: UpcomingReminderPrefs,
        ) {
            val key = rowKey(reminder)
            notifier.postFollowUpReminder(
                reminder = reminder,
                booking = booking,
                eventLabel = eventTypes.labelFor(booking.eventType, context::getString),
                style = settings.style,
                soundUri = settings.soundUri,
                ledgerKey = key,
            )
            ledger.mark(key, ReminderFireState.FIRED, keepUntil = rowKeepUntil())
        }

        private suspend fun dismiss(reminder: PaymentReminder) {
            bookingRepository.saveReminder(
                reminder.copy(status = ReminderStatus.DISMISSED, updatedAt = clock.instant()),
            )
            notifier.cancelPaymentReminder(reminder.id)
            // A dismissed row's pending snooze must never ring (ADR-064 cases).
            val key = rowKey(reminder)
            snoozer.cancel(key)
            ledger.remove(key)
        }

        private suspend fun runUpcomingReminders(
            businessId: String,
            today: LocalDate,
            settings: UpcomingReminderPrefs,
        ) {
            val dates = UpcomingReminderPlanner.reminderDates(today, settings.leadDays)
            val bookingsByDaysAway =
                dates.mapValues { (_, date) -> bookingRepository.bookingsStartingOn(businessId, date) }
            val reminders = UpcomingReminderPlanner.remindersFor(bookingsByDaysAway)

            for (upcoming in reminders) {
                val booking = upcoming.booking
                val key = ReminderLedgerKey.Upcoming(booking.id, upcoming.daysAway, booking.startDate)
                // Deliver at most once per key: FIRED/ACKED/SNOOZED entries are skipped
                // (ADR-094). SCHEDULED is re-armed — idempotent (same PendingIntent), and
                // it is how a lost alarm (reboot, revoked exact-alarm grant) recovers.
                if (ledger.entry(key)?.blocksPlanning == true) continue
                val title = upcomingTitle(booking)
                when (settings.style) {
                    ReminderStyle.NOTIFICATION -> {
                        notifier.postUpcomingReminder(booking.id, title, upcoming.daysAway, key)
                        ledger.mark(key, ReminderFireState.FIRED, keepUntil = booking.startDate)
                    }
                    // Both full-screen styles travel the exact-alarm path; the style
                    // rides in the intent so the receiver picks the launch path
                    // (locked-only vs always-takeover, ADR-072) at fire time.
                    ReminderStyle.FULLSCREEN, ReminderStyle.FULLSCREEN_ALWAYS -> {
                        UpcomingReminderAlarmReceiver.scheduleExact(
                            context = context,
                            bookingId = booking.id,
                            title = title,
                            daysAway = upcoming.daysAway,
                            soundUri = settings.soundUri,
                            style = settings.style,
                            clock = clock,
                            ledgerKey = key,
                        )
                        ledger.mark(key, ReminderFireState.SCHEDULED, keepUntil = booking.startDate)
                    }
                }
            }
        }

        private fun upcomingTitle(booking: Booking): String {
            val label = eventTypes.labelFor(booking.eventType, context::getString)
            return "${booking.displayIcon} $label - ${booking.customerName}"
        }

        private fun rowKey(reminder: PaymentReminder) =
            ReminderLedgerKey.Row(reminder.id, reminder.bookingId, followUp = reminder.kind == ReminderKind.FOLLOW_UP)

        private fun rowKeepUntil(): LocalDate = LocalDate.now(clock).plusDays(ReminderLedger.ROW_KEEP_DAYS)
    }
