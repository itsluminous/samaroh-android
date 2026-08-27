package com.itsluminous.samaroh.core.sync.wire

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.Booking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Booking colour on the sync wire (ADR-030): the outbox payload (full model with
 * `encodeDefaults = true`, same as the repositories) carries `color` verbatim through
 * `toWire`, and a pulled row's `color` decodes back into the model — including the
 * pre-005 case where the server row has NO `color` key at all (defaulted to null).
 */
class BookingColorWireRoundTripTest {
    private val json = Json { encodeDefaults = true }

    private fun booking(color: String?) =
        Booking(
            id = "bk-1",
            businessId = "b-1",
            eventType = "wedding",
            eventIcon = "\uD83D\uDC92",
            customerName = "Asha Devi",
            startDate = LocalDate.of(2026, 9, 10),
            endDate = LocalDate.of(2026, 9, 10),
            totalAmountPaise = 20_000_00L,
            color = color,
            createdBy = "u-1",
            createdAt = Instant.parse("2026-08-25T12:00:00Z"),
            updatedAt = Instant.parse("2026-08-25T12:00:00Z"),
        )

    @Test
    fun `color survives the outbox to wire to local round trip`() {
        val payload = json.encodeToString(Booking.serializer(), booking(color = "peacock"))

        val wire = WireConverter.toWire("bookings", payload)
        assertThat(wire.getValue("color").jsonPrimitive.content).isEqualTo("peacock")

        val local = WireConverter.toLocal("bookings", wire)
        val decoded = json.decodeFromJsonElement(Booking.serializer(), local)
        assertThat(decoded.color).isEqualTo("peacock")
        assertThat(decoded.totalAmountPaise).isEqualTo(20_000_00L)
    }

    @Test
    fun `null color is encoded (encodeDefaults) and round-trips as null`() {
        val payload = json.encodeToString(Booking.serializer(), booking(color = null))

        val wire = WireConverter.toWire("bookings", payload)
        assertThat(wire.getValue("color")).isEqualTo(JsonNull)

        val decoded = json.decodeFromJsonElement(Booking.serializer(), WireConverter.toLocal("bookings", wire))
        assertThat(decoded.color).isNull()
    }

    @Test
    fun `pre-005 server row without a color key decodes with null color`() {
        // Pulls select `*`; a server that has not run migration 005 returns no `color`.
        val payload = json.encodeToString(Booking.serializer(), booking(color = "sky"))
        val wire = WireConverter.toWire("bookings", payload)
        val withoutColor = kotlinx.serialization.json.JsonObject(wire.filterKeys { it != "color" })

        val decoded = json.decodeFromJsonElement(Booking.serializer(), WireConverter.toLocal("bookings", withoutColor))
        assertThat(decoded.color).isNull()
    }
}
