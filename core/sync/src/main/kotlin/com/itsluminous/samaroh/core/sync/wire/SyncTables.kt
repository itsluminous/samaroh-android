package com.itsluminous.samaroh.core.sync.wire

/**
 * One synced Postgres table (§2 canonical schema, §8 sync pipeline).
 *
 * @param name Postgres table name (matches outbox `entity_type` and the Room table name).
 * @param businessScoped whether rows carry a `business_id` column — scoped tables pull
 *   per business with a per-business cursor; the rest pull globally (RLS still scopes rows).
 * @param moneyFields money columns as `local payload key -> wire column name`. Local
 *   payloads carry Long paise (ADR-002); the wire carries decimal rupees. Keys differ from
 *   the wire name where the Wave-0 model field lacks a `@SerialName` (e.g. `amountPaise`).
 * @param enumFields Postgres-enum columns: local payloads carry the kotlinx serial name
 *   (the UPPERCASE enum entry, e.g. `"CONFIRMED"`); the wire carries the lowercase
 *   Postgres enum value (`"confirmed"`). Casing is the only difference by contract.
 * @param idColumn primary-key column used for LWW matching and tombstone updates.
 * @param selectColumns explicit pull projection; used to exclude server-only secrets
 *   (ADR-003 `google_accounts.refresh_token_cipher`).
 * @param cursorColumn timestamp column driving the incremental pull (`> cursor`, ascending).
 *   `updated_at` everywhere except immutable tables: `expense_attachments` has no
 *   `updated_at` by design (created once, tombstoned via `deleted_at` — see 001_schema.sql),
 *   so it pulls by `created_at`.
 * @param localOnlyKeys payload keys that must NEVER reach the wire — device-only state
 *   whose server column does not exist (ADR-065: `master_items.image_path` was dropped
 *   server-side). Stripped by [WireConverter.toWire] as a safety net for outbox payloads
 *   written by OLDER app versions whose serializers still emitted the key (pushing an
 *   unknown column fails the whole row op with PGRST204).
 */
data class SyncTableSpec(
    val name: String,
    val businessScoped: Boolean,
    val moneyFields: Map<String, String> = emptyMap(),
    val enumFields: Set<String> = emptySet(),
    val idColumn: String = "id",
    val selectColumns: String? = null,
    val cursorColumn: String = "updated_at",
    val localOnlyKeys: Set<String> = emptySet(),
) {
    /** Whether the server table carries `updated_at` (LWW bump + tombstone touch are valid). */
    val hasUpdatedAt: Boolean get() = cursorColumn == "updated_at"
}

/** Registry of every synced table, in pull order (parents before children is NOT required — ADR-004). */
object SyncTables {
    val ALL: List<SyncTableSpec> =
        listOf(
            SyncTableSpec("businesses", businessScoped = false),
            SyncTableSpec(
                "google_accounts",
                businessScoped = false,
                idColumn = "user_id",
                selectColumns = "user_id,email,scopes,drive_root_folder_id,calendar_id,updated_at",
            ),
            SyncTableSpec("business_members", businessScoped = true, enumFields = setOf("status")),
            SyncTableSpec("business_settings", businessScoped = true, idColumn = "business_id"),
            // `kind` is a CHECK-constrained text column, not a Postgres enum, but the
            // same casing contract applies: local "BOOKING"/"MARKER" ⇄ wire lowercase.
            SyncTableSpec("event_types", businessScoped = true, enumFields = setOf("kind")),
            SyncTableSpec(
                "bookings",
                businessScoped = true,
                moneyFields = mapOf("total_amount" to "total_amount", "security_deposit" to "security_deposit"),
                enumFields = setOf("status", "source"),
            ),
            SyncTableSpec("date_blocks", businessScoped = true),
            SyncTableSpec(
                "booking_payments",
                businessScoped = true,
                moneyFields = mapOf("amountPaise" to "amount"),
                enumFields = setOf("method"),
            ),
            SyncTableSpec(
                "payment_reminders",
                businessScoped = true,
                moneyFields = mapOf("amount_due_snapshot" to "amount_due_snapshot"),
                enumFields = setOf("status"),
            ),
            SyncTableSpec("parties", businessScoped = true),
            SyncTableSpec(
                "expenses",
                businessScoped = true,
                moneyFields = mapOf("amountPaise" to "amount"),
                enumFields = setOf("direction"),
            ),
            SyncTableSpec("expense_attachments", businessScoped = true, cursorColumn = "created_at"),
            // image_path is device-only since ADR-065 (the server column is dropped;
            // other devices serve the photo from drive_image_id) — strip it from any
            // legacy outbox payload so old rows still push cleanly.
            SyncTableSpec("master_items", businessScoped = true, localOnlyKeys = setOf("image_path")),
            SyncTableSpec(
                "inventory_transactions",
                businessScoped = true,
                moneyFields = mapOf("unit_price" to "unit_price"),
                enumFields = setOf("transaction_type"),
            ),
        )

    private val byName = ALL.associateBy { it.name }

    fun byName(name: String): SyncTableSpec? = byName[name]
}
