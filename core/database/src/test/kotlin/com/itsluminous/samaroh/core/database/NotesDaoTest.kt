package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.entity.NoteEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagLinkEntity
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** NOTES module DAOs (ADR-077): checklist converter round-trip, ordering, sweep query. */
@RunWith(RobolectricTestRunner::class)
class NotesDaoTest {
    private lateinit var db: SamarohDatabase

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(
        id: String,
        pinned: Boolean = false,
        status: NoteStatus = NoteStatus.ACTIVE,
        kind: NoteKind = NoteKind.NOTE,
        checklist: List<NoteChecklistItem> = emptyList(),
        trashedAt: Instant? = null,
        updatedAt: Instant = Instant.parse("2026-09-01T10:00:00Z"),
        deletedAt: Instant? = null,
    ) = NoteEntity(
        id = id,
        businessId = TEST_BUSINESS_ID,
        kind = kind,
        title = "note $id",
        content = null,
        checklist = checklist,
        pinned = pinned,
        status = status,
        trashedAt = trashedAt,
        createdBy = TEST_USER_ID,
        createdAt = Instant.parse("2026-09-01T09:00:00Z"),
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    @Test
    fun `checklist and enums round-trip through the converters`() =
        runTest {
            val items = listOf(NoteChecklistItem("i-1", "Milk", done = true), NoteChecklistItem("i-2", "Sugar"))
            val entity = note("n-1", kind = NoteKind.CHECKLIST, checklist = items)
            db.noteDao().upsert(entity)

            assertThat(db.noteDao().byId("n-1")).isEqualTo(entity)
        }

    @Test
    fun `notesForBusiness orders pinned first then newest and hides tombstones`() =
        runTest {
            db.noteDao().upsert(note("old", updatedAt = Instant.parse("2026-09-01T08:00:00Z")))
            db.noteDao().upsert(note("new", updatedAt = Instant.parse("2026-09-02T08:00:00Z")))
            db.noteDao().upsert(note("pinned", pinned = true, updatedAt = Instant.parse("2026-08-01T08:00:00Z")))
            db.noteDao().upsert(note("gone", deletedAt = Instant.parse("2026-09-03T08:00:00Z")))

            val notes = db.noteDao().notesForBusiness(TEST_BUSINESS_ID).first()

            assertThat(notes.map { it.id }).containsExactly("pinned", "new", "old").inOrder()
        }

    @Test
    fun `trashedBefore returns only live trashed notes older than the cutoff`() =
        runTest {
            val cutoff = Instant.parse("2026-09-01T00:00:00Z")
            db.noteDao().upsert(note("expired", status = NoteStatus.TRASHED, trashedAt = Instant.parse("2026-08-01T00:00:00Z")))
            db.noteDao().upsert(note("fresh", status = NoteStatus.TRASHED, trashedAt = Instant.parse("2026-09-02T00:00:00Z")))
            db.noteDao().upsert(note("active", status = NoteStatus.ACTIVE))
            db.noteDao().upsert(
                note(
                    "already-gone",
                    status = NoteStatus.TRASHED,
                    trashedAt = Instant.parse("2026-08-01T00:00:00Z"),
                    deletedAt = Instant.parse("2026-08-15T00:00:00Z"),
                ),
            )

            val expired = db.noteDao().trashedBefore(TEST_BUSINESS_ID, cutoff)

            assertThat(expired.map { it.id }).containsExactly("expired")
        }

    @Test
    fun `tombstone soft-deletes and bumps updated_at`() =
        runTest {
            db.noteDao().upsert(note("n-del"))
            val at = Instant.parse("2026-09-05T10:00:00Z")

            db.noteDao().tombstone("n-del", at)

            val row = db.noteDao().byId("n-del")!!
            assertThat(row.deletedAt).isEqualTo(at)
            assertThat(row.updatedAt).isEqualTo(at)
            assertThat(db.noteDao().notesForBusiness(TEST_BUSINESS_ID).first()).isEmpty()
        }

    @Test
    fun `tags order alphabetically case-insensitive and links look up by composite key`() =
        runTest {
            val at = Instant.parse("2026-09-01T10:00:00Z")
            db.noteTagDao().upsert(NoteTagEntity("t-b", TEST_BUSINESS_ID, "banquet", at, at))
            db.noteTagDao().upsert(NoteTagEntity("t-a", TEST_BUSINESS_ID, "Advance", at, at))
            db.noteTagDao().upsert(NoteTagEntity("t-gone", TEST_BUSINESS_ID, "old", at, at, deletedAt = at))

            assertThat(
                db
                    .noteTagDao()
                    .tagsForBusiness(TEST_BUSINESS_ID)
                    .first()
                    .map { it.name },
            ).containsExactly("Advance", "banquet").inOrder()

            val link = NoteTagLinkEntity("n-1", "t-a", TEST_BUSINESS_ID, at, at)
            db.noteTagLinkDao().upsert(link)
            assertThat(db.noteTagLinkDao().byIds("n-1", "t-a")).isEqualTo(link)
            assertThat(db.noteTagLinkDao().byIds("n-1", "t-b")).isNull()

            // Soft unlink: deleted links leave the live listing but the PK row remains.
            db.noteTagLinkDao().upsert(link.copy(deletedAt = at))
            assertThat(db.noteTagLinkDao().linksForBusiness(TEST_BUSINESS_ID).first()).isEmpty()
            assertThat(db.noteTagLinkDao().byIds("n-1", "t-a")!!.deletedAt).isEqualTo(at)
        }
}
