package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NoteTag
import com.itsluminous.samaroh.core.model.NoteTagLink
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NOTES repository (ADR-077): Room + outbox in one step, composite link entity ids,
 * purge tombstones and the 30-day sweep.
 */
@RunWith(RobolectricTestRunner::class)
class RoomNotesRepositoryTest {
    private data class OutboxRecord(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
        val payloadJson: String,
    )

    private class RecordingOutboxWriter : OutboxWriter {
        val records = mutableListOf<OutboxRecord>()

        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            records += OutboxRecord(entityType, entityId, operation, payloadJson)
        }
    }

    private val now: Instant = Instant.parse("2026-09-10T06:00:00Z")
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: RoomNotesRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        outbox = RecordingOutboxWriter()
        repository =
            RoomNotesRepository(
                noteDao = db.noteDao(),
                tagDao = db.noteTagDao(),
                linkDao = db.noteTagLinkDao(),
                outboxWriter = outbox,
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(
        id: String,
        status: NoteStatus = NoteStatus.ACTIVE,
        trashedAt: Instant? = null,
    ) = Note(
        id = id,
        businessId = Fixtures.BUSINESS_ID,
        kind = NoteKind.CHECKLIST,
        title = "note $id",
        checklist = listOf(NoteChecklistItem("i-1", "Milk", done = true)),
        status = status,
        trashedAt = trashedAt,
        createdBy = Fixtures.USER_ID,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `saveNote lands in room and enqueues an upsert with the checklist payload`() =
        runTest {
            repository.saveNote(note("n-1"))

            assertThat(repository.note("n-1")).isEqualTo(note("n-1"))
            assertThat(repository.notes(Fixtures.BUSINESS_ID).first().map { it.id }).containsExactly("n-1")
            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("notes")
            assertThat(record.entityId).isEqualTo("n-1")
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload.getValue("kind").jsonPrimitive.content).isEqualTo("CHECKLIST")
            assertThat(payload.getValue("checklist").toString()).contains("\"text\":\"Milk\"")
        }

    @Test
    fun `purgeNote soft-tombstones locally and enqueues a delete push`() =
        runTest {
            repository.saveNote(note("n-purge"))
            outbox.records.clear()

            repository.purgeNote("n-purge")

            // Soft delete, never a hard removal (§8).
            assertThat(db.noteDao().byId("n-purge")!!.deletedAt).isEqualTo(now)
            assertThat(repository.notes(Fixtures.BUSINESS_ID).first()).isEmpty()
            val record = outbox.records.single()
            assertThat(record.operation).isEqualTo(OutboxOperation.DELETE)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload.getValue("deleted_at").jsonPrimitive.content).isEqualTo(now.toString())
        }

    @Test
    fun `saveTagLink enqueues the composite entity id the sync engine derives`() =
        runTest {
            val link =
                NoteTagLink(
                    noteId = "n-1",
                    tagId = "t-1",
                    businessId = Fixtures.BUSINESS_ID,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.saveTagLink(link)

            assertThat(repository.tagLinks(Fixtures.BUSINESS_ID).first()).containsExactly(link)
            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("note_tag_links")
            assertThat(record.entityId).isEqualTo("n-1|t-1")
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)

            // Soft unlink: an UPSERT with deleted_at set, never a DELETE op (ADR-077).
            outbox.records.clear()
            repository.saveTagLink(link.copy(deletedAt = now))
            assertThat(repository.tagLinks(Fixtures.BUSINESS_ID).first()).isEmpty()
            assertThat(outbox.records.single().operation).isEqualTo(OutboxOperation.UPSERT)
        }

    @Test
    fun `saveTag round-trips and tombstoned tags leave the live listing`() =
        runTest {
            val tag = NoteTag(id = "t-1", businessId = Fixtures.BUSINESS_ID, name = "urgent", createdAt = now, updatedAt = now)
            repository.saveTag(tag)
            assertThat(repository.tags(Fixtures.BUSINESS_ID).first()).containsExactly(tag)

            repository.saveTag(tag.copy(deletedAt = now))
            assertThat(repository.tags(Fixtures.BUSINESS_ID).first()).isEmpty()
            assertThat(outbox.records).hasSize(2)
        }

    @Test
    fun `purgeTrashedBefore tombstones only expired trash and reports the count`() =
        runTest {
            val cutoff = Instant.parse("2026-08-11T06:00:00Z") // now - 30d
            repository.saveNote(note("expired", status = NoteStatus.TRASHED, trashedAt = Instant.parse("2026-08-01T00:00:00Z")))
            repository.saveNote(note("fresh", status = NoteStatus.TRASHED, trashedAt = Instant.parse("2026-09-01T00:00:00Z")))
            repository.saveNote(note("active"))
            outbox.records.clear()

            val purged = repository.purgeTrashedBefore(Fixtures.BUSINESS_ID, cutoff)

            assertThat(purged).isEqualTo(1)
            assertThat(db.noteDao().byId("expired")!!.deletedAt).isEqualTo(now)
            assertThat(db.noteDao().byId("fresh")!!.deletedAt).isNull()
            assertThat(db.noteDao().byId("active")!!.deletedAt).isNull()
            val record = outbox.records.single()
            assertThat(record.entityId).isEqualTo("expired")
            assertThat(record.operation).isEqualTo(OutboxOperation.DELETE)
        }

    @Test
    fun `saveNote strips blank checklist items from room and the outbox payload`() =
        runTest {
            // Phantom-card guard (ADR-079): blank-text items must never persist —
            // regardless of which caller saved (editor, inline toggle, future paths).
            val dirty =
                note("n-blank").copy(
                    checklist =
                        listOf(
                            NoteChecklistItem("i-1", "Milk", done = false),
                            NoteChecklistItem("i-2", "", done = false),
                            NoteChecklistItem("i-3", "   ", done = true),
                            NoteChecklistItem("i-4", "Diyas", done = true),
                        ),
                )

            repository.saveNote(dirty)

            val saved = repository.note("n-blank")!!
            assertThat(saved.checklist.map { it.id }).containsExactly("i-1", "i-4").inOrder()
            val payload = Json.parseToJsonElement(outbox.records.single().payloadJson).jsonObject
            assertThat(payload.getValue("checklist").toString()).doesNotContain("i-2")
            assertThat(payload.getValue("checklist").toString()).doesNotContain("i-3")
        }
}
