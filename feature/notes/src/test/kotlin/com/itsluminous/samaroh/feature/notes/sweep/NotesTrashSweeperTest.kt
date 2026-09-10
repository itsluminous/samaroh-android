package com.itsluminous.samaroh.feature.notes.sweep

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NotesPermissions
import com.itsluminous.samaroh.feature.notes.FakeNotesRepository
import com.itsluminous.samaroh.feature.notes.fakeNotesSession
import com.itsluminous.samaroh.feature.notes.noteFixture
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 30-day Trash purge sweep (ADR-077): cutoff, permission gate, no-business no-op. */
class NotesTrashSweeperTest {
    private val now: Instant = Instant.parse("2026-09-10T06:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = FakeNotesRepository()

    private fun seedTrash() {
        repository.notesFlow.value =
            listOf(
                // 31 days ago — expired.
                noteFixture("expired", status = NoteStatus.TRASHED, trashedAt = now.minusSeconds(31L * 24 * 3600)),
                // 29 days ago — keeps waiting.
                noteFixture("fresh", status = NoteStatus.TRASHED, trashedAt = now.minusSeconds(29L * 24 * 3600)),
                noteFixture("active"),
            )
    }

    @Test
    fun `owners purge trash older than 30 days only`() =
        runTest {
            seedTrash()
            val sweeper = NotesTrashSweeper(repository, fakeNotesSession(isOwner = true), clock)

            assertThat(sweeper.sweep()).isEqualTo(1)
            assertThat(repository.purgedNoteIds).containsExactly("expired")
        }

    @Test
    fun `members without notes delete never purge`() =
        runTest {
            seedTrash()
            val session =
                fakeNotesSession(
                    userId = "member-1",
                    isOwner = false,
                    permissions = MemberPermissions(notes = NotesPermissions(view = true, create = true, edit = true)),
                )

            assertThat(NotesTrashSweeper(repository, session, clock).sweep()).isEqualTo(0)
            assertThat(repository.purgedNoteIds).isEmpty()
        }

    @Test
    fun `members with notes delete purge like owners`() =
        runTest {
            seedTrash()
            val session =
                fakeNotesSession(
                    userId = "member-1",
                    isOwner = false,
                    permissions = MemberPermissions(notes = NotesPermissions(view = true, delete = true)),
                )

            assertThat(NotesTrashSweeper(repository, session, clock).sweep()).isEqualTo(1)
        }

    @Test
    fun `no active business is a no-op`() =
        runTest {
            seedTrash()
            val sweeper = NotesTrashSweeper(repository, fakeNotesSession(business = null), clock)

            assertThat(sweeper.sweep()).isEqualTo(0)
            assertThat(repository.purgedNoteIds).isEmpty()
        }
}
