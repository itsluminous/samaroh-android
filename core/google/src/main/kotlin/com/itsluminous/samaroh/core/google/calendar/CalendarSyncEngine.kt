package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.GoogleAccountLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way Google Calendar push (§4.1): local bookings are created/updated/deleted on the
 * linked account's calendar; Google-side edits are never read back.
 *
 * Target calendar (ADR-046, supersedes ADR-015): with the `calendar.app.created` scope
 * granted the engine finds-or-creates a dedicated app calendar (localized
 * `settings_gcal_calendar_name`, "Samaroh"), migrates previously pushed events off the
 * primary calendar (Calendar v3 `events.move`, delete+recreate fallback), and pushes
 * there; `google_accounts.calendar_id` records the id and SYNCS so other devices reuse
 * the same calendar. Without the scope (pre-ADR-046 grant) the engine keeps targeting
 * the primary calendar until the user re-links — Settings shows a localized hint.
 *
 * Change detection: [GcalSyncStateStore] keeps the last-pushed fingerprint per booking.
 * An empty store (fresh enable) makes every live booking a create — the §4.1 bulk-push.
 * A state miss for a booking whose synced `gcal_event_id` is set is ADOPTED as an update
 * (never a create) — see [CalendarSyncPlanner]. Every pushed event carries private
 * extended properties (booking id + managed marker, [GcalEventMapper.PROP_BOOKING_ID])
 * so a repair pass can find and delete stray duplicates. Passes are serialized by an
 * in-process [Mutex] (the one-shot and periodic workers may fire concurrently), and the
 * push state is persisted under [NonCancellable] so a cancelled worker can never lose
 * the record of already-created events (the pre-ADR-046 duplicate source).
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
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) {
        private val syncMutex = Mutex()
        private val json = Json { encodeDefaults = true }

        /** Pushes all pending booking changes for [businessId]. No-op when sync is off or Google unavailable. */
        suspend fun syncBusiness(businessId: String): Result<Unit> =
            runCatching {
                // Configuration gating happens in CalendarSyncWorker (GoogleServicesConfig).
                syncMutex.withLock {
                    val settings = businessRepository.settings(businessId).first()
                    if (settings?.gcalSyncEnabled != true) return@withLock

                    val today = LocalDate.now(clock)
                    val bookings =
                        bookingRepository
                            .bookingsBetween(businessId, today.minusYears(1), today.plusYears(3))
                            .first()
                    val paidByBooking = bookings.associate { it.id to bookingRepository.totalPaidPaise(it.id) }

                    fun fingerprintOf(booking: Booking) = GcalEventMapper.fingerprint(booking, paidByBooking[booking.id] ?: 0)

                    val state = stateStore.read(businessId).toMutableMap()
                    var plan = CalendarSyncPlanner.plan(bookings, state, ::fingerprintOf)

                    val link = requireLink()
                    val migrationPending =
                        link.scopes.contains(GoogleServicesConfig.SCOPE_CALENDAR_APP_CREATED) &&
                            (link.calendarId == null || link.calendarId == PRIMARY_CALENDAR_ID)
                    // No HTTP at all on a true no-op pass (the on-change debounce retriggers
                    // once after every mutating pass — that echo must stay free).
                    if (plan.isEmpty && !migrationPending) return@withLock

                    val bookingsById = bookings.associateBy { it.id }
                    try {
                        val target =
                            ensureCalendarTarget(link, bookings, paidByBooking, state, ::fingerprintOf)
                        if (target.stateInvalidated) {
                            // The dedicated calendar was deleted remotely — replan against the
                            // cleared state so its events are recreated on the new calendar.
                            plan = CalendarSyncPlanner.plan(bookings, state, ::fingerprintOf)
                        }
                        val calendarId = target.calendarId
                        val adopted = plan.updates.any { it.second.fingerprint == CalendarSyncPlanner.ADOPTED_FINGERPRINT }

                        for (booking in plan.creates) {
                            val eventId = calendarService.insertEvent(calendarId, buildEvent(booking, paidByBooking[booking.id] ?: 0))
                            Log.i(TAG, "created event $eventId on $calendarId for booking ${booking.id}")
                            state[booking.id] = SyncedEventState(eventId = eventId, fingerprint = fingerprintOf(booking))
                            recordEventId(booking, eventId)
                        }
                        for ((booking, planned) in plan.updates) {
                            // Migration may have re-created the event under a new id — prefer live state.
                            val pushed = state[booking.id] ?: planned
                            val eventId =
                                try {
                                    calendarService.updateEvent(
                                        calendarId,
                                        pushed.eventId,
                                        buildEvent(
                                            booking,
                                            paidByBooking[booking.id] ?: 0,
                                        ),
                                    )
                                    pushed.eventId
                                } catch (e: GoogleApiException) {
                                    if (e.code != 404 && e.code != 410) throw e
                                    // Event vanished (user deleted it / stale id) — recreate instead of failing.
                                    Log.i(TAG, "update target gone, recreating event for booking ${booking.id}")
                                    calendarService.insertEvent(calendarId, buildEvent(booking, paidByBooking[booking.id] ?: 0))
                                }
                            state[booking.id] = SyncedEventState(eventId = eventId, fingerprint = fingerprintOf(booking))
                            if (booking.gcalEventId != eventId) recordEventId(booking, eventId)
                            Log.i(TAG, "updated event $eventId on $calendarId for booking ${booking.id}")
                        }
                        for ((bookingId, planned) in plan.deletes) {
                            val pushed = state[bookingId] ?: planned
                            calendarService.deleteEvent(calendarId, pushed.eventId)
                            Log.i(TAG, "deleted event ${pushed.eventId} on $calendarId for booking $bookingId")
                            state.remove(bookingId)
                            bookingsById[bookingId]?.takeIf { it.gcalEventId != null }?.let { recordEventId(it, null) }
                        }
                        // Repair pass (ADR-046): creates/adoptions are the only ways a duplicate
                        // can appear — only then is the extra listing worth an HTTP round trip.
                        if (plan.creates.isNotEmpty() || adopted || target.migrated) {
                            dedupeManagedEvents(calendarId, state)
                        }
                    } finally {
                        // Persist partial progress so an interrupted pass never re-creates events.
                        // NonCancellable: this MUST survive worker cancellation (REPLACE policy,
                        // constraint loss) — losing it was a duplicate-event source.
                        withContext(NonCancellable) { stateStore.write(businessId, state) }
                    }
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
                syncMutex.withLock {
                    val state = stateStore.read(businessId).toMutableMap()
                    if (state.isEmpty()) return@withLock
                    val calendarId = requireLink().calendarId ?: PRIMARY_CALENDAR_ID
                    try {
                        for ((bookingId, pushed) in state.toMap()) {
                            calendarService.deleteEvent(calendarId, pushed.eventId)
                            state.remove(bookingId)
                            bookingRepository.booking(bookingId)?.takeIf { it.gcalEventId != null }?.let { recordEventId(it, null) }
                        }
                    } finally {
                        withContext(NonCancellable) { stateStore.write(businessId, state) }
                    }
                }
            }

        private data class CalendarTarget(
            val calendarId: String,
            /** True when the previous state store contents were dropped (calendar re-created). */
            val stateInvalidated: Boolean = false,
            /** True when a primary→dedicated migration ran in this pass. */
            val migrated: Boolean = false,
        )

        /**
         * Resolves the push-target calendar (ADR-046 find-or-create). The synced
         * `google_accounts.calendar_id` row is the cross-device registry — the
         * `calendar.app.created` scope has no calendar-list access, so the cached id is
         * the only way to find "our" calendar again.
         */
        private suspend fun ensureCalendarTarget(
            link: GoogleAccountLinkEntity,
            bookings: List<Booking>,
            paidByBooking: Map<String, Long>,
            state: MutableMap<String, SyncedEventState>,
            fingerprintOf: (Booking) -> String,
        ): CalendarTarget {
            if (!link.scopes.contains(GoogleServicesConfig.SCOPE_CALENDAR_APP_CREATED)) {
                // Graceful fallback (pre-ADR-046 grant): keep pushing to primary until the
                // user re-links with the new scope; Settings shows a localized hint.
                if (link.calendarId == null) persistCalendarId(link, PRIMARY_CALENDAR_ID)
                return CalendarTarget(link.calendarId ?: PRIMARY_CALENDAR_ID)
            }

            val cached = link.calendarId?.takeIf { it != PRIMARY_CALENDAR_ID }
            if (cached != null) {
                if (calendarService.calendarExists(cached)) return CalendarTarget(cached)
                Log.w(TAG, "dedicated calendar $cached is gone — recreating")
                val recreated = calendarService.createCalendar(calendarName())
                persistCalendarId(link, recreated)
                // Events died with the old calendar: drop stale push records so the plan
                // recreates everything on the new calendar (adoption hits the 404 path).
                state.clear()
                return CalendarTarget(recreated, stateInvalidated = true)
            }

            // First pass after the scope grant: create the calendar, then migrate the
            // events previously pushed to primary.
            val created = calendarService.createCalendar(calendarName())
            Log.i(TAG, "created dedicated calendar $created")
            val hadPrimaryEvents = state.isNotEmpty() || bookings.any { it.gcalEventId != null }
            persistCalendarId(link, created)
            if (hadPrimaryEvents) {
                migrateFromPrimary(created, bookings, paidByBooking, state, fingerprintOf)
            }
            return CalendarTarget(created, migrated = hadPrimaryEvents)
        }

        /**
         * Moves every recorded event from the primary calendar to [newCalendarId]
         * (`events.move` keeps event ids; a move rejection falls back to
         * delete+recreate), then deletes stray app-created events still left on primary
         * — identified by the ADR-046 extended-property marker or, for events pushed
         * before it existed, the localized "Managed by Samaroh" description line.
         */
        private suspend fun migrateFromPrimary(
            newCalendarId: String,
            bookings: List<Booking>,
            paidByBooking: Map<String, Long>,
            state: MutableMap<String, SyncedEventState>,
            fingerprintOf: (Booking) -> String,
        ) {
            val bookingsById = bookings.associateBy { it.id }
            val recorded = mutableMapOf<String, String>() // bookingId → eventId
            for ((bookingId, pushed) in state) recorded[bookingId] = pushed.eventId
            for (booking in bookings) booking.gcalEventId?.let { recorded.putIfAbsent(booking.id, it) }

            for ((bookingId, eventId) in recorded) {
                try {
                    calendarService.moveEvent(PRIMARY_CALENDAR_ID, eventId, newCalendarId)
                    Log.i(TAG, "migrated event $eventId → $newCalendarId (booking $bookingId)")
                } catch (e: GoogleApiException) {
                    if (e.code == 404 || e.code == 410) continue // already gone; plan recreates if needed
                    // Move not permitted for this event — recreate on the new calendar instead.
                    Log.w(TAG, "events.move rejected (${e.code}) for $eventId — falling back to delete+recreate")
                    val booking = bookingsById[bookingId]
                    if (booking != null && booking.status != BookingStatus.CANCELLED && booking.deletedAt == null) {
                        val newId = calendarService.insertEvent(newCalendarId, buildEvent(booking, paidByBooking[booking.id] ?: 0))
                        state[bookingId] = SyncedEventState(eventId = newId, fingerprint = fingerprintOf(booking))
                        recordEventId(booking, newId)
                    } else {
                        state.remove(bookingId)
                    }
                    calendarService.deleteEvent(PRIMARY_CALENDAR_ID, eventId)
                }
            }

            // Stray cleanup: whatever app-created events remain on primary now are
            // leftovers of the pre-ADR-046 duplicate bug. ONLY events our app created are
            // touched — matched by the private marker property or the managed-by line.
            val markers = managedByMarkers()
            val today = LocalDate.now(clock)
            val strays =
                calendarService.listEvents(
                    PRIMARY_CALENDAR_ID,
                    timeMin =
                        today
                            .minusYears(1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toString(),
                    timeMax =
                        today
                            .plusYears(3)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toString(),
                )
            val recordedIds = recorded.values.toSet()
            for (event in strays) {
                val appCreated =
                    event.privateProperties[GcalEventMapper.PROP_MANAGED] == GcalEventMapper.PROP_MANAGED_VALUE ||
                        markers.any { it.isNotBlank() && event.description.contains(it) }
                if (appCreated && event.id !in recordedIds) {
                    Log.i(TAG, "deleting stray app-created event ${event.id} from primary")
                    calendarService.deleteEvent(PRIMARY_CALENDAR_ID, event.id)
                }
            }
        }

        /**
         * Duplicate repair (ADR-046): every pushed event carries its booking id as a
         * private extended property; when two managed events claim the same booking, the
         * one that is not the recorded push is a stray and gets deleted.
         */
        private suspend fun dedupeManagedEvents(
            calendarId: String,
            state: Map<String, SyncedEventState>,
        ) {
            val managed =
                calendarService.listEvents(
                    calendarId,
                    privateExtendedProperty = "${GcalEventMapper.PROP_MANAGED}=${GcalEventMapper.PROP_MANAGED_VALUE}",
                )
            for ((bookingId, events) in managed.groupBy { it.privateProperties[GcalEventMapper.PROP_BOOKING_ID] }) {
                if (bookingId == null) continue
                val recordedId = state[bookingId]?.eventId ?: continue // other businesses' bookings stay untouched
                for (event in events) {
                    if (event.id != recordedId) {
                        Log.i(TAG, "dedupe: deleting stray event ${event.id} for booking $bookingId")
                        calendarService.deleteEvent(calendarId, event.id)
                    }
                }
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

        private suspend fun requireLink(): GoogleAccountLinkEntity {
            val session = sessionHolder.session.first() ?: throw DriveNotAvailableException("not signed in")
            return linkDao.linkForUser(session.userId).first() ?: throw DriveNotAvailableException("no google account linked")
        }

        /** Records the target calendar on the SYNCED link row so other devices reuse it. */
        private suspend fun persistCalendarId(
            link: GoogleAccountLinkEntity,
            calendarId: String,
        ) {
            val now = clock.instant()
            val updated = link.copy(calendarId = calendarId, updatedAt = now)
            linkDao.upsert(updated)
            outboxWriter.enqueue(
                entityType = "google_accounts",
                entityId = link.userId,
                operation = OutboxOperation.UPSERT,
                payloadJson =
                    json.encodeToString(
                        GoogleAccountLink.serializer(),
                        GoogleAccountLink(
                            userId = updated.userId,
                            email = updated.email,
                            scopes = updated.scopes,
                            driveRootFolderId = updated.driveRootFolderId,
                            calendarId = updated.calendarId,
                            updatedAt = now,
                        ),
                    ),
            )
        }

        /** The dedicated calendar's display name (localized catalog key, "Samaroh" in every locale). */
        private fun calendarName(): String = context.getString(R.string.settings_gcal_calendar_name)

        /** The managed-by description line in every supported locale — legacy-event matching. */
        private fun managedByMarkers(): Set<String> =
            setOf("en", "hi")
                .map { lang ->
                    val config = Configuration(context.resources.configuration)
                    config.setLocale(Locale(lang))
                    context.createConfigurationContext(config).getString(R.string.settings_gcal_event_managed_by)
                }.toSet()

        companion object {
            const val PRIMARY_CALENDAR_ID = "primary"

            /** Logcat tag for calendar-push diagnostics (grep `SamarohGcal`). */
            const val TAG = "SamarohGcal"
        }
    }
