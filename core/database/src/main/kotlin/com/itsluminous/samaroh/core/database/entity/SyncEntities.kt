package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/*
 * Local-only sync bookkeeping tables (§8) — never synced to Postgres. Added by W1-E as an
 * ADDITIVE change to the frozen Wave-0 database contract (docs/decisions.md ADR-007).
 */

/**
 * Incremental pull cursor: the KEYSET position `(last_pulled_at, last_pulled_id)` of the
 * newest row already applied for one table within one business scope. The pull pipeline
 * fetches rows strictly after that position in `(cursor_column, id)` order (§8 step 2,
 * ADR-024) — a timestamp alone loses rows when many share one `updated_at` (bulk imports
 * stamp every row with the transaction time). Business-agnostic tables (for example
 * `businesses` itself) use the [GLOBAL_SCOPE] sentinel.
 */
@Entity(
    tableName = "sync_cursors",
    primaryKeys = ["business_id", "table_name"],
)
data class SyncCursorEntity(
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "last_pulled_at") val lastPulledAt: Instant,
    /**
     * Id of the last pulled row at [lastPulledAt] — the keyset tie-breaker (ADR-024).
     * Null on pre-ADR-024 cursors: the next pull then re-fetches every row AT the stored
     * timestamp (idempotent applies), which self-heals installs that lost tied rows.
     */
    @ColumnInfo(name = "last_pulled_id") val lastPulledId: String? = null,
) {
    companion object {
        /** Scope key for tables pulled without a per-business filter. */
        const val GLOBAL_SCOPE = "*"
    }
}

/**
 * Persisted conflict log (§8): every time a pulled row wins over a pending outbox op the
 * resolution is recorded here — surfaced as a notification, the in-app banner state and
 * the Settings → Sync status conflict list. NEVER silently discarded.
 */
@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** Human-readable identifier of the row (customer/party/item name) for the notification. */
    val title: String,
    /** Comma-joined column names the local pending edit differed on. */
    @ColumnInfo(name = "overridden_fields") val overriddenFields: String,
    /** "rebased" or "dropped" — see core:data `ConflictResolution`. */
    val resolution: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    /** Set once the user has seen the conflict (clears the in-app banner). */
    val acknowledged: Boolean = false,
)
