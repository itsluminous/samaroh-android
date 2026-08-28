package com.itsluminous.samaroh.core.sync.engine

import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
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
import kotlinx.serialization.json.JsonElement
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
        private val itemImageMirror: ItemImageMirror,
        private val conflictNotifier: ConflictNotifier,
        private val syncMetaStore: SyncMetaStore,
        /** Feature-contributed reactions to applied pulls (ADR-024) — e.g. reminder re-planning. */
        private val postSyncHooks: Set<@JvmSuppressWildcards PostSyncHook>,
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
                    if (pulled > 0) {
                        // A hook failure must never fail the sync run (§8: per-item errors don't block).
                        postSyncHooks.forEach { hook -> runCatching { hook.onSyncApplied() } }
                    }
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
            if (entry.entityType == MASTER_ITEMS_TABLE && entry.operation == OutboxOperation.UPSERT.wire) {
                payloadJson = ensureItemImageMirrored(entry, payloadJson)
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

        /**
         * Local item photos are mirrored to Storage BEFORE the row op pushes (ADR-023) —
         * mirrors the attachment queue contract. A device-local `image_path` is patched
         * to the uploaded Storage object path (or null when the file vanished) in the
         * outbox payload AND Room, so a local file path never reaches the server.
         */
        private suspend fun ensureItemImageMirrored(
            entry: OutboxEntity,
            payloadJson: String,
        ): String {
            val payload = json.parseToJsonElement(payloadJson).jsonObject
            val imagePath =
                payload["image_path"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: return payloadJson
            if (!isLocalItemImagePath(imagePath)) return payloadJson
            val businessId =
                payload["business_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: return payloadJson
            val patchedPath: JsonElement =
                when (val result = itemImageMirror.mirror(businessId, entry.entityId, imagePath)) {
                    is ItemImageMirror.Result.Uploaded -> JsonPrimitive(result.storagePath)
                    // Dead local reference (file cleaned up): push the row without a photo
                    // rather than blocking every later edit of the item forever.
                    ItemImageMirror.Result.MissingFile -> JsonNull
                    is ItemImageMirror.Result.Retriable -> throw AttachmentPendingException(result.message)
                    is ItemImageMirror.Result.Rejected -> throw RemoteRejectedException(result.message)
                }
            val patched = JsonObject(payload + ("image_path" to patchedPath))
            val patchedJson = patched.toString()
            outboxDao.rewritePayload(entry.id, patchedJson)
            applier.apply(MASTER_ITEMS_TABLE, patched)
            return patchedJson
        }

        // ---------------------------------------------------------------- pull

        /**
         * Loop-until-stable pull (bounded): each pass pulls the global tables (cursor-
         * incremental, so repeats are cheap) then the business-scoped tables for every
         * business not yet covered THIS run. Businesses can land in Room mid-pass — the
         * global `businesses` pull itself, or `MembershipRefresher` writing DAOs directly
         * during sign-in — so a single enumeration would skip their bookings/payments/etc.
         * until the NEXT run (the "calendar empty right after login" bug). Re-enumerating
         * after each pass guarantees one sync run fetches everything, capped at
         * [MAX_PULL_PASSES] so a pathological stream of new businesses cannot spin forever.
         */
        private suspend fun pull(remote: RemoteStore): Pair<Int, Int> {
            var applied = 0
            var conflicts = 0
            val (globalTables, scopedTables) = SyncTables.ALL.partition { !it.businessScoped }
            val coveredBusinessIds = mutableSetOf<String>()
            for (pass in 1..MAX_PULL_PASSES) {
                for (spec in globalTables) {
                    val result = pullTableGuarded(remote, spec, SyncCursorEntity.GLOBAL_SCOPE)
                    applied += result.first
                    conflicts += result.second
                }
                val newBusinessIds =
                    businessDao
                        .allBusinesses()
                        .first()
                        .map { it.id }
                        .filter { it !in coveredBusinessIds }
                if (newBusinessIds.isEmpty()) break
                for (spec in scopedTables) {
                    for (businessId in newBusinessIds) {
                        val result = pullTableGuarded(remote, spec, businessId)
                        applied += result.first
                        conflicts += result.second
                    }
                }
                coveredBusinessIds += newBusinessIds
            }
            return applied to conflicts
        }

        /**
         * A REJECTED pull of ONE table (e.g. the server does not have `event_types` yet
         * because shared migration 006 is unapplied — PostgREST "relation does not
         * exist") must not abort the run: every other table still pulls, and the missing
         * one self-heals on the first run after the migration lands (ADR-032; same
         * philosophy as the ADR-027/030 per-item PGRST204 push holds). Transport
         * failures still propagate — the whole run retries with backoff.
         */
        private suspend fun pullTableGuarded(
            remote: RemoteStore,
            spec: SyncTableSpec,
            scope: String,
        ): Pair<Int, Int> =
            try {
                pullTable(remote, spec, scope)
            } catch (_: RemoteRejectedException) {
                0 to 0
            }

        private suspend fun pullTable(
            remote: RemoteStore,
            spec: SyncTableSpec,
            scope: String,
        ): Pair<Int, Int> {
            var applied = 0
            var conflicts = 0
            val stored = cursorDao.cursor(scope, spec.name)
            var cursorAt = stored?.lastPulledAt ?: Instant.EPOCH
            // Null id = legacy/fresh cursor: the pull then INCLUDES rows at cursorAt, so
            // ties dropped by the old timestamp-only cursor are recovered (ADR-024).
            var cursorId = stored?.lastPulledId
            while (true) {
                val rows =
                    remote.pull(
                        table = spec.name,
                        businessId = scope.takeIf { spec.businessScoped },
                        after = cursorAt,
                        afterId = cursorId,
                        limit = PULL_PAGE_SIZE,
                        columns = spec.selectColumns,
                        cursorColumn = spec.cursorColumn,
                        idColumn = spec.idColumn,
                    )
                if (rows.isEmpty()) break
                var lastAt = cursorAt
                var lastId = cursorId
                for (raw in rows) {
                    val row = WireConverter.toLocal(spec.name, raw)
                    val remoteUpdated = WireConverter.parseTimestamp(row.getValue(spec.cursorColumn).jsonPrimitive.content)
                    // Rows arrive ordered by (cursorColumn, id) — the last one is the new keyset position.
                    lastAt = remoteUpdated
                    lastId = row.getValue(spec.idColumn).jsonPrimitive.content
                    val outcome = applyWithLww(spec, row, remoteUpdated)
                    if (outcome.first) applied++
                    if (outcome.second) conflicts++
                }
                // Defensive: a page that fails to advance the position would loop forever.
                if (lastAt == cursorAt && lastId == cursorId) break
                cursorDao.upsert(SyncCursorEntity(scope, spec.name, lastAt, lastId))
                if (rows.size < PULL_PAGE_SIZE) break
                cursorAt = lastAt
                cursorId = lastId
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

            /** Re-enumeration bound: pass 1 covers known businesses, later passes catch mid-run arrivals. */
            private const val MAX_PULL_PASSES = 3
            private const val ATTACHMENTS_TABLE = "expense_attachments"
            private const val MASTER_ITEMS_TABLE = "master_items"

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
