package com.itsluminous.samaroh.core.sync

import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncConflictEntry
import com.itsluminous.samaroh.core.data.sync.SyncItemError
import com.itsluminous.samaroh.core.data.sync.SyncPendingItem
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.dao.SyncConflictDao
import com.itsluminous.samaroh.core.database.dao.SyncCursorDao
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import com.itsluminous.samaroh.core.sync.wire.SyncTables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room/DataStore-backed [SyncStatus] (§4.4 Sync status screen, §4.5 cloud icon).
 * Consumed later by the Menu tab and the app-bar cloud indicator.
 */
@Singleton
class RoomSyncStatus
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
        private val conflictDao: SyncConflictDao,
        private val cursorDao: SyncCursorDao,
        private val syncMetaStore: SyncMetaStore,
        private val syncScheduler: SyncScheduler,
        syncRunState: SyncRunState,
    ) : SyncStatus {
        private val json = Json { ignoreUnknownKeys = true }
        override val pendingCount: Flow<Int> = outboxDao.pendingCount()

        override val isSyncing: Flow<Boolean> = syncRunState.isRunning

        override val pendingItems: Flow<List<SyncPendingItem>> =
            outboxDao.pendingEntries().map { entries ->
                entries.map { entry ->
                    SyncPendingItem(
                        outboxId = entry.id,
                        entityType = entry.entityType,
                        entityId = entry.entityId,
                        operation = OutboxOperation.fromWire(entry.operation),
                        payloadJson = entry.payloadJson,
                        queuedAt = entry.createdAt,
                    )
                }
            }

        override val itemErrors: Flow<List<SyncItemError>> =
            outboxDao.erroredEntries().map { entries ->
                entries.map { entry ->
                    SyncItemError(
                        outboxId = entry.id,
                        entityType = entry.entityType,
                        entityId = entry.entityId,
                        operation = OutboxOperation.fromWire(entry.operation),
                        message = SyncErrorSanitizer.sanitize(entry.lastError.orEmpty()),
                        attemptCount = entry.attemptCount,
                        payloadJson = entry.payloadJson,
                    )
                }
            }

        override val conflictLog: Flow<List<SyncConflictEntry>> =
            conflictDao.conflictLog().map { conflicts ->
                conflicts.map { conflict ->
                    SyncConflictEntry(
                        id = conflict.id,
                        entityType = conflict.entityType,
                        entityId = conflict.entityId,
                        title = conflict.title,
                        overriddenFields = conflict.overriddenFields.split(',').filter { it.isNotBlank() },
                        resolution = ConflictResolution.fromWire(conflict.resolution),
                        occurredAt = conflict.occurredAt,
                        acknowledged = conflict.acknowledged,
                    )
                }
            }

        override val lastSyncTime: Flow<Instant?> = syncMetaStore.lastSyncTime

        override val hasUnacknowledgedConflicts: Flow<Boolean> = conflictDao.unacknowledgedCount().map { it > 0 }

        override fun syncNow() {
            syncScheduler.requestImmediateSync()
        }

        override suspend fun acknowledgeConflict(id: Long) {
            conflictDao.acknowledge(id)
        }

        /**
         * ADR-080: remove one queued change AND drop its table's pull cursor so the
         * next sync re-pulls the server's row over the diverged local state (the pull
         * cursor has already passed the row, so without the reset the local divergence
         * would persist until the row changes remotely). Re-applies are no-ops for
         * identical rows (ADR-051), so the EPOCH re-pull is safe, just not free.
         */
        override suspend fun discardItem(outboxId: Long) {
            val entry = outboxDao.entryById(outboxId) ?: return
            outboxDao.remove(outboxId)
            val spec = SyncTables.ALL.find { it.name == entry.entityType }
            if (spec != null) {
                val scope =
                    if (spec.businessScoped) {
                        runCatching {
                            json
                                .parseToJsonElement(entry.payloadJson)
                                .jsonObject["business_id"]
                                ?.takeIf { it !is JsonNull }
                                ?.jsonPrimitive
                                ?.content
                        }.getOrNull()
                    } else {
                        SyncCursorEntity.GLOBAL_SCOPE
                    }
                if (scope != null) cursorDao.delete(scope, spec.name)
            }
            syncScheduler.requestImmediateSync()
        }
    }
