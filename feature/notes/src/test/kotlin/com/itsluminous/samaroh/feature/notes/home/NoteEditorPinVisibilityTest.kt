package com.itsluminous.samaroh.feature.notes.home

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.NoteStatus
import org.junit.Test

/**
 * Pin-toggle surfaces in the note popup header (ADR-081): never in the CREATE popup,
 * kept for existing notes in both view and edit mode.
 */
class NoteEditorPinVisibilityTest {
    @Test
    fun `create popup never shows the pin toggle`() {
        val creating = NoteEditorState(noteId = null, editing = true)
        assertThat(pinToggleVisible(canEdit = true, editor = creating)).isFalse()
    }

    @Test
    fun `existing note shows the pin toggle in view and edit mode`() {
        val viewing = NoteEditorState(noteId = "n-1", editing = false)
        val editing = NoteEditorState(noteId = "n-1", editing = true)
        assertThat(pinToggleVisible(canEdit = true, editor = viewing)).isTrue()
        assertThat(pinToggleVisible(canEdit = true, editor = editing)).isTrue()
    }

    @Test
    fun `pin toggle hidden without edit permission or off the active status`() {
        val viewing = NoteEditorState(noteId = "n-1", editing = false)
        assertThat(pinToggleVisible(canEdit = false, editor = viewing)).isFalse()
        assertThat(pinToggleVisible(canEdit = true, editor = viewing.copy(status = NoteStatus.COMPLETED))).isFalse()
        assertThat(pinToggleVisible(canEdit = true, editor = viewing.copy(status = NoteStatus.TRASHED))).isFalse()
    }
}
