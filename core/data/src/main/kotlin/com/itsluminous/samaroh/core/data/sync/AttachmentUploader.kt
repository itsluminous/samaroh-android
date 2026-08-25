package com.itsluminous.samaroh.core.data.sync

/*
 * Attachment upload queue contract (spec §8: "Attachments queue file paths for upload
 * before the row op") — ADDITIVE W1-E addition (docs/decisions.md ADR-008).
 *
 * Expense-attachment files live in Google Drive (the authoritative store, §4.2); only the
 * metadata row syncs to Postgres. The sync engine (core:sync) drains the outbox FIFO and,
 * for an `expense_attachments` upsert whose `drive_file_id` is still null, asks this
 * uploader to move the locally cached file to Drive FIRST, then pushes the metadata row
 * with the returned file id. Implemented by `core:google` (W1-F) as an OPTIONAL Hilt
 * binding — while unbound (or the Google account is unlinked) attachment ops stay queued
 * with a visible pending state.
 */
interface AttachmentUploader {
    sealed interface UploadResult {
        /** File landed in Drive; the metadata row can now be pushed. */
        data class Uploaded(
            val driveFileId: String,
        ) : UploadResult

        /** No linked Google account yet — keep the op queued with a pending badge (§4.2). */
        data object NotLinked : UploadResult

        /** Upload failed. [retriable] network-ish failures stay queued; others mark the item error. */
        data class Failed(
            val message: String,
            val retriable: Boolean,
        ) : UploadResult
    }

    /**
     * Uploads the locally cached file of the attachment row [attachmentId]
     * (`expense_attachments.id`; the device cache path is Room-only state).
     */
    suspend fun upload(attachmentId: String): UploadResult
}
