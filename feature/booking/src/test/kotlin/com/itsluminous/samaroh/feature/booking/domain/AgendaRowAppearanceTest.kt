package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import org.junit.Test

/**
 * Agenda-row appearance (events view + month agenda + day sheet): the row's tinted
 * background reuses [BookingColorFallback]'s chain (explicit → preset default →
 * themed), with STATUS deciding first — tentative keeps its distinct amber treatment
 * and cancelled stays neutral, regardless of any colour on the booking.
 */
class AgendaRowAppearanceTest {
    private val colors = FakeBookingColorCatalog()
    private val presets = seededPresetFixtures()

    private fun look(booking: com.itsluminous.samaroh.core.model.Booking) = AgendaRowAppearance.lookFor(booking, colors, presets)

    @Test
    fun `explicit colour tints the row`() {
        val booking = Fixtures.booking().copy(color = "sky")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Tinted(colors.byKey("sky")!!))
    }

    @Test
    fun `uncoloured booking follows its preset default`() {
        // Fixture eventType is the legacy "wedding" KEY — matches the seeded
        // "Wedding" preset by normalized label (ADR-032), tinting tomato.
        val booking = Fixtures.booking()
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Tinted(colors.byKey("tomato")!!))
    }

    @Test
    fun `unknown explicit key falls through the chain to the preset default`() {
        val booking = Fixtures.booking().copy(color = "future-color")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Tinted(colors.byKey("tomato")!!))
    }

    @Test
    fun `no resolvable colour keeps the themed container`() {
        val booking = Fixtures.booking().copy(eventType = "family-function")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Themed)
    }

    @Test
    fun `tentative keeps its distinct treatment even when coloured`() {
        val booking = Fixtures.booking(status = BookingStatus.TENTATIVE).copy(color = "sky")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Tentative)
    }

    @Test
    fun `cancelled stays neutral even when coloured`() {
        val booking = Fixtures.booking(status = BookingStatus.CANCELLED).copy(color = "sky")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Cancelled)
    }

    @Test
    fun `completed bookings tint like confirmed ones`() {
        val booking = Fixtures.booking(status = BookingStatus.COMPLETED).copy(color = "grape")
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Tinted(colors.byKey("grape")!!))
    }
}
