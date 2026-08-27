package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.FakeEventTypeCatalog
import org.junit.Test

/**
 * Booking colour fallback chain (ADR-031): explicit `bookings.color` → the event type's
 * default colour → null (the standard themed look). The fakes mirror the shared files:
 * wedding → tomato (in the palette), birthday → banana (NOT in the fake palette),
 * custom → grape (which the chain must ignore).
 */
class BookingColorFallbackTest {
    private val colors = FakeBookingColorCatalog()
    private val eventTypes = FakeEventTypeCatalog()

    private fun effective(
        color: String?,
        eventType: String,
    ) = BookingColorFallback.effectiveKey(color, eventType, colors, eventTypes)

    @Test
    fun `explicit color wins over the type default`() {
        assertThat(effective("sky", "wedding")).isEqualTo("sky")
    }

    @Test
    fun `no explicit color falls back to the type default`() {
        assertThat(effective(null, "wedding")).isEqualTo("tomato")
    }

    @Test
    fun `custom and free-text types have no type default`() {
        // The shared file assigns a colour to the literal `custom` key, but the chain
        // ignores it — an uncoloured custom booking keeps the themed look.
        assertThat(effective(null, "custom")).isNull()
        assertThat(effective(null, "family-function")).isNull()
        // …unless explicitly coloured.
        assertThat(effective("sky", "family-function")).isEqualTo("sky")
    }

    @Test
    fun `unknown explicit key falls through to the type default`() {
        assertThat(effective("future-color", "wedding")).isEqualTo("tomato")
    }

    @Test
    fun `type default missing from the palette falls through to themed`() {
        assertThat(effective(null, "birthday")).isNull()
    }

    @Test
    fun `effectiveColor resolves the palette entry for a booking`() {
        val typed = Fixtures.booking() // wedding, no explicit colour
        assertThat(BookingColorFallback.effectiveColor(typed, colors, eventTypes)?.key).isEqualTo("tomato")
        val explicit = typed.copy(color = "sky")
        assertThat(BookingColorFallback.effectiveColor(explicit, colors, eventTypes)?.key).isEqualTo("sky")
        val custom = typed.copy(eventType = "family-function")
        assertThat(BookingColorFallback.effectiveColor(custom, colors, eventTypes)).isNull()
    }
}
