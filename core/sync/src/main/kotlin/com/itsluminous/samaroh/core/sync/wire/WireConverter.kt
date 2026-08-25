package com.itsluminous.samaroh.core.sync.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The single paise ⇄ decimal-rupee wire boundary (ADR-002): outbox payloads and Room carry
 * `Long` paise; Postgres `numeric(12,2)` carries decimal rupees. Also normalizes Postgres
 * `timestamptz` offsets (`…+00:00`) to the ISO instant form the local serializers expect.
 */
object WireConverter {
    private val timestampKeys = setOf("created_at", "updated_at", "deleted_at", "transaction_date", "last_backup_at")

    /** Local outbox payload JSON (paise) → wire row (decimal rupees, wire column names). */
    fun toWire(
        table: String,
        payloadJson: String,
    ): JsonObject {
        val spec = SyncTables.byName(table)
        val out = Json.parseToJsonElement(payloadJson).jsonObject.toMutableMap()
        spec?.moneyFields?.forEach { (localKey, wireKey) ->
            val value = out.remove(localKey) ?: return@forEach
            out[wireKey] =
                if (value is JsonNull) JsonNull else JsonPrimitive(paiseToRupees(value.jsonPrimitive.content.toLong()))
        }
        spec?.enumFields?.forEach { key ->
            val value = out[key] ?: return@forEach
            if (value is JsonNull) return@forEach
            out[key] = JsonPrimitive(value.jsonPrimitive.content.lowercase())
        }
        return JsonObject(out)
    }

    /** Wire row (decimal rupees) → local row JSON (paise, local payload keys, normalized timestamps). */
    fun toLocal(
        table: String,
        remoteRow: JsonObject,
    ): JsonObject {
        val spec = SyncTables.byName(table)
        val out = remoteRow.toMutableMap()
        spec?.moneyFields?.forEach { (localKey, wireKey) ->
            val value = out.remove(wireKey) ?: return@forEach
            out[localKey] = if (value is JsonNull) JsonNull else JsonPrimitive(rupeesToPaise(value.jsonPrimitive.content))
        }
        spec?.enumFields?.forEach { key ->
            val value = out[key] ?: return@forEach
            if (value is JsonNull) return@forEach
            out[key] = JsonPrimitive(value.jsonPrimitive.content.uppercase())
        }
        for (key in timestampKeys) {
            val value = out[key] ?: continue
            if (value is JsonNull) continue
            out[key] = JsonPrimitive(parseTimestamp(value.jsonPrimitive.content).toString())
        }
        return JsonObject(out)
    }

    /** `10651161` paise → `106511.61` (always two-decimal plain notation, never scientific). */
    fun paiseToRupees(paise: Long): BigDecimal = BigDecimal.valueOf(paise).movePointLeft(2)

    /** `"106511.61"`, `"500"`, `"500.5"` → paise. Postgres `numeric(12,2)` guarantees ≤ 2 decimals. */
    fun rupeesToPaise(decimal: String): Long = BigDecimal(decimal).movePointRight(2).longValueExact()

    /** Accepts both `2026-08-25T12:00:00Z` and Postgres `2026-08-25T12:00:00.123+00:00`. */
    fun parseTimestamp(raw: String): Instant =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(raw).toInstant()
        }
}
