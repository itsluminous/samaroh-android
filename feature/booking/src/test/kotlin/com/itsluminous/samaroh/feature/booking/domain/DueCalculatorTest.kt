package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test

/** Due calculation (§2): due = total − Σ payments, computed, never negative. */
class DueCalculatorTest {
    @Test
    fun `due is total minus paid`() {
        assertThat(DueCalculator.duePaise(totalAmountPaise = 2_00_000_00L, paidPaise = 50_000_00L))
            .isEqualTo(1_50_000_00L)
    }

    @Test
    fun `due is zero when fully paid`() {
        assertThat(DueCalculator.duePaise(totalAmountPaise = 1_00_000_00L, paidPaise = 1_00_000_00L)).isEqualTo(0L)
    }

    @Test
    fun `overpayment clamps to zero`() {
        assertThat(DueCalculator.duePaise(totalAmountPaise = 1_000_00L, paidPaise = 2_000_00L)).isEqualTo(0L)
    }

    @Test
    fun `booking overload uses total amount`() {
        val booking = Fixtures.booking(totalAmountPaise = 2_00_000_00L)
        assertThat(DueCalculator.duePaise(booking, paidPaise = 50_000_00L)).isEqualTo(1_50_000_00L)
    }

    @Test
    fun `zero total with no payments is zero due`() {
        assertThat(DueCalculator.duePaise(totalAmountPaise = 0L, paidPaise = 0L)).isEqualTo(0L)
    }

    @Test
    fun `title format matches the calendar contract`() {
        assertThat(BookingTitleFormatter.title("X", "TypeLabel", "Full Name")).isEqualTo("X TypeLabel - Full Name")
        assertThat(BookingTitleFormatter.firstName("Full Name Here")).isEqualTo("Full")
        assertThat(BookingTitleFormatter.firstName("  Padded ")).isEqualTo("Padded")
    }
}
