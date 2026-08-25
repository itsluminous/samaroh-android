package com.itsluminous.samaroh.core.sync

import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncConflictEntry
import com.itsluminous.samaroh.core.data.sync.SyncItemError
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.dao.SyncConflictDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        private val syncMetaStore: SyncMetaStore,
        private val syncScheduler: SyncScheduler,
    ) : SyncStatus {
        override val pendingCount: Flow<Int> = outboxDao.pendingCount()

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
    }
