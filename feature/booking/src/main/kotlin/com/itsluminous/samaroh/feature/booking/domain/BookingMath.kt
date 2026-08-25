package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.Booking

/**
 * Money arithmetic for bookings (all values Long paise, ADR-002).
 * due = total − Σ(payments); ALWAYS computed, never stored (§2).
 */
object DueCalculator {
    /** Outstanding amount. Never negative — overpayment clamps to zero due. */
    fun duePaise(
        totalAmountPaise: Long,
        paidPaise: Long,
    ): Long = (totalAmountPaise - paidPaise).coerceAtLeast(0L)

    fun duePaise(
        booking: Booking,
        paidPaise: Long,
    ): Long = duePaise(booking.totalAmountPaise, paidPaise)
}

/**
 * The booking's display title: `{icon} {EventType} - {Customer Name}` (§4.1). The same
 * string becomes the Google Calendar event title, so the shape is a contract.
 */
object BookingTitleFormatter {
    fun title(
        eventIcon: String,
        eventTypeLabel: String,
        customerName: String,
    ): String = "$eventIcon $eventTypeLabel - $customerName"

    /** The customer's first name, as rendered inside calendar pills. */
    fun firstName(customerName: String): String = customerName.trim().substringBefore(' ')
}
