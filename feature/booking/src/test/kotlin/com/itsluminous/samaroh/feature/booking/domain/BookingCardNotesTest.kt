package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Booking-card notes rules (ADR-092) — byte-for-byte the web `BookingDetail` behaviour. */
class BookingCardNotesTest {
    @Test
    fun `non-blank booking notes are shown trimmed`() {
        assertThat(BookingCardNotes.displayNotes("  Stage on the east side \n")).isEqualTo("Stage on the east side")
    }

    @Test
    fun `null and blank notes render nothing`() {
        assertThat(BookingCardNotes.displayNotes(null)).isNull()
        assertThat(BookingCardNotes.displayNotes("")).isNull()
        assertThat(BookingCardNotes.displayNotes("   \n\t")).isNull()
    }

    @Test
    fun `payment line is date middle-dot method without notes`() {
        assertThat(BookingCardNotes.paymentLine("12 Mar 2026", "UPI", null)).isEqualTo("12 Mar 2026 \u00B7 UPI")
        assertThat(BookingCardNotes.paymentLine("12 Mar 2026", "UPI", "  ")).isEqualTo("12 Mar 2026 \u00B7 UPI")
    }

    @Test
    fun `payment line appends notes after a second middle dot`() {
        assertThat(BookingCardNotes.paymentLine("12 Mar 2026", "Cash", "advance"))
            .isEqualTo("12 Mar 2026 \u00B7 Cash \u00B7 advance")
    }
}
