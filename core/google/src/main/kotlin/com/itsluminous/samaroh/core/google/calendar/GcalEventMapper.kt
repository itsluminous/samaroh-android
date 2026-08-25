package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A calendar event payload, ready for the Calendar v3 REST body. Either the all-day pair
 * ([startDate]/[endDateExclusive]) or the timed pair ([startDateTime]/[endDateTime]) is
 * set, never both (§4.1: "date(s) map to all-day or timed events").
 */
data class GcalEvent(
    val summary: String,
    val description: String,
    val startDate: LocalDate? = null,
    /** Calendar v3 all-day `end.date` is EXCLUSIVE — always `booking.endDate + 1`. */
    val endDateExclusive: LocalDate? = null,
    val startDateTime: LocalDateTime? = null,
    val endDateTime: LocalDateTime? = null,
    val timeZone: String? = null,
) {
    val isAllDay: Boolean get() = startDate != null
}

/** Pure Booking → [GcalEvent] mapping (§4.1) — no I/O, unit-tested. */
object GcalEventMapper {
    /**
     * @param tentativeSuffix localized `" (Tentative)"` suffix appended for tentative bookings.
     * @param description pre-formatted localized description (amounts summary + managed-by line).
     */
    fun toEvent(
        booking: Booking,
        tentativeSuffix: String,
        description: String,
        zoneId: ZoneId,
    ): GcalEvent {
        // §4.1: event title = the booking's formatted title "{icon} {EventType} - {Customer}".
        val baseTitle = "${booking.eventIcon} ${booking.eventType} - ${booking.customerName}"
        val summary = if (booking.status == BookingStatus.TENTATIVE) baseTitle + tentativeSuffix else baseTitle
        val startTime = booking.startTime
        val endTime = booking.endTime
        return if (startTime != null && endTime != null) {
            GcalEvent(
                summary = summary,
                description = description,
                startDateTime = LocalDateTime.of(booking.startDate, startTime),
                endDateTime = LocalDateTime.of(booking.endDate, endTime),
                timeZone = zoneId.id,
            )
        } else {
            GcalEvent(
                summary = summary,
                description = description,
                startDate = booking.startDate,
                endDateExclusive = booking.endDate.plusDays(1),
            )
        }
    }

    /**
     * Stable content hash of everything that affects the pushed event — booking fields
     * plus the paid total (the description carries the amounts summary). The sync engine
     * skips pushes when the fingerprint is unchanged.
     */
    fun fingerprint(
        booking: Booking,
        paidPaise: Long,
    ): String =
        listOf(
            booking.eventIcon,
            booking.eventType,
            booking.customerName,
            booking.startDate,
            booking.endDate,
            booking.startTime,
            booking.endTime,
            booking.status.wire,
            booking.totalAmountPaise,
            booking.securityDepositPaise,
            paidPaise,
        ).joinToString("|")
            .hashCode()
            .toString(16)
}
