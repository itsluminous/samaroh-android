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
import com.itsluminous.samaroh.feature.notes.domain.ChecklistReorder
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
    /** The pinned bottom "Add item" field's text (ADR-082: items are add-only). */
    val newItemText: String = "",
    val colorKey: String? = null,
    val pinned: Boolean = false,
    val status: NoteStatus = NoteStatus.ACTIVE,
    /** Live tag selection of the note (edit mode diffs it into link upserts on save). */
    val tagIds: Set<String> = emptySet(),
    /** The tag row's type-ahead text as typed (the field value, updated per keystroke). */
    val tagQuery: String = "",
    /** The debounced tag query (fires ~300 ms after typing pauses) driving suggestions. */
    val debouncedTagQuery: String = "",
)

/** The manage-tags dialog's state (rename buffer + delete confirmation). */
data class ManageTagsState(
    /** Tag currently being renamed inline; null = no rename in progress. */
    val renameTagId: String? = null,
    val renameValue: String = "",
    /** True when the entered rename duplicates another live tag (case-insensitive). */
    val renameDuplicate: Boolean = false,
    /** Tag id pending the delete confirmation dialog (shows the linked-note count). */
    val confirmDeleteTagId: String? = null,
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
    /** Live linked-note count per tag id (the delete confirmation's N). */
    val tagLinkCounts: Map<String, Int> = emptyMap(),
    val canCreate: Boolean = true,
    val canEdit: Boolean = true,
    val canDelete: Boolean = true,
    /** Normalized `view_checklists` (absent inherits view — ADR-082). */
    val canViewChecklists: Boolean = true,
    /** Normalized `toggle_checklist` (absent inherits edit — ADR-082). */
    val canToggleChecklist: Boolean = true,
    val editor: NoteEditorState? = null,
    /** Note id pending the delete-forever confirmation dialog (Trash only). */
    val confirmPurgeId: String? = null,
    /** The manage-tags dialog; null = closed. */
    val manageTags: ManageTagsState? = null,
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
        private val manageTags = MutableStateFlow<ManageTagsState?>(null)

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

        private data class Gates(
            val create: Boolean,
            val edit: Boolean,
            val delete: Boolean,
            val viewChecklists: Boolean,
            val toggleChecklist: Boolean,
        )

        private val gates: Flow<Gates> =
            combine(
                session.canCreate,
                session.canEdit,
                session.canDelete,
                session.canViewChecklists,
                session.canToggleChecklist,
            ) { create, edit, delete, viewChecklists, toggleChecklist ->
                Gates(create, edit, delete, viewChecklists, toggleChecklist)
            }

        val state: StateFlow<NotesHomeState> =
            combine(
                notesData,
                combine(section, selectedTagId, searchQuery) { s, t, q -> Triple(s, t, q) },
                gates,
                combine(editor, confirmPurgeId, manageTags) { e, p, m -> Triple(e, p, m) },
            ) { data, filters, gates, dialogs ->
                val (currentSection, tagId, query) = filters
                val (editorState, purgeId, manageState) = dialogs
                // ADR-082 view_checklists enforcement: without the (normalized) grant,
                // checklists don't exist for this member — the grid, search, section
                // empty-states and drawer tag counts all work off the filtered cards.
                val cards =
                    if (gates.viewChecklists) data.cards else data.cards.filter { it.note.kind != NoteKind.CHECKLIST }
                val visibleNoteIds = cards.mapTo(mutableSetOf()) { it.note.id }
                val links = data.links.filter { it.noteId in visibleNoteIds }
                // The tag filter only shapes the main list; Completed/Trash show all.
                val effectiveTag = tagId.takeIf { currentSection == NotesSection.NOTES }
                val visible = NotesFilter.visible(cards, currentSection, effectiveTag, query)
                val (pinned, others) = NotesFilter.splitPinned(visible)
                NotesHomeState(
                    loaded = true,
                    section = currentSection,
                    selectedTagId = effectiveTag,
                    searchQuery = query,
                    pinned = pinned,
                    others = others,
                    sectionHasNotes = cards.any { NotesFilter.sectionOf(it.note) == currentSection },
                    tags = data.tags,
                    tagLinkCounts = links.groupBy { it.tagId }.mapValues { (_, links) -> links.map { it.noteId }.distinct().size },
                    canCreate = gates.create,
                    canEdit = gates.edit,
                    canDelete = gates.delete,
                    canViewChecklists = gates.viewChecklists,
                    canToggleChecklist = gates.toggleChecklist,
                    editor = liveEditor(editorState, data.cards),
                    confirmPurgeId = purgeId,
                    manageTags = manageState,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesHomeState())

        /**
         * The VIEW-mode popup observes the LIVE note (the Room flow), never the
         * snapshot taken when it opened: an inline checkbox toggle (or any concurrent
         * edit/sync pull) persists via the repository, and this overlay reflects it in
         * the open popup instantly — the stale-snapshot bug fix. Edit mode keeps its
         * buffer untouched (in-progress typing must never be clobbered); a note that
         * vanished mid-view (purged) keeps the last snapshot until dismissed.
         */
        private fun liveEditor(
            editorState: NoteEditorState?,
            cards: List<NoteCardData>,
        ): NoteEditorState? {
            if (editorState == null || editorState.editing || editorState.noteId == null) return editorState
            val card = cards.firstOrNull { it.note.id == editorState.noteId } ?: return editorState
            return editorState.copy(
                kind = card.note.kind,
                title = card.note.title.orEmpty(),
                content = card.note.content.orEmpty(),
                items = card.note.checklist.map { EditorChecklistItem(it.id, it.text, it.done) },
                colorKey = card.note.color,
                pinned = card.note.pinned,
                status = card.note.status,
                tagIds = card.tags.map { it.id }.toSet(),
            )
        }

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
            // Create checklist additionally needs the (normalized) view_checklists
            // grant (ADR-082): a member who can't SEE checklists must not create one
            // they'd never find again. The UI hides the button; this re-guards.
            if (kind == NoteKind.CHECKLIST && !state.value.canViewChecklists) return
            editor.value =
                NoteEditorState(
                    noteId = null,
                    kind = kind,
                    editing = true,
                )
        }

        fun startEditing() {
            if (!state.value.canEdit) return
            // Seed the edit buffer from the STATE's editor — the live-note overlay —
            // not the raw snapshot flow, so edits start from what the popup shows.
            editor.value = state.value.editor?.copy(editing = true)
        }

        fun dismissEditor() {
            editor.value = null
        }

        fun setEditorTitle(value: String) = editor.update { it?.copy(title = value) }

        fun setEditorContent(value: String) = editor.update { it?.copy(content = value) }

        fun setEditorColor(colorKey: String?) = editor.update { it?.copy(colorKey = colorKey) }

        fun toggleEditorPin() = editor.update { it?.copy(pinned = !it.pinned) }

        /** The pinned bottom "Add item" field's text (ADR-082). */
        fun setNewItemText(value: String) = editor.update { it?.copy(newItemText = value) }

        /**
         * Enter / the add action on the bottom field (ADR-082): appends the trimmed
         * text as a new (non-editable) row and clears the field — the field keeps
         * focus so the next item types straight in. Blank input is a no-op.
         */
        fun commitNewItem() =
            editor.update { state ->
                state ?: return@update null
                val text = state.newItemText.trim()
                if (text.isEmpty()) {
                    state
                } else {
                    state.copy(items = state.items + newItem(text), newItemText = "")
                }
            }

        fun toggleEditorChecklistItem(itemId: String) =
            editor.update { state ->
                state?.copy(items = state.items.map { if (it.id == itemId) it.copy(done = !it.done) else it })
            }

        fun removeChecklistItem(itemId: String) = editor.update { state -> state?.copy(items = state.items.filterNot { it.id == itemId }) }

        /**
         * Drag reorder (ADR-082, replaces ADR-081's checkbox-handle variant): moves
         * the item to [toIndex] (clamped). Fired ONCE on drop — the whole-row
         * long-press drag tracks its target slot visually (midpoint crossing) and
         * commits the final position when the finger lifts.
         */
        fun moveChecklistItem(
            itemId: String,
            toIndex: Int,
        ) = editor.update { state ->
            state ?: return@update null
            val from = state.items.indexOfFirst { it.id == itemId }
            state.copy(items = ChecklistReorder.move(state.items, from, toIndex))
        }

        fun setTagQuery(value: String) = editor.update { it?.copy(tagQuery = value) }

        /** Debounced type-ahead query (fired by the field ~300 ms after typing pauses). */
        fun setDebouncedTagQuery(value: String) = editor.update { it?.copy(debouncedTagQuery = value) }

        /** Toggle an existing tag on the note being edited. */
        fun toggleEditorTag(tagId: String) =
            editor.update { state ->
                state?.copy(
                    tagIds = if (tagId in state.tagIds) state.tagIds - tagId else state.tagIds + tagId,
                    tagQuery = "",
                    debouncedTagQuery = "",
                )
            }

        /**
         * A type-ahead suggestion tap: selects the live tag with that name, or creates
         * it on the fly when none matches (the Create "x" suggestion path).
         */
        fun selectTagSuggestion(name: String) {
            val existing = state.value.tags.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            if (existing != null) {
                editor.update { it?.copy(tagIds = it.tagIds + existing.id, tagQuery = "", debouncedTagQuery = "") }
            } else {
                createTagNamed(name)
            }
        }

        /**
         * Create-on-the-fly (ADR-077): persists the tag immediately and selects it on
         * the note being edited. Reuses a live tag whose name matches
         * case-insensitively — names are unique per business among live rows.
         */
        fun createTagNamed(rawName: String) {
            if (editor.value == null) return
            val name = rawName.trim()
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
                editor.update { it?.copy(tagIds = it.tagIds + tag.id, tagQuery = "", debouncedTagQuery = "") }
            }
        }

        /** Legacy entry point (pre-type-ahead "New tag" button); creates from the typed text. */
        fun createTagFromQuery() {
            val name = editor.value?.tagQuery?.trim() ?: return
            createTagNamed(name)
        }

        // ---- manage tags (drawer surface: rename + delete, notes.edit-gated) ----

        fun openManageTags() {
            if (!state.value.canEdit) return
            manageTags.value = ManageTagsState()
        }

        fun dismissManageTags() {
            manageTags.value = null
        }

        /** Start renaming a tag inline (prefills the current name). */
        fun startRenameTag(tagId: String) {
            val tag = state.value.tags.firstOrNull { it.id == tagId } ?: return
            manageTags.update { it?.copy(renameTagId = tagId, renameValue = tag.name, renameDuplicate = false) }
        }

        fun setRenameValue(value: String) = manageTags.update { it?.copy(renameValue = value, renameDuplicate = false) }

        fun cancelRenameTag() = manageTags.update { it?.copy(renameTagId = null, renameValue = "", renameDuplicate = false) }

        /**
         * Commit a rename: rejected with an inline duplicate error when another live
         * tag already carries the name (case-insensitive — live names are unique per
         * business); a blank or unchanged name is a no-op close.
         */
        fun confirmRenameTag() {
            val manage = manageTags.value ?: return
            val tagId = manage.renameTagId ?: return
            if (!state.value.canEdit) return
            val tag = state.value.tags.firstOrNull { it.id == tagId } ?: return
            val name = manage.renameValue.trim()
            if (name.isEmpty() || name == tag.name) {
                cancelRenameTag()
                return
            }
            val duplicate =
                state.value.tags.any { it.id != tagId && it.name.equals(name, ignoreCase = true) }
            if (duplicate) {
                manageTags.update { it?.copy(renameDuplicate = true) }
                return
            }
            viewModelScope.launch {
                repository.saveTag(tag.copy(name = name, updatedAt = clock.instant()))
                cancelRenameTag()
            }
        }

        fun requestDeleteTag(tagId: String) {
            if (!state.value.canEdit) return
            manageTags.update { it?.copy(confirmDeleteTagId = tagId) }
        }

        fun dismissDeleteTag() = manageTags.update { it?.copy(confirmDeleteTagId = null) }

        /**
         * Confirmed tag delete: tombstones the tag AND soft-unlinks every live link
         * (notes themselves untouched). Clears an active drawer filter on that tag.
         */
        fun confirmDeleteTag() {
            val tagId = manageTags.value?.confirmDeleteTagId ?: return
            manageTags.update { it?.copy(confirmDeleteTagId = null) }
            if (!state.value.canEdit) return
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val tag = repository.tags(businessId).first().firstOrNull { it.id == tagId } ?: return@launch
                val now = clock.instant()
                repository.tagLinks(businessId).first().filter { it.tagId == tagId }.forEach { link ->
                    repository.saveTagLink(link.copy(updatedAt = now, deletedAt = now))
                }
                repository.saveTag(tag.copy(updatedAt = now, deletedAt = now))
                if (selectedTagId.value == tagId) selectedTagId.value = null
                // Drop the deleted tag from an open editor's selection buffer too.
                editor.update { it?.copy(tagIds = it.tagIds - tagId) }
            }
        }

        /** Save button of the edit mode: upserts the note and diffs the tag links. */
        fun saveEditor() {
            // Fold a typed-but-not-entered "Add item" text in first (ADR-082): Save
            // must never silently drop what's sitting in the bottom field.
            commitNewItem()
            val current = editor.value ?: return
            if (!current.editing) return
            val creating = current.noteId == null
            if (creating && !state.value.canCreate) return
            if (!creating && !state.value.canEdit) return
            // PHANTOM-CARD GUARD: a brand-new note with no title, no content and no
            // non-blank checklist item would render as an empty grey card in the grid —
            // treat Save as a dismiss instead of persisting an empty husk.
            val hasSubstance =
                current.title.isNotBlank() ||
                    (current.kind == NoteKind.NOTE && current.content.isNotBlank()) ||
                    (current.kind == NoteKind.CHECKLIST && current.items.any { it.text.isNotBlank() })
            if (creating && !hasSubstance) {
                editor.value = null
                return
            }
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

        /**
         * Inline checkbox on a checklist CARD (and the view-mode popup) — gated on
         * the NORMALIZED `toggle_checklist` (absent inherits edit — ADR-082), the one
         * mutation a non-editor member may hold.
         */
        fun toggleChecklistItemInline(
            noteId: String,
            itemId: String,
        ) {
            if (!state.value.canToggleChecklist) return
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
                // The open view popup reflects this via the live-note overlay.
                mutate(note.copy(pinned = !note.pinned))
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

        private fun newItem(text: String) = EditorChecklistItem(id = UUID.randomUUID().toString(), text = text, done = false)
    }
