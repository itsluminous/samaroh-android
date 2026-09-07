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
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ItemPhotoDriveMirror] over the shared [DriveUploader] (ADR-055/058, Drive-first since
 * ADR-063): uploads each pending item photo to `Samaroh/{Business}/images/inventory/`
 * (§9.1) as `{item name}-{yyyyMMdd-HHmmss}.webp` ([DriveNameFactory]), best-effort
 * shares it anyone-with-link (members and the web render from Drive now — the exact
 * ADR-059 bill posture) and stamps the returned file id into
 * `master_items.drive_image_id` (Room + outbox upsert, so it syncs and other devices can
 * serve the photo).
 *
 * A photo is pending when its live row has no `drive_image_id` and a device-local source
 * file exists: the row's own local `image_path` (ADR-063 form) or the legacy
 * `{itemId}.webp` original. Only the device that took a photo can mirror it — Supabase
 * Storage (the ADR-058 download source for web imports) is gone, so rows without a local
 * file stay as they are (all migrated rows already carry a `drive_image_id`).
 *
 * Not-linked stops the whole pass silently; per-item failures are logged and left pending
 * for the next sync run. A failed inline permission never fails the upload — the row's
 * device-only `drive_permission_ensured` flag stays unset and the ADR-059/063 repair
 * pass retries.
 */
@Singleton
class DriveItemImageMirror
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val masterItemDao: MasterItemDao,
        private val businessDao: BusinessDao,
        private val driveUploader: DriveUploader,
        private val driveService: DriveService,
        private val nameFactory: DriveNameFactory,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : ItemPhotoDriveMirror {
        private val json = Json { encodeDefaults = true }

        override suspend fun mirrorPending(): Int {
            val candidates = masterItemDao.pendingDriveImageMirror()
            var mirrored = 0
            for (row in candidates) {
                val sourceFile = localSourceFile(row) ?: continue
                val result =
                    driveUploader.upload(
                        businessName = (businessDao.byId(row.businessId) ?: continue).name,
                        target = DriveTarget.InventoryImages,
                        fileName = nameFactory.fileName(row.name, fallbackBase = "item", extension = ".webp"),
                        mimeType = "image/webp",
                        sourceFile = sourceFile,
                    )
                result.fold(
                    onSuccess = { ref ->
                        // ADR-063: item photos serve members/web via the public link, so
                        // share like bills (ADR-059). Best-effort — the repair pass retries.
                        val permissionEnsured =
                            runCatching { driveService.ensureAnyoneReaderPermission(ref.fileId) }
                                .onFailure { Log.w(TAG, "inline permission failed for ${row.id}; repair will retry") }
                                .isSuccess
                        stampDriveImageId(row, ref.fileId, permissionEnsured)
                        mirrored++
                        Log.i(TAG, "item photo uploaded to Drive: item=${row.id} driveId=${ref.fileId} shared=$permissionEnsured")
                    },
                    onFailure = { error ->
                        if (error is DriveNotAvailableException) {
                            // Not linked/configured: everything stays pending, silently
                            // (ADR-055 — the mirror never prompts). Stop the whole pass.
                            return mirrored
                        }
                        // Transient (offline, quota): leave pending; the next run retries.
                        Log.w(TAG, "item photo upload failed for ${row.id}: ${error.message}")
                    },
                )
            }
            return mirrored
        }

        /**
         * The device-local bytes of a pending row, or null when this device never had
         * them: the row's own `image_path` when it is a live local file (ADR-063 photos),
         * else the legacy `{itemId}.webp` original (photos taken here before ADR-063
         * rewrote `image_path` to a Storage object path).
         */
        private fun localSourceFile(row: MasterItemEntity): File? {
            val imagePath = row.imagePath ?: return null
            if (isLocalItemImagePath(imagePath)) {
                val file = File(imagePath)
                return if (file.isFile) file else null
            }
            return localItemImageFile(context, row.id).takeIf { it.isFile }
        }

        private suspend fun stampDriveImageId(
            row: MasterItemEntity,
            driveFileId: String,
            permissionEnsured: Boolean,
        ) {
            val updated =
                row.copy(
                    driveImageId = driveFileId,
                    drivePermissionEnsured = permissionEnsured,
                    updatedAt = clock.instant(),
                )
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
