package com.itsluminous.samaroh.feature.files

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.feature.files.ui.FilesScreen

/** Route of the Files tab's destination (ADR-085). */
const val FILES_ROUTE = "files"

/**
 * Files feature graph (ADR-085): one destination — folder navigation is in-memory
 * (breadcrumbs / Up / back), so no nested NavHost is needed.
 *
 * @param saveToFilesRequested the unified share chooser picked Save to Files (ADR-086):
 *   the screen shows the destination-folder picker over the parked payload; cleared via
 *   [onSaveToFilesConsumed]. A [State] read at composition time (ADR-099: the shell's
 *   root graph is built once).
 */
fun NavGraphBuilder.filesGraph(
    saveToFilesRequested: State<Boolean> = mutableStateOf(false),
    onSaveToFilesConsumed: () -> Unit = {},
) {
    composable(FILES_ROUTE) {
        val requested by saveToFilesRequested
        // Help/assistant context (ADR-108).
        LaunchedEffect(Unit) { CurrentScreenTracker.setScreen(HelpScreenIds.FILES) }
        FilesScreen(
            saveToFilesRequested = requested,
            onSaveToFilesConsumed = onSaveToFilesConsumed,
        )
    }
}
