package com.itsluminous.samaroh.feature.notes.share

import android.content.Context
import android.content.Intent
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Note
import com.itsluminous.samaroh.core.model.NoteKind

/**
 * Plain-text rendering of a note for the system share sheet (ADR-077): title line,
 * then the body — a checklist renders every item with its state ("[x] Milk").
 * Pure so the exact output is unit-tested.
 */
object NoteShareText {
    fun render(note: Note): String {
        val lines = mutableListOf<String>()
        note.title?.takeIf { it.isNotBlank() }?.let { lines += it }
        if (note.kind == NoteKind.CHECKLIST) {
            note.checklist.forEach { item ->
                lines += if (item.done) "[x] ${item.text}" else "[ ] ${item.text}"
            }
        } else {
            note.content?.takeIf { it.isNotBlank() }?.let { lines += it }
        }
        return lines.joinToString("\n")
    }
}

/** ACTION_SEND plain-text launch through the system chooser (BookingShare pattern). */
object NotesShare {
    fun shareText(
        context: Context,
        text: String,
    ) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.common_action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
