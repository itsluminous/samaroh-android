package com.itsluminous.samaroh.feature.files

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.feature.files.ui.FilesScreen

/** Route of the Files tab's destination (ADR-085). */
const val FILES_ROUTE = "files"

/**
 * Files feature graph (ADR-085): one destination — folder navigation is in-memory
 * (breadcrumbs / Up / back), so no nested NavHost is needed.
 *
 * @param saveToFilesRequested the unified share chooser picked Save to Files (ADR-086):
 *   the screen shows the destination-folder picker over the parked payload; cleared via
 *   [onSaveToFilesConsumed].
 */
fun NavGraphBuilder.filesGraph(
    saveToFilesRequested: Boolean = false,
    onSaveToFilesConsumed: () -> Unit = {},
) {
    composable(FILES_ROUTE) {
        FilesScreen(
            saveToFilesRequested = saveToFilesRequested,
            onSaveToFilesConsumed = onSaveToFilesConsumed,
        )
    }
}
