package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way Google Calendar push (§4.1): local bookings are created/updated/deleted on the
 * linked account's calendar; Google-side edits are never read back. Uses the
 * `calendar.events` scope, which can write events but not create calendars — events land
 * on the account's primary calendar and `google_accounts.calendar_id` records that
 * (docs/decisions.md ADR-009).
 *
 * Change detection: [GcalSyncStateStore] keeps the last-pushed fingerprint per booking.
 * An empty store (fresh enable) makes every live booking a create — the §4.1 bulk-push.
 * Tentative bookings push with the localized "(Tentative)" title suffix; the synced
 * `bookings.gcal_event_id` column records each pushed event id.
 */
@Singleton
class CalendarSyncEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val sessionHolder: SessionHolder,
        private val linkDao: GoogleAccountLinkDao,
        private val calendarService: CalendarService,
        private val stateStore: GcalSyncStateStore,
        private val clock: Clock,
    ) {
        /** Pushes all pending booking changes for [businessId]. No-op when sync is off or Google unavailable. */
        suspend fun syncBusiness(businessId: String): Result<Unit> =
            runCatching {
                if (!GoogleServicesConfig.isConfigured) return@runCatching
                val settings = businessRepository.settings(businessId).first()
                if (settings?.gcalSyncEnabled != true) return@runCatching

                val today = LocalDate.now(clock)
                val bookings =
                    bookingRepository
                        .bookingsBetween(businessId, today.minusYears(1), today.plusYears(3))
                        .first()
                val paidByBooking = bookings.associate { it.id to bookingRepository.totalPaidPaise(it.id) }

                fun fingerprintOf(booking: Booking) = GcalEventMapper.fingerprint(booking, paidByBooking[booking.id] ?: 0)

                val state = stateStore.read(businessId).toMutableMap()
                val plan = CalendarSyncPlanner.plan(bookings, state, ::fingerprintOf)
                if (plan.isEmpty) return@runCatching

                val calendarId = ensureCalendarId()
                val bookingsById = bookings.associateBy { it.id }
                try {
                    for (booking in plan.creates) {
                        val eventId = calendarService.insertEvent(calendarId, buildEvent(booking, paidByBooking[booking.id] ?: 0))
                        state[booking.id] = SyncedEventState(eventId = eventId, fingerprint = fingerprintOf(booking))
                        recordEventId(booking, eventId)
                    }
                    for ((booking, pushed) in plan.updates) {
                        calendarService.updateEvent(calendarId, pushed.eventId, buildEvent(booking, paidByBooking[booking.id] ?: 0))
                        state[booking.id] = pushed.copy(fingerprint = fingerprintOf(booking))
                        if (booking.gcalEventId != pushed.eventId) recordEventId(booking, pushed.eventId)
                    }
                    for ((bookingId, pushed) in plan.deletes) {
                        calendarService.deleteEvent(calendarId, pushed.eventId)
                        state.remove(bookingId)
                        bookingsById[bookingId]?.takeIf { it.gcalEventId != null }?.let { recordEventId(it, null) }
                    }
                } finally {
                    // Persist partial progress so an interrupted pass never re-creates events.
                    stateStore.write(businessId, state)
                }
            }

        /**
         * §4.1 "on disable": leave events and stop updating; with [removeEvents] the
         * optional cleanup deletes every synced event.
         */
        suspend fun disable(
            businessId: String,
            removeEvents: Boolean,
        ): Result<Unit> =
            runCatching {
                if (!removeEvents) return@runCatching // Keep events AND state — re-enable updates instead of duplicating.
                val state = stateStore.read(businessId).toMutableMap()
                if (state.isEmpty()) return@runCatching
                val calendarId = ensureCalendarId()
                try {
                    for ((bookingId, pushed) in state.toMap()) {
                        calendarService.deleteEvent(calendarId, pushed.eventId)
                        state.remove(bookingId)
                        bookingRepository.booking(bookingId)?.takeIf { it.gcalEventId != null }?.let { recordEventId(it, null) }
                    }
                } finally {
                    stateStore.write(businessId, state)
                }
            }

        /** Stores the pushed event id on the synced booking row (§4.1 `gcal_event_id`). */
        private suspend fun recordEventId(
            booking: Booking,
            eventId: String?,
        ) {
            bookingRepository.saveBooking(booking.copy(gcalEventId = eventId, updatedAt = clock.instant()))
        }

        private fun buildEvent(
            booking: Booking,
            paidPaise: Long,
        ): GcalEvent {
            val duePaise = (booking.totalAmountPaise - paidPaise).coerceAtLeast(0)
            val description =
                context.getString(
                    R.string.settings_gcal_event_description,
                    AmountFormatter.format(booking.totalAmountPaise),
                    AmountFormatter.format(paidPaise),
                    AmountFormatter.format(duePaise),
                ) + "\n" + context.getString(R.string.settings_gcal_event_managed_by)
            return GcalEventMapper.toEvent(
                booking = booking,
                tentativeSuffix = context.getString(R.string.settings_gcal_tentative_suffix),
                description = description,
                zoneId = ZoneId.systemDefault(),
            )
        }

        /** `calendar.events` cannot create calendars — the primary calendar is the target (ADR-009). */
        private suspend fun ensureCalendarId(): String {
            val session = sessionHolder.session.first() ?: throw DriveNotAvailableException("not signed in")
            val link = linkDao.linkForUser(session.userId).first() ?: throw DriveNotAvailableException("no google account linked")
            link.calendarId?.let { return it }
            linkDao.upsert(link.copy(calendarId = PRIMARY_CALENDAR_ID, updatedAt = clock.instant()))
            return PRIMARY_CALENDAR_ID
        }

        companion object {
            const val PRIMARY_CALENDAR_ID = "primary"
        }
    }
