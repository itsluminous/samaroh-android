package com.itsluminous.samaroh.core.sync.engine

import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.dao.SyncConflictDao
import com.itsluminous.samaroh.core.database.dao.SyncCursorDao
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import com.itsluminous.samaroh.core.sync.ConflictNotifier
import com.itsluminous.samaroh.core.sync.SyncMetaStore
import com.itsluminous.samaroh.core.sync.remote.RemoteRejectedException
import com.itsluminous.samaroh.core.sync.remote.RemoteStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import com.itsluminous.samaroh.core.sync.remote.RemoteUnavailableException
import com.itsluminous.samaroh.core.sync.wire.SyncTableSpec
import com.itsluminous.samaroh.core.sync.wire.SyncTables
import com.itsluminous.samaroh.core.sync.wire.WireConverter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Result of one sync run — consumed by [com.itsluminous.samaroh.core.sync.SyncWorker]. */
data class SyncOutcome(
    /** False when Supabase credentials are absent — the run is a successful no-op. */
    val configured: Boolean,
    val pushedCount: Int = 0,
    val itemErrorCount: Int = 0,
    val pulledCount: Int = 0,
    val conflictCount: Int = 0,
    /** True on a transport failure — the worker retries with exponential backoff. */
    val networkFailed: Boolean = false,
)

/**
 * The full sync pipeline (§8):
 * 1. Push the outbox FIFO — attachment uploads first (queue contract), then Postgrest
 *    upserts; tombstones propagate as `deleted_at` updates; RLS rejections mark the item
 *    `error` (retriable) without blocking other entities.
 * 2. Pull per-table incremental changes (`updated_at > cursor`, per business scope),
 *    apply to Room with LWW conflict resolution — a pulled row newer than a pending
 *    outbox op is REBASED (pending upsert) or the op is DROPPED (pending delete /
 *    remote tombstone), always with a persisted conflict-log entry, a local notification
 *    and the in-app banner state. Never silent.
 */
@Singleton
class SyncEngine
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
        private val businessDao: BusinessDao,
        private val cursorDao: SyncCursorDao,
        private val conflictDao: SyncConflictDao,
        private val applier: LocalApplier,
        private val remoteStoreProvider: RemoteStoreProvider,
        private val attachmentUploader: Optional<AttachmentUploader>,
        private val conflictNotifier: ConflictNotifier,
        private val syncMetaStore: SyncMetaStore,
        private val clock: Clock,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val runMutex = Mutex()

        suspend fun runSync(): SyncOutcome =
            runMutex.withLock {
                val remote = remoteStoreProvider.get() ?: return@withLock SyncOutcome(configured = false)
                var pushed = 0
                var itemErrors = 0
                var pulled = 0
                var conflicts = 0
                var networkFailed = false
                try {
                    val pushResult = push(remote)
                    pushed = pushResult.first
                    itemErrors = pushResult.second
                    val pullResult = pull(remote)
                    pulled = pullResult.first
                    conflicts = pullResult.second
                    syncMetaStore.recordSyncTime(clock.instant())
                } catch (_: RemoteUnavailableException) {
                    networkFailed = true
                }
                SyncOutcome(
                    configured = true,
                    pushedCount = pushed,
                    itemErrorCount = itemErrors,
                    pulledCount = pulled,
                    conflictCount = conflicts,
                    networkFailed = networkFailed,
                )
            }

        // ---------------------------------------------------------------- push

        private suspend fun push(remote: RemoteStore): Pair<Int, Int> {
            var pushed = 0
            var errors = 0
            // Entities whose head op failed this run: later ops for the same entity are left
            // queued untouched so per-entity FIFO order is preserved.
            val heldEntities = mutableSetOf<Pair<String, String>>()
            while (true) {
                val batch = outboxDao.nextBatch(PUSH_BATCH_SIZE)
                if (batch.isEmpty()) break
                var progressed = false
                for (entry in batch) {
                    val key = entry.entityType to entry.entityId
                    if (key in heldEntities) continue
                    try {
                        pushEntry(remote, entry)
                        outboxDao.remove(entry.id)
                        pushed++
                        progressed = true
                    } catch (e: RemoteRejectedException) {
                        outboxDao.recordFailure(entry.id, e.message ?: "rejected")
                        heldEntities += key
                        errors++
                    } catch (e: AttachmentPendingException) {
                        outboxDao.recordFailure(entry.id, e.message ?: "attachment-pending")
                        heldEntities += key
                        errors++
                    }
                }
                if (!progressed) break
            }
            return pushed to errors
        }

        private suspend fun pushEntry(
            remote: RemoteStore,
            entry: OutboxEntity,
        ) {
            var payloadJson = entry.payloadJson
            if (entry.entityType == ATTACHMENTS_TABLE && entry.operation == OutboxOperation.UPSERT.wire) {
                payloadJson = ensureAttachmentUploaded(entry, payloadJson)
            }
            val spec = SyncTables.byName(entry.entityType)
            when (OutboxOperation.fromWire(entry.operation)) {
                OutboxOperation.UPSERT -> remote.upsert(entry.entityType, WireConverter.toWire(entry.entityType, payloadJson))
                OutboxOperation.DELETE -> {
                    val payload = json.parseToJsonElement(payloadJson).jsonObject
                    val deletedAt =
                        payload["deleted_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                            ?: clock.instant().toString()
                    remote.updateTombstone(
                        entry.entityType,
                        spec?.idColumn ?: "id",
                        entry.entityId,
                        deletedAt,
                        touchUpdatedAt = spec?.hasUpdatedAt ?: true,
                    )
                }
            }
        }

        /** Attachment uploads go FIRST (§8 queue contract); the row op carries the Drive file id. */
        private suspend fun ensureAttachmentUploaded(
            entry: OutboxEntity,
            payloadJson: String,
        ): String {
            val payload = json.parseToJsonElement(payloadJson).jsonObject
            val driveFileId = payload["drive_file_id"]
            if (driveFileId != null && driveFileId !is JsonNull) return payloadJson
            val uploader =
                attachmentUploader.orElse(null)
                    ?: throw AttachmentPendingException(ERROR_STORAGE_NOT_LINKED)
            return when (val result = uploader.upload(entry.entityId)) {
                is AttachmentUploader.UploadResult.Uploaded -> {
                    val patched = JsonObject(payload + ("drive_file_id" to JsonPrimitive(result.driveFileId)))
                    val patchedJson = patched.toString()
                    outboxDao.rewritePayload(entry.id, patchedJson)
                    applier.apply(ATTACHMENTS_TABLE, patched)
                    patchedJson
                }
                AttachmentUploader.UploadResult.NotLinked -> throw AttachmentPendingException(ERROR_STORAGE_NOT_LINKED)
                is AttachmentUploader.UploadResult.Failed ->
                    if (result.retriable) {
                        throw AttachmentPendingException(result.message)
                    } else {
                        throw RemoteRejectedException(result.message)
                    }
            }
        }

        // ---------------------------------------------------------------- pull

        private suspend fun pull(remote: RemoteStore): Pair<Int, Int> {
            var applied = 0
            var conflicts = 0
            val (globalTables, scopedTables) = SyncTables.ALL.partition { !it.businessScoped }
            // Global tables first so a freshly discovered business is included in this run.
            for (spec in globalTables) {
                val result = pullTable(remote, spec, SyncCursorEntity.GLOBAL_SCOPE)
                applied += result.first
                conflicts += result.second
            }
            val businessIds = businessDao.allBusinesses().first().map { it.id }
            for (spec in scopedTables) {
                for (businessId in businessIds) {
                    val result = pullTable(remote, spec, businessId)
                    applied += result.first
                    conflicts += result.second
                }
            }
            return applied to conflicts
        }

        private suspend fun pullTable(
            remote: RemoteStore,
            spec: SyncTableSpec,
            scope: String,
        ): Pair<Int, Int> {
            var applied = 0
            var conflicts = 0
            var cursor = cursorDao.cursor(scope, spec.name) ?: Instant.EPOCH
            while (true) {
                val rows =
                    remote.pull(
                        table = spec.name,
                        businessId = scope.takeIf { spec.businessScoped },
                        after = cursor,
                        limit = PULL_PAGE_SIZE,
                        columns = spec.selectColumns,
                        cursorColumn = spec.cursorColumn,
                    )
                if (rows.isEmpty()) break
                var newest = cursor
                for (raw in rows) {
                    val row = WireConverter.toLocal(spec.name, raw)
                    val remoteUpdated = WireConverter.parseTimestamp(row.getValue(spec.cursorColumn).jsonPrimitive.content)
                    if (remoteUpdated > newest) newest = remoteUpdated
                    val outcome = applyWithLww(spec, row, remoteUpdated)
                    if (outcome.first) applied++
                    if (outcome.second) conflicts++
                }
                cursorDao.upsert(SyncCursorEntity(scope, spec.name, newest))
                if (rows.size < PULL_PAGE_SIZE || newest == cursor) break
                cursor = newest
            }
            return applied to conflicts
        }

        /** @return (rowApplied, conflictRecorded) */
        private suspend fun applyWithLww(
            spec: SyncTableSpec,
            row: JsonObject,
            remoteUpdated: Instant,
        ): Pair<Boolean, Boolean> {
            val id = row.getValue(spec.idColumn).jsonPrimitive.content
            val pending = outboxDao.pendingForEntity(spec.name, id)
            if (pending.isEmpty()) {
                applier.apply(spec.name, row)
                return true to false
            }
            val latest = pending.last()
            if (remoteUpdated <= opTimestamp(latest)) {
                // Local pending op is newer — local wins; the push will carry it up (LWW).
                return false to false
            }
            val title = applier.titleOf(row)
            val remoteIsTombstone = row["deleted_at"]?.takeIf { it !is JsonNull } != null
            val localPayload = json.parseToJsonElement(latest.payloadJson).jsonObject

            if (latest.operation == OutboxOperation.DELETE.wire || remoteIsTombstone) {
                // The pending op loses outright: DROP it, remote row (or tombstone) stands.
                pending.forEach { outboxDao.remove(it.id) }
                applier.apply(spec.name, row)
                val fields =
                    if (latest.operation == OutboxOperation.DELETE.wire) {
                        listOf("deleted_at")
                    } else {
                        changedFields(localPayload, row)
                    }
                recordConflict(spec.name, id, title, fields, ConflictResolution.DROPPED)
                return true to true
            }

            val contested = changedFields(localPayload, row)
            if (contested.isEmpty()) {
                // The remote row already contains everything the local edit wanted — no conflict.
                pending.forEach { outboxDao.remove(it.id) }
                applier.apply(spec.name, row)
                return true to false
            }

            // REBASE: re-apply the local field values on top of the newer remote row and
            // requeue a single consolidated op; the user is told their edit superseded a
            // change made elsewhere.
            val merged =
                JsonObject(
                    row + contested.associateWith { localPayload.getValue(it) } +
                        if (spec.hasUpdatedAt) {
                            mapOf("updated_at" to JsonPrimitive(clock.instant().toString()))
                        } else {
                            emptyMap()
                        },
                )
            pending.dropLast(1).forEach { outboxDao.remove(it.id) }
            outboxDao.rewritePayload(latest.id, merged.toString())
            applier.apply(spec.name, merged)
            recordConflict(spec.name, id, title, contested, ConflictResolution.REBASED)
            return true to true
        }

        /** Fields the local pending payload sets differently from the pulled row (audit fields excluded). */
        private fun changedFields(
            localPayload: JsonObject,
            remoteRow: JsonObject,
        ): List<String> =
            localPayload.keys.filter { key ->
                key !in AUDIT_FIELDS && (localPayload[key] ?: JsonNull) != (remoteRow[key] ?: JsonNull)
            }

        private fun opTimestamp(entry: OutboxEntity): Instant {
            val payload = runCatching { json.parseToJsonElement(entry.payloadJson).jsonObject }.getOrNull() ?: return Instant.EPOCH
            val raw =
                payload["updated_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: payload["deleted_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    // Immutable tables (expense_attachments) carry created_at only.
                    ?: payload["created_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: return Instant.EPOCH
            return runCatching { WireConverter.parseTimestamp(raw) }.getOrDefault(Instant.EPOCH)
        }

        private suspend fun recordConflict(
            entityType: String,
            entityId: String,
            title: String,
            fields: List<String>,
            resolution: ConflictResolution,
        ) {
            conflictDao.insert(
                SyncConflictEntity(
                    entityType = entityType,
                    entityId = entityId,
                    title = title,
                    overriddenFields = fields.joinToString(","),
                    resolution = resolution.wire,
                    occurredAt = clock.instant(),
                ),
            )
            conflictNotifier.notifyConflict(title, fields, resolution)
        }

        companion object {
            private const val PUSH_BATCH_SIZE = 50
            private const val PULL_PAGE_SIZE = 200
            private const val ATTACHMENTS_TABLE = "expense_attachments"

            /** Machine-readable error code; the Settings sync-status UI maps it to a localized string. */
            const val ERROR_STORAGE_NOT_LINKED = "attachment-pending-storage-link"

            private val AUDIT_FIELDS =
                setOf("id", "business_id", "created_at", "created_by", "updated_at", "updated_by", "deleted_at")
        }
    }

/** The attachment file cannot be uploaded yet (no Google link / transient failure); the op stays queued. */
class AttachmentPendingException(
    message: String,
) : Exception(message)
