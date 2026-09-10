package com.itsluminous.samaroh.feature.notes.share

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.feature.notes.noteFixture
import org.junit.Test

/** Plain-text share rendering (ADR-077): title line + body incl. checklist state. */
class NoteShareTextTest {
    @Test
    fun `plain note renders title and content`() {
        val text = NoteShareText.render(noteFixture("n-1", title = "Vendors", content = "Call the caterer\nBook the tent"))
        assertThat(text).isEqualTo("Vendors\nCall the caterer\nBook the tent")
    }

    @Test
    fun `checklist renders every item with its done state`() {
        val text =
            NoteShareText.render(
                noteFixture(
                    "n-2",
                    kind = NoteKind.CHECKLIST,
                    title = "Shopping",
                    checklist =
                        listOf(
                            NoteChecklistItem("i-1", "Marigold garlands", done = true),
                            NoteChecklistItem("i-2", "Diyas"),
                        ),
                ),
            )
        assertThat(text).isEqualTo("Shopping\n[x] Marigold garlands\n[ ] Diyas")
    }

    @Test
    fun `blank title and body are omitted`() {
        assertThat(NoteShareText.render(noteFixture("n-3", title = null, content = "only body"))).isEqualTo("only body")
        assertThat(NoteShareText.render(noteFixture("n-4", title = "only title", content = null))).isEqualTo("only title")
    }
}
