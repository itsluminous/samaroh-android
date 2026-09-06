package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.RemoteChangeListener
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immediate calendar push on REMOTE booking changes (ADR-047). The remote twin of
 * [BookingMutationCalendarTrigger]: a booking edited by another member arrives via the
 * sync PULL ([com.itsluminous.samaroh.core.sync.engine.LocalApplier]) — no outbox row,
 * so the ADR-046 local-mutation trigger never sees it and the event lagged until the
 * 6-hour periodic. The sync engine now reports the applied business-scoped tables per
 * run (multibound [RemoteChangeListener]); when bookings or booking payments (payments
 * change the description's paid/due amounts) of a gcal-enabled business were applied,
 * the same debounced calendar one-shot is enqueued.
 *
 * LOOP GUARD: the calendar engine's `recordEventId` write is outbox-pushed, echoed back
 * by the server, and re-applied by a later pull — which lands HERE again. That pass
 * plans EMPTY because the fingerprint deliberately excludes `gcal_event_id` and the
 * audit timestamps ([GcalEventMapper.fingerprint]); an empty plan returns before any
 * network call or write, so the cycle converges after one free extra pass — proven in
 * `CalendarSyncEngineTest's convergence test`.
 */
@Singleton
class RemoteBookingCalendarTrigger
    @Inject
    constructor(
        private val businessRepository: BusinessRepository,
        private val scheduler: CalendarSyncScheduler,
    ) : RemoteChangeListener {
        override suspend fun onRemoteChangesApplied(appliedTables: Map<String, Set<String>>) {
            val businessIds =
                CALENDAR_RELEVANT_TABLES
                    .flatMap { appliedTables[it].orEmpty() }
                    .toSet()
            for (businessId in businessIds) {
                val settings = businessRepository.settings(businessId).first()
                if (settings?.gcalSyncEnabled != true) continue
                scheduler.requestSyncOnLocalChange(businessId)
            }
        }

        private companion object {
            val CALENDAR_RELEVANT_TABLES = setOf("bookings", "booking_payments")
        }
    }
