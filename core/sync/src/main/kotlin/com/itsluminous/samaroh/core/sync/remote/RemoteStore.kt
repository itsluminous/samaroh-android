package com.itsluminous.samaroh.core.sync.remote

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Thin abstraction over the Postgrest wire (§8) so the sync engine is unit-testable
 * without a network. All rows are wire-format JSON (decimal rupees — see `WireConverter`).
 */
interface RemoteStore {
    /** Upserts one row (insert or update on primary-key conflict). */
    suspend fun upsert(
        table: String,
        row: JsonObject,
    )

    /**
     * Tombstone propagation (§8): sets `deleted_at` (and `updated_at` when
     * [touchUpdatedAt] — immutable tables like `expense_attachments` have no such column)
     * on the remote row. A no-op when the row never reached the server (0 rows match).
     */
    suspend fun updateTombstone(
        table: String,
        idColumn: String,
        id: String,
        deletedAt: String,
        touchUpdatedAt: Boolean = true,
    )

    /**
     * Incremental pull page: rows with `cursorColumn > after`, oldest first, at most [limit],
     * optionally scoped to one business and to an explicit column projection (ADR-003).
     */
    suspend fun pull(
        table: String,
        businessId: String?,
        after: Instant,
        limit: Int,
        columns: String? = null,
        cursorColumn: String = "updated_at",
    ): List<JsonObject>
}

/** Provides the configured [RemoteStore], or null while Supabase credentials are absent. */
fun interface RemoteStoreProvider {
    fun get(): RemoteStore?
}

/**
 * The server rejected the operation (RLS/permission/constraint). The item is marked
 * `error` and stays retriable after the underlying cause is fixed (§8 failures).
 */
class RemoteRejectedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Transport-level failure (offline, DNS, timeout) — the whole run retries with exponential backoff (§8). */
class RemoteUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
