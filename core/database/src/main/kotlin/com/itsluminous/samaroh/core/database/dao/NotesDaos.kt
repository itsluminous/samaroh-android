package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.NoteEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagLinkEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * NOTES module DAOs (ADR-077). The UI reads live rows per business; status filtering,
 * search and tag joins happen in the feature layer (per-business note counts are small).
 */

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: String): NoteEntity?

    /** Every live (non-tombstoned) note of the business — pinned & newest first. */
    @Query(
        """
        SELECT * FROM notes
        WHERE business_id = :businessId AND deleted_at IS NULL
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun notesForBusiness(businessId: String): Flow<List<NoteEntity>>

    /** Soft delete (tombstone) — synced rows are never hard-deleted (§8). */
    @Query("UPDATE notes SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )

    /** Trash-purge sweep input (ADR-077): live TRASHED notes trashed before [cutoff]. */
    @Query(
        """
        SELECT * FROM notes
        WHERE business_id = :businessId
          AND status = 'trashed'
          AND trashed_at IS NOT NULL AND trashed_at < :cutoff
          AND deleted_at IS NULL
        """,
    )
    suspend fun trashedBefore(
        businessId: String,
        cutoff: Instant,
    ): List<NoteEntity>
}

@Dao
interface NoteTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: NoteTagEntity)

    @Query("SELECT * FROM note_tags WHERE id = :id")
    suspend fun byId(id: String): NoteTagEntity?

    /** Live tags of the business, alphabetical (drawer tag list, tag type-ahead). */
    @Query(
        """
        SELECT * FROM note_tags
        WHERE business_id = :businessId AND deleted_at IS NULL
        ORDER BY name COLLATE NOCASE ASC
        """,
    )
    fun tagsForBusiness(businessId: String): Flow<List<NoteTagEntity>>
}

@Dao
interface NoteTagLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: NoteTagLinkEntity)

    @Query("SELECT * FROM note_tag_links WHERE note_id = :noteId AND tag_id = :tagId")
    suspend fun byIds(
        noteId: String,
        tagId: String,
    ): NoteTagLinkEntity?

    /** Live links of the business — the feature layer joins notes ↔ tags in memory. */
    @Query("SELECT * FROM note_tag_links WHERE business_id = :businessId AND deleted_at IS NULL")
    fun linksForBusiness(businessId: String): Flow<List<NoteTagLinkEntity>>
}
