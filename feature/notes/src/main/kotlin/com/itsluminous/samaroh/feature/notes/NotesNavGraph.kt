package com.itsluminous.samaroh.feature.notes

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.feature.notes.home.NotesHomeScreen

/** Route of the Notes tab's destination (ADR-077). */
const val NOTES_ROUTE = "notes"

/**
 * Notes feature graph (ADR-077): one destination — the Keep-style home hosts the
 * drawer sections (Notes/Completed/Trash/tags) and the note popup itself, so no
 * nested NavHost is needed.
 */
fun NavGraphBuilder.notesGraph() {
    composable(NOTES_ROUTE) {
        // Help/assistant context (ADR-108).
        LaunchedEffect(Unit) { CurrentScreenTracker.setScreen(HelpScreenIds.NOTES) }
        NotesHomeScreen()
    }
}
