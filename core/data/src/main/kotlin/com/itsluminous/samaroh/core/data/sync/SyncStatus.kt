package com.itsluminous.samaroh.core.data.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * Sync-status contract (spec §4.4 "Sync status" screen; §4.5 pending-sync detail screen).
 * ADDITIVE addition to the Wave 0 sync contracts, introduced by W1-F so the Settings sync
 * screen can render pending/error state without depending on the sync engine
 * implementation. The real implementation is a W1-E (`core:sync`) deliverable;
 * `feature:menu` ships an outbox-backed fallback the integrator replaces
 * (docs/decisions.md ADR-007).
 */

/** One outbox item that the backend rejected or that repeatedly failed to push. */
data class SyncItemError(
    /** Postgres table name of the failed mutation (e.g. `"bookings"`). */
    val entityType: String,
    /** Client UUID of the mutated row. */
    val entityId: String,
    /** Operation wire value — `"upsert"` or `"delete"`. */
    val operation: String,
    /** Last error message recorded for the item (localizing/raw is up to the engine). */
    val message: String,
    val attemptCount: Int,
)

/** Snapshot of the sync engine's health for the Settings sync-status screen. */
data class SyncStatus(
    /** Number of local mutations still waiting in the outbox. */
    val pendingCount: Int,
    /** Items whose push failed at least once (RLS rejections, network errors…). */
    val errors: List<SyncItemError>,
    /** Completion time of the last successful sync pass, or null if none/unknown. */
    val lastSyncAt: Instant?,
)

/** Read-side companion of [SyncScheduler]: exposes engine health to the UI. */
interface SyncStatusProvider {
    val status: Flow<SyncStatus>
}
