package com.itsluminous.samaroh.feature.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.FileWithLocalState
import com.itsluminous.samaroh.core.data.repository.FilesRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.share.ShareIntakeHolder
import com.itsluminous.samaroh.core.data.share.SharedFile
import com.itsluminous.samaroh.core.data.sync.FilesDriveDeleter
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.core.model.FolderAccess
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.feature.files.domain.FilesTree
import com.itsluminous.samaroh.feature.files.domain.FolderNameError
import com.itsluminous.samaroh.feature.files.open.FileContentResolver
import com.itsluminous.samaroh.feature.files.open.FileOpenResult
import com.itsluminous.samaroh.feature.files.upload.FileUploadIntake
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** One folder row of the listing. */
data class FolderRow(
    val folder: Folder,
    /** Live direct children (subfolders + files). */
    val itemCount: Int,
)

/** One file row of the listing. */
data class FileRow(
    val row: FileWithLocalState,
    /** Display name of the uploading member (null when unknown). */
    val addedBy: String?,
) {
    val file: FileItem get() = row.file
}

/** A global-search hit (design D11): the entry plus the folder it lives in. */
data class SearchHit(
    val folder: FolderRow? = null,
    val file: FileRow? = null,
    /** Parent folder of the hit (null = top level) — rendered as the path subtitle. */
    val parentFolderId: String?,
)

/** The owner's access editor (design D6) for one folder. */
data class AccessEditorState(
    val folder: Folder,
    val restricted: Boolean,
    /** Non-owner members of the business (invited + active) with their current tick. */
    val members: List<BusinessMember>,
    val selectedMemberIds: Set<String>,
    val saving: Boolean = false,
)

data class FilesUiState(
    val loading: Boolean = true,
    val businessId: String? = null,
    /** null = top level ("All files"). */
    val folderId: String? = null,
    val breadcrumbs: List<Folder> = emptyList(),
    val folders: List<FolderRow> = emptyList(),
    val files: List<FileRow> = emptyList(),
    val query: String = "",
    /** Non-null while a query is active: flat results across the whole accessible index. */
    val searchHits: List<SearchHit>? = null,
    val gridView: Boolean = false,
    val canUpload: Boolean = false,
    val canManageFolders: Boolean = false,
    val canDelete: Boolean = false,
    val isOwner: Boolean = false,
    /** The current folder was tombstoned / is no longer accessible (stale deep link). */
    val folderMissing: Boolean = false,
    /** Whether a Google account is linked (drives the pending-file hint + the link prompt). */
    val googleLinked: Boolean = false,
    val googleConfigured: Boolean = true,
    val uploadsInFlight: Int = 0,
    /** All live folders — the folder picker + path labels need the whole tree. */
    val allFolders: List<Folder> = emptyList(),
    /** All live files (reachable) — delete-confirm subtree counts. */
    val allFiles: List<FileWithLocalState> = emptyList(),
)

/** One-shot UI events (snackbars, navigation to external viewers, prompts). */
sealed interface FilesEvent {
    data class FolderCreated(
        val name: String,
    ) : FilesEvent

    data object FolderRenamed : FilesEvent

    data object FolderDeleted : FilesEvent

    data object FileDeleted : FilesEvent

    data class UploadStaged(
        val count: Int,
        val queuedOffline: Boolean,
        val pendingUnlinked: Boolean,
    ) : FilesEvent

    data class UploadTooLarge(
        val name: String,
    ) : FilesEvent

    data class UploadTooMany(
        val max: Int,
    ) : FilesEvent

    data class UploadFailed(
        val name: String,
    ) : FilesEvent

    /** Open an image in the in-app viewer. */
    data class OpenImage(
        val file: File,
        val row: FileWithLocalState,
    ) : FilesEvent

    /** Open a URL (Drive viewer) in a Custom Tab. */
    data class OpenUrl(
        val url: String,
    ) : FilesEvent

    data class CopyLink(
        val url: String,
    ) : FilesEvent

    /** Download the resolved bytes into public Downloads (caller runs MediaStore). */
    data class Download(
        val file: File,
        val row: FileWithLocalState,
    ) : FilesEvent

    data object OpenFailed : FilesEvent

    data object AccessSaved : FilesEvent

    data object LinkFailed : FilesEvent

    /** The Google link flow needs the consent sheet — launch the pending intent. */
    data class NeedsConsent(
        val pendingIntent: android.app.PendingIntent,
    ) : FilesEvent

    /** Unlinked user tapped Upload: show the Connect Google Drive prompt (design D8). */
    data object PromptGoogleLink : FilesEvent

    /** Proceed to the system file picker. */
    data object OpenPicker : FilesEvent

    /** Share-sheet payload saved (design D18). */
    data class SharedSaved(
        val count: Int,
    ) : FilesEvent
}

/**
 * Files tab ViewModel (ADR-085): one instance per tab hosting in-memory folder navigation
 * (breadcrumbs, Up, back), GLOBAL search over the accessible index, permission-gated
 * actions, uploads via [FileUploadIntake] (staged + outbox) and the owner's access editor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FilesViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val session: FilesSession,
        private val repository: FilesRepository,
        private val memberRepository: MemberRepository,
        private val uploadIntake: FileUploadIntake,
        private val contentResolver: FileContentResolver,
        private val driveDeleter: FilesDriveDeleter,
        private val googleAccountLinker: GoogleAccountLinker,
        private val viewPreferences: FilesViewPreferences,
        private val shareIntakeHolder: ShareIntakeHolder,
        private val clock: Clock,
    ) : ViewModel() {
        private val folderId = MutableStateFlow<String?>(savedStateHandle[KEY_FOLDER])
        private val query = MutableStateFlow("")
        private val uploadsInFlight = MutableStateFlow(0)

        private val _events = MutableSharedFlow<FilesEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val events: SharedFlow<FilesEvent> = _events

        private val _accessEditor = MutableStateFlow<AccessEditorState?>(null)
        val accessEditor: StateFlow<AccessEditorState?> = _accessEditor.asStateFlow()

        private val linkState: Flow<GoogleLinkState> = googleAccountLinker.linkState

        private data class Perms(
            val upload: Boolean,
            val manage: Boolean,
            val delete: Boolean,
            val owner: Boolean,
        )

        private val perms: Flow<Perms> =
            combine(session.canUpload, session.canManageFolders, session.canDelete, session.isOwner) { u, m, d, o -> Perms(u, m, d, o) }

        private data class Index(
            val businessId: String?,
            val folders: List<Folder>,
            val files: List<FileWithLocalState>,
            val members: List<BusinessMember>,
        )

        private val index: Flow<Index> =
            session.businessIdFlow.flatMapLatest { businessId ->
                if (businessId == null) {
                    flowOf(Index(null, emptyList(), emptyList(), emptyList()))
                } else {
                    combine(
                        repository.folders(businessId),
                        repository.files(businessId),
                        memberRepository.membersForBusiness(businessId),
                    ) { folders, files, members -> Index(businessId, folders, files, members) }
                }
            }

        val uiState: StateFlow<FilesUiState> =
            combine(
                index,
                folderId,
                query,
                perms,
                combine(viewPreferences.gridView, linkState, uploadsInFlight) { g, l, u -> Triple(g, l, u) },
            ) { index, folderId, query, perms, (grid, link, inFlight) ->
                buildState(index, folderId, query, perms, grid, link, inFlight)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

        private fun buildState(
            index: Index,
            folderId: String?,
            query: String,
            perms: Perms,
            grid: Boolean,
            link: GoogleLinkState,
            inFlight: Int,
        ): FilesUiState {
            // A file/folder under a tombstoned ancestor is hidden client-side (design D2).
            val reachableFolders = index.folders.filter { FilesTree.isReachable(it.parentId, index.folders) }
            val reachableFiles = index.files.filter { FilesTree.isReachable(it.file.folderId, index.folders) }
            val nameOf = index.members.filter { it.userId != null }.associate { it.userId!! to it.displayName }

            fun folderRow(f: Folder) = FolderRow(f, FilesTree.directChildCount(f.id, reachableFolders, reachableFiles))

            fun fileRow(f: FileWithLocalState) = FileRow(f, nameOf[f.file.createdBy])
            val folderMissing = folderId != null && !FilesTree.isReachable(folderId, index.folders)
            val hits =
                if (query.isBlank()) {
                    null
                } else {
                    reachableFolders
                        .filter {
                            FilesTree.matches(
                                query,
                                it.name,
                            )
                        }.map { SearchHit(folder = folderRow(it), parentFolderId = it.parentId) } +
                        reachableFiles
                            .filter {
                                FilesTree.matches(
                                    query,
                                    it.file.name,
                                )
                            }.map { SearchHit(file = fileRow(it), parentFolderId = it.file.folderId) }
                }
            return FilesUiState(
                loading = false,
                businessId = index.businessId,
                folderId = folderId,
                breadcrumbs = FilesTree.breadcrumbs(folderId, index.folders),
                folders = reachableFolders.filter { it.parentId == folderId }.sortedBy { it.name.lowercase() }.map(::folderRow),
                files = reachableFiles.filter { it.file.folderId == folderId }.sortedByDescending { it.file.createdAt }.map(::fileRow),
                query = query,
                searchHits = hits,
                gridView = grid,
                canUpload = perms.upload,
                canManageFolders = perms.manage,
                canDelete = perms.delete,
                isOwner = perms.owner,
                folderMissing = folderMissing,
                googleLinked = link is GoogleLinkState.Linked,
                googleConfigured = link !is GoogleLinkState.NotConfigured,
                uploadsInFlight = inFlight,
                allFolders = reachableFolders,
                allFiles = reachableFiles,
            )
        }

        // ------------------------------------------------------------ navigation

        fun openFolder(id: String?) {
            query.value = ""
            folderId.value = id
            savedStateHandle[KEY_FOLDER] = id
        }

        /** Up one level; false when already at the top (the caller lets system back proceed). */
        fun navigateUp(): Boolean {
            val current = folderId.value ?: return false
            val parent =
                uiState.value.breadcrumbs
                    .lastOrNull { it.id == current }
                    ?.parentId
            openFolder(parent)
            return true
        }

        fun onQueryChange(value: String) {
            query.value = value
        }

        fun setGridView(grid: Boolean) {
            viewModelScope.launch { viewPreferences.setGridView(grid) }
        }

        // ------------------------------------------------------------ folders

        /** Validation for the create/rename dialog against LIVE siblings (design D12). */
        fun validateFolderName(
            name: String,
            excludeFolderId: String? = null,
        ): FolderNameError? = FilesTree.validateFolderName(name, folderId.value, uiState.value.allFolders, excludeFolderId)

        /** Whether another level may be created here (design D13, depth ≤ 10). */
        fun canCreateSubfolderHere(): Boolean = FilesTree.depth(folderId.value, uiState.value.allFolders) < FileItem.MAX_FOLDER_DEPTH

        fun createFolder(name: String) {
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val userId = session.userId() ?: return@launch
                val trimmed = name.trim()
                if (validateFolderName(trimmed) != null || !canCreateSubfolderHere()) return@launch
                val now = clock.instant()
                repository.saveFolder(
                    Folder(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        parentId = folderId.value,
                        name = trimmed,
                        restricted = false,
                        createdBy = userId,
                        updatedBy = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                _events.tryEmit(FilesEvent.FolderCreated(trimmed))
            }
        }

        fun renameFolder(
            folder: Folder,
            name: String,
        ) {
            viewModelScope.launch {
                val trimmed = name.trim()
                if (FilesTree.validateFolderName(trimmed, folder.parentId, uiState.value.allFolders, excludeFolderId = folder.id) !=
                    null
                ) {
                    return@launch
                }
                val now = clock.instant()
                repository.saveFolder(folder.copy(name = trimmed, updatedBy = session.userId(), updatedAt = now))
                _events.tryEmit(FilesEvent.FolderRenamed)
            }
        }

        /** Live descendants of [folder] — the delete-confirm count. */
        fun subtreeCount(folder: Folder): Int = FilesTree.subtreeCount(folder.id, uiState.value.allFolders, uiState.value.allFiles)

        fun deleteFolder(folder: Folder) {
            viewModelScope.launch {
                val removed = repository.deleteFolderTree(folder.id)
                if (folderId.value != null &&
                    !FilesTree.isReachable(folderId.value, uiState.value.allFolders.filterNot { it.id == folder.id })
                ) {
                    openFolder(folder.parentId)
                }
                _events.tryEmit(FilesEvent.FolderDeleted)
                removed.forEach { cleanupRemoved(it) }
            }
        }

        // ------------------------------------------------------------ files

        fun deleteFile(row: FileWithLocalState) {
            viewModelScope.launch {
                val removed = repository.deleteFile(row.file.id) ?: return@launch
                _events.tryEmit(FilesEvent.FileDeleted)
                cleanupRemoved(removed)
            }
        }

        /** Best-effort Drive delete (design D10) + local copy cleanup; never fails the tombstone. */
        private suspend fun cleanupRemoved(row: FileWithLocalState) {
            contentResolver.deleteLocalCopy(row)
            row.file.driveFileId?.let { runCatching { driveDeleter.deleteBestEffort(it) } }
        }

        /** Tap: images → in-app viewer; anything else → the Drive viewer page (design D16). */
        fun openFile(row: FileRow) {
            val file = row.file
            if (file.isImage) {
                resolveThen(row.row) { _events.tryEmit(FilesEvent.OpenImage(it, row.row)) }
            } else {
                openInDrive(row)
            }
        }

        fun openInDrive(row: FileRow) {
            val driveId = row.file.driveFileId
            if (driveId == null) {
                // Not uploaded yet: the staged original is on this device — show it if it is an image.
                if (row.file.isImage) openFile(row) else _events.tryEmit(FilesEvent.OpenFailed)
                return
            }
            _events.tryEmit(FilesEvent.OpenUrl(FileItem.viewUrl(driveId)))
        }

        fun copyLink(row: FileRow) {
            val driveId = row.file.driveFileId
            if (driveId == null) {
                _events.tryEmit(FilesEvent.OpenFailed)
                return
            }
            _events.tryEmit(FilesEvent.CopyLink(FileItem.viewUrl(driveId)))
        }

        fun download(row: FileRow) {
            resolveThen(row.row) { _events.tryEmit(FilesEvent.Download(it, row.row)) }
        }

        private fun resolveThen(
            row: FileWithLocalState,
            onReady: (File) -> Unit,
        ) {
            viewModelScope.launch {
                when (val result = contentResolver.resolve(row)) {
                    is FileOpenResult.Ready -> onReady(result.file)
                    FileOpenResult.Failed -> _events.tryEmit(FilesEvent.OpenFailed)
                }
            }
        }

        // ------------------------------------------------------------ uploads

        /**
         * Upload tapped (design D8): linked or unconfigured → picker; configured-but-
         * unlinked → the Connect Google Drive prompt first (Connect links then opens
         * the picker; Not now stages locally with the pending hint).
         */
        fun onUploadTapped() {
            viewModelScope.launch {
                when (linkState.first()) {
                    is GoogleLinkState.NotLinked -> _events.tryEmit(FilesEvent.PromptGoogleLink)
                    else -> _events.tryEmit(FilesEvent.OpenPicker)
                }
            }
        }

        fun onUploadPicked(uris: List<Uri>) {
            if (uris.isEmpty()) return
            stage(uploadIntake.describe(uris), folderId.value, fromShare = false)
        }

        /** Share sheet → Save to Files: stage the parked payload into [targetFolderId] (design D18). */
        fun saveSharedFiles(targetFolderId: String?) {
            val files = shareIntakeHolder.consume()
            if (files.isEmpty()) return
            stage(files, targetFolderId, fromShare = true)
        }

        fun peekSharedFiles(): List<SharedFile> = shareIntakeHolder.peek()

        fun discardSharedFiles() = shareIntakeHolder.clear()

        private fun stage(
            files: List<SharedFile>,
            targetFolderId: String?,
            fromShare: Boolean,
        ) {
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val userId = session.userId() ?: return@launch
                uploadsInFlight.value += 1
                try {
                    val result = uploadIntake.stage(businessId, userId, targetFolderId, files)
                    if (result.tooMany) _events.tryEmit(FilesEvent.UploadTooMany(FileItem.MAX_BATCH))
                    result.tooLarge.forEach { _events.tryEmit(FilesEvent.UploadTooLarge(it)) }
                    result.unreadable.forEach { _events.tryEmit(FilesEvent.UploadFailed(it)) }
                    if (result.staged.isNotEmpty()) {
                        val link = linkState.first()
                        if (fromShare) {
                            _events.tryEmit(FilesEvent.SharedSaved(result.staged.size))
                        } else {
                            _events.tryEmit(
                                FilesEvent.UploadStaged(
                                    count = result.staged.size,
                                    queuedOffline = true,
                                    pendingUnlinked = link is GoogleLinkState.NotLinked,
                                ),
                            )
                        }
                    }
                } finally {
                    uploadsInFlight.value -= 1
                }
            }
        }

        // ------------------------------------------------------------ google link

        /** Connect (link prompt): run the ADR-049 link flow, then open the picker. */
        fun linkGoogle(activityContext: Context) {
            viewModelScope.launch {
                googleAccountLinker
                    .link(activityContext)
                    .onSuccess { _events.tryEmit(FilesEvent.OpenPicker) }
                    .onFailure(::handleLinkFailure)
            }
        }

        fun completeGoogleConsent(resultIntent: Intent?) {
            viewModelScope.launch {
                googleAccountLinker
                    .completeLink(resultIntent)
                    .onSuccess { _events.tryEmit(FilesEvent.OpenPicker) }
                    .onFailure(::handleLinkFailure)
            }
        }

        private fun handleLinkFailure(error: Throwable) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent -> _events.tryEmit(FilesEvent.NeedsConsent(error.pendingIntent))
                is GoogleLinkException.Cancelled -> Unit
                else -> _events.tryEmit(FilesEvent.LinkFailed)
            }
        }

        // ------------------------------------------------------------ access editor (owner)

        fun openAccessEditor(folder: Folder) {
            viewModelScope.launch {
                val businessId = session.businessId() ?: return@launch
                val members =
                    memberRepository
                        .membersForBusiness(businessId)
                        .first()
                        .filter { !it.isOwner && it.deletedAt == null && it.status != MemberStatus.REVOKED }
                val live =
                    repository
                        .folderAccessRows(folder.id)
                        .filter { it.deletedAt == null }
                        .map { it.memberId }
                        .toSet()
                _accessEditor.value = AccessEditorState(folder, folder.restricted, members, live)
            }
        }

        fun setAccessRestricted(restricted: Boolean) {
            _accessEditor.value = _accessEditor.value?.copy(restricted = restricted)
        }

        fun toggleAccessMember(memberId: String) {
            val current = _accessEditor.value ?: return
            val next =
                if (memberId in
                    current.selectedMemberIds
                ) {
                    current.selectedMemberIds - memberId
                } else {
                    current.selectedMemberIds + memberId
                }
            _accessEditor.value = current.copy(selectedMemberIds = next)
        }

        fun dismissAccessEditor() {
            _accessEditor.value = null
        }

        /** Saves `folders.restricted` + the `folder_access` diff as soft-link upserts (design D6/D19). */
        fun saveAccess() {
            val editor = _accessEditor.value ?: return
            _accessEditor.value = editor.copy(saving = true)
            viewModelScope.launch {
                val now = clock.instant()
                val userId = session.userId()
                if (editor.folder.restricted != editor.restricted) {
                    repository.saveFolder(editor.folder.copy(restricted = editor.restricted, updatedBy = userId, updatedAt = now))
                }
                val existing = repository.folderAccessRows(editor.folder.id).associateBy { it.memberId }
                val wanted = if (editor.restricted) editor.selectedMemberIds else emptySet()
                for (member in editor.members) {
                    val row = existing[member.id]
                    val isLive = row != null && row.deletedAt == null
                    when {
                        member.id in wanted && !isLive ->
                            repository.saveFolderAccess(
                                row?.copy(deletedAt = null, updatedAt = now)
                                    ?: FolderAccess(editor.folder.id, member.id, editor.folder.businessId, now, now, null),
                            )
                        member.id !in wanted && isLive ->
                            repository.saveFolderAccess(row!!.copy(deletedAt = now, updatedAt = now))
                    }
                }
                _accessEditor.value = null
                _events.tryEmit(FilesEvent.AccessSaved)
            }
        }

        private companion object {
            const val KEY_FOLDER = "files_folder_id"
        }
    }
