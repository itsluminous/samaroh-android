package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NoteTagLink
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * NOTES module sync wiring (ADR-077): pulled notes/tags/links apply to Room (checklist
 * jsonb ↔ converter list, enum casing), the composite-PK link table's keyset carries
 * both id legs, and LWW matches links by their "noteId|tagId" entity id.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineNotesTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore
    private lateinit var notifier: RecordingConflictNotifier

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
        notifier = RecordingConflictNotifier()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBusiness() {
        db.businessDao().upsert(
            com.itsluminous.samaroh.core.database.entity
                .BusinessEntity(
                    id = Fixtures.BUSINESS_ID,
                    name = "fixture-business",
                    ownerName = "fixture-owner",
                    ownerUserId = Fixtures.USER_ID,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                ),
        )
    }

    private fun remoteNoteRow(
        id: String,
        updatedAt: String,
        status: String = "active",
        kind: String = "checklist",
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("business_id", Fixtures.BUSINESS_ID)
            put("kind", kind)
            put("title", "remote note")
            put("content", JsonNull)
            put(
                "checklist",
                kotlinx.serialization.json.Json
                    .parseToJsonElement("""[{"id":"i-1","text":"Milk","done":true}]"""),
            )
            put("color", "peacock")
            put("pinned", true)
            put("status", status)
            put("completed_at", JsonNull)
            put("trashed_at", if (status == "trashed") "2026-08-25T10:00:00+00:00" else null)
            put("created_by", Fixtures.USER_ID)
            put("updated_by", JsonNull)
            put("created_at", "2026-08-25T09:00:00+00:00")
            put("updated_at", updatedAt)
            put("deleted_at", JsonNull)
        }

    private fun remoteLinkRow(
        noteId: String,
        tagId: String,
        updatedAt: String,
        deletedAt: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("note_id", noteId)
            put("tag_id", tagId)
            put("business_id", Fixtures.BUSINESS_ID)
            put("created_at", "2026-08-25T09:00:00+00:00")
            put("updated_at", updatedAt)
            if (deletedAt != null) put("deleted_at", deletedAt) else put("deleted_at", JsonNull)
        }

    @Test
    fun `pulled notes apply with checklist and enums decoded and status trashed intact`() =
        runTest {
            seedBusiness()
            remote.servePage(
                "notes",
                listOf(
                    remoteNoteRow("n-1", updatedAt = "2026-08-25T10:00:00+00:00"),
                    remoteNoteRow("n-2", updatedAt = "2026-08-25T11:00:00+00:00", status = "trashed", kind = "note"),
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            val n1 = db.noteDao().byId("n-1")!!
            assertThat(n1.kind).isEqualTo(NoteKind.CHECKLIST)
            assertThat(n1.status).isEqualTo(NoteStatus.ACTIVE)
            assertThat(n1.checklist).containsExactly(NoteChecklistItem("i-1", "Milk", done = true))
            assertThat(n1.pinned).isTrue()
            assertThat(n1.color).isEqualTo("peacock")

            val n2 = db.noteDao().byId("n-2")!!
            assertThat(n2.status).isEqualTo(NoteStatus.TRASHED)
            assertThat(n2.trashedAt).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"))
        }

    @Test
    fun `link pull stores the composite keyset id and reapplies idempotently`() =
        runTest {
            seedBusiness()
            remote.servePage(
                "note_tag_links",
                listOf(
                    remoteLinkRow("n-1", "t-1", updatedAt = "2026-08-25T10:00:00+00:00"),
                    remoteLinkRow("n-1", "t-2", updatedAt = "2026-08-25T10:00:00+00:00"),
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.noteTagLinkDao().byIds("n-1", "t-1")).isNotNull()
            assertThat(db.noteTagLinkDao().byIds("n-1", "t-2")).isNotNull()
            // The cursor's keyset id is the LAST row's composite "noteId|tagId".
            assertThat(db.syncCursorDao().cursor(Fixtures.BUSINESS_ID, "note_tag_links"))
                .isEqualTo(
                    SyncCursorEntity(
                        Fixtures.BUSINESS_ID,
                        "note_tag_links",
                        Instant.parse("2026-08-25T10:00:00Z"),
                        "n-1|t-2",
                        "2026-08-25T10:00:00+00:00",
                    ),
                )
        }

    @Test
    fun `link keyset passes both id legs on the next pull`() =
        runTest {
            seedBusiness()
            db.syncCursorDao().upsert(
                SyncCursorEntity(
                    Fixtures.BUSINESS_ID,
                    "note_tag_links",
                    Instant.parse("2026-08-25T10:00:00Z"),
                    "n-1|t-2",
                    "2026-08-25T10:00:00+00:00",
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            val index = remote.pullCalls.indexOfFirst { it.first == "note_tag_links" }
            assertThat(index).isAtLeast(0)
            assertThat(remote.pullAfterIds[index]).isEqualTo("n-1")
            assertThat(remote.pullAfterId2s[index]).isEqualTo("t-2")
            // Single-PK tables keep a null second leg.
            val notesIndex = remote.pullCalls.indexOfFirst { it.first == "notes" }
            assertThat(remote.pullAfterId2s[notesIndex]).isNull()
        }

    @Test
    fun `pending link op newer than the pulled row wins by composite entity id`() =
        runTest {
            seedBusiness()
            val local =
                NoteTagLink(
                    noteId = "n-1",
                    tagId = "t-1",
                    businessId = Fixtures.BUSINESS_ID,
                    createdAt = Instant.parse("2026-08-25T09:00:00Z"),
                    updatedAt = Instant.parse("2026-08-25T11:30:00Z"),
                    deletedAt = Instant.parse("2026-08-25T11:30:00Z"), // local soft unlink
                )
            db.noteTagLinkDao().upsert(
                com.itsluminous.samaroh.core.database.entity
                    .NoteTagLinkEntity(
                        noteId = local.noteId,
                        tagId = local.tagId,
                        businessId = local.businessId,
                        createdAt = local.createdAt,
                        updatedAt = local.updatedAt,
                        deletedAt = local.deletedAt,
                    ),
            )
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "note_tag_links",
                    entityId = "n-1|t-1",
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(NoteTagLink.serializer(), local),
                    createdAt = FIXED_NOW,
                ),
            )
            // Hold the op in the queue (push runs before pull).
            remote.onUpsert = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            // An OLDER remote row (still live) arrives — the local unlink must win.
            remote.servePage(
                "note_tag_links",
                listOf(remoteLinkRow("n-1", "t-1", updatedAt = "2026-08-25T10:00:00+00:00")),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            assertThat(db.noteTagLinkDao().byIds("n-1", "t-1")!!.deletedAt).isNotNull()
            assertThat(db.outboxDao().pendingForEntity("note_tag_links", "n-1|t-1")).hasSize(1)
            assertThat(outcome.conflictCount).isEqualTo(0)
            assertThat(notifier.events).isEmpty()
        }

    @Test
    fun `note delete push tombstones by the id column`() =
        runTest {
            seedBusiness()
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "notes",
                    entityId = "n-gone",
                    operation = "delete",
                    payloadJson = """{"id":"n-gone","deleted_at":"2026-08-25T12:00:00Z"}""",
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(remote.tombstones).containsExactly(Triple("notes", "n-gone", "2026-08-25T12:00:00Z"))
        }
}
