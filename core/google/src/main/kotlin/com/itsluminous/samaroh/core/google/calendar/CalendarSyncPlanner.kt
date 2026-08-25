package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import kotlinx.serialization.Serializable

/** Per-booking record of what was last pushed to the calendar. */
@Serializable
data class SyncedEventState(
    val eventId: String,
    val fingerprint: String,
)

/** What one sync pass must do (§4.1 one-way push: create/update/delete). */
data class GcalSyncPlan(
    val creates: List<Booking>,
    val updates: List<Pair<Booking, SyncedEventState>>,
    /** bookingId → state; cancelled or locally-gone bookings whose events must be removed. */
    val deletes: Map<String, SyncedEventState>,
) {
    val isEmpty: Boolean get() = creates.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/**
 * Pure diffing of local bookings against the last-pushed state — no I/O, unit-tested.
 * "Bulk-push on enable" falls out naturally: with an empty [state] every pushable
 * booking becomes a create.
 */
object CalendarSyncPlanner {
    fun plan(
        bookings: List<Booking>,
        state: Map<String, SyncedEventState>,
        fingerprintOf: (Booking) -> String,
    ): GcalSyncPlan {
        val creates = mutableListOf<Booking>()
        val updates = mutableListOf<Pair<Booking, SyncedEventState>>()
        val deletes = mutableMapOf<String, SyncedEventState>()
        val seenIds = mutableSetOf<String>()

        for (booking in bookings) {
            seenIds += booking.id
            val pushed = state[booking.id]
            val pushable = booking.status != BookingStatus.CANCELLED && booking.deletedAt == null
            when {
                !pushable -> pushed?.let { deletes[booking.id] = it }
                pushed == null -> creates += booking
                pushed.fingerprint != fingerprintOf(booking) -> updates += booking to pushed
            }
        }
        // State entries whose booking vanished locally (tombstoned outside the query window).
        for ((bookingId, pushed) in state) {
            if (bookingId !in seenIds) deletes[bookingId] = pushed
        }
        return GcalSyncPlan(creates = creates, updates = updates, deletes = deletes)
    }
}
