package com.itsluminous.samaroh.feature.notes.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Tags" section label in the note popup (feedback batch): EDIT mode only — there it
 * heads the removable chips + type-ahead as one "manage tags" section. The VIEW popup
 * shows the disabled chips bare (self-evident as tags; a label above an untagged
 * note's empty chip row would dangle).
 */
class NoteEditorTagsLabelTest {
    @Test
    fun `view popup hides the tags section label`() {
        val viewing = NoteEditorState(noteId = "n-1", editing = false)
        assertThat(tagsSectionLabelVisible(viewing)).isFalse()
    }

    @Test
    fun `edit and create popups keep the tags section label`() {
        val editing = NoteEditorState(noteId = "n-1", editing = true)
        val creating = NoteEditorState(noteId = null, editing = true)
        assertThat(tagsSectionLabelVisible(editing)).isTrue()
        assertThat(tagsSectionLabelVisible(creating)).isTrue()
    }
}
