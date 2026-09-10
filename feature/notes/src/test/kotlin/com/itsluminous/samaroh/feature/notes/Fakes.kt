package com.itsluminous.samaroh.feature.notes

import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.repository.NotesRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NoteTag
import com.itsluminous.samaroh.core.model.NoteTagLink
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

// In-memory fakes for the Notes ViewModel/logic tests — no Room, no network.

class FakeNotesRepository : NotesRepository {
    val notesFlow = MutableStateFlow<List<Note>>(emptyList())
    val tagsFlow = MutableStateFlow<List<NoteTag>>(emptyList())
    val linksFlow = MutableStateFlow<List<NoteTagLink>>(emptyList())
    val purgedNoteIds = mutableListOf<String>()

    override fun notes(businessId: String): Flow<List<Note>> =
        notesFlow.map { list ->
            list
                .filter { it.businessId == businessId && it.deletedAt == null }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
        }

    override suspend fun note(id: String): Note? = notesFlow.value.firstOrNull { it.id == id && it.deletedAt == null }

    override fun tags(businessId: String): Flow<List<NoteTag>> =
        tagsFlow.map { list ->
            list.filter { it.businessId == businessId && it.deletedAt == null }.sortedBy { it.name.lowercase() }
        }

    override fun tagLinks(businessId: String): Flow<List<NoteTagLink>> =
        linksFlow.map { list -> list.filter { it.businessId == businessId && it.deletedAt == null } }

    override suspend fun saveNote(note: Note) {
        notesFlow.value = notesFlow.value.filterNot { it.id == note.id } + note
    }

    override suspend fun purgeNote(id: String) {
        purgedNoteIds += id
        notesFlow.value =
            notesFlow.value.map {
                if (it.id == id) it.copy(deletedAt = Instant.parse("2026-09-10T09:00:00Z")) else it
            }
    }

    override suspend fun saveTag(tag: NoteTag) {
        tagsFlow.value = tagsFlow.value.filterNot { it.id == tag.id } + tag
    }

    override suspend fun saveTagLink(link: NoteTagLink) {
        linksFlow.value = linksFlow.value.filterNot { it.noteId == link.noteId && it.tagId == link.tagId } + link
    }

    override suspend fun purgeTrashedBefore(
        businessId: String,
        cutoff: Instant,
    ): Int {
        val expired =
            notesFlow.value.filter {
                it.businessId == businessId &&
                    it.deletedAt == null &&
                    it.status == NoteStatus.TRASHED &&
                    it.trashedAt != null &&
                    it.trashedAt!!.isBefore(cutoff)
            }
        expired.forEach { purgeNote(it.id) }
        return expired.size
    }
}

fun fakeNotesSession(
    business: Business? = Fixtures.business(),
    userId: String? = null,
    isOwner: Boolean = true,
    permissions: MemberPermissions = MemberPermissions(),
): NotesSession =
    NotesSession(
        activeBusinessProvider =
            object : ActiveBusinessProvider {
                override val activeBusiness = MutableStateFlow(business)
            },
        currentUserProvider =
            object : CurrentUserProvider {
                override val currentUserId = MutableStateFlow(userId)
            },
        permissionGuard =
            object : PermissionGuard {
                override fun permissions(businessId: String) = MutableStateFlow(permissions)

                override fun isOwner(businessId: String) = MutableStateFlow(isOwner)
            },
    )

class FakeBookingColorCatalog : BookingColorCatalog {
    override val colors: List<BookingColor> =
        listOf(
            BookingColor(key = "tomato", hex = "#D50000", onHex = "#FFFFFF", labelRes = 1),
            BookingColor(key = "peacock", hex = "#039BE5", onHex = "#000000", labelRes = 2),
        )
}

fun noteFixture(
    id: String,
    businessId: String = Fixtures.BUSINESS_ID,
    kind: NoteKind = NoteKind.NOTE,
    title: String? = "note $id",
    content: String? = null,
    checklist: List<NoteChecklistItem> = emptyList(),
    pinned: Boolean = false,
    status: NoteStatus = NoteStatus.ACTIVE,
    trashedAt: Instant? = null,
    updatedAt: Instant = Fixtures.NOW,
): Note =
    Note(
        id = id,
        businessId = businessId,
        kind = kind,
        title = title,
        content = content,
        checklist = checklist,
        pinned = pinned,
        status = status,
        trashedAt = trashedAt,
        createdBy = Fixtures.USER_ID,
        createdAt = Fixtures.NOW,
        updatedAt = updatedAt,
    )

fun tagFixture(
    id: String,
    name: String,
    businessId: String = Fixtures.BUSINESS_ID,
): NoteTag = NoteTag(id = id, businessId = businessId, name = name, createdAt = Fixtures.NOW, updatedAt = Fixtures.NOW)

fun linkFixture(
    noteId: String,
    tagId: String,
    businessId: String = Fixtures.BUSINESS_ID,
): NoteTagLink = NoteTagLink(noteId = noteId, tagId = tagId, businessId = businessId, createdAt = Fixtures.NOW, updatedAt = Fixtures.NOW)
