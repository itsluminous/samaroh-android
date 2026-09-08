package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.presetFixture
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

    // --- Status-label visibility (marker rows hide it, ADR-041/044) ---

    private val presetsWithMarker =
        presets +
            presetFixture(
                "Lagan",
                color = "sky",
                sortOrder = 10,
                kind = com.itsluminous.samaroh.core.model.EventTypeKind.MARKER,
            )

    @Test
    fun `real bookings show the status label`() {
        val booking = Fixtures.booking()
        assertThat(AgendaRowAppearance.showsStatus(booking, presetsWithMarker)).isTrue()
    }

    @Test
    fun `marker bookings hide the status label`() {
        val booking = Fixtures.booking().copy(eventType = "Lagan")
        assertThat(AgendaRowAppearance.showsStatus(booking, presetsWithMarker)).isFalse()
    }

    @Test
    fun `cancelled markers also hide the status label`() {
        // The struck-through Cancelled LOOK stays; only the textual status disappears.
        val booking = Fixtures.booking(status = BookingStatus.CANCELLED).copy(eventType = "lagan")
        assertThat(AgendaRowAppearance.showsStatus(booking, presetsWithMarker)).isFalse()
        assertThat(look(booking)).isEqualTo(AgendaRowLook.Cancelled)
    }

    @Test
    fun `free-text type matching no preset keeps its status`() {
        val booking = Fixtures.booking().copy(eventType = "family-function")
        assertThat(AgendaRowAppearance.showsStatus(booking, presetsWithMarker)).isTrue()
    }
}
