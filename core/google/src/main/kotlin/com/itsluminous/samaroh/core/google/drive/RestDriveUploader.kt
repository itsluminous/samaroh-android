package com.itsluminous.samaroh.core.google.drive

import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DriveUploader] over the Drive REST v3 API. Resolves the §9.1 folder chain with
 * find-or-create semantics; the `Samaroh` root folder id is cached in
 * `google_accounts.drive_root_folder_id` (spec §9.1) and sub-folder ids are memoized
 * per process.
 */
@Singleton
class RestDriveUploader
    @Inject
    constructor(
        private val driveService: DriveService,
        private val linkDao: GoogleAccountLinkDao,
        private val sessionHolder: SessionHolder,
        private val clock: Clock,
    ) : DriveUploader {
        private val mutex = Mutex()

        /** Folder-path → folder-id memo (cleared only with the process; ids are stable). */
        private val folderIdCache = mutableMapOf<String, String>()

        override suspend fun upload(
            businessName: String,
            target: DriveTarget,
            fileName: String,
            mimeType: String,
            sourceFile: File,
        ): Result<DriveFileRef> =
            runCatching {
                if (!GoogleServicesConfig.isConfigured) throw DriveNotAvailableException("google is not configured")
                mutex.withLock {
                    val rootId = ensureRootFolder()
                    val parentId =
                        DriveLayout.folderPathBelowRoot(businessName, target).fold(rootId) { parent, segment ->
                            ensureFolder(segment, parent)
                        }
                    driveService.uploadFile(fileName, mimeType, parentId, sourceFile)
                }
            }

        /** Finds/creates the `Samaroh` root and caches its id in `google_accounts` (§9.1). */
        private suspend fun ensureRootFolder(): String {
            val session = sessionHolder.session.first() ?: throw DriveNotAvailableException("not signed in")
            val link = linkDao.linkForUser(session.userId).first() ?: throw DriveNotAvailableException("no google account linked")
            link.driveRootFolderId?.let { return it }
            val rootId =
                driveService.findFolder(DriveLayout.ROOT_FOLDER_NAME, parentId = null)
                    ?: driveService.createFolder(DriveLayout.ROOT_FOLDER_NAME, parentId = null)
            linkDao.upsert(link.copy(driveRootFolderId = rootId, updatedAt = clock.instant()))
            return rootId
        }

        private suspend fun ensureFolder(
            name: String,
            parentId: String,
        ): String =
            folderIdCache.getOrPut("$parentId/$name") {
                driveService.findFolder(name, parentId) ?: driveService.createFolder(name, parentId)
            }
    }
