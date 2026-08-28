package com.itsluminous.samaroh.core.sync

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes queued mutations as Room `outbox` rows (§8). FIFO order is the autoincrement
 * row id; the sync engine (W1-E) drains the queue oldest-first.
 *
 * Every enqueue also nudges the debounced on-change sync (ADR-036), so ALL features get
 * push-within-seconds for free — no per-ViewModel wiring. Loop-safe by construction: the
 * sync engine applies pulled rows via the DAOs directly ([engine.LocalApplier]) and
 * drains/rewrites the queue via [OutboxDao], never through this writer, so a sync run
 * can never re-trigger itself.
 */
@Singleton
class RoomOutboxWriter
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) : OutboxWriter {
        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            outboxDao.enqueue(
                OutboxEntity(
                    entityType = entityType,
                    entityId = entityId,
                    operation = operation.wire,
                    payloadJson = payloadJson,
                    createdAt = clock.instant(),
                ),
            )
            syncScheduler.requestSyncOnLocalChange()
        }
    }
