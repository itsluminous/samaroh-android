package com.itsluminous.samaroh.feature.booking.ui.form

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.EventTypeKind
import com.itsluminous.samaroh.feature.booking.presetFixture
import org.junit.Test
import java.time.LocalDate

/**
 * The event-type picker model (ADR-096): each dropdown row carries a marker flag
 * RESOLVED from the live presets, so the badge shown before choosing can never disagree
 * with the amount blackout `isMarkerType` applies after choosing.
 */
class EventTypePickerEntryTest {
    private val wedding = presetFixture("Wedding", sortOrder = 0)
    private val lagan = presetFixture("Lagan", sortOrder = 1, kind = EventTypeKind.MARKER)
    private val tilak = presetFixture("Tilak", sortOrder = 2, kind = EventTypeKind.MARKER)
    private val custom = presetFixture("Custom", sortOrder = 3)
    private val day = LocalDate.of(2026, 10, 7)

    private fun formState(presets: List<EventType> = emptyList()) = BookingFormState(presets = presets, startDate = day, endDate = day)

    @Test
    fun `marker flag follows the preset kind in sort order`() {
        val state = formState(presets = listOf(wedding, lagan, tilak, custom))

        assertThat(state.pickerEntries.map { it.preset.label to it.isMarker })
            .containsExactly("Wedding" to false, "Lagan" to true, "Tilak" to true)
            .inOrder()
    }

    @Test
    fun `the Custom preset is represented by the built-in entry, never a row`() {
        val state = formState(presets = listOf(custom, presetFixture("custom", id = "p2")))

        assertThat(state.pickerEntries).isEmpty()
    }

    @Test
    fun `a tombstoned marker preset is never a marker row`() {
        // A deleted preset is not offered by the repository flow in practice, but the
        // kind resolver ignores tombstones (ADR-041 §3) — the row must agree with it.
        val deleted = lagan.copy(deletedAt = java.time.Instant.EPOCH)
        val state = formState(presets = listOf(wedding, deleted))

        assertThat(state.pickerEntries.single { it.preset.label == "Lagan" }.isMarker).isFalse()
    }

    @Test
    fun `selecting a marker row flips isMarkerType but a booking row does not`() {
        val state = formState(presets = listOf(wedding, lagan))

        val markerRow = state.pickerEntries.single { it.isMarker }
        val bookingRow = state.pickerEntries.single { !it.isMarker }

        assertThat(state.copy(eventTypeChoice = EventTypeChoice.Preset(markerRow.preset)).isMarkerType).isTrue()
        assertThat(state.copy(eventTypeChoice = EventTypeChoice.Preset(bookingRow.preset)).isMarkerType).isFalse()
    }

    @Test
    fun `no presets means no rows and no marker`() {
        val state = formState()

        assertThat(state.pickerEntries).isEmpty()
        assertThat(state.isMarkerType).isFalse()
    }
}
