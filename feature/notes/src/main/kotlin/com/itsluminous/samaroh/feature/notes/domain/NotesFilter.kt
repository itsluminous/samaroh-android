package com.itsluminous.samaroh.feature.notes.domain

import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NoteTag
import com.itsluminous.samaroh.core.model.NoteTagLink

/** Which drawer section the notes list shows (ADR-077). */
enum class NotesSection {
    NOTES,
    COMPLETED,
    TRASH,
}

/** One note card with its resolved live tags. */
data class NoteCardData(
    val note: Note,
    val tags: List<NoteTag>,
)

/**
 * Pure list shaping of the notes home (ADR-077): section filter → optional tag filter →
 * live search over title/content/checklist items/tag names → pinned-first split.
 * Kept free of Android/coroutines so the whole pipeline is unit-testable.
 */
object NotesFilter {
    /** The section a note belongs to; trashed wins over completed by status design. */
    fun sectionOf(note: Note): NotesSection =
        when (note.status) {
            NoteStatus.ACTIVE -> NotesSection.NOTES
            NoteStatus.COMPLETED -> NotesSection.COMPLETED
            NoteStatus.TRASHED -> NotesSection.TRASH
        }

    /** Resolves each note's live tags via the live links (soft-deleted rows excluded upstream). */
    fun withTags(
        notes: List<Note>,
        tags: List<NoteTag>,
        links: List<NoteTagLink>,
    ): List<NoteCardData> {
        val tagsById = tags.associateBy { it.id }
        val tagIdsByNote = links.groupBy({ it.noteId }, { it.tagId })
        return notes.map { note ->
            NoteCardData(
                note = note,
                tags = tagIdsByNote[note.id].orEmpty().mapNotNull { tagsById[it] }.sortedBy { it.name.lowercase() },
            )
        }
    }

    /**
     * The cards visible for ([section], [tagId], [query]). A tag filter applies on top
     * of the section (drawer tag taps filter the ACTIVE list); the search matches
     * title, plain content, checklist item texts and tag names, case-insensitively.
     */
    fun visible(
        cards: List<NoteCardData>,
        section: NotesSection,
        tagId: String? = null,
        query: String = "",
    ): List<NoteCardData> {
        val trimmed = query.trim()
        return cards
            .filter { sectionOf(it.note) == section }
            .filter { tagId == null || it.tags.any { tag -> tag.id == tagId } }
            .filter { trimmed.isEmpty() || matches(it, trimmed) }
    }

    /** Pinned cards first (grid section), then the rest — both keeping list order. */
    fun splitPinned(cards: List<NoteCardData>): Pair<List<NoteCardData>, List<NoteCardData>> = cards.partition { it.note.pinned }

    private fun matches(
        card: NoteCardData,
        query: String,
    ): Boolean =
        card.note.title
            .orEmpty()
            .contains(query, ignoreCase = true) ||
            card.note.content
                .orEmpty()
                .contains(query, ignoreCase = true) ||
            card.note.checklist.any { it.text.contains(query, ignoreCase = true) } ||
            card.tags.any { it.name.contains(query, ignoreCase = true) }
}
