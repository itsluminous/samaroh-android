package com.itsluminous.samaroh.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant

/**
 * `parties.booking_id` (ADR-106, shared migration 014) crosses the wire and the backup
 * archive as the model's `booking_id`; rows from pre-014 servers / older archives have no
 * such key and decode as unlinked.
 */
class PartyBookingLinkSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-13T00:30:00Z")

    private val party =
        Party(
            id = "p-1",
            businessId = "b-1",
            name = "Wedding - 20-Oct-2026 - Sandhya Upadhyay",
            createdAt = now,
            updatedAt = now,
            bookingId = "bk-1",
        )

    @Test
    fun `booking_id is encoded on the wire and decodes back`() {
        val encoded = json.encodeToString(Party.serializer(), party)
        assertThat(encoded).contains("\"booking_id\":\"bk-1\"")
        assertThat(json.decodeFromString(Party.serializer(), encoded)).isEqualTo(party)
    }

    @Test
    fun `a row without booking_id (pre-014 server, older backup) decodes as unlinked`() {
        val legacy =
            """{"id":"p-1","business_id":"b-1","name":"Ram Sweets","created_at":"2026-10-13T00:30:00Z",""" +
                """"updated_at":"2026-10-13T00:30:00Z","business_related":true}"""
        val decoded = json.decodeFromString(Party.serializer(), legacy)
        assertThat(decoded.bookingId).isNull()
        assertThat(decoded.businessRelated).isTrue()
    }
}
