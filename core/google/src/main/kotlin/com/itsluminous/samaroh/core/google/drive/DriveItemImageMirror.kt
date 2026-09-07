package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import android.util.Log
import com.itsluminous.samaroh.core.data.image.ItemPhotoStorageDownloader
import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import com.itsluminous.samaroh.core.data.image.localItemImageFile
import com.itsluminous.samaroh.core.data.sync.ItemPhotoDriveMirror
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.model.MasterItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ItemPhotoDriveMirror] over the shared [DriveUploader] (ADR-055 + ADR-058): uploads each
 * pending item photo to `Samaroh/{Business}/images/inventory/` (§9.1) as
 * `{item name}-{yyyyMMdd-HHmmss}.webp` ([DriveNameFactory]) and stamps the returned file
 * id into `master_items.drive_image_id` (Room + outbox upsert, so it syncs and the
 * ADR-023 backup manifest can reference it).
 *
 * A photo is pending when its row has no `drive_image_id` and its `image_path` is already
 * a Storage object path (the ADR-023 serving upload succeeded first — Drive is only the
 * durable copy). The bytes come from either of two sources:
 *
 * - the device-local `{itemId}.webp` file when THIS device took the photo (free, never
 *   throttled), or
 * - a Supabase Storage download ([ItemPhotoStorageDownloader]) for rows with NO local
 *   file — web imports and legacy photos (the ADR-055 gap). Downloads cost a full
 *   round-trip each, so at most [MAX_STORAGE_DOWNLOADS_PER_RUN] rows download per sync
 *   run; the rest simply stay pending and the next run continues where this one stopped
 *   (a 100-item import drains across ~10 runs instead of hammering one).
 *
 * Not-linked stops the whole pass silently; per-item failures are logged and left pending
 * for the next sync run.
 */
@Singleton
class DriveItemImageMirror
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val masterItemDao: MasterItemDao,
        private val businessDao: BusinessDao,
        private val driveUploader: DriveUploader,
        private val storageDownloader: ItemPhotoStorageDownloader,
        private val nameFactory: DriveNameFactory,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : ItemPhotoDriveMirror {
        private val json = Json { encodeDefaults = true }

        override suspend fun mirrorPending(): Int {
            val candidates = masterItemDao.pendingDriveImageMirror()
            var mirrored = 0
            var downloadsUsed = 0
            for (row in candidates) {
                val imagePath = row.imagePath ?: continue
                // Storage upload not done yet (or a path form we don't own): not our turn.
                if (isLocalItemImagePath(imagePath)) continue
                val business = businessDao.byId(row.businessId) ?: continue
                val localFile = localItemImageFile(context, row.id)
                val isTemp: Boolean
                val sourceFile: File
                if (localFile.isFile) {
                    // This device took the photo — mirror straight from disk (ADR-055).
                    sourceFile = localFile
                    isTemp = false
                } else {
                    // Storage-only row (web import / legacy, ADR-058): download, throttled.
                    if (downloadsUsed >= MAX_STORAGE_DOWNLOADS_PER_RUN) continue
                    downloadsUsed++
                    val bytes = storageDownloader.download(imagePath).getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.w(TAG, "storage download failed for ${row.id}; row stays pending")
                        continue
                    }
                    sourceFile =
                        File
                            .createTempFile("drive-mirror-", ".webp", context.cacheDir)
                            .apply { writeBytes(bytes) }
                    isTemp = true
                }
                try {
                    val result =
                        driveUploader.upload(
                            businessName = business.name,
                            target = DriveTarget.InventoryImages,
                            fileName = nameFactory.fileName(row.name, fallbackBase = "item", extension = ".webp"),
                            mimeType = "image/webp",
                            sourceFile = sourceFile,
                        )
                    result.fold(
                        onSuccess = { ref ->
                            stampDriveImageId(row, ref.fileId)
                            mirrored++
                            Log.i(TAG, "item photo mirrored to Drive: item=${row.id} driveId=${ref.fileId}")
                        },
                        onFailure = { error ->
                            if (error is DriveNotAvailableException) {
                                // Not linked/configured: everything stays pending, silently
                                // (ADR-055 — the mirror never prompts). Stop the whole pass.
                                return mirrored
                            }
                            // Transient (offline, quota): leave pending; the next run retries.
                            Log.w(TAG, "item photo mirror failed for ${row.id}: ${error.message}")
                        },
                    )
                } finally {
                    if (isTemp) sourceFile.delete()
                }
            }
            return mirrored
        }

        private suspend fun stampDriveImageId(
            row: MasterItemEntity,
            driveFileId: String,
        ) {
            val updated = row.copy(driveImageId = driveFileId, updatedAt = clock.instant())
            masterItemDao.upsert(updated)
            val model =
                MasterItem(
                    id = updated.id,
                    businessId = updated.businessId,
                    name = updated.name,
                    unit = updated.unit,
                    imagePath = updated.imagePath,
                    driveImageId = updated.driveImageId,
                    createdAt = updated.createdAt,
                    updatedAt = updated.updatedAt,
                    deletedAt = updated.deletedAt,
                )
            outboxWriter.enqueue(
                "master_items",
                updated.id,
                OutboxOperation.UPSERT,
                json.encodeToString(MasterItem.serializer(), model),
            )
        }

        companion object {
            const val TAG = "SamarohDriveMirror"

            /**
             * Storage-download budget per sync run (ADR-058). Rationale: a download+upload
             * is two full image round-trips per row; syncs run on every local mutation,
             * on app foreground and periodically, so a 100-item web import drains in ~10
             * unremarkable runs (typically within the hour) instead of one sync run doing
             * 200 transfers back-to-back on a phone connection. Local-file mirrors are a
             * single cheap upload and arrive one at a time in practice — not throttled.
             */
            const val MAX_STORAGE_DOWNLOADS_PER_RUN = 10
        }
    }
