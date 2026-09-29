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
