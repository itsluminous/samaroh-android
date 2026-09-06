package com.itsluminous.samaroh.core.google.calendar

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.TENTATIVE_ICON
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class GcalEventMapperTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val tentativeSuffix = " (Tentative)"
    private val description = "Total: X · Paid: Y · Due: Z"

    private fun map(booking: com.itsluminous.samaroh.core.model.Booking) =
        GcalEventMapper.toEvent(booking, tentativeSuffix, description, zone)

    @Test
    fun `title is icon event type dash customer`() {
        val event = map(Fixtures.booking())
        assertThat(event.summary).isEqualTo("💒 wedding - fixture-customer")
    }

    @Test
    fun `tentative bookings get the tentative suffix and the tentative icon`() {
        val event = map(Fixtures.booking(status = BookingStatus.TENTATIVE))
        // Tentative bookings render 👤 instead of the event icon (ADR-020).
        assertThat(event.summary).isEqualTo("$TENTATIVE_ICON wedding - fixture-customer (Tentative)")
    }

    @Test
    fun `confirming reverts the title to the event icon`() {
        assertThat(map(Fixtures.booking(status = BookingStatus.CONFIRMED)).summary).startsWith("💒")
    }

    @Test
    fun `confirmed bookings have no suffix`() {
        assertThat(map(Fixtures.booking(status = BookingStatus.CONFIRMED)).summary).doesNotContain("Tentative")
    }

    @Test
    fun `date-only bookings map to all-day events with exclusive end`() {
        val event =
            map(Fixtures.booking(startDate = LocalDate.of(2026, 9, 10), endDate = LocalDate.of(2026, 9, 12)))
        assertThat(event.isAllDay).isTrue()
        assertThat(event.startDate).isEqualTo(LocalDate.of(2026, 9, 10))
        // Calendar v3 all-day end.date is exclusive → endDate + 1.
        assertThat(event.endDateExclusive).isEqualTo(LocalDate.of(2026, 9, 13))
        assertThat(event.startDateTime).isNull()
    }

    @Test
    fun `timed bookings map to dateTime events in the given zone`() {
        val booking =
            Fixtures.booking(startDate = LocalDate.of(2026, 9, 10)).copy(
                startTime = LocalTime.of(18, 30),
                endTime = LocalTime.of(23, 0),
            )
        val event = map(booking)
        assertThat(event.isAllDay).isFalse()
        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 9, 10, 18, 30))
        assertThat(event.endDateTime).isEqualTo(LocalDateTime.of(2026, 9, 10, 23, 0))
        assertThat(event.timeZone).isEqualTo("Asia/Kolkata")
        assertThat(event.startDate).isNull()
    }

    @Test
    fun `description passes through`() {
        assertThat(map(Fixtures.booking()).description).isEqualTo(description)
    }

    @Test
    fun `fingerprint changes with every description-visible field, stable otherwise`() {
        val booking = Fixtures.booking(id = "b-1")
        val base = GcalEventMapper.fingerprint(booking, paidPaise = 0)

        assertThat(GcalEventMapper.fingerprint(booking, paidPaise = 0)).isEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(customerName = "someone-else"), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(status = BookingStatus.TENTATIVE), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking, paidPaise = 50_000_00L)).isNotEqualTo(base)
        // ADR-047: the description now carries phone/invoice/source/notes — all fingerprinted.
        assertThat(GcalEventMapper.fingerprint(booking.copy(customerPhone = "9876543210"), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(invoiceNumber = "INV-1"), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(source = BookingSource.PHONE), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(notes = "note"), paidPaise = 0)).isNotEqualTo(base)
    }

    @Test
    fun `fingerprint ignores gcalEventId and audit timestamps - the convergence invariant`() {
        // ADR-047: the engine's own recordEventId write (gcal_event_id + updated_at)
        // re-enters the trigger paths; the follow-up pass MUST fingerprint identical or
        // the calendar loop would never converge.
        val booking = Fixtures.booking(id = "b-1")
        val base = GcalEventMapper.fingerprint(booking, paidPaise = 0)
        val afterRecord =
            booking.copy(gcalEventId = "ev-99", updatedAt = booking.updatedAt.plusSeconds(60))
        assertThat(GcalEventMapper.fingerprint(afterRecord, paidPaise = 0)).isEqualTo(base)
    }

    // --- rich description (ADR-047) ---

    private val strings =
        GcalDescriptionStrings(
            customerNameLabel = "Customer name",
            customerPhoneLabel = "Phone number",
            eventTypeLabel = "Event type",
            statusLabel = "Status",
            totalLabel = "Total",
            securityDepositLabel = "Security deposit",
            advanceLabel = "Advance paid",
            dueLabel = "Due",
            invoiceNumberLabel = "Invoice number",
            sourceLabel = "Booking source",
            notesLabel = "Notes",
            statusNames =
                mapOf(
                    BookingStatus.TENTATIVE to "Tentative",
                    BookingStatus.CONFIRMED to "Confirmed",
                    BookingStatus.COMPLETED to "Completed",
                    BookingStatus.CANCELLED to "Cancelled",
                ),
            sourceNames =
                mapOf(
                    BookingSource.WALK_IN to "Walk-in",
                    BookingSource.PHONE to "Phone",
                    BookingSource.REFERRAL to "Referral",
                    BookingSource.REPEAT to "Repeat",
                    BookingSource.OTHER to "Other",
                ),
            line = { label, value -> "$label: $value" },
            managedBy = "Managed by Samaroh",
        )

    @Test
    fun `description renders the full booking picture one field per line`() {
        val booking =
            Fixtures
                .booking(totalAmountPaise = 1_50_000_00L, securityDepositPaise = 10_000_00L)
                .copy(
                    customerPhone = "9876543210",
                    invoiceNumber = "INV-2026-01",
                    source = BookingSource.REFERRAL,
                    notes = "stage setup\nby 4 pm",
                )
        val lines = GcalEventMapper.description(booking, paidPaise = 50_000_00L, strings = strings).split("\n")
        assertThat(lines)
            .containsExactly(
                "Customer name: fixture-customer",
                "Phone number: 9876543210",
                "Event type: wedding",
                "Status: Confirmed",
                // AmountFormatter Indian grouping (ADR-002).
                "Total: ₹1,50,000",
                "Security deposit: ₹10,000",
                "Advance paid: ₹50,000",
                "Due: ₹1,00,000",
                "Invoice number: INV-2026-01",
                "Booking source: Referral",
                // Notes verbatim (multi-line allowed), LAST before the footer.
                "Notes: stage setup",
                "by 4 pm",
                "Managed by Samaroh",
            ).inOrder()
    }

    @Test
    fun `description omits unset optional fields instead of rendering blanks`() {
        val description = GcalEventMapper.description(Fixtures.booking(), paidPaise = 0, strings = strings)
        assertThat(description).doesNotContain("Phone number")
        assertThat(description).doesNotContain("Invoice number")
        assertThat(description).doesNotContain("Booking source")
        assertThat(description).doesNotContain("Notes")
        assertThat(description).endsWith("Managed by Samaroh")
    }

    @Test
    fun `description due never goes negative on overpayment`() {
        val booking = Fixtures.booking(totalAmountPaise = 1_000_00L)
        val description = GcalEventMapper.description(booking, paidPaise = 2_000_00L, strings = strings)
        assertThat(description).contains("Due: ₹0")
    }

    @Test
    fun `every event carries the booking id and managed marker as private properties`() {
        val event = map(Fixtures.booking(id = "b-42"))
        assertThat(event.privateProperties)
            .containsExactly(
                GcalEventMapper.PROP_BOOKING_ID,
                "b-42",
                GcalEventMapper.PROP_MANAGED,
                GcalEventMapper.PROP_MANAGED_VALUE,
            )
    }

    @Test
    fun `request body embeds the private extended properties`() {
        val body = map(Fixtures.booking(id = "b-42")).toRequestBody()
        assertThat(body).contains("\"extendedProperties\"")
        assertThat(body).contains("\"samarohBookingId\":\"b-42\"")
        assertThat(body).contains("\"samarohManaged\":\"1\"")
    }
}
