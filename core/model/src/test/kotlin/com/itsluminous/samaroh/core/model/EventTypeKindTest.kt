package com.itsluminous.samaroh.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant

/**
 * `EventType.kind` round-trip + resolution (ADR-041): absent-on-pull defaults to
 * booking, the local payload carries the kotlinx serial name, and normalized-label
 * matching resolves a booking's recorded type to its preset's kind.
 */
class EventTypeKindTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encodeDefaults = Json { encodeDefaults = true }

    private fun preset(
        label: String,
        kind: EventTypeKind = EventTypeKind.BOOKING,
        deletedAt: Instant? = null,
    ) = EventType(
        id = "et-$label",
        businessId = "b-1",
        label = label,
        icon = "⭐",
        kind = kind,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        deletedAt = deletedAt,
    )

    @Test
    fun `row without kind decodes as booking`() {
        // A server without the ADR-041 column returns rows with no `kind` key.
        val decoded =
            json.decodeFromString(
                EventType.serializer(),
                """{"id":"et-1","business_id":"b-1","label":"Wedding","icon":"💒",""" +
                    """"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}""",
            )
        assertThat(decoded.kind).isEqualTo(EventTypeKind.BOOKING)
    }

    @Test
    fun `kind round-trips through the local payload`() {
        val marker = preset("Lagan", kind = EventTypeKind.MARKER)
        val encoded = encodeDefaults.encodeToString(EventType.serializer(), marker)
        // Local payloads carry the kotlinx serial name; WireConverter lowercases on push.
        assertThat(encoded).contains("\"kind\":\"MARKER\"")
        assertThat(json.decodeFromString(EventType.serializer(), encoded).kind).isEqualTo(EventTypeKind.MARKER)
    }

    @Test
    fun `fromWire is tolerant of unknown values`() {
        assertThat(EventTypeKind.fromWire("marker")).isEqualTo(EventTypeKind.MARKER)
        assertThat(EventTypeKind.fromWire("booking")).isEqualTo(EventTypeKind.BOOKING)
        assertThat(EventTypeKind.fromWire("holiday")).isEqualTo(EventTypeKind.BOOKING)
    }

    @Test
    fun `kindFor matches by normalized label and skips tombstones`() {
        val presets =
            listOf(
                preset("Room Booking", kind = EventTypeKind.BOOKING),
                preset("Lagan", kind = EventTypeKind.MARKER),
                preset("Tilak", kind = EventTypeKind.MARKER, deletedAt = Instant.parse("2026-02-01T00:00:00Z")),
            )
        // Legacy key-recorded value matches the seeded row's label.
        assertThat(EventTypeKinds.kindFor(presets, "room_booking")).isEqualTo(EventTypeKind.BOOKING)
        assertThat(EventTypeKinds.isMarker(presets, "lagan")).isTrue()
        assertThat(EventTypeKinds.isMarker(presets, " LAGAN ")).isTrue()
        // Tombstoned preset no longer resolves; free text never resolves.
        assertThat(EventTypeKinds.kindFor(presets, "Tilak")).isNull()
        assertThat(EventTypeKinds.isMarker(presets, "College Fest")).isFalse()
    }
}
