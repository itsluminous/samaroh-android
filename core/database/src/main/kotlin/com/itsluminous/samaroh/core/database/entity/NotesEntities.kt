package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import java.time.Instant

/*
 * NOTES module tables (ADR-077) — mirrors of shared migration 005_notes.sql.
 * Added in schema v12 as an ADDITIVE extension of the frozen contract.
 */

/**
 * A business note or checklist (`notes`). `checklist` is stored as the same JSON
 * document the wire carries (one LWW blob per note); `trashed_at` anchors the
 * client-side 30-day purge sweep.
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["business_id", "status"])],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val kind: NoteKind = NoteKind.NOTE,
    val title: String? = null,
    val content: String? = null,
    val checklist: List<NoteChecklistItem> = emptyList(),
    /** `shared/booking-colors.json` key; NULL = default themed look (ADR-030 palette). */
    val color: String? = null,
    val pinned: Boolean = false,
    val status: NoteStatus = NoteStatus.ACTIVE,
    @ColumnInfo(name = "completed_at") val completedAt: Instant? = null,
    @ColumnInfo(name = "trashed_at") val trashedAt: Instant? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/** A per-business note tag (`note_tags`); live names are unique case-insensitively. */
@Entity(
    tableName = "note_tags",
    indices = [Index(value = ["business_id"])],
)
data class NoteTagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * A note ↔ tag SOFT link (`note_tag_links`, composite PK): untag sets `deleted_at`,
 * retag clears it — the (note_id, tag_id) row is reused, never hard-deleted.
 */
@Entity(
    tableName = "note_tag_links",
    primaryKeys = ["note_id", "tag_id"],
    indices = [Index(value = ["business_id"]), Index(value = ["tag_id"])],
)
data class NoteTagLinkEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)
