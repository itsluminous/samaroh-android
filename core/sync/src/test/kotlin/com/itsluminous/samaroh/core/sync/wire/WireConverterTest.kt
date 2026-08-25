package com.itsluminous.samaroh.core.sync.wire

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class WireConverterTest {
    @Test
    fun `bookings paise become decimal rupees on the wire`() {
        val wire =
            WireConverter.toWire(
                "bookings",
                """{"id":"b-1","total_amount":10651161,"security_deposit":50000,"customer_name":"c"}""",
            )

        assertThat(wire.getValue("total_amount").jsonPrimitive.content).isEqualTo("106511.61")
        assertThat(wire.getValue("security_deposit").jsonPrimitive.content).isEqualTo("500.00")
        assertThat(wire.getValue("customer_name").jsonPrimitive.content).isEqualTo("c")
    }

    @Test
    fun `booking_payments amountPaise is renamed to amount and converted`() {
        val wire = WireConverter.toWire("booking_payments", """{"id":"p-1","amountPaise":50000}""")

        assertThat(wire.containsKey("amountPaise")).isFalse()
        assertThat(wire.getValue("amount").jsonPrimitive.content).isEqualTo("500.00")
    }

    @Test
    fun `expenses amountPaise is renamed to amount and converted`() {
        val wire = WireConverter.toWire("expenses", """{"id":"e-1","amountPaise":123456}""")

        assertThat(wire.getValue("amount").jsonPrimitive.content).isEqualTo("1234.56")
    }

    @Test
    fun `inventory unit_price and reminder snapshot convert in place`() {
        val txn = WireConverter.toWire("inventory_transactions", """{"id":"t-1","unit_price":10000}""")
        val reminder = WireConverter.toWire("payment_reminders", """{"id":"r-1","amount_due_snapshot":250050}""")

        assertThat(txn.getValue("unit_price").jsonPrimitive.content).isEqualTo("100.00")
        assertThat(reminder.getValue("amount_due_snapshot").jsonPrimitive.content).isEqualTo("2500.50")
    }

    @Test
    fun `tables without money fields pass through unchanged`() {
        val wire = WireConverter.toWire("parties", """{"id":"p-1","name":"n"}""")

        assertThat(wire.getValue("name").jsonPrimitive.content).isEqualTo("n")
    }

    @Test
    fun `pulled decimal rupees become paise under the local key`() {
        val local =
            WireConverter.toLocal(
                "booking_payments",
                buildJsonObject {
                    put("id", "p-1")
                    put("amount", 500.5)
                },
            )

        assertThat(local.containsKey("amount")).isFalse()
        assertThat(local.getValue("amountPaise").jsonPrimitive.content).isEqualTo("50050")
    }

    @Test
    fun `pulled booking money converts and integer rupees are handled`() {
        val local =
            WireConverter.toLocal(
                "bookings",
                buildJsonObject {
                    put("id", "b-1")
                    put("total_amount", "200000")
                    put("security_deposit", "106511.61")
                },
            )

        assertThat(local.getValue("total_amount").jsonPrimitive.content).isEqualTo("20000000")
        assertThat(local.getValue("security_deposit").jsonPrimitive.content).isEqualTo("10651161")
    }

    @Test
    fun `postgres timestamptz offsets are normalized to instants`() {
        val local =
            WireConverter.toLocal(
                "parties",
                buildJsonObject {
                    put("id", "p-1")
                    put("created_at", "2026-08-25T10:15:30.123456+00:00")
                    put("updated_at", "2026-08-25T15:45:30+05:30")
                    put("deleted_at", JsonNull)
                },
            )

        assertThat(local.getValue("created_at").jsonPrimitive.content).isEqualTo("2026-08-25T10:15:30.123456Z")
        assertThat(local.getValue("updated_at").jsonPrimitive.content).isEqualTo("2026-08-25T10:15:30Z")
        assertThat(local.getValue("deleted_at")).isEqualTo(JsonNull)
    }

    @Test
    fun `paise to rupees never uses scientific notation`() {
        assertThat(WireConverter.paiseToRupees(0L)).isEqualTo(BigDecimal("0.00"))
        assertThat(WireConverter.paiseToRupees(10_000L).toPlainString()).isEqualTo("100.00")
        assertThat(WireConverter.paiseToRupees(999_999_999_999L).toPlainString()).isEqualTo("9999999999.99")
    }

    @Test
    fun `rupees to paise round trips`() {
        assertThat(WireConverter.rupeesToPaise("500")).isEqualTo(50_000L)
        assertThat(WireConverter.rupeesToPaise("500.5")).isEqualTo(50_050L)
        assertThat(WireConverter.rupeesToPaise("106511.61")).isEqualTo(10_651_161L)
        assertThat(WireConverter.rupeesToPaise(WireConverter.paiseToRupees(12_345L).toPlainString())).isEqualTo(12_345L)
    }

    @Test
    fun `enum fields are lowercased to postgres wire values and back`() {
        val wire =
            WireConverter.toWire(
                "bookings",
                """{"id":"b-1","status":"CONFIRMED","source":"WALK_IN","total_amount":0,"security_deposit":0}""",
            )
        assertThat(wire.getValue("status").jsonPrimitive.content).isEqualTo("confirmed")
        assertThat(wire.getValue("source").jsonPrimitive.content).isEqualTo("walk_in")

        val local =
            WireConverter.toLocal(
                "booking_payments",
                buildJsonObject {
                    put("id", "p-1")
                    put("method", "bank_transfer")
                    put("amount", "10")
                },
            )
        assertThat(local.getValue("method").jsonPrimitive.content).isEqualTo("BANK_TRANSFER")
    }

    @Test
    fun `timestamp parser accepts both instant and offset forms`() {
        assertThat(WireConverter.parseTimestamp("2026-08-25T09:00:00Z")).isEqualTo(Instant.parse("2026-08-25T09:00:00Z"))
        assertThat(WireConverter.parseTimestamp("2026-08-25T09:00:00+00:00")).isEqualTo(Instant.parse("2026-08-25T09:00:00Z"))
    }
}
