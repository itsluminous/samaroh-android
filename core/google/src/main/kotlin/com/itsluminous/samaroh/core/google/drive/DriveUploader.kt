package com.itsluminous.samaroh.core.google.drive

import java.io.File

/** A file that landed in Drive. [fileId] is what synced rows store (e.g. `expense_attachments.drive_file_id`). */
data class DriveFileRef(
    val fileId: String,
    val fileName: String,
)

/**
 * Uploads app files into the business's Drive tree per the §9.1 layout.
 *
 * NOTE FOR THE INTEGRATOR: Wave 0 / W1-B shipped no `AttachmentUploadQueue` contract in
 * `core:data`, so W1-F defines this additive interface here instead (task contract). If a
 * queue contract lands later, its implementation should delegate to this uploader.
 *
 * Implementations must be safe to call from WorkManager workers (suspending, no UI) and
 * must fail with a normal [Result.failure] when Google is not configured / not linked —
 * callers keep the item pending and retry later (§4.2 outbox behavior).
 */
interface DriveUploader {
    /**
     * Ensures the `Samaroh/{business}/…` folder chain exists (root folder id cached in
     * `google_accounts.drive_root_folder_id`) and uploads [sourceFile] into it.
     */
    suspend fun upload(
        businessName: String,
        target: DriveTarget,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): Result<DriveFileRef>
}

/** Raised when an upload cannot even start because no usable Google account is available. */
class DriveNotAvailableException(
    reason: String,
) : Exception(reason)
