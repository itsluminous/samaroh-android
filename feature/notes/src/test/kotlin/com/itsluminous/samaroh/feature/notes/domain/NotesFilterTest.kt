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

    // ---- tag type-ahead suggestions (ADR-079) ----

    private fun suggestionTags() =
        listOf(
            tagFixture("t-urgent", "Urgent"),
            tagFixture("t-shopping", "Shopping"),
            tagFixture("t-shop-fittings", "Shop fittings"),
            tagFixture("t-staff", "Staff"),
        )

    @Test
    fun `blank tag query suggests nothing - never a full listing`() {
        assertThat(NotesFilter.tagSuggestions(suggestionTags(), emptySet(), "")).isEmpty()
        assertThat(NotesFilter.tagSuggestions(suggestionTags(), emptySet(), "   ")).isEmpty()
    }

    @Test
    fun `tag suggestions match name substrings case-insensitively`() {
        assertThat(NotesFilter.tagSuggestions(suggestionTags(), emptySet(), "SHOP").map { it.id })
            .containsExactly("t-shopping", "t-shop-fittings")
            .inOrder()
        assertThat(NotesFilter.tagSuggestions(suggestionTags(), emptySet(), "urg").map { it.id })
            .containsExactly("t-urgent")
    }

    @Test
    fun `tag suggestions exclude already-selected tags`() {
        assertThat(
            NotesFilter.tagSuggestions(suggestionTags(), setOf("t-shopping"), "shop").map { it.id },
        ).containsExactly("t-shop-fittings")
    }

    @Test
    fun `tag suggestions respect the limit`() {
        val many = (1..20).map { tagFixture("t-$it", "Tag $it") }
        assertThat(NotesFilter.tagSuggestions(many, emptySet(), "Tag")).hasSize(NotesFilter.TAG_SUGGESTION_LIMIT)
        assertThat(NotesFilter.tagSuggestions(many, emptySet(), "Tag", limit = 3)).hasSize(3)
    }

    // ---- checklist preview (phantom-row guard, ADR-079) ----

    @Test
    fun `checklist preview drops blank-text items`() {
        val note =
            noteFixture(
                id = "n-preview",
                kind = NoteKind.CHECKLIST,
                checklist =
                    listOf(
                        NoteChecklistItem("i-1", "Milk", done = false),
                        NoteChecklistItem("i-2", "", done = false),
                        NoteChecklistItem("i-3", "  ", done = true),
                        NoteChecklistItem("i-4", "Diyas", done = true),
                    ),
            )
        assertThat(NotesFilter.checklistPreview(note).map { it.id }).containsExactly("i-1", "i-4").inOrder()
    }
}
