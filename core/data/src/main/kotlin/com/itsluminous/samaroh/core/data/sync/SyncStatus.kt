package com.itsluminous.samaroh.core.data.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * Sync-status contract (spec §4.4 "Sync status", §4.5 cloud icon, §8) — ADDITIVE W1-E
 * addition to the Wave-0 sync contracts (docs/decisions.md ADR-008). Implemented in
 * `core:sync`; consumed by the Menu tab (Settings → Sync status) and the app-bar cloud
 * status icon.
 */

/** How a sync conflict was resolved (§8) — never silently. */
enum class ConflictResolution(
    val wire: String,
) {
    /** The local pending edit was re-applied on top of the newer remote row and re-queued. */
    REBASED("rebased"),

    /** The local pending op lost to the newer remote change and was discarded. */
    DROPPED("dropped"),
    ;

    companion object {
        fun fromWire(value: String): ConflictResolution = entries.first { it.wire == value }
    }
}

/** One outbox item that failed to push (for example an RLS rejection); retriable. */
data class SyncItemError(
    val outboxId: Long,
    val entityType: String,
    val entityId: String,
    val operation: OutboxOperation,
    val message: String,
    val attemptCount: Int,
)

/** One persisted conflict-log entry (§8: conflicts are user-visible, never silent). */
data class SyncConflictEntry(
    val id: Long,
    val entityType: String,
    val entityId: String,
    /** Human-readable row identifier (customer/party/item name) for display. */
    val title: String,
    /** Column names the local pending edit differed on. */
    val overriddenFields: List<String>,
    val resolution: ConflictResolution,
    val occurredAt: Instant,
    val acknowledged: Boolean,
)

/**
 * Read model + actions for the sync engine state. All flows are Room/DataStore backed and
 * safe to collect from the UI.
 */
interface SyncStatus {
    /** Number of local operations queued and waiting to push (the ☁️⚠️ badge count). */
    val pendingCount: Flow<Int>

    /** Per-item push errors (RLS rejections etc.), surfaced in Settings → Sync status. */
    val itemErrors: Flow<List<SyncItemError>>

    /** Full conflict log, newest first (Settings → Sync status). */
    val conflictLog: Flow<List<SyncConflictEntry>>

    /** Completion time of the last successful sync run, or null if never synced. */
    val lastSyncTime: Flow<Instant?>

    /** In-app conflict banner state: true while unacknowledged conflicts exist. */
    val hasUnacknowledgedConflicts: Flow<Boolean>

    /** Requests an immediate (expedited) sync — the Settings "Sync now" button. */
    fun syncNow()

    /** Marks a conflict as seen; clears the banner once all entries are acknowledged. */
    suspend fun acknowledgeConflict(id: Long)
}
