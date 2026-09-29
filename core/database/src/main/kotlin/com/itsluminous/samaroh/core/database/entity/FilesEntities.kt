package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/*
 * FILES module tables (ADR-085) — mirrors of shared migration 009_files_tab.sql.
 * Added in schema v13 as an ADDITIVE extension of the frozen contract. Bytes live in
 * Google Drive; these rows are the metadata index the UI lists offline.
 */

/** A Files-module folder (`folders`). `parent_id` NULL = top level. */
@Entity(
    tableName = "folders",
    indices = [Index(value = ["business_id", "parent_id"])],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val name: String,
    /** Owner-only allow-list flag (design D6). */
    val restricted: Boolean = false,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * A Files-module file row (`files`). `drive_file_id` is null ONLY while the upload waits
 * in the outbox (the server column is NOT NULL). Two DEVICE-ONLY columns, exactly the
 * bills shape (ADR-052/059): `local_cache_path` (staged original / downloaded copy) and
 * `drive_permission_ensured` (anyone-with-link confirmed by this device) — never synced;
 * `LocalApplier` preserves both across pulls.
 */
@Entity(
    tableName = "files",
    indices = [Index(value = ["business_id", "folder_id"])],
)
data class FileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    val name: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
    /** Device-only: staged original (pre-upload) or downloaded cache copy. */
    @ColumnInfo(name = "local_cache_path") val localCachePath: String? = null,
    /** Device-only: this device confirmed the anyone-with-link reader permission. */
    @ColumnInfo(name = "drive_permission_ensured", defaultValue = "0") val drivePermissionEnsured: Boolean = false,
)

/**
 * Restricted-folder allow-list SOFT link (`folder_access`, composite PK): revoke sets
 * `deleted_at`, re-grant clears it — the (folder_id, member_id) row is reused.
 */
@Entity(
    tableName = "folder_access",
    primaryKeys = ["folder_id", "member_id"],
    indices = [Index(value = ["business_id"]), Index(value = ["member_id"])],
)
data class FolderAccessEntity(
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)
