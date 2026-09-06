package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.LocalMutationListener
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immediate calendar push on booking mutations (ADR-046, REQ: no 6-hour wait). Rides the
 * ADR-036 on-local-change path: [com.itsluminous.samaroh.core.sync.RoomOutboxWriter]
 * notifies this listener for every queued mutation; when the mutation touches a booking
 * or a booking payment (payments change the event description's amounts) of a business
 * with calendar sync enabled, the debounced calendar one-shot is enqueued.
 *
 * The calendar engine's own `recordEventId` writes re-enter here once per pass; the
 * follow-up run plans empty and makes no network calls, so the loop converges.
 */
@Singleton
class BookingMutationCalendarTrigger
    @Inject
    constructor(
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val scheduler: CalendarSyncScheduler,
    ) : LocalMutationListener {
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun onLocalMutation(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            // No isConfigured gate here: CalendarSyncWorker no-ops when Google is not
            // configured, and skipping BuildConfig keeps this class hermetic in tests.
            if (entityType !in CALENDAR_RELEVANT_TYPES) return
            val businessId = resolveBusinessId(entityType, entityId, payloadJson) ?: return
            val settings = businessRepository.settings(businessId).first()
            if (settings?.gcalSyncEnabled != true) return
            scheduler.requestSyncOnLocalChange(businessId)
        }

        /** UPSERT payloads carry `business_id`; tombstones don't — fall back to the Room row. */
        private suspend fun resolveBusinessId(
            entityType: String,
            entityId: String,
            payloadJson: String,
        ): String? {
            runCatching {
                json
                    .parseToJsonElement(payloadJson)
                    .jsonObject["business_id"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()?.let { return it }
            return when (entityType) {
                "bookings" -> bookingRepository.booking(entityId)?.businessId
                else -> null
            }
        }

        private companion object {
            val CALENDAR_RELEVANT_TYPES = setOf("bookings", "booking_payments")
        }
    }
