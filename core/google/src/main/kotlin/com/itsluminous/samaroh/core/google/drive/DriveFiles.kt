package com.itsluminous.samaroh.core.google.drive

import android.util.Log
import com.itsluminous.samaroh.core.data.sync.FilesDriveDeleter
import com.itsluminous.samaroh.core.data.sync.FilesUploader
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.FileDao
import com.itsluminous.samaroh.core.database.dao.FolderDao
import com.itsluminous.samaroh.core.database.entity.FolderEntity
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FILES-module Drive pipeline (ADR-085, design D4/D19): uploads a staged file into the
 * uploader's own Drive at `Samaroh/{Business}/files/{Folder}/{Sub}/{name}` — the folder
 * chain mirrors the app's `folders` names, find-or-create per segment (the existing
 * [RestDriveUploader] memo) — then shares it anyone-with-link inline (ADR-059 posture,
 * best-effort: the device-only `drive_permission_ensured` flag lets the repair pass
 * retry). Original bytes and the ORIGINAL file name (design D12); nothing is recompressed.
 */
@Singleton
class DriveFilesUploader
    @Inject
    constructor(
        private val fileDao: FileDao,
        private val folderDao: FolderDao,
        private val businessDao: BusinessDao,
        private val driveUploader: DriveUploader,
        private val driveService: DriveService,
    ) : FilesUploader {
        override suspend fun upload(fileId: String): FilesUploader.UploadResult {
            val row = fileDao.byId(fileId) ?: return FilesUploader.UploadResult.Obsolete
            row.driveFileId?.let { return FilesUploader.UploadResult.Uploaded(it) }
            // Tombstoned before it ever uploaded: no server row will exist — drop the op.
            if (row.deletedAt != null) return FilesUploader.UploadResult.Obsolete
            val localPath =
                row.localCachePath
                    ?: return FilesUploader.UploadResult.Failed("file $fileId has no local copy", retriable = false)
            val file = File(localPath)
            if (!file.exists()) {
                return FilesUploader.UploadResult.Failed("staged file missing: $localPath", retriable = false)
            }
            val business =
                businessDao.byId(row.businessId)
                    ?: return FilesUploader.UploadResult.Failed("business ${row.businessId} not found", retriable = false)
            val segments = folderChain(row.folderId)

            return driveUploader
                .upload(
                    businessName = business.name,
                    target = DriveTarget.Files(segments),
                    fileName = row.name,
                    mimeType = row.mimeType,
                    sourceFile = file,
                ).fold(
                    onSuccess = { ref ->
                        runCatching { driveService.ensureAnyoneReaderPermission(ref.fileId) }
                            .onSuccess { fileDao.markDrivePermissionEnsured(fileId) }
                            .onFailure { Log.w(TAG, "link permission failed for ${ref.fileId}: ${it.message}") }
                        FilesUploader.UploadResult.Uploaded(ref.fileId)
                    },
                    onFailure = { error ->
                        when (error) {
                            is DriveNotAvailableException -> FilesUploader.UploadResult.NotLinked
                            else -> FilesUploader.UploadResult.Failed(error.message ?: "drive upload failed", retriable = true)
                        }
                    },
                )
        }

        /** Root-first folder NAMES from the top level down to [folderId]; depth-capped against a corrupt cycle. */
        private suspend fun folderChain(folderId: String?): List<String> {
            val names = ArrayDeque<String>()
            var cursor: FolderEntity? = folderId?.let { folderDao.byId(it) }
            var guard = 0
            while (cursor != null && guard++ < MAX_DEPTH) {
                names.addFirst(cursor.name)
                cursor = cursor.parentId?.let { folderDao.byId(it) }
            }
            return names.toList()
        }

        private companion object {
            const val TAG = "SamarohFiles"
            const val MAX_DEPTH = 64
        }
    }

/**
 * Best-effort Drive removal for tombstoned Files rows (design D10): runs only when a
 * Google account is linked, never throws, swallows 403/404 (the file belongs to another
 * member's account — the row tombstone is the authoritative, shared contract).
 */
@Singleton
class DriveFilesDeleter
    @Inject
    constructor(
        private val driveService: DriveService,
        private val googleAccountLinker: GoogleAccountLinker,
    ) : FilesDriveDeleter {
        override suspend fun deleteBestEffort(driveFileId: String) {
            if (googleAccountLinker.linkState.first() !is GoogleLinkState.Linked) return
            runCatching { driveService.deleteFile(driveFileId) }
                .onFailure { error ->
                    when {
                        error is GoogleApiException && error.code in setOf(403, 404) ->
                            Log.i(TAG, "drive file $driveFileId not ours (http ${error.code}); tombstone only")
                        else -> Log.w(TAG, "best-effort drive delete failed for $driveFileId: ${error.message}")
                    }
                }
        }

        private companion object {
            const val TAG = "SamarohFiles"
        }
    }
