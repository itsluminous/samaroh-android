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

    /**
     * Debounced one-shot sync for local outbox writes (ADR-036, additive contract
     * extension): [OutboxWriter.enqueue] calls this on EVERY queued mutation, so online
     * edits push within seconds instead of waiting for the next foreground/periodic
     * trigger. The implementation collapses bursts of edits into ONE run a few seconds
     * after the last write; offline the request simply waits on the CONNECTED
     * constraint. Default delegates to [requestImmediateSync] so simple/fake
     * implementations stay valid.
     */
    fun requestSyncOnLocalChange() = requestImmediateSync()
}

/**
 * Observes every queued local mutation (ADR-046, additive contract extension).
 * [OutboxWriter] implementations notify each listener right after the outbox row is
 * written — the same moment the debounced data sync is nudged (ADR-036) — so other
 * modules can react to specific entity mutations promptly (e.g. `core:google` enqueues
 * the Google Calendar push when a booking changes) without polling or a 6-hour periodic
 * wait. Listener failures are logged and never fail the enqueuing write.
 */
fun interface LocalMutationListener {
    suspend fun onLocalMutation(
        entityType: String,
        entityId: String,
        operation: OutboxOperation,
        payloadJson: String,
    )
}

/**
 * Reacts to a sync run that APPLIED pulled rows to Room (ADR-024). Feature modules
 * contribute implementations via Hilt `@IntoSet`; the engine invokes each one after a
 * successful pull so pulled data becomes actionable immediately — e.g. `feature:booking`
 * re-plans reminder notifications/alarms and dismisses reminders whose booking arrived
 * settled, instead of waiting for the next daily 09:00 pass. Hook failures are logged
 * and never fail the sync run.
 */
interface PostSyncHook {
    /** Called after a sync run whose pull applied at least one row. */
    suspend fun onSyncApplied()
}

/**
 * Observes WHICH tables a sync pull changed (ADR-047, additive contract extension).
 * The sync engine invokes each multibound listener after a run whose pull applied at
 * least one row, with the applied business-scoped tables and the business ids whose
 * rows changed (`table name → business ids`). This is the remote twin of
 * [LocalMutationListener]: a booking edited by ANOTHER member arrives via the pull and
 * never touches the outbox, so without this signal `core:google` could not push it to
 * the calendar until the 6-hour periodic. Listener failures are logged and never fail
 * the sync run. Global (non-business-scoped) tables are not reported, with ONE
 * exception: applied `businesses` rows are reported with the row id as the business id
 * (ADR-048 — a remote business rename must re-title that business's calendar).
 */
fun interface RemoteChangeListener {
    suspend fun onRemoteChangesApplied(appliedTables: Map<String, Set<String>>)
}

/**
 * Answers "is the local replica a CONSISTENT snapshot of the server right now?"
 * (ADR-060, additive contract extension). Destructive background planning — most
 * importantly the payment-reminder engine, which CREATES and DISMISSES rows from
 * `due = total − Σpayments` — must not run against a replica where bookings have
 * arrived but their payments have not (a partial or in-flight pull): every settled
 * past booking then looks unpaid and gets a bogus reminder, which also syncs out.
 *
 * The `core:sync` implementation returns false while a sync run is executing, and
 * after any pull that was aborted (transport failure) or skipped a rejected table,
 * until a later pull completes cleanly. It returns true when Supabase is not
 * configured or nobody is signed in — purely local data is its own single source of
 * truth. Interactive, user-initiated actions should NOT consult this; it exists for
 * unattended engines.
 */
fun interface ReplicaIntegrity {
    suspend fun isReplicaConsistent(): Boolean
}
