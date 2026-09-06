package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.displayIcon
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
    /**
     * Private extended properties stamped on every pushed event (ADR-046): the source
     * booking id + an app marker. They make app-created events findable/attributable
     * server-side, which is what the duplicate-repair pass keys on.
     */
    val privateProperties: Map<String, String> = emptyMap(),
) {
    val isAllDay: Boolean get() = startDate != null
}

/**
 * Localized building blocks for the rich event description (ADR-047): field labels
 * (reused `booking.*` catalog keys), enum value names, the `"{label}: {value}"` line
 * template and the managed-by footer — all resolved by the caller from the app's
 * current locale so the mapper itself stays pure.
 */
data class GcalDescriptionStrings(
    val customerNameLabel: String,
    val customerPhoneLabel: String,
    val eventTypeLabel: String,
    val statusLabel: String,
    val totalLabel: String,
    val securityDepositLabel: String,
    val advanceLabel: String,
    val dueLabel: String,
    val invoiceNumberLabel: String,
    val sourceLabel: String,
    val notesLabel: String,
    val statusNames: Map<BookingStatus, String>,
    val sourceNames: Map<BookingSource, String>,
    /** Renders one `"{label}: {value}"` description line (settings.gcal.description_line). */
    val line: (label: String, value: String) -> String,
    /** Footer line, `settings.gcal.event_managed_by`. */
    val managedBy: String,
)

/** Pure Booking → [GcalEvent] mapping (§4.1) — no I/O, unit-tested. */
object GcalEventMapper {
    /** Private extended-property key carrying the source booking id (ADR-046). */
    const val PROP_BOOKING_ID = "samarohBookingId"

    /** Private extended-property marker identifying events created by this app (ADR-046). */
    const val PROP_MANAGED = "samarohManaged"

    const val PROP_MANAGED_VALUE = "1"

    /**
     * Description-format version salted into [fingerprint] (ADR-047). Bumping it changes
     * every fingerprint at once, so the next pass re-pushes each recorded event exactly
     * ONCE as an UPDATE (PATCH of the recorded event id — never an insert, so no
     * duplicates) carrying the new description format.
     */
    const val FORMAT_VERSION = "v2"

    /**
     * @param tentativeSuffix localized `" (Tentative)"` suffix appended for tentative bookings.
     * @param description pre-formatted localized description — see [description].
     */
    fun toEvent(
        booking: Booking,
        tentativeSuffix: String,
        description: String,
        zoneId: ZoneId,
    ): GcalEvent {
        // §4.1: event title = the booking's formatted title "{icon} {EventType} - {Customer}".
        val baseTitle = "${booking.displayIcon} ${booking.eventType} - ${booking.customerName}"
        val summary = if (booking.status == BookingStatus.TENTATIVE) baseTitle + tentativeSuffix else baseTitle
        val privateProperties =
            mapOf(
                PROP_BOOKING_ID to booking.id,
                PROP_MANAGED to PROP_MANAGED_VALUE,
            )
        val startTime = booking.startTime
        val endTime = booking.endTime
        return if (startTime != null && endTime != null) {
            GcalEvent(
                summary = summary,
                description = description,
                startDateTime = LocalDateTime.of(booking.startDate, startTime),
                endDateTime = LocalDateTime.of(booking.endDate, endTime),
                timeZone = zoneId.id,
                privateProperties = privateProperties,
            )
        } else {
            GcalEvent(
                summary = summary,
                description = description,
                startDate = booking.startDate,
                endDateExclusive = booking.endDate.plusDays(1),
                privateProperties = privateProperties,
            )
        }
    }

    /**
     * The rich event description (ADR-047, owner REQ): the booking's full picture, EACH
     * FIELD ON ITS OWN LINE — customer name, phone, event type, status, total, security
     * deposit, advance paid, due, invoice number (when assigned), source (when set),
     * notes (verbatim, last) and the managed-by footer. Optional fields are omitted
     * rather than rendered blank. Amounts render via [AmountFormatter] (Indian
     * grouping, ADR-002).
     */
    fun description(
        booking: Booking,
        paidPaise: Long,
        strings: GcalDescriptionStrings,
    ): String {
        val duePaise = (booking.totalAmountPaise - paidPaise).coerceAtLeast(0)
        val lines = mutableListOf<String>()
        lines += strings.line(strings.customerNameLabel, booking.customerName)
        booking.customerPhone?.takeIf { it.isNotBlank() }?.let { lines += strings.line(strings.customerPhoneLabel, it) }
        lines += strings.line(strings.eventTypeLabel, booking.eventType)
        lines += strings.line(strings.statusLabel, strings.statusNames.getValue(booking.status))
        lines += strings.line(strings.totalLabel, AmountFormatter.format(booking.totalAmountPaise))
        lines += strings.line(strings.securityDepositLabel, AmountFormatter.format(booking.securityDepositPaise))
        lines += strings.line(strings.advanceLabel, AmountFormatter.format(paidPaise))
        lines += strings.line(strings.dueLabel, AmountFormatter.format(duePaise))
        booking.invoiceNumber?.takeIf { it.isNotBlank() }?.let { lines += strings.line(strings.invoiceNumberLabel, it) }
        booking.source?.let { lines += strings.line(strings.sourceLabel, strings.sourceNames.getValue(it)) }
        booking.notes?.takeIf { it.isNotBlank() }?.let { lines += strings.line(strings.notesLabel, it) }
        lines += strings.managedBy
        return lines.joinToString("\n")
    }

    /**
     * Stable content hash of everything that affects the pushed event — booking fields
     * plus the paid total (the description carries the amounts + due). The sync engine
     * skips pushes when the fingerprint is unchanged.
     *
     * CONVERGENCE INVARIANT (ADR-047): `gcalEventId` and the audit timestamps are
     * deliberately EXCLUDED. The engine's own `recordEventId` write (gcal_event_id +
     * updated_at) re-enters the trigger paths — locally via the outbox listener and,
     * after the server echoes it, via the remote-pull listener — and the follow-up pass
     * must plan EMPTY (fingerprint unchanged → no HTTP, no writes) for the loop to
     * terminate. Never add either field here.
     */
    fun fingerprint(
        booking: Booking,
        paidPaise: Long,
    ): String =
        listOf(
            FORMAT_VERSION,
            booking.eventIcon,
            booking.eventType,
            booking.customerName,
            booking.customerPhone,
            booking.startDate,
            booking.endDate,
            booking.startTime,
            booking.endTime,
            booking.status.wire,
            booking.totalAmountPaise,
            booking.securityDepositPaise,
            paidPaise,
            booking.invoiceNumber,
            booking.source?.wire,
            booking.notes,
        ).joinToString("|")
            .hashCode()
            .toString(16)
}
