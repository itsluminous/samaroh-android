package com.itsluminous.samaroh.feature.notes.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.feature.notes.linkFixture
import com.itsluminous.samaroh.feature.notes.noteFixture
import com.itsluminous.samaroh.feature.notes.tagFixture
import org.junit.Test

/** Pure list shaping of the notes home (ADR-077). */
class NotesFilterTest {
    private val tags = listOf(tagFixture("t-urgent", "Urgent"), tagFixture("t-vendors", "Vendors"))

    private fun cards() =
        NotesFilter.withTags(
            notes =
                listOf(
                    noteFixture("n-active", title = "Hall booking follow-ups", content = "call the decorator"),
                    noteFixture(
                        "n-list",
                        kind = NoteKind.CHECKLIST,
                        title = "Shopping",
                        checklist = listOf(NoteChecklistItem("i-1", "Marigold garlands"), NoteChecklistItem("i-2", "Diyas", done = true)),
                        pinned = true,
                    ),
                    noteFixture("n-done", title = "Old menu", status = NoteStatus.COMPLETED),
                    noteFixture("n-gone", title = "Cancelled plan", status = NoteStatus.TRASHED),
                ),
            tags = tags,
            links = listOf(linkFixture("n-active", "t-urgent"), linkFixture("n-list", "t-vendors")),
        )

    @Test
    fun `sections split by status`() {
        val cards = cards()
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES).map { it.note.id })
            .containsExactly("n-active", "n-list")
        assertThat(NotesFilter.visible(cards, NotesSection.COMPLETED).map { it.note.id })
            .containsExactly("n-done")
        assertThat(NotesFilter.visible(cards, NotesSection.TRASH).map { it.note.id })
            .containsExactly("n-gone")
    }

    @Test
    fun `tags resolve through live links`() {
        val byId = cards().associateBy { it.note.id }
        assertThat(byId.getValue("n-active").tags.map { it.id }).containsExactly("t-urgent")
        assertThat(byId.getValue("n-list").tags.map { it.id }).containsExactly("t-vendors")
        assertThat(byId.getValue("n-done").tags).isEmpty()
    }

    @Test
    fun `tag filter narrows the section`() {
        val visible = NotesFilter.visible(cards(), NotesSection.NOTES, tagId = "t-vendors")
        assertThat(visible.map { it.note.id }).containsExactly("n-list")
    }

    @Test
    fun `search matches title content checklist items and tag names case-insensitively`() {
        val cards = cards()
        // Title.
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES, query = "SHOPPING").map { it.note.id })
            .containsExactly("n-list")
        // Plain content.
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES, query = "decorator").map { it.note.id })
            .containsExactly("n-active")
        // Checklist item text.
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES, query = "diyas").map { it.note.id })
            .containsExactly("n-list")
        // Tag name.
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES, query = "urgent").map { it.note.id })
            .containsExactly("n-active")
        // No match.
        assertThat(NotesFilter.visible(cards, NotesSection.NOTES, query = "zzz")).isEmpty()
    }

    @Test
    fun `pinned split keeps pinned notes first`() {
        val (pinned, others) = NotesFilter.splitPinned(NotesFilter.visible(cards(), NotesSection.NOTES))
        assertThat(pinned.map { it.note.id }).containsExactly("n-list")
        assertThat(others.map { it.note.id }).containsExactly("n-active")
    }
}
