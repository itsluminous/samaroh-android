package com.itsluminous.samaroh.core.i18n

import android.content.Context

/**
 * Display label for a stored `bookings.event_type` value, usable from ANY module
 * (ADR-101: the Expenses ledger's event chip needs it without depending on
 * `feature:booking`). Since ADR-032 the value is a user-managed preset LABEL rendered
 * verbatim; pre-006 bookings stored a built-in KEY (`wedding`) which maps to the catalog
 * (`booking.event_type.*`). Mirrors `core:invoice`'s private table — keep both in step
 * with `shared/event-types.json`.
 */
object EventTypeLabels {
    private val builtIn =
        mapOf(
            "engagement" to R.string.booking_event_type_engagement,
            "tilak" to R.string.booking_event_type_tilak,
            "wedding" to R.string.booking_event_type_wedding,
            "room_booking" to R.string.booking_event_type_room_booking,
            "birthday" to R.string.booking_event_type_birthday,
            "anniversary" to R.string.booking_event_type_anniversary,
            "custom" to R.string.booking_event_type_custom,
        )

    fun label(
        context: Context,
        eventType: String,
    ): String = builtIn[eventType]?.let(context::getString) ?: eventType

    /**
     * First name of a booking customer, the way the calendar titles and pills show it
     * (`BookingTitleFormatter.firstName` convention): trimmed, up to the first space.
     */
    fun customerFirstName(customerName: String): String = customerName.trim().substringBefore(' ')
}
