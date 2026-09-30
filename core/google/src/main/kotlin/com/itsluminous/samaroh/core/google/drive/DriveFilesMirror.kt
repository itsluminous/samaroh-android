package com.itsluminous.samaroh.core.google.drive

import android.util.Log
import com.itsluminous.samaroh.core.data.sync.FilesDriveMirror
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort Drive mirror of Files-module renames/moves (ADR-090, design D14 revised) —
 * the [DriveFilesDeleter] posture: runs only when a Google account is linked, never
 * throws, 403/404 (bytes or mirror folder not in THIS account) are logged at info and
 * swallowed. The metadata row is the contract; this only keeps the human-readable
 * `Samaroh/{Business}/files/…` tree tidy for people browsing Drive.
 *
 * Folder mirrors are located FIND-ONLY by their old name chain (a folder no member ever
 * uploaded into has no mirror — nothing to do); destinations are find-or-create so a moved
 * item always lands somewhere sensible.
 */
@Singleton
class DriveFilesMirror
    @Inject
    constructor(
        private val driveService: DriveService,
        private val folderResolver: DriveFolderResolver,
        private val googleAccountLinker: GoogleAccountLinker,
    ) : FilesDriveMirror {
        override suspend fun renameFile(
            driveFileId: String,
            newName: String,
        ) = bestEffort("rename file $driveFileId") {
            driveService.updateFile(driveFileId, name = newName)
        }

        override suspend fun moveFile(
            driveFileId: String,
            businessName: String,
            newFolderPath: List<String>,
        ) = bestEffort("move file $driveFileId") {
            val destination =
                folderResolver.resolveFolderId(businessName, DriveTarget.Files(newFolderPath), create = true) ?: return@bestEffort
            reparent(driveFileId, destination)
        }

        override suspend fun renameFolder(
            businessName: String,
            folderPath: List<String>,
            newName: String,
        ) = bestEffort("rename folder ${folderPath.joinToString("/")}") {
            val folderId = folderResolver.resolveFolderId(businessName, DriveTarget.Files(folderPath), create = false) ?: return@bestEffort
            driveService.updateFile(folderId, name = newName)
        }

        override suspend fun moveFolder(
            businessName: String,
            folderPath: List<String>,
            newParentPath: List<String>,
        ) = bestEffort("move folder ${folderPath.joinToString("/")}") {
            val folderId = folderResolver.resolveFolderId(businessName, DriveTarget.Files(folderPath), create = false) ?: return@bestEffort
            val destination =
                folderResolver.resolveFolderId(businessName, DriveTarget.Files(newParentPath), create = true) ?: return@bestEffort
            if (destination == folderId) return@bestEffort
            reparent(folderId, destination)
        }

        private suspend fun reparent(
            id: String,
            destination: String,
        ) {
            val current = driveService.fileParents(id)
            if (current == listOf(destination)) return
            driveService.updateFile(id, addParentId = destination, removeParentIds = current - destination)
        }

        private suspend fun bestEffort(
            what: String,
            block: suspend () -> Unit,
        ) {
            if (googleAccountLinker.linkState.first() !is GoogleLinkState.Linked) return
            runCatching { block() }
                .onFailure { error ->
                    when {
                        error is GoogleApiException && error.code in setOf(403, 404) ->
                            Log.i(TAG, "drive mirror skipped ($what): not ours (http ${error.code})")
                        else -> Log.w(TAG, "best-effort drive mirror failed ($what): ${error.message}")
                    }
                }
        }

        private companion object {
            const val TAG = "SamarohFiles"
        }
    }
