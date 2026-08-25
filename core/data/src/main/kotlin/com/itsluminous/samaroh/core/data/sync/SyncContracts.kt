package com.itsluminous.samaroh.core.data.sync

/*
 * Sync contract (spec §8, §11 critical-path note): these interfaces are defined in Wave 0
 * so every feature can enqueue mutations and request syncs without depending on the sync
 * engine implementation (which lands in `core:sync`). FROZEN CONTRACT.
 */

/** Outbox operation kinds. Tombstones propagate as [DELETE]; everything else is an [UPSERT]. */
enum class OutboxOperation(
    val wire: String,
) {
    UPSERT("upsert"),
    DELETE("delete"),
    ;

    companion object {
        fun fromWire(value: String): OutboxOperation = entries.first { it.wire == value }
    }
}

/**
 * Queues a local mutation for push (§8 outbox pattern). Repositories call this in the same
 * logical step as the Room write; the UI is never blocked by network.
 */
interface OutboxWriter {
    /**
     * @param entityType Postgres table name (e.g. `"bookings"`).
     * @param entityId client-generated UUID of the mutated row.
     * @param payloadJson snapshot of the row at mutation time. Note: money fields carry
     *   Long paise (ADR-002); the sync engine converts to decimal rupees at the wire.
     */
    suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: OutboxOperation,
        payloadJson: String,
    )
}

/** Requests sync work; implementation (WorkManager) lives in `core:sync`. */
interface SyncScheduler {
    /** Expedited one-shot sync — called after user-visible mutations and on app foreground (§8). */
    fun requestImmediateSync()

    /** Ensures the periodic (~15 min, connectivity-constrained) sync is scheduled. */
    fun ensurePeriodicSync()
}
