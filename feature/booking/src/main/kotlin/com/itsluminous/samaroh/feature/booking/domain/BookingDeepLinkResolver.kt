package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking
import java.time.LocalDate

/**
 * Pure routing of an "open this booking" reminder deep link (ADR-098) onto the Booking
 * tab — the same card the calendar opens on tap, or a graceful fallback:
 *
 * - [Landing.Calendar]: show the Booking tab as it is, no card, nothing sensitive. Used
 *   when the booking is unknown locally, belongs to a business other than the active one
 *   (v1 is single-business — there is no selection to switch, ADR-017), or the viewer
 *   lacks `booking.view` for it.
 * - [Landing.Date]: the booking was DELETED since the reminder fired (tombstoned row) —
 *   land on its start date (month / agenda) with no card.
 * - [Landing.Card]: open the card. A CANCELLED booking still opens — exactly what the
 *   calendar does for a struck-through row.
 */
object BookingDeepLinkResolver {
    sealed interface Landing {
        data object Calendar : Landing

        data class Date(
            val date: LocalDate,
        ) : Landing

        data class Card(
            val bookingId: String,
            val date: LocalDate,
        ) : Landing
    }

    /**
     * @param linkBusinessId business id carried by the link (null for a legacy intent).
     * @param activeBusinessId the business every tab operates on; null before onboarding.
     * @param booking the local row for the link's booking id (tombstoned rows included); null if unknown.
     * @param canView whether the actor may view bookings of the active business.
     */
    fun resolve(
        linkBusinessId: String?,
        activeBusinessId: String?,
        booking: Booking?,
        canView: Boolean,
    ): Landing {
        if (activeBusinessId == null || !canView) return Landing.Calendar
        val targetBusiness = linkBusinessId ?: booking?.businessId
        if (targetBusiness != null && targetBusiness != activeBusinessId) return Landing.Calendar
        if (booking == null || booking.businessId != activeBusinessId) return Landing.Calendar
        if (booking.deletedAt != null) return Landing.Date(booking.startDate)
        return Landing.Card(booking.id, booking.startDate)
    }
}
