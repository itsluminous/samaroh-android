package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.NoteDao
import com.itsluminous.samaroh.core.database.dao.NoteTagDao
import com.itsluminous.samaroh.core.database.dao.NoteTagLinkDao
import com.itsluminous.samaroh.core.database.entity.NoteEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagLinkEntity
import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteTag
import com.itsluminous.samaroh.core.model.NoteTagLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NOTES module repository (ADR-077) — new interface, an ADDITIVE extension of the
 * frozen `core:data` contract. Offline-first like every sibling: reads from Room,
 * writes to Room + outbox in one logical step.
 *
 * `note_tag_links` rows are SOFT links with a composite PK: link/unlink both go
 * through [saveTagLink] as UPSERTs (untag sets `deletedAt`, retag clears it) — the
 * links table never issues a DELETE outbox op. Their outbox entity id is the composite
 * `"noteId|tagId"` string the sync engine's composite-key support expects (ADR-077).
 */
interface NotesRepository {
    /** Every live note of the business (all statuses), pinned & newest first. */
    fun notes(businessId: String): Flow<List<Note>>

    suspend fun note(id: String): Note?

    /** Live tags of the business, alphabetical. */
    fun tags(businessId: String): Flow<List<NoteTag>>

    /** Live note↔tag links of the business (soft-deleted links excluded). */
    fun tagLinks(businessId: String): Flow<List<NoteTagLink>>

    /** Upserts locally and enqueues an outbox push. */
    suspend fun saveNote(note: Note)

    /**
     * "Delete forever" (Trash purge, ADR-077): soft-delete tombstone + DELETE push —
     * the notes.delete-gated action. Trash/restore/complete/pin are ordinary
     * [saveNote] status updates.
     */
    suspend fun purgeNote(id: String)

    /** Upserts a tag locally and enqueues an outbox push (rename/tombstone included). */
    suspend fun saveTag(tag: NoteTag)

    /** Upserts a link row (link = live row, unlink = `deletedAt` set) + outbox push. */
    suspend fun saveTagLink(link: NoteTagLink)

    /**
     * The 30-day Trash sweep (ADR-077): hard-tombstones every live TRASHED note whose
     * `trashedAt` precedes [cutoff]. Returns the number of purged notes. Callers gate
     * on `notes.delete` (owners implicitly) before invoking.
     */
    suspend fun purgeTrashedBefore(
        businessId: String,
        cutoff: Instant,
    ): Int
}

@Singleton
class RoomNotesRepository
    @Inject
    constructor(
        private val noteDao: NoteDao,
        private val tagDao: NoteTagDao,
        private val linkDao: NoteTagLinkDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : NotesRepository {
        private val json = Json { encodeDefaults = true }

        override fun notes(businessId: String): Flow<List<Note>> =
            noteDao.notesForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun note(id: String): Note? = noteDao.byId(id)?.toModel()

        override fun tags(businessId: String): Flow<List<NoteTag>> =
            tagDao.tagsForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override fun tagLinks(businessId: String): Flow<List<NoteTagLink>> =
            linkDao.linksForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun saveNote(note: Note) {
            noteDao.upsert(note.toEntity())
            outboxWriter.enqueue("notes", note.id, OutboxOperation.UPSERT, json.encodeToString(Note.serializer(), note))
        }

        override suspend fun purgeNote(id: String) {
            val now = clock.instant()
            noteDao.tombstone(id, now)
            outboxWriter.enqueue("notes", id, OutboxOperation.DELETE, notesTombstonePayload(id, now))
        }

        override suspend fun saveTag(tag: NoteTag) {
            tagDao.upsert(tag.toEntity())
            outboxWriter.enqueue("note_tags", tag.id, OutboxOperation.UPSERT, json.encodeToString(NoteTag.serializer(), tag))
        }

        override suspend fun saveTagLink(link: NoteTagLink) {
            linkDao.upsert(link.toEntity())
            outboxWriter.enqueue(
                "note_tag_links",
                // Composite entity id (ADR-077): must match the id the sync engine
                // derives from a pulled row for LWW matching.
                "${link.noteId}|${link.tagId}",
                OutboxOperation.UPSERT,
                json.encodeToString(NoteTagLink.serializer(), link),
            )
        }

        override suspend fun purgeTrashedBefore(
            businessId: String,
            cutoff: Instant,
        ): Int {
            val expired = noteDao.trashedBefore(businessId, cutoff)
            expired.forEach { purgeNote(it.id) }
            return expired.size
        }

        private fun notesTombstonePayload(
            id: String,
            at: Instant,
        ): String =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("id", id)
                    put("deleted_at", at.toString())
                },
            )
    }

// Mechanical entity <-> model mapping (identical field sets by contract).

internal fun NoteEntity.toModel() =
    Note(
        id,
        businessId,
        kind,
        title,
        content,
        checklist,
        color,
        pinned,
        status,
        completedAt,
        trashedAt,
        createdBy,
        updatedBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun Note.toEntity() =
    NoteEntity(
        id,
        businessId,
        kind,
        title,
        content,
        checklist,
        color,
        pinned,
        status,
        completedAt,
        trashedAt,
        createdBy,
        updatedBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun NoteTagEntity.toModel() = NoteTag(id, businessId, name, createdAt, updatedAt, deletedAt)

internal fun NoteTag.toEntity() = NoteTagEntity(id, businessId, name, createdAt, updatedAt, deletedAt)

internal fun NoteTagLinkEntity.toModel() = NoteTagLink(noteId, tagId, businessId, createdAt, updatedAt, deletedAt)

internal fun NoteTagLink.toEntity() = NoteTagLinkEntity(noteId, tagId, businessId, createdAt, updatedAt, deletedAt)
