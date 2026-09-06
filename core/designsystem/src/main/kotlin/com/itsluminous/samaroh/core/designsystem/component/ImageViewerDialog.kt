package com.itsluminous.samaroh.core.designsystem.component

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.imaging.MediaStoreImageSaver
import com.itsluminous.samaroh.core.i18n.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * The viewer's DELETE affordance: what confirmation to show and what a confirmed delete
 * does — features own the semantics (expenses: tombstone the bill; inventory would be
 * "clear the photo"). Null hides the action entirely (permission gating happens at the
 * caller, per each feature's own convention).
 */
data class ImageViewerDeleteAction(
    val confirmTitle: String,
    val confirmMessage: String,
    /** Runs after the user confirms; the caller closes the viewer and cascades. */
    val onConfirmed: () -> Unit,
)

/**
 * Full-screen in-app image viewer with an action bar (ADR-053): FULL SCREEN (immersive —
 * hides system bars and the viewer chrome; tapping the image restores), DOWNLOAD (a copy
 * into public Downloads via [MediaStoreImageSaver], shown only when [downloadSource] is a
 * local file) and optional DELETE ([deleteAction], confirmation included). Tapping the
 * image outside immersive mode closes the viewer — the pre-existing tap-to-dismiss
 * gesture of the expense/inventory expand dialogs.
 *
 * Every action is an [ExplainableIcon] (long-press → localized label, hard rule 6).
 */
@Composable
fun ImageViewerDialog(
    model: Any?,
    contentDescription: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    downloadSource: File? = null,
    downloadFileName: String? = null,
    downloadMimeType: String = "image/jpeg",
    deleteAction: ImageViewerDeleteAction? = null,
) {
    var immersive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedTemplate = stringResource(R.string.common_viewer_saved_to)
    val saveFailedText = stringResource(R.string.common_viewer_save_failed)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog owns its own window: immersive mode toggles THAT window's system
        // bars, so the activity underneath is untouched when the viewer closes.
        val view = LocalView.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            val controller = WindowInsetsControllerCompat(dialogWindow, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        val performSave: () -> Unit = save@{
            val source = downloadSource ?: return@save
            scope.launch {
                when (val result = MediaStoreImageSaver.saveImage(context, source, downloadFileName ?: source.name, downloadMimeType)) {
                    is MediaStoreImageSaver.SaveResult.Saved ->
                        snackbarHostState.showSnackbar(savedTemplate.format(result.locationLabel))
                    MediaStoreImageSaver.SaveResult.Failed -> snackbarHostState.showSnackbar(saveFailedText)
                    // NeedsPermission is handled before calling (legacy APIs) — treat
                    // an unexpected fall-through as a plain failure.
                    MediaStoreImageSaver.SaveResult.NeedsPermission -> snackbarHostState.showSnackbar(saveFailedText)
                }
            }
        }
        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) performSave() else scope.launch { snackbarHostState.showSnackbar(saveFailedText) }
            }

        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            // Immersive tap RESTORES chrome + bars; a plain tap keeps the
                            // old expand-dialog gesture and closes the viewer.
                            if (immersive) immersive = false else onDismiss()
                        },
            )
            AnimatedVisibility(
                visible = !immersive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag(ACTION_BAR_TAG),
                ) {
                    ExplainableIcon(
                        icon = Icons.Filled.Fullscreen,
                        explanationRes = R.string.common_viewer_fullscreen,
                        tint = Color.White,
                        onClick = { immersive = true },
                        modifier = Modifier.testTag(FULLSCREEN_TAG),
                    )
                    if (downloadSource != null) {
                        ExplainableIcon(
                            icon = Icons.Filled.Download,
                            explanationRes = R.string.common_viewer_download,
                            tint = Color.White,
                            onClick = {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    performSave()
                                }
                            },
                            modifier = Modifier.testTag(DOWNLOAD_TAG),
                        )
                    }
                    if (deleteAction != null) {
                        ExplainableIcon(
                            icon = Icons.Filled.Delete,
                            explanationRes = R.string.common_viewer_delete,
                            tint = Color.White,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.testTag(DELETE_TAG),
                        )
                    }
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.common_viewer_close,
                        tint = Color.White,
                        onClick = onDismiss,
                        modifier = Modifier.testTag(CLOSE_TAG),
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (confirmDelete && deleteAction != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(deleteAction.confirmTitle) },
            text = { Text(deleteAction.confirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleteAction.onConfirmed()
                    },
                    modifier = Modifier.testTag(DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.common_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

/** Test tags — UI tests assert action gating and the fullscreen chrome toggle. */
const val ACTION_BAR_TAG = "image_viewer_action_bar"
const val FULLSCREEN_TAG = "image_viewer_fullscreen"
const val DOWNLOAD_TAG = "image_viewer_download"
const val DELETE_TAG = "image_viewer_delete"
const val CLOSE_TAG = "image_viewer_close"
const val DELETE_CONFIRM_TAG = "image_viewer_delete_confirm"
