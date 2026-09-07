package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.model.BookingStatus

/**
 * Which actions the booking card offers (ADR-054). Pure derivation from
 * status × marker-kind × permissions so the visibility matrix is unit-testable
 * without Compose; [com.itsluminous.samaroh.feature.booking.ui.calendar.BookingCardSheet]
 * renders exactly this state.
 */
data class BookingCardActionState(
    val showEdit: Boolean,
    val showRecordPayment: Boolean,
    val showInvoice: Boolean,
    val showWhatsAppReminder: Boolean,
    val showCancel: Boolean,
    val showRestore: Boolean,
    val showDelete: Boolean,
)

object BookingCardActions {
    /**
     * A CANCELLED booking is a dead record: nothing about it should be edited,
     * paid, invoiced or chased over WhatsApp — the only two ways forward are
     * bringing it back (Restore, `booking.edit` like every status mutation) or
     * removing it for good (Delete, `booking.delete` like Cancel before it).
     * Active bookings keep the pre-ADR-054 rules unchanged, including the
     * marker-kind money blackout (ADR-041/ADR-044).
     */
    fun forBooking(
        status: BookingStatus,
        isMarker: Boolean,
        canEdit: Boolean,
        canDelete: Boolean,
        canRecordPayment: Boolean,
        canInvoice: Boolean,
    ): BookingCardActionState {
        val cancelled = status == BookingStatus.CANCELLED
        return BookingCardActionState(
            showEdit = canEdit && !cancelled,
            showRecordPayment = canRecordPayment && !isMarker && !cancelled,
            showInvoice = canInvoice && !isMarker && !cancelled,
            showWhatsAppReminder = !isMarker && !cancelled,
            showCancel = canDelete && !cancelled,
            showRestore = canEdit && cancelled,
            showDelete = canDelete && cancelled,
        )
    }
}
