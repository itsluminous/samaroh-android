package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.FileDao
import com.itsluminous.samaroh.core.database.dao.FolderAccessDao
import com.itsluminous.samaroh.core.database.dao.FolderDao
import com.itsluminous.samaroh.core.database.entity.FileEntity
import com.itsluminous.samaroh.core.database.entity.FolderAccessEntity
import com.itsluminous.samaroh.core.database.entity.FolderEntity
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.core.model.FolderAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FILES module repository (ADR-085) — new interface, an ADDITIVE extension of the frozen
 * `core:data` contract. Offline-first like every sibling: reads from Room, writes to
 * Room + outbox in one logical step.
 *
 * Deletion contract (design D10): a tombstone is an UPSERT carrying `deleted_at` (all
 * three tables are mutable rows, so the bump moves the LWW cursor and one pull leg
 * suffices). Folder deletion cascades CLIENT-SIDE, children first, one outbox op per row
 * (ADR-028 style); Drive deletes are the caller's best-effort concern (`FilesDriveDeleter`).
 * `folder_access` rows are SOFT links with a composite `"folderId|memberId"` entity id
 * — never a DELETE op.
 */
interface FilesRepository {
    /** Every live folder of the business, A–Z. */
    fun folders(businessId: String): Flow<List<Folder>>

    /** Every live file of the business, newest first. Includes not-yet-uploaded staged rows. */
    fun files(businessId: String): Flow<List<FileWithLocalState>>

    /** Live allow-list rows of the business (server RLS already scoped them to what this member may see). */
    fun folderAccess(businessId: String): Flow<List<FolderAccess>>

    suspend fun folder(id: String): Folder?

    suspend fun file(id: String): FileWithLocalState?

    /** Case-insensitive LIVE sibling name check (design D12). */
    suspend fun liveSiblingNamed(
        businessId: String,
        parentId: String?,
        name: String,
    ): Folder?

    /** Upserts a folder locally and enqueues the push (create / rename / restricted flip). */
    suspend fun saveFolder(folder: Folder)

    /**
     * Stages a file row (Drive id null until the sync drain uploads it) with its local
     * copy, and enqueues the row op — the engine uploads BEFORE pushing (ADR-018 shape).
     */
    suspend fun stageFile(
        file: FileItem,
        localPath: String,
    )

    /**
     * Rename / move of an EXISTING file row (ADR-090, design D14): whole-row upsert with
     * the new `name` / `folder_id` (device-only columns preserved) + outbox push. The
     * caller owns the permission gate and the Drive best-effort mirror. No-op when the
     * row is gone.
     */
    suspend fun updateFile(file: FileItem)

    /** Device-only cache path update (never an outbox op). */
    suspend fun updateLocalCachePath(
        fileId: String,
        path: String?,
    )

    /** Tombstones one file (row UPSERT with `deleted_at`). Returns the tombstoned row. */
    suspend fun deleteFile(id: String): FileWithLocalState?

    /**
     * Tombstones a folder and its whole live subtree, children first. Returns every
     * tombstoned FILE (for the caller's best-effort Drive deletes / cache cleanup).
     */
    suspend fun deleteFolderTree(folderId: String): List<FileWithLocalState>

    /** Upserts an allow-list row (grant = live row, revoke = `deletedAt` set) + outbox push. */
    suspend fun saveFolderAccess(access: FolderAccess)

    /** Every allow-list row (live + revoked) of one folder — re-grants reuse the PK row. */
    suspend fun folderAccessRows(folderId: String): List<FolderAccess>
}

/** A file row with its device-only state (the Room-only columns the model does not carry). */
data class FileWithLocalState(
    val file: FileItem,
    val localCachePath: String?,
    val drivePermissionEnsured: Boolean,
)

@Singleton
class RoomFilesRepository
    @Inject
    constructor(
        private val folderDao: FolderDao,
        private val fileDao: FileDao,
        private val accessDao: FolderAccessDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : FilesRepository {
        private val json = Json { encodeDefaults = true }

        override fun folders(businessId: String): Flow<List<Folder>> =
            folderDao.foldersForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override fun files(businessId: String): Flow<List<FileWithLocalState>> =
            fileDao.filesForBusiness(businessId).map { list -> list.map { it.toLocalState() } }

        override fun folderAccess(businessId: String): Flow<List<FolderAccess>> =
            accessDao.accessForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun folder(id: String): Folder? = folderDao.byId(id)?.toModel()

        override suspend fun file(id: String): FileWithLocalState? = fileDao.byId(id)?.toLocalState()

        override suspend fun liveSiblingNamed(
            businessId: String,
            parentId: String?,
            name: String,
        ): Folder? = folderDao.liveSiblingNamed(businessId, parentId, name.trim())?.toModel()

        override suspend fun saveFolder(folder: Folder) {
            folderDao.upsert(folder.toEntity())
            outboxWriter.enqueue(TABLE_FOLDERS, folder.id, OutboxOperation.UPSERT, json.encodeToString(Folder.serializer(), folder))
        }

        override suspend fun stageFile(
            file: FileItem,
            localPath: String,
        ) {
            fileDao.upsert(file.toEntity(localCachePath = localPath, drivePermissionEnsured = false))
            outboxWriter.enqueue(TABLE_FILES, file.id, OutboxOperation.UPSERT, json.encodeToString(FileItem.serializer(), file))
        }

        override suspend fun updateFile(file: FileItem) {
            val existing = fileDao.byId(file.id) ?: return
            fileDao.upsert(
                file.toEntity(localCachePath = existing.localCachePath, drivePermissionEnsured = existing.drivePermissionEnsured),
            )
            outboxWriter.enqueue(TABLE_FILES, file.id, OutboxOperation.UPSERT, json.encodeToString(FileItem.serializer(), file))
        }

        override suspend fun updateLocalCachePath(
            fileId: String,
            path: String?,
        ) = fileDao.updateLocalCachePath(fileId, path)

        override suspend fun deleteFile(id: String): FileWithLocalState? {
            val existing = fileDao.byId(id) ?: return null
            tombstoneFile(existing, clock.instant())
            return fileDao.byId(id)?.toLocalState()
        }

        override suspend fun deleteFolderTree(folderId: String): List<FileWithLocalState> {
            val root = folderDao.byId(folderId) ?: return emptyList()
            val now = clock.instant()
            val liveFolders = folderDao.liveForBusiness(root.businessId)
            val liveFiles = fileDao.liveForBusiness(root.businessId)
            val childrenOf = liveFolders.groupBy { it.parentId }
            // Depth-first, CHILDREN FIRST (ADR-028 cascade order): a device that pulls
            // mid-cascade never sees a live child under a dead parent for long.
            val ordered = mutableListOf<FolderEntity>()

            fun visit(folder: FolderEntity) {
                childrenOf[folder.id].orEmpty().forEach(::visit)
                ordered += folder
            }
            visit(root)
            val folderIds = ordered.map { it.id }.toSet()
            val removedFiles = mutableListOf<FileWithLocalState>()
            for (file in liveFiles.filter { it.folderId in folderIds }) {
                tombstoneFile(file, now)
                fileDao.byId(file.id)?.let { removedFiles += it.toLocalState() }
            }
            for (folder in ordered) {
                val tombstoned = folder.copy(deletedAt = now, updatedAt = now)
                folderDao.upsert(tombstoned)
                outboxWriter.enqueue(
                    TABLE_FOLDERS,
                    folder.id,
                    OutboxOperation.UPSERT,
                    json.encodeToString(Folder.serializer(), tombstoned.toModel()),
                )
            }
            return removedFiles
        }

        override suspend fun saveFolderAccess(access: FolderAccess) {
            accessDao.upsert(access.toEntity())
            outboxWriter.enqueue(
                TABLE_FOLDER_ACCESS,
                // Composite entity id (ADR-077 shape): must match what the engine derives from a pulled row.
                "${access.folderId}|${access.memberId}",
                OutboxOperation.UPSERT,
                json.encodeToString(FolderAccess.serializer(), access),
            )
        }

        override suspend fun folderAccessRows(folderId: String): List<FolderAccess> = accessDao.allForFolder(folderId).map { it.toModel() }

        /**
         * A file tombstone is an UPSERT of the whole row with `deleted_at` set (the
         * mutable-row contract): the guard trigger lets delete-holders change
         * `deleted_at`; every other column round-trips unchanged. A STAGED file (never
         * uploaded, no Drive id) has no server row — its pending upsert is simply
         * replaced by nothing: the tombstone must not be pushed (drive_file_id NOT NULL
         * would reject it), so the outbox entry is left to the staged-row rule in
         * SyncEngine, which drops tombstones of never-uploaded files.
         */
        private suspend fun tombstoneFile(
            file: FileEntity,
            now: Instant,
        ) {
            val tombstoned = file.copy(deletedAt = now, updatedAt = now)
            fileDao.upsert(tombstoned)
            outboxWriter.enqueue(
                TABLE_FILES,
                file.id,
                OutboxOperation.UPSERT,
                json.encodeToString(FileItem.serializer(), tombstoned.toModel()),
            )
        }

        companion object {
            const val TABLE_FOLDERS = "folders"
            const val TABLE_FILES = "files"
            const val TABLE_FOLDER_ACCESS = "folder_access"
        }
    }

// Mechanical entity <-> model mapping (identical field sets by contract).

fun FolderEntity.toModel() = Folder(id, businessId, parentId, name, restricted, createdBy, updatedBy, createdAt, updatedAt, deletedAt)

fun Folder.toEntity() = FolderEntity(id, businessId, parentId, name, restricted, createdBy, updatedBy, createdAt, updatedAt, deletedAt)

fun FileEntity.toModel() =
    FileItem(id, businessId, folderId, name, mimeType, sizeBytes, driveFileId, createdBy, createdAt, updatedAt, deletedAt)

fun FileEntity.toLocalState() = FileWithLocalState(toModel(), localCachePath, drivePermissionEnsured)

fun FileItem.toEntity(
    localCachePath: String?,
    drivePermissionEnsured: Boolean,
) = FileEntity(
    id,
    businessId,
    folderId,
    name,
    mimeType,
    sizeBytes,
    driveFileId,
    createdBy,
    createdAt,
    updatedAt,
    deletedAt,
    localCachePath,
    drivePermissionEnsured,
)

fun FolderAccessEntity.toModel() = FolderAccess(folderId, memberId, businessId, createdAt, updatedAt, deletedAt)

fun FolderAccess.toEntity() = FolderAccessEntity(folderId, memberId, businessId, createdAt, updatedAt, deletedAt)
