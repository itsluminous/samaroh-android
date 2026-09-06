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
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.BusinessSettings
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
 * Target calendar (ADR-048, refines ADR-046 which superseded ADR-015): with the
 * `calendar.app.created` scope granted, EVERY enabled business gets its own dedicated
 * app calendar NAMED AFTER THE BUSINESS. The synced `business_settings.gcal_calendar_id`
 * is the per-business registry; the pre-ADR-048 `google_accounts.calendar_id` slot is
 * kept only as the legacy fallback — its calendar is ADOPTED (and renamed) by the first
 * business that syncs, and mirrored on create so pre-alter servers still carry a synced
 * registry. Business renames re-title the calendar (`calendars.patch`; 403 falls back to
 * create-new + migrate). Events previously pushed to primary migrate over (Calendar v3
 * `events.move`, delete+recreate fallback). Without the scope (pre-ADR-046 grant) the
 * engine keeps targeting the primary calendar until the user re-links — Settings shows
 * a localized hint.
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
                    // The calendar carries the business's own name (ADR-048); the localized
                    // "Samaroh" is only the blank-name fallback.
                    val calendarDisplayName =
                        businessRepository
                            .business(businessId)
                            ?.name
                            ?.takeIf { it.isNotBlank() } ?: calendarName()

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
                    val hasAppCreatedScope = link.scopes.contains(GoogleServicesConfig.SCOPE_CALENDAR_APP_CREATED)
                    // A pass is needed beyond booking changes when the per-business
                    // registration is still pending (adopt the legacy ADR-046 calendar or
                    // create/migrate one), or the business was renamed since this device
                    // last verified the calendar title (ADR-048).
                    val adoptionPending = hasAppCreatedScope && settings.gcalCalendarId == null
                    val renamePending =
                        hasAppCreatedScope &&
                            settings.gcalCalendarId != null &&
                            stateStore.readCalendarName(businessId) != calendarDisplayName
                    // No HTTP at all on a true no-op pass (the on-change debounce retriggers
                    // once after every mutating pass — that echo must stay free).
                    if (plan.isEmpty && !adoptionPending && !renamePending) return@withLock

                    val bookingsById = bookings.associateBy { it.id }
                    try {
                        val target =
                            ensureCalendarTarget(
                                businessId,
                                calendarDisplayName,
                                settings,
                                link,
                                bookings,
                                paidByBooking,
                                state,
                                ::fingerprintOf,
                            )
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
                    val calendarId =
                        businessRepository.settings(businessId).first()?.gcalCalendarId
                            ?: requireLink().calendarId
                            ?: PRIMARY_CALENDAR_ID
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
         * Resolves the push-target calendar (ADR-048 per-business find-or-create). The
         * synced `business_settings.gcal_calendar_id` is the registry — the
         * `calendar.app.created` scope has no calendar-list access, so a stored id is
         * the only way to find "our" calendar again. The legacy
         * `google_accounts.calendar_id` (ADR-046, one calendar per account) is adopted
         * by the first business that syncs and renamed after it.
         */
        private suspend fun ensureCalendarTarget(
            businessId: String,
            displayName: String,
            settings: BusinessSettings,
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

            // 1. Registered per-business calendar (ADR-048).
            settings.gcalCalendarId?.let { registered ->
                val summary = calendarService.calendarSummary(registered)
                if (summary != null) {
                    return alignCalendarName(
                        businessId,
                        registered,
                        summary,
                        displayName,
                        settings,
                        bookings,
                        paidByBooking,
                        state,
                        fingerprintOf,
                    )
                }
                Log.w(TAG, "dedicated calendar $registered is gone — recreating")
                return recreateCalendar(businessId, displayName, settings, state)
            }

            // 2. Legacy single-calendar registry (ADR-046 google_accounts.calendar_id).
            val legacy = link.calendarId?.takeIf { it != PRIMARY_CALENDAR_ID }
            if (legacy != null) {
                if (claimedByOtherBusiness(businessId, legacy)) {
                    // Another business already adopted the shared legacy calendar — this
                    // one gets its own; its recorded events move over (ids preserved).
                    val created = calendarService.createCalendar(displayName)
                    Log.i(TAG, "created calendar $created for business $businessId (legacy calendar claimed)")
                    persistBusinessCalendarId(settings, created)
                    stateStore.writeCalendarName(businessId, displayName)
                    migrateEvents(legacy, created, bookings, paidByBooking, state, fingerprintOf, cleanupStrays = false)
                    return CalendarTarget(created, migrated = true)
                }
                val summary = calendarService.calendarSummary(legacy)
                if (summary != null) {
                    // ADOPT: the first business to sync claims the legacy "Samaroh"
                    // calendar and renames it after itself (events stay in place).
                    Log.i(TAG, "business $businessId adopts legacy calendar $legacy")
                    persistBusinessCalendarId(settings, legacy)
                    return alignCalendarName(
                        businessId,
                        legacy,
                        summary,
                        displayName,
                        settings,
                        bookings,
                        paidByBooking,
                        state,
                        fingerprintOf,
                    )
                }
                Log.w(TAG, "legacy calendar $legacy is gone — creating fresh")
                return recreateCalendar(businessId, displayName, settings, state)
            }

            // 3. First pass after the scope grant: create the calendar, then migrate the
            // events previously pushed to primary (ADR-046).
            val created = calendarService.createCalendar(displayName)
            Log.i(TAG, "created dedicated calendar $created")
            val hadPrimaryEvents = state.isNotEmpty() || bookings.any { it.gcalEventId != null }
            persistBusinessCalendarId(settings, created)
            stateStore.writeCalendarName(businessId, displayName)
            // Mirror into the legacy slot too (first business only): servers without the
            // ADR-048 column still carry a SYNCED registry via google_accounts.
            if (link.calendarId == null || link.calendarId == PRIMARY_CALENDAR_ID) persistCalendarId(link, created)
            if (hadPrimaryEvents) {
                migrateEvents(PRIMARY_CALENDAR_ID, created, bookings, paidByBooking, state, fingerprintOf, cleanupStrays = true)
            }
            return CalendarTarget(created, migrated = hadPrimaryEvents)
        }

        /** The dedicated calendar was deleted remotely — create anew and drop stale push records. */
        private suspend fun recreateCalendar(
            businessId: String,
            displayName: String,
            settings: BusinessSettings,
            state: MutableMap<String, SyncedEventState>,
        ): CalendarTarget {
            val recreated = calendarService.createCalendar(displayName)
            persistBusinessCalendarId(settings, recreated)
            stateStore.writeCalendarName(businessId, displayName)
            // Events died with the old calendar: drop stale push records so the plan
            // recreates everything on the new calendar (adoption hits the 404 path).
            state.clear()
            return CalendarTarget(recreated, stateInvalidated = true)
        }

        /**
         * Keeps the calendar's title equal to the business name (ADR-048). A rejected
         * `calendars.patch` (403 — grant does not cover calendar metadata) falls back
         * to create-new + migrate, the ADR-046 migration shape.
         */
        private suspend fun alignCalendarName(
            businessId: String,
            calendarId: String,
            currentSummary: String,
            wantedName: String,
            settings: BusinessSettings,
            bookings: List<Booking>,
            paidByBooking: Map<String, Long>,
            state: MutableMap<String, SyncedEventState>,
            fingerprintOf: (Booking) -> String,
        ): CalendarTarget {
            if (currentSummary == wantedName) {
                stateStore.writeCalendarName(businessId, wantedName)
                return CalendarTarget(calendarId)
            }
            return try {
                calendarService.renameCalendar(calendarId, wantedName)
                Log.i(TAG, "renamed calendar $calendarId to \"$wantedName\"")
                stateStore.writeCalendarName(businessId, wantedName)
                CalendarTarget(calendarId)
            } catch (e: GoogleApiException) {
                if (e.code != 403) throw e
                Log.w(TAG, "calendars.patch rejected (403) for $calendarId — creating replacement")
                val created = calendarService.createCalendar(wantedName)
                persistBusinessCalendarId(settings, created)
                stateStore.writeCalendarName(businessId, wantedName)
                migrateEvents(calendarId, created, bookings, paidByBooking, state, fingerprintOf, cleanupStrays = false)
                CalendarTarget(created, migrated = true)
            }
        }

        /** Whether another business already registered [calendarId] as its own (ADR-048 adoption guard). */
        private suspend fun claimedByOtherBusiness(
            businessId: String,
            calendarId: String,
        ): Boolean =
            businessRepository.businesses().first().any { other ->
                other.id != businessId &&
                    businessRepository.settings(other.id).first()?.gcalCalendarId == calendarId
            }

        /**
         * Moves every recorded event from [sourceCalendarId] to [newCalendarId]
         * (`events.move` keeps event ids; a move rejection falls back to
         * delete+recreate). With [cleanupStrays] (primary migration only) it then
         * deletes stray app-created events still left on the source — identified by the
         * ADR-046 extended-property marker or, for events pushed before it existed, the
         * localized "Managed by Samaroh" description line. Stray cleanup is SKIPPED for
         * a shared legacy calendar (ADR-048): other businesses' events live there.
         */
        private suspend fun migrateEvents(
            sourceCalendarId: String,
            newCalendarId: String,
            bookings: List<Booking>,
            paidByBooking: Map<String, Long>,
            state: MutableMap<String, SyncedEventState>,
            fingerprintOf: (Booking) -> String,
            cleanupStrays: Boolean,
        ) {
            val bookingsById = bookings.associateBy { it.id }
            val recorded = mutableMapOf<String, String>() // bookingId → eventId
            for ((bookingId, pushed) in state) recorded[bookingId] = pushed.eventId
            for (booking in bookings) booking.gcalEventId?.let { recorded.putIfAbsent(booking.id, it) }

            for ((bookingId, eventId) in recorded) {
                try {
                    calendarService.moveEvent(sourceCalendarId, eventId, newCalendarId)
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
                    calendarService.deleteEvent(sourceCalendarId, eventId)
                }
            }
            if (!cleanupStrays) return

            // Stray cleanup: whatever app-created events remain on primary now are
            // leftovers of the pre-ADR-046 duplicate bug. ONLY events our app created are
            // touched — matched by the private marker property or the managed-by line.
            val markers = managedByMarkers()
            val today = LocalDate.now(clock)
            val strays =
                calendarService.listEvents(
                    sourceCalendarId,
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
                    calendarService.deleteEvent(sourceCalendarId, event.id)
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
        ): GcalEvent =
            GcalEventMapper.toEvent(
                booking = booking,
                tentativeSuffix = context.getString(R.string.settings_gcal_tentative_suffix),
                description = GcalEventMapper.description(booking, paidPaise, descriptionStrings(context)),
                zoneId = ZoneId.systemDefault(),
            )

        private suspend fun requireLink(): GoogleAccountLinkEntity {
            val session = sessionHolder.session.first() ?: throw DriveNotAvailableException("not signed in")
            return linkDao.linkForUser(session.userId).first() ?: throw DriveNotAvailableException("no google account linked")
        }

        /**
         * Records the target calendar on the SYNCED business_settings row (ADR-048) so
         * every device reuses it. Servers without the column hold this push per-item
         * (PGRST204, self-healing) until the owner applies the shared alter script.
         */
        private suspend fun persistBusinessCalendarId(
            settings: BusinessSettings,
            calendarId: String,
        ) {
            businessRepository.saveSettings(settings.copy(gcalCalendarId = calendarId, updatedAt = clock.instant()))
        }

        /**
         * Records a calendar id on the SYNCED google_accounts row — the pre-ADR-048
         * LEGACY registry, kept for the no-scope primary fallback and as a mirror so
         * pre-alter servers still sync a registry. Deprecated as the push target.
         */
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

/**
 * Resolves the localized [GcalDescriptionStrings] from [context]'s current locale
 * (ADR-047). Top-level so the en/hi description tests exercise the exact resource
 * wiring the engine uses, via locale-overridden contexts.
 */
internal fun descriptionStrings(context: Context): GcalDescriptionStrings =
    GcalDescriptionStrings(
        customerNameLabel = context.getString(R.string.booking_form_customer_name),
        customerPhoneLabel = context.getString(R.string.booking_form_customer_phone),
        eventTypeLabel = context.getString(R.string.booking_form_event_type),
        statusLabel = context.getString(R.string.booking_form_status),
        totalLabel = context.getString(R.string.booking_card_total_label),
        securityDepositLabel = context.getString(R.string.booking_card_deposit_label),
        advanceLabel = context.getString(R.string.booking_form_advance),
        dueLabel = context.getString(R.string.booking_card_due_label),
        invoiceNumberLabel = context.getString(R.string.booking_form_invoice_number),
        sourceLabel = context.getString(R.string.booking_form_source),
        notesLabel = context.getString(R.string.booking_form_notes),
        statusNames =
            mapOf(
                BookingStatus.TENTATIVE to context.getString(R.string.booking_status_tentative),
                BookingStatus.CONFIRMED to context.getString(R.string.booking_status_confirmed),
                BookingStatus.COMPLETED to context.getString(R.string.booking_status_completed),
                BookingStatus.CANCELLED to context.getString(R.string.booking_status_cancelled),
            ),
        sourceNames =
            mapOf(
                BookingSource.WALK_IN to context.getString(R.string.booking_source_walk_in),
                BookingSource.PHONE to context.getString(R.string.booking_source_phone),
                BookingSource.REFERRAL to context.getString(R.string.booking_source_referral),
                BookingSource.REPEAT to context.getString(R.string.booking_source_repeat),
                BookingSource.OTHER to context.getString(R.string.booking_source_other),
            ),
        line = { label, value -> context.getString(R.string.settings_gcal_description_line, label, value) },
        managedBy = context.getString(R.string.settings_gcal_event_managed_by),
    )
