package com.itsluminous.samaroh.core.data.sync

/**
 * FILES-module upload seam (ADR-085) — the [AttachmentUploader] shape for `files` rows.
 *
 * File bytes live in the uploader's Google Drive; only the metadata row syncs. The sync
 * engine drains the outbox FIFO and, for a `files` UPSERT whose `drive_file_id` is still
 * null, asks this uploader to move the staged local copy to Drive FIRST, then pushes the
 * row with the returned id (the server column is NOT NULL). Implemented by `core:google`
 * as an OPTIONAL Hilt binding — while unbound (or the Google account is unlinked) the
 * op stays queued with the `files.file.pending_label` badge.
 */
interface FilesUploader {
    sealed interface UploadResult {
        /** File landed in Drive (shared anyone-with-link best-effort); the row can now be pushed. */
        data class Uploaded(
            val driveFileId: String,
        ) : UploadResult

        /** No linked Google account yet — keep the op queued with a pending badge. */
        data object NotLinked : UploadResult

        /**
         * The staged row was tombstoned (or vanished) BEFORE it ever reached Drive: there
         * is nothing to push — the engine drops the op silently instead of erroring.
         */
        data object Obsolete : UploadResult

        /** Upload failed. [retriable] network-ish failures stay queued; others mark the item error. */
        data class Failed(
            val message: String,
            val retriable: Boolean,
        ) : UploadResult
    }

    /** Uploads the staged local copy of the `files` row [fileId]. */
    suspend fun upload(fileId: String): UploadResult
}

/**
 * Best-effort Drive removal for a tombstoned Files-module row (design D10, ADR-053/084):
 * the metadata tombstone is authoritative and syncs; the Drive delete only runs when
 * Google is linked, is non-fatal and swallows 403/404 (another member's file). Bound by
 * `core:google`; features call it after the repository tombstone.
 */
fun interface FilesDriveDeleter {
    suspend fun deleteBestEffort(driveFileId: String)
}

/**
 * Best-effort Drive mirror of a Files-module RENAME / MOVE (ADR-090, design D14 revised):
 * the metadata row (name / folder_id / parent_id) is authoritative and syncs through the
 * outbox; this seam only keeps the human-readable `Samaroh/{Business}/files/…` tree in
 * the actor's Drive in step. Every method is non-fatal: it runs only when Google is
 * linked, never throws, and swallows 403/404 (bytes owned by another member's account,
 * or a mirror folder that was never created). Paths are root-first folder NAMES below
 * `files/` (empty = top level) — the same segments `DriveTarget.Files` takes. Bound by
 * `core:google`.
 */
interface FilesDriveMirror {
    /** `files.update` of the Drive file's `name`. */
    suspend fun renameFile(
        driveFileId: String,
        newName: String,
    )

    /** `files.update` with `addParents`/`removeParents` onto the find-or-create mirror chain of [newFolderPath]. */
    suspend fun moveFile(
        driveFileId: String,
        businessName: String,
        newFolderPath: List<String>,
    )

    /** Renames the mirror folder at [folderPath] (find-only; a missing mirror is a no-op). */
    suspend fun renameFolder(
        businessName: String,
        folderPath: List<String>,
        newName: String,
    )

    /** Re-parents the mirror folder at [folderPath] under the find-or-create chain of [newParentPath]. */
    suspend fun moveFolder(
        businessName: String,
        folderPath: List<String>,
        newParentPath: List<String>,
    )
}
