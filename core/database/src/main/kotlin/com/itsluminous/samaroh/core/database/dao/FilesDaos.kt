package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.FileEntity
import com.itsluminous.samaroh.core.database.entity.FolderAccessEntity
import com.itsluminous.samaroh.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * FILES module DAOs (ADR-085). The UI lists live rows per business; breadcrumbs,
 * subtree counts and the folder-scoped search happen in the feature/repository layer
 * over the whole per-business index (a few hundred rows at most).
 */

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    /** Every live folder of the business, A–Z (case-insensitive). */
    @Query(
        """
        SELECT * FROM folders
        WHERE business_id = :businessId AND deleted_at IS NULL
        ORDER BY name COLLATE NOCASE ASC
        """,
    )
    fun foldersForBusiness(businessId: String): Flow<List<FolderEntity>>

    /** Snapshot of the live folders (cascade delete, duplicate steering). */
    @Query("SELECT * FROM folders WHERE business_id = :businessId AND deleted_at IS NULL")
    suspend fun liveForBusiness(businessId: String): List<FolderEntity>

    /**
     * Case-insensitive LIVE name match among the siblings of [parentId] (NULL = top
     * level) — mirrors `uq_folders_biz_parent_name` (design D12).
     */
    @Query(
        """
        SELECT * FROM folders
        WHERE business_id = :businessId
          AND deleted_at IS NULL
          AND ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId)
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun liveSiblingNamed(
        businessId: String,
        parentId: String?,
        name: String,
    ): FolderEntity?
}

@Dao
interface FileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun byId(id: String): FileEntity?

    /** Every live file of the business, newest first. */
    @Query(
        """
        SELECT * FROM files
        WHERE business_id = :businessId AND deleted_at IS NULL
        ORDER BY created_at DESC
        """,
    )
    fun filesForBusiness(businessId: String): Flow<List<FileEntity>>

    /** Snapshot of the live files (folder cascade delete). */
    @Query("SELECT * FROM files WHERE business_id = :businessId AND deleted_at IS NULL")
    suspend fun liveForBusiness(businessId: String): List<FileEntity>

    /** Device-only cache path (staged original / downloaded copy) — never enqueues an outbox op. */
    @Query("UPDATE files SET local_cache_path = :path WHERE id = :id")
    suspend fun updateLocalCachePath(
        id: String,
        path: String?,
    )

    /** Uploaded files this device has not yet confirmed link-shared (ADR-059 repair pass). */
    @Query(
        """
        SELECT * FROM files
        WHERE drive_file_id IS NOT NULL AND drive_permission_ensured = 0 AND deleted_at IS NULL
        ORDER BY created_at ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingPermissionRepair(limit: Int): List<FileEntity>

    /** Device-only flag (ADR-059 shape) — the column never syncs. */
    @Query("UPDATE files SET drive_permission_ensured = 1 WHERE id = :id")
    suspend fun markDrivePermissionEnsured(id: String)

    /** Soft delete (tombstone) — bumps `updated_at` (mutable row, single pull leg). */
    @Query("UPDATE files SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface FolderAccessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(access: FolderAccessEntity)

    @Query("SELECT * FROM folder_access WHERE folder_id = :folderId AND member_id = :memberId")
    suspend fun byIds(
        folderId: String,
        memberId: String,
    ): FolderAccessEntity?

    /** Live allow-list rows of the business (owner's access editor; `Restricted` chips). */
    @Query("SELECT * FROM folder_access WHERE business_id = :businessId AND deleted_at IS NULL")
    fun accessForBusiness(businessId: String): Flow<List<FolderAccessEntity>>

    /** Every row (live + revoked) of one folder — the editor reuses revoked PK rows on re-grant. */
    @Query("SELECT * FROM folder_access WHERE folder_id = :folderId")
    suspend fun allForFolder(folderId: String): List<FolderAccessEntity>
}
