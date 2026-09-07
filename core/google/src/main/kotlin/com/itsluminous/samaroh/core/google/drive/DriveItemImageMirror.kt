package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import android.util.Log
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
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ItemPhotoDriveMirror] over the shared [DriveUploader] (ADR-055): uploads each pending
 * device-local item photo to `Samaroh/{Business}/images/inventory/{item-name}.webp`
 * (§9.1) and stamps the returned file id into `master_items.drive_image_id` (Room +
 * outbox upsert, so it syncs and the ADR-023 backup manifest can reference it).
 *
 * A photo is pending when its row has no `drive_image_id`, its `image_path` is already a
 * Storage object path (the ADR-023 serving upload succeeded first — Drive is only the
 * durable copy), and the local `{itemId}.webp` file still exists (i.e. THIS device took
 * the photo). Not-linked stops the whole pass silently; per-item failures are logged and
 * left pending for the next sync run.
 */
@Singleton
class DriveItemImageMirror
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val masterItemDao: MasterItemDao,
        private val businessDao: BusinessDao,
        private val driveUploader: DriveUploader,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : ItemPhotoDriveMirror {
        private val json = Json { encodeDefaults = true }

        override suspend fun mirrorPending(): Int {
            val candidates = masterItemDao.pendingDriveImageMirror()
            var mirrored = 0
            for (row in candidates) {
                val imagePath = row.imagePath ?: continue
                // Storage upload not done yet (or a path form we don't own): not our turn.
                if (isLocalItemImagePath(imagePath)) continue
                val file = localItemImageFile(context, row.id)
                if (!file.isFile) continue // web-added photo, or the local copy is gone.
                val business = businessDao.byId(row.businessId) ?: continue
                val result =
                    driveUploader.upload(
                        businessName = business.name,
                        target = DriveTarget.InventoryImages,
                        fileName = driveImageFileName(row),
                        mimeType = "image/webp",
                        sourceFile = file,
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
            }
            return mirrored
        }

        /** §9.1 names the file after the item; the id suffix keeps Drive names unique. */
        private fun driveImageFileName(row: MasterItemEntity): String = "${row.name.replace('/', '-').trim()}-${row.id}.webp"

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
        }
    }
