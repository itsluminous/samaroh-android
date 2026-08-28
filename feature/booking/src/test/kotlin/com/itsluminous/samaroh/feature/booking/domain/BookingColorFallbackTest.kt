package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.presetFixture
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import org.junit.Test

/**
 * Booking colour fallback chain (ADR-031, preset-backed since ADR-032): explicit
 * `bookings.color` → the matching PRESET's default colour → null (the standard themed
 * look). The seeded fixture mirrors migration 006: Wedding → tomato (in the fake
 * palette), Birthday → banana (NOT in the fake palette), Custom → grape.
 */
class BookingColorFallbackTest {
    private val colors = FakeBookingColorCatalog()
    private val presets = seededPresetFixtures()

    private fun effective(
        color: String?,
        eventType: String,
    ) = BookingColorFallback.effectiveKey(color, eventType, colors, presets)

    @Test
    fun `explicit color wins over the type default`() {
        assertThat(effective("sky", "Wedding")).isEqualTo("sky")
    }

    @Test
    fun `no explicit color falls back to the matching preset's colour`() {
        assertThat(effective(null, "Wedding")).isEqualTo("tomato")
    }

    @Test
    fun `legacy built-in keys match their seeded preset by normalized label`() {
        // Pre-006 bookings recorded keys, not labels (ADR-032 normalization contract).
        assertThat(effective(null, "wedding")).isEqualTo("tomato")
        assertThat(effective(null, "room_booking")).isEqualTo("sky")
    }

    @Test
    fun `free text with no matching preset stays themed`() {
        assertThat(effective(null, "family-function")).isNull()
        // …unless explicitly coloured.
        assertThat(effective("sky", "family-function")).isEqualTo("sky")
    }

    @Test
    fun `free text matching a user preset follows its colour`() {
        val withHaldi = presets + presetFixture("Haldi", color = "sky", sortOrder = 9)
        assertThat(BookingColorFallback.effectiveKey(null, "Haldi", colors, withHaldi)).isEqualTo("sky")
    }

    @Test
    fun `unknown explicit key falls through to the preset default`() {
        assertThat(effective("future-color", "Wedding")).isEqualTo("tomato")
    }

    @Test
    fun `preset colour missing from the palette falls through to themed`() {
        assertThat(effective(null, "Birthday")).isNull()
    }

    @Test
    fun `deleted presets never colour bookings`() {
        val deleted =
            presets.map { if (it.label == "Wedding") it.copy(deletedAt = it.createdAt) else it }
        assertThat(BookingColorFallback.effectiveKey(null, "Wedding", colors, deleted)).isNull()
    }

    @Test
    fun `literal custom bookings follow the Custom preset row`() {
        // Since ADR-032 the Custom PRESET is a normal row (seeded grape): the literal
        // `custom` a blank custom label stores matches it like any other preset.
        assertThat(effective(null, "custom")).isEqualTo("grape")
    }

    @Test
    fun `effectiveColor resolves the palette entry for a booking`() {
        val typed = Fixtures.booking() // wedding, no explicit colour
        assertThat(BookingColorFallback.effectiveColor(typed, colors, presets)?.key).isEqualTo("tomato")
        val explicit = typed.copy(color = "sky")
        assertThat(BookingColorFallback.effectiveColor(explicit, colors, presets)?.key).isEqualTo("sky")
        val custom = typed.copy(eventType = "family-function")
        assertThat(BookingColorFallback.effectiveColor(custom, colors, presets)).isNull()
    }
}
