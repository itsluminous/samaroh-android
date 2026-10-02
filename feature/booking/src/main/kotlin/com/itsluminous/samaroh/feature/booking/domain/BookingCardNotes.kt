package com.itsluminous.samaroh.feature.booking.domain

/**
 * Display rules for the free-text notes on the booking card (ADR-092) — the
 * `bookings.notes` / `booking_payments.notes` columns, NOT the Notes tab (ADR-077).
 *
 * Mirrors web's `BookingDetail.tsx` exactly: notes are NOT behind any permission key,
 * are shown for marker bookings too, and a blank value renders nothing.
 */
object BookingCardNotes {
    /** The booking notes to render, or null when there is nothing to show (null/blank). */
    fun displayNotes(notes: String?): String? = notes?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * One payment-history line, web shape: `date · method` with ` · notes` appended only
     * when the payment carries non-blank notes.
     */
    fun paymentLine(
        date: String,
        method: String,
        notes: String?,
    ): String {
        val base = "$date $SEPARATOR $method"
        val extra = displayNotes(notes) ?: return base
        return "$base $SEPARATOR $extra"
    }

    private const val SEPARATOR = "\u00B7"
}
