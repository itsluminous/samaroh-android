package com.itsluminous.samaroh.feature.notes.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.repository.NotesRepository
import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NoteTag
import com.itsluminous.samaroh.core.model.NoteTagLink
import com.itsluminous.samaroh.feature.notes.NotesSession
import com.itsluminous.samaroh.feature.notes.domain.NoteCardData
import com.itsluminous.samaroh.feature.notes.domain.NotesFilter
import com.itsluminous.samaroh.feature.notes.domain.NotesSection
import com.itsluminous.samaroh.feature.notes.share.NoteShareText
import com.itsluminous.samaroh.feature.notes.sweep.NotesTrashSweeper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** One checklist row in the editor buffer (text editable, id stable across edits). */
data class EditorChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean,
)

/**
 * The note popup's state (ADR-077): a VIEW of an existing note or an EDIT buffer.
 * [noteId] null = creating a brand-new note; [editing] false = read-only popup
 * (actions row) — [com.itsluminous.samaroh.feature.notes.NotesSession] gates decide
 * which actions render.
 */
data class NoteEditorState(
    val noteId: String? = null,
    val kind: NoteKind = NoteKind.NOTE,
    val editing: Boolean = false,
    val title: String = "",
    val content: String = "",
    val items: List<EditorChecklistItem> = emptyList(),
    val colorKey: String? = null,
    val pinned: Boolean = false,
    val status: NoteStatus = NoteStatus.ACTIVE,
    /** Live tag selection of the note (edit mode diffs it into link upserts on save). */
    val tagIds: Set<String> = emptySet(),
    /** The tag row's type-ahead text (filters existing tags; "New tag" creates it). */
    val tagQuery: String = "",
)

data class NotesHomeState(
    val loaded: Boolean = false,
    val section: NotesSection = NotesSection.NOTES,
    /** Drawer tag filter (NOTES section only); null = no filter. */
    val selectedTagId: String? = null,
    val searchQuery: String = "",
    val pinned: List<NoteCardData> = emptyList(),
    val others: List<NoteCardData> = emptyList(),
    /** Whether ANY note exists in the section pre-search (drives which empty state shows). */
    val sectionHasNotes: Boolean = false,
    val tags: List<NoteTag> = emptyList(),
    val canCreate: Boolean = true,
    val canEdit: Boolean = true,
    val canDelete: Boolean = true,
    val editor: NoteEditorState? = null,
    /** Note id pending the delete-forever confirmation dialog (Trash only). */
    val confirmPurgeId: String? = null,
)

/** One-shot UI events. */
sealed interface NotesEvent {
    data class ShareText(
        val text: String,
    ) : NotesEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesHomeViewModel
    @Inject
    constructor(
        private val repository: NotesRepository,
        private val session: NotesSession,
        /** Booking colour palette (ADR-030) — note card backgrounds + the picker. */
        val bookingColorsProvider: BookingColorCatalog,
        private val sweeper: NotesTrashSweeper,
        private val clock: Clock,
    ) : ViewModel() {
        private val section = MutableStateFlow(NotesSection.NOTES)
        private val selectedTagId = MutableStateFlow<String?>(null)
        private val searchQuery = MutableStateFlow("")
        private val editor = MutableStateFlow<NoteEditorState?>(null)
        private val confirmPurgeId = MutableStateFlow<String?>(null)

        private val events = Channel<NotesEvent>(Channel.BUFFERED)
        val eventFlow: Flow<NotesEvent> = events.receiveAsFlow()

        init {
            // App-start / tab-open leg of the 30-day Trash purge (ADR-077); the
            // post-sync hook covers synced installs, this covers offline-only ones.
            viewModelScope.launch { sweeper.sweep() }
        }

        private data class NotesData(
            val cards: List<NoteCardData>,
            val tags: List<NoteTag>,
            val links: List<NoteTagLink>,
        )

        private val notesData: Flow<NotesData> =
            session.businessIdFlow.flatMapLatest { businessId ->
                if (businessId == null) {
                    flowOf(NotesData(emptyList(), emptyList(), emptyList()))
                } else {
                    combine(
                        repository.notes(businessId),
                        repository.tags(businessId),
                        repository.tagLinks(businessId),
                    ) { notes, tags, links ->
                        NotesData(NotesFilter.withTags(notes, tags, links), tags, links)
                    }
                }
            }

        private val gates: Flow<Triple<Boolean, Boolean, Boolean>> =
            combine(session.canCreate, session.canEdit, session.canDelete) { create, edit, delete ->
                Triple(create, edit, delete)
            }

        val state: StateFlow<NotesHomeState> =
            combine(
                notesData,
                combine(section, selectedTagId, searchQuery) { s, t, q -> Triple(s, t, q) },
                gates,
                editor,
                confirmPurgeId,
            ) { data, filters, gates, editorState, purgeId ->
                val (currentSection, tagId, query) = filters
                // The tag filter only shapes the main list; Completed/Trash show all.
                val effectiveTag = tagId.takeIf { currentSection == NotesSection.NOTES }
                val visible = NotesFilter.visible(data.cards, currentSection, effectiveTag, query)
                val (pinned, others) = NotesFilter.splitPinned(visible)
                NotesHomeState(
                    loaded = true,
                    section = currentSection,
                    selectedTagId = effectiveTag,
                    searchQuery = query,
                    pinned = pinned,
                    others = others,
                    sectionHasNotes = data.cards.any { NotesFilter.sectionOf(it.note) == currentSection },
                    tags = data.tags,
                    canCreate = gates.first,
                    canEdit = gates.second,
                    canDelete = gates.third,
                    editor = editorState,
                    confirmPurgeId = purgeId,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesHomeState())

        // ---- list shaping ----

        fun setSearchQuery(query: String) {
            searchQuery.value = query
        }

        fun selectSection(target: NotesSection) {
            section.value = target
            selectedTagId.value = null
        }

        /** Drawer tag tap: filter the main list; tapping the active tag clears it. */
        fun selectTag(tagId: String?) {
            section.value = NotesSection.NOTES
            selectedTagId.value = if (selectedTagId.value == tagId) null else tagId
        }

        // ---- popup (view / edit) ----

        fun openNote(noteId: String) {
            val card = state.value.let { it.pinned + it.others }.firstOrNull { it.note.id == noteId } ?: return
            editor.value = editorFrom(card, editing = false)
        }

        fun startCreate(kind: NoteKind) {
            if (!state.value.canCreate) return
            editor.value =
                NoteEditorState(
                    noteId = null,
                    kind = kind,
                    editing = true,
                    items = if (kind == NoteKind.CHECKLIST) listOf(newItem()) else emptyList(),
                )
        }

        fun startEditing() {
            if (!state.value.canEdit) return
            editor.update { it?.copy(editing = true) }
        }

        fun dismissEditor() {
            editor.value = null
        }

        fun setEditorTitle(value: String) = editor.update { it?.copy(title = value) }

        fun setEditorContent(value: String) = editor.update { it?.copy(content = value) }

        fun setEditorColor(colorKey: String?) = editor.update { it?.copy(colorKey = colorKey) }

        fun toggleEditorPin() = editor.update { it?.copy(pinned = !it.pinned) }

        fun addChecklistItem() = editor.update { it?.copy(items = it.items + newItem()) }

        fun setChecklistItemText(
            itemId: String,
            text: String,
        ) = editor.update { state ->
            state?.copy(items = state.items.map { if (it.id == itemId) it.copy(text = text) else it })
        }

        fun toggleEditorChecklistItem(itemId: String) =
            editor.update { state ->
                state?.copy(items = state.items.map { if (it.id == itemId) it.copy(done = !it.done) else it })
            }

        fun removeChecklistItem(itemId: String) = editor.update { state -> state?.copy(items = state.items.filterNot { it.id == itemId }) }

        /** Simple reorder (ADR-077): move the item one position up. */
        fun moveChecklistItemUp(itemId: String) =
            editor.update { state ->
                state ?: return@update null
                val index = state.items.indexOfFirst { it.id == itemId }
                if (index <= 0) return@update state
                val items = state.items.toMutableList()
                val above = items[index - 1]
                items[index - 1] = items[index]
                items[index] = above
                state.copy(items = items)
            }

        fun setTagQuery(value: String) = editor.update { it?.copy(tagQuery = value) }

        /** Toggle an existing tag on the note being edited. */
        fun toggleEditorTag(tagId: String) =
            editor.update { state ->
                state?.copy(
                    tagIds = if (tagId in state.tagIds) state.tagIds - tagId else state.tagIds + tagId,
                    tagQuery = "",
                )
            }

        /**
         * "New tag" (create on the fly, ADR-077): persists the tag immediately (the
         * type-ahead lists it live) and selects it on the note being edited. Reuses a
         * live tag whose name matches case-insensitively — names are unique per
         * business among live rows.
         */
        fun createTagFromQuery() {
            val current = editor.value ?: return
            val name = current.tagQuery.trim()
            if (name.isEmpty()) return
            if (!state.value.canEdit && !state.value.canCreate) return
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val existing = repository.tags(businessId).first().firstOrNull { it.name.equals(name, ignoreCase = true) }
                val tag =
                    existing ?: NoteTag(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        name = name,
                        createdAt = clock.instant(),
                        updatedAt = clock.instant(),
                    ).also { repository.saveTag(it) }
                editor.update { it?.copy(tagIds = it.tagIds + tag.id, tagQuery = "") }
            }
        }

        /** Save button of the edit mode: upserts the note and diffs the tag links. */
        fun saveEditor() {
            val current = editor.value ?: return
            if (!current.editing) return
            val creating = current.noteId == null
            if (creating && !state.value.canCreate) return
            if (!creating && !state.value.canEdit) return
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val userId = session.userId() ?: return@launch
                val now = clock.instant()
                val existing = current.noteId?.let { repository.note(it) }
                val note =
                    Note(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        businessId = businessId,
                        kind = current.kind,
                        title = current.title.trim().ifEmpty { null },
                        content =
                            if (current.kind == NoteKind.NOTE) current.content.trim().ifEmpty { null } else existing?.content,
                        checklist =
                            if (current.kind == NoteKind.CHECKLIST) {
                                current.items.filter { it.text.isNotBlank() }.map { NoteChecklistItem(it.id, it.text.trim(), it.done) }
                            } else {
                                existing?.checklist.orEmpty()
                            },
                        color = current.colorKey,
                        pinned = current.pinned,
                        status = existing?.status ?: NoteStatus.ACTIVE,
                        completedAt = existing?.completedAt,
                        trashedAt = existing?.trashedAt,
                        createdBy = existing?.createdBy ?: userId,
                        updatedBy = userId,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        deletedAt = null,
                    )
                repository.saveNote(note)
                syncTagLinks(businessId, note.id, current.tagIds, now)
                editor.value = null
            }
        }

        /** Diffs the editor's tag selection into link upserts / soft unlinks (ADR-077). */
        private suspend fun syncTagLinks(
            businessId: String,
            noteId: String,
            wanted: Set<String>,
            at: java.time.Instant,
        ) {
            val liveLinks = repository.tagLinks(businessId).first().filter { it.noteId == noteId }
            val live = liveLinks.map { it.tagId }.toSet()
            (wanted - live).forEach { tagId ->
                repository.saveTagLink(
                    NoteTagLink(noteId = noteId, tagId = tagId, businessId = businessId, createdAt = at, updatedAt = at),
                )
            }
            liveLinks.filter { it.tagId !in wanted }.forEach { link ->
                // Soft unlink: the composite-PK row is reused on a later retag.
                repository.saveTagLink(link.copy(updatedAt = at, deletedAt = at))
            }
        }

        // ---- card / popup quick actions (all notes.edit-gated status updates) ----

        /** Inline checkbox on a checklist CARD (and the view-mode popup). */
        fun toggleChecklistItemInline(
            noteId: String,
            itemId: String,
        ) {
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                mutate(
                    note.copy(checklist = note.checklist.map { if (it.id == itemId) it.copy(done = !it.done) else it }),
                )
            }
        }

        fun togglePin(noteId: String) {
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                mutate(note.copy(pinned = !note.pinned))
                editor.update { if (it?.noteId == noteId) it.copy(pinned = !note.pinned) else it }
            }
        }

        /** Complete → hidden from the main list, visible under Completed (ADR-077). */
        fun completeNote(noteId: String) {
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                mutate(note.copy(status = NoteStatus.COMPLETED, completedAt = clock.instant()))
                editor.value = null
            }
        }

        /** Un-complete / un-trash: back to the active Notes list. */
        fun restoreNote(noteId: String) {
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                mutate(note.copy(status = NoteStatus.ACTIVE, completedAt = null, trashedAt = null))
                editor.value = null
            }
        }

        /** Delete on an active/completed note → Trash with the purge anchor (ADR-077). */
        fun trashNote(noteId: String) {
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                mutate(note.copy(status = NoteStatus.TRASHED, trashedAt = clock.instant()))
                editor.value = null
            }
        }

        /** Delete in Trash = delete forever; asks for confirmation first. */
        fun requestPurge(noteId: String) {
            if (!state.value.canDelete) return
            confirmPurgeId.value = noteId
        }

        fun dismissPurge() {
            confirmPurgeId.value = null
        }

        fun confirmPurge() {
            val noteId = confirmPurgeId.value ?: return
            confirmPurgeId.value = null
            if (!state.value.canDelete) return
            viewModelScope.launch {
                repository.purgeNote(noteId)
                editor.value = null
            }
        }

        fun shareNote(noteId: String) {
            viewModelScope.launch {
                val note = repository.note(noteId) ?: return@launch
                events.trySend(NotesEvent.ShareText(NoteShareText.render(note)))
            }
        }

        // ---- helpers ----

        private suspend fun mutate(note: Note) {
            val userId = session.userId() ?: return
            repository.saveNote(note.copy(updatedBy = userId, updatedAt = clock.instant()))
        }

        private fun editorFrom(
            card: NoteCardData,
            editing: Boolean,
        ): NoteEditorState =
            NoteEditorState(
                noteId = card.note.id,
                kind = card.note.kind,
                editing = editing,
                title = card.note.title.orEmpty(),
                content = card.note.content.orEmpty(),
                items = card.note.checklist.map { EditorChecklistItem(it.id, it.text, it.done) },
                colorKey = card.note.color,
                pinned = card.note.pinned,
                status = card.note.status,
                tagIds = card.tags.map { it.id }.toSet(),
            )

        private fun newItem() = EditorChecklistItem(id = UUID.randomUUID().toString(), text = "", done = false)
    }
