package com.itsluminous.samaroh.feature.files.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.ImageViewerDialog
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.imaging.MediaStoreImageSaver
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.feature.files.FileRow
import com.itsluminous.samaroh.feature.files.FilesEvent
import com.itsluminous.samaroh.feature.files.FilesUiState
import com.itsluminous.samaroh.feature.files.FilesViewModel
import com.itsluminous.samaroh.feature.files.FolderRow
import com.itsluminous.samaroh.feature.files.MoveTarget
import com.itsluminous.samaroh.feature.files.SearchHit
import com.itsluminous.samaroh.feature.files.domain.FilesTree
import com.itsluminous.samaroh.feature.files.domain.MoveError
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/** A pending long-press target for the action sheet. */
private sealed interface SheetTarget {
    data class ForFolder(
        val row: FolderRow,
    ) : SheetTarget

    data class ForFile(
        val row: FileRow,
    ) : SheetTarget
}

/**
 * Files tab (ADR-085, design §6): title = current folder (or "Files"), Up affordance,
 * global search, grid/list toggle, breadcrumbs, folders-then-files listing, upload FAB +
 * New folder (permission-hidden), long-press action sheets, empty states.
 *
 * @param saveToFilesRequested the share chooser picked Save to Files: show the folder
 *   picker over the payload parked in the share holder; consumed via [onSaveToFilesConsumed].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    saveToFilesRequested: Boolean = false,
    onSaveToFilesConsumed: () -> Unit = {},
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accessEditor by viewModel.accessEditor.collectAsStateWithLifecycle()
    val showFolderPicker by viewModel.folderPickerVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var renameFileTarget by remember { mutableStateOf<FileRow?>(null) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteFileTarget by remember { mutableStateOf<FileRow?>(null) }
    var showLinkPrompt by remember { mutableStateOf(false) }
    var viewerImage by remember { mutableStateOf<Pair<File, FileRow>?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<File, FileRow>?>(null) }

    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            viewModel.onUploadPicked(uris)
        }
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.completeGoogleConsent(result.data)
        }

    // Snackbar texts resolved up front (events arrive outside composition).
    val texts = rememberFilesTexts()
    val downloadFailedText = stringResource(R.string.files_file_open_failed)
    val performDownload: (File, FileRow) -> Unit = { file, row ->
        scope.launch {
            when (MediaStoreImageSaver.saveImage(context, file, row.file.name, row.file.mimeType)) {
                is MediaStoreImageSaver.SaveResult.Saved -> snackbarHostState.showSnackbar(texts.downloadDone)
                MediaStoreImageSaver.SaveResult.NeedsPermission -> pendingDownload = file to row
                MediaStoreImageSaver.SaveResult.Failed -> snackbarHostState.showSnackbar(downloadFailedText)
            }
        }
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingDownload
            pendingDownload = null
            if (granted && pending != null) performDownload(pending.first, pending.second)
        }
    LaunchedEffect(pendingDownload) {
        if (pendingDownload != null) permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // Share sheet → Save to Files (design D18): the folder picker is the flow's door.
    LaunchedEffect(saveToFilesRequested) {
        if (saveToFilesRequested) {
            viewModel.requestSaveToFiles()
            onSaveToFilesConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesEvent.FolderCreated -> snackbarHostState.showSnackbar(texts.folderCreated)
                FilesEvent.FolderRenamed -> snackbarHostState.showSnackbar(texts.folderRenamed)
                FilesEvent.FileRenamed -> snackbarHostState.showSnackbar(texts.fileRenamed)
                is FilesEvent.Moved -> snackbarHostState.showSnackbar(texts.moved(event.destinationName ?: texts.rootLabel))
                FilesEvent.FolderDeleted -> snackbarHostState.showSnackbar(texts.folderDeleted)
                FilesEvent.FileDeleted -> snackbarHostState.showSnackbar(texts.fileDeleted)
                is FilesEvent.UploadStaged ->
                    snackbarHostState.showSnackbar(
                        when {
                            event.pendingUnlinked -> texts.pendingUnlinked
                            else -> texts.queued(event.count)
                        },
                    )
                is FilesEvent.UploadTooLarge -> snackbarHostState.showSnackbar(texts.tooLarge(event.name))
                is FilesEvent.UploadTooMany -> snackbarHostState.showSnackbar(texts.tooMany(event.max))
                is FilesEvent.UploadFailed -> snackbarHostState.showSnackbar(texts.uploadFailed(event.name))
                is FilesEvent.OpenImage -> viewerImage = event.file to FileRow(event.row, null)
                is FilesEvent.OpenUrl -> openCustomTab(context, event.url)
                is FilesEvent.OpenWithApp ->
                    if (!openWithExternalApp(context, event.file, event.mimeType)) snackbarHostState.showSnackbar(texts.noViewerApp)
                is FilesEvent.CopyLink -> {
                    copyToClipboard(context, event.url)
                    snackbarHostState.showSnackbar(texts.linkCopied)
                }
                is FilesEvent.Download -> performDownload(event.file, FileRow(event.row, null))
                FilesEvent.OpenFailed -> snackbarHostState.showSnackbar(texts.openFailed)
                FilesEvent.AccessSaved -> snackbarHostState.showSnackbar(texts.accessSaved)
                FilesEvent.LinkFailed -> snackbarHostState.showSnackbar(texts.linkFailed)
                is FilesEvent.NeedsConsent -> consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                FilesEvent.PromptGoogleLink -> showLinkPrompt = true
                FilesEvent.OpenPicker -> pickFiles.launch(arrayOf(ANY_MIME))
                is FilesEvent.SharedSaved -> snackbarHostState.showSnackbar(texts.sharedSaved(event.count))
            }
        }
    }

    // System back inside a folder goes UP one level before leaving the tab.
    BackHandler(enabled = state.folderId != null) { viewModel.navigateUp() }

    val title = state.breadcrumbs.lastOrNull()?.name ?: stringResource(R.string.files_home_title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (state.folderId != null) {
                        ExplainableIcon(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            explanationRes = R.string.files_breadcrumb_up,
                            onClick = { viewModel.navigateUp() },
                        )
                    }
                },
                actions = {
                    ExplainableIcon(
                        icon = if (state.gridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        explanationRes = if (state.gridView) R.string.files_view_list else R.string.files_view_grid,
                        onClick = { viewModel.setGridView(!state.gridView) },
                    )
                    // Permission-HIDDEN, never greyed (ADR-038).
                    if (state.canManageFolders && !state.folderMissing) {
                        ExplainableIcon(
                            icon = Icons.Filled.CreateNewFolder,
                            explanationRes = R.string.files_action_new_folder,
                            onClick = { showNewFolder = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canUpload && !state.folderMissing && state.searchHits == null) {
                SamarohFab(onClick = viewModel::onUploadTapped, explanationRes = R.string.files_action_upload) {
                    Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.files_action_upload))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(query = state.query, onQueryChange = viewModel::onQueryChange)
            if (state.searchHits == null) {
                BreadcrumbRow(breadcrumbs = state.breadcrumbs, onCrumb = viewModel::openFolder)
            }
            when {
                state.loading -> Unit
                state.folderMissing ->
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.files_access_no_access),
                        message = "",
                    )
                state.searchHits != null ->
                    SearchResults(
                        hits = state.searchHits.orEmpty(),
                        state = state,
                        onOpenFolder = viewModel::openFolder,
                        onOpenFile = viewModel::openFile,
                        onLongPressFolder = { sheetTarget = SheetTarget.ForFolder(it) },
                        onLongPressFile = { sheetTarget = SheetTarget.ForFile(it) },
                    )
                state.folders.isEmpty() && state.files.isEmpty() ->
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title =
                            stringResource(
                                if (state.folderId ==
                                    null
                                ) {
                                    R.string.files_home_empty_title
                                } else {
                                    R.string.files_folder_empty_title
                                },
                            ),
                        message =
                            if (state.folderId == null && (state.canUpload || state.canManageFolders)) {
                                stringResource(R.string.files_home_empty_message)
                            } else {
                                ""
                            },
                    )
                state.gridView ->
                    FilesGrid(
                        state = state,
                        onOpenFolder = viewModel::openFolder,
                        onOpenFile = viewModel::openFile,
                        onLongPressFolder = { sheetTarget = SheetTarget.ForFolder(it) },
                        onLongPressFile = { sheetTarget = SheetTarget.ForFile(it) },
                    )
                else ->
                    FilesList(
                        state = state,
                        onOpenFolder = viewModel::openFolder,
                        onOpenFile = viewModel::openFile,
                        onLongPressFolder = { sheetTarget = SheetTarget.ForFolder(it) },
                        onLongPressFile = { sheetTarget = SheetTarget.ForFile(it) },
                    )
            }
        }
    }

    // ---------------------------------------------------------------- sheets & dialogs

    sheetTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { sheetTarget = null }) {
            when (target) {
                is SheetTarget.ForFolder -> {
                    val folder = target.row.folder
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.canManageFolders) {
                        SheetAction(Icons.Filled.Edit, R.string.files_action_rename_folder) {
                            sheetTarget = null
                            renameTarget = folder
                        }
                        SheetAction(Icons.Filled.DriveFileMove, R.string.files_action_move) {
                            sheetTarget = null
                            moveTarget = MoveTarget.ForFolder(folder)
                        }
                    }
                    if (state.isOwner) {
                        SheetAction(Icons.Filled.ManageAccounts, R.string.files_action_manage_access) {
                            sheetTarget = null
                            viewModel.openAccessEditor(folder)
                        }
                    }
                    if (state.canDelete) {
                        SheetAction(Icons.Filled.Delete, R.string.files_action_delete_folder) {
                            sheetTarget = null
                            deleteFolderTarget = folder
                        }
                    }
                }
                is SheetTarget.ForFile -> {
                    val row = target.row
                    Text(
                        row.file.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SheetAction(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.files_action_open) {
                        sheetTarget = null
                        viewModel.openFile(row)
                    }
                    if (row.file.isUploaded) {
                        SheetAction(Icons.AutoMirrored.Filled.OpenInNew, R.string.files_action_open_in_drive) {
                            sheetTarget = null
                            viewModel.openInDrive(row)
                        }
                        SheetAction(Icons.Filled.Link, R.string.files_action_copy_link) {
                            sheetTarget = null
                            viewModel.copyLink(row)
                        }
                    }
                    SheetAction(Icons.Filled.Download, R.string.files_action_download) {
                        sheetTarget = null
                        viewModel.download(row)
                    }
                    // Rename / Move: files.delete, or own upload with files.upload (ADR-090) — hidden otherwise.
                    if (row.canRenameOrMove) {
                        SheetAction(Icons.Filled.Edit, R.string.files_action_rename_file) {
                            sheetTarget = null
                            renameFileTarget = row
                        }
                        SheetAction(Icons.Filled.DriveFileMove, R.string.files_action_move) {
                            sheetTarget = null
                            moveTarget = MoveTarget.ForFile(row.row)
                        }
                    }
                    if (state.canDelete) {
                        SheetAction(Icons.Filled.Delete, R.string.files_action_delete_file) {
                            sheetTarget = null
                            deleteFileTarget = row
                        }
                    }
                }
            }
            androidx.compose.foundation.layout
                .Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (showNewFolder) {
        FolderNameDialog(
            titleRes = R.string.files_action_new_folder,
            initialName = "",
            validate = { viewModel.validateFolderName(it) },
            onConfirm = {
                showNewFolder = false
                viewModel.createFolder(it)
            },
            onDismiss = { showNewFolder = false },
        )
    }
    renameTarget?.let { folder ->
        FolderNameDialog(
            titleRes = R.string.files_action_rename_folder,
            initialName = folder.name,
            validate = { FilesTree.validateFolderName(it, folder.parentId, state.allFolders, excludeFolderId = folder.id) },
            onConfirm = {
                renameTarget = null
                viewModel.renameFolder(folder, it)
            },
            onDismiss = { renameTarget = null },
        )
    }
    renameFileTarget?.let { row ->
        FileNameDialog(
            initialName = row.file.name,
            validate = viewModel::validateFileName,
            onConfirm = {
                renameFileTarget = null
                viewModel.renameFile(row.row, it)
            },
            onDismiss = { renameFileTarget = null },
        )
    }
    moveTarget?.let { target ->
        FolderPickerDialog(
            folders = state.allFolders,
            onPick = { destination ->
                moveTarget = null
                viewModel.move(target, destination)
            },
            onDismiss = { moveTarget = null },
            titleRes = R.string.files_move_title,
            confirmRes = R.string.files_move_confirm,
            initialSelection = target.currentParentId,
            selectionError = { destination -> viewModel.validateMove(target, destination)?.let { moveErrorText(it) } },
            canCreateFolder = state.canManageFolders,
            validateNewFolderName = { name, parentId -> viewModel.validateFolderName(name, parentId = parentId) },
            onCreateFolder = { name, parentId -> viewModel.createFolder(name, parentId) },
        )
    }
    deleteFolderTarget?.let { folder ->
        DeleteFolderDialog(
            subtreeCount = viewModel.subtreeCount(folder),
            onConfirm = {
                deleteFolderTarget = null
                viewModel.deleteFolder(folder)
            },
            onDismiss = { deleteFolderTarget = null },
        )
    }
    deleteFileTarget?.let { row ->
        DeleteFileDialog(
            onConfirm = {
                deleteFileTarget = null
                viewModel.deleteFile(row.row)
            },
            onDismiss = { deleteFileTarget = null },
        )
    }
    if (showLinkPrompt) {
        LinkGoogleDialog(
            onConnect = {
                showLinkPrompt = false
                viewModel.linkGoogle(context)
            },
            onLater = {
                // Not now: stage locally anyway (design D8) — the pending hint follows.
                showLinkPrompt = false
                pickFiles.launch(arrayOf(ANY_MIME))
            },
        )
    }
    accessEditor?.let { editor ->
        AccessEditorDialog(
            state = editor,
            onRestrictedChange = viewModel::setAccessRestricted,
            onToggleMember = viewModel::toggleAccessMember,
            onSave = viewModel::saveAccess,
            onDismiss = viewModel::dismissAccessEditor,
        )
    }
    if (showFolderPicker) {
        FolderPickerDialog(
            folders = state.allFolders,
            onPick = { folderId -> viewModel.saveSharedFiles(folderId) },
            onDismiss = { viewModel.discardSharedFiles() },
            // "New folder" inside the picker (ADR-087): effective manage_folders gate.
            canCreateFolder = state.canManageFolders,
            validateNewFolderName = { name, parentId -> viewModel.validateFolderName(name, parentId = parentId) },
            onCreateFolder = { name, parentId -> viewModel.createFolder(name, parentId) },
        )
    }
    viewerImage?.let { (file, row) ->
        ImageViewerDialog(
            model = file,
            contentDescription = stringResource(R.string.files_file_thumbnail_a11y, row.file.name),
            onDismiss = { viewerImage = null },
            downloadSource = file,
            downloadFileName = row.file.name,
            downloadMimeType = row.file.mimeType,
        )
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.files_home_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon =
            if (query.isBlank()) {
                null
            } else {
                {
                    ExplainableIcon(
                        icon = Icons.Filled.Clear,
                        explanationRes = R.string.common_action_close,
                        onClick = { onQueryChange("") },
                    )
                }
            },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** `All files › Contracts › 2026`, each crumb tappable (design §6). */
@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<Folder>,
    onCrumb: (String?) -> Unit,
) {
    val separator = stringResource(R.string.files_breadcrumb_separator)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Crumb(label = stringResource(R.string.files_home_root_label), current = breadcrumbs.isEmpty()) { onCrumb(null) }
        breadcrumbs.forEachIndexed { index, folder ->
            Text(separator, modifier = Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(label = folder.name, current = index == breadcrumbs.lastIndex) { onCrumb(folder.id) }
        }
    }
}

@Composable
private fun Crumb(
    label: String,
    current: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesList(
    state: FilesUiState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (FileRow) -> Unit,
    onLongPressFolder: (FolderRow) -> Unit,
    onLongPressFile: (FileRow) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(state.folders, key = { "folder-${it.folder.id}" }) { row ->
            FolderListRow(row = row, subtitle = null, onClick = { onOpenFolder(row.folder.id) }, onLongClick = { onLongPressFolder(row) })
        }
        items(state.files, key = { "file-${it.file.id}" }) { row ->
            FileListRow(row = row, subtitle = null, googleLinked = state.googleLinked, onClick = {
                onOpenFile(row)
            }, onLongClick = { onLongPressFile(row) })
        }
    }
}

@Composable
private fun SearchResults(
    hits: List<SearchHit>,
    state: FilesUiState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (FileRow) -> Unit,
    onLongPressFolder: (FolderRow) -> Unit,
    onLongPressFile: (FileRow) -> Unit,
) {
    if (hits.isEmpty()) {
        EmptyState(icon = Icons.Filled.Search, title = stringResource(R.string.files_search_empty), message = "")
        return
    }
    val rootLabel = stringResource(R.string.files_home_root_label)
    val separator = stringResource(R.string.files_breadcrumb_separator)
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(hits, key = {
            it.folder
                ?.folder
                ?.id
                ?.let { id -> "folder-$id" } ?: "file-${it.file!!.file.id}"
        }) { hit ->
            val path =
                stringResource(
                    R.string.files_search_result_path,
                    FilesTree.pathLabel(hit.parentFolderId, state.allFolders, rootLabel, separator),
                )
            hit.folder?.let { row ->
                FolderListRow(
                    row = row,
                    subtitle = path,
                    onClick = { onOpenFolder(row.folder.id) },
                    onLongClick = { onLongPressFolder(row) },
                )
            }
            hit.file?.let { row ->
                FileListRow(row = row, subtitle = path, googleLinked = state.googleLinked, onClick = {
                    onOpenFile(row)
                }, onLongClick = { onLongPressFile(row) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListRow(
    row: FolderRow,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle ?: itemCountLabel(row.itemCount)) },
        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.files_folder_icon_a11y)) },
        trailingContent = if (row.folder.restricted) ({ RestrictedChip() }) else null,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    row: FileRow,
    subtitle: String?,
    googleLinked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val secondary =
        buildList {
            subtitle?.let(::add)
            add(sizeLabel(row.file.sizeBytes))
            row.addedBy?.let { add(stringResource(R.string.files_file_added_by, it)) }
            if (!row.file.isUploaded) {
                add(stringResource(if (googleLinked) R.string.files_file_pending_label else R.string.files_upload_pending_unlinked))
            }
        }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(row.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { FileThumbnail(row = row, size = 40.dp) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesGrid(
    state: FilesUiState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (FileRow) -> Unit,
    onLongPressFolder: (FolderRow) -> Unit,
    onLongPressFile: (FileRow) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.folders, key = { "folder-${it.folder.id}" }) { row ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .combinedClickable(onClick = { onOpenFolder(row.folder.id) }, onLongClick = { onLongPressFolder(row) })
                        .padding(8.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = stringResource(R.string.files_folder_icon_a11y),
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    if (row.folder.restricted) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.files_access_restricted_badge),
                            modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                        )
                    }
                }
                Text(row.folder.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    itemCountLabel(row.itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.files, key = { "file-${it.file.id}" }) { row ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .combinedClickable(onClick = { onOpenFile(row) }, onLongClick = { onLongPressFile(row) })
                        .padding(8.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    FileThumbnail(row = row, size = 96.dp, fill = true)
                }
                Text(row.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (row.file.isUploaded) sizeLabel(row.file.sizeBytes) else stringResource(R.string.files_file_pending_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Thumbnail-or-type-icon (design D16): images render their staged/cached file or the
 * public Drive thumbnail; PDFs get the Drive thumbnail when uploaded; others a type icon.
 */
@Composable
private fun FileThumbnail(
    row: FileRow,
    size: androidx.compose.ui.unit.Dp,
    fill: Boolean = false,
) {
    val file = row.file
    val model: Any? =
        when {
            file.isImage && row.row.localCachePath != null -> File(row.row.localCachePath)
            (file.isImage || file.isPdf) && file.driveFileId != null -> FileItem.thumbnailUrl(file.driveFileId!!, THUMB_PX)
            else -> null
        }
    val modifier = if (fill) Modifier.fillMaxSize() else Modifier.size(size)
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = stringResource(R.string.files_file_thumbnail_a11y, file.name),
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Icon(
            imageVector = if (file.isPdf) Icons.Filled.PictureAsPdf else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = if (fill) Modifier.size(56.dp) else Modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RestrictedChip() {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(R.string.files_access_restricted_badge)) },
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun itemCountLabel(count: Int): String =
    if (count > 0) {
        pluralStringResource(R.plurals.files_folder_item_count, count, count)
    } else {
        stringResource(R.string.files_folder_item_count_empty)
    }

/** `{size} KB` under 1 MB (no decimals), `{size} MB` otherwise (one decimal), locale-formatted. */
@Composable
internal fun sizeLabel(sizeBytes: Long): String {
    val locale = Locale.getDefault()
    return if (sizeBytes < 1_048_576L) {
        val kb = NumberFormat.getIntegerInstance(locale).format(maxOf(1L, sizeBytes / 1024L))
        stringResource(R.string.files_file_size_kb, kb)
    } else {
        val format =
            NumberFormat.getNumberInstance(locale).apply {
                maximumFractionDigits = 1
                minimumFractionDigits = 1
            }
        stringResource(R.string.files_file_size_mb, format.format(sizeBytes / 1_048_576.0))
    }
}

/** Snackbar texts resolved inside composition for the event collector. */
private class FilesTexts(
    val folderCreated: String,
    val folderRenamed: String,
    val fileRenamed: String,
    val rootLabel: String,
    val noViewerApp: String,
    val folderDeleted: String,
    val fileDeleted: String,
    val pendingUnlinked: String,
    val linkCopied: String,
    val openFailed: String,
    val accessSaved: String,
    val linkFailed: String,
    val downloadDone: String,
    val queued: (Int) -> String,
    val tooLarge: (String) -> String,
    val tooMany: (Int) -> String,
    val uploadFailed: (String) -> String,
    val sharedSaved: (Int) -> String,
    val moved: (String) -> String,
)

@Composable
private fun rememberFilesTexts(): FilesTexts {
    val resources = LocalContext.current.resources
    return FilesTexts(
        folderCreated = stringResource(R.string.files_folder_created),
        folderRenamed = stringResource(R.string.files_folder_renamed),
        fileRenamed = stringResource(R.string.files_file_renamed),
        rootLabel = stringResource(R.string.files_home_root_label),
        noViewerApp = stringResource(R.string.files_file_no_viewer_app),
        folderDeleted = stringResource(R.string.files_folder_deleted),
        fileDeleted = stringResource(R.string.files_file_deleted),
        pendingUnlinked = stringResource(R.string.files_upload_pending_unlinked),
        linkCopied = stringResource(R.string.files_action_link_copied),
        openFailed = stringResource(R.string.files_file_open_failed),
        accessSaved = stringResource(R.string.files_access_saved),
        linkFailed = stringResource(R.string.files_upload_link_failed),
        downloadDone = stringResource(R.string.files_file_download_done),
        queued = { n -> resources.getQuantityString(R.plurals.files_upload_queued, n, n) },
        tooLarge = { name -> resources.getString(R.string.files_upload_too_large, name) },
        tooMany = { max -> resources.getString(R.string.files_upload_too_many, max.toString()) },
        uploadFailed = { name -> resources.getString(R.string.files_upload_failed, name) },
        sharedSaved = { n -> resources.getQuantityString(R.plurals.files_share_target_saved, n, n) },
        moved = { folder -> resources.getString(R.string.files_move_done, folder) },
    )
}

@Composable
private fun moveErrorText(error: MoveError): String =
    stringResource(
        when (error) {
            MoveError.SAME_LOCATION -> R.string.files_move_same_folder
            MoveError.INTO_SELF -> R.string.files_move_into_self
            MoveError.TOO_DEEP -> R.string.files_move_too_deep
            MoveError.DUPLICATE -> R.string.files_folder_duplicate
        },
    )

/**
 * Opens [url] in a Custom Tab PINNED to a browser package (ADR-090). An unpinned
 * `CustomTabsIntent` is an implicit `VIEW` that Android hands to the verified App Link
 * owner of `drive.google.com` — the Google Drive app — which then demands an account on
 * every open. `CustomTabsClient.getPackageName` needs the `<queries>` entry in this
 * module's manifest on API 30+.
 */
internal fun openCustomTab(
    context: Context,
    url: String,
) {
    runCatching {
        val tab = CustomTabsIntent.Builder().build()
        browserPackage(context)?.let { tab.intent.setPackage(it) }
        tab.launchUrl(context, Uri.parse(url))
    }
}

/** A Custom-Tabs-capable browser, else the default browser for a generic http URL; null when neither resolves. */
internal fun browserPackage(context: Context): String? {
    CustomTabsClient.getPackageName(context, null)?.let { return it }
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse(GENERIC_HTTP_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
    return probe.resolveActivity(context.packageManager)?.packageName
}

/**
 * Hands a NON-image file's LOCAL bytes to the system via this module's `FileProvider` +
 * `ACTION_VIEW` chooser with the row's MIME — the expense-bill mechanism (ADR-052),
 * design D16 revised. Returns false when no installed app can display the type.
 */
internal fun openWithExternalApp(
    context: Context,
    file: File,
    mimeType: String,
): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    return launchViewer(context, viewIntent(uri, mimeType))
}

/** `ACTION_VIEW` on a content uri with an explicit MIME and a read grant (never a bare URL). */
internal fun viewIntent(
    uri: Uri,
    mimeType: String,
): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Fires [intent] through the system chooser; false when nothing resolves (the manifest `<queries>` makes the check honest on API 30+). */
internal fun launchViewer(
    context: Context,
    intent: Intent,
): Boolean {
    if (intent.resolveActivity(context.packageManager) == null) return false
    return try {
        context.startActivity(Intent.createChooser(intent, null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
}

private const val ANY_MIME = "*/*"
private const val THUMB_PX = 320
private const val CLIP_LABEL = "samaroh-file-link"
private const val FILE_PROVIDER_SUFFIX = ".files.fileprovider"
private const val GENERIC_HTTP_URL = "https://example.com"
