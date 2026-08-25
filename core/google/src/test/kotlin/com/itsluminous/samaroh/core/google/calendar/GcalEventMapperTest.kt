package com.itsluminous.samaroh.core.google.calendar

import com.google.common.truth.Truth.assertThat
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
    fun `fingerprint changes with event fields and paid amount, stable otherwise`() {
        val booking = Fixtures.booking(id = "b-1")
        val base = GcalEventMapper.fingerprint(booking, paidPaise = 0)

        assertThat(GcalEventMapper.fingerprint(booking, paidPaise = 0)).isEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(customerName = "someone-else"), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking.copy(status = BookingStatus.TENTATIVE), paidPaise = 0))
            .isNotEqualTo(base)
        assertThat(GcalEventMapper.fingerprint(booking, paidPaise = 50_000_00L)).isNotEqualTo(base)
        // Fields that do NOT affect the event leave the fingerprint alone.
        assertThat(GcalEventMapper.fingerprint(booking.copy(notes = "internal note"), paidPaise = 0)).isEqualTo(base)
    }
}
