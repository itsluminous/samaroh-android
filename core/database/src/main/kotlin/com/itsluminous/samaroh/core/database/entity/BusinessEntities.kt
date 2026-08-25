package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import java.time.Instant

/*
 * Room entities — exact mirrors of the canonical Postgres schema
 * (shared/supabase/migrations/001_schema.sql). Table and column names match Postgres
 * so sync payload mapping is mechanical. FROZEN CONTRACT (docs/decisions.md ADR-001).
 *
 * No SQLite foreign-key constraints on purpose: the sync engine applies pulled rows in
 * arbitrary table order, and referential integrity is owned by Postgres (§8).
 * Money columns store Long paise (ADR-002); `deleted_at != null` = tombstone.
 */

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "business_type") val businessType: String = "Marriage Hall",
    val address: String? = null,
    @ColumnInfo(name = "owner_name") val ownerName: String,
    @ColumnInfo(name = "logo_path") val logoPath: String? = null,
    val currency: String = "INR",
    @ColumnInfo(name = "invoice_prefix") val invoicePrefix: String = "INV",
    @ColumnInfo(name = "invoice_counter") val invoiceCounter: Int = 0,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "business_members",
    indices = [Index(value = ["business_id", "invited_email"], unique = true)],
)
data class BusinessMemberEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "invited_email") val invitedEmail: String,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_owner") val isOwner: Boolean = false,
    val status: MemberStatus = MemberStatus.INVITED,
    val permissions: MemberPermissions = MemberPermissions(),
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * Client-visible projection of `google_accounts` — deliberately WITHOUT the
 * `refresh_token_cipher` column (tokens never leave the server; ADR-003).
 */
@Entity(tableName = "google_accounts")
data class GoogleAccountLinkEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    val email: String,
    val scopes: List<String> = emptyList(),
    @ColumnInfo(name = "drive_root_folder_id") val driveRootFolderId: String? = null,
    @ColumnInfo(name = "calendar_id") val calendarId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

@Entity(tableName = "business_settings")
data class BusinessSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "gcal_sync_enabled") val gcalSyncEnabled: Boolean = false,
    @ColumnInfo(name = "backup_frequency") val backupFrequency: String = "weekly",
    @ColumnInfo(name = "last_backup_at") val lastBackupAt: Instant? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)
